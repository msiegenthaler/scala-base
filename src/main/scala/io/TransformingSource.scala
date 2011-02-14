package ch.inventsoft.scalabase
package io

import scala.collection.immutable.Queue
import process._
import Messages._
import oip._
import time._


/**
 * A source that reads from an underlying source and transforms all read
 * data using a supplied function.
 */
trait TransformingSource[A,B,Accumulator] extends Source[B] with StateServer {
  protected case class TSState(source: Source[A], accumulator: Option[Accumulator], buffer: Queue[B])
  protected override type State = TSState
  
  protected override def init = {
    val rm = ResourceManager[Source[A]](openSource, _.close).receive
    val a = createAccumulator
    TSState(rm.resource, Some(a), Queue())
  }

  protected def openSource: Source[A] @process
  protected def createAccumulator: Accumulator @process
  protected def process(accumulator: Accumulator, add: Seq[A]): (Seq[B],Accumulator) @process
  protected def processEnd(accumulator: Accumulator): Seq[B] @process = Nil

  override def read(max: Int) = call { state =>
    if (state.buffer.nonEmpty) (Data(state.buffer), state.copy(buffer=Queue()))
    else state.accumulator match {
      case Some(acc) =>
        val (r,a) = readLoop(state.source, acc)
        r match {
          case Data(data) if data.size>max =>
            val (h,t) = data.splitAt(max)
            (Data(h), state.copy(accumulator=a, buffer=state.buffer ++ t))
          case r =>
            (r, state.copy(accumulator=a))
        }
      case None =>
        noop
        (EndOfData, state)
    }
  }.receive
  protected def readLoop(source: Source[A], acc: Accumulator): (Read[B],Option[Accumulator]) @process = {
    val read = source.read()
    read match {
      case EndOfData => 
        val data = processEnd(acc)
        if (data.isEmpty) (EndOfData,None)
        else (Data(data),None)
      case Data(data) =>
        val (res,na) = process(acc, data)
        if (res.isEmpty) {
          readLoop(source, na)
        } else {
          noop
          (Data(res),Some(na))
        }
    }
  }

  override def readWithin(timeout: Duration, max: Int) = call { state =>
    if (state.buffer.nonEmpty) (Some(Data(state.buffer)), state.copy(buffer=Queue()))
    else state.accumulator match {
      case Some(acc) =>
        val t = System.currentTimeMillis() + timeout.amountAs(Milliseconds)
        val (r,a) = readLoopWithTimeout(state.source, acc, t)
        r match {
          case Some(Data(data)) if data.size>max =>
            val (h,t) = data.splitAt(max)
            (Some(Data(h)), state.copy(accumulator=a, buffer=state.buffer ++ t))
          case Some(_) =>
            (r, state.copy(accumulator=a))            
          case None => 
            (r, state.copy(accumulator=a))
        }
      case None =>
        noop
        (Some(EndOfData), state)
    }
  }.receive
  protected def readLoopWithTimeout(source: Source[A], acc: Accumulator, endTime: Long):
      (Option[Read[B]],Option[Accumulator]) @process = {
    val timeLeft = (endTime - System.currentTimeMillis()) ms;
    if (timeLeft.isNegative) {
      //to prevent cases where the source always delivers very fast but very little
      // data and exceeds the timeout doing that
      (None, Some(acc))
    } else {
      val read = source.readWithin(timeLeft)
      read match {
        case Some(EndOfData) => 
          val data = processEnd(acc)
          if (data.isEmpty) (Some(EndOfData),None)
          else (Some(Data(data)),None)
        case Some(Data(data)) =>
          val (res,na) = process(acc, data)
          if (res.isEmpty) {
            readLoopWithTimeout(source, na, endTime)
          } else {
            noop
            (Some(Data(res)),Some(na))
          }
        case None =>
          noop
          (None, Some(acc))
      }
    }
  }

  override def close = stopAndWait
}


/** Source that transforms one item read into exactly one item. */
trait OneToOneTransformingSource[A,B] extends Source[B] {
  protected val source: Source[A]
  protected def transform(from: A): B
  override def read(max: Int) = {
    val read = source.read(max)
    read match {
      case Data(data) =>
        Data(data.view.map(transform _))
      case EndOfData => EndOfData
    }
  }
  override def readWithin(timeout: Duration, max: Int) = {
    val read = source.readWithin(timeout, max)
    read match {
      case None => None
      case Some(Data(data)) =>
        Some(Data(data.view.map(transform _)))
      case Some(EndOfData) => Some(EndOfData)
    }
  }
  override def close = source.close
}
