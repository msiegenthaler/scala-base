package ch.inventsoft.scalabase.process.cps

import Process._
import collection.mutable.{Buffer,ListBuffer}

class MessageQueueReadWriteLockSpec extends MessageQueueSpec  {
  override def makeQueue[A] = new MessageQueueReadWriteLock[A]
  override def name = "MessageQueueReadWriteLock"
}
