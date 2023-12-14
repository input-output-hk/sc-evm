import SolidityPlugin.solcSource
import com.typesafe.sbt.SbtGit.GitKeys._
import com.typesafe.sbt.SbtNativePackager.autoImport.NativePackagerHelper._

import scala.sys.process._

autoCompilerPlugins := true

// Enable dev mode: disable certain flags, etc.
val scEvmDev      = sys.props.get("scEvmDev").contains("true") || sys.env.get("SC_EVM_DEV").contains("true")
val rootDirectory = file(".")

val isRunningInCI = sys.env.get("CI").contains("true")

lazy val compilerOptimizationsForProd = Seq(
  "-opt:l:method",              // method-local optimizations
  "-opt:l:inline",              // inlining optimizations
  "-opt-inline-from:io.iohk.**" // inlining the project only
)

val `scala-2.13`           = "2.13.10"
val supportedScalaVersions = List(`scala-2.13`)

val baseScalacOptions = Seq(
  "-unchecked",
  "-deprecation",
  "-feature",
  "-Ywarn-unused",
  "-Xlint",
  "-encoding",
  "utf-8",
  "-Ymacro-annotations"
)

// ideally use this in every subproject
val noWarnings = Seq.empty

val exemptWarnings = Seq(
  "cat=unused&msg=^Unused\\ import:w", // needed by scalafix to remove unused imports
  // Let's turn those gradually into errors:(
  "cat=unused:ws",
  "cat=deprecation:ws",
  "cat=lint-package-object-classes:ws",
  "cat=lint-infer-any:ws",
  "cat=lint-byname-implicit:ws"
)

def fatalWarnings(warnings: Seq[String]): Seq[String] = Seq(if (sys.env.get("SC_EVM_FULL_WARNS").contains("true")) {
  "-Wconf:any:w"
} else {
  "-Wconf:" ++ (warnings :+ "any:e").mkString(",")
}) ++ Seq("-Ypatmat-exhaust-depth", "off")

def githubToken = sys.env.get("GITHUB_TOKEN")
  .orElse(Some(System.getProperty("github.token")))
  .getOrElse(
    throw new Exception(
      "environment variable GITHUB_TOKEN or Java property github.token" +
        " needs to be set to a GitHub token with read:packages permission"
    )
  )


inThisBuild(
  List(
    organization := "io.iohk",
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    scalaVersion := `scala-2.13`,
    scalafixScalaBinaryVersion := CrossVersion.binaryScalaVersion(
      scalaVersion.value
    ),
    credentials += Credentials("GitHub Package Registry", "maven.pkg.github.com", "_", githubToken),
    // Scalanet snapshots are published to Sonatype after each build.
    resolvers ++= Seq(
      "Sonatype OSS Snapshots".at("https://oss.sonatype.org/content/repositories/snapshots"),
      "GitHub Package Registry (input-output-hk/armadillo)".at(
        "https://maven.pkg.github.com/input-output-hk/armadillo"
      )
    ),
    scalafixDependencies ++= List(
      "com.github.liancheng" %% "organize-imports" % "0.6.0",
      "com.github.vovapolu"  %% "scaluzzi"         % "0.1.23"
    ),
    // Only publish selected libraries.
    publish / skip := true
  )
)

// https://github.com/sbt/sbt/issues/3570
updateOptions := updateOptions.value.withGigahorse(false)

// prevent execution in parallel for tests
(concurrentRestrictions in Global) ++= (if (isRunningInCI)
                                          Seq(Tags.limit(Tags.Test, 1))
                                        else Nil)

// artifact name will include scala version
crossPaths := true

