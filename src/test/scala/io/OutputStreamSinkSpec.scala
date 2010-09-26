package ch.inventsoft.scalabase
package io

import org.scalatest._
import matchers._
import java.io._
import oip._
import process._
import time._


class OutputStreamSinkSpec extends ProcessSpec with ShouldMatchers {

  describe("OutputStreamSink") {
    describe("empty stream") {
      it_("should not write anything to the output stream if write is never called") {
        val (sink,bos) = create
        sink.close.receive
        check(bos)(Nil)
      }
    }
    describe("one write") {
      it_("should write a single byte to the os if a single byte have been written to the sink") {
        val (sink, bos) = create
        sink.write(12).receive
        sink.close.receive
        check(bos)(12 :: Nil)
      }
      it_("should write 10 bytes to the os if 10 bytes have been written to the sink") {
        val data = data10
        val (sink,bos) = create
        sink.write(data).await
        sink.close.receive
        check(bos)(data)
      }
    }
    describe("one writeCast") {
      it_("should write a single byte to the os if a single byte have been written to the sink") {
        val (sink, bos) = create
        sink.writeCast(12)
        sink.close.await
        check(bos)(12 :: Nil)
      }
      it_("should write 10 bytes to the os if 10 bytes have been written to the sink") {
        val data = data10
        val (sink,bos) = create
        sink.writeCast(data)
        sink.close.await
        check(bos)(data)
      }
    }
    describe("multiple writes") {
      it_("should be possible to write a sequence of 10 bytes one by one") {
        val (sink,bos) = create
        data10.foreach_cps { d => sink.write(d).await }
        sink.close.await
        check(bos)(data10)
      }
      it_("should be possible to write 10 times 10 bytes") {
        val (sink,bos) = create
        (1 to 10).foreach_cps { _ => sink.write(data10).await }
        sink.close.await
        val should = (1 to 10).flatMap(_ => data10)
        check(bos)(should)
      }
    }
    describe("multiple writes cast") {
      it_("should be possible to write a sequence of 10 bytes one by one") {
        val (sink,bos) = create
        data10.foreach_cps { d => sink.writeCast(d); noop }
        sink.close.await
        check(bos)(data10)
      }
      it_("should be possible to write 10 times 10 bytes") {
        val (sink,bos) = create
        (1 to 10).foreach_cps { _ => sink.writeCast(data10); noop }
        sink.close.await
        val should = (1 to 10).flatMap(_ => data10)
        check(bos)(should)
      }
    }
    describe("large data") {
      class CountingOutputStream extends OutputStream {
        private val written = new java.util.concurrent.atomic.AtomicLong
        def countWritten: Long = written.get
        override def write(data: Int) = {
          written.incrementAndGet
        }
        override def write(data: Array[Byte]) = {
          written.addAndGet(data.size)
        }
      }
      it_("should be possible to write 1k at once") {
        val (sink,bos) = create
        sink.write(data1k).await
        sink.close.await
        check(bos)(data1k)
      }
      it_("should be possible to write more data in one write than memory available in multiple writes") {
        val out = new CountingOutputStream
        val sink = OutputStreamSink(out)
        val repeats = 1024 * 1024 // 1Gb
        (1 to repeats).foreach_cps(_ => sink.write(data1k).await )
        sink.close.await
        out.countWritten should be(1024L * repeats)
      }
      it_("should be possible to write a huge amount of data in one write") {
        val out = new CountingOutputStream
        val sink = OutputStreamSink(out)
        val count = 512 * 1024 // 512 Mb
        val toWrite = (1 to count).view.flatMap(_ => data1k)
        sink.write(toWrite).await
        sink.close.await
        out.countWritten should be(1024L * count)
      }
    }
  }

  val data10 = 1 :: 2 :: 3 :: 4 :: 5 :: 6 :: 7 :: 8 :: 9 :: 10 :: Nil map(_.toByte)
  val data1k = (1 to 1024).map(_ % 4).map(_.toByte).toArray

  def check(bos: ByteArrayOutputStream)(data: Seq[Byte]): Unit = {
    val is = bos.toByteArray
    val expected = data.toArray
    is should be(expected)
  }
  implicit def listToByteSeq(in: List[Int]): Seq[Byte] = {
    in.map(_.toByte)
  }
  implicit def intsToBytes(in: List[Int]) = in.map(_.toByte)
  implicit def intToByte(in: Int) = in.toByte

  def create = {
    val bos = new ByteArrayOutputStream
    val sink = OutputStreamSink(bos)
    (sink, bos)
  }
}
