package ch.inventsoft.scalabase
package oip

import process._
import Messages._
import executionqueue._


/**
 * Object that support process-based actions (asynchronous methods).
 *
 * Example:
 * class MyService extends ConcurrentObject {
 *   //will be executed in a newly spawned process
 *   def calculate(a: Long, b: Long) = concurrentWithReply {
 *     val r: Long = doSomeComplexCalculation(a,b)
 *     r + 1
 *   }
 * }
 */
trait ConcurrentObject {
  protected[this] def concurrentQueue: ExecutionQueue = execute
  
  /** Doesn't do much except returning a MessageSelector instead of a "simple" value */
  protected[this] def replyInCallerProcess[A](fun: => A @processCps): MessageSelector[A] @processCps = {
    val token = RequestToken.create[A]
    token.reply(fun)
    token.select
  }

  /** Executes fun in a new process (spawnChild(Required)) */
  protected[this] def concurrent(fun: => Unit @processCps): Unit @processCps = concurrent(concurrentQueue)(fun)
  protected[this] def concurrent(queue: ExecutionQueue)(fun: => Unit @processCps): Unit @processCps = {
    spawnChildProcess(queue)(Required) { fun }
    noop
  }

  /** Executes fun in a new process (spawnChild(Required)) and returns a selector for the result */
  protected[this] def concurrentWithReply[A](fun: => A @processCps): MessageSelector[A] @processCps = concurrentWithReply(concurrentQueue)(fun)
  protected[this] def concurrentWithReply[A](queue: ExecutionQueue)(fun: => A @processCps): MessageSelector[A] @processCps = {
    val token = RequestToken.create[A]
    spawnChildProcess(queue)(Required) {
      val result = fun
      token.reply(result)
    }
    token.select
  }
  /**
   * Executes fun in a new process (spawnChild(Required)) and returns a selector for the result.
   * Fun gets passed a callback function.
   * Example:
   * def calculateIt(a: Long) = concurrentWithReplyFun { reply =>
   *   val r = doSomeCalculation(a)
   *   reply(r)
   * }
   */
  protected[this] def concurrentWithReplyFun[A](fun: (A => Unit) => Unit @processCps): MessageSelector[A] @processCps = concurrentWithReplyFun(concurrentQueue)(fun)
  protected[this] def concurrentWithReplyFun[A](queue: ExecutionQueue)(fun: (A => Unit) => Unit @processCps): MessageSelector[A] @processCps = {
    val token = RequestToken.create[A]
    spawnChildProcess(queue)(Required) {
      val result = fun(token.reply _)
    }
    token.select
  }
}
