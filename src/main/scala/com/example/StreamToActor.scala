package com.example

import org.apache.pekko.actor.typed.ActorRef

object StreamToActorMessaging {

  @com.fasterxml.jackson.annotation.JsonTypeInfo(use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.CLASS, property = "type")
  sealed trait StreamToActorMessage[+T] extends CborSerializable

  @SerialVersionUID(1L)
  case class StreamInit[T](replyTo: ActorRef[StreamToActorMessage[T]])
    extends StreamToActorMessage[T]

  @SerialVersionUID(1L)
  case object StreamAck extends StreamToActorMessage[Nothing]

  @SerialVersionUID(1L)
  case object StreamCompleted extends StreamToActorMessage[Nothing]

  @SerialVersionUID(1L)
  case class StreamFailed(cause: String) extends StreamToActorMessage[Nothing]

  @SerialVersionUID(1L)
  case class StreamGetSource[T](replyTo: ActorRef[StreamToActorMessage[T]])
    extends StreamToActorMessage[T]

  @SerialVersionUID(1L)
  case class StreamElementIn[T](
    @com.fasterxml.jackson.annotation.JsonTypeInfo(use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.CLASS, property = "type")
    in: T,
    replyTo: ActorRef[StreamToActorMessage[T]]
  ) extends StreamToActorMessage[T]

  @SerialVersionUID(1L)
  case class StreamElementOut[T](
    @com.fasterxml.jackson.annotation.JsonTypeInfo(use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.CLASS, property = "type")
    msg: T
  ) extends StreamToActorMessage[T]

  @SerialVersionUID(1L)
  case class StreamElementOutWithAck[T](
    @com.fasterxml.jackson.annotation.JsonTypeInfo(use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.CLASS, property = "type")
    msg: T
  ) extends StreamToActorMessage[T]

  @SerialVersionUID(1L)
  case class StreamSourceResponse[T](source: org.apache.pekko.stream.scaladsl.Source[T, org.apache.pekko.NotUsed]) extends StreamToActorMessage[T]
}
