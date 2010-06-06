package ch.inventsoft.scalabase.process.cps

import scala.util.continuations._
import ch.inventsoft.scalabase.log._
import ch.inventsoft.scalabase.time._
import ch.inventsoft.scalabase.executionqueue._
import ch.inventsoft.scalabase.process._
import ExecutionQueues._


final object ProcessCps extends Log {
  
  def spawnProcess(executionQueue: ExecutionQueue)(body: => Any @processCps): Process = {
    ProcessImpl(body, None, executionQueue)
  }
  
  def spawnChildProcess(executionQueue: ExecutionQueue, kind: ChildType, body: => Any @processCps): Process @processCps = {
    val caller = self
    val process = ProcessImpl(body, Some((kind,caller)), executionQueue)
    new AddChildProcessAction(process).cps
    process
  }
    
  def receive[T](f: PartialFunction[Any,T @processCps]): T @processCps = {
    new NestedReceiveAction(f).cps
  }

  def receiveWithin[T](timeout: Duration)(f: PartialFunction[Any,T @processCps]): T @processCps = {
    new NestedReceiveWithinAction(timeout, f).cps
  }
  
  def receiveNoWait[T](f: PartialFunction[Any,T @processCps]): T @processCps =
    receiveWithin(immediately)(f)

  def self: Process @processCps = {
      new SelfAction().cps
  }
  
  def watch(toWatch: Process): Unit @processCps = {
    val me = self
    toWatch ! RegisterWatcher(me)
  }
  
  type processCps = cps[ProcessAction[Any]]
  
  def noop: Unit @processCps = ProcessAction.noop.cps
  def valueToCps[A](value: A): A @processCps = ProcessAction(value).cps 
  
  
  
  /**
   * Advanced functions, that should only be used in special cases, mostly inside frameworks.
   */
  object useWithCare {
    def currentProcess: Option[Process] = _currentProcess.get
  }  
  
  
  //Implementations
  
  private type ProcessActionResponse[T] = Function2[T,ProcessState,Unit]
  private val ignoreProcessActionResponse: ProcessActionResponse[Any] = (reponse: Any, state: ProcessState) => ()
  
 
  sealed trait ProcessAction[T] {
    private[ProcessCps] def run(state: ProcessState, respond: ProcessActionResponse[T], executor: ProcessExecutor): Unit

    private[ProcessCps] def cps: T @processCps = {
      shift { continuation: (T => ProcessAction[Any]) =>
        flatMap(continuation)
      }
    }
    private[ProcessCps] def flatMap[A](next: T => ProcessAction[A]): ProcessAction[A] = {
      new ChainedProcessAction(this, next)
    }
  }
  private class ChainedProcessAction[T,A](firstAction: ProcessAction[T], next: T => ProcessAction[A]) extends ProcessAction[A] {
    override def run(state: ProcessState, respond: ProcessActionResponse[A], executor: ProcessExecutor) = {
      val responseToFirst = (result: T, s1: ProcessState) => {
        val nextAction = next(result)
        executor.executeStep(s1, respond) { (s2, respond2) =>
          nextAction.run(s2, respond2, executor)
        }
      }
      firstAction.run(state, responseToFirst, executor)
    }
  }
  private object ProcessAction {
    def apply[T](f: => T) = {
      new ProcessAction[T] {
        override def run(state: ProcessState, respond: ProcessActionResponse[T], executor: ProcessExecutor) = {
          val value = f
          respond(value, state)
        }
      }
    }
    def noop = {
      new ProcessAction[Unit] {
        override def run(state: ProcessState, respond: ProcessActionResponse[Unit], executor: ProcessExecutor) = {
          val value = ()
          respond(value, state)
        }
      }
    }
  }

  private trait ReceiveWithoutTimeout[T] extends ProcessAction[T] {
    private[ProcessCps] override def run(state: ProcessState, respondToCaller: ProcessActionResponse[T], executor: ProcessExecutor) = {
      state.lookForExistingMessage(filter) match {
        case (Some(msg), pending) =>
          respondReceive(state, respondToCaller, executor)(msg, pending)
        case (None, pending) =>
          val capture = new PartialFunction[Any,Unit] {
            override def isDefinedAt(msg: Any) = filter(msg)
            override def apply(msg: Any) = executor.executeStep(state, respondToCaller) { (state, respondToCaller) =>
              respondReceive(state, respondToCaller, executor)(msg, pending)
            }
          }
          state.messageQueue.captureMessage(capture)
      }
    }
    protected[this] def filterForUs(msg: Any): Boolean
    protected[this] def filter(msg: Any) = msg == InterruptMessage || filterForUs(msg)
    protected[this] def respondReceive(state: ProcessState, respondToCaller: ProcessActionResponse[T], executor: ProcessExecutor)(msg: Any, pending: List[Any]): Unit
  }
  private val timer = new java.util.Timer(true)
  private trait ReceiveWithTimeout[T] extends ProcessAction[T] {
    val timeout: Duration
    
