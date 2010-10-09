package ch.inventsoft.scalabase
package io

import process._
import Messages._
import oip._
import time._


/**
 * Alternative to source for other styles of data-sources to make the implementation
 * easier.
 * Can be converted to a source.
 */
trait Reader[A] {
  /**
   * Reads a fragment of data and calls the readFun with it. As long as the readFun
   * return Next it is called again with the next fragment of data. The readFun is
   * executed in a process managed by the source.
   * If EndOfData is read then the return value of readerFun is ignored, since it
   * does not make sense to read on.
   * 
   * Be careful if waiting for messages to be sent to self (receive-constructs), the
   * source could requested to be closed while the readFun is blocking. You should
   * include 'Terminate' in the partial function and resend that message to self
   * in the handler (receive { ...; case Terminate => self ! Terminate; doSome))
   */
  def read(readerFun: Read[A] => ReadResult @process): Unit @process

  /**
   * Close the source.
   * If a readFun is still running close will not finish until the read function is
   * terminated.
   */
  def close: Completion @process
}

sealed trait ReadResult
/** Call the readerFun again with the next set of data (as soon as available). */
object Next extends ReadResult
/** Do not call the readFun again */
object End extends ReadResult


object Reader {
  type ReaderFun[A] = Read[A] => ReadResult @process

  def toSource[A](reader: => Reader[A] @process, as: SpawnStrategy = SpawnAsRequiredChild): Source[A] @process = {
    val source = new SourceReaderImpl[A] {
      override def openReader = reader
    }
    Spawner.start(source, as)
  }

  trait SourceReaderImpl[A] extends Source[A] with StateServer {
    protected[this] def openReader: Reader[A] @process

    protected[this] object ReadNext
    protected[this] case class Buffered(read: Read[A], from: Process) {
      def ack = from ! ReadNext
    }
    protected[this] override type State = (Reader[A], Option[Buffered])
    protected[this] override def init = {
      val reader = ResourceManager[Reader[A]](openReader, _.close).receive.resource
      reader.read(doRead _)
      (reader, None)
    }
    protected[this] override def termination(state: State) = {
      state._2.foreach_cps(_.ack)
      state._1.close.await
    }
    protected[this] override def handler(state: State) = super.handler(state).orElse_cps {
      case b: Buffered =>
        assert(state._2.isEmpty, "Buffer already full")
        Some((state._1, Some(b)))
    }

    override def read = call {
      _ match {
        case (src, Some(buffered)) =>
          buffered.ack
          (buffered.read, (src, None))
        case state@(_, None) =>
          receive {
            case buffered: Buffered =>
              buffered.ack
              (buffered.read, state)
            case Terminate =>
              (EndOfData, state)
          }
      }
    }.receive

    override def read(timeout: Duration) = call {
      _ match {
        case (src, Some(buffered)) =>
          buffered.ack
          (Some(buffered.read), (src, None))
        case state@(_, None) =>
          receiveWithin(timeout) {
            case buffered: Buffered =>
              buffered.ack
              (Some(buffered.read), state)
            case Terminate =>
              (None, state)
            case Timeout =>
              (None, state)
          }
      }
    }.receive

    def close = stopAndWait

    protected[this] def doRead(in: Read[A]): ReadResult @process = in match {
      case data: Data[A] =>
        process ! Buffered(in, self)
        receive {
          case ReadNext => Next
          case Terminate =>
            self ! Terminate
            End
        }
      case EndOfData =>
        endReader
        End
    }
    protected[this] def endReader: Unit @process = {
      process ! Buffered(EndOfData, self)
      receive {
        case ReadNext => endReader
        case Terminate =>
          self ! Terminate
          noop
      }
    }
  }
}


