package korolev.http

import java.net.InetSocketAddress

import korolev.data.ByteVector
import korolev.effect.{Queue, Stream}
import korolev.http.protocol.WebSocketProtocol
import korolev.http.protocol.WebSocketProtocol.Frame
import korolev.web.{Path, Request}
import korolev.web.Request.Method
import korolev.web.Response.Status
import org.scalatest.{AsyncFlatSpec, FlatSpec, Matchers}

import scala.concurrent.Future

class HttpClientSpec extends AsyncFlatSpec with Matchers {

  "HttpClient" should "properly send GET (content-length: 0) requests" in {
    for {
      response <- HttpClient(
        host = "example.com",
        port = 80,
        request = Request(Method.Get, Path.Root, Nil, Some(0), Stream.empty[Future, ByteVector])
      )
      strictResponseBody <- response.body.fold(ByteVector.empty)(_ ++ _)
      utf8Body = strictResponseBody.utf8String
    } yield {
      assert(utf8Body.contains("Example Domain") && response.status == Status.Ok)
    }
  }

  final val wsSample1 = Frame.Text(ByteVector.ascii("Hello!"))

  final val wsSample2 = Frame.Text(ByteVector.ascii("I'm cow!"))

  it should "properly send/receive WebSocket frames" in {
    for {
      queue <- Future.successful(Queue[Future, Frame]())
      response <- HttpClient.webSocket(
        host = "echo.websocket.org",
        port = 80,
        path = Path.Root,
        outgoingFrames = queue.stream
      )
      _ <- queue.offer(wsSample1)
      echo1 <- response.body.pull()
      _ <- queue.offer(wsSample2)
      echo2 <- response.body.pull()
      _ <- response.body.cancel()
      _ <- queue.close()
    } yield {
      assert(echo1.contains(wsSample1) && echo2.contains(wsSample2))
    }
  }
}