def commonSettings(projectName: String, warnings: Seq[String] = exemptWarnings): Seq[sbt.Def.Setting[_]] = Seq(
  name := projectName,
  crossScalaVersions := List(`scala-2.13`),
  semanticdbEnabled := true,                        // enable SemanticDB
  semanticdbVersion := scalafixSemanticdb.revision, // use Scalafix compatible version
  Test / testOptions += Tests
    .Argument(
      TestFrameworks.ScalaTest,
      "-oSI"
    ),
  scalacOptions := baseScalacOptions ++ fatalWarnings(warnings),
  scalacOptions ++= (if (scEvmDev) Seq.empty else compilerOptimizationsForProd),
  Compile / console / scalacOptions ~= (_.filterNot(
    Set(
      "-Ywarn-unused-import",
      "-Xfatal-warnings"
    )
  )),
  scalacOptions ~= (options => if (scEvmDev) options.filterNot(_ == "-Xfatal-warnings") else options),
  Test / parallelExecution := !isRunningInCI,
  (Test / scalastyleConfig) := file("scalastyle-test-config.xml"),
  IntegrationTest / parallelExecution := false,
  Compile / doc / sources := Nil,
  Compile / packageDoc / publishArtifact := false,
  libraryDependencies ++= Dependencies.betterMonadicFor,
  libraryDependencies += compilerPlugin(Dependencies.kindProjectorPlugin.cross(CrossVersion.full))
) ++
  inConfig(IntegrationTest)(Defaults.itSettings) ++
  inConfig(IntegrationTest)(org.scalafmt.sbt.ScalafmtPlugin.scalafmtConfigSettings) ++
  inConfig(IntegrationTest)(scalafixConfigSettings(IntegrationTest))

lazy val consensus = project
  .in(file("modules/consensus"))
  .configs(IntegrationTest)
  .enablePlugins(SolidityPlugin)
  .dependsOn(execution % "test->test;compile->compile;it->test", metrics, solidity % "it->compile")
  .settings(commonSettings("consensus"))
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.testing,
      Dependencies.itTesting
    ).flatten
  )
  .settings(
    IntegrationTest / compile := (IntegrationTest / compile)
      .dependsOn(IntegrationTest / solidityCompile)
      .dependsOn(IntegrationTest / solidityCompileRuntime)
      .value,
    IntegrationTest / managedClasspath += target.value / "contracts"
  )

lazy val core = project
  .in(file("modules/core"))
  .configs(IntegrationTest)
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(crypto, rlp)
  .settings(commonSettings("core"))
  // TODO move back to node module if possible
  .settings(
    buildInfoKeys := Seq[BuildInfoKey](
      name,
      version,
      scalaVersion,
      sbtVersion,
      gitHeadCommit,
      gitCurrentBranch,
      gitCurrentTags,
      gitDescribedVersion,
      gitUncommittedChanges,
      Compile / libraryDependencies,
      BuildInfoKey.action("nixInputHash") {
        (for {
          _           <- sys.env.get("NIX_BUILD_TOP")
          nixStorePath = raw"/nix/store/(\w+).*".r
          outVar      <- Option(System.getenv("out")).map(_.trim)
          hash <- outVar match {
                    case nixStorePath(hash) => Some(hash)
                    case _                  => None
                  }
        } yield hash).getOrElse("impure")
      },
      BuildInfoKey.action("gitRev") {
        sys.env.get("NIX_GIT_REVISION") match {
          case Some(rev) => rev
          case None =>
            "git diff-index --quiet HEAD --".! match {
              case 0 => "git rev-parse HEAD".!!.trim()
              case _ => "dirty"
            }
        }
      }
    ),
    buildInfoPackage := "io.iohk.scevm.utils",
    Compile / buildInfoOptions += BuildInfoOption.ToMap
  )
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.boopickle,
      Dependencies.dependencies,
      Dependencies.catsStack,
      Dependencies.logging,
      Dependencies.fs2,
      Dependencies.shapeless,
      Dependencies.guava,
      Dependencies.testing,
      Dependencies.itTesting,
      Dependencies.json4s,
      Dependencies.newtype,
      Dependencies.quicklens
    ).flatten
  )

lazy val execution = project
  .in(file("modules/execution"))
  .configs(IntegrationTest)
  .dependsOn(storage % "test->test;compile->compile", metrics)
  .settings(commonSettings("execution"))
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.rhino,
      Dependencies.testing,
      Dependencies.itTesting
    ).flatten
  )

lazy val executionBench = project
  .in(file("tooling/execution-bench"))
  .dependsOn(execution % "test->test", evmTest % "test->test")
  .settings(commonSettings("execution-bench"))
  .settings(
    Jmh / sourceDirectory := (Test / sourceDirectory).value,
    Jmh / classDirectory := (Test / classDirectory).value,
    Jmh / dependencyClasspath := (Test / dependencyClasspath).value,
    // rewire tasks, so that 'jmh:run' automatically invokes 'jmh:compile' (otherwise a clean 'jmh:run' would fail)
    Jmh / compile := (Jmh / compile).dependsOn(Test / compile).value,
    Jmh / run := (Jmh / run).dependsOn(Jmh / Keys.compile).evaluated
  )
  .enablePlugins(JmhPlugin)

