package ch.inventsoft.scalabase
package time

import org.scalatest._
import matchers._


class TimePointSpec extends Spec with ShouldMatchers {
  describe("format to xml") {
    it("should be 1970-01-01T00:00:00Z for 0") {
      TimePoint.unixTime(0).asXmlDateTime should be("1970-01-01T00:00:00Z")
    }
    it("should be 2002-10-30T17:12:15Z for 1035997935000") {
      TimePoint.unixTime(1035997935000L).asXmlDateTime should be("2002-10-30T17:12:15Z")
    }
  }
}
