package ch.inventsoft.scalabase.extcol

import org.scalatest._
import matchers._
import ch.inventsoft.scalabase.time._
import ListUtil._


class ListUtilSpec extends Spec with ShouldMatchers  {
  val always = (e: Any) => true
  val never = (e: Any) => false
  
  describe("ListUtil") {
    describe("removeFirst") {
      it("should return None on empty List") {
        val r = removeFirst(Nil, always)
        r should be(None)
      }
      it("should return Some(first,Nil) on one element List with always matcher") {
        val r = removeFirst(List("One"), always)
        r should be(Some(("One", Nil)))
      }
      it("should return Some(first,tail) on two element List with always matcher") {
        val r = removeFirst(List("One", "Two"), always)
        r should be(Some(("One", List("Two"))))
      }
      it("should return Some(first,tail) on List with always matcher") {
        val r = removeFirst(List("One", "Two", "Three", "Four", "Five", "Six"), always)
        r should be(Some(("One", List("Two", "Three", "Four", "Five", "Six"))))
      }
      it("should return None if no element matches") {
        val r = removeFirst(List("One", "Two", "Three", "Four", "Five", "Six"), never)
        r should be(None)
      }
      it("should return Some(firstMatching,rest) on List with a filter") {
        val r = removeFirst(List("One", "Two", "Three", "Four", "Five", "Six"), (e: String) => e == "Three")
        r should be(Some(("Three", List("One", "Two", "Four", "Five", "Six"))))
      }
      it("should return Some(firstMatching,rest) on List with a filter even if its the last element") {
        val r = removeFirst(List("One", "Two", "Three", "Four", "Five", "Six"), (e: String) => e == "Six")
        r should be(Some(("Six", List("One", "Two", "Three", "Four", "Five"))))
      }
      it("should return Some(firstMatching,rest) on List with a filter even if its the first element") {
        val r = removeFirst(List("One", "Two", "Three", "Four", "Five", "Six"), (e: String) => e == "One")
        r should be(Some(("One", List("Two", "Three", "Four", "Five", "Six"))))
      }
      it("should be fast on small lists when first matches") {
        val list = List("One", "Two", "Three", "Four", "Five", "Six")
        val duration = 200 microsecond;
        val x = time("removeFirst on small list (first)", 10000, 10000) {
          removeFirst(list, (i: String) => i == "One")  match {
            case Some((e, s :: _)) =>
              e should be("One")
              s should be("Two")
            case other => fail(other.toString)
          }
        } should(be < duration)
      }
      it("should be fast on small lists when last matches") {
        val list = List("One", "Two", "Three", "Four", "Five", "Six")
        val expected = Some(("Six", List("One", "Two", "Three", "Four", "Five")))
        val duration = 200 microsecond;
        val x = time("removeFirst on small list (last)", 10000) {
          val value = removeFirst(list, (i: String) => i == "Six")  match {
            case Some((e, s :: _)) =>
              e should be("Six")
              s should be("One")
            case other => fail(other.toString)
          }
        } should(be < duration)
      }
      it("should be fast on large lists when first matches") {
        val list = List("One", "Two", "Three", "Four", "Five", "Six") ::: (7 to 1000).map(_.toString).foldLeft(List[String]())((l,e) => e :: l)
        val duration = 200 microsecond;
        val x = time("removeFirst on large list (first)", 10000) {
          removeFirst(list, (i: String) => i == "One") match {
            case Some((e, s :: _)) =>
              e should be("One")
              s should be("Two")
            case other => fail(other.toString)
          }
        
        } should(be < duration)
      }
      it("should be fast on large lists when last matches") {
        val list = List("One", "Two", "Three", "Four", "Five", "Six") ::: (7 to 1000).map(_.toString).foldLeft(List[String]())((l,e) => e :: l)
        val duration = 200 microsecond;
        val x = time("removeFirst on large list (last)", 10000) {
          removeFirst(list, (i: String) => i == "1000") match {
            case Some((e, s :: _)) =>
              e should be("1000")
              s should be("One")
            case other => fail(other.toString)
          }
        
        } should(be < duration)
      }
    }
  
    describe("removeLast") {
      it("should return None on empty List") {
        val r = removeLast(Nil, always)
        r should be(None)
      }
      it("should return Some(last,Nil) on one element List with always matcher") {
        val r = removeLast(List("One"), always)
        r should be(Some(("One", Nil)))
      }
      it("should return Some(last,head) on two element List with always matcher") {
        val r = removeLast(List("One", "Two"), always)
        r should be(Some(("Two", List("One"))))
      }
      it("should return Some(first,tail) on List with always matcher") {
        val r = removeLast(List("One", "Two", "Three", "Four", "Five", "Six"), always)
        r should be(Some(("Six", List("One", "Two", "Three", "Four", "Five"))))
      }
      it("should return None if no element matches") {
        val r = removeLast(List("One", "Two", "Three", "Four", "Five", "Six"), never)
        r should be(None)
      }
      it("should return Some(firstMatching,rest) on List with a filter") {
        val r = removeLast(List("One", "Two", "Three", "Four", "Five", "Six"), (e: String) => e == "Three")
        r should be(Some(("Three", List("One", "Two", "Four", "Five", "Six"))))
      }
      it("should return Some(firstMatching,rest) on List with a filter even if its the first element") {
        val r = removeLast(List("One", "Two", "Three", "Four", "Five", "Six"), (e: String) => e == "One")
        r should be(Some(("One", List("Two", "Three", "Four", "Five", "Six"))))
      }
      it("should return Some(firstMatching,rest) on List with a filter even if its the last element") {
        val r = removeLast(List("One", "Two", "Three", "Four", "Five", "Six"), (e: String) => e == "Six")
        r should be(Some(("Six", List("One", "Two", "Three", "Four", "Five"))))
      }
      it("should be fast on small lists when first matches") {
        val list = List("One", "Two", "Three", "Four", "Five", "Six")
        val duration = 200 microsecond;
        val x = time("removeLast on small list (first)", 10000, 10000) {
          removeLast(list, (i: String) => i == "One")  match {
            case Some((e, s :: _)) =>
              e should be("One")
              s should be("Two")
            case other => fail(other.toString)
          }
        } should(be < duration)
      }
      it("should be fast on small lists when last matches") {
        val list = List("One", "Two", "Three", "Four", "Five", "Six")
        val expected = Some(("Six", List("One", "Two", "Three", "Four", "Five")))
        val duration = 200 microsecond;
        val x = time("removeLast on small list (last)", 10000) {
          val value = removeLast(list, (i: String) => i == "Six")  match {
            case Some((e, s :: _)) =>
              e should be("Six")
              s should be("One")
            case other => fail(other.toString)
          }
        } should(be < duration)
      }
      it("should be fast on large lists when first matches") {
        val list = List("One", "Two", "Three", "Four", "Five", "Six") ::: (7 to 1000).map(_.toString).foldLeft(List[String]())((l,e) => e :: l)
        val duration = 200 microsecond;
        val x = time("removeLast on large list (first)", 10000) {
          removeLast(list, (i: String) => i == "One") match {
            case Some((e, s :: _)) =>
              e should be("One")
              s should be("Two")
            case other => fail(other.toString)
          }
        
        } should(be < duration)
      }
      it("should be fast on large lists when last matches") {
        val list = List("One", "Two", "Three", "Four", "Five", "Six") ::: (7 to 1000).map(_.toString).foldLeft(List[String]())((l,e) => e :: l)
        val duration = 200 microsecond;
        val x = time("removeLast on large list (last)", 10000) {
          removeLast(list, (i: String) => i == "1000") match {
            case Some((e, s :: _)) =>
              e should be("1000")
              s should be("One")
            case other => fail(other.toString)
          }
        
        } should(be < duration)
      }
    }
  }

  val warmups = 1000
  def time(desc: String, count: Int, warmupCount: Int = warmups)(f: => Unit) = {
    (1 to warmupCount).foreach(_ => f) //warmup
    val t0 = System.nanoTime
    (1 to count).foreach(_ => f)
    val duration = System.nanoTime - t0
    val dpi = Duration(duration / count, Nanoseconds)
    println(desc+" took "+dpi+" per item (total time: "+duration+"ns)")    
    dpi
  }

}
