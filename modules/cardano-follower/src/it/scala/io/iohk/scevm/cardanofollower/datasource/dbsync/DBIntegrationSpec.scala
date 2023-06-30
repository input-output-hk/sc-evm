package io.iohk.scevm.cardanofollower.datasource.dbsync

import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import io.iohk.scevm.cardanofollower.datasource.DatasourceConfig.DbSync
import io.iohk.scevm.cardanofollower.datasource.Sensitive
import org.flywaydb.core.Flyway
import org.scalatest.{Assertion, BeforeAndAfterEach, Suite}
import org.testcontainers.utility.DockerImageName

trait DBIntegrationSpec extends ForAllTestContainer with BeforeAndAfterEach { suite: Suite =>

  override val container: PostgreSQLContainer =
    PostgreSQLContainer(dockerImageNameOverride = DockerImageName.parse("postgres:11.5-alpine"))
  var currentDb: TestDB = _

  private def config =
    DbSync(container.username, Sensitive(container.password), container.jdbcUrl, container.driverClassName, 32)

  def withMigrations(migrations: String*)(test: => Assertion): Assertion = {
    currentDb = new TestDB(config)
    currentDb.testConnection()
    val flyway =
      Flyway
        .configure()
        .dataSource(config.url, config.username, config.password.value)
        .locations(
          s"classpath:db/migration/${Migrations.Base}" +: migrations.map(m => s"classpath:db/migration/$m"): _*
        )
        .load()
    flyway.migrate()
    try test
    finally {
      flyway.clean()
      currentDb.close()
    }
  }

}
