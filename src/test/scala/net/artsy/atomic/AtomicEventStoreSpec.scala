package net.artsy.atomic

import akka.actor._
import akka.testkit._
import org.scalacheck.{ Arbitrary, Gen }
import org.scalatest._
import org.scalatest.prop.PropertyChecks

import scala.concurrent.duration.{ DurationInt, FiniteDuration }

/** Simply echos incoming messages */
class EchoActor extends Actor {
  def receive = {
    case msg => sender() ! msg
  }
}

// Declare test types for events and validations. These have to be declared
// outside the Spec's scope for serialization purposes.
sealed trait TestEvent extends Serializable {
  def scopeId: String
  def withScopeId(id: String) = this match {
    case TestEvent1(_) => TestEvent1(id)
    case TestEvent2(_) => TestEvent2(id)
    case TestEvent3(_) => TestEvent3(id)
    case TestEvent4(_) => TestEvent4(id)
    case TestEvent5(_) => TestEvent5(id)
  }
}
case class TestEvent1(scopeId: String) extends TestEvent
case class TestEvent2(scopeId: String) extends TestEvent
case class TestEvent3(scopeId: String) extends TestEvent
case class TestEvent4(scopeId: String) extends TestEvent
case class TestEvent5(scopeId: String) extends TestEvent
object TestEvent extends Serializable {
  implicit val testEventScoped: Scoped[TestEvent] = new Scoped[TestEvent] {
    def scopeIdentifier(domainEvent: TestEvent): String = domainEvent.scopeId
  }
}

sealed trait ValidationReason extends Serializable
case object Timeout extends ValidationReason
case object ArbitraryRejectionReason extends ValidationReason

object TestEventStore extends AtomicEventStore[TestEvent, ValidationReason](Timeout)

/**
 * Spec for the AtomicEventStore
 *
 * NOTE: when developing specs, it is important to ensure that each test case
 * uses `expect` to consume all messages sent back to the test actor.
 * Otherwise, messages like replies and timeouts can arrive unexpectedly within
 * other test cases. This could also be fixed by using separate TestProbes for
 * each test case, but for right now, the solution is just to write clean test
 * cases.
 */
