package com.example


import akka.actor.typed.ActorRef
import akka.actor.typed.receptionist.ServiceKey
import com.example.proto._
import StreamToActorMessaging._

object FlowMessage {
  // Make the base trait serializable
  @SerialVersionUID(1L)
  trait FlowMessage extends Serializable
  
  // IMPORTANT: Since Scan is a protobuf message, we need to handle it carefully
  @SerialVersionUID(1L)
  case class Invocation(msg: Scan) extends FlowMessage {
    // Override serialization methods to handle protobuf properly
    private def writeObject(out: java.io.ObjectOutputStream): Unit = {
      out.defaultWriteObject()
      val bytes = msg.toByteArray
      out.writeInt(bytes.length)
      out.write(bytes)
    }
    
    private def readObject(in: java.io.ObjectInputStream): Unit = {
      in.defaultReadObject()
      val length = in.readInt()
      val bytes = new Array[Byte](length)
      in.readFully(bytes)
      // Use reflection to set the msg field since it's immutable
      val field = this.getClass.getDeclaredField("msg")
      field.setAccessible(true)
      field.set(this, Scan.parseFrom(bytes))
    }
  }
  
  // The ServiceKey for router registration
  val RouterKey: ServiceKey[StreamToActorMessage[FlowMessage]] = 
    ServiceKey[StreamToActorMessage[FlowMessage]]("RouterService")
}