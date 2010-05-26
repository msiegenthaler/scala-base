package ch.inventsoft.scalabase.process

import Process._
import cps.CpsUtils._


/**
 * Helpers for message handling.
 */
object Messages {
  /**
   * Selects a message and applies an optional conversion function to the message before 
   * returning it.
   * Usage: val x = receive { myselector.apply(_ + 1) }
   *    or: val x = receiveWithin(10 s) { myselector.value } 
   */
  trait MessageSelector[A] {
    def apply(): PartialFunction[Any,A @processCps] 
    def apply[B](body: Function1[A,B @processCps]): PartialFunction[Any,B @processCps] = map_cps(body)()
    def value = apply()
    def option: PartialFunction[Any,Option[A] @processCps] = {
      apply(v => Some(v)).orElse_cps({
        case Timeout => None
      })
    }
    def map[B](fun: Function1[A,B]): MessageSelector[B] = {
      val outer = this
      new MessageSelector[B] {
        override def apply() = {
          val f = outer.apply()
          new PartialFunction[Any,B @processCps] {
            override def isDefinedAt(v: Any) = f.isDefinedAt(v)
            override def apply(v: Any) = {
              val a = f(v)
              fun(a)
            }
          }
        }
      }
    }
    def map_cps[B](fun: Function1[A,B @processCps]): MessageSelector[B] = {
      val outer = this
      new MessageSelector[B] {
        override def apply() = cpsPartialFunction(outer.apply()).andThen_cps(fun)
      }
    }
    override def toString = "<message selector>"
  }
  
  implicit def messageSelectorToPartialFunction[A](selector: MessageSelector[A]): PartialFunction[Any,A @processCps] = {
    selector.value
  }
  
  def select[A](preProcessor: PartialFunction[Any,A @processCps]): MessageSelector[A] = {
    new MessageSelector[A] {
      override def apply() = preProcessor
    }
  }
  
  trait SenderAwareMessage {
    val sender: Process = {
      useWithCare.currentProcess match {
        case Some(process) => process
        case None => throw new IllegalStateException("Not inside a process, cannot set sender")
      }
    }
    def reply(msg: Any) = sender ! msg
  }
  trait ReplyMessage {
    def request: SenderAwareMessage
    def isReplyTo(request: SenderAwareMessage) = this.request eq request
  }
  
  /**
   * Use for request-reply style communication between two processes. Be careful not to use reply
   * but replyValue.
   * 
   * Example:
   *  case class SumRequest(a: Int, b: Int) extends MessageWithSimpleReply[Long]
   *  // in Process A:
   *    SumRequest(v1, v2) sendAndSelect(processB)
   *  // in Process B:
   *    receive {
   *      case request @ SumRequest(a, b) =>
   *        request.replyValue(a+b)
   *    }
   */
  trait MessageWithSimpleReply[A] extends SenderAwareMessage {
    def sendAndSelect(to: Process): MessageSelector[A] = {
      to ! this
      //a bit complicated because guards won't work properly with cps
      val fun = new PartialFunction[Any,A @processCps] {
        override def isDefinedAt(value: Any) = {
          if (value.isInstanceOf[SimpleReplyMessage[_]]) {
            value.asInstanceOf[SimpleReplyMessage[A]] isReplyTo MessageWithSimpleReply.this
          } else false
        }
        override def apply(value: Any): A @processCps = {
          value.asInstanceOf[SimpleReplyMessage[A]].value
        }
      }
      select(fun)
    }
    def replyValue(value: A) = reply(SimpleReplyMessage(value, this)) 
    
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
      val fun = new PartialFunction[Any,A @processCps] {
        override def isDefinedAt(value: Any) = {
          if (value.isInstanceOf[Reply[_]]) {
            value.asInstanceOf[Reply[A]] isForToken RequestToken.this
          } else false
        }
        override def apply(value: Any): A @processCps = {
          value.asInstanceOf[Reply[A]].value
        }
      }
      Messages.select(fun)
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