package ch.inventsoft.scalabase
package executionqueue

object ForkJoinExecutor extends ExecutorFactory {
  override def createExecutor(label: String, spec: ExecutorSpec): Executor = {
    if (spec.mightBlock) new OpenThreadPoolExecutor(label, spec.priority)
    else new ForkJoinExecutor(label, spec.priority)
  }
}

class ForkJoinExecutor(val label: String, override val priority: Priority) extends Executor {
  import jsr166y._
  private[this] val threadFactory = new ForkJoinPool.ForkJoinWorkerThreadFactory {
    private val counter = new java.util.concurrent.atomic.AtomicInteger(0)
    override def newThread(pool: ForkJoinPool) = {
      val t = new FJWThread(label, priority, counter.incrementAndGet, pool)
      t
    }
  }
  private[this] val pool = new ForkJoinPool(threadFactory)
  def execute(f: => Unit) = {
    pool.execute(new ForkJoinTask[Unit] {
      override def exec() = {
        f
        ExecutionQueues.executionLocal = None
        true
      }
      override def getRawResult() = ()
      override def setRawResult(value: Unit) = ()
    })
  }
  def shutdown = pool.shutdown
}
private[executionqueue] class FJWThread private(pool: jsr166y.ForkJoinPool) extends jsr166y.ForkJoinWorkerThread(pool) with ExecutorThread {
  def this(label: String, priority: Priority, id: Int, pool: jsr166y.ForkJoinPool) = {
    this(pool)
    val name = label + "-p" + priority + "-" + id
    setName(name)
    setPriority(priority.javaPriority)
    setDaemon(false)
  }
}
