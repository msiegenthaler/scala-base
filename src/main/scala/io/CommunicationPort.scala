package ch.inventsoft.scalabase
package io

import process._
import Messages._
import oip._


/** Port for bidirectional communication with another party. */
trait CommunicationPort[In,Out] extends Sink[Out] with Source[In]


object CommunicationPort {
  /** Creates a communication port containing a source and a sink */
  def apply[In,Out,X <% SourceSink[In,Out]](
      open: => X @process,
      close: X => Any @process = (x: X) => noop): Selector[CommunicationPort[In,Out]] @process = {
    val port = {
      val holder = open
      new SourceSinkCommunicationPort[In,Out] {
        override val source = holder.source
        override val sink = holder.sink
      }
    }
    val mgrSel = ResourceManager[CommunicationPort[In,Out]](port, _.close)
    mgrSel.map(_.resource)
  }
  
  type SourceSink[In,Out] = {
    val source: Source[In]
    val sink: Sink[Out]
  }

  private trait SourceSinkCommunicationPort[In,Out] extends CommunicationPort[In,Out] with oip.ConcurrentObject {
    protected[this] val source: Source[In]
    protected[this] val sink: Sink[Out]
    
    override def read = source.read

    override def write(items: Iterable[Out]) = sink.write(items)
    override def write(item: Out) = sink.write(item)
    override def writeCast(items: Iterable[Out]) = sink.writeCast(items)
    override def writeCast(item: Out) = sink.writeCast(item)

    override def close = concurrentWithReply {
      val l = source.close :: sink.close :: Nil
      l.foreach_cps(_.await)
    }
  }
}
