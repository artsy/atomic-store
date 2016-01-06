package net.artsy.atomic

/**
 * Typeclass for objects that provide a string scope identifier that can be
 * used to categorize them. In the context of AtomicEventStore, the scope
 * identifier indicates the atomic log to which the event should be submitted,
 * and atomicity is maintained per scope identifier, meaning that events for
 * differing scopes can be processed in parallel, but within a scope, they are
 * strictly sequential.
 *
 * This is external to the DomainEvent supertype client code uses so that the
 * classes used in the client code need not natively know anything about
 * scopes.
 *
 * @tparam T the underlying type
 */
trait Scoped[T] {
  def scopeIdentifier(domainEvent: T): String
}
