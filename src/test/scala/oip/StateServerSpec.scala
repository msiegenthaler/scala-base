package ch.inventsoft.scalabase
package oip

import scala.concurrent._
import org.scalatest._
import matchers._
import process._
import oip._
import time._
import Messages._


class StateServerSpec extends ProcessSpec with ShouldMatchers {
  object PeopleStateServer {
    def apply(as: SpawnStrategy = SpawnAsRequiredChild): PeopleStateServer @process = 
      Spawner.start(new PeopleStateServer, as)
  }
  class PeopleStateServer protected() extends StateServer {
    type State = PeopleState
    protected[this] override def init = PeopleState(0, Nil)
    
    def addPerson(name: String) = cast { (state) =>
      tick
      PeopleState(state.counter + 1, name :: state.people)
    }
    def addPersonFast(name: String) = cast { (state) =>
      PeopleState(state.counter + 1, name :: state.people)
    }
    def count = call { state =>
      val c = state.counter
      tick
      (c, state)
    }
    def count2 = get(_.counter)
    def people = call { state =>
      tick
      (state.people, state)
    }
    def people2 = get(_.people)
    def kill = stop
    def die(of: Exception) = cast { state => throw of }
    def countAndKill = {
      val r = get(_.counter)
      stop
      r
    }
    
    def countDefered = async { state =>
      sleep(1 s)
      state.counter
    }
    def countDefered2 = async { state =>
      state.counter
    }
    def getProcess = process

    private def tick = sleep(50 ms)
  }
  case class PeopleState(counter: Int, people: List[String])
  
  object ParentServer {
    def apply(as: SpawnStrategy = SpawnAsRequiredChild): ParentServer @process = 
      Spawner.start(new ParentServer, as)
  }
  class ParentServer protected() extends StateServer {
    type State = List[Process]
    protected[this] override def init = Nil
    protected[this] override def handler(state: State) = super.handler(state).orElse_cps {
      case end: ProcessEnd =>
        val s = state.filterNot(_ == end.process)
        Some(s)
    }
    def running = call { state =>
      (state, state)
    }
    def start(runFor: Duration) = cast { state =>
      val p = spawnChild(Monitored) { Thread.sleep(runFor.amountAs(Milliseconds)) }
      p :: state
    }
    def crashingStart(runFor: Duration) = cast { state =>
      val p = spawnChild(Monitored) {
        Thread.sleep(runFor.amountAs(Milliseconds))
        throw new RuntimeException("I crash")
      }
      p :: state
    }
  }
  