    private[ProcessCps] override def run(state: ProcessState, respondToCaller: ProcessActionResponse[T], executor: ProcessExecutor) = {
      state.lookForExistingMessage(filter) match {
        case (Some(msg), pending) => respondReceive(state, respondToCaller, executor)(msg, pending)
        case (None, pending) =>
          if (timeout.isPositive) {
            class Answerer {
              private var done = false
              def apply(timeout: Boolean, msg: Any) = synchronized {
                if (!done) {
                  val exec = (s2: ProcessState, respondToCaller: ProcessActionResponse[T]) => {
                    respondReceive(s2, respondToCaller, executor)(msg, pending)
                  }
                  executor.executeStep(state, respondToCaller)(exec)
                  done = true
                }
              }
            }
            val answerer = new Answerer
            val timeoutTask = new java.util.TimerTask {
              override def run = {
                answerer(true, Timeout)
              }
            }
            timer.schedule(timeoutTask, timeout.amount(Milliseconds))
            val capture = new PartialFunction[Any,Unit] {
              override def isDefinedAt(msg: Any) = filter(msg)
              override def apply(msg: Any) = {
                timeoutTask.cancel
                answerer(false, msg)
              }
            }
            state.messageQueue.captureMessage(capture)
          } else {
            respondReceive(state, respondToCaller, executor)(Timeout, pending)
          }
      }
    }
    protected[this] def filterForUs(msg: Any): Boolean
    protected[this] def filter(msg: Any) = msg == InterruptMessage || filterForUs(msg)
    protected[this] def respondReceive(state: ProcessState, respondToCaller: ProcessActionResponse[T], executor: ProcessExecutor)(msg: Any, pending: List[Any]): Unit
  }
  //TODO remove if change proves stable
  private trait UnnestedRespondAction[T] extends ProcessAction[T] {
    protected[this] val processingFun: PartialFunction[Any,T]
    protected[this] def filterForUs(msg: Any) = processingFun.isDefinedAt(msg)
    protected[this] def respondReceive(oldState: ProcessState, respondToCaller: ProcessActionResponse[T], executor: ProcessExecutor)(msg: Any, pending: List[Any]) = {
      val state = oldState.withPending(pending)
      log.trace("{} received message '{}'", state.process.external, msg)
      val response = processingFun(msg)
      respondToCaller(response, state)
    }
  }
  private trait NestedRespondAction[T] extends ProcessAction[T] {
    protected[this] val processingFun: PartialFunction[Any,T @processCps]
    protected[this] def filterForUs(msg: Any) = processingFun.isDefinedAt(msg)
    protected[this] def respondReceive(oldState: ProcessState, respondToCaller: ProcessActionResponse[T], executor: ProcessExecutor)(msg: Any, pending: List[Any]) = {
      val state = oldState.withPending(pending)
      log.trace("{} received message '{}'", state.process.external, msg)
      val exec = new NestedExecution(processingFun, msg, executor)
      executor.executeStep(state, respondToCaller)(exec)
    }
  }
  private class NestedExecution[T](processingFun: PartialFunction[Any,T @processCps], msg: Any, executor: ProcessExecutor) extends Function2[ProcessState, ProcessActionResponse[T],Unit] {
    override def apply(state: ProcessState, respond: ProcessActionResponse[T]) = {
      val a: ProcessAction[Any] = reset {
        val result = processingFun(msg)
        new LastProcessAction(result)
      }
      a.run(state, respond.asInstanceOf[Function2[Any,ProcessState,Unit]], executor)
    }
  }
  private class LastProcessAction(result: Any) extends ProcessAction[Any] {
    private[ProcessCps] override def run(state: ProcessState, respond: ProcessActionResponse[Any], executor: ProcessExecutor) = {
      respond(result, state)
    }
  }
  
  private class RespondNestedAction[A](value: A, otherRespond: ProcessActionResponse[A]) extends ProcessAction[Unit] {
    private[ProcessCps] override def run(state: ProcessState, respond: ProcessActionResponse[Unit], executor: ProcessExecutor) = {
      otherRespond(value, state)
      respond((), state)
    }
  }
  
