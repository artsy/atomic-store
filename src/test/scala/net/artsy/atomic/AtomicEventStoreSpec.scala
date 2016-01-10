package net.artsy.atomic

import akka.actor._
import akka.testkit._
import org.scalatest._
import scala.concurrent.duration.DurationInt

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

  // Declare test types for events and validations

  sealed trait TestEvent { def scopeId: String }
  case class TestEvent1(scopeId: String) extends TestEvent
  case class TestEvent2(scopeId: String) extends TestEvent
  implicit val testEventScoped: Scoped[TestEvent] = new Scoped[TestEvent] {
    def scopeIdentifier(domainEvent: TestEvent): String = domainEvent.scopeId
  }

  sealed trait ValidationReason
  case object Timeout extends ValidationReason
  case object ArbitraryRejectionReason extends ValidationReason

  type TestEventStore = AtomicEventStore[TestEvent, ValidationReason]

  val eventStore = new AtomicEventStore[TestEvent, ValidationReason](100.milliseconds, Timeout)

  // Test helper code

  val defaultTimeout = 1.second

  // Used to isolate scopes from one test to another so we don't have to clean
  // up
  object UniqueId {
    private var count = 0
    def next: Int = {
      val last = count
      count += 1
      last
    }
  }

  /** Loan fixture for setting up receptionist with dummy logs */
  def withReceptionistAndDummyLogs(testCode: (TestEventStore, ActorRef) => Any)  {
    // For test purposes, inject a factory that makes spys instead of logs for
    // the receptionists children
    val dummyLogFactory = (_: String) => Props(new EchoActor)

    val receptionist = system.actorOf(Props[eventStore.Receptionist](new eventStore.Receptionist(dummyLogFactory)))

    testCode(eventStore, receptionist)
  }

  /** Loan fixture for setting up a simple log */
  def withLog(testCode: (TestEventStore, () => (ActorRef, String)) => Any) {
    val testLogScope = s"test-${UniqueId.next}"

    val logMaker = () => (system.actorOf(eventStore.EventLog.props(testLogScope)), testLogScope)

    testCode(eventStore, logMaker)
  }

  "Receptionist" must {
    val scope = "testScope"

    "initially have no active logs" in withReceptionistAndDummyLogs { (eventStore, receptionist) =>
      within(defaultTimeout) {
        receptionist ! eventStore.GetLiveLogScopes
        expectMsgPF() {
          case logScopes: Set[_] => assert(logScopes.isEmpty)
        }
      }
    }

    "create an active log and forward an incoming command to it" in withReceptionistAndDummyLogs { (eventStore, receptionist) =>
      within(defaultTimeout) {
        val event = TestEvent1(scope)
        val commandMessage = eventStore.StoreIfValid(event)
        receptionist ! commandMessage
        expectMsg(commandMessage)

        receptionist ! eventStore.GetLiveLogScopes
        expectMsgPF() {
          case logScopes: Set[_] => assert(logScopes.size === 1)
        }
      }
    }

    "remove a log from live logs when it is terminated" in withReceptionistAndDummyLogs { (eventStore, receptionist) =>
      within(defaultTimeout) {
        receptionist ! eventStore.GetLiveLogScopes
        val logScopes1 = expectMsgPF(hint = "empty scope set") { case logScopes: Set[_] => logScopes }
        assert(logScopes1.size === 0)

        val commandMessage = eventStore.StoreIfValid(TestEvent1(scope))
        receptionist ! commandMessage
        expectMsg(commandMessage)

        // We can take advantage of the leaked actor ref so that we can put a
        // death watch on the actor to synchronize the last part of the test.
        val dummyLogActorRef = lastSender
        watch(dummyLogActorRef)

        receptionist ! eventStore.GetLiveLogScopes
        val logScopes2 = expectMsgPF(hint = "scope set with one scope") { case logScopes: Set[_] => logScopes }
        assert(logScopes2.size === 1)

        // Terminate the log
        dummyLogActorRef ! PoisonPill
        expectMsgPF(hint = "Terminated message for the dummy log actor") { case Terminated(`dummyLogActorRef`) => }

        // Not sure it's really sound to assume that the receptionist
        // _definitely_ got the Terminated message yet, but yolo

        receptionist ! eventStore.GetLiveLogScopes
        val logScopes3 = expectMsgPF(hint = "empty scope set") { case logScopes: Set[_] => logScopes }
        assert(logScopes3.size === 0)
      }
    }
  }

  "Log" must {
    "initially be empty" in withLog { (eventStore, logMaker) =>
      within(defaultTimeout) {
        val (log, _) = logMaker()

        log ! eventStore.QueryEvents
        expectMsgPF(hint = "empty list of events") { case List() => }
      }
    }

    "send a positive command result for a command with a positive validation, and store event at end of event list" in withLog { (eventStore, logMaker) =>
      within(defaultTimeout) {
        val (log, scopeId) = logMaker()
        val toAccept = true

        val event = TestEvent1(scopeId)
        val commandMessage = eventStore.StoreIfValid(event)

        log ! commandMessage
        val reply = expectMsgPF(hint = "ValidationRequest") { case reply: eventStore.ValidationRequest => reply }

        log ! reply.response(toAccept)
        val result = expectMsgPF(hint = "Result") { case result: eventStore.Result => result }
        assert(result.wasAccepted == toAccept)
        assert(result.storedEventList.lastOption.map(_.data).contains(event))
      }
    }

    "store an event for command with a positive validation" in withLog { (eventStore, logMaker) =>
      within(defaultTimeout) {
        val (log, scopeId) = logMaker()
        val toAccept = true

        val event = TestEvent1(scopeId)
        val commandMessage = eventStore.StoreIfValid(event)

        log ! commandMessage
        val reply = expectMsgPF(hint = "ValidationRequest") { case reply: eventStore.ValidationRequest => reply }

        log ! reply.response(toAccept)
        expectMsgPF(hint = "Result") { case result: eventStore.Result => result }

        log ! eventStore.QueryEvents
        expectMsgPF(hint = "empty list of events") { case List(Timestamped(eventInStore, _)) if eventInStore == event  => }
      }
    }

    "send a negative command response for a command with a negative validation, returning the reason and not storing event" in withLog { (eventStore, logMaker) =>
      within(defaultTimeout) {
        val (log, scopeId) = logMaker()
        val toAccept = false
        val reason = ArbitraryRejectionReason

        val event = TestEvent1(scopeId)
        val commandMessage = eventStore.StoreIfValid(event)

        log ! commandMessage
        val reply = expectMsgPF(hint = "ValidationRequest") { case reply: eventStore.ValidationRequest => reply }

        log ! reply.response(toAccept, Some(reason))
        val result = expectMsgPF(hint = "Result") { case result: eventStore.Result => result }
        assert(result.wasAccepted == toAccept)
        assert(result.reason.contains(reason))
        assert(!result.storedEventList.exists(_.data == event))
      }
    }

    "queue a message received while busy validating, and process it after responding" in withLog { (eventStore, logMaker) =>
      within(defaultTimeout) {
        val (log, scopeId) = logMaker()

        val event1 = TestEvent1(scopeId)
        val commandMessage1 = eventStore.StoreIfValid(event1)
        log ! commandMessage1

        val event2 = TestEvent2(scopeId)
        val commandMessage2 = eventStore.StoreIfValid(event2)
        log ! commandMessage2

        val reply1 = expectMsgPF(hint = "ValidationRequest for first event") { case reply: eventStore.ValidationRequest => reply }
        assert(reply1.prospectiveEvent == event1)

        log ! reply1.response(didPass = true)
        val result = expectMsgPF(hint = "Result for first event") { case result: eventStore.Result => result }
        assert(result.prospectiveEvent == event1)

        val reply2 = expectMsgPF(hint = "ValidationRequest for second event") { case reply: eventStore.ValidationRequest => reply }
        assert(reply2.prospectiveEvent == event2)
      }
    }

    "discard an event if it is stopped before validation response is received" in withLog { (eventStore, logMaker) =>
      within(defaultTimeout) {
        val (log, scopeId) = logMaker()
        watch(log)

        val event1 = TestEvent1(scopeId)
        val commandMessage1 = eventStore.StoreIfValid(event1)

        log ! commandMessage1

        val reply1 = expectMsgPF(hint = "ValidationRequest for first event") { case reply: eventStore.ValidationRequest => reply }
        assert(reply1.prospectiveEvent == event1)

        log ! PoisonPill
        expectMsgPF(hint = "terminated message") { case Terminated(`log`) => }

        val (rematerializedLog, _) = logMaker()

        rematerializedLog ! eventStore.QueryEvents
        expectMsgPF(hint = "Empty event list") { case List() => }
      }
    }

    "restore 3 stored events if stopped and restarted" in withLog { (eventStore, logMaker) =>
      within(defaultTimeout) {
        val (log, scopeId) = logMaker()

        val event1 = TestEvent1(scopeId)
        val commandMessage1 = eventStore.StoreIfValid(event1)

        log ! commandMessage1
        val reply = expectMsgPF(hint = "ValidationRequest") { case reply: eventStore.ValidationRequest => reply }

        log ! reply.response(didPass = true)
        expectMsgPF(hint = "Result") { case result: eventStore.Result => result }

        val event2 = TestEvent2(scopeId)
        val commandMessage2 = eventStore.StoreIfValid(event2)

        log ! commandMessage2
        val reply2 = expectMsgPF(hint = "ValidationRequest") { case reply: eventStore.ValidationRequest => reply }

        log ! reply2.response(didPass = true)
        expectMsgPF(hint = "Result") { case result: eventStore.Result => result }

        val event3 = TestEvent1(scopeId)
        val commandMessage3 = eventStore.StoreIfValid(event3)

        log ! commandMessage3
        val reply3 = expectMsgPF(hint = "ValidationRequest") { case reply: eventStore.ValidationRequest => reply }

        log ! reply3.response(didPass = true)
        expectMsgPF(hint = "Result") { case result: eventStore.Result => result }

        log ! eventStore.QueryEvents
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
        val receptionist = system.actorOf(eventStore.receptionistProps)
        val testScope1 = s"test-${UniqueId.next}"
        val testScope2 = s"test-${UniqueId.next}"

        val event1 = TestEvent1(testScope1)
        receptionist ! eventStore.StoreIfValid(event1)
        val reply1 = expectMsgPF(hint = "ValidationRequest") { case reply: eventStore.ValidationRequest => reply }

        val event2 = TestEvent2(testScope2)
        receptionist ! eventStore.StoreIfValid(event2)
        val reply2 = expectMsgPF(hint = "ValidationRequest") { case reply: eventStore.ValidationRequest => reply }

        receptionist ! reply1.response(didPass = true)
        val result1 = expectMsgPF(hint = "Result") { case result: eventStore.Result => result }
        assert(result1.storedEventList.map(_.data) === List(event1))

        receptionist ! reply2.response(didPass = true)
        val result2 = expectMsgPF(hint = "Result") { case result: eventStore.Result => result }
        assert(result2.storedEventList.map(_.data) === List(event2))

        receptionist ! eventStore.eventsForScopeQuery(testScope1)
        val queryResult1 = expectMsgPF(hint = "list of one event") {
          case storedEvents: List[_] => storedEvents.collect {
            case Timestamped(event, _) => event
          }
        }
        assert(queryResult1 === List(event1))

        receptionist ! eventStore.eventsForScopeQuery(testScope2)
        val queryResult2 = expectMsgPF(hint = "list of one event") {
          case storedEvents: List[_] => storedEvents.collect {
            case Timestamped(event, _) => event
          }
        }
        assert(queryResult2 === List(event2))
      }
    }

    "accept and route unwrapped ValidationResponse to the right actor" in {
      within(defaultTimeout) {
        val receptionist = system.actorOf(eventStore.receptionistProps)
        val testScope1 = s"test-${UniqueId.next}"

        val event1 = TestEvent1(testScope1)
        receptionist ! eventStore.StoreIfValid(event1)
        val reply1 = expectMsgPF(hint = "ValidationRequest") { case reply: eventStore.ValidationRequest => reply }

        receptionist ! reply1.response(didPass = true)
        val result1 = expectMsgPF(hint = "Result") { case result: eventStore.Result => result }
        assert(result1.storedEventList.map(_.data) === List(event1))
      }
    }

    "accept and route ValidationResponse in an incorrect envelope to the right log" in {
      within(defaultTimeout) {
        val receptionist = system.actorOf(eventStore.receptionistProps)
        val testScope1 = s"test-${UniqueId.next}"

        val event1 = TestEvent1(testScope1)
        receptionist ! eventStore.StoreIfValid(event1)
        val reply1 = expectMsgPF(hint = "ValidationRequest") { case reply: eventStore.ValidationRequest => reply }

        receptionist ! eventStore.Envelope("other scope", reply1.response(didPass = true))
        val result1 = expectMsgPF(hint = "Result") { case result: eventStore.Result => result }
        assert(result1.storedEventList.map(_.data) === List(event1))
      }
    }

    "work correct if ValidtionResponse is sent directly back to the log" in {
      within(defaultTimeout) {
        val receptionist = system.actorOf(eventStore.receptionistProps)
        val testScope1 = s"test-${UniqueId.next}"

        val event1 = TestEvent1(testScope1)
        receptionist ! eventStore.StoreIfValid(event1)
        val reply1 = expectMsgPF(hint = "ValidationRequest") { case reply: eventStore.ValidationRequest => reply }

        lastSender ! reply1.response(didPass = true)
        val result1 = expectMsgPF(hint = "Result") { case result: eventStore.Result => result }
        assert(result1.storedEventList.map(_.data) === List(event1))
      }
    }

    "regenerate dead log actor that had two events, with state intact" in {
      within(defaultTimeout) {
        val receptionist = system.actorOf(eventStore.receptionistProps)
        val testScope1 = s"test-${UniqueId.next}"

        val event1 = TestEvent1(testScope1)
        receptionist ! eventStore.StoreIfValid(event1)
        val reply1 = expectMsgPF(hint = "ValidationRequest") { case reply: eventStore.ValidationRequest => reply }

        // Steal a direct reference to the log so we can manage its lifecycle
        val logRef = lastSender
        watch(logRef)

        receptionist ! reply1.response(didPass = true)
        val result1 = expectMsgPF(hint = "Result") { case result: eventStore.Result => result }
        assert(result1.storedEventList.map(_.data) === List(event1))

        val event2 = TestEvent2(testScope1)
        receptionist ! eventStore.StoreIfValid(event2)
        val reply2 = expectMsgPF(hint = "ValidationRequest") { case reply: eventStore.ValidationRequest => reply }

        receptionist ! reply2.response(didPass = true)
        val result2 = expectMsgPF(hint = "Result") { case result: eventStore.Result => result }
        assert(result2.storedEventList.map(_.data) === List(event1, event2))

        receptionist ! eventStore.eventsForScopeQuery(testScope1)
        val queryResult1 = expectMsgPF(hint = "list of one event") {
          case storedEvents: List[_] => storedEvents.collect {
            case Timestamped(event, _) => event
          }
        }
        assert(queryResult1 === List(event1, event2))

        receptionist ! eventStore.Envelope(testScope1, PoisonPill)
        expectMsgPF(hint = "Terminated message for the log actor") { case Terminated(`logRef`) => }

        receptionist ! eventStore.eventsForScopeQuery(testScope1)
        val queryResult2 = expectMsgPF(hint = "list of one event") {
          case storedEvents: List[_] => storedEvents.collect {
            case Timestamped(event, _) => event
          }
        }
        assert(queryResult2 === List(event1, event2))
      }
    }
  }
}
