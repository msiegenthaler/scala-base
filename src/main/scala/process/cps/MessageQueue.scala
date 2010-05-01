package ch.inventsoft.scalabase.process.cps

import ch.inventsoft.scalabase.extcol.ListUtil._
import ch.inventsoft.scalabase.executionqueue._


/**
 * A queue that allows to register a callback on certain messages (with a matcher).
 */
trait MessageQueue[A] {
  def enqueue(msg: A): Unit
  def enqueue(msgs: Seq[A]): Unit
  /**
   * @return all messages from the queue. Order: last inserted (newest) is first in list
   */
  def drain: List[A]
  /**
   * Adds a message capture. The capture is called as soon as a message matching the filter is added to the queue.
   * If there's already a matching message then it will call capture immediately. A capture only matches one message
   * and is remove after that.
   * An existing capture will be overwritten.
   */
  def captureMessage(fun: PartialFunction[A,_]): Unit
  /**
   * Unsets a capture.
   */
  def cancelCapture(fun: PartialFunction[A,_]): Boolean
}


/**
 * MessageQueue that uses a read write-lock to synchronize the readers and writers.
 * It allows for concurrent enqueues, but a dequeue blocks all enqueues.
 * The captures are executed on the enqueuing-thread.
 */
class MessageQueueReadWriteLock[A] extends MessageQueue[A] {
  import java.util.concurrent.locks._
  import java.util.concurrent.atomic._
  
  private[this] val lock = new ReentrantReadWriteLock
  // to _ you must hold _:   read: lock.readLock       write: lock.writeLock
  private[this] var capture: PartialFunction[A,_] = NoCapture
  // to _ you must hold _:   read: nothing             write: lock.readLock
  private[this] var pending: AtomicReference[List[A]] = new AtomicReference(Nil)
  
  protected[this] object NoCapture extends PartialFunction[A,Unit] {
    override def isDefinedAt(msg: A) = false
    override def apply(msg: A) = ()
  }
 
  
  override def enqueue(msg: A) = {
    val fun = read {
      if (capture.isDefinedAt(msg)) Some(executeCapture(capture) _)
      else {
        addToPendingMsgs(msg)
        None
      }
    } match {
      case Some(fun) => fun(msg)
      case None => ()
    }
  }
  override def enqueue(msgs: Seq[A]) = 
    msgs.foreach(enqueue _)
  override def drain = read {
    resetPendingMsgs
  }
  
  override def captureMessage(fun: PartialFunction[A,_]) = {
    write {
      val msgs = pending.get
      removeLast(msgs, fun.isDefinedAt) match {
        case Some((msg, newMsgs)) =>
          if (!pending.compareAndSet(msgs, newMsgs)) {
            println("expected: "+msgs)
            println("but is:   "+pending.get)
            throw new AssertionError("invalid synchronization")
          }
          Some(msg)
        case None =>
          capture = fun
          None
      }
    } match {
      case Some(msg) => fun(msg)
      case None =>
    }
  }
  override def cancelCapture(fun: PartialFunction[A,_]) = {
    write {
      if (capture == fun) {
        capture = NoCapture
        true
      } else false
    }
  }
  
  protected[this] def executeCapture(c: PartialFunction[A,_])(msg: A) = {
    write {
      if (capture == c) capture = NoCapture
    }
    c(msg)
  }
  /** you must hold at least the read-lock to call this method */ 
  private[this] def addToPendingMsgs(msg: A): Unit = {
    val msgs = pending.get
    if (!pending.compareAndSet(msgs, msg :: msgs)) addToPendingMsgs(msg)
  }
  /** you must hold at least the read-lock to call this method */ 
  protected[this] final def resetPendingMsgs: List[A] = {
    val msgs = pending.get
    if (!pending.compareAndSet(msgs, Nil)) resetPendingMsgs
    else msgs
  }
  
  protected[this] def read[A] = withLock[A](lock.readLock) _
  protected[this] def write[A] = withLock[A](lock.writeLock) _
  protected[this] def withLock[A](lock: Lock)(f: => A): A = {
    lock.lock
    try {
      f
    } finally {
      lock.unlock
    }
  }
}


/**
 * Synchronized message queue. Does synchronize all enqueues and dequeues.
 * The captures are executed on the enqueuing-thread.
 */ 
class MessageQueueSync[A] extends MessageQueue[A] {
  private var messages: List[A] = Nil
  private var handler: (A) => Boolean = no_handler
  private val mutex = new Object

  private def no_handler(msg: A) = false

  override def enqueue(msg: A): Unit = mutex synchronized {
    if (handler(msg)) handler = no_handler 
    else messages = msg :: messages
  }
  override def enqueue(msgs: Seq[A]): Unit = {
    val msgsList = msgs.toList
    mutex synchronized { removeFirst(msgsList, handler) match {
      case Some((_, msgsList)) =>
        messages = msgsList.reverse ::: messages
      case None =>
        messages = msgsList.reverse ::: messages
    }}
  }

  override def drain: List[A] = {
    val ms = mutex synchronized {
      val old = messages
      messages = Nil
      old
    }
    ms
  }

  override def captureMessage(fun: PartialFunction[A,_]): Unit = {
    mutex synchronized {
      removeLast(messages, fun.isDefinedAt) match {
        case Some((msg, msgs)) =>
          messages = msgs
          Some(msg)
        case None =>
          handler = new Handler(fun)
          None
      }
    } match {
      case Some(msg) => fun(msg)
      case None => ()
    }
  }
  override def cancelCapture(fun: PartialFunction[A,_]): Boolean = {
    mutex synchronized {
      handler match {
        case Handler(f) if f == fun =>
          handler = no_handler
          true
        case otherwise => false
      }
    }
  }
  
  private[this] case class Handler(fun: PartialFunction[A,_]) extends Function1[A,Boolean] {
    override def apply(msg: A) = {
      if (fun.isDefinedAt(msg)) {
          fun(msg)
          true
      } else false
    }
  }
}
