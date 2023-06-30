package io.iohk.scevm.testnode

import com.typesafe.config.Config

final case class TestNodeConfig(hardhatMode: Boolean)

object TestNodeConfig {
  def fromConfig(rawConfig: Config): TestNodeConfig =
    TestNodeConfig(rawConfig.getBoolean("test-node.hardhat-mode"))
}
