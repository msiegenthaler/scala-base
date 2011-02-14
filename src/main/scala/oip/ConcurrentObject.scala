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
  protected def concurrentQueue: ExecutionQueue = execute
  
  /** Doesn't do much except returning a MessageSelector instead of a "simple" value */
  protected def replyInCallerProcess[A](fun: => A @process): Selector[A] @process = {
    val token = RequestToken.create[A]
    token.reply(fun)
    token.select
  }

  /** Executes fun in a new process (spawnChild(Required)) */
  protected def concurrent(fun: => Unit @process): Unit @process = concurrent(concurrentQueue)(fun)
  protected def concurrent(queue: ExecutionQueue)(fun: => Unit @process): Unit @process = {
    spawnChildProcess(queue)(Required) { fun }
    noop
  }

  /** Executes fun in a new process (spawnChild(Required)) and returns a selector for the result */
  protected def concurrentWithReply[A](fun: => A @process): Selector[A] @process =
    concurrentWithReply(concurrentQueue)(fun)
  protected def concurrentWithReply[A](queue: ExecutionQueue)(fun: => A @process) = {
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
  protected def concurrentWithReplyFun[A](fun: (A => Unit @process) => Unit @process): Selector[A] @process =
    concurrentWithReplyFun(concurrentQueue)(fun)
  protected def concurrentWithReplyFun[A](queue: ExecutionQueue)(fun: (A => Unit @process) => Unit @process) = {
    val token = RequestToken.create[A]
    spawnChildProcess(queue)(Required) {
      val result = fun(token.reply _)
    }
    token.select
  }
}
