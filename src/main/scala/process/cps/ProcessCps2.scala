package ch.inventsoft.scalabase.process.cps

import scala.util.continuations._
import ch.inventsoft.scalabase.log._
import ch.inventsoft.scalabase.time._
import ch.inventsoft.scalabase.executionqueue._
import ch.inventsoft.scalabase.process._
import ExecutionQueues._


object ProcessCps extends Log {

  def spawnProcess(executionQueue: ExecutionQueue)(body: => Any @processCps): Process = {
    ProcessImpl.root(executionQueue, body)
  }
  def spawnChildProcess(executionQueue: ExecutionQueue, kind: ChildType, body: => Any @processCps): Process @processCps = {
    new SpawnChildProcessAction(executionQueue, kind, body).cps
  }

  def self = SelfProcessAction.cps
  def receive[T](fun: PartialFunction[Any,T @processCps]) = new ReceiveProcessAction(fun).cps
  def receiveWithin[T](timeout: Duration)(fun: PartialFunction[Any,T @processCps]) = {
    if (timeout.isZero) new ReceiveNoWaitProcessAction(fun).cps
    else new ReceiveWithinProcessAction(fun, timeout).cps
  }
  def receiveNoWait[T](fun: PartialFunction[Any,T @processCps]) = new ReceiveNoWaitProcessAction(fun).cps
  
  def watch(toWatch: Process) = new WatchProcessAction(toWatch).cps

  object useWithCare {
    def currentProcess: Option[Process] = ProcessImpl.currentProcess
  }


  def valueToCps[A](value: A) = new ValueProcessAction(value).cps
  def noop: Unit @processCps = NoopAction.cps

  type processCps = cps[ProcessAction[Any]]

  /**
   * Part of a process execution
   */
  sealed trait ProcessAction[T] {
    private[ProcessCps] def run(state: ProcessState, continue: ContinueProcess[T], flow: ProcessFlowHandler)
    private[ProcessCps] def cps: T @processCps = shift { cont: (T => ProcessAction[Any]) =>
      flatMap(cont)
    }
    private[ProcessCps] def flatMap[A](next: T => ProcessAction[A]): ProcessAction[A] = {
      new ChainedProcessAction(this, next)
    }
  }

  private type ContinueProcess[T] = Function2[T,ProcessState,Unit]
  private trait ProcessFlowHandler {
    /**
     * Execute a step. Might throw exceptions that must be presented to #exception()
     */
    def step(state: ProcessState): ProcessState
    /**
     * Handle an unhandled exception by the process. The process will not continue.
     */
    def exception(finalState: ProcessState, e: Throwable): Unit
    /**
     * Execute the 'toexec' in the same executor as the process runs. Must not be used
     * to start concurrent tasks but only to continue the execution of the process.
     */
    def spawn(toexec: => Unit): Unit
  }

  /**
   * Chains together two ProcessActions (flatMap)
   */
  private class ChainedProcessAction[T,A](first: ProcessAction[T], second: T => ProcessAction[A]) extends ProcessAction[A] {
    override def run(state: ProcessState, continue: ContinueProcess[A], flow: ProcessFlowHandler) = {
      val contToFirst = (result: T, stateAfterFirst: ProcessState) => {
        try {
          val secondAction = second(result)
          val stateAfterFirst2 = flow.step(stateAfterFirst)
          try {
            secondAction.run(stateAfterFirst2, continue, flow)
          } catch {
            case t => flow.exception(stateAfterFirst2, t)
          }
        } catch {
          case t => flow.exception(stateAfterFirst, t)
        }
      }
      try {
        val state2 = flow.step(state)
        try {
          first.run(state2, contToFirst, flow)
        } catch { 
          case t => flow.exception(state2, t)
        }
      } catch {
        case t => flow.exception(state, t)
      }
    }
  }

