package ch.inventsoft.scalabase.process.cps

import org.scalatest._
import matchers._
import ch.inventsoft.scalabase.process._
import CpsUtils._

class CpsUtilsTest extends ProcessSpec with ShouldMatchers {
  
  describe("CPS Utilities") {
    describe("Traversable") {
      describe("foreach") {
        it_("should support cps-fun") {
          val p = self
          val col = (1 to 3)
          col.foreach_cps { e => 
            spawnChild(Required) {
              p ! e
            }
          }
          val r = receive {
            case a: Int => a
          } :: receive {
            case a: Int => a
          } :: receive {
            case a: Int => a
          } :: Nil
          r.size should be(3)
          r should contain(1)
          r should contain(2)
          r should contain(3)
        }
      }
      describe("map") {
        it_("should be able to spawn children") {
          val ps = (1 to 3).map_cps { i =>
            spawnChild(Required) { 
              receive { case Terminate => () }
            }
          }
          ps.size should be(3)
          ps.foreach(_ ! Terminate)
        }
        it_("should allow receives in map") {
          val p = self
          spawnChild(Required) {
            (1 to 100).foreach(i => p ! i)
          }
          val r = (1 to 100).map_cps { _ =>
            receive { case a: Int => a }
          }.foldLeft(0)(_+_)
          r should be(5050)
        }
      }
      describe("flatMap") {
        it_("should be able to spawn children in flatMap") {
          val ps = (1 to 3).flatMap_cps { i =>
            spawnChild(Required) { 
              receive { case Terminate => () }
            } :: spawnChild(Required) { 
              receive { case Terminate => () }
            } :: Nil 
          }
          ps.size should be(6)
          ps.foreach(_ ! Terminate)
        }
      }
      describe("foldLeft") {
        it_("should be able to receive in foldLeft") {
          val p = self
          spawnChild(Required) {
            (1 to 100).foreach(i => p ! i)
          }
          val r = (1 to 100).foldLeft_cps(0) { (s,e) =>
            receive { case a: Int => s+a } 
          }
          r should be(5050)
        }
        it_("should have the correct order (first is first)") {
          val p = self
          spawnChild(Required) {
            (1 to 100).foreach(i => p ! i)
          }
          val r = (1 to 100).foldLeft_cps(0) { (s,e) =>
            receive { case a: Int => a } 
          }
          r should be(100)
        }
      }
      describe("foldRight") {
        it_("should be able to receive in foldRight") {
          val p = self
          spawnChild(Required) {
            (1 to 100).foreach(i => p ! i)
          }
          val r = (1 to 100).foldRight_cps(0) { (e,s) =>
            receive { case a: Int => s+a } 
          }
          r should be(5050)
        }
        it_("should have the correct order (first is last)") {
          val p = self
          spawnChild(Required) {
            (1 to 100).foreach(i => p ! i)
          }
          val r = (1 to 100).foldRight_cps(0) { (e,s) =>
            receive { case `e` => e } 
          }
          r should be(1)
        }
      }
    }
    describe("PartialFunction") {
      it_("should support cps andThen") {
        val p = self
        spawnChild(Required) {
          p ! "Mario"
        }
        val f0: PartialFunction[Any,String @processCps] = {
          case a: String => a
        }
        val f1 = f0.andThen_cps(a => "Hi "+ a)
        val r = receive(f1)
        r should be("Hi Mario")
      }
    }
  } 
  
  object Terminate
}