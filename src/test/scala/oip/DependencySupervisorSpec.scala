package ch.inventsoft.scalabase.oip

import org.scalatest._
import matchers._
import ch.inventsoft.scalabase.process._
import scala.concurrent.SyncVar
import ch.inventsoft.scalabase.time._
import cps.CpsUtils._


class DependencySupervisorSpec extends ProcessSpec with ShouldMatchers {
  trait TestStateServer extends StateServer[Int] {
    def queryProcess = process
    def addAndGet = call { state => (state, state+1) }
    def kill = cast { state => throw new RuntimeException("kill me") }
    def stop = cast_ { state => None }
  }
  class SerialPort(portName: String) extends TestStateServer {
    protected[this] override def initialState = 0
    def queryPortName = get(state => portName)
  }
  object SerialPort {
    def apply(portName: String)(as: SpawnStrategy) = {
      val o = new SerialPort(portName)
      o.start(as)
      o
    }
  }
  class LowLevelXBee(serialPort: SerialPort) extends TestStateServer {
    protected[this] override def initialState = 0
    def querySerialPort = get(state => serialPort)
  }
  object LowLevelXBee {
    def apply(serialPort: SerialPort)(as: SpawnStrategy) = {
      val o = new LowLevelXBee(serialPort)
      o.start(as)
      o
    }
  }
  class Series1XBee(lowLevel: LowLevelXBee) extends TestStateServer {
    protected[this] override def initialState = 0
    def queryLowLevel = get(state => lowLevel)
  }
  object Series1XBee {
    def apply(lowLevel: LowLevelXBee)(spawn: SpawnStrategy) = {
      val o = new Series1XBee(lowLevel)
      o.start(spawn)
      o
    }
  }

  class TestSupervisor private(usbPort: String, answerTo: Process) extends DependencySupervisor {
    protected[this] val serial = permanent.shutdownTimeout(2 s) { _ =>
      SerialPort(usbPort)(Spawner)
    }
    protected[this] val lowLevel = permanent.shutdownTimeout(1 s) { _ =>
      LowLevelXBee(serial)(Spawner)
    }
    protected[this] val series1 = permanent.shutdownTimeout(1 s) { _ =>
      Series1XBee(lowLevel)(Spawner)
    }
    protected[this] val published = transient.shutdownTimeout(1 s) { spawn =>
      val msg = DependenciesStarted(serial.value, lowLevel.value, series1.value)
      spawn(answerTo ! msg)
    }

    def stop = process ! Terminate
    def getSerial = receiveWithin(1 s) { serial.getValue.option }
    def getLowLevel = receiveWithin(1 s) { lowLevel.getValue.option }
    def getSeries1 = receiveWithin(1 s) { series1.getValue.option }
  }
  object TestSupervisor {
    def apply(usbPort: String, answerTo: Process) = {
      val s = new TestSupervisor(usbPort, answerTo)
      s.start(SpawnAsRequiredChild)
      s
    }
  }
  case class DependenciesStarted(serial: SerialPort, lowLevel: LowLevelXBee, series1: Series1XBee)

  def initialize = {
    val supervisor = TestSupervisor("myPort", self)
    receiveWithin(10 s) {
      case DependenciesStarted(serial, lowLevel, series1) =>
        (supervisor, serial, lowLevel, series1)
      case Timeout =>
        fail("not started within Timeout")
    }
  }
  
