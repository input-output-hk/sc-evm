package io.iohk.scevm.cardanofollower.datasource.dbsync

import cats.effect.Resource
import cats.effect.kernel.Async
import com.zaxxer.hikari.HikariConfig
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import io.iohk.scevm.cardanofollower.datasource.DatasourceConfig.DbSync

class Db(config: DbSync) {
  // Resource yielding a transactor configured with a bounded connect EC and an unbounded
  // transaction EC. Everything will be closed and shut down cleanly after use.
  def transactor[F[_]: Async]: Resource[F, HikariTransactor[F]] =
    for {
      threadPool <- ExecutionContexts.fixedThreadPool[F](config.connectThreadPoolSize)
      transactor <- HikariTransactor.fromHikariConfig[F](
                      {
                        val hikariConfig = new HikariConfig()
                        hikariConfig.setDriverClassName(config.driver)
                        hikariConfig.setJdbcUrl(config.url)
                        hikariConfig.setUsername(config.username)
                        hikariConfig.setPassword(config.password.value)
                        hikariConfig.setMaximumPoolSize(config.connectThreadPoolSize)
                        hikariConfig
                      },
                      threadPool
                    )
    } yield transactor
}