  private class ReceiveAction[T](protected[this] val processingFun: PartialFunction[Any,T]) extends UnnestedRespondAction[T] with ReceiveWithoutTimeout[T]
  private class NestedReceiveAction[T](protected[this] val processingFun: PartialFunction[Any,T @processCps]) extends NestedRespondAction[T] with ReceiveWithoutTimeout[T]
  private class ReceiveWithinAction[T](val timeout: Duration, protected[this] val processingFun: PartialFunction[Any,T]) extends UnnestedRespondAction[T] with ReceiveWithTimeout[T]
  private class NestedReceiveWithinAction[T](val timeout: Duration, protected[this] val processingFun: PartialFunction[Any,T @processCps]) extends NestedRespondAction[T] with ReceiveWithTimeout[T]
  
  private class SelfAction extends ProcessAction[Process] {
    private[ProcessCps] override def run(state: ProcessState, respond: ProcessActionResponse[Process], executor: ProcessExecutor) = {
      val process = state.process.external
      respond(process, state)
      ()
    }
  }
  private val _currentProcess = new ThreadLocal[Option[Process]] {
    protected override val initialValue = None
  }
  
  private class AddChildProcessAction(child: Process) extends ProcessAction[Unit] {
    private[ProcessCps] override def run(state: ProcessState, respond: ProcessActionResponse[Unit], executor: ProcessExecutor) = {
      val children = child :: state.children
      respond((), state.withChildren(children))
      ()
    }
  }
  
  private def shutdown = {
    //TODO reactivate?
//    log.info("All processes terminated, shutting down the ExecutionQueues.")
//    ExecutionQueues.shutdownQueues
  }
  
  private val pidGenerator = new java.util.concurrent.atomic.AtomicLong
  private val runningProcessCount = new java.util.concurrent.atomic.AtomicLong
  
  private object ProcessImpl {
    def apply(body: => Any @processCps, parentChild: Option[(ChildType,Process)], executionQueue: ExecutionQueue): Process = {
      val terminationManager = parentChild match {
        case Some((Monitored,parent)) =>
          new MonitoredChildTerminationManager(parent)
        case Some((NotMonitored,parent)) =>
          new NotMonitoredChildTerminationManager(parent)
        case Some((Required,parent)) =>
          new RequiredChildTerminationManager(parent)
        case None =>
          new RootTerminationManager
      }
      val process = new ProcessImpl(body, terminationManager, executionQueue)
      process.external 
    }

  }
  private class ProcessImpl private[this](final terminationManager: TerminationManager, final executionQueue: ExecutionQueue) extends Process {
    final val pid = pidGenerator.incrementAndGet()
    private[this] final val messageQueue = new MessageQueueSync[Any]  //Uses less memory, but does not allow multiple senders to enqueue concurrently
    //Alternatives:
    //    private[this] final val messageQueue = new MessageQueueReadWriteLock[Any]   //Uses more memory but allows concurrent insertions
    private[this] final val systemQueue = new java.util.concurrent.ConcurrentLinkedQueue[SystemMessage]
    
    def this(body: => Any @processCps, terminationManager: TerminationManager, executionQueue: ExecutionQueue) {
      this(terminationManager, executionQueue)
      val a: ProcessAction[Any] = reset {
        firstFun.cps
        body
        lastFun
      }
      a.run(new ProcessState(this, Nil, messageQueue, systemQueue, Nil, Nil), ignoreProcessActionResponse, executor)
    }

    def !(msg: Any): Unit = {
      msg match {
        case msg: SystemMessage => 
          log.trace("{} enqueues system message '{}'", external, msg)
          systemQueue add msg
          if (msg.isInterrupting) messageQueue enqueue InterruptMessage
        case msg =>
          log.trace("{} enqueues message '{}'", external, msg)
          messageQueue enqueue msg
      }
    }

    override def toString = "Process-"+pid
    
     
    private[this] def firstFun = new ProcessAction[Any] {
      private[ProcessCps] override def run(state: ProcessState, respond: ProcessActionResponse[Any], executor: ProcessExecutor) = {
        executor.executeStep[Unit](state, respond) { (state, respond) =>
          runningProcessCount.incrementAndGet()
          log.debug.ifEnabled {
            terminationManager.parent match {
              case Some(parent) => log.debug("Started {} (child of {})", ProcessImpl.this, parent)
              case None => log.debug("Started {}", ProcessImpl.this)
            }
          }
          respond((), state)
        }
      }
    }    
    private[this] def lastFun = new ProcessAction[Any] {
      private[ProcessCps] override def run(state: ProcessState, respond: ProcessActionResponse[Any], executor: ProcessExecutor) = {
        respond((), state)
        terminate(ProcessExit(external), state)
      }
    }    
    
