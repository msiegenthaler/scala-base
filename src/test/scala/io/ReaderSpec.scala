package ch.inventsoft.scalabase
package io

import scala.collection.immutable.Queue
import org.scalatest._
import matchers._
import java.io._
import oip._
import process._
import time._


class ReaderSpec extends ProcessSpec with ShouldMatchers {
  describe("Reader.toSource") {
    describe("read") {
      it_("should return with EndOfData on an empty reader") {
        val reader = TestReader(Nil)
        val source = Reader.toSource(reader)
        val r = source.read()
        r should be(EndOfData)
        source.close.await
      }
      it_("should always return EndOfData on an empty reader") {
        val reader = TestReader(Nil)
        val source = Reader.toSource(reader)
        (1 to 100).foreach_cps { _ =>
          val r = source.read()
          r should be(EndOfData)
        }
        source.close.await
      }
      it_("should return a read followed by EndOfData on an one-element reader") {
        val reader = TestReader(Elem(12 :: 13 :: Nil) :: Nil)
        val source = Reader.toSource(reader)
        val r = source.read()
        r should be(Data(12 :: 13 :: Nil))
        val e = source.read()
        e should be(EndOfData)
        source.close.await
      }
      it_("should return a read followed by EndOfData on an one-element reader after a short wait") {
        val reader = TestReader(Elem(12 :: 13 :: Nil) :: Nil)
        val source = Reader.toSource(reader)
        sleep(200 ms)
        val r = source.read()
        r should be(Data(12 :: 13 :: Nil))
        val e = source.read()
        e should be(EndOfData)
        source.close.await
      }
      it_("should return a read followed by EndOfData on an one-element slow reader") {
        val reader = TestReader(Elem(12 :: 13 :: Nil, Some(200 ms)) :: Nil)
        val source = Reader.toSource(reader)
        val r = source.read()
        r should be(Data(12 :: 13 :: Nil))
        val e = source.read()
        e should be(EndOfData)
        source.close.await
      }
      it_("should return three reads followed by EndOfData on a three-element reader") {
        val reader = TestReader(Elem(12 :: 13 :: Nil) :: Elem(14 :: Nil) :: Elem(15 :: 16 :: Nil) :: Nil)
        val source = Reader.toSource(reader)
        val r1 = source.read()
        r1 should be(Data(12 :: 13 :: Nil))
        val r2 = source.read()
        r2 should be(Data(14 :: Nil))
        val r3 = source.read()
        r3 should be(Data(15 :: 16 :: Nil))
        val e = source.read()
        e should be(EndOfData)
        source.close.await
      }
      it_("should return three reads followed by EndOfData on a slow three-element reader") {
        val reader = TestReader(
          Elem(12 :: 13 :: Nil, Some(20 ms)) ::
          Elem(14 :: Nil, Some(500 ms)) ::
          Elem(15 :: 16 :: Nil, Some(10 ms)) :: Nil)
        val source = Reader.toSource(reader)
        val r1 = source.read()
        r1 should be(Data(12 :: 13 :: Nil))
        val r2 = source.read()
        r2 should be(Data(14 :: Nil))
        val r3 = source.read()
        r3 should be(Data(15 :: 16 :: Nil))
        val e = source.read()
        e should be(EndOfData)
        source.close.await
      }
      it_("should return only one element if read(1) is called") {
        val reader = TestReader(
          Elem(10 :: 11 :: 12 :: 13 :: 14 :: Nil, Some(10 ms)) ::
          Elem(20 :: Nil, Some(10 ms)) :: Nil)
        val source = Reader.toSource(reader)
        val r1 = source.read(1)
        r1 should be(Data(10 :: Nil))
        val r2 = source.read(2)
        r2 should be(Data(11 :: 12 :: Nil))
        val r3 = source.read()
        source.close.await
      }
    }
    describe("read with timeout") {
      it_("should return with EndOfData on an empty reader") {
        val reader = TestReader(Nil)
        val source = Reader.toSource(reader)
        val r = source.readWithin(200 ms)
        r should be(Some(EndOfData))
        source.close.await
      }
      it_("should always return EndOfData on an empty reader") {
        val reader = TestReader(Nil)
        val source = Reader.toSource(reader)
        (1 to 100).foreach_cps { _ =>
          val r = source.readWithin(200 ms)
          r should be(Some(EndOfData))
        }
        source.close.await
      }
      it_("should return a read followed by EndOfData on an one-element reader") {
        val reader = TestReader(Elem(12 :: 13 :: Nil) :: Nil)
        val source = Reader.toSource(reader)
        val r = source.readWithin(200 ms)
        r should be(Some(Data(12 :: 13 :: Nil)))
        val e = source.readWithin(200 ms)
        e should be(Some(EndOfData))
        source.close.await
      }
      it_("should return a read followed by EndOfData on an one-element reader after a short wait") {
        val reader = TestReader(Elem(12 :: 13 :: Nil) :: Nil)
        val source = Reader.toSource(reader)
        sleep(200 ms)
        val r = source.readWithin(200 ms)
        r should be(Some(Data(12 :: 13 :: Nil)))
        val e = source.readWithin(200 ms)
        e should be(Some(EndOfData))
        source.close.await
      }
      it_("should return a read followed by EndOfData on an one-element slow reader") {
        val reader = TestReader(Elem(12 :: 13 :: Nil, Some(200 ms)) :: Nil)
        val source = Reader.toSource(reader)
        val r = source.readWithin(500 ms)
        r should be(Some(Data(12 :: 13 :: Nil)))
        val e = source.readWithin(500 ms)
        e should be(Some(EndOfData))
        source.close.await
      }
      it_("should return three reads followed by EndOfData on a three-element reader") {
        val reader = TestReader(Elem(12 :: 13 :: Nil) :: Elem(14 :: Nil) :: Elem(15 :: 16 :: Nil) :: Nil)
        val source = Reader.toSource(reader)
        val r1 = source.readWithin(200 ms)
        r1 should be(Some(Data(12 :: 13 :: Nil)))
        val r2 = source.readWithin(200 ms)
        r2 should be(Some(Data(14 :: Nil)))
        val r3 = source.readWithin(200 ms)
        r3 should be(Some(Data(15 :: 16 :: Nil)))
        val e = source.readWithin(200 ms)
        e should be(Some(EndOfData))
        source.close.await
      }
      it_("should return three reads followed by EndOfData on a slow three-element reader") {
        val reader = TestReader(
          Elem(12 :: 13 :: Nil, Some(20 ms)) ::
          Elem(14 :: Nil, Some(500 ms)) ::
          Elem(15 :: 16 :: Nil, Some(10 ms)) :: Nil)
        val source = Reader.toSource(reader)
        val r1 = source.readWithin(1 s)
        r1 should be(Some(Data(12 :: 13 :: Nil)))
        val r2 = source.readWithin(1 s)
        r2 should be(Some(Data(14 :: Nil)))
        val r3 = source.readWithin(1 s)
        r3 should be(Some(Data(15 :: 16 :: Nil)))
        val e = source.readWithin(1 s)
        e should be(Some(EndOfData))
        source.close.await
      }
      it_("should timeout on read from a slow reader") {
        val reader = TestReader(Elem(12 :: Nil, Some(300 ms)) :: Nil)
        val source = Reader.toSource(reader)
        val r1 = source.readWithin(200 ms)
        r1 should be(None)
        source.close.await
      }
      it_("should not lose data on a read timeout") {
        val reader = TestReader(Elem(12 :: Nil, Some(300 ms)) :: Nil)
        val source = Reader.toSource(reader)
        val r1 = source.readWithin(200 ms)
        r1 should be(None)
        val r2 = source.readWithin(200 ms)
        r2 should be(Some(Data(12 :: Nil)))
        source.close.await
      }
      it_("should read all data even with read timeouts") {
        val reader = TestReader(
          Elem(12 :: 13 :: Nil, Some(100 ms)) ::
          Elem(14 :: Nil, Some(100 ms)) ::
          Elem(15 :: 16 :: Nil, Some(100 ms)) :: Nil)
        val source = Reader.toSource(reader)
        val r1t = source.readWithin(60 ms)
        r1t should be(None)
        val r1 = source.readWithin(60 ms)
        r1 should be(Some(Data(12 :: 13 :: Nil)))
        val r2t = source.readWithin(60 ms)
        r2t should be(None)
        val r2 = source.readWithin(60 ms)
        r2 should be(Some(Data(14 :: Nil)))
        val r3t = source.readWithin(60 ms)
        r3t should be(None)
        val r3 = source.readWithin(60 ms)
        r3 should be(Some(Data(15 :: 16 :: Nil)))
        val e = source.readWithin(60 ms)
        e should be(Some(EndOfData))
        source.close.await
      }
      it_("should return only one element if read(1) is called") {
        val reader = TestReader(
          Elem(10 :: 11 :: 12 :: 13 :: 14 :: Nil, Some(10 ms)) ::
          Elem(20 :: Nil, Some(10 ms)) :: Nil)
        val source = Reader.toSource(reader)
        val r1 = source.readWithin(100 ms, 1)
        r1 should be(Some(Data(10 :: Nil)))
        val r2 = source.readWithin(100 ms, 2)
        r2 should be(Some(Data(11 :: 12 :: Nil)))
        val r3 = source.read()
        source.close.await
      }
    }
  }

  case class Elem[A](value: Seq[A], delay: Option[Duration] = None)
  class TestReader[A](data: List[Elem[A]]) extends Reader[A] with StateServer {
    type State = List[Elem[A]]
    protected[this] override def init = data
    override def read(sourceFun: Read[A] => ReadResult @process) = cast(doRead(sourceFun, _))
    protected[this] def doRead(sourceFun: Read[A] => ReadResult @process, data: List[Elem[A]]):
        List[Elem[A]] @process = {
      val (read, rest) = data match {
        case Elem(d, delay) :: rest => 
          delay.foreach_cps(sleep(_))
          (Data(d), rest)
        case Nil => noop; (EndOfData, Nil)
      }
      val res = sourceFun(read)
      res match {
        case Next => 
          if (read.isData) 
            doRead(sourceFun, rest)
          else {
            noop
            rest
          }
        case End => noop; rest
      }
    }
    override def close = stopAndWait
  }
  object TestReader {
    def apply(data: List[Elem[Int]]) = {
      val s = new TestReader(data)
      Spawner.start(s, SpawnAsRequiredChild)
    }
  }
}
