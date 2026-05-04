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
 * Messages:
 *   Publish(msg)       — RouterActor pushes a RawMessage into the hub.
 *   Subscribe(replyTo) — gRPC layer requests the shared `Source[RawMessage, NotUsed]`.
 */
object TopicActor extends LazyLogging {

  def apply(topic: String): Behavior[TopicHubCommand] =
    Behaviors.setup { context =>
      implicit val system      = context.system
      implicit val materializer: Materializer = Materializer(system)

      logger.info(s"[TopicActor-$topic] Starting up. Creating Queue→BroadcastHub pipeline.")

      // To strictly avoid buffering old data, we use the absolute minimal buffer sizes.
      // queueBufferSize = 1: Drops the very last message immediately if the hub is busy.
      // hubBufferSize = 2: Minimum power-of-2 allowed by BroadcastHub.
      val queueBufferSize = 1
      val hubBufferSize = 2

      val (queue, hubSource) = Source.queue[RawMessage](queueBufferSize, overflowStrategy = OverflowStrategy.dropHead)
        .toMat(BroadcastHub.sink[RawMessage](bufferSize = hubBufferSize))(Keep.both)
        .run()

      logger.info(s"[TopicActor-$topic] Hub pipeline running.")

      // Capture for use inside the Future callback (avoids closing over `context`).
      implicit val ec = context.executionContext

      Behaviors.receiveMessage[TopicHubCommand] {
        case Publish(msg) =>
          // Offer the message to the stream queue without materializing a new
          // stream.  The returned Future is matched so every drop is observable
          // in logs rather than silently discarded.
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
          logger.info(s"[TopicActor-$topic] New subscriber registered.")
          replyTo ! hubSource
          Behaviors.same
      }
    }
}
