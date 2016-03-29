import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys

import scalariform.formatter.preferences._

// Metadata and build publication settings

name := """atomic-store"""

version := "0.0.2"

organization := "net.artsy"

homepage := Some(url("https://github.com/artsy/atomic-store"))

licenses +=("MIT", url("https://opensource.org/licenses/MIT"))

scalaVersion := "2.11.8"

resolvers += "dnvriend at bintray" at "http://dl.bintray.com/dnvriend/maven"

publishMavenStyle := true

publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases"  at nexus + "service/local/staging/deploy/maven2")
}

publishArtifact in Test := false

pomExtra := (
  <scm>
    <url>git@github.com:artsy/atomic-store.git</url>
    <connection>scm:git:git@github.com:artsy/atomic-store.git</connection>
  </scm>
  <developers>
    <developer>
      <id>acjay</id>
      <name>Alan Johnson</name>
      <url>http://www.acjay.com</url>
    </developer>
  </developers>)

// Code settings

libraryDependencies ++= Seq(
  "com.typesafe.akka"         %% "akka-testkit"               % "2.4.2",
  "org.scalatest"             %% "scalatest"                  % "2.2.6" % "test",
  "org.iq80.leveldb"          %  "leveldb"                    % "0.7", // For LevelDB journal
  "org.fusesource.leveldbjni" %  "leveldbjni-all"             % "1.8", // For LevelDB journal
  "com.github.dnvriend"       %% "akka-persistence-inmemory"  % "1.2.8"
)

fork := true

SbtScalariform.scalariformSettings

ScalariformKeys.preferences := ScalariformKeys.preferences.value
  .setPreference(AlignArguments, true)
  .setPreference(AlignParameters, true)
  .setPreference(AlignSingleLineCaseStatements, true)
  .setPreference(DoubleIndentClassDeclaration, false)