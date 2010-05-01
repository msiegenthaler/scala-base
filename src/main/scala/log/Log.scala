package ch.inventsoft.scalabase.log

import org.slf4j._

/**
 * Adds a logger to the implementing class. 
 * @author ms
 */
trait Log {
  protected[this] val log = LoggerFactory.getLogger(this.getClass)
}

/**
 * Mapped diagnositic context.
 */
object MDC {
  def put(key: String, value: String) = org.slf4j.MDC.put(key, value)
  def remove(key: String) = org.slf4j.MDC.remove(key)
  def clear() = org.slf4j.MDC.clear()
  
  def apply[T](key: String, value: String)(body: => T): T = {
    try {
      put(key, value)
      body
    } finally {
      remove(key)
    }
  }
}