  describe("StateServer") {
    it_("should have an initial state") {
      val server = PeopleStateServer()
      val c = receiveWithin(1 s) { server.count }
      c should be(0)
    }
    it_("should be modifiable") {
      val server = PeopleStateServer()
      server.addPerson("Mario")
      val a = receiveWithin(1 s) { server.count }
      a should be(1)
      val b = receiveWithin(1 s) { server.people }
      b should be("Mario" :: Nil)
    }
    it_("should support gets") {
      val server = PeopleStateServer()
      val c1 = receiveWithin(1 s) { server.count2 }
      c1 should be(0)
      server.addPerson("Mario")
      val c2 = receiveWithin(1 s) { server.count2 }
      c2 should be(1)
      val r = receiveWithin(1 s) { server.people2 }
      r should be("Mario" :: Nil)
    }
    it_("should be threadsafe") {
      val server = PeopleStateServer()
      val t = self
      (1 to 10).foreach_cps(i => spawnChild(Required) {
        server.addPerson("Person "+i)
        t ! ()
      })
      (1 to 10).foreach_cps(_ => receive { case () => () })
      val c = receiveWithin(1 s)(server.count)
      c should be(10)
      val people = receiveWithin(1 s)(server.people)
      people.size should be(10)
      (1 to 10).foreach { i =>
        people.contains("Person "+i) should be(true)
      }
    }
    it_("should allow defered responses") {
      val server = PeopleStateServer()
      server.addPerson("Mario")
      server.addPerson("Claudia")
      val c = receiveWithin(2 s)(server.countDefered)
      c should be(2)
    }
    it_("should allow pseudo defered responses") {
      val server = PeopleStateServer()
      server.addPerson("Mario")
      server.addPerson("Claudia")
      val c = receiveWithin(500 ms)(server.countDefered2) 
      c should be(2)
    }
    it_("should allow defered responses with the original state preserved") {
      val server = PeopleStateServer()
      server.addPerson("Mario")
      server.addPerson("Claudia")
      val s = server.countDefered
      server.addPerson("Jack")
      val c = receiveWithin(500 ms) { server.count }
      c should be(3)
      val c2 = receiveWithin(2 s)(s)
      c2 should be(2)
    }
    
    it_("should be possible to terminate a server by cast") {
      val server = PeopleStateServer()
      spawnChild(Required) {
        server addPerson "Mario"
        val c = receiveWithin(1 s)(server.count)
        c should be(1)
        server.kill
        val c2 = receiveWithin(500 ms)(server.count.option)
        c2 should be(None)
        server addPerson "Claudia"
        val c3 = receiveWithin(500 ms)(server.count.option)
        c3 should be(None)
      }
      noop
    }
    it_("should be possible to terminate a server by call") {
      val server = PeopleStateServer()
      server addPerson "Mario"
      assertEquals(receiveWithin(1 s)(server.count), 1)
      assertEquals(receiveWithin(1 s)(server.countAndKill), 1)
      assertEquals(receiveWithin(1 s)(server.count.option), None)
      server addPerson "Claudia"
      assertEquals(receiveWithin(1 s)(server.count.option), None)
    }
    it_("should be possible to crash a server") {
      val server = PeopleStateServer(SpawnAsOwnProcess)
      server addPerson "Mario"
      assertEquals(receiveWithin(1 s)(server.count), 1)
      server.die(new RuntimeException("die hard"))
      assertEquals(receiveWithin(1 s)(server.count.option), None)
      server addPerson "Claudia"
      assertEquals(receiveWithin(1 s)(server.count.option), None)
    }
    
    it_("should support multiple calls") {
      val server = PeopleStateServer()
      (1 to 10).foreach_cps(i => server.addPersonFast("Person "+i))
      assertEquals(receiveWithin(1 s)(server.count), 10)
    }
    it_("should support many calls") {
      val server = PeopleStateServer()
      def add(limit: Int, index: Int = 0): Unit @process = {
        if (index < limit) {
          server.addPersonFast("Person "+index)
          add(limit, index+1)
        } else noop
      }
      add(10000)
      assertEquals(receiveWithin(10 s)(server.count), 10000)
    }
    
    it_("should be able to detect the termination of a subprocess") {
      val server = ParentServer()
      assertEquals(receiveWithin(1 s)(server.running), Nil)
      server.start(200 ms)
      assertEquals(receiveWithin(1 s)(server.running).length, 1)
      sleep(500 ms)
      assertEquals(receiveWithin(1 s)(server.running), Nil)
    }
    it_("should be able to detect crashes of subprocesses") {
      val server = ParentServer()
      val r1 = receiveWithin(1 s)(server.running) 
      r1 should be(Nil)
      server.crashingStart(200 ms)
      val r2 = receiveWithin(1 s)(server.running).length
      r2 should be(1)
      sleep(500 ms)
      val r3 = receiveWithin(1 s)(server.running)
      r3 should be(Nil)
    }
  
    class SendTerminatePeopleStateServer(parent: Process) extends PeopleStateServer {
      protected[this] override def termination(finalState: PeopleState) = {
        parent ! "Terminated"
      }
    }
    object SendTerminatePeopleStateServer {
      def apply(parent: Process, as: SpawnStrategy = SpawnAsRequiredChild) =
	Spawner.start(new SendTerminatePeopleStateServer(parent), as)
    }

    it_("should have a way to react to normal termination") {
      val server = SendTerminatePeopleStateServer(self, SpawnAsRequiredChild) 
      server addPerson "Claudia"
      server.kill
      receiveWithin(1 s) { 
        case "Terminated" => //ok
        case Timeout => fail
      }
    }
    it_("should not call terminatedNormally if crashed") {
      val server = SendTerminatePeopleStateServer(self, SpawnAsOwnProcess) 
      server addPerson "Claudia"
      server.die(new RuntimeException("oO"))
      receiveWithin(1 s) { 
        case "Terminated" => fail
        case Timeout => //ok
      }
    }

    it_("should not run into a NPE when the initializer is slow") {
      val p = new SyncVar[Process]
      val server = new PeopleStateServer() {
        protected[this] override def init = {
          p.set(process)
          sleep(500 ms)
          PeopleState(0, Nil)
        }
      }
      server.start(SpawnAsRequiredChild)
      server.getProcess should not be(null)
      server.addPerson("Mario")
      val r = receive { server.people2 }
      r should be("Mario" :: Nil)
      p.get(1000) should not be(null)
      server.kill
    }
    it_("should not run into a NPE with a slow initializer (a bit different)") {
      val p = new SyncVar[Process]
      val server = new PeopleStateServer() {
        protected[this] override def init = {
          sleep(500 ms)
          PeopleState(0, Nil)
        }
      }
      server.start(SpawnAsRequiredChild)
      server.getProcess should not be(null)
      server.kill
    }
    it_("should have the process available in initial state") {
      (1 to 1000).foreach_cps { i =>
        val p = new SyncVar[Process]
        val server = new PeopleStateServer() {
          protected[this] override def init = {
            val myProcess = process
            p.set(myProcess)
            PeopleState(0, Nil)
          }
        }
        server.start(SpawnAsRequiredChild)
        p.get(1000) should not be(null)
        server.kill
      }
    }
  }
}

