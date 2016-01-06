name := """atomic-store"""

version := "0.0.1"

scalaVersion := "2.11.7"

resolvers += "dnvriend at bintray" at "http://dl.bintray.com/dnvriend/maven"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-persistence" % "2.4.1",
  "com.typesafe.akka" %% "akka-persistence-query-experimental" % "2.4.1",
  "com.typesafe.akka" %% "akka-testkit" % "2.4.1",
  "org.scalatest" %% "scalatest" % "2.2.1" % "test",
  "org.scalatestplus" %% "play" % "1.4.0-M3" % "test",
  "com.github.nscala-time" %% "nscala-time" % "2.2.0",
  "org.iq80.leveldb"            % "leveldb"          % "0.7", // For LevelDB journal
  "org.fusesource.leveldbjni"   % "leveldbjni-all"   % "1.8", // For LevelDB journal
  "com.github.dnvriend" %% "akka-persistence-inmemory" % "1.1.5"
)

fork := true