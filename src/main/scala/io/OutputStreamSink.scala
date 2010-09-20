package ch.inventsoft.scalabase.io

import scala.collection.mutable.WrappedArray
import ch.inventsoft.scalabase.process._
import Messages._
import ch.inventsoft.scalabase.oip._
import ch.inventsoft.scalabase.executionqueue.ExecutionQueues._
import ch.inventsoft.scalabase.time._
import java.io.OutputStream


object OutputStreamSink extends SpawnableCompanion[OutputStreamSink] {
  def apply(os: OutputStream, as: SpawnStrategy = Spawn.asChild(Required)(executeForBlocking)) = {
    val sink = new OutputStreamSink {
      override val output = os
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
  protected[this] val output: OutputStream
  protected[this] val maxWrittenAtATime = 64*1024 // 64k

  protected[this] override type State = Unit
  protected[this] override def init = noop
  protected[this] override def termination(state: State) = {
    output.close
  }
  
  override def write(items: Iterable[Byte]) = get { _ =>
    writeBlocking(items)
  }
  override def writeCast(items: Iterable[Byte]) = cast { _ =>
    writeBlocking(items)
    ()
  }
  protected[this] def writeBlocking(items: Iterable[Byte]): Unit = items match {
    case array: WrappedArray[Byte] =>
      output.write(array.array)
    case iterable =>
      iterable.foreach(output.write(_))
  }

  override def close = stopAndWait
}