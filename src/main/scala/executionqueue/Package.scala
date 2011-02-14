package ch.inventsoft.scalabase

package object executionqueue {
  
  /** Default execution queue (concurrent). */
  val execute = queue()
  
  /** Background priority execution queue (concurrent) */
  val executeInBackground = queue background()
  
  /** High priority execution queue (concurrent) */
  val executeHighPrio = queue high()
  
  /**
   * Execute a task that spends a lot of time blocked. Beware, since this uses a thread per
   * concurrent execution.
   */
  val executeForBlocking = queue mightBlock()
  

  /** Define a specially defined queue. */
  def queue = ExecutionQueues.queue

  /**
   * "Thread-local" for the execution. Will be cleared after the execution finishes.
   */
  def executionLocal_=(value: Option[Any]) = ExecutionQueues.executionLocal = value
  /** Access the "execution-local". Return None if not set or not inside an executor */
  def executionLocal = ExecutionQueues.executionLocal
}
