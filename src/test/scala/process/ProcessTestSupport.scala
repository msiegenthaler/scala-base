package ch.inventsoft.scalabase
package process

import org.scalatest._
import matchers._
import time._


trait ProcessSpec extends Spec {
  protected[this] def it_(name: String)(body: => Unit @process): Unit = it(name) {
    spawnAndBlock(body)
  }
  
  import ShouldMatchers._
  protected[this] def assertEquals(a: Any, b: Any) = {
    a should be(b)
  }
  
  protected[this] def sleep(forTime: Duration) = {
    receiveWithin(forTime) { case Timeout => () }
  }
}
