package korolev.server

import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.Executors

import korolev.Context
import korolev.effect.Reporter
import korolev.effect.syntax._
import korolev.state.javaSerialization._

import scala.concurrent.{ExecutionContext, Future}

object TestApp extends App {

  import levsha.dsl._
  import html._
  val context = Context[Future, String, Any]
  import context._

  implicit val ec = ExecutionContext
    .fromExecutorService(Executors.newCachedThreadPool())

  val config = KorolevServiceConfig[Future, String, Any](
    stateLoader = StateLoader.default("Hello world"),
    head = _ => Seq(
      link(rel := "stylesheet", href := "static/main.css")
    ),
    render = (state) => optimize {
      body(
        state,
        button(
          "Plus one",
          event("click")(_.transition(_ + "1"))
        )
      )
    }
  )

  val service = korolevService(config)
  val group = AsynchronousChannelGroup.withThreadPool(ec)
  val address = new InetSocketAddress("localhost", 8080)

  standalone
    .buildServer[Future](service, address, group)
    .runAsyncForget(Reporter.PrintReporter)

  Thread.currentThread().join()
}
