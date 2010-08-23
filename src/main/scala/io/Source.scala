package ch.inventsoft.scalabase.io

import ch.inventsoft.scalabase.process._
import Messages._


/**
 * A source of i/o data.
 * It abstracts a "slow" data-source, such as a network or a harddrive. The cpu is forced to wait for
 * remote or slow devices.
 */
trait Source[A] {
  /**
   * Read the next fragment of data. Exactly one message is received, if no data is currently available
   * the answer is delayed until the next fragment becomes available.
   */
  def read: Selector[Read[A]]

  /** Close the source. */
  def close: Selector[Unit]
}


sealed trait Read[+A] {
  def isData: Boolean
  def isEnd: Boolean = !isData
}

final case class Data[+A](items: Iterable[A]) extends Read[A] {
  override def isData = true
}

final object EndOfData extends Read[Nothing] {
  override def isData = false
}
