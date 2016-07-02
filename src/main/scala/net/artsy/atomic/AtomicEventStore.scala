package net.artsy.atomic

import akka.actor._
import akka.event.LoggingReceive
import akka.persistence.fsm.PersistentFSM
import akka.persistence.fsm.PersistentFSM.FSMState
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

/** Supertype of messages, for serialization purposes */
trait Message

trait ScopedMessage extends Serializable with Message {
  def scopeId: String
}

/**
 * Implements a data-agnostic persistent event store, in the Event Sourcing
 * methodology. The store is divided into scopes, and within each scope events
 * can be admitted to a log, one at a time. Clients request events to be
 * admitted to the log by sending [[StoreIfValid]] messages containing the
 * prospective EventType.
 *
 * The sender of the event will receive a [[ValidationRequest]] message, and
 * is then responsible for validating the event, or delegating that
 * responsibility. The validator determines whether a command should be
 * accepted as an event, considering all of the previously accepted events as
 * context for that decision, and replies to the log with a
 * [[ValidationResponse]] message. The log will then persist the event to
 * storage, if it was accepted, and then reply to the client with a [[Result]]
 * message. The client is then responsible for carrying out the consequences
 * of the event being accepted or rejected, e.g. updating state and notifying
 * its downstream clients. During validation, the AtomicEventStore postpones
 * consideration of any other events, maintaining atomicity.
 *
 * It's important for the client to *not* attempt to perform any
 * "prevalidation" on an event in the context of other events, before sending
 * the event to the AtomicEventStore. This would break atomicity. Always do
 * all validation only in response to the [[ValidationRequest]] message.
 *
 * Subclass this abstract class, and then use
 * [[AtomicEventStore#receptionistProps]] as the entry point for instantiating
 * the system. The receptionist manages the lifecycle of the logs, and the
 * logs manage their own persistence.
 *
 * @tparam EventType the supertype of domain events used by this store
 * @tparam ValidationReason supertype for descriptor objects that indicate why
 *                          an event validation failed (or succeeded).
 * @param timeoutReason reason object for validations that timeout.
 */

