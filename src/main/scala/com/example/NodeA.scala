package com.example

import org.apache.pekko.actor.typed._
import org.apache.pekko.actor.typed.scaladsl._
import org.apache.pekko.actor.typed.receptionist.Receptionist
import com.typesafe.config.ConfigFactory
import org.apache.pekko.stream.Materializer
import com.typesafe.scalalogging.LazyLogging
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.grpc.scaladsl.{ServerReflection, ServiceHandler}
import com.example.grpc.{StreamRouter, StreamRouterHandler}
import com.example.FlowMessage._

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

object RouterNode extends LazyLogging {

  def main(args: Array[String]): Unit = {
    val config = ConfigFactory
      .parseString("""
        pekko.remote.artery.canonical.hostname = "10.0.0.10"
        pekko.remote.artery.canonical.port = 25520

        pekko.cluster.seed-nodes = [
          "pekko://ClusterSystem@10.0.0.9:25520",
          "pekko://ClusterSystem@10.0.0.10:25520"
        ]
        """)
      .withFallback(ConfigFactory.load())

    val system = ActorSystem[Nothing](
      Behaviors.setup[Nothing] { ctx =>
        implicit val sys: ActorSystem[?] = ctx.system
        implicit val ec = sys.executionContext
        implicit val materializer: Materializer = Materializer(sys)

        // Spawn RouterActor and register it under RouterKey
        val routerActor = ctx.spawn(
          Behaviors.supervise(RouterActor())
            .onFailure[Throwable](SupervisorStrategy.restart),
          "routerActor"
        )

        ctx.system.receptionist ! Receptionist.Register(RouterKey, routerActor)

        logger.info(
          "[RouterNode] RouterActor spawned with supervision and registered under RouterKey."
        )

        // Start gRPC Server
        val grpcPort = config.getInt("grpc.port")
        val grpcService = new GrpcStreamService()(sys, materializer, ec)

        val grpcHandler = ServiceHandler.concatOrNotFound(
          StreamRouterHandler.partial(grpcService),
          ServerReflection.partial(List(StreamRouter))
        )

        Http()(sys.classicSystem)
          .newServerAt("0.0.0.0", grpcPort)
          .bind(grpcHandler)
          .onComplete {
            case Success(binding) =>
              logger.info(s"[RouterNode] gRPC Server bound to ${binding.localAddress}")

            case Failure(ex) =>
              logger.error("[RouterNode] Failed to bind gRPC server. Terminating ActorSystem.", ex)
              ctx.system.terminate()
          }(ec)

        logger.info("[RouterNode] Waiting for producer nodes to connect over Pekko Cluster...")

        Behaviors.empty
      },
      "ClusterSystem",
      config
    )

    logger.info("[RouterNode] System started.")

    // Important for systemd:
    // Do NOT use StdIn.readLine(), because systemd has no terminal.
    // Keep JVM alive until ActorSystem is terminated.
    Await.result(system.whenTerminated, Duration.Inf)
  }
}