package ch.inventsoft.scalabase
package process
package performance

import time._
import log._

/**
 * Compare this implementation to other implementations (erlang, scala actors).
 * see http://tech.puredanger.com/2009/01/05/scala-ring/
 */
object RingPerformance extends Log {
  
  def main(args: Array[String]) = {
    warmup
    log.info("Starting...")
    runTestWith(100, 100000)
  }
  
  def warmup = {
    log.info("Warming up the ring...")
    val endVar = new scala.concurrent.SyncVar[Unit]
    val timer = startTimer(_ => endVar.set(()))
    log.debug("Starting warmup nodes")
    val nodes = (1 to 100).map(id => startNode(id, timer, times => times < 50000)).toList 
    log.debug("Connecting warmup nodes")
    connectNodes(nodes)
    
    log.debug("Sending warmup message")
    nodes.head ! StartMessage
    
    endVar.get
    log.info("Warmed up the ring.")
  }
  
  def runTestWith(nodeCount: Int, roundCount: Int) = {
    val durationVar = new scala.concurrent.SyncVar[Duration]
    val timer = startTimer(d => durationVar.set(d))
    val nodes = timed("create "+nodeCount+" nodes")((1 to nodeCount).map(id => startNode(id, timer, times => {
//      if (times % (roundCount/20) == 0) log.info("Around ring "+times+" times")
      times < roundCount
    }))).toList 
    connectNodes(nodes)
    
    nodes.head ! StartMessage
//    nodes.head ! TokenMessage(-1, 1)

    val duration = durationVar.get
    val msgCount = roundCount * nodeCount
    println("It took "+duration.amountAs(Milliseconds)+"ms to send a message "+roundCount+" times around "+nodeCount+" nodes ("+msgCount+" msgs)")
    println(" - duration per round: "+duration.as(Nanoseconds)/roundCount)
    println(" - duration per message: "+duration.as(Nanoseconds)/msgCount)
    val rps = (1 s) / (duration / roundCount)
    val mps = (1 s) / (duration / msgCount)
    println(" - rounds per second: "+rps)
    println(" - messages per second: "+mps)
  }
  
  def timed[A](title: String)(f: => A) = {
    val t0 = System.nanoTime
    val result = f
    val duration = (System.nanoTime-t0) ns;
    log.info("It took {} ms to {}", duration.amountAs(Milliseconds), title)
    result
  }
  
  def connectNodes(nodes: List[Process]) = {
    if (nodes.nonEmpty) {
      val n2 = nodes.tail ::: nodes.head :: Nil
      nodes.zip(n2).foreach_cps { (pair) =>
        val (from,to) = pair
        from ! SetNextNode(to)
      }
    } else noop
  }
  
  
  def startNode(nodeId: Int, timer: Process, messageWasSentAroundRing: Int => Boolean): Process = spawn {
    def run(next: Process): Unit @process = {
      val cont = receive {
        case msg @ StartMessage =>
          timer ! msg
          next ! TokenMessage(nodeId, 1)
          true
        case msg @ StopMessage =>
          next ! msg
          false
        case TokenMessage(`nodeId`, value) =>
          if (messageWasSentAroundRing(value))
            next ! TokenMessage(nodeId, value+1)
          else {
            timer ! StopMessage
            next ! StopMessage
          }
          true
        case msg @ TokenMessage(id, _) =>
          next ! msg
          true
      }
      if (cont) run(next)
      else noop
    }
    val next = receive {
      case SetNextNode(next) =>
        next
    }
    run(next)
  }
  
  def startTimer(reportTo: Duration => Unit): Process = spawn {
    def timing(t0: Long): Boolean @process = receive {
      case StopMessage =>
        val duration = System.nanoTime - t0
        reportTo(duration ns)
        false
      case msg =>
        log.warn("Ignored message in timer-before: "+msg)
        true
    }
    def before: Unit @process = receive {
      case StartMessage =>
        if (timing(System.nanoTime)) before
        else noop
      case msg => log.warn("Ignored message in timer-before: "+msg)
    }
    before
  }

  
  case class SetNextNode(next: Process)
  case object StartMessage
  case object StopMessage
  case class TokenMessage(id: Int, value: Int)
}
