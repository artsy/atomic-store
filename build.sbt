name := """atomic-store"""

version := "0.0.1"

scalaVersion := "2.11.7"

resolvers += "dnvriend at bintray" at "http://dl.bintray.com/dnvriend/maven"

libraryDependencies ++= {
  val akkaVersion = "2.4.2"
  Seq(
    "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence-query-experimental" % akkaVersion,
    "com.github.nscala-time" %% "nscala-time" % "2.2.0",
    "com.github.dnvriend" %% "akka-persistence-inmemory" % "1.2.7",
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
    "org.scalatest" %% "scalatest" % "2.2.1" % Test
  )
}

fork := true