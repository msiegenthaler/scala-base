package ch.inventsoft.scalabase
package time

import org.scalatest._
import matchers._


class DurationSpec extends Spec with ShouldMatchers {
  describe("Duration") {
    describe("toString") {
      it("should include the unit as lowercase") {
        Duration(12, Seconds).toString should be("12 seconds")
        Duration(10, Minutes).toString should be("10 minutes")
        Duration(2, Days).toString should be("2 days")
      }
      it("should remove the 's if amount=1") {
        (1 second).toString should be("1 second")
        Duration(1, Microseconds).toString should be("1 microsecond")
      }
    }
    describe("equals") {
      it("should equal 1s and 1s") {
        assert((1 second) == Duration(1, Seconds)) 
        assert((5 second) == Duration(5, Seconds)) 
      }
      it("should be independend of unit") {
        assert((60 seconds) == (1 minute))
        assert((1000 ms) == (1 second))
        assert((5600000 μsec) == (5600 ms))
      }
    }
    describe("convert") {
      it("should be able to convert between ns and s") {
        (20000000000L ns).as(Seconds).toString should be("20 seconds")
      }
      it("should round properly") {
        (6400 ms).as(Seconds).toString should be("6 seconds")
        (6499 ms).as(Seconds).toString should be("6 seconds")
        (6500 ms).as(Seconds).toString should be("7 seconds")
        (6600 ms).as(Seconds).toString should be("7 seconds")
        (6000 ms).as(Seconds).toString should be("6 seconds")
      }
    }
    describe("api") {
      it("should have a fluent API") {
        val d = 2 seconds;
        d.toString should be("2 seconds")
        (1 minute).toString should be("1 minute")
      }
    }
    describe("unapply") {
      it("should be unapply with the same unit") {
        val ut = 20 seconds;
        ut match {
          case Seconds(s) =>
            s should be(20)
          case _ => fail
        }
      }
      it("should be unapply with a different unit") {
        val ut = 20 seconds;
        ut match {
          case Milliseconds(ms) =>
            ms should be(20000)
          case _ => fail
        }
      }
      it("should be unapply with a value") {
        val ut = 20 seconds;
        ut match {
        case Microseconds(20000000) => //ok
        case _ => fail
        }
      }
    }
    describe("sorting") {
      it("should recognize that 1s and 1s are equal") {
        assert((1 second).compareTo(Duration(1, Seconds))==0) 
        assert((5 second).compareTo(Duration(5, Seconds))==0) 
      }
      it("should recognize equality independend of unit") {
        assert((60 seconds).compareTo(1 minute) == 0)
        assert((1000 ms).compareTo(1 second) == 0)
        assert((5600000 μsec).compareTo(5600 ms) == 0)
      }
      it("should be able to figure out > and < with the same unit") {
        assert((1 seconds) < (2 seconds))
        assert((1 seconds) < (3 seconds))
        assert((3 seconds) > (0 seconds))
        assert((3 seconds) > (2 seconds))
      }
      it("should be able to figure out > and < with the different units") {
        assert((1 minute) < (61 seconds))
        assert((1 minute) > (59 seconds))
      }
      it("should be able to figure out >= and <= with the different units") {
        assert((1 minute) <= (61 seconds))
        assert((1 minute) >= (59 seconds))
        assert((1 minute) <= (60 seconds))
        assert((1 minute) >= (60 seconds))
      }
    }
    describe("calculate") {
      it("should be addable to the same unit") {
        (1 seconds) + (2 seconds) should be(3 seconds)
        (5 minutes) + (10 minutes) should be(15 minutes)
      }
      it("should be addable to different units") {
        (1 seconds) + (1 minute) should be(61 seconds)
        (5 minutes) + (60 seconds) should be(6 minutes)
      }
      it("should be possible to multiply with a factor") {
        (1 seconds) * 4 should be(4 seconds)
        (1 millisecond) * 1000 should be(1 second)
      }
      it("should be dividable") {
        (19 seconds) / 19 should be(1 second)
        (1 minute) / 60 should be(1 second)
      }
      it("should be addable with correct rounding") {
        ((1 minute) + (1 second)) equalsApprox(1 minute, Minutes) should be(true)
        ((1 minute) + (29 second)) equalsApprox(1 minute, Minutes) should be(true)
        ((1 minute) + (30 second)) equalsApprox(2 minutes, Minutes) should be(true)
        ((1 minute) + (60 second)) equalsApprox(2 minute, Minutes) should be(true)
        ((1 minute) + (89 second)) equalsApprox(2 minute, Minutes) should be(true)
        ((1 minute) + (90 second)) equalsApprox(3 minute, Minutes) should be(true)
      }
      it("should be addable with correct rounding while preserving the actual value") {
        ((1 minute) + (29 second) + (1 second)) equalsApprox(2 minutes, Minutes) should be(true)
      }
      it("should be subtractable from the same and different units") {
        (10 seconds) - (1 second) should be(9 seconds)
        (10 seconds) - (10 second) should be(0 seconds)
        (120 seconds) - (1 minute) should be (60 seconds)
        (1 minute) - (1 second) should be (59 seconds)
      }
      it("should support max with the same unit") {
        (10 seconds) max (11 seconds) should be (11 seconds)
        (11 seconds) max (10 seconds) should be (11 seconds)
      }
      it("should support min with the same unit") {
        (10 seconds) min (11 seconds) should be (10 seconds)
        (11 seconds) min (10 seconds) should be (10 seconds)
      }
      it("should support max with the different unit") {
        (10 minutes) max (11 seconds) should be (10 minutes)
        (11 seconds) max (10 minutes) should be (600 seconds)
      }
      it("should support min with the different unit") {
        (10 minutes) min (11 seconds) should be (11 seconds)
        (11 seconds) min (10 minutes) should be (11 seconds)
      }
      it("should support an absolute value") {
        (10 seconds).abs should be (10 seconds)
        (-10 seconds).abs should be (10 seconds)
        (-0 seconds).abs should be (0 seconds)
      }
      it("should support a isZero") {
        assert((0 seconds).isZero) 
        assert((0 ns).isZero) 
        assert((0 hours).isZero)
        assert(((1 minute) - (1 minute)).isZero)
        assert(((1 minute) - (60 seconds)).isZero)
        assert(!((1 minute) - (59 seconds)).isZero)
      }
      it("should support isPositive") {
        assert((1 second).isPositive)
        assert(!(-1 second).isPositive)
        assert(!((1 second) - (2 seconds)).isPositive)
        assert(((-1 seconds) + (2 seconds)).isPositive)
      }
      it("should support isNegative") {
        assert(!(1 second).isNegative)
        assert((-1 second).isNegative)
        assert(((1 second) - (2 seconds)).isNegative)
        assert(!((-1 seconds) + (2 seconds)).isNegative)
      }
      it("should have an approximate equals") {
        assert((125 seconds) equalsApprox(2 minutes, Minutes))
        assert((29 seconds) equalsApprox(0 minutes, Minutes))
        assert((30 seconds) equalsApprox(1 minutes, Minutes))
        assert((90 seconds) equalsApprox(2 minutes, Minutes))
        assert(!((90 seconds) equalsApprox(2 minutes, Seconds)))
        assert((90 seconds) equalsApprox(2 minutes, Hours))
      }
    }
  }
}
