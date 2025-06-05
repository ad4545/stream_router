package com.example

import akka.actor.typed._
import akka.actor.typed.scaladsl._
import akka.actor.typed.receptionist.Receptionist
import com.typesafe.config.ConfigFactory
import akka.actor.typed.scaladsl.adapter.TypedActorContextOps
import FlowMessage._
// import akka.stream.ActorMaterializer
import akka.stream.Materializer
import com.example.proto._
import akka.stream.scaladsl.BroadcastHub
import akka.stream.scaladsl.Keep
import akka.stream.OverflowStrategy
import StreamToActorMessaging._
import akka.actor.typed.scaladsl.adapter._
import akka.stream.typed.scaladsl.ActorSource

import scala.concurrent.Await
import scala.concurrent.duration.Duration

object RouterNode extends App {
  val config = ConfigFactory
    .parseString("""
      akka.remote.artery.canonical.port = 25520
     """)
    .withFallback(ConfigFactory.load())

  val system = ActorSystem[Nothing](
    Behaviors.setup[Nothing] { ctx =>
      implicit val system: ActorSystem[_] = ctx.system
      import system.executionContext
      // Remove the implicit from materializer declaration
      val materializer: Materializer = Materializer(system)

      case object Complete extends FlowMessage
      case class Fail(ex: Exception) extends FlowMessage

      val actorSource = ActorSource.actorRef[FlowMessage](
        completionMatcher = { case Complete =>
        },
        failureMatcher = { case Fail(ex) =>
          ex
        },
        bufferSize = 10,
        overflowStrategy = OverflowStrategy.dropHead
      )

      val (grpcActorRef, source) = actorSource
        .collect { case Invocation(i) =>
          Scan(
            angleMax = i.angleMax,
            angleMin = i.angleMin,
            angleIncrement = i.angleIncrement,
            ranges = i.ranges,
            scanTime = i.scanTime
          )
        }
        .toMat(BroadcastHub.sink[Scan])(Keep.both)
        .run()(materializer) // Explicitly pass the materializer

      val routerActor = ctx.actorOf(
        PropsAdapter[StreamToActorMessage[FlowMessage]](
          RouterActor(grpcActorRef)
        ),
        "routerActor"
      )

      // Register it under the receptionist
      ctx.system.receptionist ! Receptionist.Register(RouterKey, routerActor)
      println("[NodeA] Registered Router under RouterKey; waiting for subscribers...")

      Behaviors.empty
    },
    "ClusterSystem",
    config
  )

  println("[RouterNode] System started, press ENTER to terminate...")
  scala.io.StdIn.readLine()
  system.terminate()
}
