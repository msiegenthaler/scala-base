package ch.inventsoft.scalabase
package oip

import executionqueue._
import process._


/**
 * Class that is spawned as its own process. 
 */
trait Spawnable {
  protected[oip] def start(as: SpawnStrategy) = {
    val p = as.spawn(body)
    _process.set(p)
  }
  private val _process = new scala.concurrent.SyncVar[Process]
  protected[this] lazy val process = _process.get  

  protected[this] def body: Unit @process
}
trait SpawnableCompanion[+A <: Spawnable] {
  protected[this] def start(as: SpawnStrategy)(what: A) = {
    what.start(as)
    what
  }
}


/**
 * Implements the spawning of a Spawnable. 
 */
trait SpawnStrategy extends {
  def spawn[A](body: => A @process): Process @process
  final def apply[A](body: => A @process): Process @process = spawn(body)
}
object SpawnAsOwnProcess extends SpawnStrategy {
  override def spawn[A](body: => A @process) = {
    ch.inventsoft.scalabase.process.spawn(body)
  }
}
object SpawnAsRequiredChild extends SpawnStrategy {
  override def spawn[A](body: => A @process) = {
    spawnChild(Required)(body)
  }
}
object SpawnAsMonitoredChild extends SpawnStrategy {
  override def spawn[A](body: => A @process) = {
    spawnChild(Monitored)(body)
  }
}

object Spawn {
  def asChild(childType: ChildType)(queue: ExecutionQueue = execute) = new SpawnStrategy {
    override def spawn[A](body: => A @process) = {
      spawnChildProcess(queue)(childType)(body)
    }
  }
  def asOwnProcess(queue: ExecutionQueue = execute) = new SpawnStrategy {
    override def spawn[A](body: => A @process) = {
      spawnProcess(queue)(body)
    }
  }
}
