package korolev.http.protocol

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

import korolev.data.ByteVector
import korolev.effect.{Decoder, Effect, Stream}
import korolev.effect.syntax._

import korolev.web
import korolev.web.Response.Status
import korolev.web.{Headers, Path, Request, Response}

import scala.collection.mutable

object Http11 {

  private final val LastChunk = Stream(ByteVector.ascii("0\r\n\r\n"))

  private final val HeaderDelimiter =
    Array[Byte]('\r', '\n', '\r', '\n')

  private implicit final class StringBuilderOps(val builder: StringBuilder) extends AnyVal {
    def newLine(): StringBuilder = builder
      .append('\r')
      .append('\n')
  }

  def decodeRequest[F[_]: Effect](decoder: Decoder[F, ByteVector]): Stream[F, Request[Stream[F, ByteVector]]] = decoder
      .decode(ByteVector.empty)(decodeRequest)
      .map { request =>
        request.copy(
          body = request.header(Headers.ContentLength).map(_.toLong) match {
            case Some(contentLength) =>
              decoder.decode(0L)(decodeLimitedBody(_, _, contentLength))
            case None =>
              decoder
          }
        )
      }

  def decodeRequest(buffer: ByteVector,
                    incoming: ByteVector): (ByteVector, Decoder.Action[ByteVector, Request[Unit]]) = {
    val allBytes = buffer ++ incoming
    findLastHeaderEnd(buffer) match {
      case -1 => (allBytes, Decoder.Action.TakeNext)
      case lastByteOfHeader =>
        val (bodyBytes, request) = parseRequestHeading(allBytes, lastByteOfHeader)
        (ByteVector.empty, Decoder.Action.Fork(request, bodyBytes))
    }
  }

  def decodeLimitedBody[F[_] : Effect](prevBytesTotal: Long,
                                       incoming: ByteVector,
                                       contentLength: Long): (Long, Decoder.Action[ByteVector, ByteVector]) =
    prevBytesTotal + incoming.length match {
      case `contentLength` =>
        (contentLength, Decoder.Action.PushFinish(incoming))
      case total if total > contentLength =>
        val pos = contentLength - prevBytesTotal
        val value = incoming.slice(0, pos)
        val takeBack = incoming.slice(pos)
        // Push left slice to a downstream
        // and take back right slice to the upstream
        (total, Decoder.Action.ForkFinish(value, takeBack))
      case total =>
        (total, Decoder.Action.Push(incoming))
    }

  def parseRequestHeading(allBytes: ByteVector,
                          lastByteOfHeader: Long): (ByteVector, Request[Unit]) = {
    // Buffer contains header.
    // Lets parse it.
    val methodEnd = allBytes.indexOf(' ')
    val paramsStart = allBytes.indexOf('?', methodEnd + 1)
    val pathEnd = allBytes.indexOf(' ', methodEnd + 1)
    val protocolVersionEnd = allBytes.indexOf('\r', pathEnd + 1)
    val hasParams = paramsStart == -1 || paramsStart >= protocolVersionEnd
    val method = allBytes.slice(0, methodEnd).asciiString
    val path = allBytes.slice(methodEnd + 1, if (hasParams) pathEnd else paramsStart - 1).asciiString
    val params = if (hasParams) allBytes.slice(paramsStart + 1, pathEnd).asciiString else null
    //val protocolVersion = allBytes.slice(pathEnd + 1, protocolVersionEnd).asciiString
    // Parse headers.
    val headers = mutable.Buffer.empty[(String, String)]
    var headerStart = protocolVersionEnd + 2 // first line end plus \r\n chars
    var cookie: String = null
    while (headerStart < lastByteOfHeader) {
      val nameEnd = allBytes.indexOf(':', headerStart)
      val valueEnd = allBytes.indexOf('\r', nameEnd)
      val name = allBytes.slice(headerStart, nameEnd).asciiString
      val value = allBytes.slice(nameEnd + 1, valueEnd).asciiString // TODO optimization available
      name match {
        case _ if name.equalsIgnoreCase(Headers.Cookie) => cookie = value
          // TODO recognize content length at this step
        //case _ if name.equalsIgnoreCase(Headers.ContentLength) => contentLength = value
        case _ => ()
      }
      headers += ((name, value.trim))
      headerStart = valueEnd + 2
    }
    val request = web.Request(
      method = Request.Method.fromString(method),
      path = Path.fromString(path),
      param = parseParams(params),
      cookie = parseCookie(cookie),
      headers = headers,
      body = ()
    )
    val bodyBytes = allBytes.slice(lastByteOfHeader + 4, allBytes.length)
    (bodyBytes, request)
  }

  def findLastHeaderEnd(bytes: ByteVector): Long =
    bytes.indexOfSlice(HeaderDelimiter)

