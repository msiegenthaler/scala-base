package ch.inventsoft.scalabase
package io

import process._
import Messages._
import oip._


/**
 * A sink that transforms the items written before writing it to an underlying
 * sink.
 */
trait TransformingSink[A,B,Accumulator] extends Sink[A] with StateServer {
  protected case class TSState(sink: Sink[B], accumulator: Accumulator)
  protected override type State = TSState

  protected[this] override def init = {
    val rm = ResourceManager[Sink[B]](openSink, _.close).receive
    val a = createAccumulator
    val (data,a2) = process(a, Nil)
    if (data.nonEmpty) rm.resource.write(data).await
    TSState(rm.resource, a2)
  }
  protected[this] override def termination(state: State) = {
    val left = processEnd(state.accumulator)
    if (left.nonEmpty) {
      state.sink.write(left).await
    } else noop
  }
  
  protected[this] def openSink: Sink[B] @process
  protected[this] def createAccumulator: Accumulator @process
  protected[this] def process(accumulator: Accumulator, add: Seq[A]): (Seq[B],Accumulator) @process
  protected[this] def processEnd(accumulator: Accumulator): Seq[B] @process = Nil

  override def write(items: Seq[A]) = call { state => 
    val (w, acc) = process(state.accumulator, items)
    state.sink.write(w).await
    ((), state.copy(accumulator = acc))
  }
  override def writeCast(items: Seq[A]) = cast { state => 
    val (w, acc) = process(state.accumulator, items)
    state.sink.writeCast(w)
    state.copy(accumulator = acc)
  }

  override def close = stopAndWait
}
