package ch.inventsoft.scalabase

import scala.util.continuations._
import time._
import executionqueue._
import process.cps._


/**
 * Lightweight processes (aka actors). See Process for detailed documentation.
 */
package object process {
  type processCps = ProcessCps.processCps

  /**
   * Spawns a new process.
   */
  def spawnProcess(executionQueue: ExecutionQueue)(body: => Any @processCps): Process = {
    ProcessCps.spawnProcess(executionQueue)(body)
  }
  
  /**
   * Spawns a new process with the default priority.
   */
  def spawn(body: => Any @processCps): Process =
    spawnProcess(execute)(body)
  /**
   * Spawns a new process with background priority.
   */
  def spawnBackground(body: => Any @processCps): Process = 
    spawnProcess(executeInBackground)(body)
  /**
   * Spawns a new process with high priority.
   */
  def spawnHighPrio(body: => Any @processCps): Process = 
    spawnProcess(executeHighPrio)(body)
  
  /**
   * Spawns a child process. The process will be linked to the calling process (the parent).
   */
  def spawnChildProcess(executionQueue: ExecutionQueue)(kind: ChildType)(body: => Any @processCps): Process @processCps = 
    ProcessCps.spawnChildProcess(executionQueue, kind, body)
    
  /**
   * Spawns a child process with the default priority.
   * The process will be linked to the calling process (the parent).
   */
  def spawnChild(kind: ChildType)(body: => Any @processCps): Process @processCps =
    spawnChildProcess(execute)(kind)(body)
  /**
   * Spawns a child process with background priority.
   * The process will be linked to the calling process (the parent).
   */
  def spawnBackgroundChild(kind: ChildType)(body: => Any @processCps): Process @processCps =
    spawnChildProcess(executeInBackground)(kind)(body)
  /**
   * Spawns a child process with high priority.
   * The process will be linked to the calling process (the parent).
   */
  def spawnHighPrioChild(kind: ChildType)(body: => Any @processCps): Process @processCps =
    spawnChildProcess(executeHighPrio)(kind)(body)
    

  /**
   * Receives the next matching message for the process. Blocks until a message is
   * received (indefinitly is no matching message is received).
   * @param f message processor
   * @return the value returned by the message processor
   */
  def receive[T](f: PartialFunction[Any,T @processCps]): T @processCps =
    ProcessCps.receive(f)
  /**
   * Receives the next matching message for the process. Blocks until a message is
   * received or the timeout is expired. A timeout expiration results in a Timeout
   * message.
   * @param timeout timespan after that the Timeout message is received
   * @param f message processor
   * @return the value returned by the message processor
   */
  def receiveWithin[T](timeout: Duration)(f: PartialFunction[Any,T @processCps]): T @processCps =
    ProcessCps.receiveWithin(timeout)(f)
  /**
   * Receives the next matching message with a timeout of 0. Does not block, but instead
   * results in an immediate Timeout if no matching message is waiting.
   * @param f message processor
   * @return the value returned by the message processor
   */
  def receiveNoWait[T](f: PartialFunction[Any,T @processCps]): T @processCps = 
    ProcessCps.receiveNoWait(f)
    
  /**
   * Registers this process as a watcher on the <i>toWatch</i> process. The process will
   * receive ProcessEnd messages for the process.
   * @param toWatch
   * @return nothing
   */
  def watch(toWatch: Process): Unit @processCps = ProcessCps.watch(toWatch)

  /**
   * @return the the current process (external view)
   */
  def self: Process @processCps = ProcessCps.self
  
  /**
   * @return a no-op cps
   */
  def noop: Unit @processCps = ProcessCps.noop
  /**
   * @return the value as a cps
   */
  implicit def valueToCps[A](value: A): A @processCps = ProcessCps.valueToCps(value) 
  
  
  /**
   * Advanced functions, that should only be used in special cases, mostly inside frameworks.
   */
  object useWithCare {
    /**
     * @return the process executing the calling code. It's better to use 'self', but currentProcess
     * might be easier in some cases.
     */
    def currentProcess: Option[Process] = ProcessCps.useWithCare.currentProcess
  }  

  /**
   * Message signaling an expired timeout.
   * @see process.receiveWithin and process.receiveNoWait
   */
  object Timeout {
    override def toString = "Timeout"
  }
  
  
  
  //CPS Util functions
  
  implicit def cpsIterable[A](ita: Iterable[A]) = CpsUtils.cpsIterable(ita)
  implicit def cpsOption[A](option: Option[A]) = CpsUtils.cpsOption(option)
  implicit def cpsPartialFunction[A,B,X](fun: PartialFunction[A,B @cps[X]]) = CpsUtils.cpsPartialFunction(fun)
}


