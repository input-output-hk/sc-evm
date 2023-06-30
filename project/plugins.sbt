logLevel := sbt.Level.Warn

addSbtPlugin("ch.epfl.scala"                     % "sbt-scalafix"          % "0.10.4")
addSbtPlugin("com.eed3si9n"                      % "sbt-buildinfo"         % "0.10.0")
addSbtPlugin("com.geirsson"                      % "sbt-ci-release"        % "1.5.6")
addSbtPlugin("com.thesamet"                      % "sbt-protoc"            % "1.0.6")
addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings"      % "3.0.0")
addSbtPlugin("com.timushev.sbt"                  % "sbt-updates"           % "0.6.1")
addSbtPlugin("com.typesafe.sbt"                  % "sbt-git"               % "1.0.0")
addSbtPlugin("com.typesafe.sbt"                  % "sbt-native-packager"   % "1.7.5")
addSbtPlugin("org.scalameta"                     % "sbt-scalafmt"          % "2.4.6")
addSbtPlugin("org.scalastyle"                   %% "scalastyle-sbt-plugin" % "1.0.0")
addSbtPlugin("pl.project13.scala"                % "sbt-jmh"               % "0.4.3")
addSbtPlugin("org.typelevel"                     % "sbt-fs2-grpc"          % "2.5.8")

addDependencyTreePlugin

libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.10"
