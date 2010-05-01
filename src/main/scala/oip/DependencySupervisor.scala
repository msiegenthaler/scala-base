package ch.inventsoft.scalabase.oip

import ch.inventsoft.scalabase.time._
import ch.inventsoft.scalabase.process._
import Messages._
import ch.inventsoft.scalabase.log._
import ProcessSpecification._ 

/**
 * Supervisor, that allows for easy creation of processes that depend upon each other.
 * 
 * Example:
 *  abstract class TestSupervisor(usbPort: String, answerTo: Process) extends DependencySupervisor {
 *    protected[this] val serial = permanent.shutdownTimeout(2 s) { _ =>
 *      SerialPort(usbPort)(Spawner)
 *    }
 *    protected[this] val lowLevel = permanent.shutdownTimeout(1 s) { _ =>
 *      LowLevelXBee(serial)(Spawner)
 *    }
 *    protected[this] val series1 = permanent.shutdownTimeout(1 s) { _ =>
 *      Series1XBee(lowLevel)(Spawner)
 *    }
 *    protected[this] val published = transient.shutdownTimeout(1 s) { spawn =>
 *      val msg = DependenciesStarted(serial.value, lowLevel.value, series1.value)
 *      spawn(answerTo ! msg)
 *    }
 *  }
 */
trait DependencySupervisor extends Supervisor with Spawnable with Log {
  protected[this] def transient[A] = new SpecBuilder1[A](Transient)
  protected[this] def permanent[A] = new SpecBuilder1[A](Permanent)
  protected[this] def temporary[A] = new SpecBuilder1[A](Temporary)
  protected[this] type SpawnFun = (=> Unit @processCps) => Process @processCps
  private[this] type ValueMap = Map[ChildDefinition[_],_]

  private[this] var _definitions: List[ChildDefinition[_]] = Nil
  private[this] def receiveDefinitions = synchronized {
    val r = _definitions.reverse
    _definitions = Nil
    r
  }
  private[this] def addDefinition[A](definition: ChildDefinition[A]) = synchronized {
    _definitions = definition :: _definitions
  }
  
  /* Initialize Phase (children are defined)
   * ================
   * - Supervisor process is started                  [main/sp]
   * - val's are evaluated, resulting in:             [main]
   *   - creation of ChildDefinition                  [main]
   *   - add ChildDefinition to _definitions          [main]
   *   [- set field to ChildDefinition                [main]
   * - read ChildDefinitions (_definitions)           [sp]
   *   
   * Start Phase (children are started)
   * ===========
   * - for all spec wrappers in order of registration [sp]
   *   - set as currentlyStarting                     [sp]
   *   - childDefinition.start                        [sp]
   *     - evaluate the "content" of val              [sp] (i.e. new MyServer with Spawner)
   *       - Spawner.spawn or spawnFun called         [sp] (i.e. during the construction of the StateServer)
   *       - 0-n accesses to other childs defs        [sp]
   *       - spawn a new process as child of sp       [sp]
   *         - according to the definition in val     [sp] (with a SpecifiedProcessManager)
   *       - capture the process-manager              [sp]
   *     - add value&pm to the state                  [sp]
   * - continue to run phase                          [sp]
   */
  protected[this] def body = {
    val initResult = phaseInitialize
    initResult match {
      case InitializeSuccessful(definitions) =>
        phaseStart(definitions, Nil) match {
          case StartTerminated(children) => phaseStop(children.reverse)
          case StartFailed(failed, children, notStarted) =>
            val restartResult = phaseRestart(failed.definition, children, notStarted)
            restartResult match {
              case Some(children) => phaseRun(children)
              case None => noop // Termination requested
            }
          case StartSuccessful(children) => phaseRun(children)
        }
      case InitializeTerminated => noop //termination requested 
    }
  }

  private[this] case class StartedDefinition[A](definition: ChildDefinition[A], value: A, dependsUpon: List[ChildDefinition[_]], manager: Option[SpecifiedProcessManager]) {
    def withManager(manager: SpecifiedProcessManager) = StartedDefinition(definition, value, dependsUpon, Some(manager))
    def withoutManager = StartedDefinition(definition, value, dependsUpon, None)
  }
  