    private[this] def terminate(reason: ProcessEnd, s: ProcessState): Unit = {
      val lastState = processSystemQueue(s, (_,_,_
) => ()) //catch up with registrations and terminated childs

      logTermination(reason)
      terminationManager.handleTermination(this, reason)
      informChildrenOfTermination(reason, lastState.children.reverse)
      informWatchersOfTermination(reason, lastState.watcher.reverse)
      external.terminated(this)
      
      if (runningProcessCount.decrementAndGet() == 0) shutdown
    }    
    
    private[this] def logTermination(reason: ProcessEnd) = reason match {
      case ProcessCrash(p, t) =>
        terminationManager.parent match {
          case Some(parent) => 
	    log.debug("{} crashed with {}: {}. Will inform {}.", p, t.getClass.getName, t.getMessage, parent)
          case None => log.error("{} crashed with {}", p, t)
        }
      case ProcessExit(p) =>
        log.debug("{} terminated normally", p)
      case ProcessKill(p, by, reason) =>
        log.debug("{} was killed by {} because of {}", p, by, reason)
    }
    private[this] def informWatchersOfTermination(reason: ProcessEnd, watchers: List[Process]): Unit = watchers.foreach { watcher =>
      log.trace("Informing {} of termination of watched {}", watcher, external)
      watcher ! reason
    }
    private[this] def informChildrenOfTermination(reason: ProcessEnd, children: List[Process]): Unit = reason match {
      case ProcessExit(_) =>
        //normal exit, don't inform children
      case ProcessKill(_, by,reason) =>
        children.filterNot(_ == by).foreach { child =>
          log.trace("Informing {} of abnormal termination (kill) of its parent {}", child, external)
          child ! Kill(ProcessImpl.this, reason)
        }
      case ProcessCrash(_, reason) =>
        children.foreach { child =>
          log.trace("Informing {} of abnormal termination (crash) of its parent {}", child, external)
          child ! Kill(ProcessImpl.this, reason)
        }
    }
    
    private[this] def preStep(state: ProcessState): ProcessState = {
      processSystemQueue(state, (from,s,reason) => throw new KillProcessException(from.external,s,reason))
    }
    private[this] def processSystemQueue(state: ProcessState, killHandler: Function3[ProcessImpl,ProcessState,Any,Unit]): ProcessState = {
      val msg = state.systemQueue.poll
      if (msg != null) {
        val newState = msg match {
          case RegisterWatcher(watcher) =>
            log.debug("{} has {} registered as a watcher", external, watcher);
            state.withWatcher(watcher :: state.watcher)
          case ChildTerminated(child) =>
            log.trace("{} received notification about terminated child {}", external, child)
            state.withChildren(state.children.filterNot(_ == child))
          case Kill(from, reason) => 
            log.trace("{} received Kill message from {}", external, from)
            killHandler(from, state, reason)
            state
        }
        processSystemQueue(newState, killHandler)
      } else state        
    }

    private[this] val executor = new ProcessExecutor {
      private[this] def exec[T](state: ProcessState, respond: ProcessActionResponse[T])(f: (ProcessState, ProcessActionResponse[T]) => Unit) = {
        try {
          _currentProcess.set(Some(external))
          val newState = preStep(state)
          
          try {
            f(newState, respond)
          } catch {
            case t => terminate(ProcessCrash(external, t), newState)
          }
        } catch {
          case KillProcessException(by, state, reason) => terminate(ProcessKill(external, by, reason), state) 
          case t => terminate(ProcessCrash(external, t), state)
        } finally {
          _currentProcess.set(None)
        }
      }
      override def executeStep[T](state: ProcessState, respond: ProcessActionResponse[T])(f: (ProcessState, ProcessActionResponse[T]) => Unit) = {
        executionQueue <-- {
          exec(state, respond)(f)
        }
      }
    }
    
    final val external = new ProcessExternal(this)
  }
  
  private trait ProcessExecutor {
    def executeStep[T](state: ProcessState, respond: ProcessActionResponse[T])(f: (ProcessState, ProcessActionResponse[T]) => Unit): Unit
  }

