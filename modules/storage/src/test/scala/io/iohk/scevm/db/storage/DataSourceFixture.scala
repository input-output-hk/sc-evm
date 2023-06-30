package io.iohk.scevm.db.storage

import io.iohk.scevm.db.dataSource.{DataSource, EphemDataSource}
import org.scalatest.{FixtureAsyncTestSuite, FixtureTestSuite, FutureOutcome, Outcome}

trait DataSourceFixture extends FixtureTestSuite {

  def initFixture(dataSource: DataSource): FixtureParam

  override protected def withFixture(test: OneArgTest): Outcome = {
    lazy val dataSource = EphemDataSource()
    val fixture         = initFixture(dataSource)
    super.withFixture(test.toNoArgTest(fixture))
  }
}

trait AsyncDataSourceFixture extends FixtureAsyncTestSuite {

  def initFixture(dataSource: DataSource): FixtureParam

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    lazy val dataSource = EphemDataSource()
    val fixture         = initFixture(dataSource)

    super.withFixture(test.toNoArgAsyncTest(fixture))
  }
}
