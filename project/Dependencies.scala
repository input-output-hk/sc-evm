import sbt._

object Dependencies {

  private val akkaVersion = "2.6.20"

  val akka: Seq[ModuleID] =
    Seq(
      "com.typesafe.akka" %% "akka-actor"          % akkaVersion,
      "com.typesafe.akka" %% "akka-actor-typed"    % akkaVersion,
      "com.typesafe.akka" %% "akka-slf4j"          % akkaVersion,
      "com.typesafe.akka" %% "akka-testkit"        % akkaVersion % "it,test",
      "com.typesafe.akka" %% "akka-stream"         % akkaVersion,
      "com.miguno.akka"   %% "akka-mock-scheduler" % "0.5.5"     % "it,test"
    )

  val akkaHttp: Seq[ModuleID] = {
    val akkaHttpVersion = "10.2.10"
    Seq(
      "com.typesafe.akka" %% "akka-http"         % akkaHttpVersion,
      "ch.megard"         %% "akka-http-cors"    % "1.1.3",
      "de.heikoseeberger" %% "akka-http-json4s"  % "1.39.2",
      "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % "it,test"
    )
  }

  val akkaTestkit: Seq[ModuleID]      = Seq("com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test")
  val akkaTypedTestkit: Seq[ModuleID] = Seq("com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test)
  val akkaUtil: Seq[ModuleID]         = Seq("com.typesafe.akka" %% "akka-actor" % akkaVersion)
  val betterMonadicFor: Seq[ModuleID] = Seq(compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"))
  val boopickle: Seq[ModuleID]        = Seq("io.suzaku" %% "boopickle" % "1.4.0")

  val cats: Seq[ModuleID]    = Seq("org.typelevel" %% "cats-core" % "2.9.0")
  val kittens: Seq[ModuleID] = Seq("org.typelevel" %% "kittens" % "3.0.0-M3")

  val catsStack: Seq[ModuleID] = cats ++ kittens ++
    Seq(
      "org.typelevel" %% "mouse"               % "1.2.1",
      "org.typelevel" %% "cats-effect"         % "3.4.7",
      "org.typelevel" %% "cats-effect-testkit" % "3.4.7" % Test,
      "org.typelevel" %% "log4cats-core"       % "2.5.0",
      "org.typelevel" %% "log4cats-slf4j"      % "2.5.0",
      "org.typelevel" %% "log4cats-noop"       % "2.5.0" % Test
    )

  val catsRetry: Seq[ModuleID] = Seq("com.github.cb372" %% "cats-retry" % "3.1.0")

  val circe: Seq[ModuleID] = {
    val circeVersion = "0.14.3"
    Seq(
      "io.circe" %% "circe-core"           % circeVersion,
      "io.circe" %% "circe-generic"        % circeVersion,
      "io.circe" %% "circe-parser"         % circeVersion,
      "io.circe" %% "circe-generic-extras" % circeVersion,
      "io.circe" %% "circe-literal"        % circeVersion
    )
  }

  val config: Seq[ModuleID] = Seq("com.typesafe" % "config" % "1.4.2")

  val decline: Seq[ModuleID] = {
    val declineVersion = "2.3.0"
    Seq(
      "com.monovore" %% "decline"        % declineVersion,
      "com.monovore" %% "decline-effect" % declineVersion
    )
  }

  val crypto = Seq("org.bouncycastle" % "bcprov-jdk15on" % "1.66")

  val dependencies = Seq(
    "org.jline"               % "jline"             % "3.21.0",
    "org.scala-sbt.ipcsocket" % "ipcsocket"         % "1.4.0",
    "org.xerial.snappy"       % "snappy-java"       % "1.1.8.4",
    "org.web3j"               % "core"              % "5.0.0" % Test,
    "javax.servlet"           % "javax.servlet-api" % "4.0.1"
  )

  val enumeratum: Seq[ModuleID] = Seq(
    "com.beachape" %% "enumeratum"      % "1.7.2",
    "com.beachape" %% "enumeratum-cats" % "1.7.2"
  )

  val fs2: Seq[ModuleID] = {
    val fs2Version = "3.6.1"
    Seq(
      "co.fs2" %% "fs2-core"             % fs2Version,
      "co.fs2" %% "fs2-reactive-streams" % fs2Version,
      "co.fs2" %% "fs2-io"               % fs2Version,
      "co.fs2" %% "fs2-scodec"           % fs2Version
    )
  }

  val fs2Grpc: Seq[ModuleID] = Seq("org.typelevel" %% "fs2-grpc-runtime" % _root_.fs2.grpc.buildinfo.BuildInfo.version)

  val grpcNetty: Seq[ModuleID] = Seq(
    "io.grpc"   % "grpc-netty"                    % scalapb.compiler.Version.grpcJavaVersion,
    ("io.netty" % "netty-transport-native-kqueue" % "4.1.86.Final").classifier("osx-x86_64"),
    ("io.netty" % "netty-transport-native-kqueue" % "4.1.86.Final").classifier("osx-aarch_64"),
    ("io.netty" % "netty-transport-native-epoll"  % "4.1.88.Final").classifier("linux-x86_64")
  )

  val guava: Seq[ModuleID] = {
    val version = "31.0.1-jre"
    Seq(
      "com.google.guava" % "guava"         % version,
      "com.google.guava" % "guava-testlib" % version % "test"
    )
  }

  val http4s: Seq[ModuleID] = Seq(
    "org.http4s" %% "http4s-blaze-client" % "0.23.11"
  )

  val json4s: Seq[ModuleID]         = Seq("org.json4s" %% "json4s-native" % "4.0.4")
  val kindProjectorPlugin: ModuleID = "org.typelevel" % "kind-projector" % "0.13.2"

  val logging = Seq(
    "ch.qos.logback"              % "logback-classic"          % "1.2.10",
    "com.typesafe.scala-logging" %% "scala-logging"            % "3.9.4",
    "net.logstash.logback"        % "logstash-logback-encoder" % "7.0.1",
    "org.codehaus.janino"         % "janino"                   % "3.1.6"
  )

  val munit: Seq[ModuleID] = Seq(
    "org.scalameta" %% "munit"               % "0.7.29" % Test,
    "org.typelevel" %% "munit-cats-effect-3" % "1.0.7"  % Test
  )

  val newtype: Seq[ModuleID] = Seq("io.estatico" %% "newtype" % "0.4.4")

  val prometheus: Seq[ModuleID] = {
    val provider = "io.prometheus"
    val version  = "0.16.0"
    Seq(
      provider % "simpleclient"            % version,
      provider % "simpleclient_logback"    % version,
      provider % "simpleclient_hotspot"    % version,
      provider % "simpleclient_httpserver" % version
    )
  }

  val rocksDb: Seq[ModuleID]   = Seq("org.rocksdb" % "rocksdbjni" % "6.29.5")
  val scaffeine: Seq[ModuleID] = Seq("com.github.blemale" %% "scaffeine" % "5.2.1")
  val scalamock: Seq[ModuleID] = Seq("org.scalamock" %% "scalamock" % "5.2.0" % "it,test")
  val scopt: Seq[ModuleID]     = Seq("com.github.scopt" %% "scopt" % "4.1.0")
  val shapeless: Seq[ModuleID] = Seq("com.chuusai" %% "shapeless" % "2.3.10")

  val scalaCheck: ModuleID = "org.scalacheck" %% "scalacheck" % "1.17.0"

  val itTesting: Seq[ModuleID] = Seq(
    "org.scalatest" %% "scalatest" % "3.2.15" % "it",
    scalaCheck       % "it"
  )

  val testing: Seq[ModuleID] = Seq(
    "org.scalatest"     %% "scalatest"       % "3.2.15",
    "org.scalatestplus" %% "scalacheck-1-17" % "3.2.15.0",
    scalaCheck,
    "com.softwaremill.diffx" %% "diffx-scalatest-should" % "0.8.2"
  ).map(_ % Test)

  val doobie: Seq[ModuleID] = {
    val version = "1.0.0-RC2"
    Seq(
      "org.tpolecat" %% "doobie-core"     % version,
      "org.tpolecat" %% "doobie-hikari"   % version, // HikariCP transactor.
      "org.tpolecat" %% "doobie-postgres" % version  // Postgres driver 42.3.1 + type mappings.
    )
  }
  val dbTesting: Seq[ModuleID] = {
    val version = "0.40.5"
    Seq(
      "com.dimafeng" %% "testcontainers-scala-scalatest"  % version % IntegrationTest,
      "com.dimafeng" %% "testcontainers-scala-postgresql" % version % IntegrationTest,
      "org.flywaydb"  % "flyway-core"                     % "8.5.5" % IntegrationTest
    )
  }
  val trace4cats: Seq[ModuleID] = {
    val version = "0.13.0"
    Seq(
      "io.janstenpickle" %% "trace4cats-core"                             % version,
      "io.janstenpickle" %% "trace4cats-inject"                           % version,
      "io.janstenpickle" %% "trace4cats-opentelemetry-otlp-http-exporter" % version
    )
  }

  val borer: Seq[ModuleID] = {
    val version = "1.8.0"
    Seq(
      "io.bullet" %% "borer-core" % version
    )
  }

  val armadillo: Seq[ModuleID] = {
    val armadilloVersion: String = "0.1.0"
    Seq(
      "io.iohk.armadillo"             %% "armadillo-server-tapir"  % armadilloVersion,
      "io.iohk.armadillo"             %% "armadillo-json-json4s"   % armadilloVersion,
      "io.iohk.armadillo"             %% "armadillo-openrpc"       % armadilloVersion,
      "io.iohk.armadillo"             %% "armadillo-openrpc-circe" % armadilloVersion,
      "io.iohk.armadillo"             %% "armadillo-server-stub"   % armadilloVersion % Test,
      "com.softwaremill.sttp.client3" %% "json4s"                  % "3.7.6"          % Test
    )
  }

  val tapir: Seq[ModuleID] = {
    val tapirVersion: String = "1.2.10"
    Seq(
      "com.softwaremill.sttp.tapir"  %% "tapir-newtype"          % tapirVersion,
      "com.softwaremill.sttp.tapir"  %% "tapir-json-json4s"      % tapirVersion,
      ("com.softwaremill.sttp.tapir" %% "tapir-akka-http-server" % tapirVersion)
        .exclude("com.typesafe.akka", "akka-stream_2.13")
        .exclude("com.typesafe.akka", "akka-slf4j_2.13"),
      "com.softwaremill.sttp.tapir" %% "tapir-sttp-stub-server" % tapirVersion % Test
    )
  }

  def magnolia(scalaVersion: String): Seq[ModuleID] =
    Seq(
      "com.softwaremill.magnolia1_2" %% "magnolia"      % "1.1.2",
      "org.scala-lang"                % "scala-reflect" % scalaVersion % Provided
    )

  val rhino: Seq[ModuleID] = {
    val version = "1.7.14"
    Seq("org.mozilla" % "rhino" % version)
  }

  val quicklens: Seq[ModuleID] = Seq(
    "com.softwaremill.quicklens" % "quicklens_2.13" % "1.8.10"
  )

  val sttp: Seq[ModuleID] = {
    val version = "3.8.5"
    Seq(
      "com.softwaremill.sttp.client3" %% "core"           % version,
      "com.softwaremill.sttp.client3" %% "http4s-backend" % version,
      "com.softwaremill.sttp.client3" %% "circe"          % version
    )
  }
}
