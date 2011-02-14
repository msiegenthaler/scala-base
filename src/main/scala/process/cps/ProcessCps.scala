package ch.inventsoft.scalabase
package process
package cps

import scala.util.continuations._
import log._
import time._
import executionqueue._
import process._


/** Process implementation based on delimited continuations */
object ProcessCps extends Log with MessageBoxContainer[Any] {

  def spawnProcess(executionQueue: ExecutionQueue)(body: => Any @process): Process = {
    ProcessImpl.root(executionQueue, body)
  }
  def spawnChildProcess(executionQueue: ExecutionQueue, kind: ChildType, body: => Any @process): Process @process = {
    new SpawnChildProcessAction(executionQueue, kind, body).cps
  }
  def spawnWatcherProcess(executionQueue: ExecutionQueue)(body: => Any @process): Process @process = {
    new SpawnWatcherProcessAction(executionQueue, body).cps
  }
  def spawnWatchedProcess(executionQueue: ExecutionQueue)(body: => Any @process): Process @process = {
    new SpawnWatchedProcessAction(executionQueue, body).cps
  }


  def self = SelfProcessAction.cps
  def receive[T](fun: PartialFunction[Any,T @process]) = new ReceiveProcessAction(fun).cps
  def receiveWithin[T](timeout: Duration)(fun: PartialFunction[Any,T @process]) = {
    if (timeout.isZero) new ReceiveNoWaitProcessAction(fun).cps
    else new ReceiveWithinProcessAction(fun, timeout).cps
  }
  def receiveNoWait[T](fun: PartialFunction[Any,T @process]) = new ReceiveNoWaitProcessAction(fun).cps
  
  def watch(toWatch: Process) = new WatchProcessAction(toWatch).cps

  object useWithCare {
    def currentProcess: Option[Process] = ProcessImpl.currentProcess
  }

  def valueToCps[A](value: A) = new ValueProcessAction(value).cps
  def noop: Unit @process = NoopAction.cps

  type process = cps[ProcessAction[Any]]

