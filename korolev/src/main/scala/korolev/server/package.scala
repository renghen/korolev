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

package korolev

import korolev.effect.{Effect, Stream}
import korolev.effect.io.LazyBytes
import korolev.server.internal.services._
import korolev.server.internal.{FormDataCodec, KorolevServiceImpl}
import korolev.state.{DeviceId, StateDeserializer, StateSerializer}
import korolev.web.Request.RequestHeader
import korolev.web.{Request, Response}

package object server {

  type HttpRequest[F[_]] = Request[LazyBytes[F]]
  type HttpResponse[F[_]] = Response[LazyBytes[F]]
  type WebSocketRequest[F[_]] = Request[Stream[F, String]]
  type WebSocketResponse[F[_]] = Response[Stream[F, String]]
  type StateLoader[F[_], S] = (DeviceId, RequestHeader) => F[S]

  def korolevService[F[_]: Effect, S: StateSerializer: StateDeserializer, M](
      config: KorolevServiceConfig[F, S, M]): KorolevService[F] = {

    import config.executionContext

    val commonService = new CommonService[F]()
    val filesService = new FilesService[F](commonService)
    val sessionsService = new SessionsService[F, S, M](config)
    val messagingService = new MessagingService[F](config.reporter, commonService, sessionsService)
    val formDataCodec = new FormDataCodec(config.maxFormDataEntrySize)
    val postService = new PostService[F](config.reporter, sessionsService, commonService, formDataCodec)
    val ssrService = new ServerSideRenderingService[F, S](sessionsService, config)

    new KorolevServiceImpl[F](
      config.reporter,
      commonService,
      filesService,
      messagingService,
      postService,
      ssrService
    )
  }

}
