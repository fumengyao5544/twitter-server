package com.twitter.server

import com.twitter.app.App
import com.twitter.finagle.tracing.Trace
import com.twitter.logging.{Level => TwLevel}
import com.twitter.util.Time
import java.util.logging.{Formatter, Level, LogRecord, Logger}
import java.io.{PrintWriter, StringWriter}
import scala.reflect.NameTransformer

trait LogFormat { app: App =>
  premain {
    for (h <- Logger.getLogger("").getHandlers)
      h.setFormatter(new LogFormatter)
  }
}

/**
 * Implements "glog" style log formatting.
 */
private class LogFormatter extends Formatter {
  private val levels = Map[Level, Char](
    Level.FINEST -> 'D',
    Level.FINER -> 'D',
    Level.FINE -> 'D',
    TwLevel.TRACE -> 'D',
    TwLevel.DEBUG -> 'D',
    Level.CONFIG -> 'I',
    Level.INFO -> 'I',
    TwLevel.INFO -> 'I',
    Level.WARNING -> 'W',
    TwLevel.WARNING -> 'W',
    Level.SEVERE -> 'E',
    TwLevel.ERROR -> 'E',
    TwLevel.CRITICAL -> 'E',
    TwLevel.FATAL -> 'E'
  )

  // Make some effort to demangle scala names.
  private def prettyClass(name: String): String = {
    var s = NameTransformer.decode(name)
    val dolladolla = s.indexOf("$$")
    if (dolladolla > 0) {
      s = s.substring(0, dolladolla)
      s += "~"
    }

    s
  }

  override def format(r: LogRecord): String = {
    val msg = formatMessage(r)

    val str = new StringBuilder(msg.length + 30 + 150)
      .append(levels.getOrElse(r.getLevel, 'U'))
      .append(Time.fromMilliseconds(r.getMillis).format(" MMdd HH:mm:ss.SSS"))
      .append(" THREAD")
      .append(r.getThreadID)

    for (id <- Trace.idOption) {
      str.append(" TraceId:")
      str.append(id.traceId)
    }

    if (r.getSourceClassName != null) {
      str.append(' ').append(prettyClass(r.getSourceClassName))
      if (r.getSourceMethodName != null)
        str.append('.').append(r.getSourceMethodName)
    }

    str.append(": ")
    str.append(msg)

    if (r.getThrown != null) {
      val w = new StringWriter
      r.getThrown.printStackTrace(new PrintWriter(w))
      str.append('\n').append(w.toString)
    }

    str.append('\n')
    str.toString
  }
}
