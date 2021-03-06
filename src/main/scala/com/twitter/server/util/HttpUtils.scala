package com.twitter.server.util

import com.twitter.finagle.Service
import com.twitter.finagle.http.{MediaType, Request, Response, Status, Version}
import com.twitter.io.Buf
import com.twitter.util.Future
import org.jboss.netty.handler.codec.http.QueryStringDecoder
import scala.collection.JavaConverters._
import scala.collection.{Map, Seq}

private[server] object HttpUtils {
  /**
   * Creates a http [[com.twitter.finagle.Service]] which attempts a
   * request on the given `services`, in order, until a service returns
   * a response with a non-404 status code. If none return a non-404,
   * the response of the last service is used. If the given list of `services`
   * is empty the resulting services will be always answering with 404.
   */
  def combine(services: Seq[Service[Request, Response]]): Service[Request, Response] =
    Service.mk[Request, Response] { req =>
      def loop(services: Seq[Service[Request, Response]]): Future[Response] =
        services match {
          case service +: Nil => service(req)
          case service +: tail =>
            service(req).flatMap { rep =>
              if (rep.status == Status.NotFound)
                loop(tail)
              else
                Future.value(rep)
            }
          case Nil =>
            Future.value(Response(req.version, Status.NotFound))
        }

      loop(services)
    }

  /**
   * Determines (by examine the "Accept" header on `req`) if the client accepts given `contentType`.
   *
   * Note that this method simply checks if the given `contentType` is a substring of the "Accept"
   * header.
   */
  def accepts(req: Request, contentType: String): Boolean =
    req.headerMap.get("Accept").exists(_.contains(contentType))

  /**
   * Determines if the client expects to receive `text/html` content type.
   */
  def expectsHtml(req: Request): Boolean = {
    val decoder = new QueryStringDecoder(req.uri)
    decoder.getPath.endsWith(".html") || accepts(req, MediaType.Html)
  }

  /**
   * Determines if the client expects to receive `application/json` content type.
   */
  def expectsJson(req: Request): Boolean = {
    val decoder = new QueryStringDecoder(req.uri)
    decoder.getPath.endsWith(".json") || accepts(req, MediaType.Json)
  }

  /**
   * Create an http response with the give params.
   * Some of the headers like content length are inferred.
   *
   * @param version The HTTP version for this response.
   * @param status The HTTP status code.
   * @param headers Additional headers to include in the response.
   * @param contentType The content type header, defaults to text/plain
   * @param content The content body of the HTTP response.
   */
  def newResponse(
    version: Version = Version.Http11,
    status: Status = Status.Ok,
    headers: Iterable[(String, Object)] = Seq(),
    contentType: String,
    content: Buf
  ): Future[Response] = {
    val response = Response(version, status)
    response.content = content
    for ((k, v) <- headers) response.headerMap.add(k, v.toString)
    response.headerMap.add("Content-Language", "en")
    response.headerMap.add("Content-Length", content.length.toString)
    response.headerMap.add("Content-Type", contentType)
    Future.value(response)
  }

  /** Returns a new 200 OK with contents set to `msg` */
  def newOk(msg: String): Future[Response] =
    newResponse(
      contentType = "text/plain;charset=UTF-8",
      content = Buf.Utf8(msg)
    )

  /** Returns a new 404 with contents set to `msg` */
  def new404(msg: String): Future[Response] =
    newResponse(
      status = Status.NotFound,
      contentType = "text/plain;charset=UTF-8",
      content = Buf.Utf8(msg)
    )

  /** Parse uri into (path, params) */
  def parse(uri: String): (String, Map[String, Seq[String]]) = {
    val qsd = new QueryStringDecoder(uri)
    val params = qsd.getParameters.asScala.mapValues { _.asScala.toSeq }
    (qsd.getPath, params)
  }
}
