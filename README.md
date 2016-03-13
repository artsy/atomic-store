#Atomic Store

## Meta

* __State:__ development
* __Point People:__ [@acjay](https://github.com/acjay)

**WARNING: This is pre-production software. Use at your own risk. While it will be used in a commercial system soon, it is not currently thoroughly tested, and major features are pending.**

Atomic Store is a system for managing persistent streams of atomic events. It is intended for systems in which only one event can be admitted to the canonical stream at a time, contingent upon past events. It exists to maintain the atomicity of handling of incoming events, but outsources the actual validation logic back to the event originator. In a sense, the idea here is to do as little as possible to meet this goal, but in a way that is as practical as possible. 

## Installation

Atomic Store includes a copy of [Typesafe Activator](http://www.typesafe.com/community/core-tools/activator-and-sbt), which can be used to load the project prompt with `./activator`. You might also choose to install Activator globally on your system.

## Running

At the moment, the only thing you can do is execute the tests, by running `test` from Activator/SBT prompt.

## Integrating into a project

Include the following line in your `build.sbt`:

```
libraryDependencies ++= Seq(
  "net.artsy" %% "atomic-store" % "0.0.2")
```

In a project, it's likely you will want some sort of server-push mechanism to notify clients of new events. Rather than containing this logic. This code can likely be located within the same logic that does the validation.

## Technology

Atomic Store is built using Scala, the Akka framework, and associated libraries. Specifically, here are the core technologies being used, with links to documentation:

- Scala (see [tutorials](http://docs.scala-lang.org/tutorials/))
- [Akka](http://doc.akka.io/docs/akka/snapshot/scala.html)
  - [Actor basics](http://doc.akka.io/docs/akka/snapshot/scala/actors.html)
  - [Persistence](http://doc.akka.io/docs/akka/snapshot/scala/persistence.html)
  
## Todos ##

- Akka Clustering support for failover.
- Support for configurable serialization.
  
## Changelog

*0.0.2*
- Remove `Timestamped`. It's not crucial to the logic of this library, so let the client own all of the metadata it wants to associate with its events.

*0.0.1*
- Initial release