  /**
   * Part of a process execution
   */
  sealed trait ProcessAction[T] {
    private[ProcessCps] def run(state: ProcessState, continue: ContinueProcess[T], flow: ProcessFlowHandler)
    private[ProcessCps] def cps: T @process = shift { cont: (T => ProcessAction[Any]) =>
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
    protected def body(state: ProcessState, continue: ContinueProcess[T])
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
    protected def execNested(state: ProcessState, continue: ContinueProcess[T], flow: ProcessFlowHandler)(result: => T @process): Unit = {
      val action: ProcessAction[Any] = reset {
        try {
          val r = result
          new ValueProcessAction[Any](r)
        } catch {
          case x => new ExceptionProcessAction(x)
        }
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
  /**
   * ProcessAction representing an exception
   */
  private class ExceptionProcessAction(exception: Throwable) extends ProcessAction[Any] {
    override def run(state: ProcessState, continue: ContinueProcess[Any], flow: ProcessFlowHandler) = {
      flow.exception(state, exception)
    }
  }
  /**
   * Action that does nothing and returns Unit
   */
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
  private class SpawnChildProcessAction(executionQueue: ExecutionQueue, kind: ChildType, body: => Any @process) extends ProcessAction[Process] {
    override def run(state: ProcessState, continue: ContinueProcess[Process], flow: ProcessFlowHandler) = {
      val me = state.process 
      val child = ProcessImpl.child(me, kind, executionQueue, body)
      continue(child, state.copy(children = child :: state.children))
    }
  }

  /**
   * ProcessAction receiving a message matching a partial function.
   */
  private class ReceiveProcessAction[T](fun: PartialFunction[Any,T @process]) extends ProcessAction[T] with NestingSupport[T] {
    override def run(state: ProcessState, continue: ContinueProcess[T], flow: ProcessFlowHandler) = {
      val reg = new CaptureRegistrant(fun.isDefinedAt _, (s,m) => execNested(s,continue,flow)(fun(m)), flow)
      reg.register(state)
    }
  }
  /**
   * ProcessAction receving a message matching a partial function within a certain timeframe.
   */
  private class ReceiveWithinProcessAction[T](fun: PartialFunction[Any,T @process], timeout: Duration) extends ProcessAction[T] with NestingSupport[T] {
    override def run(state: ProcessState, continue: ContinueProcess[T], flow: ProcessFlowHandler) = {
      val messageBox = state.messageBox
      class CaptureRegistrantWithin extends CaptureRegistrant(fun.isDefinedAt _, (s,m) => execNested(s,continue,flow)(fun(m)), flow) {
        object TimeoutTask extends java.util.TimerTask {
          private var canceled: Boolean = false
          override def run = handleTimeout
          override def cancel = synchronized {
            canceled = true
            super.cancel
          }
          def isCancelled = synchronized(canceled)
        }
        val done = new java.util.concurrent.atomic.AtomicBoolean(false)

        override def register(state: ProcessState) = synchronized {
          val capture = createCapture(state)
          messageBox.setCapture(capture)
          if (!TimeoutTask.isCancelled)
            timer.schedule(TimeoutTask, timeout.amountAs(Milliseconds))
        }
        override def reregisterCapture(state: ProcessState) = {
          val capture = createCapture(state)
          messageBox.setCapture(capture)
        }
        protected override def execute(state: ProcessState, msg: Any) = synchronized {
          if (done.compareAndSet(false, true)) {
            TimeoutTask.cancel
            super.execute(state, msg)
          }
        }
        protected def handleTimeout: Unit = {
          messageBox.cancelCapture { _ match {
            case capture: CaptureRegistrantCreated if capture.registrant == CaptureRegistrantWithin.this =>
              capture(Timeout)
            case other => 
              ()
          }}
        }
      }
      val reg = new CaptureRegistrantWithin
      reg.register(state)
    }
  }
  /**
   * ProcessAction receiving a matching message if already present, else Timeout.
   */
  private class ReceiveNoWaitProcessAction[T](fun: PartialFunction[Any,T @process]) extends ProcessAction[T] with NestingSupport[T] {
    override def run(state: ProcessState, continue: ContinueProcess[T], flow: ProcessFlowHandler) = {
      val reg = new CaptureRegistrant(fun.isDefinedAt _, (s,m) => execNested(s,continue,flow)(fun(m)), flow) {
        override def register(state: ProcessState) = {
          val capture = createCapture(state)
          def cancel(current: Capture) = {
            if (current == capture) capture(Timeout)
          }
          state.messageBox.setPeekCapture(capture, cancel _)
        }
      }
      reg.register(state)
    }
  }

  private class CaptureRegistrant(matcher: Any => Boolean, fun: (ProcessState,Any) => Unit, flow: ProcessFlowHandler) {
    def register(state: ProcessState): Unit = {
      val cap = createCapture(state)
      state.messageBox.setCapture(cap)
    }
    def reregisterCapture(state: ProcessState): Unit = {
      register(state)
    }

    protected def createCapture(state: ProcessState): Capture = new PartialFunction[Any,Unit] with CaptureRegistrantCreated {
      override val registrant = CaptureRegistrant.this
      override def isDefinedAt(msg: Any) = matcher(msg) || msg==ManagementMessage
      override def apply(msg: Any) = {
        if (msg == ManagementMessage) processMgmt(state)
        else execute(state, msg)
      }
    }
    protected def execute(state: ProcessState, msg: Any): Unit = {
      fun(state, msg)
    }
    protected def processMgmt(state: ProcessState) = {
      try {
        val state2 = flow.step(state)
        try {
          reregisterCapture(state2)
        } catch {
          case t => flow.exception(state2, t)
        }
      } catch {
        case t => flow.exception(state, t)
      }
    }
  }
  private trait CaptureRegistrantCreated {
    val registrant: CaptureRegistrant
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
  /**
   * ProcessAction spawning a proces that watches this process.
   */
  private class SpawnWatcherProcessAction(executionQueue: ExecutionQueue, body: => Any @process) extends ProcessAction[Process] {
    override def run(state: ProcessState, continue: ContinueProcess[Process], flow: ProcessFlowHandler) = {
      val me = state.process 
      val process = ProcessImpl.root(
        queue = executionQueue,
        body = body, 
        watched = me :: Nil)
      continue(process, state.copy(watchers = process :: state.watchers))
    }
  }

  /**
   * ProcessAction spawning a proces that gets watched by this process.
   */
  private class SpawnWatchedProcessAction(executionQueue: ExecutionQueue, body: => Any @process) extends ProcessAction[Process] {
    override def run(state: ProcessState, continue: ContinueProcess[Process], flow: ProcessFlowHandler) = {
      val me = state.process 
      val process = ProcessImpl.root(
        queue = executionQueue,
        body = body, 
        watchers = me :: Nil)
      continue(process, state.copy(watched = process :: state.watched))
    }
  }

  /** "Management" view onto a process. All declared methods behave like .!() (async, no exeception) */
  private trait ProcessInternal extends Process {
    /** Forcefully stop the process on the next possible location */
    def kill(killer: ProcessInternal, originalKiller: Process, reason: Throwable)
    def removeChild(child: ProcessInternal)
    def addWatcher(watcher: ProcessInternal)
    def removeWatcher(watcher: ProcessInternal)
    def send(msg: Any)
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
      finalState.watchers.foreach(_ send ProcessExit(of))
    }
    override def onException(in: ProcessInternal, finalState: ProcessState, cause: Throwable) = {
      super.onException(in, finalState, cause)
      finalState.watched.foreach(_.removeWatcher(in))
      finalState.watchers.foreach(_ send ProcessCrash(in, cause))
    }
    override def onKill(of: ProcessInternal, finalState: ProcessState, by: ProcessInternal, originalBy: Process, reason: Throwable) = {
      super.onKill(of, finalState, by, originalBy, reason)
      finalState.watched.foreach(_.removeWatcher(of))
      finalState.watchers.foreach(_ send ProcessKill(of, by, reason))
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
    val parent: ProcessInternal
    override def onNormalTermination(of: ProcessInternal, finalState: ProcessState) = {
      super.onNormalTermination(of, finalState)
      parent send ProcessExit(of)
    }
    override def onException(in: ProcessInternal, finalState: ProcessState, cause: Throwable) = {
      super.onException(in, finalState, cause)
      parent send ProcessCrash(in, cause)
    }
    override def onKill(of: ProcessInternal, finalState: ProcessState, by: ProcessInternal, originalBy: Process, reason: Throwable) = {
      super.onKill(of, finalState, by, originalBy, reason)
      parent send ProcessKill(of, by, reason)
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
      super.onStart(of)
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
  private case class ProcessState(process: ProcessImpl, children: List[ProcessInternal], watchers: List[ProcessInternal], watched: List[ProcessInternal]) {
    def messageBox = process.messageBox
  }

  private val IgnoreProcessResult = (res: Any, state: ProcessState) => ()

  /**
   * Implementation of a process.
   */
  private object ProcessImpl {
    def root(queue: ExecutionQueue, body: => Any @process, watched: List[ProcessImpl] = Nil, watchers: List[ProcessImpl] = Nil): ProcessInternal = {
      new ProcessImpl(
        queue = queue,
        listener = RootProcessListener,
        body = body,
        initialWatched = watched,
        initialWatchers = watchers)
    }
    def child(parent: ProcessInternal, childType: ChildType, queue: ExecutionQueue, body: => Any @process, watched: List[ProcessImpl] = Nil, watchers: List[ProcessImpl] = Nil): ProcessInternal = {
      val tm = childType match {
        case Monitored => new MonitoredChildProcessListener(parent)
        case Required => new RequiredChildProcessListener(parent)
        case NotMonitored => new NotMonitoredChildProcessListener(parent)
      }
      new ProcessImpl(
        queue = queue,
        listener = tm,
        body = body,
        initialWatched = watched,
        initialWatchers = watchers)
    }

    def currentProcess: Option[Process] = {
      ExecutionQueues.executionLocal.flatMap { p =>
        if (p.isInstanceOf[Process]) Some(p.asInstanceOf[Process])
        else None
      }
    }
  }
  private final val pidDealer = new java.util.concurrent.atomic.AtomicLong(0)

  private class ProcessImpl(queue_org: ExecutionQueue, listener: ProcessListener) extends Process with ProcessInternal {
    def this(queue: ExecutionQueue, listener: ProcessListener, body: => Any @process, initialWatchers: List[ProcessInternal], initialWatched: List[ProcessInternal]) = {
      this(queue, listener)
      val toExecute: ProcessAction[Any] = reset {
        firstFun.cps
        body
        lastFun
      }
      queue <-- {
        ExecutionQueues.executionLocal = Some(ProcessImpl.this)
        val initialState = ProcessState(
          process = this,
          watched = initialWatched,
          watchers = initialWatchers,
          children = Nil)
        toExecute.run(initialState, IgnoreProcessResult, flowHandler)
      }
    }

    val pid = pidDealer.incrementAndGet
    val queue = new ExecutionQueue {
      override def execute(f: => Unit) = queue_org <-- {
        ExecutionQueues.executionLocal = Some(ProcessImpl.this)
        ProcessImpl.this.synchronized(f)
      }
    }
    val messageBox: MessageBox = new MessageBox(queue)
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
    override def addWatcher(watcher: ProcessInternal) = mgmtStep { state =>
      log.trace("{} add watcher {}", external, watcher)
      state.copy(watchers = watcher :: state.watchers)
    }
    override def removeWatcher(watcher: ProcessInternal) = mgmtStep { state =>
      log.trace("{} remove watcher {}", external, watcher)
      val nw = state.watchers.filterNot(_ == watcher)
      state.copy(watchers = nw)
    }

    private[this] def mgmtStep(action: ProcessState => ProcessState): Unit = {
      val steps = mgmtSteps.get
      if (!mgmtSteps.compareAndSet(steps, action :: steps))
        mgmtStep(action)  //try again
      else 
        this send ManagementMessage //trigger a step if within a receive
    }

    override def !(msg: Any) = messageBox.enqueue(msg)
    def send(msg: Any) = messageBox.enqueue(msg)

    override def toString = "<Process-"+pid+">"

    val external: Process = this //TODO let gc collect us we don't have a 'internal' reference anymore
  }

  private case class KillTheProcess(by: ProcessInternal, originalBy: Process, reason: Throwable) extends Exception(reason)
  private object ManagementMessage

}


/**
 * Message box for many senders and a single consumer.
 */
trait MessageBoxContainer[T] extends Log {
    type Capture = PartialFunction[T,Unit]
    type Cancel = Capture => Unit

    private[this] trait Captures {
      def add(capture: Capture): Captures
      def add(cancel: Cancel): Captures
      def apply(old: Option[Capture]): (Option[Capture],Option[Cancel])
      def resultsInCapture(current: Boolean): Boolean
    }
    private[this] object Captures0 extends Captures {
      override def add(capture: Capture) = new Captures1A(capture)
      override def add(cancel: Cancel) = new Captures1R(cancel)
      override def apply(old: Option[Capture]) = (old, None)
      override def resultsInCapture(current: Boolean) = current
    }
    private[this] class Captures1A(capture: Capture) extends Captures {
      override def add(captureNew: Capture) =
        throw new IllegalStateException("Already a capture registered (A)")
      override def add(cancel: Cancel) =
        new Captures2AR(capture, cancel)
      override def apply(old: Option[Capture]) = {
        if (old.isDefined)
          throw new IllegalStateException("Uncancelled capture (A)")
        (Some(capture),None)
      }
      override def resultsInCapture(current: Boolean) = true
    }
    private[this] class Captures1R(cancel: Cancel) extends Captures {
      override def add(capture: Capture) =
        throw new IllegalStateException("Processing continued before cancel was executed")
      override def add(cancelNew: Cancel) =
        throw new IllegalStateException("Already a cancel registerd (R)")
      override def apply(old: Option[Capture]) = {
        old.foreach(cancel(_))
        (None, None)
      }
      override def resultsInCapture(current: Boolean) = false
    }
    private[this] class Captures2AR(capture: Capture, cancel: Cancel) extends Captures {
      override def add(captureNew: Capture) = {
        cancel(capture)
        new Captures1A(captureNew)
      }
      override def add(cancelNew: Cancel) =
        throw new IllegalStateException("Already a cancel registerd (AR)")
      override def apply(old: Option[Capture]) = {
        if (old.isDefined)
          throw new IllegalStateException("Uncancelled capture (AR)")
        (Some(capture),Some(cancel))
      }
      override def resultsInCapture(current: Boolean) = false
    }

    private[this] case class Actions(in: List[T], captures: Captures, checkerRunning: Boolean, hasMsgs: Boolean, hasCapture: Boolean)
    private[this] object ActionsRMC extends Actions (Nil, Captures0, true, true, true)
    private[this] object ActionsRfC extends Actions (Nil, Captures0, true, false, true)
    private[this] object ActionsRMf extends Actions (Nil, Captures0, true, true, false)
    private[this] object ActionsRff extends Actions (Nil, Captures0, true, false, false)
    private[this] object ActionsfMC extends Actions (Nil, Captures0, false, true, true)
    private[this] object ActionsffC extends Actions (Nil, Captures0, false, false, true)
    private[this] object ActionsfMf extends Actions (Nil, Captures0, false, true, false)
    private[this] object Actionsfff extends Actions (Nil, Captures0, false, false, false)

    private[this] case class State(msgs: List[T], capture: Option[Capture])

  /**
   * Message box for many senders and a single consumer.
   */
  protected class MessageBox(checkExec: ExecutionQueue) {
    import java.util.concurrent.atomic._
    import ch.inventsoft.scalabase.extcol.ListUtil._

    //Actions to execute. Modified and read by all threads
    private[this] val pending = new AtomicReference[Actions](Actions(Nil, Captures0, false, false, false))
    //State of the message box. Only accessed by the checker-threads (not-concurrent)
    @volatile private[this] var state = State(Nil, None)

    /**
     * Enqueue a new message.
     * Does complete fast, does never block. The amount of code executed in the caller thread
     * is minimal. No capture check is done in this thread.
     */
    def enqueue(msg: T): Unit = {
      val actions = pending.get
      val msgs = msg :: actions.in
      if (actions.checkerRunning || !actions.hasCapture) {
        val na = actions.copy(in=msgs, hasMsgs=true)
        if (!pending.compareAndSet(actions, na)) enqueue(msg) // retry
      } else {
        val na = actions.copy(in=msgs, hasMsgs=true, checkerRunning=true)
        if (pending.compareAndSet(actions, na)) checkExec <-- check
        else enqueue(msg) //retry
      }
    }

    /**
     * Register a new capture for the message box.
     */
    def setCapture(capture: Capture): Unit = {
      val actions = pending.get
      val ncs = actions.captures.add(capture)
      if (actions.checkerRunning || !actions.hasMsgs) {
        val na = actions.copy(captures=ncs, hasCapture=true)
        if (!pending.compareAndSet(actions, na)) setCapture(capture) //retry
      } else {
        val na = actions.copy(captures=ncs, hasCapture=true, checkerRunning=true)
        if (pending.compareAndSet(actions, na)) checkExec <-- check 
        else setCapture(capture) //retry
      }
    }
    /**
     * Cancel the currently registered capture.
     * The 'cancel' will be called with the deregistered capture. If no capture is registered
     * then this method is a no-op, 'cancel' will not be called.
     */
    def cancelCapture(cancel: Cancel): Unit = {
      val actions = pending.get
      val ncs = actions.captures.add(cancel)
      if (actions.checkerRunning || !actions.hasCapture) {
        val na = actions.copy(captures=ncs)
        if (!pending.compareAndSet(actions, na)) cancelCapture(cancel) //retry
      } else {
        val na = actions.copy(captures=ncs, checkerRunning=true)
        if (pending.compareAndSet(actions, na)) checkExec <-- check 
        else cancelCapture(cancel) //retry
      }
    }

    /**
     * Register a capture and cancel it right away. The capture will check all msgs received
     * so far and the be canceled.
     */
    def setPeekCapture(capture: Capture, cancel: Cancel): Unit = {
      val actions = pending.get
      val ncs = actions.captures.add(capture).add(cancel)
      if (actions.checkerRunning) { // must run always, else the capture does not get cancelled
        val na = actions.copy(captures=ncs, hasCapture=true)
        if (!pending.compareAndSet(actions, na)) setPeekCapture(capture, cancel) //retry
      } else {
        val na = actions.copy(captures=ncs, hasCapture=true, checkerRunning=true)
        if (pending.compareAndSet(actions, na)) checkExec <-- check
        else setPeekCapture(capture, cancel) //retry
      }
    } 

    /**
     * Guarantees:
     * - only active once ('synchronized') and only in the checkExec queue
     * - sees every msg without external delay
     */
    private[this] def check = {
      def process(s: State): Unit = {
        val actions = pending.get

        val runningAction = {
          if (actions.in.nonEmpty || s.msgs.nonEmpty) {
            if (actions.captures.resultsInCapture(s.capture.isDefined)) ActionsRMC
            else ActionsRMf
          } else {
            if (actions.captures.resultsInCapture(s.capture.isDefined)) ActionsRfC
            else ActionsRff
          }
        }

        //Confirm that we received the action..
        if (!pending.compareAndSet(actions, runningAction)) process(s)
        else {
          val (capture, deferredCancel) = actions.captures(s.capture)
          //try to apply the capture and merge the action-msgs into the state
          val s2 = processMsgs(s, capture, deferredCancel, actions.in.reverse)

          // need to do that before cas, because everything after cas might overlap with next invocation
          state = s2 

          val notRunningAction = {
            if (s2.msgs.nonEmpty) {
              if (s2.capture.isDefined) ActionsfMC
              else ActionsfMf
            } else {
              if (s2.capture.isDefined) ActionsffC
              else Actionsfff
            }
          }

          //See if we can terminate..
          if (!pending.compareAndSet(runningAction, notRunningAction)) {
            // new messages/captures arrived, so we need to rerun ourselves
            process(s2)
          }
        }
      }
      process(state)
    }


    /**
     * Process messages and apply the captures.
     * @param s current state
     * @param capture the capture to apply (new/ols capture)
     * @param actionMsgs new messages
     * @return new state
     */
    private[this] def processMsgs(s: State, capture: Option[Capture], deferredCancel: Option[Cancel], actionMsgs: List[T]): State = capture match {
      case c @ Some(capture) =>
        if (s.capture == capture) {
          //only check the new msgs
          removeFirst(actionMsgs, capture.isDefinedAt _) match {
            case Some((msg,rest)) =>
              capture(msg)
              State(s.msgs ::: rest, None)
            case None => 
              val msgs = s.msgs ::: actionMsgs
              deferredCancel match {
                case Some(cancel) => 
                  cancel(capture)
                  State(msgs, None)
                case None => 
                  State(msgs, c)
              }
          }
        } else {
          //check all msgs
          val msgs = if (!actionMsgs.isEmpty) s.msgs ::: actionMsgs else s.msgs
          removeFirst(msgs, capture.isDefinedAt _) match {
            case Some((msg,rest)) =>
              capture(msg)
              State(rest, None)
            case None =>
              deferredCancel match {
                case Some(cancel) => 
                  cancel(capture)
                  State(msgs, None)
                case None => 
                  State(msgs, c)
              }
          }
        }
      case None =>
        val msgs = if (!actionMsgs.isEmpty) s.msgs ::: actionMsgs else s.msgs
        State(msgs, None)
    }
  }
}
