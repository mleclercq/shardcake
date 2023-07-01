addSbtPlugin("org.scalameta"  % "sbt-scalafmt"   % "2.4.6")
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.5.10")
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype"   % "3.9.13")
addSbtPlugin("com.thesamet"   % "sbt-protoc"     % "1.0.6")

addSbtPlugin("org.scala-js"       % "sbt-scalajs"              % "1.13.2")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.0.0")
addSbtPlugin("com.github.sbt"     % "sbt-native-packager"      % "1.9.16")

resolvers ++= Resolver.sonatypeOssRepos("snapshots")

libraryDependencies += "com.thesamet.scalapb"          %% "compilerplugin"   % "0.11.10"
libraryDependencies += "com.thesamet.scalapb.zio-grpc" %% "zio-grpc-codegen" % "0.6.0-rc5"
