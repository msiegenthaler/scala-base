package ch.inventsoft.scalabase.executionqueue

import util._

trait ExecutionQueue {
  def execute(f: => Unit): Unit
  def <--(f: => Unit) = execute(f)
  def -->:(f: () => Unit) = execute({ f() })
  def -->:(fs: Seq[() => Unit]) = fs.foreach(f => execute({ f() }))
}


object ExecutionQueues {
  //private val executorFactory = ThreadPoolExecutorFactory
  private val executorFactory = ForkJoinExecutor
  
  private val initialSpec = new ExecutionQueueSpec(Priority.Normal, QueueType.Concurrent, false)
  private val executors: Map[ExecutorSpec,Executor] = {
    val es = for {
      prio <- Priority.Min.javaPriority to Priority.Max.javaPriority;
      mightBlock <- List(false, true);
      spec = ExecutorSpec(new Priority() { override def javaPriority = prio }, mightBlock)      
    } yield (
      (spec, executorFactory.createExecutor("Executor", spec))
    )
    Map(es :_*)
  }
  
  
  /**
   * Default execution queue (concurrent).
   */
  val execute: ExecutionQueue = queue()
  /**
   * Background priority execution queue (concurrent)
   */
  val executeInBackground: ExecutionQueue = queue background()
  /**
   * High priority execution queue (concurrent)
   */
  val executeHighPrio: ExecutionQueue = queue high()
  /**
   * Execute a task that spends a lot of time blocked. Beware, since this uses a thread per
   * concurrent execution.
   */
  val executeForBlocking: ExecutionQueue = queue mightBlock()
  

  /**
   * Define a special queue.
   */
  def queue: ExecutionQueueGenerator =
    new ExecutionQueueGenerator(createQueue, initialSpec)

  
  //TODO figure something out to allow "auto-termination"
  /**
   * Shutdown the execution queues. The object is not usable after this method has been called.
   */
  def shutdownQueues = {
    executors.foreach(e => {
      try {
        e._2.shutdown
      } catch {
        case e => //ignore
      }
    })
  }


  private def createQueue(spec: ExecutionQueueSpec) = spec match {
    case ExecutionQueueSpec(prio, QueueType.Concurrent, mb) =>
      val executor = executors(ExecutorSpec(prio, mb))
      new ConcurrentQueue(executor)
    case ExecutionQueueSpec(prio, QueueType.Serial, mb) =>
      val executor = executors(ExecutorSpec(prio, mb))
      new SerialQueue(executor)
    case strange => throw new AssertionError("No matching queue type found... strange")
  }

  private class ConcurrentQueue(executor: Executor) extends ExecutionQueue {
    override def execute(f: => Unit) = {
      executor.execute(f)
    }
  }
  
  private class SerialQueue(executor: Executor) extends ExecutionQueue {
    //TODO write a more efficient implementation
    private var pending = List[() => Unit]()
    private var executing = false
    private val mutex = new Object
    override def execute(f: => Unit) = {
      val fun = () => { f; executeNext }
      mutex synchronized {
        pending = pending ::: List(fun)
        if (!executing) executeNext
      }
    }
    def executeNext = mutex synchronized {
      pending match {
        case next :: rest =>
          executor.execute { next() }
          pending = rest
        case Nil => //end, wait for new elements
      }
    }
  }
  
  private[ExecutionQueues] case class ExecutionQueueSpec(priority: Priority, queueType: QueueType.QueueType, mightBlock: Boolean)
  class ExecutionQueueGenerator protected[ExecutionQueues] (creator: (ExecutionQueueSpec) => ExecutionQueue, spec: ExecutionQueueSpec) extends Function0[ExecutionQueue] {
    import QueueType._
    import Priority._
    
    def apply() = {
      creator(spec)
    }
    
    def withPriority(priority: Priority) = 
      respec(ExecutionQueueSpec(priority, spec.queueType, spec.mightBlock))
    def realtime = withPriority(Realtime)
    def high = withPriority(High)
    def low = withPriority(Low)
    def normal = withPriority(Normal)
    def background = withPriority(Background)
    
    def ofType(queueType: QueueType) = 
      respec(ExecutionQueueSpec(spec.priority, queueType, spec.mightBlock))
    def serial = ofType(QueueType.Serial)
    def concurrent = ofType(QueueType.Concurrent)
    
    def mightBlock(might: Boolean) = 
      respec(ExecutionQueueSpec(spec.priority, spec.queueType, might))
    def mightBlock: ExecutionQueueGenerator = mightBlock(true)
    def nonblocking = mightBlock(false)
    
    private def respec(newSpec: ExecutionQueueSpec) = 
      new ExecutionQueueGenerator(creator, newSpec)
  }
}



sealed trait Priority extends Ordered[Priority] {
  private[executionqueue] def javaPriority: Int
  override def toString = javaPriority.toString
  override def hashCode = javaPriority.hashCode
  override def equals(other: Any) = other match {
    case p: Priority => p.javaPriority == javaPriority
    case _ => false
  }
  override def compare(other: Priority) =
    javaPriority.compare(other.javaPriority)
}
object Priority {
  object Realtime   extends Priority { override val javaPriority = 10 }
  object High       extends Priority { override val javaPriority = 8 }
  val Medium = Normal
  object Normal     extends Priority { override val javaPriority = 5 }
  object Low        extends Priority { override val javaPriority = 3 }
  object Background extends Priority { override val javaPriority = 1 }
  val Min = Background
  val Max = Realtime
}

object QueueType extends Enumeration {
  type QueueType = Value
  val Serial, Concurrent = Value
}


trait Executor {
  def priority: Priority
  def execute(f: => Unit): Unit
  def shutdown: Unit
}
case class ExecutorSpec(priority: Priority, mightBlock: Boolean)
trait ExecutorFactory {
  def createExecutor(label: String, spec: ExecutorSpec): Executor
}
