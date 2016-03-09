package net.artsy.atomic

import org.joda.time.DateTime

/**
 * Simple wrapper for timestamped domain data
 * @param data the domain data
 * @param createdAt timestamp for creation time
 * @tparam A domain data's type
 */
case class Timestamped[A <: Serializable](data: A, createdAt: DateTime) extends Serializable {
  def map[B <: Serializable](f: A => B): Timestamped[B] = Timestamped(f(data), createdAt)
}
