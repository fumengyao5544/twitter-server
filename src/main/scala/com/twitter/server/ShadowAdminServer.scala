package com.twitter.server

import com.twitter.app.App
import com.twitter.concurrent.NamedPoolThreadFactory
import com.twitter.finagle.http.{HttpMuxer, HttpMuxHandler}
import com.twitter.finagle.netty4
import com.twitter.finagle.netty4.http.exp.Netty4Impl
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle.util.LoadService
import com.twitter.finagle.{Http, ListeningServer, NullServer, param}
import io.netty.channel.nio.NioEventLoopGroup
import java.net.InetSocketAddress
import java.util.logging.Logger

/**
 * An admin http server which serves requests outside the default
 * finagle worker pool. This server shadows vital endpoints (ex. stats)
 * that are useful for diagnostics and should be available even if the
 * server becomes overwhelmed.
 *
 * Note, we don't serve all of /admin on a separate worker pool because
 * it's important to serve certain admin endpoints in-band with the server.
 * In particular, /health and /ping should be served in-band so that they
 * are an accurate proxy of server health.
 */
trait ShadowAdminServer { self: App with AdminHttpServer =>

  @volatile protected var shadowHttpServer: ListeningServer = NullServer
  val shadowAdminPort = flag("shadow.admin.port", new InetSocketAddress(defaultHttpPort+1),
    "Shadow admin http server port")

  premain {
    val log = Logger.getLogger(getClass.getName)
    log.info(s"Serving BlackBox http server on port ${shadowAdminPort().getPort}")

    // Both ostrich and metrics export a `HttpMuxHandler`
    val handlers = LoadService[HttpMuxHandler]() filter { handler =>
      handler.pattern == "/stats.json" ||
      handler.pattern == "/admin/metrics.json" ||
      handler.pattern == "/admin/per_host_metrics.json"
    }

    val muxer = handlers.foldLeft(new HttpMuxer) {
      case (muxer, h) => muxer.withHandler(h.pattern, h)
    }

    val shadowEventLoop = new NioEventLoopGroup(1 /*nThreads*/ ,
      new NamedPoolThreadFactory("twitter-server/netty", makeDaemons = true))

    shadowHttpServer = Http.server
      .configured(param.Stats(NullStatsReceiver))
      .configured(Netty4Impl)
      .configured(param.Tracer(NullTracer))
      .configured(netty4.param.WorkerPool(shadowEventLoop))
      .serve(shadowAdminPort(), muxer)
    closeOnExit(shadowHttpServer)
  }
}