  private trait TerminationManager {
    def isChild: Boolean = childType.isDefined 
    def childType: Option[ChildType]
    def parent: Option[Process]
    def handleTermination(process: ProcessImpl, reason: ProcessEnd)
  }
  private class RootTerminationManager extends TerminationManager {
    override def childType = None
    override def parent = None
    override def handleTermination(process: ProcessImpl, reason: ProcessEnd) = ()
  }
  private trait ChildTerminationManager extends TerminationManager {
    protected[this] val parentProcess: Process
    override def parent = Some(parentProcess)
    override def handleTermination(process: ProcessImpl, reason: ProcessEnd) = {
      parentProcess ! ChildTerminated(process)
    }
  }
  private class MonitoredChildTerminationManager(protected[this] override val parentProcess: Process) extends ChildTerminationManager {
    override def childType = Some(Monitored)
    override def handleTermination(process: ProcessImpl, reason: ProcessEnd) = {
      super.handleTermination(process, reason)
      parentProcess ! reason
    }
  }
  private class NotMonitoredChildTerminationManager(protected[this] override val parentProcess: Process) extends ChildTerminationManager {
    override def childType = Some(NotMonitored)
  }
  private class RequiredChildTerminationManager(protected[this] override val parentProcess: Process) extends ChildTerminationManager {
    override def childType = Some(Required)
    override def handleTermination(process: ProcessImpl, reason: ProcessEnd) = {
      super.handleTermination(process, reason)
      reason match {
        case ProcessCrash(_, exception) =>
          //Crash -> Kill the parent
          parentProcess ! Kill(process, exception)
        case ProcessKill(_, `parentProcess`, _) =>
          //Killed by parent, don't do anything
        case ProcessKill(_, by, reason) =>
          //Killed by child, kill parent
          parentProcess ! Kill(process, reason)
        case ProcessExit(_) =>
          //normal exit
      }
    }
  }
  
  private sealed trait SystemMessage {
    def isInterrupting: Boolean
  }
  private case class Kill(killer: ProcessImpl, reason: Any) extends SystemMessage {
    val isInterrupting = true
  }
  private case class ChildTerminated(child: ProcessImpl) extends SystemMessage {
    val isInterrupting = false
  }
  private case class RegisterWatcher(watcher: Process) extends SystemMessage {
    val isInterrupting = false
  }
  private object InterruptMessage

  private case class KillProcessException(by: Process, state: ProcessState, reason: Any) extends RuntimeException
  
  private case class ProcessState(process: ProcessImpl, pendingMessages: List[Any], messageQueue: MessageQueue[Any], systemQueue: java.util.Queue[SystemMessage], children: List[Process], watcher: List[Process]) {
    def withPending(pendingMessages: List[Any]) = ProcessState(process, pendingMessages, messageQueue, systemQueue, children, watcher) 
    def withChildren(children: List[Process]) = ProcessState(process, pendingMessages, messageQueue, systemQueue, children, watcher)
    def withWatcher(watcher: List[Process]) = ProcessState(process, pendingMessages, messageQueue, systemQueue, children, watcher)

    def lookForExistingMessage(lookFor: (Any) => Boolean): (Option[Any], List[Any]) = {
      import ch.inventsoft.scalabase.extcol.ListUtil._

      removeFirst(pendingMessages, lookFor) match {
        case Some((msg, pending)) => (Some(msg), pending)
        case None => {
          val drained = messageQueue.drain.reverse
          removeFirst(drained, lookFor) match {
            case Some((msg, drained)) => (Some(msg), pendingMessages ::: drained)
            case None => (None, pendingMessages ::: drained)
          }
        }
      }
    }
  }
  

  /**
   * External view onto a process. Allows garbage collection of the whole internal state of the
   * process when it terminates. All that remains is a no-op hull.
   */
  private class ProcessExternal extends Process {
    @volatile private[this] var reference: Either[Process,Long] = Right(-1)

    def this(process: Process) = {
      this()
      reference = Left(process)
    }
    private[ProcessCps] def terminated(process: ProcessImpl) = {
      reference = Right(process.pid)
    }
      
    override def !(msg: Any) = {
      val ref = reference
      if (ref.isLeft) reference.left.get ! msg
      else msg match {
	case msg: SystemMessage => ()
	case msg => log.debug("Message {} discarded because receiving process is already terminated", msg)
      }
    }
    override def toString = {
      reference match {
	case Left(process) => "<"+process.toString+">"
	case Right(id) => "<Process-"+id+">"
      }
    }
  }
}