  describe("DependencySupervisor") {
    describe("startup") {
      it_("should start up all the dependencies") {
        val (supervisor, serial, lowlevel, series1) = initialize
        serial should not be(null)
        lowlevel should not be(null)
        series1 should not be(null)
        assertEquals(receiveWithin(1 s) { series1.addAndGet.option }, Some(0))
        assertEquals(receiveWithin(1 s) { series1.addAndGet.option }, Some(1))
        supervisor.stop
      }
      it_("should actually inject the dependencies into the others") {
        val (supervisor, serial, lowlevel, series1) = initialize
        assertEquals(receiveWithin(1 s){ lowlevel.querySerialPort.option }, Some(serial))
        assertEquals(receiveWithin(1 s){ series1.queryLowLevel.option }, Some(lowlevel))
        supervisor.stop
      }
    }
    
    describe("shutdown") {
      it_("should stop the dependencies when we stop the supervisor") {
        val (supervisor, serial, lowlevel, series1) = initialize
        assertEquals(receiveWithin(1 s){ lowlevel.querySerialPort.option }, Some(serial))
        supervisor.stop
        sleep(500 ms)
        assertEquals(receiveWithin(1 s){ serial.addAndGet.option }, None) 
        assertEquals(receiveWithin(1 s){ lowlevel.addAndGet.option }, None) 
        assertEquals(receiveWithin(1 s){ series1.addAndGet.option }, None) 
      }
    }
    describe("all-for-one") {
      it_("should restart all the dependencies if the first fails") {
        val (supervisor, serial_1, lowlevel_1, series1_1) = initialize
        assertEquals(receiveWithin(1 s) { serial_1.addAndGet.option }, Some(0))
        assertEquals(receiveWithin(1 s) { serial_1.addAndGet.option }, Some(1))
        
        serial_1.kill

        val (serial_2, lowlevel_2, series1_2) = receiveWithin(10 s) {
          case DependenciesStarted(serial, lowLevel, series1) => (serial, lowLevel, series1)
          case Timeout => fail("not restarted")
        }
        serial_2 should not be(null)
        serial_2 should not be(serial_1)
        serial_2.queryProcess should not be(serial_1.queryProcess)
        lowlevel_2 should not be(null)
        lowlevel_2 should not be(lowlevel_1)
        lowlevel_2.queryProcess should not be(lowlevel_1.queryProcess)
        series1_2 should not be(null)
        series1_2 should not be(series1_1)
        series1_2.queryProcess should not be(series1_1.queryProcess)
        assertEquals(receiveWithin(1 s) { serial_2.addAndGet.option }, Some(0))
        assertEquals(receiveWithin(1 s) { serial_2.addAndGet.option }, Some(1))
        
        supervisor.stop
      }
      it_("should restart all the dependencies if the second fails") {
        val (supervisor, serial_1, lowlevel_1, series1_1) = initialize
        assertEquals(receiveWithin(1 s) { serial_1.addAndGet.option }, Some(0))
        assertEquals(receiveWithin(1 s) { serial_1.addAndGet.option }, Some(1))
        
        lowlevel_1.kill

        val (serial_2, lowlevel_2, series1_2) = receiveWithin(10 s) {
          case DependenciesStarted(serial, lowLevel, series1) => (serial, lowLevel, series1)
          case Timeout => fail("not restarted")
        }
        serial_2 should not be(null)
        serial_2 should not be(serial_1)
        serial_2.queryProcess should not be(serial_1.queryProcess)
        lowlevel_2 should not be(null)
        lowlevel_2 should not be(lowlevel_1)
        lowlevel_2.queryProcess should not be(lowlevel_1.queryProcess)
        series1_2 should not be(null)
        series1_2 should not be(series1_1)
        series1_2.queryProcess should not be(series1_1.queryProcess)
        assertEquals(receiveWithin(1 s) { serial_2.addAndGet.option }, Some(0))
        assertEquals(receiveWithin(1 s) { serial_2.addAndGet.option }, Some(1))
        
        supervisor.stop
      }
      it_("should restart all the dependencies if the third fails") {
        val (supervisor, serial_1, lowlevel_1, series1_1) = initialize
        assertEquals(receiveWithin(1 s) { serial_1.addAndGet.option }, Some(0))
        assertEquals(receiveWithin(1 s) { serial_1.addAndGet.option }, Some(1))
        
        series1_1.kill

        val (serial_2, lowlevel_2, series1_2) = receiveWithin(10 s) {
          case DependenciesStarted(serial, lowLevel, series1) => (serial, lowLevel, series1)
          case Timeout => fail("not restarted")
        }
        serial_2 should not be(null)
        serial_2 should not be(serial_1)
        serial_2.queryProcess should not be(serial_1.queryProcess)
        lowlevel_2 should not be(null)
        lowlevel_2 should not be(lowlevel_1)
        lowlevel_2.queryProcess should not be(lowlevel_1.queryProcess)
        series1_2 should not be(null)
        series1_2 should not be(series1_1)
        series1_2.queryProcess should not be(series1_1.queryProcess)
        assertEquals(receiveWithin(1 s) { serial_2.addAndGet.option }, Some(0))
        assertEquals(receiveWithin(1 s) { serial_2.addAndGet.option }, Some(1))
        
        supervisor.stop
      }
    }
  }
}




