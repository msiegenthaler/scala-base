package ch.inventsoft.scalabase
package process
package performance

import log._
import time._


/**
 * Tests the performance of processes with a large message buffer.
 */
object ProcessLargeBufferPerformance extends Log {
  def main(args: Array[String]) = {
    val buffer = 1000
    val rounds = 200

    //warmup
    log.info("Warming up")
    runRepeated(200, 1000)
    runRepeated(200, 10000)
    runRepeated(200, 1000)

    //execute
    log.info("Executing performance tests...")
    log.info("Medium sized (100 msgs)")
    val (duration100,msgs100) =  runRepeated(2000, 100)
    log.info("Large (1000 msgs)")
    val (duration1k,msgs1k) = runRepeated(2000, 1000)
    log.info("Very large (10000 msgs)")
    val (duration10k,msgs10k) = runRepeated(2000, 10000)
    log.info("done.")

    val duration = duration100 + duration1k + duration10k
    val msgs = msgs100 + msgs1k + msgs10k
    log.info("Total duration was {} seconds for {} msgs", duration.as(Seconds), msgs)
    log.info(" - {} per message", duration / msgs)
    log.info(" - messages per second: {}", mps(duration,msgs))
    log.info("   - {} mps for 100 msgs in queue", mps(duration100,msgs100))
    log.info("   - {} mps for 1k msgs in queue", mps(duration1k,msgs1k))
    log.info("   - {} mps for 10k msgs in queue", mps(duration10k,msgs10k))
    log.info("done.")
  }

  def mps(duration: Duration, msgs: Int) = (1 s) / (duration / msgs)

  def run(msgCount: Int) = {
    log.trace("Spawning  processes")
    val durationVar = new scala.concurrent.SyncVar[Duration]
    val timer = startTimer { duration => durationVar.set(duration) }

    val receiver = spawn {
      receive { case Go => () }
      log.trace("Receive messages")
      timer ! StartMessage

      def rcv(count: Int = 0): Unit @process = count match {
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
      (1 to msgCount).foreach_cps(i => receiver ! i)
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