  /**
   * ProcessAction with exception and flow handling
   */
  private trait BodyProcessAction[T] extends ProcessAction[T] {
    protected[this] def body(state: ProcessState, continue: ContinueProcess[T])
    private[ProcessCps] final def run(state: ProcessState, continue: ContinueProcess[T], flow: ProcessFlowHandler) = {
      try {
        val state2 = flow.step(state)
        try {
          body(state2, continue)
        } catch {
          case t => flow.exception(state2, t) 
        }
      } catch {
        case t => flow.exception(state, t)
      }
    }
  }

  /**
   * Support for execution nested CPS'es
   */
  private trait NestingSupport[T] {
    protected[this] def execNested(state: ProcessState, continue: ContinueProcess[T], flow: ProcessFlowHandler)(result: => T @processCps): Unit = {
      val action: ProcessAction[Any] = reset {
        noop
        val r: T = result
        new ValueProcessAction[Any](r)
      }
      try {
        val state2 = flow.step(state)
        try {
          action.run(state2, continue.asInstanceOf[ContinueProcess[Any]], flow)
        } catch {
          case t => flow.exception(state2, t)
        }
      } catch {
        case t => flow.exception(state, t)
      }
    }
  }

  /**
   * ProcessAction representing a simple (no-cps) value.
   */
  private class ValueProcessAction[T](value: => T) extends BodyProcessAction[T] {
    override def body(state: ProcessState, continue: ContinueProcess[T]) = {
      continue(value, state)
    }
  }
  private object NoopAction extends ValueProcessAction(())
  
  /**
   * ProcessAction returning the process itself.
   */
  private object SelfProcessAction extends ProcessAction[Process] {
    override def run(state: ProcessState, continue: ContinueProcess[Process], flow: ProcessFlowHandler) = {
      continue(state.process, state)
    }
  }

  /**
   * ProcessAction spawning a child process.
   */
  private class SpawnChildProcessAction(executionQueue: ExecutionQueue, kind: ChildType, body: => Any @processCps) extends ProcessAction[Process] {
    override def run(state: ProcessState, continue: ContinueProcess[Process], flow: ProcessFlowHandler) = {
      val me = state.process 
      val child = ProcessImpl.child(me, kind, executionQueue, body)
      continue(child, state.copy(children = child :: state.children))
    }
  }

  /**
   * Message box for many senders and a single consumer.
   */
//TODO idea: we could only spawn a checker if a capture is active
// - add a second paramter to the inQueue (alreadyChecking, capturePossiblyActive)
  private class MessageBox[T](checkExec: ExecutionQueue) {
    import java.util.concurrent.atomic._
    import ch.inventsoft.scalabase.extcol.ListUtil._
    type Capture = PartialFunction[T,Unit]
    private[this] case class SetCapture(capture: Capture)
    private[this] case class ForceCapture(fun: Function1[Capture,Unit])

    //unprocessed msgs (in reverse order)
    private[this] val inQueue = new AtomicMarkableReference[List[Any]](Nil, false)
    @volatile private[this] var capture: Option[Capture] = None
    //msgs already checked by capture (in reverse order)
    @volatile private[this] var messages: List[T] = Nil

    /**
     * Enqueue a new message.
     * Does complete fast, does never block. The amount of code executed in the caller thread
     * is minimal. No capture check is done in this thread.
     */
    def enqueue(msg: T) = {
      enqueue_internal(msg)
    }

    /**
     * Register a new capture for the message box.
     * Does replace the previously registered capture.
     */
    def setCapture(capture: Capture) = {
      enqueue_internal(SetCapture(capture))
    }

    /**
     * Cancel the currently registered capture.
     * The 'fun' will be called with the deregistered capture. If no capture is registered
     * then this method is a no-op, 'fun' will not be called.
     */
    def cancelCapture(fun: Function1[Capture,Unit]) = {
      enqueue_internal(ForceCapture(fun))
    }

