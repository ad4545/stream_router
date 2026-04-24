package com.example

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.scaladsl.{ BroadcastHub, Keep, MergeHub, Sink, Source }
import org.apache.pekko.stream.Materializer
import org.apache.pekko.NotUsed
import com.example.FlowMessage._
import com.typesafe.scalalogging.LazyLogging

/**
 * A dedicated fan-out actor for a single Kafka topic.
 *
 * Architecture:
 *   producers ──▶ MergeHub ──▶ BroadcastHub ──▶ N gRPC subscribers
 *
 * - MergeHub.source:  accepts concurrent `Source.single(msg).runWith(sink)` pushes from
 *                     RouterActor (or any other producer) without race conditions.
 * - BroadcastHub.sink: any number of downstream consumers (gRPC streams) can each
 *                      independently materialize `hubSource`; each gets its own cursor.
 *
 * Messages:
 *   Publish(msg)      — RouterActor pushes a RawMessage into the hub.
 *   Subscribe(replyTo) — gRPC layer requests the shared `Source[RawMessage, NotUsed]`.
 */
object TopicActor extends LazyLogging {

  def apply(topic: String): Behavior[TopicHubCommand] =
    Behaviors.setup { context =>
      implicit val system      = context.system
      implicit val materializer: Materializer = Materializer(system)

      logger.info(s"[TopicActor-$topic] Starting up. Creating MergeHub→BroadcastHub pipeline.")

      // MergeHub: fan-in from many concurrent publishers (thread-safe).
      // BroadcastHub: fan-out to many independent subscribers.
      val bufferSize = system.settings.config
        .getInt("stream-router.topic-actor-buffer-size")

      val (mergeSink, hubSource): (Sink[RawMessage, NotUsed], Source[RawMessage, NotUsed]) =
        MergeHub.source[RawMessage](perProducerBufferSize = 16)
          .toMat(BroadcastHub.sink[RawMessage](bufferSize = bufferSize))(Keep.both)
          .run()

      logger.info(s"[TopicActor-$topic] Hub pipeline running.")

      Behaviors.receiveMessage[TopicHubCommand] {
        case Publish(msg) =>
          // Push a single message into the MergeHub. Each push is an independent
          // stream materialisation — safe for concurrent callers.
          Source.single(msg).runWith(mergeSink)
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
