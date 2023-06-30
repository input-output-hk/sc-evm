package io.iohk.scevm.extvm

sealed trait VmConfig

object VmConfig {
  final case class InternalEVMConfig()                                          extends VmConfig
  final case class KEVMConfig(host: String, port: Int, protoApiVersion: String) extends VmConfig
  final case class IELEConfig(
      host: String,
      port: Int,
      protoApiVersion: String
  )                                                        extends VmConfig
  final case class TestModeConfig(host: String, port: Int) extends VmConfig

  import com.typesafe.config.Config
  def fromRawConfig(config: Config): VmConfig = {
    val vmConfig = config.getConfig("vm")
    val vmType   = vmConfig.getString("vm-type").toLowerCase

    vmType match {
      case "internal" => InternalEVMConfig()
      case key @ "kevm" =>
        val subConfig = vmConfig.getConfig(key)
        KEVMConfig(
          subConfig.getString("host"),
          subConfig.getInt("port"),
          subConfig.getString("proto-api-version")
        )
      case key @ "iele" =>
        val subConfig = vmConfig.getConfig(key)
        IELEConfig(
          subConfig.getString("host"),
          subConfig.getInt("port"),
          subConfig.getString("proto-api-version")
        )
      case key @ "test-mode" =>
        val subConfig = vmConfig.getConfig(key)
        TestModeConfig(subConfig.getString("host"), subConfig.getInt("port"))
      case other =>
        throw new IllegalArgumentException(s"Unknown VM type vm-type=$other")
    }
  }
}
