package ch.inventsoft.scalabase
package io

import scala.collection.mutable.WrappedArray
import java.io.InputStream
import process._
import Messages._
import oip._
import executionqueue._
import time._


object InputStreamSource {
  def apply(input: => InputStream @process, bufferSizeBytes: Int = 1024, as: SpawnStrategy = Spawn.asChild(Required)(executeForBlocking)) = {
    Reader.toSource(reader(input, bufferSizeBytes, as))
  }
  def reader(input: => InputStream @process, bufferSizeBytes: Int = 1024, as: SpawnStrategy = Spawn.asChild(Required)(executeForBlocking)) = {
    val reader = new InputStreamReader {
      override def openInput = input
      override val bufferSize = bufferSizeBytes
    }
    Spawner.start(reader, as)
  }
}


/**
 * Source based on a InputStream.
 * Make sure to only use forBlocking executors to spawn the InputStreamSource.
 */
trait InputStreamReader extends Reader[Byte] with Spawnable with ConcurrentObject {
  /** The input stream to read from */
  protected def openInput: InputStream @process
  /** Max bytes that are read in a single read */
  protected val bufferSize: Int = 1024

  protected type ReadFun = Read[Byte] => ReadResult @process

  protected override def body = {
    val input = ResourceManager[InputStream](openInput, _.close).receive.resource
    loop(input)
    input.close //no problem if closed twice
  }
  protected def loop(input: InputStream): Unit @process = {
    receiveNoWait {
      case Terminate => noop //end has priority
      case Timeout => receive {
        case fun: ReadFun =>
          doRead(fun, input)
          loop(input)
        case Terminate => noop //end
      }
    }
  }
  protected def doRead(fun: ReadFun, input: InputStream): Unit @process = {
    val buffer = new Array[Byte](bufferSize)
    val read = input.read(buffer) match {
      case -1 => EndOfData
      case 0 => Data(Nil)
      case count => 
        val d = new WrappedArray.ofByte(buffer)
        Data(d.take(count))
    }
    val res = fun(read)
    res match {
      case End => noop
      case Next => if (read.isData) doRead(fun, input) else noop
    }
  }

  override def read(readerFun: Read[Byte] => ReadResult @process) = {
    process ! readerFun
  }
  override def close = replyInCallerProcess {
    process ! Terminate
    ()
  }
  override def toString = "InputStreamReader"
}
