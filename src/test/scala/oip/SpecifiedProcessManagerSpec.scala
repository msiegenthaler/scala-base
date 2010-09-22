package ch.inventsoft.scalabase
package oip

import scala.concurrent._
import org.scalatest._
import matchers._
import process._
import oip._
import time._
import Messages._


class SpecifiedProcessManagerSpec extends ProcessSpec with ShouldMatchers {

  describe("SpecifiedProcessManager") {
    describe("basic") {
      it_("should return the spawned process as managedProcess") {
        val a = new SyncVar[Process]
        val spec = ProcessSpecification.temporary.unnamed.killForShutdown.as {
          a.set(self)
        }
        val parent = TestSPMP()
        val manager = SpecifiedProcessManager.startAsChild(spec, parent)
    
        val p = manager.managedProcess
        val ap = a.get(1000)
        p should be(ap.get)
        manager.stop(true)
        parent.stop
      }
      it_("should be stopable nice within timeout") {
        val a = new SyncVar[String]
        val b = new SyncVar[String]
        val spec = ProcessSpecification.temporary.unnamed.withShutdownTimeout(1 s).as {
          a.set("My value")
          receive { 
            case "never" => b.set("bad")
            case Terminate => b.set("done")
            case other => b.set("funny")
          }
        }
        val parent = TestSPMP()
        val m = SpecifiedProcessManager.startAsChild(spec, parent)
          
        get(a) should be("My value")
        b.get(200) should be(None)
        assertEquals(parent.value_, Nil)
          
        assertEquals(receiveWithin(1 s) { m.stop(true) },())
        get(b) should be("done")
      }   
      it_("should be stopable by kill") {
        val a = new SyncVar[String]
        val b = new SyncVar[String]
        val spec = ProcessSpecification.temporary.unnamed.killForShutdown.as {
          a.set("My value")
          receive { 
            case "never" => b.set("bad")
            case Terminate => b.set("done (bad)")
            case other => b.set("funny")
          }
        }
        val parent = TestSPMP()
        val m = SpecifiedProcessManager.startAsChild(spec, parent)
          
        get(a) should be("My value")
        b.get(200) should be(None)
        assertEquals(parent.value_, Nil)
          
        assertEquals(receiveWithin(1 s) { m.stop(true) }, ())
        b.get(200) should be(None)
      }   
      it_("should be stopable by kill because of timeout") {
        val a = new SyncVar[String]
        val b = new SyncVar[String]
        val spec = ProcessSpecification.temporary.unnamed.withShutdownTimeout(100 ms).as {
          a.set("My value")
          receive { 
            case "never" => b.set("bad")
          }
        }
        val parent = TestSPMP()
        val m = SpecifiedProcessManager.startAsChild(spec, parent)
          
        get(a) should be("My value")
        b.get(200) should be(None)
        assertEquals(parent.value_, Nil)
          
        assertEquals(receiveWithin(1 s) { m.stop(true) }, ())
        b.get(200) should be(None)
      }
    }    
    describe("temporary") {
      it_("should start the process on creation") {
        val a = new SyncVar[String]
        val spec = ProcessSpecification.temporary.unnamed.killForShutdown.as {
          a.set("My value")
        }
        val parent = TestSPMP()
        val m = SpecifiedProcessManager.startAsChild(spec, parent)
    
        get(a) should be("My value")
        parent.stop
      }
      it_("should not request a restart on normal termination") {
        val a = new SyncVar[String]
        val spec = ProcessSpecification.temporary.unnamed.killForShutdown.as {
          a.set("My value")
        }
        val parent = TestSPMP()
        val m = SpecifiedProcessManager.startAsChild(spec, parent)
    
        assertParentState(parent, (m,false) :: Nil)
      }
      it_("should not request a restart on a crash") {
        val a = new SyncVar[String]
        val spec = ProcessSpecification.temporary.unnamed.killForShutdown.as {
          a.set("My value")
          throw new RuntimeException("crash")
        }
        val parent = TestSPMP()
        val m = SpecifiedProcessManager.startAsChild(spec, parent)
    
        get(a) should be("My value")
        assertParentState(parent, (m,false) :: Nil)
      }
    }
    describe("permanent") {
      it_("should start the process on creation") {
        val a = new SyncVar[String]
        val spec = ProcessSpecification.permanent.unnamed.killForShutdown.as {
          a.set("My value")
        }
        val parent = TestSPMP()
        val m = SpecifiedProcessManager.startAsChild(spec, parent)
    
        get(a) should be("My value")
        parent.stop
      }
      it_("should request a restart on normal termination") {
        val a = new SyncVar[String]
        val spec = ProcessSpecification.permanent.unnamed.killForShutdown.as {
          a.set("My value")
        }
        val parent = TestSPMP()
        val m = SpecifiedProcessManager.startAsChild(spec, parent)
    
        assertParentState(parent, (m,true) :: Nil)
      }
      it_("should request a restart on a crash") {
        val a = new SyncVar[String]
        val spec = ProcessSpecification.permanent.unnamed.killForShutdown.as {
          a.set("My value")
          throw new RuntimeException("crash")
        }
        val parent = TestSPMP()
        val m = SpecifiedProcessManager.startAsChild(spec, parent)
    
        get(a) should be("My value")
        assertParentState(parent, (m,true) :: Nil)
      }
    }
    describe("transient") {
      it_("should start the process on creation") {
        val a = new SyncVar[String]
        val spec = ProcessSpecification.transient.unnamed.killForShutdown.as {
          a.set("My value")
        }
        val parent = TestSPMP()
        val m = SpecifiedProcessManager.startAsChild(spec, parent)
    
        get(a) should be("My value")
        parent.stop
      }
      it_("should not request a restart on normal exit") {
        val a = new SyncVar[String]
        val spec = ProcessSpecification.transient.unnamed.killForShutdown.as {
          a.set("My value")
        }
        val parent = TestSPMP()
        val m = SpecifiedProcessManager.startAsChild(spec, parent)
    
        assertParentState(parent, (m,false) :: Nil)
      }
      it_("should request a restart on a crash") {
        val a = new SyncVar[String]
        val spec = ProcessSpecification.transient.unnamed.killForShutdown.as {
          a.set("My value")
          throw new RuntimeException("crash")
        }
        val parent = TestSPMP()
        val m = SpecifiedProcessManager.startAsChild(spec, parent)
    
        get(a) should be("My value")
        assertParentState(parent, (m,true) :: Nil)
      }
    }
  }
  
  def assertParentState(parent: TestSPMP, s: List[(SpecifiedProcessManager,Boolean)]) = {
    sleep(300 ms)
    val p = parent.value_
    p should be(s)
    parent.stop
  }
  
  class TestSPMP extends SpecifiedProcessManagerParent with StateServer {
    type State = List[(SpecifiedProcessManager,Boolean)]
    override protected[this] def init = Nil
    override def processStopped(manager: SpecifiedProcessManager, requestsRestart: Boolean) = cast { state =>
      (manager,requestsRestart) :: state
    }
    def value = get { state =>
      state
    }
    def value_ = receiveWithin(1 s) { value }
    override def stop = super.stop
  }
  object TestSPMP {
    def apply() = {
      val s = new TestSPMP
      s.start(SpawnAsRequiredChild)
      s
    }
  }
  
  def get[T](value: SyncVar[T]): T = {
    value.get(3000) match {
      case Some(v) => v
      case None => fail("get was None")
    }
  }
}
