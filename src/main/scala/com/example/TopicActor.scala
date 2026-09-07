package com.example

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.scaladsl.{ BroadcastHub, Keep, Source }
import org.apache.pekko.stream.{ OverflowStrategy, QueueOfferResult }
import org.apache.pekko.stream.Materializer
import org.apache.pekko.NotUsed
import com.example.FlowMessage._
import com.typesafe.scalalogging.LazyLogging
import scala.util.{ Failure, Success }
import scala.concurrent.duration._

/**
 * A dedicated fan-out actor for a single Kafka topic.
 *
 * Architecture:
 *   producers ──▶ Source.queue ──▶ BroadcastHub ──▶ N gRPC subscribers
 *
 * - Actor mailbox: bounded via `stream-router.topic-actor-mailbox` (capacity =
 *   `topic-actor-buffer-size`, push-timeout = 0s).  When the mailbox is full,
 *   `Publish` messages are dropped to dead letters by the Pekko dispatcher
 *   before they even reach this actor.  The RouterActor is never blocked.
 *
 * - Source.queue (bufferSize = 1, dropHead): a second drop boundary inside the
 *   actor.  `queue.offer` is non-blocking; if the queue is busy the oldest
 *   held message is evicted.  The offer result is matched explicitly so every
 *   discard is logged at WARN rather than silently discarded.
 *
 * - BroadcastHub.sink: any number of downstream consumers (gRPC streams) can
 *   each independently materialize `hubSource`; each gets its own cursor.
 *
 * Idle Timeout:
 *   When there are NO active gRPC subscribers AND no data has arrived for 15
 *   seconds, the actor stops itself — freeing the mailbox, queue, and hub.
 *   The RouterActor catches the Terminated signal and removes the topic from
 *   knownTopics / the Receptionist, so ListTopics no longer advertises it.
 *
 *   If subscribers ARE connected, the timeout is cancelled; the actor stays
 *   alive indefinitely so live consumers are never dropped.  The 15-second
 *   clock re-arms only after the last subscriber disconnects.
 *
 *   When data resumes for a stopped topic, RouterActor lazily re-spawns a
 *   fresh TopicActor and re-registers it — transparent to new subscribers.
 *
 * Messages:
 *   Publish(msg)       — RouterActor pushes a RawMessage into the hub.
 *   Subscribe(replyTo) — gRPC layer requests the shared `Source[RawMessage, NotUsed]`.
 *   SubscriberLeft     — GrpcStreamService notifies that a subscriber stream ended.
 *   IdleTimeout        — Internal: fired after 15 s with zero subscribers.
 */
object TopicActor extends LazyLogging {

  private val idleTimeout = 15.seconds

  def apply(topic: String): Behavior[TopicHubCommand] =
    Behaviors.setup { context =>
      implicit val system      = context.system
      implicit val materializer: Materializer = Materializer(system)

      logger.debug(s"[TopicActor-$topic] Starting up. Creating Queue→BroadcastHub pipeline.")

      // Arm the idle timer immediately — no subscribers yet.
      context.setReceiveTimeout(idleTimeout, IdleTimeout)

      // To strictly avoid buffering old data, we use the absolute minimal buffer sizes.
      // queueBufferSize = 1: Drops the very last message immediately if the hub is busy.
      // hubBufferSize = 2: Minimum power-of-2 allowed by BroadcastHub.
      val queueBufferSize = 1
      val hubBufferSize = 2

      val (queue, hubSource) = Source.queue[RawMessage](queueBufferSize, overflowStrategy = OverflowStrategy.dropHead)
        .toMat(BroadcastHub.sink[RawMessage](bufferSize = hubBufferSize))(Keep.both)
        .run()

      logger.debug(s"[TopicActor-$topic] Hub pipeline running.")

      // Capture for use inside the Future callback (avoids closing over `context`).
      implicit val ec = context.executionContext

      // Track active gRPC subscribers so we know when it's safe to idle-stop.
      var subscriberCount = 0

      Behaviors.receiveMessage[TopicHubCommand] {

        case Publish(msg) =>
          // Offer the message to the stream queue without materializing a new
          // stream.  The returned Future is matched so every drop is observable
          // in logs rather than silently discarded.
          // Pekko automatically resets the receive-timeout on every message,
          // so this Publish also resets the 15-second idle clock (when armed).
          queue.offer(msg).onComplete {
            case Success(QueueOfferResult.Enqueued)         => // normal path
            case Success(QueueOfferResult.Dropped)          =>
              logger.warn(
                s"[TopicActor-$topic] queue.offer dropped a message (dropHead). " +
                "Hub buffer full — likely no active subscribers or subscriber too slow.")
            case Success(QueueOfferResult.QueueClosed)      =>
              logger.error(s"[TopicActor-$topic] queue.offer rejected: queue closed (stream shut down).")
            case Success(QueueOfferResult.Failure(ex))      =>
              logger.error(s"[TopicActor-$topic] queue.offer failed: ${ex.getMessage}", ex)
            case Failure(ex)                                =>
              logger.error(s"[TopicActor-$topic] queue.offer threw: ${ex.getMessage}", ex)
          }
          Behaviors.same

        case Subscribe(replyTo) =>
          // The BroadcastHub source is reusable: each materialisation is an
          // independent subscriber cursor.
          subscriberCount += 1
          logger.info(s"[TopicActor-$topic] New subscriber registered (total: $subscriberCount). Cancelling idle timeout.")
          // Cancel the idle timer while at least one subscriber is connected —
          // we must never drop an active consumer.
          context.cancelReceiveTimeout()
          replyTo ! hubSource
          Behaviors.same

        case SubscriberLeft =>
          subscriberCount = math.max(0, subscriberCount - 1)
          if (subscriberCount == 0) {
            logger.info(s"[TopicActor-$topic] Last subscriber disconnected. Arming 15-second idle timeout.")
            context.setReceiveTimeout(idleTimeout, IdleTimeout)
          } else {
            logger.info(s"[TopicActor-$topic] Subscriber disconnected (remaining: $subscriberCount).")
          }
          Behaviors.same

        case IdleTimeout =>
          // Guard: only stop if truly no subscribers remain (defensive check
          // in case a stale timeout fires after a concurrent Subscribe).
          if (subscriberCount == 0) {
            logger.info(s"[TopicActor-$topic] Idle for ${idleTimeout.toSeconds} seconds with no subscribers — stopping to free resources.")
            Behaviors.stopped
          } else {
            logger.warn(s"[TopicActor-$topic] IdleTimeout fired but $subscriberCount subscriber(s) still active — ignoring.")
            context.cancelReceiveTimeout()
            Behaviors.same
          }
      }
    }
}
