package ch.inventsoft.scalabase
package io

import process._


/** Port for bidirectional communication with another party. */
trait CommunicationPort[A] extends Sink[A] with Source[A]


object CommunicationPort {
  def apply[A](source: Source[A], sink: Sink[A]): CommunicationPort[A] = {
    val so = source
    val si = sink
    new SourceSinkCommunicationPort[A] {
      override val source = so
      override val sink = si
    }
  }
}

trait SourceSinkCommunicationPort[A] extends CommunicationPort[A] with oip.ConcurrentObject {
  protected[this] val source: Source[A]
  protected[this] val sink: Sink[A]

  override def read = source.read

  override def write(items: Iterable[A]) = sink.write(items)
  override def write(item: A) = sink.write(item)
  override def writeCast(items: Iterable[A]) = sink.writeCast(items)
  override def writeCast(item: A) = sink.writeCast(item)

  override def close = concurrentWithReply {
    val l = source.close :: sink.close :: Nil
    l.foreach_cps(_.await)
  }
}
