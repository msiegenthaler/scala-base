package ch.inventsoft.scalabase
package io

import process._
import Messages._
import oip._
import time._


/**
 * A source that reads from an underlying source and transforms all read
 * data using a supplied function.
 */
trait TransformingSource[A,B,Accumulator] extends Source[B] with StateServer {
  protected case class TSState(source: Source[A], accumulator: Option[Accumulator])
  protected override type State = TSState
  
  protected[this] override def init = {
    val rm = ResourceManager[Source[A]](openSource, _.close).receive
    val a = createAccumulator
    TSState(rm.resource, Some(a))
  }

  protected[this] def openSource: Source[A] @process
  protected[this] def createAccumulator: Accumulator @process
  protected[this] def process(accumulator: Accumulator, add: Seq[A]): (Seq[B],Accumulator) @process
  protected[this] def processEnd(accumulator: Accumulator): Seq[B] @process = Nil

  override def read = call { state => state.accumulator match {
    case Some(acc) =>
      val (r,a) = readLoop(state.source, acc)
      (r, state.copy(accumulator=a))
    case None =>
      noop
      (EndOfData, state)
  }}.receive
  protected[this] def readLoop(source: Source[A], acc: Accumulator): (Read[B],Option[Accumulator]) @process = {
    val read = source.read
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

  override def read(timeout: Duration) = call { state => state.accumulator match {
    case Some(acc) =>
      val t = System.currentTimeMillis() + timeout.amountAs(Milliseconds)
      val (r,a) = readLoopWithTimeout(state.source, acc, t)
      (r, state.copy(accumulator=a))
    case None =>
      noop
      (Some(EndOfData), state)
  }}.receive
  protected[this] def readLoopWithTimeout(source: Source[A], acc: Accumulator, endTime: Long):
      (Option[Read[B]],Option[Accumulator]) @process = {
    val timeLeft = (endTime - System.currentTimeMillis()) ms;
    if (timeLeft.isNegative) {
      //to prevent cases where the source always delivers very fast but very little
      // data and exceeds the timeout doing that
      (None, Some(acc))
    } else {
      val read = source.read(timeLeft)
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
          (None, Some(acc))
      }
    }
  }

  override def close = stopAndWait
}
