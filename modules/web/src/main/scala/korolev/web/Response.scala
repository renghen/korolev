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

package korolev.web

import korolev.web.Response.Status

final case class Response[Body](
    status: Status,
    body: Body,
    headers: Seq[(String, String)],
    contentLength: Option[Long]
)

object Response {

  final case class Status(code: Int, phrase: String) {
    val codeAsString: String = code.toString
  }

  object Status {
    val Ok: Status = Status(200, "OK")
    val NotFound: Status = Status(404, "Not Found")
    val BadRequest: Status = Status(400, "Bad Request")
    val Gone: Status = Status(410, "Gone")
    val SwitchingProtocols: Status = Status(101, "Switching Protocols")
  }
}