class AtomicEventStoreSpec
  extends ImplicitSender
  with WordSpecLike
  with PropertyChecks
  with Matchers
  with BeforeAndAfterAll
  with TestKitBase {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSize = 3, sizeRange = 15, minSuccessful = 20)

  import TestEventStore._

  //
  // Akka boilerplate
  //

  implicit lazy val system = ActorSystem()

  override def afterAll = {
    TestKit.shutdownActorSystem(system)
  }

  //
  // Test helper code
  //

  val validationTimeout = 1.second
  val defaultTimeout = 1.second

  // Used to isolate scopes from one test to another so we don't have to clean
  // up
  object UniqueId {
    private var count = 0
    def next: Int = synchronized {
      val last = count
      count += 1
      last
    }
  }

  /** Kills an actor and waits for it to die */
  def cleanup(actors: ActorRef*): Unit = {
    val tp = TestProbe()
    actors.foreach { (actor: ActorRef) ⇒
      tp.watch(actor)
      actor ! PoisonPill
      tp.expectTerminated(actor)
      tp.unwatch(actor)
    }
  }

  /** Loan fixture for setting up receptionist with dummy logs */
  def withReceptionistAndDummyLogs(testCode: (ActorRef) => Any) = {
    // For test purposes, inject a factory that makes spies instead of logs for
    // the receptionists children
    val dummyLogFactory = (_: String) => Props(new EchoActor)

    val receptionist = system.actorOf(Props[Receptionist](new Receptionist(dummyLogFactory)))

    testCode(receptionist)
  }

  /** Loan fixture for setting up a simple log */
  def withLog(testCode: (() => (ActorRef, String)) => Any) = {
    val testLogScope = s"test-${UniqueId.next}"

    val logMaker = () => (system.actorOf(EventLog.props(testLogScope, validationTimeout, "", ""), testLogScope), testLogScope)

    testCode(logMaker)
  }

  type ScopeId = String

  implicit def arbitraryTestEvent(implicit scopeId: ScopeId): Arbitrary[TestEvent] = Arbitrary {
    for {
      eventTypeIndex <- Gen.chooseNum(1, 5)
    } yield {
      eventTypeIndex match {
        case 1 => TestEvent1(scopeId)
        case 2 => TestEvent2(scopeId)
        case 3 => TestEvent3(scopeId)
        case 4 => TestEvent4(scopeId)
        case 5 => TestEvent5(scopeId)
      }
    }
  }

  "TestEvent events" must {
    "be serializable" in {
      val serialization = akka.serialization.SerializationExtension(system)
      def serialize[A <: AnyRef](obj: A) = {
        val serializer = serialization.findSerializerFor(obj)
        serializer.toBinary(obj)
      }

      val event1 = TestEvent1("blah")
      serialize(event1)
      serialize(TestEventStore.EventLogAvailable)
      serialize(TestEventStore.EventLogBusyValidating)
    }
  }

  "Receptionist" must {
    val scope = "testScope"

    "initially have no active logs" in withReceptionistAndDummyLogs { receptionist =>
      within(defaultTimeout) {
        receptionist ! GetLiveLogScopes
        expectMsgPF() {
          case logScopes: Set[_] => logScopes.isEmpty shouldBe true
        }
      }
    }

    "create an active log and forward an incoming command to it" in withReceptionistAndDummyLogs { receptionist =>
      within(defaultTimeout) {
        val event = TestEvent1(scope)
        val commandMessage = StoreIfValid(event)
        receptionist ! commandMessage
        expectMsg(commandMessage)

        receptionist ! GetLiveLogScopes
        expectMsgPF() {
          case logScopes: Set[_] => logScopes.size shouldEqual 1
        }
      }
    }

    "remove a log from live logs when it is terminated" in withReceptionistAndDummyLogs { receptionist =>
      within(defaultTimeout) {
        receptionist ! GetLiveLogScopes
        val logScopes1 = expectMsgPF(hint = "empty scope set") { case logScopes: Set[_] => logScopes }
        logScopes1.size shouldEqual 0

        val commandMessage = StoreIfValid(TestEvent1(scope))
        receptionist ! commandMessage
        expectMsg(commandMessage)

        // We can take advantage of the leaked actor ref so that we can put a
        // death watch on the actor to synchronize the last part of the test.
        val dummyLogActorRef = lastSender
        watch(dummyLogActorRef)

        receptionist ! GetLiveLogScopes
        val logScopes2 = expectMsgPF(hint = "scope set with one scope") { case logScopes: Set[_] => logScopes }
        logScopes2.size shouldEqual 1

        // Terminate the log
        dummyLogActorRef ! PoisonPill
        expectMsgPF(hint = "Terminated message for the dummy log actor") { case Terminated(`dummyLogActorRef`) => }

        // Not sure it's really sound to assume that the receptionist
        // _definitely_ got the Terminated message yet, but yolo

        receptionist ! GetLiveLogScopes
        val logScopes3 = expectMsgPF(hint = "empty scope set") { case logScopes: Set[_] => logScopes }
        logScopes3.size shouldEqual 0
      }
    }
  }

  "Log" must {
    "initially be empty" in withLog { logMaker =>
      within(defaultTimeout) {
        val (log, _) = logMaker()

        log ! QueryEvents
        expectMsgPF(hint = "empty list of events") { case seq: Seq[_] if seq.isEmpty => }
      }
    }

    "send a positive command result for a command with a positive validation, and store event at end of event list" in withLog { logMaker =>
      within(defaultTimeout) {
        val (log, scopeId) = logMaker()
        val toAccept = true

        val event = TestEvent1(scopeId)
        val commandMessage = StoreIfValid(event)

        log ! commandMessage
        val reply = expectMsgPF(hint = "ValidationRequest") { case reply: ValidationRequest => reply }

        log ! reply.response(toAccept)
        val result = expectMsgPF(hint = "Result") { case result: Result => result }
        result.wasAccepted shouldEqual toAccept
        result.storedEventList.lastOption.contains(event) shouldBe true
      }
    }

    "store an event for command with a positive validation" in withLog { logMaker =>
      within(defaultTimeout) {
        val (log, scopeId) = logMaker()
        val toAccept = true

        val event = TestEvent1(scopeId)
        val commandMessage = StoreIfValid(event)

        log ! commandMessage
        val reply = expectMsgPF(hint = "ValidationRequest") { case reply: ValidationRequest => reply }

        log ! reply.response(toAccept)
        expectMsgPF(hint = "Result") { case result: Result => result }

        log ! QueryEvents
        expectMsgPF(hint = "empty list of events") { case List(`event`) => }
      }
    }

    "send a negative command response for a command with a negative validation, returning the reason and not storing event" in withLog { logMaker =>
      within(defaultTimeout) {
        val (log, scopeId) = logMaker()
        val toAccept = false
        val reason = ArbitraryRejectionReason

        val event = TestEvent1(scopeId)
        val commandMessage = StoreIfValid(event)

        log ! commandMessage
        val reply = expectMsgPF(hint = "ValidationRequest") { case reply: ValidationRequest => reply }

        log ! reply.response(toAccept, Some(reason))
        val result = expectMsgPF(hint = "Result") { case result: Result => result }
        result.wasAccepted shouldEqual toAccept
        result.reason.contains(reason) shouldBe true
        result.storedEventList.contains(event) shouldBe false
      }
    }

    "queue a message received while busy validating, and process it after responding" in withLog { logMaker =>
      within(defaultTimeout) {
        val (log, scopeId) = logMaker()

        val event1 = TestEvent1(scopeId)
        val commandMessage1 = StoreIfValid(event1)
        log ! commandMessage1

        val event2 = TestEvent2(scopeId)
        val commandMessage2 = StoreIfValid(event2)
        log ! commandMessage2

        val reply1 = expectMsgPF(hint = "ValidationRequest for first event") { case reply: ValidationRequest if reply.prospectiveEvent == event1 => reply }
        reply1.prospectiveEvent shouldEqual event1

        log ! reply1.response(didPass = true)
        val result = expectMsgPF(hint = "Result for first event") { case result: Result if result.prospectiveEvent == event1 => result }
        result.prospectiveEvent shouldEqual event1

        val reply2 = expectMsgPF(hint = "ValidationRequest for second event") { case reply: ValidationRequest if reply.prospectiveEvent == event2 => reply }
        reply2.prospectiveEvent shouldEqual event2

        log ! reply2.response(didPass = true)
        expectMsgPF(hint = "Result for second event") { case result: Result if result.prospectiveEvent == event2 => result }
      }
    }

    "discard an event if it is stopped before validation response is received (expect a dead letter in the logs)" in withLog { logMaker =>
      within(defaultTimeout) {
        val (log, scopeId) = logMaker()
        watch(log)

        val event1 = TestEvent1(scopeId)
        val commandMessage1 = StoreIfValid(event1)

        log ! commandMessage1

        val reply1 = expectMsgPF(hint = "ValidationRequest for first event") { case reply: ValidationRequest => reply }
        reply1.prospectiveEvent shouldEqual event1

        // Because we're killing this log actor, it won't receive its own
        // timeout message, resulting in a dead letter.
        log ! PoisonPill
        expectMsgPF(hint = "terminated message") { case Terminated(`log`) => }

        val (rematerializedLog, _) = logMaker()

        rematerializedLog ! QueryEvents
        expectMsgPF(hint = "Empty event list") { case List() => }
      }
    }

    "restore 3 stored events if stopped and restarted" in withLog { logMaker =>
      within(defaultTimeout) {
        val (log, scopeId) = logMaker()

        val event1 = TestEvent1(scopeId)
        val commandMessage1 = StoreIfValid(event1)

        log ! commandMessage1
        val reply = expectMsgPF(hint = "ValidationRequest") { case reply: ValidationRequest => reply }

        log ! reply.response(didPass = true)
        expectMsgPF(hint = "Result") { case result: Result => result }

        val event2 = TestEvent2(scopeId)
        val commandMessage2 = StoreIfValid(event2)

        log ! commandMessage2
        val reply2 = expectMsgPF(hint = "ValidationRequest") { case reply: ValidationRequest => reply }

        log ! reply2.response(didPass = true)
        expectMsgPF(hint = "Result") { case result: Result => result }

        val event3 = TestEvent1(scopeId)
        val commandMessage3 = StoreIfValid(event3)

        log ! commandMessage3
        val reply3 = expectMsgPF(hint = "ValidationRequest") { case reply: ValidationRequest => reply }

        log ! reply3.response(didPass = true)
        expectMsgPF(hint = "Result") { case result: Result => result }

        log ! QueryEvents
        val events = expectMsgPF(hint = "empty list of events") {
          case storedEvents: List[_] => storedEvents
        }
        events should be(List(event1, event2, event3))
      }
    }
  }

  "AtomicEventStore" must {
    "log events with separate scopes separately" in {
      within(defaultTimeout) {
        val receptionist = system.actorOf(receptionistProps(validationTimeout))
        val testScope1 = s"test-${UniqueId.next}"
        val testScope2 = s"test-${UniqueId.next}"

        val event1 = TestEvent1(testScope1)
        receptionist ! StoreIfValid(event1)
        val reply1 = expectMsgPF(hint = "ValidationRequest") { case reply: ValidationRequest => reply }

        val event2 = TestEvent2(testScope2)
        receptionist ! StoreIfValid(event2)
        val reply2 = expectMsgPF(hint = "ValidationRequest") { case reply: ValidationRequest => reply }

        receptionist ! reply1.response(didPass = true)
        val result1 = expectMsgPF(hint = "Result") { case result: Result => result }
        result1.storedEventList shouldEqual List(event1)

        receptionist ! reply2.response(didPass = true)
        val result2 = expectMsgPF(hint = "Result") { case result: Result => result }
        result2.storedEventList shouldEqual List(event2)

        receptionist ! eventsForScopeQuery(testScope1)
        val queryResult1 = expectMsgPF(hint = "list of one event") {
          case storedEvents: List[_] => storedEvents
        }
        queryResult1 shouldEqual List(event1)

        receptionist ! eventsForScopeQuery(testScope2)
        val queryResult2 = expectMsgPF(hint = "list of one event") {
          case storedEvents: List[_] => storedEvents
        }
        queryResult2 shouldEqual List(event2)
      }
    }

    "accept and route unwrapped ValidationResponse to the right actor" in {
      within(defaultTimeout) {
        val receptionist = system.actorOf(receptionistProps(validationTimeout))
        val testScope1 = s"test-${UniqueId.next}"

        val event1 = TestEvent1(testScope1)
        receptionist ! StoreIfValid(event1)
        val reply1 = expectMsgPF(hint = "ValidationRequest") { case reply: ValidationRequest => reply }

        receptionist ! reply1.response(didPass = true)
        val result1 = expectMsgPF(hint = "Result") { case result: Result => result }
        result1.storedEventList shouldEqual List(event1)
      }
    }

    "accept and route ValidationResponse in an incorrect envelope to the right log" in {
      within(defaultTimeout) {
        val receptionist = system.actorOf(receptionistProps(validationTimeout))
        val testScope1 = s"test-${UniqueId.next}"

        val event1 = TestEvent1(testScope1)
        receptionist ! StoreIfValid(event1)
        val reply1 = expectMsgPF(hint = "ValidationRequest") { case reply: ValidationRequest => reply }

        receptionist ! Envelope("other scope", reply1.response(didPass = true))
        val result1 = expectMsgPF(hint = "Result") { case result: Result => result }
        result1.storedEventList shouldEqual List(event1)
      }
    }

    "work correct if ValidationResponse is sent directly back to the log" in {
      within(defaultTimeout) {
        val receptionist = system.actorOf(receptionistProps(validationTimeout))
        val testScope1 = s"test-${UniqueId.next}"

        val event1 = TestEvent1(testScope1)
        receptionist ! StoreIfValid(event1)
        val reply1 = expectMsgPF(hint = "ValidationRequest") { case reply: ValidationRequest => reply }

        lastSender ! reply1.response(didPass = true)
        val result1 = expectMsgPF(hint = "Result") { case result: Result => result }
        result1.storedEventList shouldEqual List(event1)
      }
    }

    "regenerate dead log actor that had two events, with state intact" in {
      within(defaultTimeout) {
        val receptionist = system.actorOf(receptionistProps(validationTimeout), "dead-log-regeneration-receptionist")
        val testScope1 = s"test-${UniqueId.next}"

        val event1 = TestEvent1(testScope1)
        receptionist ! StoreIfValid(event1)
        val reply1 = expectMsgPF(hint = "ValidationRequest") { case reply: ValidationRequest => reply }

        // Steal a direct reference to the log so we can manage its lifecycle
        val logRef = lastSender

        receptionist ! reply1.response(didPass = true)
        val result1 = expectMsgPF(hint = "Result") { case result: Result => result }
        result1.storedEventList shouldEqual List(event1)

        val event2 = TestEvent2(testScope1)
        receptionist ! StoreIfValid(event2)
        val reply2 = expectMsgPF(hint = "ValidationRequest") { case reply: ValidationRequest => reply }

        receptionist ! reply2.response(didPass = true)
        val result2 = expectMsgPF(hint = "Result") { case result: Result => result }
        result2.storedEventList shouldEqual List(event1, event2)

        receptionist ! eventsForScopeQuery(testScope1)
        val queryResult1 = expectMsgPF(hint = "list of one event") {
          case storedEvents: List[_] => storedEvents
        }
        queryResult1 shouldEqual List(event1, event2)

        cleanup(logRef)

        receptionist ! eventsForScopeQuery(testScope1)
        val queryResult2 = expectMsgPF(hint = "list of one event") {
          case storedEvents: List[_] => storedEvents
        }
        queryResult2 shouldEqual List(event1, event2)
      }
    }

    "remember a random set of events upon regeneration" in {
      // This test case persists (or not) a random sequence of events, killing
      // the event log each time, simulating many start up and shut down
      // sequences, with modifications.

      implicit val scopeId: ScopeId = ""

      val receptionist = system.actorOf(receptionistProps(validationTimeout), "random-seq-test-receptionist")

      forAll { (eventsAndDecisionsWrongScope: Seq[(TestEvent, Boolean)]) =>

        val testScope = s"test-${UniqueId.next}"
        val eventsAndDecisions = eventsAndDecisionsWrongScope.map(old => old._1.withScopeId(testScope) -> old._2)

        within(defaultTimeout) {
          for (esAndDs <- eventsAndDecisions.inits.toSeq.reverse.tail) {
            val (event, decision) = esAndDs.last

            receptionist ! StoreIfValid(event)
            val reply = expectMsgPF(hint = "ValidationRequest") { case reply: ValidationRequest if reply.prospectiveEvent == event => reply }

            // Steal a direct reference to the log so we can manage its lifecycle
            val logRef = lastSender

            receptionist ! reply.response(didPass = decision)
            val result = expectMsgPF(hint = "Result") { case result: Result if result.prospectiveEvent == event => result }

            result.wasAccepted shouldEqual decision

            cleanup(logRef)

            receptionist ! eventsForScopeQuery(testScope)
            val queryResult = expectMsgPF(hint = "List of events") {
              case storedEvents: Seq[_] => storedEvents
            }
            queryResult shouldEqual esAndDs.collect { case (e, true) => e }
          }
        }
      }
    }
  }
}
