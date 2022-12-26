/*
 * Copyright 2013 http4s.org
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

package org.http4s.internal

import cats.Monad
import cats.effect.kernel.Async
import cats.syntax.all._
import fs2.Stream
import org.http4s.Charset
import org.http4s.Entity
import org.http4s.Headers
import org.http4s.MediaType
import org.http4s.Message
import org.http4s.Request
import org.http4s.Response
import org.typelevel.ci.CIString

object Logger {

  def defaultLogHeaders[F[_]](message: Message[F])(
      logHeaders: Boolean,
      redactHeadersWhen: CIString => Boolean = Headers.SensitiveHeaders.contains,
  ): String =
    if (logHeaders)
      message.headers.mkString("Headers(", ", ", ")", redactHeadersWhen)
    else ""

  def defaultLogBody[F[_]](
      message: Message[F]
  )(logBody: Boolean)(implicit F: Async[F]): Option[F[String]] =
    if (logBody && message.entity != Entity.Empty) {
      val isBinary = message.contentType.exists(_.mediaType.binary)
      val isJson = message.contentType.exists(mT =>
        mT.mediaType == MediaType.application.json || mT.mediaType.subType.endsWith("+json")
      )

      message.entity match {
        case Entity.Empty =>
          None
        case Entity.Strict(bv) =>
          if (!isBinary || isJson) {
            val charset = message.charset.fold(Charset.`UTF-8`.nioCharset)(_.nioCharset)
            Some(F.delay(bv.decodeStringLenient()(charset)))
          } else
            Some(F.delay(bv.toHex))
        case Entity.Default(_, _) =>
          val stream =
            if (!isBinary || isJson)
              message.bodyText
            else
              message.body.map(b => java.lang.Integer.toHexString(b & 0xff))

          Some(stream.compile.string)
      }
    } else None

  def logMessage[F[_]](message: Message[F])(
      logHeaders: Boolean,
      logBody: Boolean,
      redactHeadersWhen: CIString => Boolean = Headers.SensitiveHeaders.contains,
  )(log: String => F[Unit])(implicit F: Async[F]): F[Unit] = {
    val logBodyText = (_: Stream[F, Byte]) => defaultLogBody(message)(logBody)

    logMessageWithBodyText(message)(logHeaders, logBodyText, redactHeadersWhen)(log)
  }

  def logMessageWithBodyText[F[_]](message: Message[F])(
      logHeaders: Boolean,
      logBodyText: Stream[F, Byte] => Option[F[String]],
      redactHeadersWhen: CIString => Boolean = Headers.SensitiveHeaders.contains,
  )(log: String => F[Unit])(implicit F: Monad[F]): F[Unit] = {
    def prelude =
      message match {
        case req: Request[_] => s"${req.httpVersion} ${req.method} ${req.uri}"
        case resp: Response[_] => s"${resp.httpVersion} ${resp.status}"
      }

    val headers: String = defaultLogHeaders(message)(logHeaders, redactHeadersWhen)

    val bodyText: F[String] =
      logBodyText(message.body) match {
        case Some(textF) => textF.map(text => s"""body="$text"""")
        case None => F.pure("")
      }

    def spaced(x: String): String = if (x.isEmpty) x else s" $x"

    bodyText
      .map(body => s"$prelude${spaced(headers)}${spaced(body)}")
      .flatMap(log)
  }

}
