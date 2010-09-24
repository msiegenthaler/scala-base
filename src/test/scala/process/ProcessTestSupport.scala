package ch.inventsoft.scalabase
package process

import org.scalatest._
import matchers._
import time._


object ProcessTestSupport {  
  def spawnedTest(body: => Unit @processCps) = {
    spawnAndBlock {
      body
    }
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
