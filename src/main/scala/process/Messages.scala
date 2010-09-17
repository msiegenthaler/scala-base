package ch.inventsoft.scalabase.process

import Process._
import cps.CpsUtils._
import ch.inventsoft.scalabase.time._


/**
 * Helpers for message handling.
 */
object Messages {
  type MsgSel[A] = MessageSelector[A]
  type Sel[A] = MessageSelector[A]
  type Selector[A] = MessageSelector[A]

  /**
   * Selects a message and applies an optional conversion function to the message before 
   * returning it.
   *
   * Usage:
   *   val x: Int = receive { myselector }
   *   val y: Int = receive { myselector.map(_ + 1) }
   *   val z: Option[Int] = receiveWithin(10 s){ myselector.option }
   * 
   * Shortcut functions (do the same):
   *   val x = myselector.receive
   *   val y = myselector.map(_ + 1).receive
   *   val z = myselector.receiveOption(10 s)
   */
  trait MessageSelector[+B] extends PartialFunction[Any,B @processCps] {
    def receive = ch.inventsoft.scalabase.process.receive(this)
    def receiveWithin(timeout: Duration) = ch.inventsoft.scalabase.process.receiveWithin(timeout)(this)
    def receiveOption(timeout: Duration) = option.receiveWithin(timeout)
    def await: Unit @processCps = {
      receive
      noop
    }
    def await(timeout: Duration): Unit @processCps = {
      receiveWithin(timeout)
      noop
    }

    def option = map(Some(_)).or {
      case Timeout => None
    }

    def or[B1>:B](that: PartialFunction[Any,B1]): MessageSelector[B1] = {
      val base = this
      new MessageSelector[B1] {
        override def apply(v: Any) = {
          if (base.isDefinedAt(v)) base.apply(v)
          else that.apply(v)
        }
        override def isDefinedAt(v: Any) = base.isDefinedAt(v) || that.isDefinedAt(v)
      }
    }
    def map[C](fun: B => C) = {
      val base = this
      new MessageSelector[C] {
        override def apply(v: Any) = {
          val r1 = base(v)
          fun(r1)
        }
        override def isDefinedAt(v: Any) = base.isDefinedAt(v)
      }
    }
    def map_cps[C](fun: B => C @processCps) = {
      val base = this
      new MessageSelector[C] {
        override def apply(v: Any) = {
          val r1 = base(v)
          fun(r1)
        }
        override def isDefinedAt(v: Any) = base.isDefinedAt(v)
      }
    }
    override def toString = "<message selector>"
  }

  
  implicit def partialFunctionToMessageSelector[A](fun: PartialFunction[Any,A @processCps]): MessageSelector[A] = {
    new MessageSelector[A] {
      override def apply(v: Any) = fun(v)
      override def isDefinedAt(v: Any) = fun.isDefinedAt(v)
    }
  }
  
  trait SenderAwareMessage {
    protected[this] val sender: Process = {
      useWithCare.currentProcess match {
        case Some(process) => process
        case None => throw new IllegalStateException("Not inside a process, cannot set sender")
      }
    }
  }
  trait ReplyMessage {
    def request: SenderAwareMessage
    def isReplyTo(request: SenderAwareMessage) = this.request eq request
  }
  trait MessageWithSelectableReply[R] {
    protected[this] val sender: Process
    def sendAndSelect(to: Process): MessageSelector[R]
  }
  
  /**
   * Use for request-reply style communication between two processes. It is possible to send
   * more than one reply message.
   * 
   * Example:
   *  case class SumRequest(a: Int, b: Int) extends MessageWithSimpleReply[Long]
   *  // in Process A:
   *    SumRequest(v1, v2) sendAndSelect(processB)
   *  // in Process B:
   *    receive {
   *      case request @ SumRequest(a, b) =>
   *        request.reply(a+b)
   *    }
   */
  trait MessageWithSimpleReply[A] extends SenderAwareMessage with MessageWithSelectableReply[A] {
    override def sendAndSelect(to: Process): MessageSelector[A] = {
      to ! this
      //a bit complicated because guards won't work properly with cps
      new MessageSelector[A] {
        override def isDefinedAt(value: Any) = {
          if (value.isInstanceOf[SimpleReplyMessage[_]]) {
            value.asInstanceOf[SimpleReplyMessage[A]] isReplyTo MessageWithSimpleReply.this
          } else false
        }
        override def apply(value: Any): A @processCps = {
          value.asInstanceOf[SimpleReplyMessage[A]].value
        }
      }
    }
    def reply(value: A) = sender ! SimpleReplyMessage(value, this)
    
    private[this] case class SimpleReplyMessage[A](value: A, request: MessageWithSimpleReply[A]) extends ReplyMessage
  }

  /**
   * Token we can then reply to from a different process.
   * Usage: 
   *   val token = RequestToken.create[String]
   *   spawnChild(Required) {
   *     val result = doSomethingComplicated()
   *     token.reply(result)
   *   } 
   *   token.select
   */
  class RequestToken[A] protected(val initiatedBy: Process) {
    def reply(value: A) = {
      initiatedBy ! Reply(value, this)
    }
    def select: MessageSelector[A] = {
      new MessageSelector[A] {
        override def isDefinedAt(value: Any) = {
          if (value.isInstanceOf[Reply[_]]) {
            value.asInstanceOf[Reply[A]] isForToken RequestToken.this
          } else false
        }
        override def apply(value: Any): A @processCps = {
          value.asInstanceOf[Reply[A]].value
        }
      }
    }
    private[this] case class Reply[A](value: A, token: RequestToken[A]) {
      def isForToken(token: RequestToken[_]) = this.token == token
    }
  }
  object RequestToken {
    def apply[A](): RequestToken[A] @processCps = {
      new RequestToken(self)
    }
    def create[A]: RequestToken[A] @processCps = {
      new RequestToken(self)
    }
  }
  
  
  def timeoutToNone[A](value: Any): Option[A] = value match {
    case Timeout => None
  }
}
