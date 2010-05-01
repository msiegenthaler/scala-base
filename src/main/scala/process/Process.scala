package ch.inventsoft.scalabase.process

/**
 * An execution thread that may have side-effects (i.e. send, receive messages). 
 * @author ms
 */
trait Process {
  /**
   * Send an asynchronous message to the process.
   * @param msg the message
   */
  def !(msg: Any): Unit
}
