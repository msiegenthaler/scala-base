package ch.inventsoft.scalabase
package io

import process._
import Messages._
import oip._


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
      (EndOfData,state)
  }}
  protected[this] def readLoop(source: Source[A], acc: Accumulator): (Read[B],Option[Accumulator]) @process = {
    val read = source.read.receive
    read match {
      case EndOfData => 
        val data = processEnd(acc)
        if (data.isEmpty) (EndOfData,None)
        else (Data(data),None)
      case Data(data) =>
        val (res,na) = process(acc, data)
        if (res.isEmpty) readLoop(source, na)
        else { noop; (Data(res),Some(na)) }
    }
  }

  override def close = stopAndWait
}
