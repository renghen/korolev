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

package korolev.server.internal

import korolev.Qsid
import korolev.effect.{Effect, Reporter}
import korolev.server.{HttpRequest, HttpResponse, WebSocketRequest, WebSocketResponse}
import korolev.server.internal.services._
import korolev.server.KorolevService
import korolev.web.Request
import korolev.web.Path._

private[korolev] final class KorolevServiceImpl[F[_]: Effect](reporter: Reporter,
                                                              commonService: CommonService[F],
                                                              filesService: FilesService[F],
                                                              messagingService: MessagingService[F],
                                                              postService: PostService[F],
                                                              ssrService: ServerSideRenderingService[F, _])
    extends KorolevService[F] {

  def http(request: HttpRequest[F]): F[HttpResponse[F]] = {
    request match {

      // Static files
      case Request(_, Root / "static", _, _, _, _) =>
        commonService.notFoundResponseF
      case Request(_, path, _, _, _, _) if path.startsWith("static") =>
        filesService.resourceFromClasspath(path)

      // Long polling
      case Request(_, Root / "bridge" / "long-polling" / deviceId / sessionId / "publish", _, _, _, body) =>
        messagingService.longPollingPublish(Qsid(deviceId, sessionId), body)
      case r @ Request(_, Root / "bridge" / "long-polling" / deviceId / sessionId / "subscribe", _, _, _, _) =>
        messagingService.longPollingSubscribe(Qsid(deviceId, sessionId), r)

      // Data for app given via POST requests
      case Request(_, Root / "bridge" / deviceId / sessionId / "form-data" / descriptor, _, _, headers, body) =>
        postService.formData(Qsid(deviceId, sessionId), descriptor, headers, body)
      case Request(_, Root / "bridge" / deviceId / sessionId / "file" / descriptor / "info", _, _, _, body) =>
        postService.filesInfo(Qsid(deviceId, sessionId), descriptor, body)
      case Request(_, Root / "bridge" / deviceId / sessionId / "file" / descriptor, _, _, headers, body) =>
        postService.file(Qsid(deviceId, sessionId), descriptor, headers, body)

      // Server side rendering
      case request if request.path == Root || ssrService.canBeRendered(request.path) =>
        ssrService.serverSideRenderedPage(request)

      // Not found
      case _ =>
        commonService.notFoundResponseF
    }

  }

  def ws(request: WebSocketRequest[F]): F[WebSocketResponse[F]] = {
    request match {
      case r @ Request(_, Root / "bridge" / "web-socket" / deviceId / sessionId, _, _, _, body) =>
        messagingService.webSocketMessaging(Qsid(deviceId, sessionId), r, body)
      case _ =>
        webSocketBadRequestF
    }
  }

  private val webSocketBadRequestF = {
    val error = BadRequestException("Wrong path. Should be '/bridge/web-socket/<device>/<session>'.")
    Effect[F].fail[WebSocketResponse[F]](error)
  }
}
