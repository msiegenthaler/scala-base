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
  protected case class State(sink: Sink[B], accumulator: Accumulator)

  protected override def init = {
    val rm = ResourceManager[Sink[B]](openSink, _.close).receive
    val a = createAccumulator
    val (data,a2) = process(a, Nil)
    if (data.nonEmpty) rm.resource.write(data).await
    State(rm.resource, a2)
  }
  protected override def termination(state: State) = {
    val left = processEnd(state.accumulator)
    if (left.nonEmpty) {
      state.sink.write(left).await
    } else noop
  }
  
  protected def openSink: Sink[B] @process
  protected def createAccumulator: Accumulator @process
  protected def process(accumulator: Accumulator, add: Seq[A]): (Seq[B],Accumulator) @process
  protected def processEnd(accumulator: Accumulator): Seq[B] @process = Nil

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


/**
 * Sink that transforms one item to exactly one element of a different type.
 */
trait OneToOneTransformingSink[A,B] extends Sink[A] {
  protected val sink: Sink[B]
  protected def transform(from: A): B
  override def write(items: Seq[A]) = {
    val its = items.view.map(transform _)
    sink.write(its)
  }
  override def writeCast(items: Seq[A]) = {
    val its = items.view.map(transform _)
    sink.writeCast(its)
  }
  override def close = sink.close
}
