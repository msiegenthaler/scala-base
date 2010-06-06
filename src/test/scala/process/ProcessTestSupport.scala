package ch.inventsoft.scalabase.process

import org.scalatest._
import matchers._
import ch.inventsoft.scalabase.process._
import ch.inventsoft.scalabase.time._

object ProcessTestSupport {  
  def spawnedTest(body: => Unit @processCps) = {
    val error = new scala.concurrent.SyncVar[Option[Throwable]]
    val p = spawn {
      val test = spawnChild(Monitored)(body)
      receive {
        case ProcessExit(`test`) => error.set(None)
        case ProcessCrash(`test`, reason) => error.set(Some(reason))
        case ProcessKill(`test`, by, reason) => error.set(Some(new RuntimeException("killed by "+by, reason)))
      }
    }
    error.get.foreach(throw _)
  }
  
  def sleep(time: Duration) = {
    receiveWithin(time) { case Timeout => () }
  }
}

trait ProcessSpec extends Spec {
  protected[this] def it_(name: String)(body: => Unit @processCps): Unit = it(name) {
    ProcessTestSupport.spawnedTest(body)
  }
  
  import ShouldMatchers._
  protected[this] def assertEquals(a: Any, b: Any) = {
    a should be(b)
  }
  
  protected[this] def sleep(forTime: Duration) = {
    receiveWithin(forTime) { case Timeout => () }
  }
}
