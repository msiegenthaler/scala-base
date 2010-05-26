package ch.inventsoft.scalabase.process

import org.scalatest._
import matchers._
import ch.inventsoft.scalabase.time._
import Process._
import ProcessTestSupport._
import Messages._

class MessagesSpec extends ProcessSpec with ShouldMatchers{
  class MyServer {
    private trait MyServerMessage
    private case class Log(text: String) extends MyServerMessage
    private case class GetLogged() extends MyServerMessage with MessageWithSimpleReply[String]
    private case class GetLoggedDelay(delay: Duration) extends MyServerMessage with MessageWithSimpleReply[String]
    
    private val process = spawn(step(""))
    private def step(log: String): Unit @processCps = receive {
      case Log(text) =>
        if (log.isEmpty) step(text) else step(log+"\n"+text)
      case msg: GetLogged =>
        msg replyValue log
        step(log)
      case msg @ GetLoggedDelay(delay)  =>
        Thread.sleep(delay.amountAs(Milliseconds))
        msg replyValue log
        step(log)
    }
    
    def log(text: String): Unit = process ! Log(text)
    def logged: MessageSelector[String] = GetLogged().sendAndSelect(process)
    def loggedSlow(delay: Duration) = GetLoggedDelay(delay).sendAndSelect(process)
  }
  case class TestMessage() extends SenderAwareMessage
  
  describe("SenderAwareMessage") {
    it_("should have the current process as its sender") {
      val msg = TestMessage()
      val s = msg.sender 
      s should be(self)
    }
    it_("should be able to reply to the sending process") {
      val msg = TestMessage()
      msg.reply("Hi")
      receiveWithin(500 ms) {
        case "Hi" => //ok
        case otherwise => fail(""+otherwise)
      }
    }
    it_("should retain the original sender if sent to a different process") {
      val p = spawnChild(Required) { receive {
        case msg: TestMessage => msg reply "ok" 
      }}
      val msg = TestMessage()
      p ! msg
      receiveWithin(500 ms) {
        case "ok" => //ok
        case otherwise => fail(""+otherwise)
      }
    }
  }
  
  describe("Message Selector") {
    it_("should support a nice syntax for receiving specific values") {
      val s = new MyServer
      s log "Funny stuff"
      val log = receive { s.logged }
      log should be("Funny stuff")
    }
    it_("should support inline transformations") {
      val s = new MyServer
      s log "Mario rocks"
      val log = receive { s.logged(_+"!") }
      log should be("Mario rocks!")
    }
    it_("should support receive within (not timeouted)") {
      val s = new MyServer
      s log "Mario rocks"
      val log = receiveWithin(100 ms) { s.logged }
      log should be("Mario rocks")
    }
    it_("should provide an easy possibility to support timeouts (options)") {
      val s = new MyServer
      s log "Huhu"
      val r = receiveWithin(100 ms)(s.loggedSlow(1 s).option)
      r should be(None)
    }
    it_("should provide an easy possibility to support timeouts (options, not timeouted)") {
      val s = new MyServer
      s log "Huhu"
      val r = receiveWithin(500 ms)(s.loggedSlow(100 ms).option)
      r should be(Some("Huhu"))
    }
    it_("should support mapping") {
      val s = new MyServer
      s log "Mario rocks"
      val logSel = s.logged
      val logSel2 = logSel.map(_ + "!")
      val log = receive(logSel2)
      log should be("Mario rocks!")
    }
    it_("should support mapping to a different type") {
      val s = new MyServer
      s log "Mario rocks"
      val logSel = s.logged
      val logSel2 = logSel.map(_ match {
        case "Mario rocks" => true
        case other => false
      })
      val log = receive(logSel2)
      log should be(true)
    }
  }
  describe("Request Token") {
    it_("should make it easy to spawn calculations as child processes") {
      val token = RequestToken.create[Int]
      spawnChild(Required) {
        val result = (1 to 100).foldLeft(0)(_ + _)
        token.reply(result)
      }
      val r = receiveWithin(500 ms)( token.select.option )
      r should be(Some(5050))
    }
  }
}
