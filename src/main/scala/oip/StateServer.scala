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
trait StateServer extends Spawnable with Log with Process {
  protected type State

  override def !(msg: Any) = process ! msg
  def ![R](msg: MessageWithSelectableReply[R]) = {
    msg.sendAndSelect(this)
  }

  protected[this] def cast(modificator: State => State @processCps) = {
    this ! new ModifyStateMessage {
      override def execute(state: State) = modificator(state)
    }
  }
  protected[this] def call[R](fun: State => (R,State) @processCps): Selector[R] @processCps = {
    this ! new ModifyStateMessage with MessageWithSimpleReply[R] {
      override def execute(state: State) = {
        val (v, s) = fun(state)
        reply(v)
        s
      }
    }
  }
  protected[this] def get[R](getter: State => R @processCps): Selector[R] @processCps = {
    this ! new ModifyStateMessage with MessageWithSimpleReply[R] {
      override def execute(state: State) = {
        val v = getter(state)
        reply(v)
        state
      }
    }
  }
  protected[this] def async[R](fun: State => R @processCps): Selector[R] @processCps = {
    this ! new ModifyStateMessage with MessageWithSimpleReply[R] {
      override def execute(state: State) = {
        spawnChild(Required) {
          val r = fun(state)
          reply(r)
        }
        state
      }
    }
  }
  protected def stop = this ! Terminate
  protected def stopAndWait: Completion @processCps = {
    async { state =>
      watch(process)
      stop
      receive {
        case ProcessExit(this.process) => ()
      }
    }
  }

  protected[this] def init: State @processCps
  protected[this] override def body = stateRun(init)
  protected[this] def stateRun(state: State): Unit @processCps = {
    val newState = receive(handler(state))
    if (newState.isDefined) stateRun(newState.get)
    else noop
  }
  protected def handler(state: State): PartialFunction[Any,Option[State] @processCps] = {
    case msg: ModifyStateMessage => Some(msg.execute(state))
    case Terminate => termination(state); None
  }
  protected[this] def termination(state: State) = noop

  protected[this] trait ModifyStateMessage {
    def execute(state: State): State @processCps
  }
}

