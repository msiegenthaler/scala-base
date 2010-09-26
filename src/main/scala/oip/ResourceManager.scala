package ch.inventsoft.scalabase
package oip

import process._
import Messages._
import executionqueue._


/**
 * Manages a resource by taking care of closing it if the caller process exits.
 */
object ResourceManager extends log.Log {
  /**
   * 'resource' is executed in the caller process
   * 'close' is executed in the monitoring process. Exceptions in that code won't affect anybody.
   */
  def apply[A](resource: => A @process, close: A => Any @process, openAs: SpawnStrategy = RunInCallerProcess):
      Selector[ResourceManager[A]] @process = {
    val resourceOpen = RequestToken[ResourceManager[A]]()
    val user = self
    val res = resource
    val watcher = spawnWatcher {
      val watcher = self
      val manager = new ResourceManager[A] {
        override def close = Close().sendAndSelect(watcher)
        override val resource = res
      }
      resourceOpen.reply(manager)

      def doClose(reason: => String) = {
        log.debug("Closing resource {} for {}: {}", res, user, reason)
        close(res)
        log debug ("Closed resource {} for {}", res, user)
      }

      receive {
        case closeMsg: Close =>
          doClose("as per request")
          closeMsg.reply(())
        case ProcessExit(`user`) =>
          doClose("normal exit")
        case ProcessCrash(`user`, _) =>
          doClose("crash")
        case ProcessKill(`user`, _, _) =>
          doClose("kill")
      }
    }
    resourceOpen.select
  }

  private case class Close() extends MessageWithSimpleReply[Unit]
}

/** Manages a resource by taking care of closing if it if the caller exits (or close is called) */
trait ResourceManager[A] {
  def close: Selector[Unit] @process
  val resource: A
}
