package ch.inventsoft.scalabase
package io

import org.scalatest._
import matchers._
import oip._
import process._
import time._


class OneToOneTransformingSinkSpec extends ProcessSpec with ShouldMatchers {
  describe("OneToOneTransformingSink") {
    it_("should transform a single item written with write") {
      val a = StringSink()
      val b = trans(a)
      a.assertValue("")
      b.write(9)
      a.assertValue("9")
    }
    it_("should transform a single item written with writeCast") {
      val a = StringSink()
      val b = trans(a)
      a.assertValue("")
      b.writeCast(12)
      a.assertValue("12")
    }
    it_("should transform a three items written with write") {
      val a = StringSink()
      val b = trans(a)
      b.write(9 :: 10 :: 11 :: Nil)
      a.assertValue("91011")
    }
    it_("should transform a three items written with writeCast") {
      val a = StringSink()
      val b = trans(a)
      b.writeCast(9 :: 10 :: 11 :: Nil)
      a.assertValue("91011")
    }
    it_("should forward the call to close") {
      val a = StringSink()
      val b = trans(a)
      b.close.await
      val x = a.value.receiveOption(100 ms)
      x should be(None)
    }
  }

  def trans(ss: Sink[String]) = {
    new OneToOneTransformingSink[Int,String] {
      val sink = ss
      override def transform(v: Int) = v.toString
    }
  }

  class StringSink extends Sink[String] with StateServer {
    override type State = String
    override def init = ""
    def value: Selector[String] @process = get((s: String) => s)
    def assertValue(expected: String) = {
      val v = value.receiveWithin(1 s)
      v should be(expected)
    }
    override def write(items: Seq[String]) = call { v =>
      ((), items.foldLeft(v)(_ + _))
    }
    override def writeCast(items: Seq[String]) = cast { v =>
      items.foldLeft(v)(_ + _)
    }
    override def close = stopAndWait
  }
  object StringSink {
    def apply() = {
      Spawner.start(new StringSink(), SpawnAsRequiredChild)
    }
  }
}