  /**
   * Collects all definitions form the initialize process (the one that called "new Supervisor").
   */
  private[this] def phaseInitialize = {
    receiveNoWait {
      case Terminate => InitializeTerminated
      case Timeout => InitializeSuccessful(receiveDefinitions)
    }
  }
  private[this] sealed trait PhaseInitializeResult
  /** order of definitions: head is first to be started */
  private[this] case class InitializeSuccessful(definitions: List[ChildDefinition[_]]) extends PhaseInitializeResult
  /** termination of the supervisor was requested */
  private[this] object InitializeTerminated extends PhaseInitializeResult
  
  private[this] case class AddDefinition[A](definition: ChildDefinition[A])
  private[this] object EndOfDefinitionPhase

  /**
   * Start the definitions in the parameter.
   * 
   * @param toStart in order, first will be started first
   * @param started already started things (order: first is most recently started)
   */
  private[this] def phaseStart(toStart: List[ChildDefinition[_]], started: List[StartedDefinition[_]]): StartPhaseResult @processCps = {
    log.trace("Supervisor: Starting {} children", toStart.size)
    receiveNoWait {
      case ProcessStopped(manager, true) =>
        started.find(_.manager == Some(manager)) match {
          case Some(crashed) =>
            log.info("Supervised child {} crashed and requests restart (during start)", crashed.definition)
            StartFailed(crashed, started, toStart)
          case None =>
            //not one of our
            StartMore(toStart, started)
        }
      case ProcessStopped(manager, false) =>
        //just a normal termination, remove it if in started list
        val newStarted = started.map { sd =>
          if (sd.manager == Some(manager)) sd.withoutManager
          else sd
        }
        StartMore(toStart, newStarted)
      case Terminate =>
        StartTerminated(started)
      case AddDefinition(definition) =>
        log.warn("Definition added to supervisor after initization phase was over. Ignoring.")
        StartMore(toStart, started)
      case Timeout =>
        //Nothing was terminated, everything is ok, start the next one
        toStart match {
          case definition :: rest =>
            val alreadyStarted: ValueMap = Map[ChildDefinition[_],Any]() ++ started.map(s => (s.definition, s.value))
	    def start[B](definition: ChildDefinition[B]) = {
              val ((value,spm), usedDeps) = DependencyProvider(alreadyStarted) { 
		definition.start
              }
              log.debug("Supervised Child {} started as process {}", definition, spm.managedProcess)
              val newOne = StartedDefinition(definition, value, usedDeps, Some(spm))
              StartMore(rest, started ::: newOne :: Nil)
	    }
	    start(definition)
          case Nil =>
            StartSuccessful(started)
        }
    } match {
      case result: StartPhaseResult => noop; result
      case StartMore(toStart, started) => phaseStart(toStart, started)
    }
  }
  private[this] case class StartMore(toStart: List[ChildDefinition[_]], started: List[StartedDefinition[_]])
  
  private[this] sealed trait StartPhaseResult
  private[this] case class StartTerminated(started: List[StartedDefinition[_]]) extends StartPhaseResult
  private[this] case class StartFailed(crashed: StartedDefinition[_], started: List[StartedDefinition[_]], notStartedYet: List[ChildDefinition[_]]) extends StartPhaseResult
  private[this] case class StartSuccessful(started: List[StartedDefinition[_]]) extends StartPhaseResult
  
  /**
   * Everything has been started and is running just fine. This method watches out for things
   * like crashes and terminations and either continues with a restart (phaseRestart) or a
   * shutdown (phaseStop).
   */
  private[this] def phaseRun(children: List[StartedDefinition[_]]): Unit @processCps = {
    receive {
      case AddDefinition(definition) =>
        log.warn("Definition added to supervisor after initization phase was over. Ignoring.")
        ContinueRun(children)
      case ProcessStopped(manager, false) =>
        val newChildren = children.map { sd =>
          if (sd.manager == Some(manager)) sd.withoutManager
          else sd
        }
        ContinueRun(newChildren)
      case ProcessStopped(crashedMgr, true) =>
        children.find(_.manager == Some(crashedMgr)) match {
          case Some(crashed) =>
            //the child was one of ours, initiate a restart
            log.info("Supervised child {} crashed and requests restart", crashed.definition)
            val restartResult = phaseRestart(crashed.definition, children, Nil) 
            restartResult match {
              case Some(newChildren) =>
                //Restart successful, continue running
                ContinueRun(newChildren)
              case None =>
                StopRun(Nil)
            }
          case None =>
            //not our child or already marked as stopped
            ContinueRun(children)
        }
      case Terminate => StopRun(children)
        
    } match {
      case StopRun(children) => phaseStop(children.reverse)
      case ContinueRun(children) => phaseRun(children)
    }
  }
  private[this] case class StopRun(children: List[StartedDefinition[_]])
  private[this] case class ContinueRun(children: List[StartedDefinition[_]])
  
