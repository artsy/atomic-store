Atomic Store
=========

Atomic Store is a system for managing persistent streams of atomic events. It persists events, maintains atomicity of new events, and supports queries of past events. All shared state for the system is encapsulated here.

The system is designed with the goal of cleanly separating the stateful aspects of a system from the business logic. It exists to maintain the atomicity of handling of incoming events, but outsources the actual validation logic back to the event sender.

# Installation

Atomic Store includes a copy of [Typesafe Activator](http://www.typesafe.com/community/core-tools/activator-and-sbt), which can be used to load the project prompt with `./activator`. You might also choose to install Activator globally on your system.

# Running

At the moment, the only thing you can do is execute the tests, by running `test` from Activator/SBT prompt.

# Technology

Atomic Store is built using Scala, the Akka framework, and associated libraries. Specifically, here are the core technologies being used, with links to documentation:

- Scala (see [tutorials](http://docs.scala-lang.org/tutorials/))
- [Akka](http://doc.akka.io/docs/akka/snapshot/scala.html)
  - [Actor basics](http://doc.akka.io/docs/akka/snapshot/scala/actors.html)
  - [Persistence](http://doc.akka.io/docs/akka/snapshot/scala/persistence.html)
  
In the future, we will likely integrate:

- [Akka cluster & remoting](http://doc.akka.io/docs/akka/snapshot/scala.html)
- [Akka persistence query](http://doc.akka.io/docs/akka/snapshot/scala/persistence-query.html)

# TODOs

- ~~Stashing of messages while in the BusyValidating state~~
- ~~Query side of data store~~
- Deployment workflow
- HTTP API for input of events
- Logging
- Push events to outside world
- OSS!