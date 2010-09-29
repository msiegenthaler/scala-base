package ch.inventsoft.scalabase
package io

import org.scalatest._
import matchers._
import java.io._
import process._
import oip._
import time._


class CommunicationPortSpec extends ProcessSpec with ShouldMatchers {

  private class TestPortContainer {
    val deviceInput = new PipedInputStream
    val deviceOutput = new PipedOutputStream
    val portInput = new PipedInputStream(deviceOutput)
    val portOutput = new PipedOutputStream(deviceInput)
    
    def readAsDevice(byteCount: Int, delay: Duration = (200 ms)) = {
      sleep(delay)
      val buffer = new Array[Byte](byteCount)
      def readIt(pos: Int): List[Byte] = {
        if (pos == byteCount) buffer.toList
        else deviceInput.read(buffer, pos, byteCount-pos) match {
          case -1 => buffer.take(pos).toList
          case 0 => readIt(pos)
          case count => readIt(pos+count)
        }
      }
      readIt(0)
    }
    def sendAsDevice(data: List[Byte], delay: Duration = (200 ms)) = {
      deviceOutput.write(data.toArray)
      deviceOutput.flush
      sleep(delay)
    }
    
    var _port: CommunicationPort[Byte,Byte] = null
    def port = {
      if (_port == null) throw new IllegalStateException
      else _port
    }
    def start: Unit @process = {
      _port = {
        case class SourceSink(source: Source[Byte], sink: Sink[Byte])
        CommunicationPort[Byte,Byte,SourceSink](
          open = { () =>
            val source = InputStreamSource(portInput)
            val sink = OutputStreamSink(portOutput)
            SourceSink(source, sink)
          },
          close = { (ss: SourceSink) =>
            val s1 = ss.sink.close
            val s2 = ss.source.close
            s1.await
            s2.await
          },
          as = SpawnAsRequiredChild
        ).receiveWithin(1 s)
      }
    }
    
    def close = {
      port.close
      deviceInput.close
      deviceOutput.close
    }
  }
  private object TestPortContainer {
    def apply() = {
      val o = new TestPortContainer
      o.start
      o
    }
  }
  implicit def intlist2ByteList(list: List[Int]) = list.map(_.toByte)


  describe("CommunicationPort based on Streams") {
    it_("should be possible to send data to the device") {
      val container = TestPortContainer()
      val port = container.port
      port.write(1 :: 2 :: 3 :: Nil).await
      assertEquals(container.readAsDevice(3), 1 :: 2 :: 3 :: Nil)
      container.close
    }
    it_("should be possible to send data to the device with delay at start") {
      val container = TestPortContainer()
      sleep(500 ms)
      val port = container.port
      port.write(1 :: 2 :: 3 :: Nil).await
      assertEquals(container.readAsDevice(3), 1 :: 2 :: 3 :: Nil)
      container.close
    }
    it_("should be possible to send multiple data fragments to the device") {
      val container = TestPortContainer()
      val port = container.port
      port.write(1 :: 2 :: Nil).await
      port.write(1 :: 3 :: Nil).await
      port.write(1 :: 4 :: Nil).await
      port.write(5 :: Nil).await
      assertEquals(container.readAsDevice(7), 1 :: 2 :: 1 :: 3 :: 1 :: 4 :: 5 :: Nil)
      container.close
    }
    it_("should be possible to send multiple data fragments to the device using cast (await on last)") {
      val container = TestPortContainer()
      val port = container.port
      port.writeCast(1 :: 2 :: Nil)
      port.writeCast(1 :: 3 :: Nil)
      port.writeCast(1 :: 4 :: Nil)
      port.write(5 :: Nil).await
      assertEquals(container.readAsDevice(7), 1 :: 2 :: 1 :: 3 :: 1 :: 4 :: 5 :: Nil)
      container.close
    }
    it_("should be possible to send multiple data fragments to the device using cast (sleep)") {
      val container = TestPortContainer()
      val port = container.port
      port.writeCast(1 :: 2 :: Nil)
      port.writeCast(1 :: 3 :: Nil)
      port.writeCast(1 :: 4 :: Nil)
      port.writeCast(5 :: Nil)
      receiveWithin(200 ms) { case Timeout => }
      assertEquals(container.readAsDevice(7), 1 :: 2 :: 1 :: 3 :: 1 :: 4 :: 5 :: Nil)
      container.close
    }
    it_("should be possible to send many data fragments to the device") {
      val container = TestPortContainer()
      val port = container.port
      val count = 100
      (1 to count).foreach_cps { i =>
        port.write(1 :: 2 :: 3 :: 4 :: Nil).await
      }
      container.readAsDevice(count * 4, 2 s)
      container.close
    }
    it_("should be possible to read available data from the device") {
      val container = TestPortContainer()
      val port = container.port
      val data = 1 :: 2 :: 3 :: Nil map(_.toByte)
      container.sendAsDevice(data)
      val r = port.read.receive
      r should be(Data(data))
      container.close
    }
    it_("should be possible to read all the data from the device at once") {
      val container = TestPortContainer()
      val port = container.port
      val d1 = 1 :: 2 :: 3 :: Nil map(_.toByte)
      val d2 = 10 :: 20 :: 30 :: Nil map(_.toByte)
      val d3 = 50 :: Nil map(_.toByte)
      container.sendAsDevice(d1)
      container.sendAsDevice(d2)
      container.sendAsDevice(d3)
      sleep(200 ms)
      val r = port.read.receive
      r should be(Data(d1 ::: d2 ::: d3))
      container.close
    }
    it_("should close the port on a close") {
      val container = TestPortContainer()
      val port = container.port
      container.sendAsDevice(1 :: 2 :: 3 :: Nil map(_.toByte))
      port.write(4 :: 5 :: 6 :: Nil).await
      port.close.await
      val rs = port.read.receiveOption(200 ms)
      rs should be(None)
      val ws = port.write(1 :: Nil).receiveOption(200 ms)
      ws should be(None)
      val cs = port.close.receiveOption(200 ms)
      cs should be(None)
    }
  }
}
