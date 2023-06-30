package io.iohk.scevm.observer

import cats.Show

sealed trait DatasourceConfig

object DatasourceConfig {
  final case class DbSync(
      username: String,
      password: Sensitive,
      url: String,
      driver: String,
      connectThreadPoolSize: Int
  ) extends DatasourceConfig
}

final case class Sensitive(value: String) extends AnyVal {
  override def toString: String = "***"
}

object Sensitive {
  implicit val showSensitive: Show[Sensitive] = Show.fromToString
}
