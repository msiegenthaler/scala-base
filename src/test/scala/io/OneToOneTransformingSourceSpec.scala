package ch.inventsoft.scalabase
package io

import org.scalatest._
import matchers._
import oip._
import process._
import time._


class OneToOneTransformingSourceSpec extends ProcessSpec with ShouldMatchers {
  describe("OneToOneTransformingSource") {
    it_("should transform a single item read") {
      val a = IntSource(1 :: 2 :: Nil)
      val b = trans(a)
      val x = b.read()
      x should be(Data("1" :: Nil))
    }
    it_("should transform a single item read with timeout") {
      val a = IntSource(1 :: 2 :: Nil)
      val b = trans(a)
      val x = b.readWithin(100 ms)
      x should be(Some(Data("1" :: Nil)))
    }
    it_("should transform two items read") {
      val a = IntSource(1 :: 2 :: Nil)
      val b = trans(a)
      val x = b.read()
      x should be(Data("1" :: Nil))
      val y = b.read()
      y should be(Data("2" :: Nil))
    }
    it_("should be forward to call to close") {
      val a = IntSource(1 :: 2 :: Nil)
      val b = trans(a)
      b.close.await
      val x = a.value.receiveOption(100 ms)
      x should be(None)
    }
  }

  def trans(ss: Source[Int]) = {
    new OneToOneTransformingSource[Int,String] {
      val source = ss
      override def transform(v: Int) = v.toString
    }
  }

  trait IntSource extends Source[Int] with StateServer {
    override type State = Seq[Int]
    def assertNumberLeft(number: Int) = get { s => 
      s.length should be(number)
    }
    def value: Selector[Seq[Int]] @process = get(s => s)
    override def read(max: Int) = call { s =>
      (Data(List(s.head)), s.tail)
    }.receive
    override def readWithin(t: Duration, max: Int) = call { s =>
      (Some(Data(List(s.head))), s.tail)
    }.receive
    override def close = stopAndWait
  }
  object IntSource {
    def apply(data: List[Int]) = {
      Spawner.start(new IntSource {
        override def init = data
      }, SpawnAsRequiredChild)
    }
  }
}
