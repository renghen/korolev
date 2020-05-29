package korolev.server

import java.nio.charset.StandardCharsets

import korolev.effect.Effect
import korolev.effect.io.LazyBytes
import korolev.web.Response
import korolev.web.Response.Status

package object internal {

  def HttpResponse[F[_]: Effect](status: Status): HttpResponse[F] = {
    new Response(status, LazyBytes.empty[F], Nil)
  }

  def HttpResponse[F[_]: Effect](status: Status, body: Array[Byte], headers: Seq[(String, String)]): HttpResponse[F] = {
    new Response(status, LazyBytes[F](body), headers)
  }

  def HttpResponse[F[_]: Effect](status: Status, message: String, headers: Seq[(String, String)]): HttpResponse[F] = {
    HttpResponse(status, message.getBytes(StandardCharsets.UTF_8), headers)
  }
}
