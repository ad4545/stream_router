package com.example

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import com.example.StreamToActorMessaging._
import com.example.FlowMessage.{FlowMessage, Invocation}

object RouterActor {
  var counter: Int = 0

  def apply(
    sourceActorRef: ActorRef[FlowMessage]
  ): Behavior[StreamToActorMessage[FlowMessage]] =
    Behaviors.setup { context =>
      println(s"[RouterActor] Starting with sourceActorRef: $sourceActorRef")
      
      Behaviors.receiveMessage[StreamToActorMessage[FlowMessage]] {
        case StreamInit(replyTo) =>
          println(s"[RouterActor] Received StreamInit from: $replyTo")
          println("[RouterActor] Signaling demand by responding with Ack")
          replyTo ! StreamAck
          Behaviors.same

        case StreamElementIn(element, replyTo) =>
          counter += 1
          println(s"[RouterActor] Received StreamElementIn #$counter")
          println(s"[RouterActor] Element type: ${element.getClass}")
          println(s"[RouterActor] Element value: $element")
          
          // Handle the element safely without casting
          element match {
            case invocation: Invocation =>
              println(s"[RouterActor] Processing Invocation: $invocation")
              // Forward to the source actor if needed
              // sourceActorRef ! invocation
              
            case other =>
              println(s"[RouterActor] Processing other FlowMessage type: ${other.getClass.getSimpleName}")
              // Handle other FlowMessage types
          }
          
          println(s"[RouterActor] Sending StreamAck to: $replyTo")
          replyTo ! StreamAck
          Behaviors.same

        case StreamFailed(throwable) =>
          println(s"[RouterActor] Received StreamFailed: $throwable")
          println("[RouterActor] Will cleanup and stop")
          throwable.printStackTrace()
          Behaviors.stopped

        case StreamCompleted =>
          println("[RouterActor] Received StreamCompleted, will cleanup and stop")
          Behaviors.stopped
          
        case other =>
          println(s"[RouterActor] Received unexpected message: $other")
          Behaviors.same
      }
    }
}
