package com.example

import org.apache.pekko.actor.typed.{ ActorRef, Behavior, Terminated, MailboxSelector }
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.receptionist.Receptionist
import com.example.StreamToActorMessaging._
import com.example.FlowMessage._
import com.typesafe.scalalogging.LazyLogging

import scala.collection.mutable

/**
 * Ingestion gateway actor — the single Receptionist entry point for stream_handler.
 *
 * Registered under `RouterKey` (matching the stream_handler's existing send target).
 *
 * Responsibilities:
 *  - Accept `StreamToActorMessage[FlowMessage]` from stream_handler subscribers.
 *  - On first message for an unseen topic, spawn a child `TopicActor`, register it
 *    under `topicHubKey(topic)`, and record it in `topicActors`.
 *  - Forward each `RawMessage` to the correct `TopicActor` via `Publish`.
 *  - Watch each child and remove it from `topicActors` on `Terminated` so it can
 *    be transparently re-created on the next message for that topic.
 *  - Maintain `knownTopics` for the `ListTopics` gRPC call.
 *
 * Flow-control note:
 *  RouterActor sends `StreamAck` back to the stream_handler immediately after
 *  forwarding `Publish` to the TopicActor — it does NOT wait for the TopicActor
 *  to confirm processing.  This keeps throughput high but means backpressure is
 *  decoupled from actual TopicActor consumption.
 *
 *  To prevent unbounded mailbox growth at the TopicActor, every TopicActor child
 *  is spawned with a bounded mailbox (`stream-router.topic-actor-mailbox` in
 *  application.conf, capacity = `topic-actor-buffer-size`, push-timeout = 0s).
 *  When the mailbox is full, excess `Publish` messages are dropped to dead letters
 *  immediately — the RouterActor is never blocked.  Dead-letter logging provides
 *  operator visibility (controlled by `pekko.log-dead-letters`).
 */
object RouterActor extends LazyLogging {

  /**
   * Shared topic registry — written exclusively by RouterActor, read lock-free
   * by GrpcStreamService (ListTopics).
   *
   * Concurrency contract:
   *  - RouterActor is the SOLE writer (add on first-seen, remove on Terminated).
   *    Both writes happen in the actor's single-threaded mailbox, so they are
   *    never concurrent with each other.
   *  - ConcurrentHashMap.KeySet is safe for concurrent reads from any thread.
   *
   * Race-condition note (documented deliberately):
   *  A ListTopics call that arrives between the moment a TopicActor is
   *  deregistered from the Receptionist and the moment its Terminated signal is
   *  processed by RouterActor will still see the topic in knownTopics.
   *  In that narrow window the topic is removed from the Receptionist listing
   *  but not yet from knownTopics, so a client could receive a topic name for
   *  which SubscribeToTopic would temporarily return NOT_FOUND and trigger the
   *  exponential-backoff retry loop.
   *
   *  This window is bounded by a single actor-mailbox scheduling quantum
   *  (typically < 1 ms on a healthy JVM).  The retry backoff absorbs it
   *  gracefully, so no corrective action is taken in the hot path.  If the
   *  actor is genuinely gone (crash, not transient restart) the retries will
   *  exhaust and the client receives a clean NOT_FOUND — no silent data loss.
   */
  val knownTopics: java.util.Set[String] =
    java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  def apply(): Behavior[StreamToActorMessage[FlowMessage]] =
    Behaviors.setup { context =>

      // Mutable registry: topic → dedicated TopicActor ref
      val topicActors: mutable.Map[String, ActorRef[TopicHubCommand]] = mutable.Map.empty

      var messageCounter: Long = 0L

      logger.info("[RouterActor] Initialized. Waiting for ingestion messages.")

      Behaviors.receiveMessage[StreamToActorMessage[FlowMessage]] { msg =>
        try {
          msg match {

            // ── Ingestion protocol ────────────────────────────────────────────
            case StreamInit(replyTo) =>
              logger.info(s"[RouterActor] StreamInit from ${replyTo.path}")
              replyTo ! StreamAck
              Behaviors.same

            case StreamElementIn(element, replyTo) =>
              messageCounter += 1
              element match {
                case raw: RawMessage =>
                  val topic = raw.topic

                  // ── Lazy TopicActor spawning ──────────────────────────────
                  val topicRef = topicActors.getOrElseUpdate(topic, {
                    logger.info(s"[RouterActor] First message for topic '$topic' — spawning TopicActor.")
                    // Spawn with a bounded mailbox so the actor's message queue
                    // cannot grow without limit when ingest outpaces consumption
                    // (e.g. zero gRPC subscribers).  Overflow → dead letters (0s
                    // push-timeout), RouterActor is never blocked.
                    val mailbox = MailboxSelector.fromConfig("stream-router.topic-actor-mailbox")
                    val child   = context.spawn(TopicActor(topic), s"topic-$topic", mailbox)
                    context.watch(child) // detect crashes / termination
                    context.system.receptionist ! Receptionist.Register(topicHubKey(topic), child)
                    knownTopics.add(topic)
                    child
                  })

                  topicRef ! Publish(raw)

                  if (messageCounter % 500 == 0)
                    logger.info(s"[RouterActor] Forwarded $messageCounter messages across ${topicActors.size} topic(s).")

                case other =>
                  logger.warn(s"[RouterActor] Unexpected FlowMessage subtype: ${other.getClass.getSimpleName}")
              }
              replyTo ! StreamAck
              Behaviors.same

            case StreamFailed(cause) =>
              logger.error(s"[RouterActor] Input stream failed: $cause")
              Behaviors.same

            case StreamCompleted =>
              logger.info("[RouterActor] Input stream completed.")
              Behaviors.same

            case _ =>
              Behaviors.same
          }
        } catch {
          case ex: Throwable =>
            logger.error(s"[RouterActor] Fatal error in processing: ${ex.getMessage}", ex)
            // Rethrow so supervision can catch it and restart the actor
            throw ex
        }
      }.receiveSignal {
        case (ctx, Terminated(ref)) =>
          topicActors.find { case (_, v) => v == ref }.foreach { case (topic, _) =>
            logger.warn(s"[RouterActor] TopicActor for '$topic' terminated — removing from registry.")
            topicActors.remove(topic)
            // Remove from the shared read-set so ListTopics stops advertising
            // a topic that no longer has an active actor.  This write happens
            // in the same single-threaded actor mailbox as the add, so there
            // is no concurrent-modification risk on the writer side.
            knownTopics.remove(topic)
          }
          Behaviors.same
      }
    }
}
