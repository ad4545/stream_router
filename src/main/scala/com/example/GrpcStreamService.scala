package com.example

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.{ ActorRef, ActorSystem }
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import com.example.grpc._
import com.example.FlowMessage._
import scala.concurrent.{ ExecutionContext, Future }
import org.apache.pekko.actor.typed.receptionist.Receptionist
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.util.Timeout
import scala.concurrent.duration._
import io.grpc.{ Status, StatusException, StatusRuntimeException }

/**
 * gRPC service implementation for StreamRouter.
 *
 * SubscribeToTopic flow:
 *  1. Query the Receptionist for the topicHubKey of the requested topic.
 *  2. If no actors are registered yet (topic not yet seen by RouterActor), retry
 *     up to `maxRetries` times with exponential backoff starting at 250ms.
 *  3. On success, send Subscribe to the TopicActor and stream the hub Source.
 *  4. On exhausted retries, fail with NOT_FOUND gRPC status.
 *
 * ListTopics: queries the Receptionist for all known TopicHub service keys — no
 * hardcoded topic list; reflects reality at call time.
 */
class GrpcStreamService()(
  implicit system: ActorSystem[?],
  mat: Materializer,
  ec: ExecutionContext
) extends StreamRouter {

  private val config     = system.settings.config
  private val lookupTimeout: Timeout =
    Timeout(config.getDuration("stream-router.grpc-topic-lookup-timeout").toMillis.millis)
  private val maxRetries =
    config.getInt("stream-router.grpc-topic-retry-backoff-max")

  // ── Internal helper: resolve a TopicHub ActorRef ───────────────────────────
  private def resolveTopicActor(topic: String)(implicit t: Timeout): Future[Option[ActorRef[TopicHubCommand]]] = {
    val key = topicHubKey(topic)
    system.receptionist.ask[Receptionist.Listing](Receptionist.Find(key))(t, system.scheduler).map { listing =>
      listing.serviceInstances(key).headOption
    }
  }

  // ── Retry with exponential backoff ─────────────────────────────────────────
  private def resolveWithRetry(topic: String, attempt: Int = 0): Future[Option[ActorRef[TopicHubCommand]]] = {
    resolveTopicActor(topic)(lookupTimeout).flatMap {
      case some @ Some(_) => Future.successful(some)
      case None if attempt < maxRetries =>
        val delayMs = (250L * math.pow(2, attempt)).toLong  // 250, 500, 1000, 2000
        org.apache.pekko.pattern.after(delayMs.millis, system.classicSystem.scheduler) {
          resolveWithRetry(topic, attempt + 1)
        }
      case None => Future.successful(None)
    }
  }

  override def subscribeToTopic(in: TopicStreamRequest): Source[RawDataChunk, NotUsed] = {
    val topic = in.topic

    Source.lazySource { () =>
      val futureSource: Future[Source[RawDataChunk, NotUsed]] =
        resolveWithRetry(topic).flatMap {
          case Some(ref) =>
            // Ask the TopicActor for its BroadcastHub source.
            val sourceF = ref.ask[Source[RawMessage, NotUsed]](Subscribe(_))(lookupTimeout, system.scheduler)
            sourceF.map { hubSource =>
              hubSource.map { raw =>
                RawDataChunk(
                  topic   = raw.topic,
                  key     = raw.key,
                  payload = com.google.protobuf.ByteString.copyFrom(raw.value)
                )
              }
            }
          case None =>
            Future.failed(Status.NOT_FOUND
              .withDescription(s"Topic '$topic' not yet active. Ensure the producer node is running and restarted with CBOR serialization.")
              .asRuntimeException()
            )
        }

      Source.futureSource(futureSource).mapMaterializedValue(_ => NotUsed)
    }.mapMaterializedValue(_ => NotUsed)
  }

  override def listTopics(in: ListTopicsRequest): Future[TopicList] = {
    // RouterActor.knownTopics is a ConcurrentHashMap.KeySet written on first-seen
    // topic — safe to read from any thread without actor messaging.
    import scala.jdk.CollectionConverters._
    Future.successful(TopicList(topics = RouterActor.knownTopics.asScala.toSeq.sorted))
  }
}
