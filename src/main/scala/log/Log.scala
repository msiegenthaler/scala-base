package ch.inventsoft.scalabase.log


/**
 * Adds a logger to the implementing class. 
 */
trait Log {
  protected[this] val log = {
    val thelog = org.slf4j.LoggerFactory.getLogger(this.getClass)
    new Slf4jLogger { protected[this] override val log = thelog }
  }
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

trait Logger {
  def error: LoggerLevel
  def warn: LoggerLevel
  def info: LoggerLevel
  def debug: LoggerLevel
  def trace: LoggerLevel
}
trait LoggerLevel {
  def enabled: Boolean
  def ifEnabled(body: => Any): Unit = if (enabled) body
  def apply(msg: String): Unit
  def apply(msg: String, p1: => Any): Unit
  def apply(msg: String, p1: => Any, p2: => Any): Unit
  def apply(msg: String, p1: => Any, p2: => Any, p3: => Any): Unit
  def apply(msg: String, p1: => Any, p2: => Any, p3: => Any, p4: => Any): Unit
  def apply(msg: String, p1: => Any, p2: => Any, p3: => Any, p4: => Any, p5: => Any): Unit
}


protected trait Slf4jLogger extends Logger {
  protected[this] val log: org.slf4j.Logger
  val error = {
    new ArrayLoggerLevel with ProcessAwareLoggerLevel {
      override def enabled = log.isErrorEnabled
      protected[this] override def applyArray(msg: String, ps: Array[Object]) = around { log.error(msg, ps) }
    }
  }
  val warn = {
    new ArrayLoggerLevel with ProcessAwareLoggerLevel {
      override def enabled = log.isWarnEnabled
      protected[this] override def applyArray(msg: String, ps: Array[Object]) = around { log.warn(msg, ps) }
    }
  }
  val info = {
    new ArrayLoggerLevel with ProcessAwareLoggerLevel {
      override def enabled = log.isInfoEnabled
      protected[this] override def applyArray(msg: String, ps: Array[Object]) = around { log.info(msg, ps) }
    }
  }
  val debug = {
    new ArrayLoggerLevel with ProcessAwareLoggerLevel {
      override def enabled = log.isDebugEnabled
      protected[this] override def applyArray(msg: String, ps: Array[Object]) = around { log.debug(msg, ps) }
    }
  }
  val trace = {
    new ArrayLoggerLevel with ProcessAwareLoggerLevel {
      override def enabled = log.isTraceEnabled
      protected[this] override def applyArray(msg: String, ps: Array[Object]) = around { log.trace(msg, ps) }
    }
  }
}

protected trait ProcessAwareLoggerLevel {
  protected[this] def around(what: => Any): Any = {
    val proc = ch.inventsoft.scalabase.process.useWithCare.currentProcess
    if (proc.isDefined) {
      MDC.put("process", proc.get.toString)
      try {
        what
      } finally {
        MDC.remove("process")
      }
    } else what
  }
}

trait ArrayLoggerLevel extends LoggerLevel {
  override def apply(msg: String) = if (enabled) applyArray(msg, Array())
  override def apply(msg: String, p1: => Any) = if (enabled) {
    applyArray(msg, Array(p1.asInstanceOf[AnyRef]))
  }
  override def apply(msg: String, p1: => Any, p2: => Any) = if (enabled) {
    applyArray(msg, Array(p1.asInstanceOf[AnyRef], p2.asInstanceOf[AnyRef]))
  }
  override def apply(msg: String, p1: => Any, p2: => Any, p3: => Any) = if (enabled) {
    applyArray(msg, Array(p1.asInstanceOf[AnyRef], p2.asInstanceOf[AnyRef], p3.asInstanceOf[AnyRef]))
  }
  override def apply(msg: String, p1: => Any, p2: => Any, p3: => Any, p4: => Any) = if (enabled) {
    applyArray(msg, Array(p1.asInstanceOf[AnyRef], p2.asInstanceOf[AnyRef], p3.asInstanceOf[AnyRef], p4.asInstanceOf[AnyRef]))
  }
  override def apply(msg: String, p1: => Any, p2: => Any, p3: => Any, p4: => Any, p5: => Any) = if (enabled) {
    applyArray(msg, Array(p1.asInstanceOf[AnyRef], p2.asInstanceOf[AnyRef], p3.asInstanceOf[AnyRef], p4.asInstanceOf[AnyRef], p5.asInstanceOf[AnyRef]))
  }
  protected[this] def around(what: => Any): Any
  protected[this] def applyArray(msg: String, ps: Array[Object]): Unit 
}

object IgnoreLoggerLevel extends LoggerLevel {
  override def enabled = false
  override def apply(msg: String) = ()
  override def apply(msg: String, p1: => Any) = ()
  override def apply(msg: String, p1: => Any, p2: => Any) = ()
  override def apply(msg: String, p1: => Any, p2: => Any, p3: => Any) = ()
  override def apply(msg: String, p1: => Any, p2: => Any, p3: => Any, p4: => Any) = ()
  override def apply(msg: String, p1: => Any, p2: => Any, p3: => Any, p4: => Any, p5: => Any) = ()
}