    private[this] def enqueue_internal(msg: Any): Unit = {
      val mark = new Array[Boolean](1)
      val q = inQueue.get(mark)
      val nq = msg :: q
      if (mark(0)) {
        //checker is already scheduled or running
        if (!inQueue.compareAndSet(q, nq, true, true)) enqueue_internal(msg) //retry
      } else {
        //checker is not running and not scheduled
        if (inQueue.compareAndSet(q, nq, false, true)) checkExec <-- check
        else enqueue_internal(msg) //retry
      }
    }

    /**
     * Guarantees:
     * - only active once ('synchronized')
     * - sees every msg without external delay
     * - calls processMsgs for every message
     */
    private[this] def check = {
      def emptyOrContinue(processed: List[Any], processedLen: Int): Unit = {
        if (!inQueue.compareAndSet(processed, Nil, true, false)) {
          //queue has changed since we checked it
          // => process the newly added messages
          val p2 = inQueue.getReference
          val len_p2 = p2.length
          val newMsgs = p2.take(len_p2 - processedLen)
          processMsgs(newMsgs)
          emptyOrContinue(p2, len_p2)
        }
      }

      val toProcess = inQueue.getReference
      processMsgs(toProcess)
      emptyOrContinue(toProcess, toProcess.length)
    }
    private[this] def processMsgs(toProcess: List[Any]) = { //only active in one thread
      def process(msgs: List[Any], capture: Option[Capture], messages: List[T]): (Option[Capture], List[T]) = msgs match {
        case Nil =>
          (capture, messages)
        case SetCapture(capture) :: rest =>
          removeLast(messages, capture.isDefinedAt _) match {
            case Some((msg, nm)) => //msg matching capture
              capture.apply(msg)
              process(rest, None, nm)
            case None =>            //register capture
              process(rest, Some(capture), messages)
          }
        case ForceCapture(fun) :: rest =>
          capture.foreach(c => fun(c))
          process(rest, None, messages)
        case m :: rest =>
          val msg: T = m.asInstanceOf[T]
          capture match {
            case Some(capture) if capture.isDefinedAt(msg) =>
              // msg matching capture
              capture.apply(msg)
              process(rest, None, messages)
            case unmatched =>
              process(rest, capture, msg :: messages)
          }
      }
      val (cap, coll) = process(toProcess.reverse, capture, messages)
      capture = cap
      messages = coll
    }
  }

  /**
   * ProcessAction receiving a message matching a partial function.
   */
  private class ReceiveProcessAction[T](fun: PartialFunction[Any,T @processCps]) extends ProcessAction[T] with ReceiveSupport[T] {
    override def run(state: ProcessState, continue: ContinueProcess[T], flow: ProcessFlowHandler) = {
      val capture = createCapture(fun, state, continue, flow)
      state.messageBox.setCapture(capture)
    }
  }
  /**
   * ProcessAction receving a message matching a partial function within a certain timeframe.
   */
  private class ReceiveWithinProcessAction[T](fun: PartialFunction[Any,T @processCps], timeout: Duration) extends ProcessAction[T] with ReceiveSupport[T] {
    override def run(state: ProcessState, continue: ContinueProcess[T], flow: ProcessFlowHandler) = {
      val capture = createCapture(fun, state, continue, flow)
      val timeoutTask = new java.util.TimerTask {
        override def run = state.messageBox.cancelCapture { cap => 
          if (cap == capture) cap(Timeout)
        }
      }
      state.messageBox.setCapture(capture)
      timer.schedule(timeoutTask, timeout.amountAs(Milliseconds))
    }
  }
  /**
   * ProcessAction receiving a matching message if already present, else Timeout.
   */
  private class ReceiveNoWaitProcessAction[T](fun: PartialFunction[Any,T @processCps]) extends ProcessAction[T] with ReceiveSupport[T] {
    override def run(state: ProcessState, continue: ContinueProcess[T], flow: ProcessFlowHandler) = {
      val capture = createCapture(fun, state, continue, flow)
      state.messageBox.setCapture(capture)
      state.messageBox.cancelCapture { cap =>
        if (cap == capture) cap(Timeout)
      }
    }
  }
  /** common functions for all receive actions */
  private trait ReceiveSupport[T] extends NestingSupport[T] {
    protected[this] def createCapture(fun: PartialFunction[Any,T @processCps], state: ProcessState, continue: ContinueProcess[T], flow: ProcessFlowHandler, alsoEquals: Option[Any] = None): PartialFunction[Any, Unit] = {
      new PartialFunction[Any,Unit] {
        override def isDefinedAt(msg: Any) = {
          fun.isDefinedAt(msg) || msg==ManagementMessage
        }
        override def apply(msg: Any) = {
          if (msg == ManagementMessage) {
            try {
              val state2 = flow.step(state)
              try {
                val capture = if (state2 == state) this
                              else createCapture(fun, state2, continue, flow)
                state2.messageBox.setCapture(capture)
              } catch {
                case t => flow.exception(state2, t)
              }
            } catch {
              case t => flow.exception(state, t)
            }
          } else {
            execNested(state, continue, flow) { fun(msg) }
          }
        }
        override def equals(other: Any) = {
          //Extended equals for matching of replaced captured (see receiveWithin)
          other == this || (alsoEquals.isDefined && alsoEquals.get == other)
        }
      }
    }
  }
  private val timer = new java.util.Timer(true)

