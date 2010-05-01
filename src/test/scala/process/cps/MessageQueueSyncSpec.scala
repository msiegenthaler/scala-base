package ch.inventsoft.scalabase.process.cps

import Process._
import collection.mutable.{Buffer,ListBuffer}

class MessageQueueSyncSpec extends MessageQueueSpec  {
  override def makeQueue[A] = new MessageQueueSync[A]
  override def name = "MessageQueueSync"
}