lazy val externalvm = project
  .in(file("modules/externalvm"))
  .configs(IntegrationTest)
  .dependsOn(core, execution % "test->test;compile->compile")
  .dependsOn(storage % "test->test;compile->compile")
  .settings(commonSettings("externalvm"))
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.akka,
      Dependencies.enumeratum,
      Dependencies.fs2,
      Dependencies.testing,
      Dependencies.itTesting
    ).flatten
  )
  .settings(
    // NOTE `sbt-protoc` and `sbt-buildinfo` do not work well together,
    //      see https://github.com/sbt/sbt-buildinfo/issues/104
    //      and https://github.com/thesamet/sbt-protoc/issues/6
    //      That is why generate protobuf code in another folder
    //      (`protobuf`).
    Compile / PB.targets := Seq(
      scalapb.gen() -> (Compile / sourceManaged).value / "protobuf"
    )
  )

lazy val cardanoFollower = project
  .in(file("modules/cardano-follower"))
  .configs(IntegrationTest)
  .dependsOn(
    core   % "test->test;compile->compile;it->test",
    plutus % "test->test;compile->compile",
    trustlessSidechain
  )
  .settings(commonSettings("cardano-follower"))
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.doobie,
      Dependencies.circe,
      Dependencies.testing,
      Dependencies.itTesting,
      Dependencies.dbTesting,
      Dependencies.borer
    ).flatten
  )

lazy val plutus = project
  .in(file("external/plutus"))
  .configs(IntegrationTest)
  .dependsOn(bytes)
  .settings(commonSettings("plutus"))
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.borer,
      Dependencies.cats,
      Dependencies.kittens,
      Dependencies.testing,
      Dependencies.itTesting,
      Dependencies.magnolia(scalaVersion.value)
    ).flatten
  )

// TODO ideally solidity should be in external modules with no dependencies on core, or execution.
// But it's depending on UInt256 and Address so this need to be sorted out before it's moved to external
lazy val solidity = project
  .in(file("modules/solidity"))
  .settings(commonSettings("solidity"))
  .dependsOn(bytes, crypto, core, execution % "test->test")
  .settings(
    libraryDependencies ++= Seq(Dependencies.testing).flatten
  )

lazy val trustlessSidechain = project
  .in(file("modules/trustless-sidechain"))
  .dependsOn(core % "test->test;compile->compile;", plutus, core)
  .settings(commonSettings("trustless-sidechain"))
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.circe,
      Dependencies.testing,
      Dependencies.borer
    ).flatten
  )

lazy val metrics = project
  .in(file("modules/metrics"))
  .configs(IntegrationTest)
  .dependsOn(core)
  .settings(commonSettings("metrics"))
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.http4s,
      Dependencies.prometheus,
      Dependencies.trace4cats
    ).flatten
  )

lazy val network = project
  .in(file("modules/network"))
  .configs(IntegrationTest)
  .dependsOn(
    consensus % "test->test;compile->compile",
    metrics
  )
  .settings(commonSettings("network"))
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.akka,
      Dependencies.akkaHttp,
      Dependencies.akkaTypedTestkit,
      Dependencies.scaffeine,
      Dependencies.testing,
      Dependencies.itTesting
    ).flatten
  )

lazy val rpc = project
  .in(file("modules/rpc"))
  .configs(IntegrationTest)
  .dependsOn(
    core      % "test->test;compile->compile",
    storage   % "test->test;compile->compile",
    network   % "test->test;compile->compile",
    consensus % "test->test;compile->compile",
    metrics,
    execution
  )
  .settings(commonSettings("rpc"))
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.catsStack,
      Dependencies.testing,
      Dependencies.itTesting,
      Dependencies.json4s,
      Dependencies.armadillo,
      Dependencies.tapir,
      Dependencies.circe
    ).flatten
  )

lazy val sidechainRpc = project
  .in(file("modules/sidechain-rpc"))
  .configs(IntegrationTest)
  .dependsOn(
    rpc             % "test->test;compile->compile",
    sidechain       % "test->test;compile->compile",
    cardanoFollower % "test->test;compile->compile"
  )
  .settings(commonSettings("sidechain-rpc"))
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.catsStack,
      Dependencies.testing,
      Dependencies.itTesting,
      Dependencies.json4s,
      Dependencies.armadillo,
      Dependencies.tapir
    ).flatten
  )

