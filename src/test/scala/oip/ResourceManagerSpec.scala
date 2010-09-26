package ch.inventsoft.scalabase
package oip

import scala.concurrent.SyncVar
import org.scalatest._
import matchers._
import time._
import process._


class ResourceManagerSpec extends ProcessSpec with ShouldMatchers {
  describe("ResourceManager") {
    it_("should open the resource and provide it to the user") {
      val rm = makeRM.receive
      rm.resource should be('open)
      rm.resource should not be('closed)
      rm.resource.use
      rm.resource should be(rm.resource)
    }
    it_("should let the user close the resource manually") {
      val rm = makeRM.receive
      rm.resource should be('open)
      rm.close.await(1 s)
      rm.resource should be('closed)
      rm.resource should not be('open)
    }
    it_("should close the resource when the process exits normally") {
      val p = self
      val c = spawnWatched {
        val rm = makeRM.receive
        p ! rm
        rm.resource should be('open)
        rm.resource use
      }
      val rm = receiveWithin(1 s) { case rm: ResourceManager[ResU] => rm }
      receiveWithin(1 s) {
        case ProcessExit(`c`) =>
          sleep(200 ms) //give it some time to close
          rm.resource should be('closed)
      }
    }
    it_("should close the resource when the process crashes") {
      val p = self
      val c = spawnWatched {
        val rm = makeRM.receive
        p ! rm
        rm.resource should be('open)
        rm.resource.use
        throw new RuntimeException("expected")
      }
      val rm = receiveWithin(1 s) { case rm: ResourceManager[ResU] => rm }
      receiveWithin(1 s) {
        case ProcessCrash(`c`, r) =>
          r.getMessage should be("expected")
          sleep(200 ms) //give it some time to close
          rm.resource should be('closed)
      }
    }
    it_("should close the resource when the process is killed") {
      val p = self
      val c = spawnWatched {
        val rm = makeRM.receive
        p ! rm
        rm.resource should be('open)
        rm.resource.use
        spawnChild(Required) {
          throw new RuntimeException("expected")
        }
        receive { case 'never => () }
      }
      val rm = receiveWithin(1 s) { case rm: ResourceManager[ResU] => rm }
      receiveWithin(1 s) {
        case ProcessKill(`c`, _, r) =>
          r.getMessage should be("expected")
          sleep(200 ms) //give it some time to close
          rm.resource should be('closed)
      }
    }
  }

  def makeRM = {
    ResourceManager[ResU](
      resource = {
        val r = new ResU
        r.init
        r
      },
      close = { r =>
        r.close
      }
    )
  }

  class ResU {
    private var isInitialized: Boolean = false
    private var isClosed: Boolean = false
    def init = synchronized {
      noop
      assert(!isInitialized, "opened twice")
      isInitialized = true
    }
    def close = synchronized {
      noop
      assert(isInitialized, "not initialized")
      isClosed = true
    }
    def initialized = synchronized(isInitialized)
    def closed = synchronized(isClosed)
    def open = synchronized(!closed && initialized)
    def use = synchronized {
      assert(open, "not open")
      noop
    }
  }
}
