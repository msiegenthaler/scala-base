package ch.inventsoft.scalabase
package time


/** Unit of time (i.e. second, millisecond) */
trait TimeUnit {
  protected[time] def oneInNanos: Long
  protected[time] def inNanos(amount: Long) = amount * oneInNanos
  protected[time] def fromNanosFloor(ns: Long) = ns / oneInNanos
  protected[time] def fromNanosRound(ns: Long) = (ns.toDouble / oneInNanos).round
  override def toString = {
    val n = getClass.getSimpleName
    val p = n.indexOf("$")
    if (p == -1) n
    else n.substring(0, p)
  }
  def unapply(time: Duration) = Some(time.amountAs(this)) 
}

object Nanoseconds extends TimeUnit {
  override protected[time] def oneInNanos = 1L
  override protected[time] def fromNanosFloor(ns: Long) = ns
  override protected[time] def fromNanosRound(ns: Long) = ns
}
object Microseconds extends TimeUnit {
  override protected[time] def oneInNanos = 1000L
}
object Milliseconds extends TimeUnit {
  override protected[time] def oneInNanos = 1000000L
}
object Seconds extends TimeUnit {
  override protected[time] def oneInNanos = 1000000000L
}
object Minutes extends TimeUnit {
  override protected[time] def oneInNanos = 60000000000L  
}
object Hours extends TimeUnit {
  override protected[time] def oneInNanos = 3600000000000L  
}
object Days extends TimeUnit {
  override protected[time] def oneInNanos = 86400000000000L  
}
