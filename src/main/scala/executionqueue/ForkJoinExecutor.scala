package ch.inventsoft.scalabase.executionqueue

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
      val t = new ForkJoinWorkerThread(pool) { }
      val name = label + "-p" + priority + "-" + counter.incrementAndGet
      t.setName(name)
      t.setPriority(priority.javaPriority)
      t.setDaemon(false)
      t
    }
  }
  private[this] val pool = new ForkJoinPool(threadFactory)
  def execute(f: => Unit) = {
    pool.execute(new ForkJoinTask[Unit] {
      override def exec() = {
        f
        true
      }
      override def getRawResult() = ()
      override def setRawResult(value: Unit) = ()
    })
  }
  def shutdown = pool.shutdown
}
