import java.io.PrintWriter
import sbt.{Def, TaskKey, _}
import Keys._
import SolidityPlugin.autoImport.solidityCompile

object SolidityPlugin extends AutoPlugin {

  case class SolcInputSource(dir: java.io.File, base: Option[java.io.File])
  def solcSource(dir: java.io.File): SolcInputSource                     = SolcInputSource(dir, None)
  def solcSource(dir: java.io.File, base: java.io.File): SolcInputSource = SolcInputSource(dir, Some(base))

  object autoImport {
    lazy val solidityCompile   = TaskKey[Unit]("solidityCompile", "Compiles solidity contracts")
    lazy val soliditySourceDir = SettingKey[Seq[SolcInputSource]]("input directories")
    lazy val targetEvm         = SettingKey[String]("evm version target")
    lazy val solidityCompileRuntime =
      TaskKey[Unit]("solidityCompileRuntime", "Compiles solidity contracts without constructor")
    lazy val baseSoliditySettings: Seq[Def.Setting[_]] = Seq(
      soliditySourceDir := Seq(solcSource(sourceDirectory.value / "solidity")),
      targetEvm := "london",
      solidityCompile := {
        import sys.process._

        val contractsDir = soliditySourceDir.value
        val outDir       = target.value / "contracts"

        contractsDir.filter(_.dir.exists()).foreach { contract =>
          (contract.dir ** "*.sol").get.foreach { f =>
            Seq("solc", f.getPath, "--bin", "--overwrite", "-o", outDir.getPath).!!

            // this is a temporary workaround, see: https://github.com/ethereum/solidity/issues/1732
            val abiOut    = Seq("solc", f.getPath, "--abi").!!
            val abisLines = abiOut.split("\n").sliding(4, 4)
            abisLines.foreach { abiLines =>
              val contractName = abiLines(1)
                .replace(f.getPath, "")
                .dropWhile(_ != ':')
                .drop(1)
                .takeWhile(_ != ' ')
              new PrintWriter(outDir / s"$contractName.abi") {
                write(abiLines.drop(3).mkString);
                close()
              }
            }
          }

        }
      },
      solidityCompileRuntime := {
        import sys.process._
        val contractsDir = soliditySourceDir.value

        val outDir = target.value / "contracts"
        contractsDir.filter(_.dir.exists()).foreach { base =>
          (base.dir ** "*.sol").get.foreach { f =>
            Seq(
              "solc",
              "--base-path",
              base.base.getOrElse(base.dir).getPath,
              "--evm-version",
              targetEvm.value,
              "--bin-runtime",
              "--overwrite",
              "-o",
              outDir.getPath,
              f.getPath
            ).!!
          }
        }
      },
      managedClasspath += target.value / "contracts"
    )
  }

  import autoImport._
  override def projectSettings: Seq[Def.Setting[_]] =
    inConfig(Test)(baseSoliditySettings) ++ inConfig(Compile)(baseSoliditySettings) ++ inConfig(IntegrationTest)(
      baseSoliditySettings
    )

}
