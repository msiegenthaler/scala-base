package ch.inventsoft.scalabase
package io

import scala.collection.mutable.WrappedArray
import java.io.InputStream
import process._
import Messages._
import oip._
import executionqueue._
import time._


object InputStreamSource extends SpawnableCompanion[InputStreamSource] {
  def apply(is: InputStream, bufferSizeBytes: Int = 1024, as: SpawnStrategy = Spawn.asChild(Required)(executeForBlocking)) = {
    val source = new InputStreamSource {
      override val _input = is
      override val bufferSize = bufferSizeBytes
    }
    start(as)(source)
  }
}

/**
 * Source based on a InputStream. Take care to use a blocking executor to avoid staving the other
 * processes.
 */
trait InputStreamSource extends Source[Byte] with StateServer {
  /** The input stream to read from */
  protected[this] val _input: InputStream
  /** Max bytes that are read in a single read */
  protected[this] val bufferSize: Int = 1024

  type State = InputStream

  protected[this] override def init = {
    ResourceManager.forAlreadyOpen(_input, _input.close).receive.resource
  }
  protected[this] override def termination(state: State) = {
    //_input gets closed by its resource manager
    noop
  }

  override def read = get { input =>
    val buffer = new Array[Byte](bufferSize)
    input.read(buffer) match {
      case -1 => EndOfData
      case 0 => Data(Nil)
      case count => 
        val d = new WrappedArray.ofByte(buffer)
        Data(d.take(count))
    }
  }

  override def close = stopAndWait
}
