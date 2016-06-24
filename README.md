#Atomic Store

## Meta

* __State:__ development
* __Point People:__ [@acjay](https://github.com/acjay)

**WARNING: This is pre-production software. Use at your own risk. While it will be used in a commercial system soon, it is not currently thoroughly tested, and major features are pending.**

Atomic Store is a system for managing persistent streams of atomic events, with strict consistency. It is intended for systems in which only one event can be admitted to a canonical event log at a time, contingent upon past events. It exists to maintain the atomicity of handling of incoming events, but outsources the actual validation logic back to the event originator. In a sense, the idea here is to do as little as possible to meet this goal, but in a way that is as practical as possible. 

## Philosophy

Atomic Store is built on top of [Akka Persistence](http://doc.akka.io/docs/akka/snapshot/scala/persistence.html), which is designed to natively support highly scalable distributed systems with relaxed consistency. A distributed system can [maximize its scalability](https://www.lightbend.com/blog/microservices-101-exploiting-realitys-constraints-with-technology) by reducing coupling between its components, and synchronization of state changes is one such coupling. The general approach to relaxed consistency is to take compensatory actions to rectify inconsistencies between distributed components, in retrospect. But this is complex, and not desirable in all situations. Atomic Store is designed for situations where strict consistency is more desirable or appropriate than extreme scalability.

## Installation

Atomic Store includes a copy of [Typesafe Activator](http://www.typesafe.com/community/core-tools/activator-and-sbt), which can be used to load the project prompt with `./activator`. You might also choose to install Activator globally on your system.

## Running

At the moment, the only thing you can do is execute the tests, by running `test` from Activator/SBT prompt.

## Integrating into a project

Include the following line in your `build.sbt`:

```
libraryDependencies ++= Seq(
  "net.artsy" %% "atomic-store" % "0.0.4")
```

Then, in your project, you will want to instantiate an atomic store matching your event types:
  
```
object MyEventStore extends AtomicEventStore[MyEventType, MyRejectionReasonType](myTimeoutReason)   
```

In your start up code, you'll start the Receptionist actor, which serves as the store's entry point:

```
case class MyEventStore(
  storeTimeout:     FiniteDuration,
  journalPluginId:  String,
  snapshotPluginId: String
)(
  implicit
  system:  ActorSystem,
  ec:      ExecutionContextExecutor
) {
  import MyEventStore._
  
  val receptionist = system.actorOf(receptionistProps(storeTimeout, journalPluginId, snapshotPluginId))
}
```

At this point, you'll be able to persist events and check the store by sending messages to the receptionist. 

To work within a cluster, it's important that only one instance of each event log be alive within the cluster. This can be accomplished by instantiating the receptionist as a cluster singleton. This might look like:

```
case class MyEventStore(
  storeTimeout:     FiniteDuration,
  journalPluginId:  String,
  snapshotPluginId: String
)(
  implicit
  system:  ActorSystem,
  ec:      ExecutionContextExecutor,
  timeout: Timeout
) {
  import MyEventStore._

  val singletonProps = ClusterSingletonManager.props(
    singletonProps     = receptionistProps(storeTimeout, journalPluginId, snapshotPluginId),
    terminationMessage = PoisonPill,
    settings           = ClusterSingletonManagerSettings(system)
  )
  val manager = system.actorOf(singletonProps, "receptionist")
  
  val path = manager.path.toStringWithoutAddress
  val proxyProps = ClusterSingletonProxy.props(
    singletonManagerPath = path,
    settings             = ClusterSingletonProxySettings(system)
  )
  val receptionist = system.actorOf(proxyProps)
}
```

In a project, it's likely you will want some sort of server-push mechanism to notify clients of new events. Rather than containing this logic. This code can likely be located within the handler of the result.

You will likely also want to use Protobuf and a custom serializer for high-performance serialization of messages to and from Atomic Store. A sample `.proto` file:

```
syntax = "proto2";

package net.artsy.auction.protobuf;

// Lot Event Store messages

message AtomicStoreMessageProto {
    oneof type {
        QueryEventsProto query_event = 1;
        StoreIfValidProto store_if_valid = 2;
        ValidationRequestProto validation_request = 3;
        ValidationResponseProto validation_response = 4;
        ResultProto result = 5;
    }
}

message QueryEventsProto {
    optional string scope_id = 6;
}

message StoreIfValidProto {
    optional bytes event = 7;
}

message ValidationRequestProto {
    optional bytes prospective_event = 8;
    repeated bytes past_events = 9;
}

message ValidationResponseProto {
    optional bool validation_did_pass = 10;
    optional bytes event = 11;
    optional string reason = 12;
    optional MetaProto meta = 13;
}

message ResultProto {
    optional bool was_accepted = 14;
    optional bytes prospective_event = 15;
    repeated bytes stored_event_list = 16;
    optional string reason = 17;
    optional MetaProto meta = 18;
}

message MetaProto {
    // Domain-specific fields
}
```

In your [Akka Serializer](http://doc.akka.io/docs/akka/current/scala/serialization.html) implementation, you'll then want to serialize your events themselves to a byte array, perhaps deferring to a separate serializer.

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

- Testing of complicated random flows of events, validations, and timeouts.
  
## Changelog

*0.0.5*
- Add `meta` field to validation process to allow validation code to pass back arbitrary additional information.
- Bump Akka version to 2.4.7

*0.0.4*
- Remove clustering code (client code may manage Receptionist as a Cluster Singleton if needed)
- Convert nested singleton messages to case classes, for proper deserialization.

*0.0.3*
- Factor out transient state data from what is persisted. It is unlikely to be of any use upon recovery, anyway. *Important:* this _will_ break compatibility with any existing data that's stored.
- Set up Akka Cluster Singleton for EventLog actors 

*0.0.2*
- Remove `Timestamped`. It's not crucial to the logic of this library, so let the client own all of the metadata it wants to associate with its events.
- Allow Akka Persistence plugin to be selected at run-time.
- Upgrade to Scala compiler 2.11.8.

*0.0.1*
- Initial release.
