package ch.inventsoft.scalabase.oip

import ch.inventsoft.scalabase.executionqueue._
import ch.inventsoft.scalabase.time._
import ch.inventsoft.scalabase.process._
import ch.inventsoft.scalabase.process.cps.CpsUtils._
import Process._
import Messages._
import ch.inventsoft.scalabase.log.Log


/**
 * Manages a state private to a process.
 * The state can be queried and modified using messages to the process. The messages are
 * emitted using special methods.
 */
trait StateServer[State] extends Spawnable with Log {
  type StateModifier = (State) => State @processCps
  type StateModifierWithEnd = (State) => Option[State] @processCps
  type StateModifierWithReply[R] = (State) => (R,State) @processCps
  type StateModifierWithReplyAndEnd[R] = (State) => (R,Option[State]) @processCps
  type StateModifierWithSpecialReply[R] = (State, Function1[R,Unit]) => (Option[State]) @processCps
  type StateGetter[R] = (State) => R @processCps
  
  protected[this] def initialState: State @processCps
  
  private type Handler = PartialFunction[Any,Option[State] @processCps]
  protected[this] override def body = step(initialState)
  private[this] def step(state: State): Unit @processCps = {
    val modifyStateHandler: Handler = {
      case ModifyState(fun) => 
        fun(state)
      case msg @ ModifyStateWithReply(fun) => 
        val (result,newState) = fun(state)
        msg.replyValue(result)
        newState
      case msg @ ModifyStateWithSpecialReply(fun) =>
        val resultFun = msg.replyValue _
        val newState = fun(state, resultFun)
        newState
      case msg @ GetState(fun) =>
        msg.replyValue(fun(state))
        Some(state)
    }
    val specialHandler: Handler = {
      case end: ProcessEnd => handleProcessEnd(end, state)
      case Terminate => handleStopRequest(state)
    }
    val handler = modifyStateHandler.
      orElse_cps(specialHandler).
      orElse_cps(messageHandler(state)).
      orElse_cps(unhandledMessageHandler(state))
      
    val newState = receive(handler)
    if (newState.isDefined) step(newState.get)
    else terminatedNormally(state)
  }
  protected[this] def terminatedNormally(finalState: State): Unit @processCps = ()
  protected[this] def handleProcessEnd(end: ProcessEnd, state: State): Option[State] @processCps = Some(state)
  protected[this] def handleStopRequest(state: State): Option[State] @processCps = None
  protected[this] def messageHandler(state: State): Handler = new PartialFunction[Any,Option[State] @processCps] {
    override def isDefinedAt(msg: Any) = false
    override def apply(msg: Any) = Some(state)
  }
  private[this] def unhandledMessageHandler(state: State): Handler = {
    case any =>
      log.debug("StateServer {} received unhandled message {}", StateServer.this, any)
      Some(state)
  }
  
  private[this] trait StateServerMessage
  private[this] case class ModifyState(modifier: StateModifierWithEnd) extends StateServerMessage
  private[this] case class ModifyStateWithReply[R](modifier: StateModifierWithReplyAndEnd[R]) extends StateServerMessage with MessageWithSimpleReply[R]
  private[this] case class ModifyStateWithSpecialReply[R](modifier: StateModifierWithSpecialReply[R]) extends StateServerMessage with MessageWithSimpleReply[R]
  private[this] case class GetState[R](getter: StateGetter[R]) extends StateServerMessage with MessageWithSimpleReply[R]
  
  protected[this] def cast(f: StateModifier) = {
    cast_(s => Some(f(s)))
  }
  protected[this] def cast_(f: StateModifierWithEnd): Unit = process ! ModifyState(f)
  protected[this] def call[R](f: StateModifierWithReply[R]): MessageSelector[R] = {
    call_ { s =>
      val (r,s2) = f(s)
      (r,Some(s2))
    }
  }
  protected[this] def call_[R](f: StateModifierWithReplyAndEnd[R]): MessageSelector[R] =
    ModifyStateWithReply(f).sendAndSelect(process)
  protected[this] def call_?[R](f: StateModifierWithSpecialReply[R]): MessageSelector[R] =
    ModifyStateWithSpecialReply(f).sendAndSelect(process)
  protected[this] def get[R](f: StateGetter[R]): MessageSelector[R] =
    GetState(f).sendAndSelect(process)
}

