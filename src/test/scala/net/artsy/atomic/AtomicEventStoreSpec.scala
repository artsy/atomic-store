package net.artsy.atomic

import akka.actor._
import akka.testkit._
import org.scalatest._
import scala.concurrent.duration.{FiniteDuration, DurationInt}

/** Simply echos incoming messages */
class EchoActor extends Actor {
  def receive = {
    case msg => sender() ! msg
  }
}

class AtomicEventStoreSpec
  extends ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll with TestKitBase {

  // Akka boilerplate

  implicit lazy val system = ActorSystem()

  override def afterAll = {
    TestKit.shutdownActorSystem(system)
  }

  def probe: TestProbe = TestProbe()

  /**
    * Sends the PoisonPill command to an actor and waits for it to die
    */
  def cleanup(actors: ActorRef*): Unit = {
    val tp = probe
    actors.foreach { (actor: ActorRef) ⇒
      actor ! PoisonPill
      tp watch actor
      tp.expectTerminated(actor)
    }
  }

  // Declare test types for events and validations

  sealed trait TestEvent extends Serializable { def scopeId: String }
  case class TestEvent1(scopeId: String) extends TestEvent
  case class TestEvent2(scopeId: String) extends TestEvent
  implicit val testEventScoped: Scoped[TestEvent] = new Scoped[TestEvent] {
    def scopeIdentifier(domainEvent: TestEvent): String = domainEvent.scopeId
  }

  sealed trait ValidationReason
  case object Timeout extends ValidationReason
  case object ArbitraryRejectionReason extends ValidationReason


  val validationTimeout = 3.second
  object TestEventStore extends AtomicEventStore[TestEvent, ValidationReason](Timeout)
  import TestEventStore._

  // Test helper code

  val defaultTimeout = 3.second

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

  /** Loan fixture for setting up receptionist with dummy logs */
  def withReceptionistAndDummyLogs(testCode: (ActorRef) => Any)  {
    // For test purposes, inject a factory that makes spies instead of logs for
    // the receptionists children
    val dummyLogFactory = (_: String, _: FiniteDuration) => Props(new EchoActor)

    val receptionist = system.actorOf(Props[Receptionist](new Receptionist(dummyLogFactory, validationTimeout)))

    testCode(receptionist)
  }

  /** Loan fixture for setting up a simple log */
  def withLog(testCode: (() => (ActorRef, String)) => Any) {
    val testLogScope = s"test-${UniqueId.next}"

    val logMaker = () => (system.actorOf(EventLog.props(testLogScope, validationTimeout)), testLogScope)

    testCode(logMaker)
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
        expectMsgPF(hint = "empty list of events") { case List() => }
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
        result.storedEventList.lastOption.map(_.data).contains(event) shouldBe true
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
        expectMsgPF(hint = "empty list of events") { case List(Timestamped(eventInStore, _)) if eventInStore == event  => }
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
        result.storedEventList.exists(_.data == event) shouldBe false
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

        val reply1 = expectMsgPF(hint = "ValidationRequest for first event") { case reply: ValidationRequest => reply }
        reply1.prospectiveEvent shouldEqual event1

        log ! reply1.response(didPass = true)
        val result = expectMsgPF(hint = "Result for first event") { case result: Result => result }
        result.prospectiveEvent shouldEqual event1

        val reply2 = expectMsgPF(hint = "ValidationRequest for second event") { case reply: ValidationRequest => reply }
        reply2.prospectiveEvent shouldEqual event2
      }
    }

    "discard an event if it is stopped before validation response is received" in withLog { logMaker =>
      within(defaultTimeout) {
        val (log, scopeId) = logMaker()
        watch(log)

        val event1 = TestEvent1(scopeId)
        val commandMessage1 = StoreIfValid(event1)

        log ! commandMessage1

        val reply1 = expectMsgPF(hint = "ValidationRequest for first event") { case reply: ValidationRequest => reply }
        reply1.prospectiveEvent shouldEqual event1

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
          case storedEvents: List[_] => storedEvents.collect {
            case Timestamped(event, _) => event
          }
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
        result1.storedEventList.map(_.data) shouldEqual List(event1)

        receptionist ! reply2.response(didPass = true)
        val result2 = expectMsgPF(hint = "Result") { case result: Result => result }
        result2.storedEventList.map(_.data) shouldEqual List(event2)

        receptionist ! eventsForScopeQuery(testScope1)
        val queryResult1 = expectMsgPF(hint = "list of one event") {
          case storedEvents: List[_] => storedEvents.collect {
            case Timestamped(event, _) => event
          }
        }
        queryResult1 shouldEqual List(event1)

        receptionist ! eventsForScopeQuery(testScope2)
        val queryResult2 = expectMsgPF(hint = "list of one event") {
          case storedEvents: List[_] => storedEvents.collect {
            case Timestamped(event, _) => event
          }
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
        result1.storedEventList.map(_.data) shouldEqual List(event1)
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
        result1.storedEventList.map(_.data) shouldEqual List(event1)
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
        result1.storedEventList.map(_.data) shouldEqual List(event1)
      }
    }

    "regenerate dead log actor that had two events, with state intact" in {
      within(defaultTimeout) {
        val receptionist = system.actorOf(receptionistProps(validationTimeout))
        val testScope1 = s"test-${UniqueId.next}"

        val event1 = TestEvent1(testScope1)
        receptionist ! StoreIfValid(event1)
        val reply1 = expectMsgPF(hint = "ValidationRequest") { case reply: ValidationRequest => reply }

        // Steal a direct reference to the log so we can manage its lifecycle
        val logRef = lastSender

        receptionist ! reply1.response(didPass = true)
        val result1 = expectMsgPF(hint = "Result") { case result: Result => result }
        result1.storedEventList.map(_.data) shouldEqual List(event1)

        val event2 = TestEvent2(testScope1)
        receptionist ! StoreIfValid(event2)
        val reply2 = expectMsgPF(hint = "ValidationRequest") { case reply: ValidationRequest => reply }

        receptionist ! reply2.response(didPass = true)
        val result2 = expectMsgPF(hint = "Result") { case result: Result => result }
        result2.storedEventList.map(_.data) shouldEqual List(event1, event2)

        receptionist ! eventsForScopeQuery(testScope1)
        val queryResult1 = expectMsgPF(hint = "list of one event") {
          case storedEvents: List[_] => storedEvents.collect {
            case Timestamped(event, _) => event
          }
        }
        queryResult1 shouldEqual List(event1, event2)

        cleanup(logRef)
//        receptionist ! Envelope(testScope1, PoisonPill)
//        expectMsgPF(hint = "Terminated message for the log actor") { case Terminated(`logRef`) => }

        receptionist ! eventsForScopeQuery(testScope1)
        val queryResult2 = expectMsgPF(hint = "list of one event") {
          case storedEvents: List[_] => storedEvents.collect {
            case Timestamped(event, _) => event
          }
        }
        queryResult2 shouldEqual List(event1, event2)
      }
    }
  }
}
