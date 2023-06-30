package io.iohk.scevm

import com.typesafe.config.{ConfigException, ConfigFactory}
import org.scalatest.wordspec.AnyWordSpec

class ScEvmConfigParsingSpec extends AnyWordSpec {
  "config has to have 'chain-mode' specified" in {
    assertThrows[ConfigException](ScEvmConfig.fromConfig(ConfigFactory.load("base").getConfig("sc-evm")))
  }
  "should parse config with 'chain-mode = standalone'" in {
    ScEvmConfig.fromConfig(ConfigFactory.load("conf/local-standalone/application.conf").getConfig("sc-evm"))
  }
}
