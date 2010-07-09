package ch.inventsoft.scalabase.process.performance

import ch.inventsoft.scalabase.process._
import ch.inventsoft.scalabase.log._
import ch.inventsoft.scalabase.time._


/**
 * Tests the performance of processes with a large message buffer.
 */
object ProcessLargeBufferPerformance extends Log {
  def main(args: Array[String]) = {
    val buffer = 10000
    val rounds = 10

    //warmup
    log.info("Warming up")
    runRepeated((rounds/10) min 100 max 5, buffer)

    //execute
    log.info("Executing performance test...")
    val (duration,msgs) =  runRepeated(rounds, buffer)
    log.info("done.")

    log.info("It took {} to receive {} msgs (in-order)", duration.as(Seconds), msgs)
    log.info(" - {} per message", duration / msgs)
    val mps = (1 s) / (duration / msgs)
    log.info(" - messages per second: {}", mps)
    log.info("done.")
  }

  def run(msgCount: Int) = {
    log.trace("Spawning  processes")
    val durationVar = new scala.concurrent.SyncVar[Duration]
    val timer = startTimer { duration => durationVar.set(duration) }

    val receiver = spawn {
      receive { case Go => () }
      log.trace("Receive messages")
      timer ! StartMessage

      def rcv(count: Int = 0): Unit @processCps = count match {
        case `msgCount` => ()
        case other =>
          receive { case number: Int => number }
          rcv(count + 1)
      }
      rcv()
      
      timer ! StopMessage
    }

    log.trace("Sending {} messages", msgCount)
    val sender = spawn {
      (1 to msgCount).foreach(i => receiver ! i)
      receiver ! Go
    }

    val duration = durationVar.get
    duration
  }
  
  def runRepeated(rounds: Int, msgsInBuffer: Int, msgsSoFar: Int = 0, durationSoFar: Duration = 0 ns): (Duration,Int) = rounds match {
    case 0 =>
      (durationSoFar, msgsSoFar)

    case rounds =>
      val duration = run(msgsInBuffer)
      log.debug("Round took {}", duration.as(Seconds))
      runRepeated(rounds-1, msgsInBuffer, msgsSoFar+msgsInBuffer, durationSoFar+duration)
  }
  object Go

  def startTimer(reportTo: Duration => Unit) = spawn {
    val t0 = receive {
      case StartMessage => System.nanoTime
    }
    val t1 = receive {
      case StopMessage => 
        val t1 = System.nanoTime
        reportTo((t1 - t0) ns)
    }
  }
  object StartMessage
  object StopMessage

}

