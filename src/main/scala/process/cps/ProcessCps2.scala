package ch.inventsoft.scalabase.process.cps

import scala.util.continuations._
import ch.inventsoft.scalabase.log._
import ch.inventsoft.scalabase.time._
import ch.inventsoft.scalabase.executionqueue._
import ch.inventsoft.scalabase.process._
import ExecutionQueues._


object ProcessCps extends Log {

  def spawnProcess(executionQueue: ExecutionQueue)(body: => Any @processCps): Process = {
    new ProcessImpl(executionQueue, body)
  }

  def self = SelfProcessAction.cps
  def receive[T](fun: PartialFunction[Any,T @processCps]) = new ReceiveProcessAction(fun).cps
  def receiveWithin[T](timeout: Duration)(fun: PartialFunction[Any,T @processCps]) = {
    if (timeout.isZero) new ReceiveNoWaitProcessAction(fun).cps
    else new ReceiveWithinProcessAction(fun, timeout).cps
  }
  def receiveNoWait[T](fun: PartialFunction[Any,T @processCps]) = new ReceiveNoWaitProcessAction(fun).cps
  
  //TODO
  def watch(toWatch: Process) = noop
  //TODO
  def spawnChildProcess(executionQueue: ExecutionQueue, kind: ChildType, body: => Any @processCps): Process @processCps = {
    //TODO
    noop
    spawnProcess(executionQueue)(body)
  }

  //TODO
  object useWithCare {
    //TODO
    def currentProcess: Option[Process] = None
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
    def step: Unit
    def exception(e: Throwable): Unit
    def spawn(toexec: => Unit): Unit
  }

  /**
   * Chains together two ProcessActions (flatMap)
   */
  private class ChainedProcessAction[T,A](first: ProcessAction[T], second: T => ProcessAction[A]) extends ProcessAction[A] {
    override def run(state: ProcessState, continue: ContinueProcess[A], flow: ProcessFlowHandler) = {
      val contToFirst = (result: T, stateAfterFirst: ProcessState) => {
        val secondAction = second(result)
        secondAction.run(stateAfterFirst, continue, flow)
      }
      first.run(state, contToFirst, flow)
    }
  }

  /**
   * ProcessAction with exception and flow handling
   */
  private trait BodyProcessAction[T] extends ProcessAction[T] {
    protected[this] def body(state: ProcessState, continue: ContinueProcess[T])
    private[ProcessCps] final def run(state: ProcessState, continue: ContinueProcess[T], flow: ProcessFlowHandler) = {
      try {
        flow.step
        body(state, continue)
      } catch {
        case t => flow.exception(t) 
      }
    }
  }

