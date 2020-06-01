package korolev.server

import java.net.{InetSocketAddress, SocketAddress}
import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.Executors

import korolev.Context
import korolev.effect.Effect
import korolev.effect.io.ServerSocket.ServerSocketHandler
import korolev.effect.syntax._
import korolev.state.{StateDeserializer, StateSerializer}

import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}

abstract class KorolevApp[
  F[_] : Effect,
  S: StateSerializer : StateDeserializer,
  M](address: SocketAddress = new InetSocketAddress("localhost", 8080),
     gracefulShutdown: Boolean = false) {

  implicit lazy val executionContext: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

  val context: Context[F, S, M] = Context[F, S, M]

  val config: KorolevServiceConfig[F, S, M]

  val channelGroup: AsynchronousChannelGroup =
    AsynchronousChannelGroup.withThreadPool(executionContext)

  private def logServerStarted(): Unit =
    config.reporter.info(s"Server stated at $address")

  private def addShutdownHook(handler: ServerSocketHandler[F]): Unit =
    Runtime.getRuntime.addShutdownHook(
      new Thread {
        override def run(): Unit = {
          config.reporter.info("Shutdown signal received.")
          config.reporter.info("Stopping serving new requests.")
          config.reporter.info("Waiting clients disconnection.")
          handler
            .stopServingRequests()
            .after(handler.awaitShutdown())
            .runSyncForget(config.reporter)
        }
      }
    )

  def main(args: Array[String]): Unit = {
    standalone
      .buildServer[F](korolevService(config), address, channelGroup)
      .runAsync {
        case Left(error) =>
          config.reporter.error("Bootstrap error.", error)
        case Right(handler) if gracefulShutdown =>
          logServerStarted()
          addShutdownHook(handler)
        case Right(_) =>
          logServerStarted()
      }
  }
}
