package ch.inventsoft.scalabase.process.cps

import org.scalatest._
import matchers._
import Process._
import collection.mutable.{Buffer,ListBuffer}
import ch.inventsoft.scalabase.time._
import ch.inventsoft.scalabase.executionqueue._
import ExecutionQueues._


trait MessageQueueSpec extends Spec with ShouldMatchers {
  
  def name: String
  def makeQueue[A]: MessageQueue[A]
  
  describe("MessageQueue "+name) {
    describe("drain") {
      it("should return an enqueued element") {
        val q = makeQueue[String]
        q.enqueue("Mario")
        q.drain should be(List("Mario"))
      }
      it("should preserve the ordering of the enqueue") {
        val q = makeQueue[String]
        (1 to 1000).foreach(i => q.enqueue("Item "+i))
        val expect = (1 to 1000).map(i => "Item "+i).reverse.toList
        q.drain should be(expect)
      }
    }
    
    describe("capture") {
      it("should work before the enqueue") {
        val q = makeQueue[String]
        val r = new ListBuffer[String]
        q.captureMessage(_ match { case m => r+=m })
        q.enqueue("Test")
        r.toList should be(List("Test"))
      }
      it("should work after the enqueue") {
        val q = makeQueue[String]
        val r = new ListBuffer[String]
        q.enqueue("Test")
        q.captureMessage(_ match { case m => r+=m })
        r.toList should be(List("Test"))
      }
      it("should work before the enqueue with multiple elements") {
        val q = makeQueue[String]
        val r = new ListBuffer[String]
        q.captureMessage(_ match { case m => r+=m })
        (1 to 1000).foreach(i => q.enqueue("Test "+1))
        r.toList should be(List("Test 1"))
      }
      it("should work after the enqueue with multiple elements") {
        val q = makeQueue[String]
        val r = new ListBuffer[String]
        (1 to 1000).foreach(i => q.enqueue("Test "+i))
        q.captureMessage(_ match { case m => r+=m })
        r.toList should be(List("Test 1"))
      }
    }
    
    describe("multithreaded") {
      it("ordering should be preserved when capture is registered later") {
        (1 to 100).foreach { i =>
          val q = makeQueue[String]
          concurrent {
            (1 to 1000).foreach(i => q.enqueue("Test "+i))
          }
          val r = new ListBuffer[String]
          q.captureMessage(_ match { case m => r+=m })
          tick
          r.toList should be(List("Test 1"))
        }
      }
      it("ordering should be preserved when capture is registered earier") {
        (1 to 100).foreach { i =>
          val q = makeQueue[String]
          val r = new ListBuffer[String]
          q.captureMessage(_ match { case m => r+=m })
          concurrent {
            (1 to 1000).foreach(i => q.enqueue("Test "+1))
          }
          tick
          r.toList should be(List("Test 1"))
        }
      }
    }
    
    describe("single-threaded performance") {
      it("should allow fast single enqueues if no capture is registered") {
        val queue = makeQueue[String]
        val expect = 10 microsecond
        val d = time("single enqueue", 100000) {
          queue.enqueue("My message")
        } should(be < expect)
      }
      it("should allow fast enqueues when a non matching capture is registered") {
        val queue = makeQueue[String]
        val expect = 10 microsecond;
        queue.captureMessage(_ match { case "never" => fail("got never") })
        val d = time("single enqueue with non-matching capture", 100000) {
          queue.enqueue("My message")
        } should(be < expect)
      }
      it("should allow fast registration and matching of captures") {
        val queue = makeQueue[String]
        val expect = 10 microsecond
        val d = time("enqueue and capture", 100000) {
          queue.captureMessage(_ match { case "My message" => () })
          queue.enqueue("My message")
        } should(be < expect)
      }
      it("should allow fast draining") {
        val queue = makeQueue[String]
        val expect = 100 microsecond
        val d = time("enqueue 10 and drain", 100000) {
          (1 to 10).foreach(_ => queue.enqueue("My message"))
          queue.drain.length should be(10)
        } should(be < expect)
      }
      it("should allow fast capturing of message when there are 50 other messages in the queue") {
        val queue = makeQueue[String]
        val expect = 20 microsecond;
        (1 to 50).foreach(_ => queue.enqueue("Your message"))
        time("enqueue and capture with 50 items in queue", 100000) {
          queue.captureMessage(_ match { case "My message" => () })
          queue.enqueue("My message")
        } should(be < expect)        
      }
    }
    describe("multi-threaded perfomance") {
      val count = 30000
      it("should allow fast single enqueues if no capture is registered") {
        val queue = makeQueue[String]
        val expect = 20 microsecond;
        val latch = new java.util.concurrent.CountDownLatch(count+warmups)
        time("single enqueue", count)({
          ExecutionQueues.execute <-- { queue.enqueue("My message"); latch.countDown }
        }, {
          latch.await
        })should(be < expect)
      }      
      it("should allow fast enqueues when a non matching capture is registered") {
        val queue = makeQueue[String]
        val expect = 20 microsecond;
        queue.captureMessage(_ match { case "never" => fail("got a never") })
        val latch = new java.util.concurrent.CountDownLatch(count+warmups)
        val d = time("single enqueue with non-matching capture", count)({
          ExecutionQueues.execute <-- { queue.enqueue("My message"); latch.countDown }
        }, {
          latch.await()
        }) should(be < expect)
      }
      it("should allow fast draining") {
        val queue = makeQueue[String]
        val expect = 200 microsecond;
        val latch = new java.util.concurrent.CountDownLatch(count+warmups)
        val counter = new java.util.concurrent.atomic.AtomicInteger
        val d = time("enqueue 10 and drain", count)({ExecutionQueues.execute <-- {
          (1 to 10).foreach(_ => queue.enqueue("My message"))
          counter.addAndGet(queue.drain.length)
          latch.countDown()
        }}, {
          latch.await()
        }) should(be < expect)
        counter.get should be((count+warmups)*10)
      }
      it("should allow one thread to drain fast while other threads fill") {
        val queue = makeQueue[String]
        val expect = 20 microsecond;
        //spawn fillers
        (1 to 100).foreach(_ => ExecutionQueues.execute <-- { (1 to 1000).foreach(i => queue.enqueue(i.toString)) })
        def drain(left: Int): Unit = {
            val drained = queue.drain.length
            val newLeft = left - drained
            if (newLeft > 0) drain(newLeft)            
        }

        time("dequeue", 100*1000)({}, {
          drain(100*1000)
        }) should (be < expect)
      }
      it("should allow one thread to capture fast while other threads fill") {
        val queue = makeQueue[String]
        val expect = 2000 microsecond;
        //spawn fillers
        (1 to 10).foreach(_ => ExecutionQueues.execute <-- { (1 to 1000).foreach(i => queue.enqueue(i.toString)) })
        time("dequeue", 10*1000-warmups)({
          val s = new scala.concurrent.SyncVar[String]
          queue.captureMessage(_ match { case msg => s.set(msg) })
          s.get
        }) should (be < expect)
      }
    }
  }
  
  val warmups = 1000
  def time(desc: String, count: Int, warmupCount: Int = warmups)(f: => Unit, after: => Unit = ()) = {
    (1 to warmupCount).foreach(_ => f) //warmup
    val t0 = System.nanoTime
    (1 to count).foreach(_ => f)
    after
    val duration = Duration(System.nanoTime - t0, Nanoseconds)
    val dpi = duration / count
    println(desc+" took "+dpi+" per item (total time: "+duration+"ns)")    
    dpi
  }
  
  def concurrent(f: => Unit) = {
    val mutex = new Object
    var done = false
    new Thread {
      override def run = {
        mutex synchronized {
          done = true
          mutex.notifyAll
        }
        f
      }
    }.start
    mutex synchronized {
      if (!done) mutex.wait
    }
  }

  private def tick = Thread.sleep(70)
  private def tickLong = Thread.sleep(200)
}