abstract class AtomicEventStore[EventType <: Serializable: Scoped: ClassTag, ValidationReason](
  timeoutReason: ValidationReason
) extends Serializable {
  /**
   * The props for instantiating the AtomicEventStore network
   *
   * @param validationTimeout the duration to wait before validation fails, if
   *                          no response is yet received.
   * @param journalPluginId ID of the plugin to use for the Akka Persistence
   *                      journal back-end for logs.
   * @param snapshotPluginId ID of the plugin to use for the Akka Persistence
   *                       snapshot back-end for logs.
   */
  def receptionistProps(
    validationTimeout: FiniteDuration,
    journalPluginId:   String         = "",
    snapshotPluginId:  String         = ""
  ) = Props(new Receptionist(EventLog.props(_, validationTimeout, journalPluginId, snapshotPluginId)))

  /**
   * Trait for messages that should be routed to a specific log. Using the
   * [[Scoped]] type class for messages that carry events allow those to
   * either be sent to the receptionist or replied to the log directly.
   */
  object ScopedMessage {
    // This bit of magic lets us pull the scope ID off of messages that are
    // inherently scoped, like events, while allowing unscoped messages to be
    // routed using [[Envelope]]. If an Envelope wraps an inherently scoped
    // message, the scope of the envelope is simply discarded.
    def unapply(message: Any): Option[(String, Any)] = {
      message match {
        // warning generated about "outer reference in this type test cannot be checked at run time"
        // because ScopedMessage is an inner class
        case msg: ScopedMessage =>
          msg match {
            case Envelope(_, ScopedMessage(scopeId, m)) => Some((scopeId, m))
            case Envelope(scopeId, m)                   => Some((scopeId, m))
            case _                                      => Some((msg.scopeId, msg))
          }
        case _ => None
      }
    }
  }

  /**
   * Envelope for messages sent to the Receptionist that should be forwarded
   * on to the appropriate log, particularly when the message doesn't contain
   * the log's scope in any inherent way.
   *
   * @param scopeId scope of the log to forward to
   * @param message message to forward
   * @tparam MessageType contained message type
   */
  case class Envelope[MessageType](scopeId: String, message: MessageType) extends ScopedMessage

  /**
   * Asks a log for its list of events
   *
   * NOTE: This is intentionally a class, so that deserialization works
   * correctly. Having it as an inner case object was causing remote Atomic
   * Store instances not to recognize the message as their own singleton.
   */
  case class QueryEvents() extends Message

  /**
   * Ask for the events from a specific log
   *
   * @param scopeId log scope
   * @return a message the receptionist can forward to the log
   */
  def eventsForScopeQuery(scopeId: String) = Envelope(scopeId, QueryEvents())

  /**
   * A command to consider an incoming event. A [[ValidationRequest]] will be
   * replied to the sender, so it can validate the event in the context of the
   * all the previously accepted events, while the event store queues any other
   * incoming events.
   *
   * @param event event to consider
   */
  case class StoreIfValid(event: EventType) extends ScopedMessage {
    val scopeId = implicitly[Scoped[EventType]].scopeIdentifier(event)
  }

  /**
   * Sent to the original requester for to request validation. The atomicity of
   * the event log holds off any other prospective events while the validation
   * decision is being made.
   *
   * @param prospectiveEvent the event to consider
   * @param pastEvents all prior events
   */
  case class ValidationRequest(prospectiveEvent: EventType, pastEvents: Seq[EventType]) extends Message {
    def response(didPass: Boolean, reason: Option[ValidationReason] = None) =
      ValidationResponse[Nothing](didPass, prospectiveEvent, reason)
  }

  /**
   * The response to send back to determine whether to accept the event or not.
   *
   * @param validationDidPass true, iff the event should be accepted
   * @param event the event considered
   * @param reason the reason for the decision. Usually included iff the event
   *               was rejected.
   * @param meta additional data the validation
   */
  case class ValidationResponse[MetaType](
    validationDidPass: Boolean,
    event:             EventType,
    reason:            Option[ValidationReason],
    meta:              Option[MetaType]         = None
  ) extends ScopedMessage {
    val scopeId = implicitly[Scoped[EventType]].scopeIdentifier(event)

    def withMeta[NewMetaType](newMeta: NewMetaType): ValidationResponse[NewMetaType] = copy(meta = Some(newMeta))

    def toResult(events: Seq[EventType]): Result[MetaType] = Result(validationDidPass, event, events, reason, meta)
  }

  /**
   * Response that indicates whether the incoming event was accepted.
   *
   * @param wasAccepted true iff validation succeeded before the timeout
   * @param prospectiveEvent the event sent in the original request
   * @param storedEventList all accepted events at the time of response (including
   *                  the prospective one, in the final position, iff accepted)
   * @param reason the validation reason
   */
  case class Result[MetaType](
    wasAccepted:      Boolean,
    prospectiveEvent: EventType,
    storedEventList:  Seq[EventType],
    reason:           Option[ValidationReason],
    meta:             Option[MetaType]
  ) extends Message

  /** Diagnostic query to inspect live log actors */
  case class GetLiveLogScopes() extends Message

  ///////////
  // Actors
  //

  /**
   * Supervises the [[EventLog]] actors and routes incoming requests to them
   *
   * @param logProps factory for making EventLog props for given scope IDs.
   *                 This is mostly for testing, and is wired up automatically
   *                 when using [[receptionistProps]]
   */
  class Receptionist(
    logProps: String => Props
  ) extends Actor {
    val logs = scala.collection.mutable.Map.empty[String, ActorRef]

    def liveLogForScope(scope: String): ActorRef = logs.getOrElseUpdate(scope, createLog(scope))

    // [Re]create the log, which will recall all preexisting events
    def createLog(scope: String): ActorRef = {
      val materializedLog = context.actorOf(logProps(scope), scope)
      context.watch(materializedLog)
      materializedLog
    }

    def receive = LoggingReceive {
      case ScopedMessage(scope, message) =>
        liveLogForScope(scope).forward(message)

      // watch for terminated log, and remove it from the logs map
      case Terminated(deadActorRef) => {
        logs.collectFirst { case (key, `deadActorRef`) => key }.foreach(scope => logs -= scope)
      }

      case GetLiveLogScopes() =>
        sender() ! logs.keys.toSet
    }
  }

  /**
   * Actor responsible for processing and persisting events within a scope.
   *
   * When reading the code below, it's important to note that there are 3
   * distinct senses of the term event:
   *
   * - External events -- the permanent events clients care about
   * - Internal events -- represent all changes to the stored data, whether
   *   for internal use (transient) or external visibility (permanent)
   * - Persistent FSM Events -- wrappers for internal events that bundle the
   *   stored data as well
   *
   * In addition, there are 2 distinct senses of the idea of state:
   *
   * - Persistent FSM state -- the two modes the actor can be in
   * - Actor state -- the locally managed data
   *
   * @param scopeId separates this log from others, both by storage and
   *                provided atomicity
   * @param journalPluginId ID of the plugin to use for the Akka Persistence
   *                      journal back-end for logs.
   * @param snapshotPluginId ID of the plugin to use for the Akka Persistence
   *                       snapshot back-end for logs.
   */
  class EventLog(
    scopeId:                       String,
    validationTimeout:             FiniteDuration,
    override val journalPluginId:  String,
    override val snapshotPluginId: String
  ) extends PersistentFSM[EventLogState, Seq[EventType], EventType] with Stash {

    // Separate the logs in the database by scopes
    def persistenceId: String = s"domainEvents:$scopeId"

    var transientState: Option[TransientState] = None

    /**
     * StateFunction for managing queries. This is factored out so that it can
     * be composed with the state-specific StateFunctions below, so that it is
     * always available.
     */
    val handleQuery: StateFunction = {
      case Event(QueryEvents(), storedEvents) =>
        stay().replying(storedEvents)
    }

    // Events are persisted only when applied by the FSM below.
    def applyEvent(domainEvent: EventType, currentData: Seq[EventType]): Seq[EventType] =
      currentData :+ domainEvent

    startWith(EventLogAvailable, Seq.empty)

    // Open for processing
    when(EventLogAvailable)(handleQuery orElse {
      case Event(StoreIfValid(event), eventList) =>
        goto(EventLogBusyValidating).replying(ValidationRequest(event, eventList)).andThen { _ =>
          transientState = Some(TransientState(event, sender()))
        }
    })

    // While waiting for the sender to validate the event, hold off any others.
    when(EventLogBusyValidating, stateTimeout = validationTimeout)(handleQuery orElse {
      // Validation succeeded
      case Event(v @ ValidationResponse(wasAccepted, event, _, _), _) if transientState.exists(_.eventUnderConsideration == event) =>
        // We're going to reply to `sender` instead of the stored `replyTo`.
        // This is to enable the client to operate using the `ask` pattern,
        // which uses a fresh actorRef for each round.
        val replyTo = sender()

        val nextState = goto(EventLogAvailable)
        val nextStateWithApply =
          if (wasAccepted) {
            nextState.applying(event)
          } else {
            nextState
          }
        nextStateWithApply.andThen { eventList =>
          // We have to wait until after the events have persisted to send our
          // reply, instead of using `.replying` to send it before the
          // transition, just to make sure.
          replyTo ! v.toResult(eventList)
        }

      // If we timeout, let the sender know. There should be data stored about
      // the event-under-consideration and its sender, so reply back with a
      // Result, if we can.
      case Event(StateTimeout, _) =>
        val nextState = goto(EventLogAvailable)

        // Reply, if we can.
        transientState match {
          case Some(TransientState(event, replyTo)) =>
            nextState.andThen { eventList =>
              replyTo ! Result(wasAccepted = false, event, eventList, Some(timeoutReason), None)
            }
          case _ => nextState
        }

      // This assumes any other message is potentially valid, and defers to the
      // LogAvailable handler to discriminate.
      case msg: Any =>
        stash()
        stay()
    })

    // Start accepting messages when we go available again
    onTransition {
      case EventLogBusyValidating -> EventLogAvailable =>
        transientState = None
        unstashAll()
    }

    // Don't really understand why this is needed to fulfill the trait, but whatevs
    def domainEventClassTag: ClassTag[EventType] = implicitly[ClassTag[EventType]]
  }

  object EventLog extends Serializable {
    /** Convenience method for the props to instantiate the EventLog actor */
    def props(
      scope:             String,
      validationTimeout: FiniteDuration,
      journalPluginId:   String,
      snapshotPluginId:  String
    ) = Props(new EventLog(scope, validationTimeout, journalPluginId, snapshotPluginId))
  }

  // Data types for EventLog

  // States
  sealed trait EventLogState extends FSMState
  case object EventLogAvailable extends EventLogState {
    override def identifier: String = "available"
  }
  case object EventLogBusyValidating extends EventLogState {
    override def identifier: String = "busyValidating"
  }

  case class TransientState(eventUnderConsideration: EventType, replyTo: ActorRef)
}