  /**
   * Stops all children in the order in the list.
   */
  private[this] def phaseStop(toStop: List[StartedDefinition[_]]): Unit @processCps = toStop match {
    case child :: rest =>
      child.manager match {
        case Some(manager) =>
          val timeout = manager.specification.shutdownTimeout.getOrElse(0 s) + (2 s)
          receiveWithin(timeout) { manager.stop(true).option }
        case None => //already stopped
      }
      phaseStop(rest)
    case Nil => ()
  }
  /**
   * Executes the restart
   * @param crashed the one that caused the restart.
   * @param children all running children, still including the crashed one
   */
  private[this] def phaseRestart(crashed: ChildDefinition[_], children: List[StartedDefinition[_]], stillToStart: List[ChildDefinition[_]]): Option[List[StartedDefinition[_]]] @processCps = {
    //TODO Implement that in strategy classes...
    //TODO should we abort somewhen (after x unsuccessful restarts or so)?
   
    //All-for-one
    val defsToStart = children.map(_.definition) ::: stillToStart
    val toStop = children.filterNot(_.definition == crashed)
    phaseStop(toStop.reverse)
    phaseStart(defsToStart, Nil) match {
      case StartSuccessful(started) => 
        noop
        Some(started)
      case StartFailed(crashed, started, notStarted) =>
        phaseRestart(crashed.definition, started, notStarted)
      case StartTerminated(toStop) =>
        phaseStop(toStop.reverse)
        None
    }
  }
  
  private[this] case class GetValueForDefinition[A](wrapper: ChildDefinition[A]) extends MessageWithSimpleReply[Option[A]]
  private[this] case class ProcessStopped(manager: SpecifiedProcessManager, requestsRestart: Boolean)

  private[this] object ChildIdDealer {
    private[this] val ids = new java.util.concurrent.atomic.AtomicLong()
    def apply() = ids.incrementAndGet 
  }
  protected[this] class SpecBuilder1[A](restart: RestartSpecification) {
    def shutdownTimeout(timeout: Duration) = new SpecBuilder2[A](restart, Some(timeout))
    def hardShutdown = new SpecBuilder2[A](restart, None)
  }
  protected[this] class SpecBuilder2[A](restart: RestartSpecification, timeout: Option[Duration]) {
    def apply(starter: SpawnFun => A @processCps): ChildDefinition[A] = {
      val wrapper = new ChildDefinition(restart, timeout, starter, ChildIdDealer())
      log.trace("Supervisor: Adding child {}", wrapper.id)
      addDefinition(wrapper)
      wrapper
    }
  }
  protected[this] class ChildDefinition[A](restart: RestartSpecification, timeout: Option[Duration], starter: SpawnFun => A @processCps, protected[DependencySupervisor] val id: Long) {
    def value: A = DependencyProvider.getDependency(this)
    def getValue = GetValueForDefinition(this).sendAndSelect(process)
    private[DependencySupervisor] def start: (A,SpecifiedProcessManager) @processCps = {
      log.trace("Supervisor: Starting child {}", id)
      val result = ProcessSpawnCollector(spawn _) {
        val process = ProcessSpawnCollector.spawn _
        starter(process)
      }
      log.trace("Supervisor: Started child {}", id)
      result
    }
    private[this] def spawn(body: => Any @processCps): SpecifiedProcessManager @processCps = {
      val specification = new ProcessSpecification {
        override val id = None
        override val restart = ChildDefinition.this.restart 
        override val shutdownTimeout = ChildDefinition.this.timeout 
        override def apply() = {
          body
          ()
        }
        override def toString = "Spec for SupervisorChild["+id+"]"
      }      
      SpecifiedProcessManager.startAsChild(specification, Parent)
    }
    override def toString = "SupervisorChild["+id+"]" 
  }
  protected[this] implicit def childDefinitionToValue[A](wrapper: ChildDefinition[A]): A = {
    wrapper.value
  }
  
