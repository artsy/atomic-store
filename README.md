#Atomic Store

## Meta

* __State:__ development
* __Point People:__ [@acjay](https://github.com/acjay)

Atomic Store is a system for managing persistent streams of atomic events. It is intended for systems in which only one event can be admitted to the canonical stream at a time, contingent upon past events. It exists to maintain the atomicity of handling of incoming events, but outsources the actual validation logic back to the event originator.

## Installation

Atomic Store includes a copy of [Typesafe Activator](http://www.typesafe.com/community/core-tools/activator-and-sbt), which can be used to load the project prompt with `./activator`. You might also choose to install Activator globally on your system.

## Running

At the moment, the only thing you can do is execute the tests, by running `test` from Activator/SBT prompt.

## Integrating into a project

Eventually, this will be published to a public Maven repository for proper versioning, but at the moment, it can be included within another project directly via Github:

```
RootProject(uri("git://github.com/artsy/atomic-store.git"))
```

## Technology

Atomic Store is built using Scala, the Akka framework, and associated libraries. Specifically, here are the core technologies being used, with links to documentation:

- Scala (see [tutorials](http://docs.scala-lang.org/tutorials/))
- [Akka](http://doc.akka.io/docs/akka/snapshot/scala.html)
  - [Actor basics](http://doc.akka.io/docs/akka/snapshot/scala/actors.html)
  - [Persistence](http://doc.akka.io/docs/akka/snapshot/scala/persistence.html)
  