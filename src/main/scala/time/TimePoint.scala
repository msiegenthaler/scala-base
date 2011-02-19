package ch.inventsoft.scalabase
package time

import javax.time._
import calendar._


/**
 * A specific, absolute point in time (i.e. the 10:12:12.123 on 2nd of January 2010 (UTC))
 * with millisecond precision.<br>
 * Does not depend on timezones etc.
 */
trait TimePoint extends Ordered[TimePoint] with Equals {
  /** ms (excl. leap seconds) sice 01.01.1970 00:00:00.000 UTF */
  val unixTime: Long

  def +(duration: Duration): TimePoint =
    t(unixTime + duration.amountAs(Milliseconds))
  def -(duration: Duration): TimePoint =
    t(unixTime - duration.amountAs(Milliseconds))
  def -(other: TimePoint): Duration =
    (unixTime - other.unixTime) ms

  override def compare(o: TimePoint) = {
    longWrapper(unixTime).compareTo(o.unixTime)
  }
  override def hashCode = unixTime.hashCode
  override def equals(o: Any) = o match {
    case o: TimePoint =>
      if (o.canEqual(this)) unixTime == o.unixTime
      else false
    case other => false
  }
  override def canEqual(o: Any) = o.isInstanceOf[TimePoint]

  def asXmlDateTime = {
    val t = Instant.millis(unixTime)
    val dt = OffsetDateTime.fromInstant(t, ZoneOffset.UTC)
    calendar.format.DateTimeFormatters.isoDateTime().print(dt)
  }
  def asInstant = Instant.millis(unixTime)
  def asDateTime = OffsetDateTime.fromInstant(asInstant, ZoneOffset.UTC)
  def asDate = asDateTime.toOffsetDate

  override def toString = {
    new java.util.Date(unixTime).toString
  }

  private def t(t: Long) = TimePoint.unixTime(t)
}

object TimePoint {
  def current: TimePoint = unixTime(System.currentTimeMillis())
  def unixTime(time: Long): TimePoint = {
    new TimePoint {
      override val unixTime = time
    }
  }
}
