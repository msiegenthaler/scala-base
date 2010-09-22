package ch.inventsoft.scalabase
package time

/**
 * Duration in the temporal dimension (i.e. 24 seconds).
 */
class Duration protected(protected val nanos: Long, unit: TimeUnit) extends Ordered[Duration] {
  def as(u: TimeUnit) = {
    if (unit != u) new Duration(nanos, u)
    else this
  }
  def amount(u: TimeUnit): Long = u.fromNanosRound(nanos)
  def amountAs(u: TimeUnit): Long = amount(u)
  
  def +(t: Duration) = withAmount(nanos+t.nanos)
  def -(t: Duration) = withAmount(nanos-t.nanos)
  def *(factor: Int) = withAmount(nanos*factor)
  def /(divideBy: Int) = withAmount(nanos/divideBy)
  def /(time: Duration) = (nanos.toDouble / time.nanos).round
  def max(t: Duration) = withAmount(nanos max t.nanos)
  def min(t: Duration) = withAmount(nanos min t.nanos)
  def abs = withAmount(nanos abs)
  def isZero = nanos == 0
  def isPositive = nanos > 0
  def isNegative = nanos < 0
  private def withAmount(nanos: Long) = new Duration(nanos, unit)
  
  
  def equalsApprox(other: Duration, unit: TimeUnit) = amountAs(unit) == other.amountAs(unit) 
  override def equals(o: Any) = o match {
    case o: Duration => nanos == o.nanos
    case _ => false
  }
  override def compare(other: Duration) = {
    amount(Nanoseconds).compare(other.amount(Nanoseconds)) 
  }
  override def toString = {
    val u = {
      val b = unit.toString.toLowerCase
      if (amount(unit) == 1 && b.endsWith("s")) b.substring(0, b.length-1)
      else b
    }
    amount(unit).toString + " " + u
  }
}

object Duration {
  def apply(amount: Long, unit: TimeUnit) = {
    val nanos = unit.inNanos(amount)
    new Duration(nanos, unit)
  }
  
  class DurationMaker(amount: Long) {
    def nanoseconds = Duration(amount, Nanoseconds)
    def nanosecond = nanoseconds
    def ns = nanoseconds
    def microseconds = Duration(amount, Microseconds)
    def microsecond = microseconds
    def μsec = microseconds
    def milliseconds = Duration(amount, Milliseconds)
    def millisecond = milliseconds
    def millis = milliseconds
    def ms = milliseconds
    def seconds = Duration(amount, Seconds)
    def second = seconds
    def s = seconds
    def minutes = Duration(amount, Minutes)
    def minute = minutes
    def hours = Duration(amount, Hours)
    def hour = hours
    def days = Duration(amount, Days)
    def day = days
  }
}

object immediately extends Duration(0, Nanoseconds)
