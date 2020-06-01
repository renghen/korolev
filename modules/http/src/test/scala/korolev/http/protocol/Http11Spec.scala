package korolev.http.protocol

import korolev.data.ByteVector
import korolev.effect.{Decoder, Stream}
import korolev.web.{Path, Request, Response}
import org.scalacheck._
import org.scalatest.{FlatSpec, Matchers}
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration

class Http11Spec extends FlatSpec with Matchers with ScalaCheckPropertyChecks {

  import korolev.http.protocol.Http11._

  // TODO use from effect
  implicit val ec = new  ExecutionContext {
    def execute(runnable: Runnable): Unit = runnable.run()
    def reportFailure(cause: Throwable): Unit = cause.printStackTrace()
  }

  "decodeLimitedBody" should "pass bytes if content length not reached and byteTotal is zero" in {
    val bytes = ByteVector.fill(5)(i => i.toByte)
    val contentLength = 10L
    val (bytesTotal, frame) = decodeLimitedBody(0, bytes, contentLength)
    bytesTotal shouldEqual 5
    frame shouldEqual Decoder.Action.Push(bytes)
  }

  it should "pass part of bytes and take back rest of bytes if content length reached" in {
    val bytes = ByteVector.fill(5)(i => i.toByte)
    val contentLength = 10L
    val (_, frame) = decodeLimitedBody(7, bytes, contentLength)
    frame shouldEqual Decoder.Action.ForkFinish(
      bytes.slice(0, 3),
      bytes.slice(3)
    )
  }

  it should "finish the stream if count of bytes in chunk is equals to content length" in {
    val bytes = ByteVector.fill(3)(i => i.toByte)
    val contentLength = 10L
    val (_, frame) = decodeLimitedBody(7, bytes, contentLength)
    frame shouldEqual Decoder.Action.PushFinish(bytes)
  }

  // Request/Response

  private val genHeader =
    for {
      k <- Gen.alphaNumStr.filter(_.length > 0)
      v <- Gen.asciiPrintableStr
    } yield (k, v)

  private val genStatus =
    for {
      k <- Gen.choose(0, 1000)
      v <- Gen.alphaNumStr.filter(_.length > 0)
    } yield Response.Status(k, v.toUpperCase)

  private val genResponse =
    for {
      status <- genStatus
      //pathStrings <- Gen.listOf(Gen.asciiPrintableStr)
      //path = Path.fromString(pathStrings.mkString("/"))
      headers <- Gen.listOf(genHeader)
      bytes <- Gen.listOf(Gen.choose(Byte.MinValue, Byte.MaxValue))
      bytesVector = ByteVector(bytes.toArray)
    } yield Stream(bytesVector)
      .mat()
      .map { bodyStream =>
        bytesVector -> Response(
          status = status,
          headers = headers,
          body = bodyStream,
          contentLength = Some(bytes.length.toLong)
        )
      }

//  private val genRequest =
//    for {
//      method <- Gen.oneOf(Request.Method.All)
//      pathStrings <- Gen.listOf(Gen.asciiPrintableStr)
//      path = Path.fromString(pathStrings.mkString("/"))
//      headers <- Gen.listOf(genHeader)
//      bytes <- Gen.listOf(Gen.choose(Byte.MinValue, Byte.MaxValue))
//      bytesVector = ByteVector(bytes.toArray)
//    } yield Stream(bytesVector)
//      .mat()
//      .map { bodyStream =>
//        bytesVector -> Request(
//          method, path, s => None, s => None, headers, bodyStream
//        )
//      }

  "renderResponse/parseResponse" should "comply with the law `parse(render(response)) == response`" in {
    forAll (genResponse) { generated =>
      val (responseNoBody, bodyBytes, (parsedBodyBytes, parsedResponse)) = await {
        for {
          (bodyBytes, response) <- generated
          bytesStream <- Http11.renderResponse(response)
          bytes <- bytesStream.fold(ByteVector.empty)(_ ++ _)
          //_ = println(bytes.asciiString.replaceAll("\r", "\\\\r"))
          lhe = findLastHeaderEnd(bytes)
        } yield {
          val responseNoBody = response.copy(body = ())
          (responseNoBody, bodyBytes, Http11.parseResponse(bytes, lhe))
        }
      }
      assert(
        parsedBodyBytes == bodyBytes &&
        parsedResponse == responseNoBody
      )
    }
  }

//  "renderRequest/parseRequest" should "comply with the law `parse(render(request)) == request`" in {
//  }

  // FIXME https://github.com/scalatest/scalatest/issues/1320
  private def await[T](process: Future[T]): T =
    process
      .ready(Duration.Inf)(null)
      .value
      .get
      .get


}