  /**
   * Support for execution nested CPS'es
   */
  private trait NestingSupport[T] {
    protected[this] def execNested(state: ProcessState, continue: ContinueProcess[T], flow: ProcessFlowHandler)(result: => T @processCps): Unit = {
      val action: ProcessAction[Any] = reset {
        val r: T = result
        new ValueProcessAction[Any](r)
      }
      try {
        flow.step
        action.run(state, continue.asInstanceOf[ContinueProcess[Any]], flow)
      } catch {
        case t => flow.exception(t)
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
   * Message box for many senders and a single consumer.
   */
//TODO idea: we could only spawn a checker if a capture is active
// - add a second paramter to the inQueue (alreadyChecking, capturePossiblyActive)
  class MessageBox[T](checkExec: ExecutionQueue) {
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
      def emptyOrContinue(processed: List[Any]): Unit = {
        if (!inQueue.compareAndSet(processed, Nil, true, false)) {
          //queue has changed since we checked it
          // => process the newly added messages
          val p2 = inQueue.getReference
          val newMsgs = p2.take(p2.length - processed.length)
          processMsgs(newMsgs)
          emptyOrContinue(p2)
        }
      }

      val toProcess = inQueue.getReference
      processMsgs(toProcess)
      emptyOrContinue(toProcess)
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
  private class ReceiveProcessAction[T](fun: PartialFunction[Any,T @processCps]) extends ProcessAction[T] with NestingSupport[T] {
    override def run(state: ProcessState, continue: ContinueProcess[T], flow: ProcessFlowHandler) = {
      val capture = new PartialFunction[Any,Unit] {
        override def isDefinedAt(msg: Any) = fun.isDefinedAt(msg)
        override def apply(msg: Any) = execNested(state, continue, flow) {
          fun(msg)
        }
      }
      state.messageBox.setCapture(capture)
    }
  }
  /**
   * ProcessAction receving a message matching a partial function within a certain timeframe.
   */
  private class ReceiveWithinProcessAction[T](fun: PartialFunction[Any,T @processCps], timeout: Duration) extends ProcessAction[T] with NestingSupport[T] {
    override def run(state: ProcessState, continue: ContinueProcess[T], flow: ProcessFlowHandler) = {
      val capture = new PartialFunction[Any,Unit] {
        override def isDefinedAt(msg: Any) = fun.isDefinedAt(msg)
        override def apply(msg: Any) = execNested(state, continue, flow) {
          fun(msg)
        }
      }
      val timeoutTask = new java.util.TimerTask {
        override def run = state.messageBox.cancelCapture { cap => 
          if (cap == capture) cap(Timeout)
        }
      }
      state.messageBox.setCapture(capture)
      timer.schedule(timeoutTask, timeout.amountAs(Milliseconds))
    }
  }
  private val timer = new java.util.Timer(true)
  private class ReceiveNoWaitProcessAction[T](fun: PartialFunction[Any,T @processCps]) extends ProcessAction[T] with NestingSupport[T] {
    override def run(state: ProcessState, continue: ContinueProcess[T], flow: ProcessFlowHandler) = {
      val capture = new PartialFunction[Any,Unit] {
        override def isDefinedAt(msg: Any) = fun.isDefinedAt(msg)
        override def apply(msg: Any) = execNested(state, continue, flow) {
          fun(msg)
        }
      }
      state.messageBox.setCapture(capture)
      state.messageBox.cancelCapture { cap => 
        if (cap == capture) cap(Timeout)
      }
    }
  }

  
  //TODO parent/child
  //TODO watch


  private case class ProcessState(process: ProcessImpl) {
    def messageBox = process.messageBox
  }


  private val IgnoreProcessResult = (res: Any, state: ProcessState) => ()

  private final val pidDealer = new java.util.concurrent.atomic.AtomicLong(0)
  private class ProcessImpl(queue: ExecutionQueue) extends Process {
    private[ProcessCps] val messageBox: MessageBox[Any] = new MessageBox[Any](queue)
    val pid = pidDealer.incrementAndGet

    private[this] val flowHandler = new ProcessFlowHandler {
      override def step = () //TODO check for kill
      override def exception(e: Throwable) = {
        //TODO
      }
      override def spawn(toexec: => Unit) = queue <-- toexec
    }

    def this(queue: ExecutionQueue, body: => Any @processCps) = {
      this(queue)
      val toExecute = reset {
        firstFun.cps
        body
        lastFun
      }
      toExecute.run(ProcessState(this), IgnoreProcessResult, flowHandler)
    }

    private[this] def firstFun = new ProcessAction[Any] {
      override def run(state: ProcessState, continue: ContinueProcess[Any], flow: ProcessFlowHandler) {
        log.debug("Started {}", external)
        continue((), state)
      }
    }
    private[this] def lastFun = new ProcessAction[Any] {
      override def run(state: ProcessState, continue: ContinueProcess[Any], flow: ProcessFlowHandler) {
        continue((), state)
        //TODO termination logic
        log.debug("Terminated {}", external)
      }
    }

    override def !(msg: Any) = messageBox.enqueue(msg)

    override def toString = "<Process-"+pid+">"

    val external: Process = this
  }


}
