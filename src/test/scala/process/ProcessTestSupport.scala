package ch.inventsoft.scalabase
package process

import org.scalatest._
import matchers._
import time._


trait ProcessSpec extends Spec {
  protected def it_(name: String)(body: => Unit @process): Unit = it(name) {
    spawnAndBlock(body)
  }
  
  import ShouldMatchers._
  protected def assertEquals(a: Any, b: Any) = {
    a should be(b)
  }
  
  protected def sleep(forTime: Duration) = {
    receiveWithin(forTime) { case Timeout => () }
  }
}