  /**
   * Register us as a watcher to another process.
   */
  private class WatchProcessAction(toWatch: Process) extends ProcessAction[Unit] {
    override def run(state: ProcessState, continue: ContinueProcess[Unit], flow: ProcessFlowHandler) = {
      val watcher = state.process
      val s = toWatch match {
        case toWatch: ProcessInternal =>
          toWatch.addWatcher(watcher)
          state.copy(watched = toWatch :: state.watched)
        case _ =>
          log.error("Unknown Process type for {}. Cannot add watcher {}", toWatch, watcher)
          state
      }
      continue((), s)
    }
  }

  /** "Management" view onto a process. All declared methods behave like .!() (async, no exeception) */
  private trait ProcessInternal extends Process {
    /** Forcefully stop the process on the next possible location */
    def kill(killer: ProcessInternal, originalKiller: Process, reason: Throwable): Unit
    
    def removeChild(child: ProcessInternal): Unit
    /** Executes the action on every child of the process */
//    def foreachChild(action: ProcessInternal => Unit): Unit

    def addWatcher(watcher: Process): Unit
    def removeWatcher(watcher: Process): Unit
    /** Executes the action on every watcher of the process */
//    def foreachWatcher(action: Process => Unit): Unit
    /** Executes the action on every process this process is watching */
//    def foreachWatched(action: ProcessInternal => Unit): Unit
  }
  
  /**
   * Handler for process termination.
   */
  private trait ProcessListener {
    def onStart(of: ProcessInternal) = ()
    def onNormalTermination(of: ProcessInternal, finalState: ProcessState) = ()
    def onKill(of: ProcessInternal, finalState: ProcessState, by: ProcessInternal, originalBy: Process, reason: Throwable) = ()
    def onException(in: ProcessInternal, finalState: ProcessState, cause: Throwable) = ()
  }
  