lazy val storage = project
  .in(file("modules/storage"))
  .configs(IntegrationTest)
  .dependsOn(
    core % "test->test;compile->compile",
    metrics
  )
  .settings(commonSettings("storage"))
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.guava,
      Dependencies.rocksDb,
      Dependencies.scalamock,
      Dependencies.testing,
      Dependencies.itTesting
    ).flatten
  )

lazy val sync = project
  .in(file("modules/sync"))
  .configs(IntegrationTest)
  .dependsOn(
    core      % "test->test;compile->compile",
    consensus % "test->test;compile->compile",
    network   % "test->test;compile->compile"
  )
  .settings(commonSettings("sync", noWarnings))
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.testing,
      Dependencies.itTesting,
      Dependencies.catsRetry
    ).flatten
  )

lazy val bytes = project
  .in(file("external/bytes"))
  .configs(IntegrationTest)
  .settings(commonSettings("bytes"))
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.akkaUtil,
      Dependencies.testing,
      Dependencies.itTesting,
      Dependencies.cats
    ).flatten
  )

lazy val crypto = project
  .in(file("external/crypto"))
  .configs(IntegrationTest)
  .dependsOn(bytes)
  .settings(commonSettings("crypto"))
  .settings(
    libraryDependencies ++=
      Seq(
        Dependencies.catsStack,
        Dependencies.crypto,
        Dependencies.testing,
        Dependencies.itTesting
      ).flatten
  )

lazy val rlp = project
  .in(file("external/rlp"))
  .configs(IntegrationTest)
  .dependsOn(bytes)
  .settings(commonSettings("rlp"))
  .settings(
    libraryDependencies ++=
      Seq(Dependencies.shapeless, Dependencies.testing, Dependencies.itTesting).flatten
  )

lazy val evmTest = project
  .in(file("tooling/evmTest"))
  .enablePlugins(SolidityPlugin)
  .configs(IntegrationTest)
  .dependsOn(execution % "test->test;compile->compile")
  .settings(commonSettings("evm-test"))
  .settings(libraryDependencies ++= Seq(Dependencies.circe, Dependencies.testing, Dependencies.itTesting).flatten)
  .settings(Test / compile := (Test / compile).dependsOn(Test / solidityCompile).value)

lazy val sidechain = project
  .in(file("modules/sidechain"))
  .enablePlugins(SolidityPlugin)
  .configs(IntegrationTest)
  .dependsOn(
    execution,
    cardanoFollower % "test->test;compile->compile",
    consensus       % "test->test;compile->compile;it->it",
    core            % "test->test;compile->compile;it->test",
    sync            % "test->test;compile->compile;it->test",
    solidity,
    trustlessSidechain
  )
  .settings(commonSettings("sidechain"))
  .settings(
    Test / soliditySourceDir := Seq(
      solcSource(rootDirectory / "src" / "solidity-bridge" / "contracts"),
      solcSource(
        dir = (Test / sourceDirectory).value / "solidity",
        base = rootDirectory / "src" / "solidity-bridge" / "contracts"
      )
    ),
    Test / compile := (Test / compile).dependsOn(Test / solidityCompileRuntime).value,
    libraryDependencies ++= Seq(
      Dependencies.doobie,
      Dependencies.circe,
      Dependencies.testing,
      Dependencies.itTesting,
      Dependencies.dbTesting,
      Dependencies.scaffeine,
      Dependencies.borer
    ).flatten
  )

