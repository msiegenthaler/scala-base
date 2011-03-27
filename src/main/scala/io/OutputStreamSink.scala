package ch.inventsoft.scalabase
package io

import scala.collection.mutable.WrappedArray
import java.io.OutputStream
import process._
import Messages._
import oip._
import executionqueue._
import time._


object OutputStreamSink {
  def apply(os: OutputStream, as: SpawnStrategy = Spawn.asChild(Required)(executeForBlocking)) = {
    val sink = new OutputStreamSink {
      override val _output = os
    }
    Spawner.start(sink, as)
  }
}

/**
 * Sink backed by an ordinary java OutputStream. Take care to use a blocking executor to avoid
 * straving other processes
 */
trait OutputStreamSink extends Sink[Byte] with StateServer {
  /** The output stream to write to */
  protected val _output: OutputStream
  protected val maxWrittenAtATime = 64*1024 // 64k

  protected override type State = OutputStream
  protected override def init = {
    ResourceManager[OutputStream](_output, _.close).receive.resource
  }
  protected override def termination(output: State) = {
    output.close
  }
  
  override def write(items: Seq[Byte]) = get { output =>
    writeBlocking(output, items)
  }
  override def writeCast(items: Seq[Byte]) = cast { output =>
    writeBlocking(output, items)
    output
  }
  protected def writeBlocking(output: OutputStream, items: Seq[Byte]): Unit = items match {
    case array: WrappedArray[Byte] =>
      output.write(array.array)
    case iterable =>
      iterable.foreach(output.write(_))
  }

  override def close = stopAndWait

  override def toString = "OutputStreamSink"
}
