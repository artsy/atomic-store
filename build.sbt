import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys

import scalariform.formatter.preferences._

// Metadata and build publication settings

name         in ThisBuild := """atomic-store"""
version      in ThisBuild := "0.0.7-SNAPSHOT"
organization in ThisBuild := "net.artsy"
homepage     in ThisBuild :=  Some(url("https://github.com/artsy/atomic-store"))
licenses     in ThisBuild +=  ("MIT", url("https://opensource.org/licenses/MIT"))
scmInfo      in ThisBuild :=  Some(
                                ScmInfo(
                                  url("https://github.com/artsy/atomic-store"),
                                  "scm:git@github.com:artsy/atomic-store.git"
                                )
                              )
developers   in ThisBuild :=  List(
                                Developer(
                                  id    = "acjay",
                                  name  = "Alan Johnson",
                                  email = "alan@breakrs.com",
                                  url   = url("http://www.acjay.com")
                                )
                              )
scalaVersion in ThisBuild := "2.12.3"

val akkaV = "2.5.4"
lazy val root = project.in(file("."))
  .settings(
    crossScalaVersions := Seq("2.11.11", "2.12.3"),
    resolvers += "dnvriend at bintray" at "http://dl.bintray.com/dnvriend/maven",
    libraryDependencies ++= Seq(
      "com.typesafe.akka"         %% "akka-actor"                 % akkaV,
      "com.typesafe.akka"         %% "akka-testkit"               % akkaV,
      "org.scalatest"             %% "scalatest"                  % "3.0.1"  % "test",
      "org.scalacheck"            %% "scalacheck"                 % "1.13.4" % "test",    // Property-based testing
      "com.github.dnvriend"       %% "akka-persistence-inmemory"  % "2.5.0.0"
    ),
    fork := true,
    SbtScalariform.scalariformSettings,
    ScalariformKeys.preferences := ScalariformKeys.preferences.value
      .setPreference(AlignArguments, true)
      .setPreference(AlignParameters, true)
      .setPreference(AlignSingleLineCaseStatements, true)
      .setPreference(DoubleIndentClassDeclaration, false),
    moduleName := "atomic-store",
    publishMavenStyle := true,
    publishTo := {
      val nexus = "https://oss.sonatype.org/"
      if (isSnapshot.value)
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases"  at nexus + "service/local/staging/deploy/maven2")
    },
    publishArtifact in Test := false,
    pgpPassphrase := Some(scala.util.Properties.envOrElse("GPG_PASS", "gpg-password").toArray)
  )