lazy val node = {
  Test / scalastyleSources ++= (IntegrationTest / unmanagedSourceDirectories).value

  val node = project
    .in(file("modules/node"))
    .configs(IntegrationTest)
    .enablePlugins(
      JDKPackagerPlugin,
      JavaAppPackaging,
      BuildInfoPlugin,
      UniversalPlugin,
      BatStartScriptPlugin,
      BashStartScriptPlugin
    )
    .dependsOn(
      network % "test->test;compile->compile",
      sync,
      rpc,
      execution,
      externalvm,
      sidechainRpc
    )
    .settings(commonSettings("sc-evm"): _*)
    .settings(
      libraryDependencies ++= Seq(
        Dependencies.akka,
        Dependencies.akkaHttp,
        Dependencies.catsStack,
        Dependencies.testing,
        Dependencies.itTesting
      ).flatten,
      executableScriptName := name.value
    )
    .settings(
      inConfig(IntegrationTest)(
        Defaults.testSettings
          ++ org.scalafmt.sbt.ScalafmtPlugin.scalafmtConfigSettings :+ (Test / parallelExecution := false)
      ): _*
    )
    .settings(
      // Packaging
      Compile / mainClass := Some("io.iohk.scevm.App"),
      Compile / discoveredMainClasses := Seq(),
      // Requires the 'ant-javafx.jar' that comes with Oracle JDK
      // Enables creating an executable with the configuration files, has to be run on the OS corresponding to the desired version
      Universal / mappings ++= directory((Compile / resourceDirectory).value / "conf"),
      Universal / mappings += (Compile / resourceDirectory).value / "logback.xml" -> "conf/logback.xml",
      bashScriptExtraDefines += """addJava "-Dconfig.file=${app_home}/../conf/app.conf"""",
      bashScriptExtraDefines += """addJava "-Dlogback.configurationFile=${app_home}/../conf/logback.xml"""",
      batScriptExtraDefines += """call :add_java "-Dconfig.file=%APP_HOME%\conf\app.conf"""",
      batScriptExtraDefines += """call :add_java "-Dlogback.configurationFile=%APP_HOME%\conf\logback.xml"""",
      dockerBaseImage := "openjdk:17",
      dockerUpdateLatest := true,
      Docker / packageName := "scevm_image"
    )
  node

}

lazy val testnode = project
  .in(file("tooling/testnode"))
  .settings(commonSettings("testnode"))
  .dependsOn(node, rpc % "test->test;compile->compile")

lazy val sidechainTest = project
  .in(file("tooling/sidechainTest"))
  .configs(IntegrationTest)
  .settings(commonSettings("testing-sidechain"))
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.itTesting
    ).flatten
  )
  .dependsOn(node)

lazy val dataGenerator = project
  .in(file("tooling/dataGenerator"))
  .enablePlugins(SolidityPlugin)
  .configs(IntegrationTest)
  .settings(commonSettings("data-generator"))
  .settings(libraryDependencies ++= Seq(Dependencies.scalaCheck))
  .settings(libraryDependencies ++= Dependencies.testing)
  .settings(Compile / compile := (Compile / compile).dependsOn(Compile / solidityCompile).value)
  .dependsOn(
    consensus,
    execution,
    storage,
    core,
    node
  )

lazy val scEvmCli = project
  .in(file("tooling/scEvmCli"))
  .settings(commonSettings("sc-evm-cli"))
  .settings(
    libraryDependencies ++= Dependencies.decline,
    libraryDependencies ++= Dependencies.sttp
  )
  .enablePlugins(JavaAppPackaging)
  .dependsOn(
    jsonRpcClient,
    sidechain,
    solidity,
    storage,
    trustlessSidechain
  )

lazy val relay = project
  .in(file("tooling/relay"))
  .settings(commonSettings("relay"))
  .settings(
    libraryDependencies ++= Dependencies.catsStack,
    libraryDependencies ++= Dependencies.circe,
    libraryDependencies ++= Dependencies.decline,
    libraryDependencies ++= Dependencies.logging,
    libraryDependencies ++= Dependencies.munit,
    libraryDependencies ++= Dependencies.sttp
  )
  .enablePlugins(JavaAppPackaging)
  .dependsOn(
    bytes,
    crypto
  )

lazy val observer = project
  .in(file("tooling/observer"))
  .settings(commonSettings("observer"))
  .enablePlugins(JavaAppPackaging)
  .dependsOn(metrics)
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.doobie,
      Dependencies.circe,
      Dependencies.sttp
    ).flatten
  )

lazy val jsonRpcClient = project
  .in(file("tooling/json-rpc-client"))
  .settings(commonSettings("json-rpc-client"))
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.circe,
      Dependencies.sttp
    ).flatten
  )

addCommandAlias(
  "compileAll",
  s""";compile
     |;Test/compile
     |;IntegrationTest/compile
     |""".stripMargin
)

addCommandAlias(
  "formatAll",
  s""";compileAll
     |;scalafmtSbt
     |;scalafmtAll
     |;scalafmtSbt
     |;scalafixAll
     |""".stripMargin
)

//validate - used for CI
addCommandAlias(
  "validate",
  s""";compileAll
     |;scalafmtSbtCheck
     |;scalafmtCheckAll
     |;scalafixAll --check
     |;test
     |;IntegrationTest/test
     |;testQuick
     |""".stripMargin
)
