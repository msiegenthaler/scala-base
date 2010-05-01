package ch.inventsoft.scalabase.process

/**
 * An lightweight execution thread that may have side-effects (i.e. send, receive messages). Also
 * known as actor (see scala.actors.Actor).
 * A process only uses about 600 bytes of memory and no other system resources (esp. no os-thread),
 * so spawning processes is really cheap (time to spawn &lt;2ms). The processes are executed using
 * ExecutionQueues (ForkJoinThreadPool based).
 * Processes can communicate using asynchronous messaging (bang-operator and receive- resp.
 * receiveWithin-functions). Message are received using pattern matching.
 *
 * Usage example:
 *   val p = spawn {
 *     println("Process started")
 *     receive {
 *       case a: Int => println("Received "+a)
 *     }
 *     println("Process terminated")
 *   }
 *   p ! 12
 */
trait Process {
  /**
   * Send an asynchronous message to the process.
   * @param msg the message
   */
  def !(msg: Any): Unit
}
