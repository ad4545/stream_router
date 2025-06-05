package com.example

import akka.actor.typed.{ActorRef}
import akka.actor.typed.scaladsl.adapter._
import com.example.proto.Scan
import com.typesafe.scalalogging.LazyLogging

object StreamToActorMessaging {
  // Make the base trait serializable
  @SerialVersionUID(1L)
  trait StreamToActorMessage[+T] extends Serializable

  @SerialVersionUID(1L)
  case class StreamInit[T](replyTo: ActorRef[StreamToActorMessage[T]]) 
    extends StreamToActorMessage[T]

  @SerialVersionUID(1L)
  case object StreamAck extends StreamToActorMessage[Nothing]
  
  @SerialVersionUID(1L)
  case object StreamCompleted extends StreamToActorMessage[Nothing]
  
  @SerialVersionUID(1L)
  case class StreamFailed(ex: Throwable) extends StreamToActorMessage[Nothing]
  
  @SerialVersionUID(1L)
  case class StreamGetSource[T](replyTo: ActorRef[StreamToActorMessage[T]])
    extends StreamToActorMessage[T]
  
  @SerialVersionUID(1L)
  case class StreamElementIn[T](
    in: T,
    replyTo: ActorRef[StreamToActorMessage[T]]
  ) extends StreamToActorMessage[T]
  
  @SerialVersionUID(1L)
  case class StreamElementOut[T](msg: T) extends StreamToActorMessage[T]
  
  @SerialVersionUID(1L)
  case class StreamElementOutWithAck[T](msg: T) extends StreamToActorMessage[T]
}
