package ch.inventsoft.scalabase
package process
package performance

import scala.concurrent._
import scala.math.sqrt
import time._


object InstructionPerformance extends log.Log {
  def main(args: Array[String]) = {
    loopAtFirstLevel
    functionCallAtFirstLevel
  }

  def loopAtFirstLevel = {
    val count = 10000000

    def normal = {
      val sqrts = (1 to count).map(sqrt(_))
      val sum = sqrts.foldLeft(0d)(_ + _)
      assert(sum != 0)
    }
    def process = {
      val w = new SyncVar[Unit]
      spawn {
        val sqrts = (1 to count).map(sqrt(_))
        val sum = sqrts.foldLeft(0d)(_ + _)
        assert(sum != 0)
        w.set(())
      }
      w.get
    }

    log debug("Warming up")
    warmup(normal _)
    log debug("Running variant 'normal'")
    val tn = time(normal _)

    log debug("Warming up")
    warmup(process _)
    log debug("Running variant 'process'")
    val tp = time(process _)

    println("Loop At First Level")
    println("  Normal Performance:          "+(tn/count)+" per item")
    println("  Performance inside processs: "+(tp/count)+" per item")
  }

  def functionCallAtFirstLevel = {
    val count = 10000000

    def normal = {
      val sqrts = (1 to count).map(sqrt(_))
      val sum = sqrts.foldLeft(0d)(_ + _)
      assert(sum != 0)
    }
    def process = {
      val w = new SyncVar[Unit]
      spawn {
        normal
        w.set(())
      }
      w.get
    }

    log debug("Warming up")
    warmup(normal _)
    log debug("Running variant 'normal'")
    val tn = time(normal _)

    log debug("Warming up")
    warmup(process _)
    log debug("Running variant 'process'")
    val tp = time(process _)

    println("Function Call at First Level")
    println("  Normal Performance:          "+(tn/count)+" per item")
    println("  Performance inside processs: "+(tp/count)+" per item")
  }

  def warmup(what: () => Any): Unit = time(what, 2)
  def time(what: () => Any, reps: Int = 10) = {
    def innerTime: Duration = {
      val t0 = System.nanoTime
      what()
      (System.nanoTime-t0) ns
    }
    val totalDuration = (1 to reps).map(_ => innerTime).foldLeft(0 ns)(_ + _)
    totalDuration / reps
  }
}
