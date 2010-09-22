package ch.inventsoft.scalabase
package communicationport

import org.scalatest._
import matchers._
import java.io._
import oip._
import process._
import time._


class IOStreamPortSpec extends ProcessSpec with ShouldMatchers {

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
    
    private class MyIOPort extends IOStreamPort[Unit] {
      protected[this] override def openStreams = {
        (portInput, portOutput, ())
      }
      override def start(as: SpawnStrategy) = super.start(as)
    }
    private val _port: MyIOPort = new MyIOPort
    def port: CommunicationPort = _port
    def start: Unit @processCps = {
      _port.start(SpawnAsRequiredChild)
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
  implicit def intlist2ByteList(list: List[Byte]) = list.map(_.toByte)
  implicit def intlist2Byteiterator(list: List[Int]) = list.map(_.toByte).iterator


  describe("InputOutputStreamBasedPort") {
    it_("should be possible to send data to the device") {
      val container = TestPortContainer()
      val port = container.port
      port.send(1 :: 2 :: 3 :: Nil)
      assertEquals(container.readAsDevice(3), 1 :: 2 :: 3 :: Nil)
      container.close
    }
    it_("should be possible to send data to the device with delay at start") {
      val container = TestPortContainer()
      sleep(500 ms)
      val port = container.port
      port.send(1 :: 2 :: 3 :: Nil)
      assertEquals(container.readAsDevice(3), 1 :: 2 :: 3 :: Nil)
      container.close
    }
    it_("should be possible to send multiple data fragments to the device") {
      val container = TestPortContainer()
      val port = container.port
      port.send(1 :: 2 :: Nil)
      port.send(1 :: 3 :: Nil)
      port.send(1 :: 4 :: Nil)
      port.send(5 :: Nil)
      assertEquals(container.readAsDevice(7), 1 :: 2 :: 1 :: 3 :: 1 :: 4 :: 5 :: Nil)
      container.close
    }
    it_("should be possible to send many data fragments to the device") {
      val container = TestPortContainer()
      val port = container.port
      val count = 100
      (1 to count).foreach { i =>
        port.send(1 :: 2 :: 3 :: 4 :: Nil)
      }
      container.readAsDevice(count * 4, 2 s)
      container.close
    }
    it_("should be possible to read available data from the device") {
      val container = TestPortContainer()
      val port = container.port
      val data = 1 :: 2 :: 3 :: Nil map(_.toByte)
      container.sendAsDevice(data)
      assertEquals(receive { port.receive.option }, Some(data))
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
      assertEquals(receive { port.receive.option }, Some(d1 ::: d2 ::: d3)) 
      container.close
    }
    it_("should be possible to redirect the receive data to another process") {
      val container = TestPortContainer()
      val port = container.port
      port.redirectIncomingTo(Some(self))
      sleep(200 ms)
      
      val d1 = 1 :: 2 :: 3 :: Nil map(_.toByte)
      container.sendAsDevice(d1)
      receiveWithin(1 s) { 
        case DataReceived(`port`, data) => assertEquals(data, d1)
      }
      
      val d2 = 10 :: 20 :: 30 :: Nil map(_.toByte)
      container.sendAsDevice(d2)
      receiveWithin(1 s) { 
        case DataReceived(`port`, data) => assertEquals(data, d2)
      }

      val d3 = 50 :: Nil map(_.toByte)
      container.sendAsDevice(d3)
      receiveWithin(1 s) { 
        case DataReceived(`port`, data) => assertEquals(data, d3)
      }
      container.close
    }
    it_("should be possible to redirect the already received data (in buffer) to another process") {
      val container = TestPortContainer()
      val port = container.port
      
      val d1 = 1 :: 2 :: 3 :: Nil map(_.toByte)
      val d2 = 4 :: 5 :: 6 :: Nil map(_.toByte)
      container.sendAsDevice(d1)
      container.sendAsDevice(d2)

      port.redirectIncomingTo(Some(self))
      receiveWithin(1 s) { 
        case DataReceived(`port`, data) => assertEquals(data, d1 ::: d2)
      }
      container.close
    }
  }
}
