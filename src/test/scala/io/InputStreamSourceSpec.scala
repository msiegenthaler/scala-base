package ch.inventsoft.scalabase
package io

import org.scalatest._
import matchers._
import java.io._
import oip._
import process._
import time._


class InputStreamSourceSpec extends ProcessSpec with ShouldMatchers {

  describe("InputStreamSource") {
    describe("empty stream") {
      it_("should return EndOfData if reading from empty stream") {
        val is = new ByteArrayInputStream(Array[Byte]())
        val source = InputStreamSource(is)
        val read = source.read.receiveOption(1 s)
        read should be(Some(EndOfData))
      }
      it_("should return EndOfData if one continues reading from empty stream") {
        val is = new ByteArrayInputStream(Array[Byte]())
        val source = InputStreamSource(is)
        val read1 = source.read.receiveOption(1 s)
        read1 should be(Some(EndOfData))
        val read2 = source.read.receiveOption(1 s)
        read2 should be(Some(EndOfData))
        val read3 = source.read.receiveOption(1 s)
        read3 should be(Some(EndOfData))
      }
      it_("should successfully close") {
        val is = new ByteArrayInputStream(Array[Byte]()) with CloseTracked
        val source = InputStreamSource(is)
        val read = source.read.receiveOption(1 s)
        read should be(Some(EndOfData))

        is.closed should be(false)
 
        val cr = source.close.receiveOption(1 s)
        cr should be(Some(()))
        is.closed should be(true)
      }
    }
    describe("stream smaller than buffer size") {
      val buffer = "Hello Mario!".getBytes("UTF-8")
      def mkis = new ByteArrayInputStream(buffer) with CloseTracked
      def mksource = InputStreamSource(mkis, 512)
      def mksource_(is: InputStream) = InputStreamSource(is, 512)
      it_("should return all the data at once") {
        val source = mksource
        val read1 = source.read.receiveOption(1 s)
        read1 match {
          case Some(Data(data)) =>
            data.toArray should be(buffer)
          case other => fail(""+other)
        }
        noop
      }
      it_("should return EndOfData after everything is read") {
        val source = mksource
        val read1 = source.read.receiveOption(1 s)
        read1 match {
          case Some(Data(data)) =>
            data.toArray should be(buffer)
          case other => fail(""+other)
        }
        val read2 = source.read.receiveOption(1 s)
        read2 should be(Some(EndOfData))
      }
      it_("should return always EndOfData after everything is read") {
        val source = mksource
        source.read.receiveOption(1 s)
        val read2 = source.read.receiveOption(1 s)
        read2 should be(Some(EndOfData))
        val read3 = source.read.receiveOption(1 s)
        read3 should be(Some(EndOfData))
      }
      it_("should successfully close nothing is read") {
        val is = mkis
        val source = mksource_(is)
        is.closed should be(false)
        val cr = source.close.receiveOption(1 s)
        cr should be(Some(()))
        is.closed should be(true)
      }
      it_("should successfully close a read source") {
        val is = mkis
        val source = mksource_(is)
        val read = source.read.receiveOption(1 s)
        is.closed should be(false)
        val cr = source.close.receiveOption(1 s)
        cr should be(Some(()))
        is.closed should be(true)
      }
      it_("should successfully close a completly read source") {
        val is = mkis
        val source = mksource_(is)
        source.read.receiveOption(1 s)
        val r = source.read.receiveOption(1 s)
        r should be(Some(EndOfData))
        is.closed should be(false)
        val cr = source.close.receiveOption(1 s)
        cr should be(Some(()))
        println("...asas "+is.closed)
        is.closed should be(true)
      }
    }

    describe("stream exactly same size as buffer") {
      val buffer = "Hello Mario!".getBytes("UTF-8")
      def mkis = new ByteArrayInputStream(buffer) with CloseTracked
      def mksource = InputStreamSource(mkis, 12)
      def mksource_(is: InputStream) = InputStreamSource(is, 12)
      it_("should return all the data at once") {
        val source = mksource
        val read1 = source.read.receiveOption(1 s)
        read1 match {
          case Some(Data(data)) =>
            data.toArray should be(buffer)
          case other => fail(""+other)
        }
        noop
      }
      it_("should return EndOfData after everything is read") {
        val source = mksource
        val read1 = source.read.receiveOption(1 s)
        read1 match {
          case Some(Data(data)) =>
            data.toArray should be(buffer)
          case other => fail(""+other)
        }
        val read2 = source.read.receiveOption(1 s)
        read2 should be(Some(EndOfData))
      }
      it_("should return always EndOfData after everything is read") {
        val source = mksource
        source.read.receiveOption(1 s)
        val read2 = source.read.receiveOption(1 s)
        read2 should be(Some(EndOfData))
        val read3 = source.read.receiveOption(1 s)
        read3 should be(Some(EndOfData))
      }
      it_("should successfully close nothing is read") {
        val is = mkis
        val source = mksource_(is)
        is.closed should be(false)
        val cr = source.close.receiveOption(1 s)
        cr should be(Some(()))
        is.closed should be(true)
      }
      it_("should successfully close a read source") {
        val is = mkis
        val source = mksource_(is)
        val read = source.read.receiveOption(1 s)
        is.closed should be(false)
        val cr = source.close.receiveOption(1 s)
        cr should be(Some(()))
        is.closed should be(true)
      }
      it_("should successfully close a completly read source") {
        val is = mkis
        val source = mksource_(is)
        source.read.receiveOption(1 s)
        val r = source.read.receiveOption(1 s)
        r should be(Some(EndOfData))
        is.closed should be(false)
        val cr = source.close.receiveOption(1 s)
        cr should be(Some(()))
        is.closed should be(true)
      }
    }

    describe("stream of bigger size than buffer") {
      val buffer = "Hello Mario!".getBytes("UTF-8")
      def mkis = new ByteArrayInputStream(buffer) with CloseTracked
      def mksource = InputStreamSource(mkis, 5)
      def mksource_(is: InputStream) = InputStreamSource(is, 5)
      def readAndCheckString(source: Source[Byte], should: String) = {
        val bytes = should.getBytes("UTF-8")
        readAndCheck(source, bytes)
      }
      def readAndCheck(source: Source[Byte], should: Seq[Byte]) = {
        val read = source.read.receiveOption(1 s)
        read match {
          case Some(Data(data)) =>
            data.toArray should be(should.toArray)
          case other => fail(""+other)
        }
      }
      def readShouldEnd(source: Source[Byte]) = {
        val read = source.read.receiveOption(1 s)
        read should be(Some(EndOfData))
      }
      it_("should return the first fragment of data on the first call") {
        val source = mksource
        readAndCheckString(source, "Hello")
      }
      it_("should return the 3 fragments of data") {
        val source = mksource
        readAndCheckString(source, "Hello")
        readAndCheckString(source, " Mari")
        readAndCheckString(source, "o!")
      }
      it_("should return EndOfData after all three fragments are read") {
        val source = mksource
        readAndCheckString(source, "Hello")
        readAndCheckString(source, " Mari")
        readAndCheckString(source, "o!")
        readShouldEnd(source)
      }
      it_("should successfully close nothing is read") {
        val is = mkis
        val source = mksource_(is)
        is.closed should be(false)
        val cr = source.close.receiveOption(1 s)
        cr should be(Some(()))
        is.closed should be(true)
      }
      it_("should successfully close a read source") {
        val is = mkis
        val source = mksource_(is)
        readAndCheckString(source, "Hello")
        is.closed should be(false)
        val cr = source.close.receiveOption(1 s)
        cr should be(Some(()))
        is.closed should be(true)
      }
      it_("should successfully close a completly read source") {
        val is = mkis
        val source = mksource_(is)
        readAndCheckString(source, "Hello")
        readAndCheckString(source, " Mari")
        readAndCheckString(source, "o!")
        readShouldEnd(source)
        is.closed should be(false)
        val cr = source.close.receiveOption(1 s)
        cr should be(Some(()))
        is.closed should be(true)
      }
    }
    describe("huge source") {
      class RandomInputStream extends InputStream {
        private val random = new java.util.Random()
        override def read = random.nextInt(256)
        override def read(buffer: Array[Byte]) = {
          random.nextBytes(buffer)
          buffer.length
        }
        override def read(buffer: Array[Byte], offset: Int, length: Int) = {
          val tmp = new Array[Byte](length)
          random.nextBytes(tmp)
          Array.copy(tmp, 0, buffer, offset, length)
          length
        }
        override def skip(toSkip: Long) = toSkip
        override def available = Int.MaxValue
        override def close = ()
      }

      it_("should be possible to read 100Mb") {
        val is = new RandomInputStream
        val source = InputStreamSource(is, 1024)
        
        def readit(left: Int): Unit @process = {
          if (left > 0) {
            val read = source.read.receiveWithin(1 s)
            read match {
              case Data(data) =>
                data.size should be(1024)
                noop
              case other =>
                fail(""+other)
            }
            readit(left - 1)
          } else ()
        }

        readit(100000) // 100'000 times 1Kb
      }
    }
    describe("medium long") {
      val string = "The foxy fox jumps the bandwagon in order to get on board the train and have a lot of fun, but then he looses its temper and bites the goose who was piloting the locomotive across the big blue sea"
      val bytes = string.getBytes("UTF-8")
      def readAll(source: Source[Byte], soFar: Seq[Byte] = Nil): Seq[Byte] @process = {
        val read = source.read.receiveWithin(1 s)
        read match {
          case Data(data) =>
            val nd = soFar ++ data
            readAll(source, nd)
          case EndOfData => 
            noop
            soFar
        }
      }
      it_("should read a string of bytes no matter the buffer size") {
        (1 to 1000).foreach_cps { bufferSize =>
          val is = new ByteArrayInputStream(bytes)
          val source = InputStreamSource(is, bufferSize)
          val data = readAll(source)
          data.toArray should be(bytes)
        }
      }
    }
  }

  trait CloseTracked extends InputStream {
    private val _closed = new java.util.concurrent.atomic.AtomicBoolean
    override def close(): Unit = {
      _closed.set(true)
      super.close
    }
    def closed = _closed.get
  }
}
