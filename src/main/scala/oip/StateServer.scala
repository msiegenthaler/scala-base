package ch.inventsoft.scalabase
package oip

import executionqueue._
import time._
import process._
import Messages._
import log.Log


/**
 * Manages a state private to a process.
 * The state can be queried and modified using messages to the process. The messages are
 * emitted using special methods.
 */
trait StateServer extends Spawnable with ConcurrentObject with Log with Process {
  protected type State

  override def !(msg: Any) = process ! msg
  def ![R](msg: MessageWithSelectableReply[R]) = {
    msg.sendAndSelect(this)
  }

  protected def cast(modificator: State => State @process) = {
    this ! new ModifyStateMessage {
      override def execute(state: State) = modificator(state)
    }
  }
  protected def call[R](fun: State => (R,State) @process): Selector[R] @process = {
    this ! new ModifyStateMessage with MessageWithSimpleReply[R] {
      override def execute(state: State) = {
        val (v, s) = fun(state)
        reply(v)
        s
      }
    }
  }
  protected def get[R](getter: State => R @process): Selector[R] @process = {
    this ! new ModifyStateMessage with MessageWithSimpleReply[R] {
      override def execute(state: State) = {
        val v = getter(state)
        reply(v)
        state
      }
    }
  }
  protected def async[R](fun: State => R @process): Selector[R] @process = {
    this ! new ModifyStateMessage with MessageWithSimpleReply[R] {
      override def execute(state: State) = {
        concurrent {
          val r = fun(state)
          reply(r)
        }
        state
      }
    }
  }
  protected def asyncCast[R](fun: State => Any @process): Unit @process = {
    this ! new ModifyStateMessage {
      override def execute(state: State) = {
        concurrent {
          fun(state)
          ()
        }
        state
      }
    }
  }
  protected def atomic(fun: State => State @process) = {
    val r = this ! new ModifyStateMessage with MessageWithSimpleReply[State] {
      override def execute(state: State) = {
        val s = fun(state)
        reply(s)
        s
      }
    }
    r.receive
  }
  protected def stop = this ! Terminate
  protected def stopAndWait: Completion @process = concurrentWithReply {
    watch(process)
    stop
    receive {
      case ProcessExit(this.process) => ()
    }
  }

  protected def init: State @process
  protected override def body = stateRun(init)
  protected def stateRun(state: State): Unit @process = {
    val newState = receive(handler(state))
    if (newState.isDefined) stateRun(newState.get)
    else noop
  }
  protected def handler(state: State): PartialFunction[Any,Option[State] @process] = {
    case msg: ModifyStateMessage => Some(msg.execute(state))
    case Terminate => termination(state); None
  }
  protected def termination(state: State) = noop

  protected trait ModifyStateMessage {
    def execute(state: State): State @process
  }
}