  def parseResponse(bytes: ByteVector, lastHeaderEnd: Long): (ByteVector, Response[Unit]) = {
    // First line
    val protocolVersionEnd = bytes.indexOf(' ')
    val statusCodeStart = protocolVersionEnd + 1
    val statusCodeEnd = bytes.indexOf(' ', statusCodeStart)
    val statusPhraseStart = statusCodeEnd + 1
    val statusPhraseEnd = bytes.indexOf('\r', statusPhraseStart)
    val statusCode = bytes.slice(statusCodeStart, statusCodeEnd)
    val statusPhrase = bytes.slice(statusPhraseStart, statusPhraseEnd)
    // Headers
    var headerStart = statusPhraseEnd + 2
    val headers = mutable.Buffer.empty[(String, String)]
    var cookie: String = null
    var contentLength: String = null
    while (headerStart < lastHeaderEnd) {
      val nameEnd = bytes.indexOf(':', headerStart)
      val valueEnd = bytes.indexOf('\r', nameEnd)
      val name = bytes.slice(headerStart, nameEnd).asciiString
      val value =
        if (bytes(nameEnd + 1) == ' ') bytes.slice(nameEnd + 2, valueEnd).asciiString
        else bytes.slice(nameEnd + 1, valueEnd).asciiString
      name match {
        case _ if name.equalsIgnoreCase(Headers.Cookie) =>
          cookie = value
          headers += ((name, value))
        case _ if name.equalsIgnoreCase(Headers.ContentLength) =>
          contentLength = value
        case _ =>
          headers += ((name, value))
      }
      headerStart = valueEnd + 2
    }
    val response = Response(
      status = Status(statusCode.asciiString.toInt, statusPhrase.asciiString),
      headers = headers,
      contentLength = Option(contentLength.toLong),
      body = ()
    )
    val bodyBytes = bytes.slice(lastHeaderEnd + 4, bytes.length)
    (bodyBytes, response)
  }

  def parseParams(params: String): String => Option[String] = {
    lazy val map =
      if (params == null) Map.empty[String, String]
      else params
        .split('&')
        .map { xs =>
          val Array(k, v) = xs.split('=')
          (URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8))
        }
        .toMap
    k => map.get(k)
  }

  def parseCookie(cookie: String): String => Option[String] = {
    lazy val map =
      if (cookie == null) Map.empty[String, String]
      else cookie
        .split(';')
        .map { xs =>
          val Array(k, v) = xs.split('=')
          (URLDecoder.decode(k.trim, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8))
        }
        .toMap
    k => map.get(k)
  }


  def renderResponseHeader(status: Status, headers: Seq[(String, String)]): String = {
    val builder = new StringBuilder()
    builder.append("HTTP/1.1 ")
      .append(status.codeAsString)
      .append(' ')
      .append(status.phrase)
      .newLine()
    def putHeader(name: String, value: String) = builder
      .append(name)
      .append(':')
      .append(' ')
      .append(value)
      .newLine()
    headers.foreach {
      case (name, value) =>
        putHeader(name, value)
    }
    builder
      .newLine()
      .mkString
  }

  def renderRequest[F[_]: Effect](request: Request[Stream[F, ByteVector]]): F[Stream[F, ByteVector]] = {
    val sb = new StringBuilder()
      .append(request.method.value)
      .append(' ')
      .append(request.path.mkString)
      .append(' ')
      .append("HTTP/1.1")
      .newLine()
    // TODO params.
    // TODO cookie
    request.headers.foreach {
      case (k, v) => sb
        .append(k)
        .append(": ")
        .append(v)
        .newLine()
    }
    val header = sb
      .newLine()
      .mkString
    
    Stream(ByteVector.ascii(header))
      .mat()
      //.map(_ ++ request.body)
  }

  def renderResponse[F[_]: Effect](response: Response[Stream[F, ByteVector]]): F[Stream[F, ByteVector]] = {
    def go(readers: Seq[(String, String)], body: Stream[F, ByteVector]) = {
      val fullHeaderString =  renderResponseHeader(response.status, readers)
      val fullHeaderBytes = ByteVector.ascii(fullHeaderString)
      Stream(fullHeaderBytes).mat().map(_ ++ body)
    }
    response.contentLength match {
      case Some(s) => go((Headers.ContentLength -> s.toString) +: response.headers, response.body)
      case None if response.status == Status.SwitchingProtocols => go(response.headers, response.body)
      case None =>
        val chunkedBody = response.body.map { chunk =>
          ByteVector.ascii(chunk.length.toHexString) ++
            ByteVector.CRLF ++
            chunk ++
            ByteVector.CRLF
        }
        LastChunk
          .mat()
          .flatMap { lastChunk =>
            go(Headers.TransferEncodingChunked +: response.headers, chunkedBody ++ lastChunk)
          }
    }
  }
}
