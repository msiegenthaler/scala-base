package ch.inventsoft.scalabase.binary

import org.scalatest._
import matchers._
import BytesParsing._

class BytesParsingSpec  extends Spec with ShouldMatchers {
  describe("Bytes Parsing") {
    describe("integer") {
      it("should serialize when 0") {
        val data = integer(0)
        data should be((0 :: 0 :: 0 :: 0 :: Nil).map(_.toByte))
      }
      it("should serialize when < 128") {
        val data = integer(124)
        data should be((0 :: 0 :: 0 :: 124 :: Nil).map(_.toByte))
      }
      it("should serialize when 127 < x < 255") {
        val data = integer(200)
        data should be((0 :: 0 :: 0 :: 200 :: Nil).map(_.toByte))
      }
      it("should serialize when 255") {
        val data = integer(255)
        data should be((0 :: 0 :: 0 :: 255 :: Nil).map(_.toByte))
      }
      it("should serialize when 256") {
        val data = integer(256)
        data should be((0 :: 0 :: 1 :: 0 :: Nil).map(_.toByte))
      }
      it("should serialize when MAX_VALUE") {
        val data = integer(Integer.MAX_VALUE)
        data should be((127 :: 255 :: 255 :: 255 :: Nil).map(_.toByte))
      }
      it("should serialize when MIN_VALUE") {
        val data = integer(Integer.MIN_VALUE)
        data should be((128 :: 0 :: 0 :: 0 :: Nil).map(_.toByte))
      }
      it("should serialize when -1") {
        val data = integer(-1)
        data should be((255 :: 255 :: 255 :: 255 :: Nil).map(_.toByte))
      }
    
      it("should parse when 0") {
        val data = (0 :: 0 :: 0 :: 0 :: Nil).map(_.toByte)
        data match {
          case integer(value, Nil) =>
            value should be(0)
          case _ => fail
        }
      }
      it("should parse when 12") {
        val data = (0 :: 0 :: 0 :: 12 :: Nil).map(_.toByte)
        data match {
          case integer(value, Nil) =>
            value should be(12)
          case _ => fail
        }
      }
      it("should parse when 255") {
        val data = (0 :: 0 :: 0 :: 255 :: Nil).map(_.toByte)
        data match {
          case integer(value, Nil) =>
            value should be(255)
          case _ => fail
        }
      }
      it("should parse when 256") {
        val data = (0 :: 0 :: 1 :: 0 :: Nil).map(_.toByte)
        data match {
          case integer(value, Nil) =>
            value should be(256)
          case _ => fail
        }
      }
      it("should parse when MAX_VALUE") {
        val data = (127 :: 255 :: 255 :: 255 :: Nil).map(_.toByte)
        data match {
          case integer(value, Nil) =>
            value should be(Integer.MAX_VALUE)
          case _ => fail
        }
      }
      it("should parse when MIN_VALUE") {
        val data = (128 :: 0 :: 0 :: 0 :: Nil).map(_.toByte)
        data match {
          case integer(value, Nil) =>
            value should be(Integer.MIN_VALUE)
          case _ => fail
        }
      }
      it("should parse when -1") {
        val data = (255 :: 255 :: 255 :: 255 :: Nil).map(_.toByte)
        data match {
          case integer(value, Nil) =>
            value should be(-1)
          case _ => fail
        }
      }
    
      it("should parse tail") {
        val data = (3 :: 0 :: 0 :: 1 :: 0 :: Nil).map(_.toByte)
        data match {
          case a :: integer(value, Nil) =>
            a should be(3)
            value should be(256)
          case _ => fail
        }
      }
      it("should parse head") {
        val data = (0 :: 0 :: 1 :: 0 :: 3 :: Nil).map(_.toByte)
        data match {
          case integer(value, 3 :: Nil) =>
            value should be(256)
          case _ => fail
        }
      }
      it("should parse tail with no rest") {
        val data = (3 :: 0 :: 0 :: 1 :: 0 :: Nil).map(_.toByte)
        data match {
          case a :: integer(value, rest) =>
            a should be(3)
            value should be(256)
            rest should be(Nil)
          case _ => fail
        }
      }
      it("should parse partial in middle with exact before and after") {
        val data = (3 :: 0 :: 0 :: 1 :: 0 :: 2 :: 3 :: 4 :: Nil).map(_.toByte)
        data match {
          case a :: integer(value, rest) =>
            a should be(3)
            value should be(256)
            rest should be(List(2, 3, 4))
          case _ => fail
        }
      }
      it("should parse partial in middle with exact before and after with unapply") {
        val data = (3 :: 0 :: 0 :: 1 :: 0 :: 2 :: 3 :: 4 :: Nil).map(_.toByte)
        data match {
          case 3 :: integer(value, 2 :: 3 :: 4 :: Nil) =>
            value should be(256)
          case _ => fail
        }
      }
    }
    describe("long") {
      def testLong(value: Long, serializedI: List[Int]) {
        val serialized = serializedI.map(_.toByte)
        long(value) should be(serialized)
        serialized match {
          case long(v, rest) =>
            v should be(value)
            rest should be(Nil)
          case otherwise => fail
        }
      }
      it("should be possible to write and read 0") {
        testLong(0, 0 :: 0 :: 0 :: 0 :: 0 :: 0 :: 0 :: 0 :: Nil)
      }
      it("should be possible to write and read 25") {
        testLong(25, 0 :: 0 :: 0 :: 0 :: 0 :: 0 :: 0 :: 25 :: Nil)
      }
      it("should be possible to write and read 255") {
        testLong(255, 0 :: 0 :: 0 :: 0 :: 0 :: 0 :: 0 :: 255 :: Nil)
      }
      it("should be possible to write and read 256") {
        testLong(256, 0 :: 0 :: 0 :: 0 :: 0 :: 0 :: 1 :: 0 :: Nil)
      }
      it("should be possible to write and read Long.MAX_VALUE") {
        testLong(Long.MaxValue, 127 :: 255 :: 255 :: 255 :: 255 :: 255 :: 255 :: 255 :: Nil)
      }
      it("should be possible to write and read Long.MIN_VALUE") {
        testLong(Long.MinValue, 128 :: 0 :: 0 :: 0 :: 0 :: 0 :: 0 :: 0 :: Nil)
      }
      it("should be possible to write and read -1") {
        testLong(-1, 255 :: 255 :: 255 :: 255 :: 255 :: 255 :: 255 :: 255 :: Nil)
      }
    }
    describe("short") {
      def testShort(value: Short, serializedI: List[Int]) {
        val serialized = serializedI.map(_.toByte)
        short(value) should be(serialized)
        serialized match {
          case short(v, rest) =>
            v should be(value)
            rest should be(Nil)
          case otherwise => fail
        }
      }
      it("should be possible to write and read 0") {
        testShort(0, 0 :: 0 :: Nil)
      }
      it("should be possible to write and read 25") {
        testShort(25, 0 :: 25 :: Nil)
      }
      it("should be possible to write and read 255") {
        testShort(255, 0 :: 255 :: Nil)
      }
      it("should be possible to write and read 256") {
        testShort(256, 1 :: 0 :: Nil)
      }
      it("should be possible to write and read Short.MAX_VALUE") {
        testShort(Short.MaxValue, 127 :: 255 :: Nil)
      }
      it("should be possible to write and read Short.MIN_VALUE") {
        testShort(Short.MinValue, 128 :: 0 :: Nil)
      }
      it("should be possible to write and read -1") {
        testShort(-1, 255 :: 255 :: Nil)
      }
    }
    describe("byte") {
      it("should write all byte values") {
        val bs = (0 to 255).map(_.toByte)
        bs.foreach { b =>
          byte(b) should be(List(b))
        }
      }
      it("should parse all byte values") {
        val bs = (0 to 255).map(_.toByte)
        bs.foreach { b =>
          val d = b :: Nil
          d match {
            case byte(v, rest) =>
              v should be(b)
              rest should be(Nil)
            case _ => fail
          }
        }
      }
    }
    describe("bit byte") {
      it("should support writing single bit byte values") {
        bit_byte((false, false, false, false, false, false, false, false)) should be(0 :: Nil)
        bit_byte((false, false, false, false, false, false, false, true)) should be(1 :: Nil)
        bit_byte((false, true, false, true, false, true, false, true)) should be(85 :: Nil)
      }
      it("should support reading single bit byte values") {
        0 :: Nil map(_.toByte) match {
          case bit_byte((a,b,c,d,e,f,g,h),Nil) =>
            a should be(false)
            b should be(false)
            c should be(false)
            d should be(false)
            e should be(false)
            f should be(false)
            g should be(false)
            h should be(false)
        }
        1 :: Nil map(_.toByte) match {
          case bit_byte((a,b,c,d,e,f,g,h),Nil) =>
            a should be(false)
            b should be(false)
            c should be(false)
            d should be(false)
            e should be(false)
            f should be(false)
            g should be(false)
            h should be(true)
        }
        85 :: Nil map(_.toByte) match {
          case bit_byte((a,b,c,d,e,f,g,h),Nil) =>
            a should be(false)
            b should be(true)
            c should be(false)
            d should be(true)
            e should be(false)
            f should be(true)
            g should be(false)
            h should be(true)
        }
      }
    }
  
    describe("nice syntax byte parsing") {
      it("should allow easy composition (3 parts)") {
        val p = <<(integer, byte, integer)>>;
        p((500, 23, 300)) should be(List(
          0, 0, 1, -12,
          23,
          0, 0, 1, 44
        ))
        List(0,0,1,-12,23,0,0,1,44).map(_.toByte) match {
          case p(v,rest) =>
            v should be((500,23,300))
          case _ => fail
        }
        checkRoundtrip(p)((12, 13, 14), (123441, 12, Integer.MIN_VALUE))
      }
      it("should allow decomposition (3 parts) with partial data") {
        val p = <<(byte, byte, byte)>>;
        val data = List(8,1,3).map(_.toByte)
        p.unapply(Nil) should be(None)
        p.unapply(data.take(1)) should be(None)
        p.unapply(data.take(2)) should be(None)
        p.unapply(data.take(3)) should be(Some(((8,1,3),Nil)))
      }    
      it("should allow decomposition (3 parts) with partial data and list at end") {
        val p = <<(byte, byte, list_to_end(byte))>>;
        val data = List(8,1,3).map(_.toByte)
        p.unapply(Nil) should be(None)
        p.unapply(data.take(1)) should be(None)
        p.unapply(data.take(2)) should be(Some(((8,1,Nil),Nil)))
        p.unapply(data.take(3)) should be(Some(((8,1,List(3)),Nil)))
      }    
      it("should allow mapped decomposition (3 parts) with partial data") {
        val p = <<(byte, byte, byte)>>>(
            (e: Int) => (1,2,3),
            t => Some(t._1.toInt+t._2+t._3)
        )
        val data = List(8,1,3).map(_.toByte)
        p.unapply(Nil) should be(None)
        p.unapply(data.take(1)) should be(None)
        p.unapply(data.take(2)) should be(None)
        p.unapply(data.take(3)) should be(Some(((12),Nil)))
      }    
      it("should allow mapped decomposition (5 parts) with partial data") {
        val p = <<(byte, byte, byte, byte, byte)>>>(
            (e: Int) => (1,2,3,4,5),
            t => Some(t._1.toInt+t._2+t._3+t._4+t._5)
        )
        val data = List(8,1,3,6,4).map(_.toByte)
        p.unapply(Nil) should be(None)
        p.unapply(data.take(1)) should be(None)
        p.unapply(data.take(2)) should be(None)
        p.unapply(data.take(3)) should be(None)
        p.unapply(data.take(4)) should be(None)
        p.unapply(data.take(5)) should be(Some(((22),Nil)))
      }    
      it("should allow mapped values a bit ugly") {
        case class TestClass(a: Int, b: Int, c: Byte)
      
        val t2tc = (t: Tuple3[Int,Int,Byte]) => Some(TestClass(t._1,t._2,t._3))
        val tc2t: TestClass => Tuple3[Int,Int,Byte] = (v: TestClass) => (v.a,v.b,v.c)
        val p = <<(integer, integer, byte)>>>(tc2t,t2tc);
        checkRoundtrip(p)(TestClass(1232123, 12123124, 2), TestClass(0,0,0), TestClass(-3,-4,-5))
      }
      it("should allow mapped values") {
        case class TestClass(a: Int, b: Int, c: Byte)
        val p = <<(integer, integer, byte)>>>(
            (v: TestClass) => {
              (v.a, v.b, v.c)
            },
            t => {
              val (a,b,c) = t
              Some(TestClass(a,b,c))
            }
        );
        checkRoundtrip(p)(TestClass(1232123, 12123124, 2), TestClass(0,0,0), TestClass(-3,-4,-5))
      }
      it("should allow to define a class/object from a thing") {
        object P extends FragmentClass[(Int,Int,Byte)] {
          override protected[this] val definition = <<(integer, integer, byte)>>
        }
        P((1,2,3)) should be(0 :: 0 :: 0 :: 1 :: 0 :: 0 :: 0 :: 2 :: 3 :: Nil)
      }
      it("should allow to define a mapped class/object from a thing") {
        case class TestClass(a: Int, b: Int, c: Byte)

        object P extends MappedFragmentClass[TestClass,(Int,Int,Byte)] {
          override protected[this] val rawDefinition = <<(integer, integer, byte)>>
          override protected[this] def toValue(t: (Int,Int,Byte)) = Some(TestClass(t._1, t._2, t._3))
          override protected[this] def fromValue(value: TestClass) = (value.a, value.b, value.c)
        }
        checkRoundtrip(P)(TestClass(1232123, 12123124, 2), TestClass(0,0,0), TestClass(-3,-4,-5))
      }
    }
    describe("nested byte parsing") {
      it("should be easy to nest structures") {
        val p1 = <<(integer, byte)>>
        val p = <<(p1, integer)>>;
        List(0,0,1,-12,23,0,0,1,44).map(_.toByte) match {
          case p(v,rest) =>
            v should be(((500,23),300))
          case _ => fail
        }
        checkRoundtrip(p)(((12, 13), 14), ((123441, 12), Integer.MIN_VALUE))
      }
      it("should be possible to expect certain values") {
        val p1 = <<(integer, byte)>>
        val p = <<(fix_integer(12345), p1)>>;
        List(0,0,48,57, 0,0,0,1, 121).map(_.toByte) match {
          case p(((),(a,b)),rest) =>
            a should be(1)
            b should be(121)
            rest should be(Nil)
          case _ => fail
        }
        checkRoundtrip(p)(
          ((),(3454,1)),
          ((),(1,-1)),
          ((),(0,0))
        )
      }
      it("should be possible to 'exclude' values") {
        val p = <<(fix_byte(12), byte)>>;
        val p2 = p.drop1
        p2(13) should be(12 :: 13 :: Nil)
        val data = (12 :: 13 :: Nil).map(_.toByte)
        data match {
          case p2(v, Nil) => v should be(13)
          case _ => fail
        }
      }
      it("should be possible to 'exclude' multiple values") {
        val p = <<(fix_byte(12), byte, fix_byte(14))>>;
        val p2 = p.drop1.drop2
        p2(13) should be(12 :: 13 :: 14 :: Nil)
        val data = (12 :: 13 :: 14 :: Nil).map(_.toByte)
        data match {
          case p2(v, Nil) => v should be(13)
          case _ => fail
        }
      }
      it("should be possible to expect certain values with map") {
        val p1 = <<(integer, byte)>>
        val p = <<(fix_integer(12345), p1)>>>( (value: (Int,Byte)) => {
            ((),value)
          },
          value => {
            Some(value._2)
          }
        )
        List(0,0,48,57, 0,0,0,1, 121).map(_.toByte) match {
          case p((a,b), rest) =>
            a should be(1)
            b should be(121)
            rest should be(Nil)
          case _ => fail
        }
        checkRoundtrip(p)(
          (3454,1),
          (1,-1),
          (0,0)
        )
      }
      it("should be possible to expect a prefix") {
        val p = prefix(fix_integer(12345), <<(integer,byte)>>)
        List(0,0,48,57, 0,0,0,1, 121).map(_.toByte) match {
          case p((a,b), rest) =>
            a should be(1)
            b should be(121)
            rest should be(Nil)
          case _ => fail
        }
        checkRoundtrip(p)(
          (3454,1),
          (1,-1),
          (0,0)
        )
      }
    }
    describe("optional values") {
      it("should support an optional value at the end (value present)") {
        val p = <<( byte, optional(short) )>>
        val data = 1 :: 2 :: 3 :: Nil map(_.toByte)
        data match {
          case p((a, b), rest) =>
            a should be(1)
            b should be(Some(515))
            rest should be(Nil)
          case _ => fail
        }
      }
      it("should support an optional value at the end (value not present)") {
        val p = <<( byte, optional(short) )>>
        val data = 1 :: Nil map(_.toByte)
        data match {
          case p((a, b), rest) =>
            a should be(1)
            b should be(None)
            rest should be(Nil)
          case _ => fail
        }
      }
      it("should support an optional value at the end (incomplete value)") {
        val p = <<( byte, optional(short) )>>
        val data = 1 :: 2 :: Nil map(_.toByte)
        data match {
          case p((a, b), rest) =>
            a should be(1)
            b should be(None)
            rest should be(2 :: Nil)
          case _ => fail
        }
      }
      it("should support an optional value at the end (value present) with rest") {
        val p = <<( byte, optional(short) )>>
        val data = 1 :: 2 :: 3 :: 4 :: 5 :: Nil map(_.toByte)
        data match {
          case p((a, b), rest) =>
            a should be(1)
            b should be(Some(515))
            rest should be(4 :: 5 :: Nil)
          case _ => fail
        }
      }
    }
    describe("list byte parsing") {
      it("should be possible to parse bytes till the end") {
        val p = list_to_end(byte)
        List(1,2,3,4,5,6,7,8,9,10).map(_.toByte) match {
          case p(list,rest) =>
            list should be(List(1,2,3,4,5,6,7,8,9,10).map(_.toByte))
            rest should be(Nil)
          case _ => fail 
        }
      }
      it("should be possible to parse single byte") {
        val p = list_to_end(byte)
        List(1).map(_.toByte) match {
          case p(list,rest) =>
            list should be(List(1).map(_.toByte))
            rest should be(Nil)
          case _ => fail 
        }
      }
      it("should be possible to parse ints till the end") {
        val p = list_to_end(integer)
        List(0,0,0,1, 0,0,0,2, 0,0,0,3, 0,0,0,4).map(_.toByte) match {
          case p(list,rest) =>
            list should be(List(1,2,3,4))
            rest should be(Nil)
          case _ => fail 
        }
      }
      it("should be possible to parse single int") {
        val p = list_to_end(integer)
        List(0,0,0,1).map(_.toByte) match {
          case p(list,rest) =>
            list should be(List(1))
            rest should be(Nil)
          case _ => fail 
        }
      }
      it("should be possible to parse a fixed count of bytes") {
        val p = list(3, byte)
        List(1,2,3,4,5).map(_.toByte) match {
          case p(list,rest) =>
            list should be(List(1,2,3).map(_.toByte))
            rest should be(List(4,5).map(_.toByte))
        }
      }
      it("should be possible to parse a fixed count of bytes to the end") {
        val p = list(5, byte)
        List(1,2,3,4,5).map(_.toByte) match {
          case p(list,rest) =>
            list should be(List(1,2,3,4,5).map(_.toByte))
            rest should be(Nil)
        }
      }
      it("should be possible to parse a fixed count of ints") {
        val p = list(3, integer)
        List(0,0,0,1, 0,0,0,2, 0,0,0,3, 0,0,0,4).map(_.toByte) match {
          case p(list,rest) =>
            list should be(List(1,2,3))
            rest should be(List(0,0,0,4).map(_.toByte))
        }
      }
      it("should be possible to parse a fixed count of ints till the end") {
        val p = list(4, integer)
        List(0,0,0,1, 0,0,0,2, 0,0,0,3, 0,0,0,4).map(_.toByte) match {
          case p(list,rest) =>
            list should be(List(1,2,3,4))
            rest should be(Nil)
        }
      }
      it("should be possible to parse a list based on a preample") {
        val pre = <<( fix_byte(10), byte )>>
        val p = list(pre, (t: (Unit,Byte)) => t._2, (v: (Unit,Byte),l) => ((),l.toByte), byte)
        List(10, 3, 1, 2, 3) map(_.toByte) match {
          case p((pre,list),rest) =>
            pre should be(((),3))
            list should be(List(1,2,3))
            rest should be(Nil)
          case otherwise => fail
        }
      }
      it("should be possible to parse a list based on a preample with suffix") {
        val pre = <<( fix_byte(10), byte )>>
        val p = list(pre, (t: (Unit,Byte)) => t._2, (v: (Unit,Byte),l) => ((),l.toByte), byte)
        List(10, 5, 1, 2, 3, 4, 5, 12) map(_.toByte) match {
          case p((pre,list),rest) =>
            pre should be(((),5))
            list should be(List(1,2,3,4,5))
            rest should be(12 :: Nil)
          case otherwise => fail
        }
      }
      it("should be possible to parse a list based on a byte preample") {
        val p = list_byte_count(byte, byte)
        List(3, 1, 2, 3) map(_.toByte) match {
          case p(list,rest) =>
            list should be(List(1,2,3))
            rest should be(Nil)
          case otherwise => fail
        }
      }
      it("should be possible to parse a list based on a byte preample with suffix") {
        val p = list_byte_count(byte, byte)
        List(5, 1, 2, 3, 4, 5, 12) map(_.toByte) match {
          case p(list,rest) =>
            list should be(List(1,2,3,4,5))
            rest should be(12 :: Nil)
          case otherwise => fail
        }
      }
      it("should be possible to parse a list based on a mapped byte preample") {
        val pre = <<( fix_byte(10), byte, fix_byte(11) )>>>(
          (v: Byte) => ((),v,()),
          t => Some(t._2)
        )
        val p = list_byte_count(pre, byte)
        List(10, 3, 11, 1, 2, 3) map(_.toByte) match {
          case p(list,rest) =>
            list should be(List(1,2,3))
            rest should be(Nil)
          case otherwise => fail
        }
      }
      it("should be possible to parse a list based on a byte preample when length > 127") {
        val p = list_byte_count(byte, byte)
        val inList = (1 to 200).foldLeft[List[Byte]](Nil)((l,e) => e.toByte :: l)
        200.toByte :: inList map(_.toByte) match {
          case p(list,rest) =>
            list.length should be(200)
            list should be(inList)
            rest should be(Nil)
          case otherwise => fail
        }
      }
      it("should be possible to parse a list based on a short preample") {
        val p = list_short_count(short, byte)
        List(0, 3, 1, 2, 3) map(_.toByte) match {
          case p(list,rest) =>
            list should be(List(1,2,3))
            rest should be(Nil)
          case otherwise => fail
        }
      }
      it("should be possible to parse a list based on a short preample with suffix") {
        val p = list_short_count(short, byte)
        List(0, 5, 1, 2, 3, 4, 5, 12) map(_.toByte) match {
          case p(list,rest) =>
            list should be(List(1,2,3,4,5))
            rest should be(12 :: Nil)
          case otherwise => fail
        }
      }
      it("should be possible to parse a list based on a short preample when longer than 32k") {
        val p = list_short_count(short, byte)
        val inList = (1 to 40000).foldLeft[List[Byte]](Nil)((l,e) => e.toByte :: l)
        0x9C.toByte :: 0x40.toByte :: inList map(_.toByte) match {
          case p(list,rest) =>
            list.length should be(40000)
            list should be(inList)
            rest should be(Nil)
          case otherwise => fail
        }
      }
    }
    describe("real world examples") {
      case class FrameId(id: Byte)
      def AtPreample(c1: Char, c2: Char) = {
        <<( fix_byte(0x08), byte, fix_byte(c1.toByte), fix_byte(c2.toByte) )>>>(
            (frame: FrameId) => ((),frame.id,(),()),
            (t) => Some(FrameId(t._2))
        )
      }      
      val AtMy = <<( AtPreample('M', 'Y') )>>
    
      val UnescapedPacketPreample = <<( fix_byte(0x7e), short )>>>(
          (length: Short) => ((), length),
          (t) => Some(t._2)
      )
      def calculateChecksum(of: Seq[Byte]): Byte = {
        (0xFF - (of.foldLeft(0)((a, b) => a + b) & 0xFF)).toByte
      }
      val UnescapedPacket = <<(list_short_count(UnescapedPacketPreample, byte), byte)>>>(
          (payload: Seq[Byte]) => {
            (payload, calculateChecksum(payload))
          },
          (t) => {
            val (data, checksum) = t
            if (checksum == calculateChecksum(data)) Some(data)
            else None
          }
      )
      case class Address64(id: Long)
      val Tx64 = <<( fix_byte(0x00), byte, long, fix_byte(0x00), list_to_end(byte) )>>>(
          (v: (FrameId,Address64,Seq[Byte])) => {
            val (frame,address,data) = v
            ((),frame.id,address.id,(),data)
          },
          t => {
            val (_,frame,address,(),data) = t
            Some((FrameId(frame),Address64(address),data))
          }
      )
    
      it("should be possible to parse a XBee ATMY command") {
        val data = 0x08 :: 0x52 :: 0x4D :: 0x59 :: Nil map (_.toByte)
        data match {
          case AtMy(frame, rest) =>
            frame should be(FrameId(0x52))
            rest should be(Nil)
          case other => fail
        }
      }
      it("should be possible to parse a XBee ATMY command with a useless tail") {
        val data = 0x08 :: 0x01 :: 0x4D :: 0x59 :: Nil map (_.toByte)
        val suffix = 0x12 :: 0x13 :: Nil map (_.toByte)
        data ::: suffix match {
          case AtMy(frame, rest) =>
            frame should be(FrameId(0x01))
            rest should be(suffix)
          case other => fail
        }
      }
      it("should be possible to serialize a ATMY command") {
        val expect = 0x08 :: 0x01 :: 0x4D :: 0x59 :: Nil map (_.toByte)
        val data = AtMy(FrameId(0x01))
        data should be(expect)
      }
    
      it("should be possible to parse a XBee packet") {
        val data = 0x7e :: 0x00 :: 0x04 :: 0x08 :: 0x52 :: 0x4D :: 0x59 :: 0xFF :: Nil map(_.toByte)
        val payload = 0x08 :: 0x52 :: 0x4D :: 0x59 :: Nil map (_.toByte)

        data match { 
          case UnescapedPacket(p, rest) =>
            p should be(payload)
            rest should be(Nil)
          case other => fail
        }
      }
      it("should be possible to parse a XBee packet with a useless suffix") {
        val data = 0x7e :: 0x00 :: 0x04 :: 0x08 :: 0x52 :: 0x4D :: 0x59 :: 0xFF :: 0x21 :: 0x24 :: Nil map(_.toByte)
        val payload = 0x08 :: 0x52 :: 0x4D :: 0x59 :: Nil map (_.toByte)

        data match { 
          case UnescapedPacket(p, rest) =>
            p should be(payload)
            rest should be(0x21 :: 0x24 :: Nil)
          case other => fail
        }
      }
      it("should be possible to parse a XBee packet with invalid checksum") {
        val data = 0x7e :: 0x00 :: 0x04 :: 0x08 :: 0x52 :: 0x4D :: 0x59 :: 0x04 :: Nil map(_.toByte)
        val payload = 0x08 :: 0x52 :: 0x4D :: 0x59 :: Nil map (_.toByte)

        data match { 
          case UnescapedPacket(p, rest) => fail
          case other => //ok
        }
      }
      it("should be possible to perform nested parsing on an XBee packet") {
        val data = 0x7e :: 0x00 :: 0x04 :: 0x08 :: 0x52 :: 0x4D :: 0x59 :: 0xFF :: Nil map(_.toByte)
        data match {
          case UnescapedPacket(packet @ AtMy(frame,Nil),rest) =>
            frame should be(FrameId(0x52))
            rest should be(Nil)
          case other => fail
        }
      }
      it("should be possible to parse a XBee Tx64 command") {
        val expectAddress = long.read(List(1,2,3,4,5,6,7,8).map(_.toByte)).get._1
        val expectPayload = (0x12 :: 0x32 :: 0x11 :: 0x19 :: 0x60 :: 0xFF :: 0xFE :: Nil).map(_.toByte)
      
        val data = 0x00 :: 0x01 :: 0x01 :: 0x02 :: 0x03 :: 0x04 :: 0x05 :: 0x06 :: 0x07 :: 0x08 :: 0x00 :: 0x12 :: 0x32 :: 0x11 :: 0x19 :: 0x60 :: 0xFF :: 0xFE :: Nil
        data.map(_.toByte) match {
          case Tx64((frame,to,data),rest) =>
            frame should be(FrameId(0x01))
            to should be(Address64(expectAddress))
            data should be(expectPayload)
            rest should be(Nil)
          case other => fail
        }
      }
      it("should be possible to serialize an XBee Tx64 packet") {
        val to = Address64(long.read(List(1,2,3,4,5,6,7,8).map(_.toByte)).get._1)
        val payload = (0x12 :: 0x32 :: 0x11 :: 0x19 :: 0x60 :: 0xFF :: 0xFE :: Nil).map(_.toByte)
        val a = Tx64(FrameId(0x16),to,payload)
        val data = UnescapedPacket(Tx64(FrameId(0x16),to,payload))
      
        val expect = List(126, 0, 18, 0, 22, 1, 2, 3, 4, 5, 6, 7, 8, 0, 18, 50, 17, 25, 96, -1, -2, -6)
        data should be(expect.map(_.toByte))
      }
    }
  }
  
  def checkRoundtrip[A](f: Fragment[A])(values: A*) = {
    values.foreach{ v =>
      val bytes = f(v)
      bytes match {
        case f(va, rest) =>
          va should be(v)
          rest should be(Nil)
        case _ => fail
      }
      bytes ++ (12 :: 13 :: Nil).map(_.toByte) match {
        case f(va, rest) =>
          va should be(v)
          rest should be(12 :: 13 :: Nil)
        case _ => fail
      }
    }
  }
}
