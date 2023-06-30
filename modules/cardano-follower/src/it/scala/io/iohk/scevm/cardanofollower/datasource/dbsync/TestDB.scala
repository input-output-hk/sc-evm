package io.iohk.scevm.cardanofollower.datasource.dbsync

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.scalalogging.StrictLogging
import doobie.Transactor
import doobie.hikari.HikariTransactor
import doobie.syntax.all._
import io.iohk.scevm.cardanofollower.datasource.DatasourceConfig.DbSync

import scala.concurrent.duration._

/** A work-around to use the `xaResource` imperatively.
  * Copied from https://github.com/softwaremill/bootzooka/blob/master/backend/src/test/scala/com/softwaremill/bootzooka/test/TestDB.scala
  */
class TestDB(config: DbSync) extends StrictLogging {

  var xa: Transactor[IO]                      = _
  private var releaseAction: Option[IO[Unit]] = None

  {
    val xaResource = for {
      connectEC <- doobie.util.ExecutionContexts.fixedThreadPool[IO](config.connectThreadPoolSize)
      xa <- HikariTransactor.newHikariTransactor[IO](
              config.driver,
              config.url,
              config.username,
              config.password.value,
              connectEC
            )
    } yield xa

    val (xa, release) = xaResource.allocated.unsafeRunSync()
    this.xa = xa
    this.releaseAction = Some(release)
  }

  def testConnection(): Unit = {
    sql"select 1".query[Int].unique.transact(xa).unsafeRunTimed(1.minute)
    ()
  }

  def close(): Unit =
    releaseAction.map(_.unsafeRunTimed(1.minute))
}
