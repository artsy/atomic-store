import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys

import scalariform.formatter.preferences._

// Metadata and build publication settings

name := """atomic-store"""

version := "0.0.6-SNAPSHOT"

scalaVersion := "2.11.11"

resolvers += "dnvriend at bintray" at "http://dl.bintray.com/dnvriend/maven"

organization := "net.artsy"
publishMavenStyle := true
pgpPassphrase := Some(scala.util.Properties.envOrElse("GPG_PASS", "gpg-password").toArray)

scalacOptions ++= Seq("-unchecked", "-deprecation")

// Code settings

val akkaV = "2.5.1"

libraryDependencies ++= Seq(
  "com.typesafe.akka"         %% "akka-actor"                 % akkaV,
  "com.typesafe.akka"         %% "akka-testkit"               % akkaV,
  "org.scalatest"             %% "scalatest"                  % "3.0.1"  % "test",
  "org.scalacheck"            %% "scalacheck"                 % "1.13.4" % "test",    // Property-based testing
  "com.github.dnvriend"       %% "akka-persistence-inmemory"  % "2.5.0.0"
)

fork := true

SbtScalariform.scalariformSettings

ScalariformKeys.preferences := ScalariformKeys.preferences.value
  .setPreference(AlignArguments, true)
  .setPreference(AlignParameters, true)
  .setPreference(AlignSingleLineCaseStatements, true)
  .setPreference(DoubleIndentClassDeclaration, false)
