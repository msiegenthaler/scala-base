package ch.inventsoft.scalabase
package io

import process._
import Messages._
import time._


/**
 * A source of i/o data.
 * It abstracts a "slow" data-source, such as a network or a harddrive. The cpu is forced to wait for
 * remote or slow devices.
 */
trait Source[A] {
  /**
   * Read the next fragment of data (wait until available).
   * @param maxItems maximum number of items to read
   */
  def read(maxItems: Int=Integer.MAX_VALUE): Read[A] @process

  /**
   * Read the next fragment of data if something becomes available within the
   * specified timeout. Else None is returned and no data is read (or lost).
   * @param timeout
   * @param maxItems maximum number of items to read
   */
  def readWithin(timeout: Duration, maxItems: Int=Integer.MAX_VALUE): Option[Read[A]] @process

  /** Close the reader and the source */
  def close: Completion @process
}

sealed trait Read[+A] {
  def isData: Boolean
  def isEnd: Boolean = !isData
}
/** One or more data items has been read */
final case class Data[+A](items: Seq[A]) extends Read[A] {
  override def isData = true
}
/** No more data is available (i.e. end of file). */
final object EndOfData extends Read[Nothing] {
  override def isData = false
}
