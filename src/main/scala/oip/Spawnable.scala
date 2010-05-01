package ch.inventsoft.scalabase.oip

import ch.inventsoft.scalabase.executionqueue.ExecutionQueues._
import ch.inventsoft.scalabase.process._


/**
 * Class that is spawned as its own process. 
 */
trait Spawnable {
  protected def start(as: SpawnStrategy) = {
    val p = as.spawn(body)
    _process.set(p)
  }
  private val _process = new scala.concurrent.SyncVar[Process]
  protected[this] lazy val process = _process.get  

  protected[this] def body: Unit @processCps
}

/**
 * Implements the spawning of a Spawnable. 
 */
trait SpawnStrategy {
  def spawn[A](body: => A @processCps): Process @processCps
}
object SpawnAsOwnProcess extends SpawnStrategy {
  override def spawn[A](body: => A @processCps) = {
    ch.inventsoft.scalabase.process.spawn(body)
  }
}
object SpawnAsRequiredChild extends SpawnStrategy {
  override def spawn[A](body: => A @processCps) = {
    spawnChild(Required)(body)
  }
}
object SpawnAsMonitoredChild extends SpawnStrategy {
  override def spawn[A](body: => A @processCps) = {
    spawnChild(Monitored)(body)
  }
}