  /** Log the normal stop */
  private trait LogNormalStopPL extends ProcessListener {
    override def onNormalTermination(of: ProcessInternal, finalState: ProcessState) = {
      super.onNormalTermination(of, finalState)
      log.debug("{} has finished", of)
    }
  }
  /** Logs the killing of the process */
  private trait LogKillPL extends ProcessListener {
    override def onKill(of: ProcessInternal, finalState: ProcessState, by: ProcessInternal, originalBy: Process, reason: Throwable) = {
      super.onKill(of, finalState, by, originalBy, reason)
      if (by == originalBy) {
        log.info("{} was killed by {} due to {}: {}", of, by, reason.getClass.getSimpleName, reason.getMessage)
      } else {
        log.info("{} was killed by {} because {} crashed with {}: {}", of, by, originalBy, reason.getClass.getSimpleName, reason.getMessage)
      }
    }      
  }
  /** Logs a warning for unexpected crashing processes */
  private trait WarnCrashPL extends ProcessListener {
    override def onException(in: ProcessInternal, finalState: ProcessState, cause: Throwable) = {
      super.onException(in, finalState, cause)
      log.warn("{} crashed with {}: {}", in, cause.getClass.getSimpleName, cause.getMessage)
    }
  }
  /** Logs expectedly crashing processes */
  private trait LogCrashPL extends ProcessListener {
    override def onException(in: ProcessInternal, finalState: ProcessState, cause: Throwable) = {
      super.onException(in, finalState, cause)
      log.debug("{} crashed with {}: {}", in, cause.getClass.getSimpleName, cause.getMessage)
    }
  }
  /** Responsible for informing the watchers about this processes end */
  private trait WatcherSupportPL extends ProcessListener {
    override def onNormalTermination(of: ProcessInternal, finalState: ProcessState) = {
      super.onNormalTermination(of, finalState)
      finalState.watched.foreach(_.removeWatcher(of))
      finalState.watchers.foreach(_ ! ProcessExit(of))
    }
    override def onException(in: ProcessInternal, finalState: ProcessState, cause: Throwable) = {
      super.onException(in, finalState, cause)
      finalState.watched.foreach(_.removeWatcher(in))
      finalState.watchers.foreach(_ ! ProcessCrash(in, cause))
    }
    override def onKill(of: ProcessInternal, finalState: ProcessState, by: ProcessInternal, originalBy: Process, reason: Throwable) = {
      super.onKill(of, finalState, by, originalBy, reason)
      finalState.watched.foreach(_.removeWatcher(of))
      finalState.watchers.foreach(_ ! ProcessKill(of, by, reason))
    }
  }
  /** Kills the children if the process crashes */
  private trait KillChildrenOnNonNormalPL extends ProcessListener {
    override def onException(in: ProcessInternal, finalState: ProcessState, cause: Throwable) = {
      super.onException(in, finalState, cause)
      finalState.children.foreach(_.kill(in, in, cause))
    }
    override def onKill(of: ProcessInternal, finalState: ProcessState, by: ProcessInternal, originalBy: Process, reason: Throwable) = {
      super.onKill(of, finalState, by, originalBy, reason)
      finalState.children.foreach(_.kill(of, originalBy, reason))
    }
  }
  /** Logs the start of a child */
  private trait LogChildPL extends ProcessListener {
    val parent: Process
    override def onStart(of: ProcessInternal) = {
      super.onStart(of)
      log.debug("{} started (child of {})", of, parent)
    }
  }
  /** Kills the parent process if the child crashes or is killed */
  private trait KillParentOnNonNormalPL extends ProcessListener {
    val parent: ProcessInternal
    override def onException(in: ProcessInternal, finalState: ProcessState, cause: Throwable) = {
      super.onException(in, finalState, cause)
      parent.kill(in, in, cause)
    }
    override def onKill(of: ProcessInternal, finalState: ProcessState, by: ProcessInternal, originalBy: Process, reason: Throwable) = {
      super.onKill(of, finalState, by, originalBy, reason)
      if (by != parent) parent.kill(of, originalBy, reason)
    }
  }
  /** Sends ProcessEnd messages to the parent */
  private trait ParentAsWatcherPL extends ProcessListener {
    val parent: Process
    override def onNormalTermination(of: ProcessInternal, finalState: ProcessState) = {
      super.onNormalTermination(of, finalState)
      parent ! ProcessExit(of)
    }
    override def onException(in: ProcessInternal, finalState: ProcessState, cause: Throwable) = {
      super.onException(in, finalState, cause)
      parent ! ProcessCrash(in, cause)
    }
    override def onKill(of: ProcessInternal, finalState: ProcessState, by: ProcessInternal, originalBy: Process, reason: Throwable) = {
      super.onKill(of, finalState, by, originalBy, reason)
      parent ! ProcessKill(of, by, reason)
    }
  }
  /** Removes the child from the parent on process ends (all) */
  private trait RemoveChildFromParentPL extends ProcessListener {
    val parent: ProcessInternal
    override def onNormalTermination(of: ProcessInternal, finalState: ProcessState) = {
      super.onNormalTermination(of, finalState)
      parent.removeChild(of)
    }
    override def onException(in: ProcessInternal, finalState: ProcessState, cause: Throwable) = {
      super.onException(in, finalState, cause)
      parent.removeChild(in)
    }
    override def onKill(of: ProcessInternal, finalState: ProcessState, by: ProcessInternal, originalBy: Process, reason: Throwable) = {
      super.onKill(of, finalState, by, originalBy, reason)
      parent.removeChild(of)
    }
  }


