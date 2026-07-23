version := "0.1.0-SNAPSHOT"
name := "openapi-client"
organization := "org.openapitools"

scalaVersion := "3.3.8"

libraryDependencies ++= Seq(
  "com.softwaremill.sttp.client4" %% "core"                          % "4.0.25",
  "com.softwaremill.sttp.client4" %% "jsoniter"                      % "4.0.25",
  "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core"   % "2.38.14",
  "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.38.14" % "compile-internal",
  "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-circe"  % "2.38.14"
)

scalacOptions := Seq(
  "-unchecked",
  "-deprecation",
  "-feature"
)