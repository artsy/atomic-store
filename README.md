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
  "net.artsy" %% "atomic-store" % "0.0.3")
```

In a project, it's likely you will want some sort of server-push mechanism to notify clients of new events. Rather than containing this logic. This code can likely be located within the same logic that does the validation.

## Technology

Atomic Store is built using Scala, the Akka framework, and associated libraries. Specifically, here are the core technologies being used, with links to documentation:

- Scala (see [tutorials](http://docs.scala-lang.org/tutorials/))
- [Akka](http://doc.akka.io/docs/akka/snapshot/scala.html)
  - [Actor basics](http://doc.akka.io/docs/akka/snapshot/scala/actors.html)
  - [Persistence](http://doc.akka.io/docs/akka/snapshot/scala/persistence.html)
  
## Releasing new versions
   
For testing changes:

1. Bump the version in `build.sbt` as appropriate, and add `-SNAPSHOT` to the end of the version number.
2. Update the `libraryDependencies` line above in anticipation of the next version.
3. Use the `sbt publish-signed` task to push snapshots to Maven Central.
4. Update the *Changelog* as noteworthy changes are made.  
5. During the testing period, merge new changes into the `development` branch, so that the `master` branch on Github always reflects the latest version on Maven Central. 

For releasing new versions:
 
1. Remove the `-SNAPSHOT` suffix in `build.sbt`.
2. Publish to Maven Central staging using `sbt publish-signed`.
3. Follow [the Maven Central workflow](http://central.sonatype.org/pages/releasing-the-deployment.html) for releasing the next version, logging in to Maven Central Nexus with an account set up with the privilege to publish to [the Open Source Project Repository Atomic Store entry](https://issues.sonatype.org/browse/OSSRH-20964). 
4. Merge `development` into `master` to update the canonical version on Github.
  
For reference on this process, you may want to see the following links:
 
- [SBT: Deploying to Sonatype](http://www.scala-sbt.org/0.13/docs/Using-Sonatype.html)
- [SBT-PGP Usage docs](http://www.scala-sbt.org/sbt-pgp/usage.html)
- [The Central Repository: Releasing The Deployment](http://central.sonatype.org/pages/releasing-the-deployment.html)
  
## Todos

- Akka Clustering support for failover.
- Document support for configurable serialization.
- Testing of complicated random flows of events, validations, and timeouts.
  
## Changelog

*0.0.4*
- Remove clustering code (client code may manage Receptionist as a Cluster Singleton if needed)

*0.0.3*
- Factor out transient state data from what is persisted. It is unlikely to be of any use upon recovery, anyway. *Important:* this _will_ break compatibility with any existing data that's stored.
- Set up Akka Cluster Singleton for EventLog actors 

*0.0.2*
- Remove `Timestamped`. It's not crucial to the logic of this library, so let the client own all of the metadata it wants to associate with its events.
- Allow Akka Persistence plugin to be selected at run-time.
- Upgrade to Scala compiler 2.11.8.

*0.0.1*
- Initial release.