  /** Root Process */
  private object RootProcessListener extends ProcessListener 
          with LogNormalStopPL with LogKillPL with WarnCrashPL
          with WatcherSupportPL with KillChildrenOnNonNormalPL {
    override def onStart(of: ProcessInternal) = {
      log.debug("{} started", of)
    }
  }
  
  /** Monitored Child */
  private class MonitoredChildProcessListener(val parent: ProcessInternal) extends ProcessListener 
          with LogChildPL with LogNormalStopPL with LogKillPL with LogCrashPL 
          with WatcherSupportPL 
          with ParentAsWatcherPL with RemoveChildFromParentPL with KillChildrenOnNonNormalPL
  
  /** Required Child */
  private class RequiredChildProcessListener(val parent: ProcessInternal) extends ProcessListener 
          with LogChildPL with LogNormalStopPL with LogKillPL with WarnCrashPL 
          with WatcherSupportPL
          with RemoveChildFromParentPL with KillParentOnNonNormalPL with KillChildrenOnNonNormalPL 

  /** Not Monitored Child */
  private class NotMonitoredChildProcessListener(val parent: ProcessInternal) extends ProcessListener
          with LogChildPL with LogNormalStopPL with LogKillPL with WarnCrashPL
          with WatcherSupportPL
          with RemoveChildFromParentPL with KillChildrenOnNonNormalPL


  /**
   * State of a process
   */
  private case class ProcessState(process: ProcessImpl, children: List[ProcessInternal], watchers: List[Process], watched: List[ProcessInternal]) {
    def messageBox = process.messageBox
  }

  private val IgnoreProcessResult = (res: Any, state: ProcessState) => ()

  /**
   * Implementation of a process.
   */
  private object ProcessImpl {

    def root(queue: ExecutionQueue, body: => Any @processCps): ProcessInternal = {
      new ProcessImpl(queue, RootProcessListener, body)
    }
    def child(parent: ProcessInternal, childType: ChildType, queue: ExecutionQueue, body: => Any @processCps): ProcessInternal = {
      val tm = childType match {
        case Monitored => new MonitoredChildProcessListener(parent)
        case Required => new RequiredChildProcessListener(parent)
        case NotMonitored => new NotMonitoredChildProcessListener(parent)
      }
      new ProcessImpl(queue, tm, body)
    }

    private val current = new ThreadLocal[Option[Process]] {
      override def initialValue = None
    }
    def currentProcess: Option[Process] = current.get
  }
  private final val pidDealer = new java.util.concurrent.atomic.AtomicLong(0)

  private class ProcessImpl(queue_org: ExecutionQueue, listener: ProcessListener) extends Process with ProcessInternal {
    def this(queue: ExecutionQueue, listener: ProcessListener, body: => Any @processCps) = {
      this(queue, listener)
      val toExecute: ProcessAction[Any] = reset {
        firstFun.cps
        body
        lastFun
      }
      toExecute.run(ProcessState(this, Nil, Nil, Nil), IgnoreProcessResult, flowHandler)
    }

