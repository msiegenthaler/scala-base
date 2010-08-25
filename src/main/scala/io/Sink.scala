package ch.inventsoft.scalabase.io

import ch.inventsoft.scalabase.process._
import Messages._


/**
 * A sink of i/o data.
 * It abstracts a "slow" data-sink, such as a network or a harddrive. The cpu is forced to wait for
 * remote or slow devices.
 */
trait Sink[A] {
  /**
   * Write the item to the data-sink.
   * The reply is received as soon as the data is written.
   */
  def write(item: A): Selector[Unit] 
  /** Write a bunch of items */
  def write(items: Iterable[A]): Selector[Unit]

  /**
   * Write the item without a way to track the success or a flow control.
   * Be carefull not to run out-of-memory if the writter is faster then the device written to.
   */
  def writeCast(item: A): Unit
  /** Write a bunch of item without tracking */
  def writeCast(items: Iterable[A]): Unit

  /** Close the sink */
  def close: Selector[Unit]
}