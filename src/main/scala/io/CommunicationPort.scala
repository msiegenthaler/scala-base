package ch.inventsoft.scalabase
package io

import process._
import Messages._
import oip._
import time._


/** Port for bidirectional communication with another party. */
trait CommunicationPort[In,Out] extends Source[In] with Sink[Out]


object CommunicationPort {
  def apply[In,Out,X <: PortState[In,Out]](
      open: => X @process,
      close: X => Unit @process,
      as: SpawnStrategy = SpawnAsRequiredChild): Selector[CommunicationPort[In,Out]] @process = {

    val token = RequestToken[CommunicationPort[In,Out]]
    as.spawn {
      val state = open
      val port = createPort[In,Out,X](state)
      token.reply(port)
      waitForTermination[In,Out,X](state, close)
    }
    token.select
  }

  private def createPort[In,Out,X <: PortState[In,Out]](state: X) = {
    val p = self
    val port = new SimpleCommunicationPort[In,Out] {
      override val process = p
      override val source = state.source
      override val sink = state.sink
    }
    port
  }
  private def waitForTermination[In,Out,X <: PortState[In,Out]](state: X, close: X => Unit @process) = {
    receive {
      case msg: StopPort =>
        close(state)
      msg.reply(())
    }
  }


  private trait SimpleCommunicationPort[In,Out] extends CommunicationPort[In,Out]
      with ConcurrentObject {
    protected val process: Process
    protected val source: Source[In]
    protected val sink: Sink[Out]

    override def read(maxItems: Int) = source.read(maxItems)
    override def readWithin(timeout: Duration, maxItems: Int) = source.readWithin(timeout, maxItems)

    override def write(data: Seq[Out]) = sink.write(data)
    override def write(data: Out) = sink.write(data)
    override def writeCast(data: Seq[Out]) = sink.writeCast(data)
    override def writeCast(data: Out) = sink.writeCast(data)

    override def close = {
      StopPort().sendAndSelect(process)
    }
    override def toString = "CommunicationPort"
  }
  private case class StopPort() extends MessageWithSimpleReply[Unit]


  type PortState[In,Out] = {
    val source: Source[In]
    val sink: Sink[Out]
  }
}