    val pid = pidDealer.incrementAndGet
    //TODO this is very slow, use something more efficient or find a different solution for this
    // 'unsafe' feature
    val queue = new ExecutionQueue {
      private[this] val me = Some(ProcessImpl.this)
      override def execute(f: => Unit) = queue_org <-- {
        ProcessImpl.current.set(Some(ProcessImpl.this))
        try {
          f
        } finally {
          ProcessImpl.current.set(None)
        }
      }
    }
//    val queue = queue_org
    val messageBox: MessageBox[Any] = new MessageBox[Any](queue)
    private[this] val mgmtSteps = new java.util.concurrent.atomic.AtomicReference[List[ProcessState => ProcessState]](Nil)
    private[this] val flowHandler = new ProcessFlowHandler {
      override def step(state: ProcessState) = {
        def removeSteps(count: Int): Unit = {
          val c = mgmtSteps.get
          if (!mgmtSteps.compareAndSet(c, c.take(count)))
            removeSteps(count) //try again
        }

        mgmtSteps.get match {
          case Nil => state
          case actions =>
            val s2 = actions.foldRight(state)((a,s) => a(s))
            if (!mgmtSteps.compareAndSet(actions, Nil)) removeSteps(actions.size)
            s2
        }
      }
      override def exception(finalState: ProcessState, e: Throwable) = e match {
        case KillTheProcess(by, originalBy, reason) =>
          log.trace("{} killed, notifying listeners ({})", external, reason)
          listener.onKill(ProcessImpl.this, finalState, by, originalBy, reason)
        case e =>
          log.trace("{} threw exception {}: {}", external, e.getClass.getSimpleName, e.getMessage)
          listener.onException(ProcessImpl.this, finalState, e)
      }
      override def spawn(toexec: => Unit) = {
        queue <-- toexec
      }
    }

    private[this] def firstFun = new ProcessAction[Any] {
      override def run(state: ProcessState, continue: ContinueProcess[Any], flow: ProcessFlowHandler) {
        listener.onStart(ProcessImpl.this)
        continue((), state)
      }
    }
    private[this] def lastFun = new ProcessAction[Any] {
      override def run(state: ProcessState, continue: ContinueProcess[Any], flow: ProcessFlowHandler) {
        continue((), state) // TODO this is a no-op, can we remove that?
        listener.onNormalTermination(ProcessImpl.this, state)
      }
    }

    override def kill(killer: ProcessInternal, originalKiller: Process, reason: Throwable) = mgmtStep { state =>
      log.trace("{} is killed ({})...", external, reason)
      throw KillTheProcess(killer, originalKiller, reason)
    }
    override def removeChild(child: ProcessInternal) = mgmtStep { state =>
      log.trace("{} remove child {}", external, child)
      val nc = state.children.filterNot(_ == child)
      state.copy(children = nc)
    }
    override def addWatcher(watcher: Process) = mgmtStep { state =>
      log.trace("{} add watcher {}", external, watcher)
      state.copy(watchers = watcher :: state.watchers)
    }
    override def removeWatcher(watcher: Process) = mgmtStep { state =>
      log.trace("{} remove watcher {}", external, watcher)
      val nw = state.watchers.filterNot(_ == watcher)
      state.copy(watchers = nw)
    }

    private[this] def mgmtStep(action: ProcessState => ProcessState): Unit = {
      val steps = mgmtSteps.get
      if (!mgmtSteps.compareAndSet(steps, action :: steps))
        mgmtStep(action)  //try again
      else 
        this ! ManagementMessage //trigger a step if within a receive
    }

    override def !(msg: Any) = messageBox.enqueue(msg)

    override def toString = "<Process-"+pid+">"

    val external: Process = this //TODO let gc collect us we don't have a 'internal' reference anymore
  }

  private case class KillTheProcess(by: ProcessInternal, originalBy: Process, reason: Throwable) extends Exception(reason)
  private object ManagementMessage

}

