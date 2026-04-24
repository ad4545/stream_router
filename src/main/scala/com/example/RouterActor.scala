package com.example

import org.apache.pekko.actor.typed.{ ActorRef, Behavior, Terminated }
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
 */
object RouterActor extends LazyLogging {

  /** Shared topic registry — written by RouterActor, read by GrpcStreamService. */
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
                    val child = context.spawn(TopicActor(topic), s"topic-$topic")
                    context.watch(child) // detect crashes
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
          }
          Behaviors.same
      }
    }
}
