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

final case class Request[Body](method: Request.Method,
                               path: Path,
                               param: String => Option[String],
                               cookie: String => Option[String],
                               headers: Seq[(String, String)],
                               body: Body)
    extends Request.RequestHeader {
  // TODO
  lazy val contentLength: Option[Long] =
    headers.collectFirst {
      case ("content-length", value) =>
        value.toLong
    }
}

object Request {

  sealed trait RequestHeader {

    def path: Path
    def param: String => Option[String]
    def cookie: String => Option[String]
    def headers: Seq[(String, String)]

    def header(header: String): Option[String] = {
      val htl = header.toLowerCase
      headers.collectFirst {
        case (k, v) if k.toLowerCase == htl => v
      }
    }
  }

  sealed abstract class Method(val value: String)

  object Method {
    
    def fromString(method: String): Method =
      method match {
        case "POST" => Post
        case "GET" => Get
        case "PUT" => Put
        case "DELETE" => Delete
        case "OPTIONS" => Options
        case "HEAD" => Head
        case "TRACE" => Trace
        case "CONNECT" => Connect
        case _ => Unknown(method)
      }

    case object Post extends Method("POST")
    case object Get extends Method("GET")
    case object Put extends Method("PUT")
    case object Delete extends Method("DELETE")
    case object Options extends Method("OPTIONS")
    case object Head extends Method("HEAD")
    case object Trace extends Method("TRACE")
    case object Connect extends Method("CONNECT")
    case class Unknown(override val value: String) extends Method(value)
  }
}
