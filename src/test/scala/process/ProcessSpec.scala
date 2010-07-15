package ch.inventsoft.scalabase.process

import org.scalatest._
import matchers._
import ch.inventsoft.scalabase.process._
import ch.inventsoft.scalabase.process.cps.CpsUtils._
import scala.concurrent.SyncVar
import ch.inventsoft.scalabase.time._

class ProcessTest extends ProcessSpec with ShouldMatchers {
  
  describe("Process") {
    describe("spawn") {
      it_("should execute a simple operation when spawned") {
        val s = new SyncVar[Boolean] 
        spawn {
          s.set(true)
        }
        s.get(1000) should be(Some(true))
      }
      it_("should execute a simple operation followed by a noop when spawned") {
        val s = new SyncVar[Boolean] 
        spawn {
          s.set(true)
          noop
        }
        s.get(1000) should be(Some(true))
      }
      it_("should execute a noop followed by a simple operation when spawned") {
        val s = new SyncVar[Boolean] 
        spawn {
          noop
          s.set(true)
        }
        s.get(1000) should be(Some(true))
      }
      it_("should execute a 'self' when spawned") {
        val s = new SyncVar[Process] 
        val p = spawn {
          s.set(self)
        }
        s.get(1000) should be(Some(p))
      } 
      it_("should execute a send when spawned") {
        val s = new SyncVar[Boolean] 
        spawn {
          self ! "hi"
          receive {
            case "hi" => s.set(true)
            case other => s.set(false)
          }
        }
        s.get(1000) should be(Some(true))
      }      
    }
    /* /////////////////////////// */
    // Receive
    /* /////////////////////////// */
    describe("receive") {
      it_("should be able to receive messages") {
        val s = new SyncVar[String]
        val p = spawn {
          receive {
            case msg: String => s.set(msg)
          }
        }
        p ! "Hi"
        s.get(1000) should be(Some("Hi"))
      }
      it_("should preseve the ordering of buffered messages") {
        val r = new SyncVar[List[String]]
        val p = spawn {
          receiveWithin(1 s) { case Timeout => () }
          val data = receive { case a: String => a } :: receive { case a: String => a } :: receive { case a: String => a } :: receive { case a: String => a } :: Nil
          r.set(data)
        }
        p ! "a"
        p ! "b" 
        p ! "c"
        p ! "d"
        val msgs = "a" :: "b" :: "c" :: "d" :: Nil
        r.get(5000) should be(Some(msgs))
      }
      it_("should be able to selectivly receive messages") {
        val s = new SyncVar[String]
        val p = spawn {
          receive {
            case msg: String => s.set(msg)
          }
        }
        p ! 123
        p ! "Hi there!"
        s.get(1000) should be(Some("Hi there!"))
      }
      it_("should be able to do nested receives") {
        val s = new SyncVar[String]
        val p = spawn {
          receive {
            case part1: String =>
              receive {
                case part2: String =>
                  s.set(part1+part2)
              }              
          }
        }
        p ! 123
        p ! "Hi "
        p ! "there!"
        s.get(10000) should be(Some("Hi there!"))
      }      
      it_("should be possible to receive two messages in sequence") {
        val s1 = new SyncVar[String]
        val s2 = new SyncVar[String]
        val p = spawn {
          receive {
            case a: String => s1.set(a)
          }
          receive {
            case a: String => s2.set(a)
          }
        }
        p ! "Hi"
        p ! "Ho!"
        s1.get(1000) should be (Some("Hi"))
        s2.get(1000) should be (Some("Ho!"))
      }
      it_("should be possible to receive three messages in sequence") { (1 to 100).foreach_cps { i =>
        val s1 = new SyncVar[String]
        val s2 = new SyncVar[String]
        val s3 = new SyncVar[String]
        val p = spawn {
          receive {
            case a: String => s1.set(a)
          }
          receive {
            case a: String => s2.set(a)
          }
          receive {
            case a: String => s3.set(a)
          }
        }
        p ! "Hi"
        p ! "Ho!"
        p ! "Ha"
        s1.get(1000) should be (Some("Hi"))
        s2.get(1000) should be (Some("Ho!"))
        s3.get(1000) should be (Some("Ha"))
      }}
      it_("should be possible to receive two messages nested") {
        val s1 = new SyncVar[String]
        val s2 = new SyncVar[String]
        val p = spawn {
          receive {
            case a: String =>
              s1.set(a)
              receive {
                case a: String => s2.set(a)
              }
          }
        }
        p ! "Hi"
        p ! "Ho!"
        s1.get(1000) should be (Some("Hi"))
        s2.get(1000) should be (Some("Ho!"))
      }
      it_("should be possible to receive three messages nested") {
        val s1 = new SyncVar[String]
        val s2 = new SyncVar[String]
        val s3 = new SyncVar[String]
        val p = spawn {
          receive {
            case a: String =>
              s1.set(a)
              receive {
                case a: String =>
                  s2.set(a)
                  receive {
                    case a: String => s3.set(a)
                  }
              }
          }
        }
        p ! "Hi"
        p ! "Ho!"
        p ! "Boomba"
        s1.get(1000) should be (Some("Hi"))
        s2.get(1000) should be (Some("Ho!"))
        s3.get(1000) should be (Some("Boomba"))
      }
      it_("should be able to answer to messages") {
        val s = new SyncVar[String]
        val p1 = spawn {
          receive {
            case (msg: String, from: Process) =>
              val answer = msg + " my friend"
              from ! answer
          }
        }
        val p2 = spawn {
          p1 ! ("huhu", self)
          receive {
            case msg: String => s.set(msg)
          }
        }
        s.get(1000) should be(Some("huhu my friend"))
      }
      it_("should be able to send three messages when one is received") {
        val s1 = new SyncVar[String]
        val s2 = new SyncVar[String]
        val p1 = spawn {
          val partner = receive {
            case (msg: String, from: Process) =>
              from ! self
              from ! (msg + " is nice")
              from
          }
          partner ! "bye"
        }
        spawn {
          p1 ! ("Mario", self)
          val v = receive {
            case p: Process =>
              receive {
                case a: String => a
              }
          }
          s1.set(v)
          val w = receive {
            case b: String => b
          }
          s2.set(v+"\n"+w)
        }
        s1.get(1000) should be(Some("Mario is nice"))
        s2.get(1000) should be(Some("Mario is nice\nbye"))
      }
      it_("should support receives with timeouts (timeouted)"){ (1 to 100).foreach_cps { _ =>
        val s = new SyncVar[String]
        val p = spawn {
          receiveWithin(10 ms) {
            case x: String => s.set(x)
            case Timeout => s.set("timeout")
          }
        }
        sleep(50 ms)
        p ! "hi"
        s.get(1000) should be(Some("timeout"))
      }}
      it_("should support receives with timeouts (sleep)"){ (1 to 100).foreach_cps { _ =>
        val s = new SyncVar[String]
        val p = spawn {
          receiveWithin(100 ms) {
            case Timeout => s.set("timeout")
            case x => s.set("fail")
          }
        }
        s.get(1000) should be(Some("timeout"))
      }}
      it_("should support receives with timeouts (not timeouted)") {
        val s = new SyncVar[String]
        val p = spawn {
          receiveWithin(100 ms) {
            case x: String => s.set(x)
            case Timeout => s.set("timeout")
          }
        }
        sleep(10 ms)
        p ! "hi"
        s.get(1000) should be(Some("hi"))
      }
      it_("should support instant receives for priorities") {
        val s = new SyncVar[String]
        val p = spawn { 
          sleep(50 ms)
          receiveNoWait {
            case x: Int =>
              s.set(x.toString)
            case Timeout => receive {
              case x: String => s.set(x)
            }
          }
        }
        p ! "hi"
        s.get(1000) should be(Some("hi"))
      }
      it_("should support instant receives for priorities (prio)") {
        val s = new SyncVar[String]
        val p = spawn {
          receiveWithin(200 ms) { case Timeout => () }
          receiveNoWait {
            case x: Int => s.set(x.toString)
            case Timeout => receive {
              case x: String => s.set(x)
            }
          }
        }
        p ! "hi"
        p ! 100
        s.get(1000) should be(Some("100"))
      }
    }

    /* /////////////////////////// */
    // Monitoring
    /* /////////////////////////// */
    describe("monitoring") {
      it_("should send a message to the parent if a Monitored child exits normally") {
        val s = new SyncVar[String]
        val p = spawn {
          val c = spawnChild(Monitored) {
            "i'm not doing anything"
          }
          val process = receive {
            case ProcessExit(process) => process
          }
          if (process==c) s.set("ok")
          else s.set("fail")
        }
        s.get(1000) should be(Some("ok"))
      }
      it_("should send a message to the parent if a Monitored child crashes") {
        val s = new SyncVar[String]
        val p = spawn {
          val c = spawnChild(Monitored) {
            throw new RuntimeException("oO")
          }
          val a = receiveWithin(300 ms) {
            case ProcessCrash(process, ex) =>
              Some((process, ex.getMessage))
            case Timeout => None
          }
          a match {
            case Some((process,msg)) if process == c => s.set(msg)
            case _ => s.set("fail")
          }
        }
        s.get(1000) should be(Some("oO"))
      }

      it_("should not send a message to the paret if a NotMonitoredChild exits normally") {
        val s = new SyncVar[String]
        val p = spawn {
          val c = spawnChild(NotMonitored) {
            "i'm not doing anything"
          }
          receiveWithin(300 ms) {
            case ProcessExit(process) =>
              s.set("fail")
            case Timeout =>
              s.set("ok")
          }
        }
        s.get(1000) should be(Some("ok"))
      }
      it_("should not send a message to the parent if a NotMonitored child crashes") {
        val s = new SyncVar[String]
        val p = spawn {
          val c = spawnChild(NotMonitored) {
            throw new RuntimeException("oO")
          }
          receiveWithin(300 ms) {
            case ProcessCrash(process, ex) =>
              s.set(ex.getMessage)
            case Timeout =>
              s.set("ok")
          }
        }
        s.get(1200) should be(Some("ok"))
      }
      
      it_("should crash the parent if a Required child crashes") {
        val s1 = new SyncVar[String]
        val s2 = new SyncVar[String]
        val p = spawn {
          val p = self
          val c = spawnChild(Required) {
            p ! "Hi"
            receive {
              case "ok, go crashing" => throw new RuntimeException("this is expected")
              case _ => fail
            }
          }
          receive {
            case "Hi" => //go on
            case _ => fail
          }
          s1.set("letting it crash")
          c ! "ok, go crashing"
          receiveWithin(2 s) {
            case Timeout =>
              s2.set("failed because not killed")
          }
        }
        s1.get(3000) should be(Some("letting it crash"))
        s2.get(3000) should be(None)
      }
      it_("should crash the parent and the peers if a Required child crashes") {
        val s1 = new SyncVar[String]
        val s2 = new SyncVar[String]
        val s3 = new SyncVar[String]
        val s4 = new SyncVar[String]
        val p = spawn {
          val p = self
          val peer = spawnChild(Required) {
            receive {
              case "Hi" => s3.set("ok")
              case a => s3.set("Fail "+a)
            }
            receiveWithin(1 s) {
              case Timeout => s4.set("failed")
            }
          }
          val c = spawnChild(Required) {
            p ! "Hi"
            receive {
              case "ok, go crashing" => throw new RuntimeException("this is expected")
              case _ => fail
            }
          }
          receive {
            case "Hi" => //go on
            case _ => fail
          }
          s1.set("letting it crash")
          peer ! "Hi"
          c ! "ok, go crashing"
          receiveWithin(1 s) {
            case Timeout =>
              s2.set("failed because not killed")
          }
        }
        s1.get(2000) should be(Some("letting it crash"))
        s3.get(2000) should be(Some("ok"))
        s2.get(2000) should be(None)
        s4.get(2000) should be(None)
      }
      it_("should not affect the parent if a Required child exits normally") {
        val s = new SyncVar[String]
        spawn {
          val p = self
          val c = spawnChild(Required) {
            p ! "Hi"
          }
          receiveWithin(300 ms) {
            case "Hi" => //go on
            case x => s.set("Fail: "+x)
          }
          receiveWithin(1 s) {
            case Timeout => s.set("ok")
            case any => s.set("Fail: "+any)
          }
        }
        s.get(3000) should be(Some("ok"))
      }
      it_("should notify the monitored parents parent if a Required child crashes the parent") {
        val s1 = new SyncVar[String]
        val s2 = new SyncVar[String]
        val s3 = new SyncVar[String]
        val s4 = new SyncVar[String]
        val pp = spawn {
          val p = spawnChild(Monitored) {
            val p = self
            val c = spawnChild(Required) {
              p ! "Hi"
              receive {
                case "Ok, crash" =>
                  s1.set("ok")
                  throw new RuntimeException("this is expected")
                case other => s1.set("Fail "+other) 
              }
            }
            receive {
              case "Hi" => s2.set("ok")
              case other => s2.set("Fail "+other)
            }
            c ! "Ok, crash"
            receiveWithin(1 s) {
              case Timeout => s3.set("nooo")
            }
          }
          receiveWithin(3 s) {
            case ProcessKill(process, by, _) =>
              if (process == p) s4.set("ok")
              else s4.set("wrong "+process)
            case other => s4.set("Fail "+other)
          }
        }
        s1.get(2000) should be(Some("ok")) 
        s2.get(2000) should be(Some("ok"))
        s3.get(2000) should be(None)
        s4.get(4000) should be(Some("ok"))
      }
      it_("should be possible to watch a process and receive notification about a normal exit") {
        val s = new SyncVar[String]
        val p = spawn {
          val pid = receive {
            case pid: Process =>
              watch(pid)
              pid
          }
          pid ! ()
          receive {
            case ProcessExit(p2) => s.set(p2+" ok")
          }
        }
        val c = spawn {
          p ! self
          receive {
            case () => ()
          }
        }
        s.get(1000) should be(Option(c+" ok"))
      }
      it_("should be possible to watch a process and receive a notification about a crash") {
        val s = new SyncVar[String]
        val p = spawn {
          val pid = receive {
            case pid: Process =>
              watch(pid)
              pid
          }
          pid ! ()
          receive {
            case ProcessCrash(p2,ex) =>
              s.set(ex.getMessage)
          }
        }
        val c = spawn {
          p ! self
          receive {
            case () => ()
          }
          throw new RuntimeException("oO")
        }
        s.get(1000) should be(Some("oO"))
      }
      
      it_("should be that a crashing parent also terminates its children") {
        val s1 = new SyncVar[String]
        val s2 = new SyncVar[String]
        val p = spawn {
          val c = spawnChild(Required) {
            receive {
              case wontReceiveThat: Int => s1.set("bla")
            }
          }
          receive {
            case w: Process => w ! c
          }
          receive {
            case "go on" => ()
          }
          throw new RuntimeException("parent crash")
        }
        val w = spawn {
          p ! self
          val c = receive {
            case c: Process => c
          }
          watch(c)
          watch(p)
          receiveWithin(200 ms) { case Timeout => () }
          p ! "go on"
          receive {
            case ProcessCrash(pid, t) =>
              s1.set(pid+" crash with "+t.getMessage)
          }
          receive {
            case ProcessKill(pid, by, _) =>
              s2.set("killed by "+by)
          }
        }
        s1.get(1000) should be(Some(p+" crash with parent crash"))
        s2.get(1000) should be(Some("killed by "+p))
      }
      it_("should be that a crashing parent also terminates its children v2") {
        val s1 = new SyncVar[String]
        val s2 = new SyncVar[String]
        val p = spawn {
          val c = spawnChild(Required) {
            receive {
              case wontReceiveThat: Int => s1.set("bla")
            }
          }
          receive {
            case w: Process => w ! c
          }
          receive {
            case "go on" => ()
          }
          throw new RuntimeException("parent crash")
        }
        val w = spawn {
          p ! self
          val c = receive {
            case c: Process => c
          }
          watch(c)
          watch(p)
          sleep(100 ms)
          p ! "go on"
          receive {
            case ProcessKill(pid, by, _) =>
              s2.set("killed by "+by)
          }
          receive {
            case ProcessCrash(pid, t) =>
              s1.set("crash with "+t.getMessage)
          }
        }
        s1.get(1000) should be(Some("crash with parent crash"))
        s2.get(1000) should be(Some("killed by "+p))
      }
      it_("should be that a crashing parent also terminates its children v3") {
        val s = new SyncVar[String]
        val s2 = new SyncVar[String]
        val p2 = spawn {
          receive {
            case (c: Process, p: Process) =>
              watch(c)
              sleep(50 ms)
              p ! ()
              sleep(250 ms)
              c ! "Hi there"
              receive {
                case ProcessKill(p,b,_) => s2.set("killed by "+b)
                case ProcessExit(p) => s2.set("ok")
                case a => s2.set(""+a)
              }
          }
        }
        val p = spawn {
          val c = spawnChild(Required) {
            receive {
              case a: String => s.set(a)
            }
          };
          p2 ! (c,self)
          receive {
            case () => ()
          }
          throw new RuntimeException()
        }
        s2.get(1000) should be(Some("killed by "+p))
        s.get(1000) should be(None)
      }
      it_("should not be that a normally exiting parent kills its children") {
        val s = new SyncVar[String]
        val s2 = new SyncVar[String]
        val p2 = spawn {
          receive {
            case (c: Process, p: Process) =>
              watch(c)
              receiveWithin(50 ms) { case Timeout => () }
              p ! ()
              receiveWithin(250 ms) { case Timeout => () }
              c ! "Hi there"
              receive {
                case ProcessKill(p,b,_) => s2.set("killed by "+b)
                case ProcessExit(p) => s2.set("ok")
                case a => s2.set(""+a)
              }
          }
        }
        val p = spawn {
          val c = spawnChild(Required) {
            receive {
              case a: String => s.set(a)
            }
          }
          p2 ! (c,self)
          receive {
            case () => ()
          }
        }
        s.get(1000) should be(Some("Hi there"))
        s2.get(1000) should be(Some("ok"))
      }
    }
    describe("use with care") {
      it_("should be possible to get the current") {
        val p1 = self
        val p2 = useWithCare.currentProcess
        Some(p1) should be(p2)
      }
    }

    /* /////////////////////////// */
    // Performance
    /* /////////////////////////// */
    describe("performance") {
      it_("should be possible to start at least 10000 concurrent processes") {
        val c = (1 to 10000)
        val shouldSum = c.foldLeft(0)(_ + _)
        val s = new SyncVar[Int]
        val p = spawn {
          def run(sum: Int): Unit @processCps = {
            val v = receiveWithin (10 s) {
              case i: Int => Some(sum + i)
              case Timeout => s.set(sum); None
            }
            if (v.isDefined) run(v.get)
            else noop
          }
          run(0)
        }
        c.map(i => spawn {
          p ! i
          receive {
            case () => ()
          }
        }).toList.foreach { pi => spawn { pi ! () }}
        s.get(11000) should be(Some(shouldSum))
      }
      it_("should not use resources for terminated processes") {
        for (i <- 1 to 10000) {
          val p = spawn {
            receive {
              case p2: Process => p2 ! self
            }
          }
          val s = new SyncVar[Process]
          val p2 = spawn {
            p ! self
            receive {
              case pd: Process => s.set(pd)
            }
          }
          s.get(1000) should be(Some(p))
        }
      }
      it_("should be possible for a process to loop forever using recursion (no stack overflow)") {
        val s = new SyncVar[Int]
        val p = spawn {
          def run(sum: Int): Unit @processCps = {
            receive {
              case i: Int => run(sum + i)
              case () => s.set(sum); noop
            }
          }
          run(0)
        }
        val count = 100000
        spawn {
          def exec(left: Int): Unit @processCps = left match {
            case 0 =>
              p ! ()
              noop
            case left =>
              p ! left
              exec(left -1)
          }
          exec(count)
        }
        s.get(60000) should be(Some((1 to count).foldLeft(0)(_ + _)))
      }
      it_("should have a fast message passing (unnested) with a large msg buffer") {
        val count = 1000000
        val r = new SyncVar[Long]
        val p = spawn {
          def doit(index: Long): Unit @processCps = {
            val cont = receive {
              case nr: Long => true
              case Exit => r.set(index); false
            }
            if (cont) doit(index+1)
            else noop
          }
          doit(0)
        }
        def exec(left: Long): Unit = left match {
          case 0 => ()
          case left =>
            p ! left
            exec(left -1)
        }
        val t0 = System.nanoTime
        val duration = new SyncVar[Long]
        exec(count)
        p ! Exit
        r.get(300000) should be(Some(count))
        duration.set(System.nanoTime - t0)
    
        val dpm = duration.get / count
        val mps = 1000000000L / dpm
        println("Duration per unnested message: "+dpm+"ns")
        println("That is "+mps+" messages/s")
        (mps > 10000) should be(true)
      }
      it_("should be possible to implement a fast ping pong") {
        val count = 1000000
        val p = spawn {
          def doit: Unit @processCps = {
            val proc = receive {
              case proc: Process => Some(proc)
              case Exit => None
            }
            if (proc.isDefined) {
              proc.get ! "got ya!"
              doit
            } else noop
          }
          doit
        }
        val failed = new SyncVar[Boolean]
        def exec(left: Long): Unit @processCps = left match {
          case 0 =>
            failed.set(false)
          case left =>
            p ! self
            val cont = receive {
              case "got ya!" => true
              case _ => false
            }
            if (cont) exec(left-1)
            else {
              failed.set(true)
              noop
            }
        }
        spawn {
          exec(10000) //warmup
        }
    
        val duration = new SyncVar[Long]
        spawn {
          val t0 = System.nanoTime
          exec(count)
          duration.set(System.nanoTime - t0)        
        }

        val dpm = duration.get / count
        val mps = 1000000000L / dpm
        println("Duration per unnested ping-pong: "+dpm+"ns")
        println("That is "+(mps*2)+" messages/s")
        (mps > 10000) should be(true)
        failed.get(1000) should be(Some(false))
        p ! Exit
      }
      it_("should have a fast message passing") {
        val count = 100000
        val p = spawn {
          def doit: Unit @processCps = {
            val cont = receive {
              case p2: Process =>
                p2 ! "hi"
                true
              case Exit => false
            }
            if (cont) doit
            else noop
          }
          doit 
        }
        val duration = new SyncVar[Long]
        spawn {
          def exec(left: Int): Unit @processCps = left match {
            case 0 => ()
            case left =>
              p ! self
              receive {
                case x: String => ()
              }
              exec(left - 1)
          }
          exec(count / 10) //warmup
          val t0 = System.nanoTime
          exec(count)
          duration.set(System.nanoTime - t0)
          p ! Exit
        }
        val dpm = Duration(duration.get, Nanoseconds) / count
        val mps = (1 second) / dpm
        println("Duration per message: "+dpm+"ns")
        println("That is "+mps+" messages/s")
        (mps > 10000) should be(true)
      }
      it_("should be fast at spawning processes") {
        val count = 10000
        val expect = 100000 ns
    
        time("Process creation", count) {
          spawn { noop }
        } should(be < expect)
      }
      it_("should not use much memory per process") {
        val count = 30000
        Runtime.getRuntime.gc()
        sleep(200 ms)
        val mem0 = Runtime.getRuntime.totalMemory - Runtime.getRuntime.freeMemory
        val ps = (1 to count).map { _ => 
          spawn { receive { case Exit => () } }
        }
        //now we have 'count' processes running
        Runtime.getRuntime.gc()
        sleep(200 ms)
        val mem = Runtime.getRuntime.totalMemory - Runtime.getRuntime.freeMemory - mem0
        val bytesPerProcess = mem / count //includes list 'ps'
        println("Running "+count+" processes with <"+(mem/1048576)+"Mb used (<"+bytesPerProcess+" bytes per process)")
        if (bytesPerProcess > 3000) fail("Too much memory used per process: "+bytesPerProcess+" bytes")
        //Stopping
        ps.foreach(p => p ! Exit)
      }
    }
  }

  
  protected val warmups = 1000
  protected def time(desc: String, count: Int, warmupCount: Int = warmups)(f: => Unit, after: => Unit = ()) = {
    (1 to warmupCount).foreach(_ => f) //warmup
    val t0 = System.nanoTime
    (1 to count).foreach(_ => f)
    after
    val duration = Duration(System.nanoTime - t0, Nanoseconds)
    val dpi = duration / count
    println(desc+" took "+dpi+" per item (total time: "+duration+"ns)")    
    dpi
  }
  
  object Exit
}
