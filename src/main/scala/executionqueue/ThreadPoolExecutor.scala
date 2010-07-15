package ch.inventsoft.scalabase.executionqueue

import java.util.concurrent._


object ThreadPoolExecutorFactory extends ExecutorFactory {
  private val count = (Runtime.getRuntime.availableProcessors * 2 + 2)
  override def createExecutor(label: String, spec: ExecutorSpec): ThreadPoolExecutor = {
    if (spec.mightBlock) new OpenThreadPoolExecutor(label, spec.priority)
    else new FixedThreadPoolExecutor(label, spec.priority, count)
  }
}


abstract class ThreadPoolExecutor(val label: String, val priority: Priority) extends Executor {
  val threadGroup = new ThreadGroup(label)
  protected[this] val threadFactory = new ThreadFactory {
    private val counter = new java.util.concurrent.atomic.AtomicInteger(0)
    override def newThread(r: Runnable) = {
      new TPThread(r, threadGroup, label, counter.incrementAndGet, priority)
    }
  }
  val pool: ExecutorService
  
  override def execute(f: => Unit) = {
    pool.execute(new Runnable { override def run { 
      f
      ExecutionQueues.executionLocal = None
    }})
  }

  override def shutdown = pool.shutdown
}
class FixedThreadPoolExecutor(override val label: String, override val priority: Priority, val size: Int) extends ThreadPoolExecutor(label, priority) {
  val pool: ExecutorService = {
    val policy = new ThreadPoolExecutor.CallerRunsPolicy()
    new java.util.concurrent.ThreadPoolExecutor(size, size, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue[Runnable], threadFactory, policy)
  }
}
class OpenThreadPoolExecutor(l: String, override val priority: Priority) extends ThreadPoolExecutor("MightBlock-"+l, priority) {
  val pool: ExecutorService = {
    val policy = new ThreadPoolExecutor.CallerRunsPolicy()
    new java.util.concurrent.ThreadPoolExecutor(0, Integer.MAX_VALUE, 20, TimeUnit.SECONDS, new SynchronousQueue[Runnable], threadFactory, policy)
  }
}

private[executionqueue] class TPThread private(r: Runnable, name: String, group: ThreadGroup) extends Thread(group, r, name) with ExecutorThread {
  def this(toRun: Runnable, threadGroup: ThreadGroup, label: String, id: Int, priority: Priority) = {
    this(toRun, label + "-p" + priority + "-" + id, threadGroup)
    setPriority(priority.javaPriority)
  }
}
