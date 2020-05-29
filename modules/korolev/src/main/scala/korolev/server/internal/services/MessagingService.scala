/*
 * Copyright 2017-2020 Aleksey Fomkin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package korolev.server.internal.services

import korolev.effect.io.LazyBytes
import korolev.effect.syntax._
import korolev.effect.{Effect, Queue, Reporter, Stream}
import korolev.server.{HttpResponse, WebSocketResponse}
import korolev.server.internal.HttpResponse
import korolev.web.Request.RequestHeader
import korolev.web.Response
import korolev.web.Response.Status
import korolev.{Qsid, web}

import scala.collection.mutable

private[korolev] final class MessagingService[F[_]: Effect](reporter: Reporter,
                                                            commonService: CommonService[F],
                                                            sessionsService: SessionsService[F, _, _]) {

  /**
    * Poll message from session's ongoing queue.
    */
  def longPollingSubscribe(qsid: Qsid, rh: RequestHeader): F[HttpResponse[F]] = {
    for {
      app <- sessionsService.findAppOrCreate(qsid, rh, createTopic(qsid))
      maybeMessage <- app.frontend.outgoingMessages.pull()
    } yield {
      maybeMessage match {
        case None => commonGoneResponse
        case Some(message) =>
          HttpResponse(
            status = Response.Status.Ok,
            message = message,
            headers = commonResponseHeaders
          )
      }
    }
  }

  /**
    * Push message to session's incoming queue.
    */
  def longPollingPublish(qsid: Qsid, data: LazyBytes[F]): F[HttpResponse[F]] = {
    for {
      topic <- takeTopic(qsid)
      message <- data.toStrictUtf8
      _ <- topic.offer(message)
    } yield commonOkResponse
  }

  def webSocketMessaging(qsid: Qsid, rh: RequestHeader, incomingMessages: Stream[F, String]): F[WebSocketResponse[F]] = {
    sessionsService.findAppOrCreate(qsid, rh, incomingMessages) map { app =>
      Response(Status.Ok, app.frontend.outgoingMessages, Nil, None)
    }
  }

  /**
    * Sessions created via long polling subscription
    * takes messages from topics stored in this table.
    */
  private val longPollingTopics = mutable.Map.empty[Qsid, Queue[F, String]]

  /**
    * Same headers in all responses
    */
  private val commonResponseHeaders = Seq(
    "cache-control" -> "no-cache",
    "content-type" -> "application/json"
  )

  /**
    * Same response for all 'publish' requests.
    */
  private val commonOkResponse = web.Response(
    status = Response.Status.Ok,
    body = LazyBytes.empty[F],
    headers = commonResponseHeaders,
    contentLength = Some(0L)
  )

  /**
    * Same response for all 'subscribe' requests
    * where outgoing stream is consumed.
    */
  private val commonGoneResponse = web.Response(
    status = Response.Status.Gone,
    body = LazyBytes.empty[F],
    headers = commonResponseHeaders,
    contentLength = Some(0L)
  )

  private def takeTopic(qsid: Qsid) =
    Effect[F].delay {
      if (longPollingTopics.contains(qsid)) longPollingTopics(qsid)
      else throw new Exception(s"There is no long-polling topic matching $qsid")
    }

  private def createTopic(qsid: Qsid) =
    longPollingTopics.synchronized {
      val topic = Queue[F, String]()
      longPollingTopics.put(qsid, topic)
      topic.stream
    }
}
