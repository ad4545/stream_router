package com.example

import org.apache.pekko.actor.typed._
import org.apache.pekko.actor.typed.scaladsl._
import org.apache.pekko.actor.typed.receptionist.Receptionist
import com.typesafe.config.ConfigFactory
import org.apache.pekko.stream.Materializer
import com.typesafe.scalalogging.LazyLogging
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.grpc.scaladsl.{ ServerReflection, ServiceHandler }
import com.example.grpc.{ StreamRouter, StreamRouterHandler }
import com.example.FlowMessage._

object RouterNode extends LazyLogging {

  def main(args: Array[String]): Unit = {
    val config = ConfigFactory
      .parseString("""
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
        implicit val ec  = sys.executionContext
        val materializer: Materializer = Materializer(sys)

        // ── Spawn a single RouterActor and register under the shared RouterKey ──
        // stream_handler nodes discover this actor via the Receptionist.
        val routerActor = ctx.spawn(RouterActor(), "routerActor")
        ctx.system.receptionist ! Receptionist.Register(RouterKey, routerActor)
        logger.info("[RouterNode] RouterActor spawned and registered under RouterKey.")

        // ── Start gRPC Server ────────────────────────────────────────────────
        val grpcPort    = config.getInt("grpc.port")
        val grpcService = new GrpcStreamService()(sys, materializer, ec)

        val grpcHandler = ServiceHandler.concatOrNotFound(
          StreamRouterHandler.partial(grpcService),
          ServerReflection.partial(List(StreamRouter))
        )

        Http()(sys.classicSystem)
          .newServerAt("0.0.0.0", grpcPort)
          .bind(grpcHandler)
          .map { binding =>
            logger.info(s"[RouterNode] gRPC Server bound to ${binding.localAddress}")
          }(ec)

        logger.info("[RouterNode] Waiting for producer nodes to connect over Pekko Cluster...")

        Behaviors.empty
      },
      "ClusterSystem",
      config
    )

    logger.info("[RouterNode] System started, press ENTER to terminate...")
    scala.io.StdIn.readLine()
    logger.info("[RouterNode] Shutting down...")
    system.terminate()
  }
}
