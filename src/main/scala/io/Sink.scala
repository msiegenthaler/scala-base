package ch.inventsoft.scalabase
package io

import process._
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
  def write(item: A): Completion @process = write(item :: Nil)
  /** Write a bunch of items */
  def write(items: Seq[A]): Completion @process

  /**
   * Write the item without a way to track the success or a flow control.
   * Be carefull not to run out-of-memory if the writter is faster then the device written to.
   */
  def writeCast(item: A): Unit @process = writeCast(item :: Nil)
  /** Write a bunch of item without tracking */
  def writeCast(items: Seq[A]): Unit @process

  /** Close the sink */
  def close: Completion @process
}
