package com.example

import akka.actor.typed._
import akka.actor.typed.scaladsl._
import akka.actor.typed.receptionist.Receptionist
import com.typesafe.config.ConfigFactory
import akka.actor.typed.scaladsl.adapter.TypedActorContextOps
import FlowMessage._
// import akka.stream.ActorMaterializer
import akka.stream.Materializer
import akka.stream.scaladsl.Source
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


      val streamConfigs: List[(String, PartialFunction[FlowMessage, Any])] = List(
        ("scan", { 
          case Invocation(i) => 
            Scan(angleMax = i.angleMax,
            angleMin = i.angleMin,
            angleIncrement = i.angleIncrement,
            ranges = i.ranges,
            scanTime = i.scanTime)
        }: PartialFunction[FlowMessage, Any]),
        
        ("pose", { 
          case PoseInvocation(p) => 
            Pose(x = p.x, y = p.y, orientation = p.orientation)
        }: PartialFunction[FlowMessage, Any])
      )

      val sourceActorRefs: Map[String, (ActorRef[FlowMessage], Source[Any, _])] = streamConfigs.map { 
        case (name, collector) =>
          val actorSource = ActorSource.actorRef[FlowMessage](
            completionMatcher = { case Complete => },
            failureMatcher = { case Fail(ex) => ex },
            bufferSize = 10,
            overflowStrategy = OverflowStrategy.dropHead
          )
          
          val (actorRef, source) = actorSource
            .collect(collector)
            .toMat(BroadcastHub.sink[Any])(Keep.both)
            .run()(materializer)
          
          name -> (actorRef, source)
      }.toMap


      val routerActor = ctx.actorOf(
        PropsAdapter[StreamToActorMessage[FlowMessage]](
          RouterActor(sourceActorRefs)
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
