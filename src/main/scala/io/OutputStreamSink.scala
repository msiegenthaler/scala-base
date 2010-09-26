package ch.inventsoft.scalabase
package io

import scala.collection.mutable.WrappedArray
import java.io.OutputStream
import process._
import Messages._
import oip._
import executionqueue._
import time._


object OutputStreamSink extends SpawnableCompanion[OutputStreamSink] {
  def apply(os: OutputStream, as: SpawnStrategy = Spawn.asChild(Required)(executeForBlocking)) = {
    val sink = new OutputStreamSink {
      override val _output = os
    }
    start(as)(sink)
  }
}

/**
 * Sink backed by an ordinary java OutputStream. Take care to use a blocking executor to avoid
 * straving other processes
 */
trait OutputStreamSink extends Sink[Byte] with StateServer {
  /** The output stream to write to */
  protected[this] val _output: OutputStream
  protected[this] val maxWrittenAtATime = 64*1024 // 64k

  protected[this] override type State = OutputStream
  protected[this] override def init = {
    ResourceManager[OutputStream](_output, _.close).receive.resource
  }
  protected[this] override def termination(output: State) = {
    output.close
  }
  
  override def write(items: Iterable[Byte]) = get { output =>
    writeBlocking(output, items)
  }
  override def writeCast(items: Iterable[Byte]) = cast { output =>
    writeBlocking(output, items)
    output
  }
  protected[this] def writeBlocking(output: OutputStream, items: Iterable[Byte]): Unit = items match {
    case array: WrappedArray[Byte] =>
      output.write(array.array)
    case iterable =>
      iterable.foreach(output.write(_))
  }

  override def close = stopAndWait
}