  private[this] object Parent extends SpecifiedProcessManagerParent {
    override def processStopped(manager: SpecifiedProcessManager, requestsRestart: Boolean) = {
      process ! ProcessStopped(manager, requestsRestart)
    }
  }
  
  /**
   * Responsible for offering references to already spawned definitions to a spawning definition
   */
  private[this] object DependencyProvider {
    private[this] var _map: Option[ValueMap] = None
    private[this] var _used: List[ChildDefinition[_]] = Nil
    
    def apply[A](map: ValueMap)(body: => A @processCps): (A, List[ChildDefinition[_]]) @processCps = {
      assertInSupervisor
      if (_map.isDefined) throw InvalidUsageOfDependencySupervisor("nesting not allowed")
      _map = Some(map)
      _used = Nil
      val value = body
      _map = None
      (value, _used)
    }
    
    def getDependency[A](definition: ChildDefinition[A]): A = {
      assertInSupervisor
      _map match {
        case Some(map) =>
          map.get(definition) match {
            case Some(value) =>
              _used = definition :: _used
              value.asInstanceOf[A]
            case None =>
              throw UnsatisfiedDependency()
          }
        case None => throw InvalidUsageOfDependencySupervisor("not inside DependencyProvider")
      }
    }
  }
  /**
   * Responsible for collecting the spawned SpecifiedProcessManager by the external code.
   */
  private[this] object ProcessSpawnCollector {
    private[this] var _collectFun: Option[(=> Any @processCps) => SpecifiedProcessManager @processCps] = None
    private[this] var _manager: Option[SpecifiedProcessManager] = None
    
    /**
     * Setup the spawn-collector with the given spawnFun.
     * Call from the supervisor with the specified spawn-function.
     * (SETUP)
     * Usage: ProcessSpawnCollector(accordingToSpecification)( 'execute the spawn body' )
     * Returns: The Manager for the spawned process and the value returned by 'inner'
     */
    def apply[A](spawnFun: (=> Any @processCps) => SpecifiedProcessManager @processCps)(inner: => A @processCps): (A,SpecifiedProcessManager) @processCps = {
      assertInSupervisor
      if (_collectFun.isDefined) throw InvalidUsageOfDependencySupervisor("nesting not allowed")
      _collectFun = Some(spawnFun)
      _manager = None
      
      val value = inner
      
      _manager match {
        case Some(manager) =>
          noop
          _manager = None
          (value, manager)
        case None =>
          throw InvalidUsageOfDependencySupervisor("no process spawned in dependency")
      }
    }
    /**
     * Spawn a new child-process using a specified processmanager (_collectFun). This is used
     * as/inside the spawn-strategy.
     * (SPAWNING)
     */
    def spawn[A](body: => A @processCps): Process @processCps = {
      assertInSupervisor
      _collectFun match {
        case Some(fun) =>
          val m = fun(body)
          _manager = Some(m)
          _collectFun = None
          m.managedProcess
        case None =>
          throw InvalidUsageOfDependencySupervisor("can only use spawn inside the declaration (or tried to spawn twice)")
      }
    }
  }
  private[this] def assertInSupervisor: Unit = {
    if (Some(process) != useWithCare.currentProcess) throw InvalidUsageOfDependencySupervisor("can only be called from inside the supervisor process")
  }
  
  /**
   * Usage: 
   *  val myThing = transient.shutdownTimeout(1 s) {
   *    MyThing(dependency1)(Spawner)
   *  }
   */
  protected[this] object Spawner extends SpawnStrategy {
    override def spawn[A](body: => A @processCps): Process @processCps = ProcessSpawnCollector.spawn(body)
  }
}


case class InvalidUsageOfDependencySupervisor(msg: String) extends Exception(msg)
case class DuplicateStartupOfDependency() extends RuntimeException("duplicate startup in a dependency (used Spawner twice in the same declaration?)")
case class UnsatisfiedDependency() extends RuntimeException("unsatisfied dependency")
