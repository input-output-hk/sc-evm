package io.iohk.scevm.utils

import java.io.{PrintWriter, StringWriter}

object ThrowableExtensions {
  implicit class ThrowableOps(val t: Throwable) extends AnyVal {
    def stackTraceString: String = {
      val sw = new StringWriter
      t.printStackTrace(new PrintWriter(sw))
      sw.toString
    }
  }
}
