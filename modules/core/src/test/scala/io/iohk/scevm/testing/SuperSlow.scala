package io.iohk.scevm.testing

import com.typesafe.config.ConfigFactory

trait SuperSlow {
  private lazy val skip = ConfigFactory.load().getBoolean("skip-super-slow-tests")

  /** Some assertions may be prohibitively slow and shouldn't run on every CI run. Use this method when that's the case.
    *
    * @param f slow tests
    */
  def superSlow[T](f: => T): Option[T] =
    if (skip) None else Some(f)
}
