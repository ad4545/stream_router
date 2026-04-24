package com.example

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.receptionist.ServiceKey
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.NotUsed
import StreamToActorMessaging._

object FlowMessage {

  @SerialVersionUID(1L)
   trait FlowMessage extends CborSerializable

  // ── Raw Kafka message — no parsing done here ──────────────────────────────
  // The downstream Router/service is responsible for interpreting the bytes.
  @SerialVersionUID(1L)
  case class RawMessage(
    topic: String,          // Kafka topic name the message came from
    key:   String,          // Kafka record key (may be null/empty)
    value: Array[Byte]      // Raw protobuf bytes — unparsed
  ) extends FlowMessage

  // ── Ingestion entry point — stream_handler sends ALL data here ───────────
  // This key is the single point-of-entry for producers. Do not remove.
  val RouterKey: ServiceKey[StreamToActorMessage[FlowMessage]] =
    ServiceKey[StreamToActorMessage[FlowMessage]]("RouterService")

  // ── Per-topic subscriber hub ───────────────────────────────────────────────
  // Commands handled by TopicActor — one instance per discovered topic.
  sealed trait TopicHubCommand extends CborSerializable

  /** Push a raw message into this topic's BroadcastHub. */
  case class Publish(msg: RawMessage) extends TopicHubCommand

  /** Request the shared BroadcastHub source for this topic. */
  case class Subscribe(replyTo: ActorRef[Source[RawMessage, NotUsed]]) extends TopicHubCommand

  /** ServiceKey factory — unique key per topic string. */
  def topicHubKey(topic: String): ServiceKey[TopicHubCommand] =
    ServiceKey[TopicHubCommand](s"TopicHub-$topic")
}