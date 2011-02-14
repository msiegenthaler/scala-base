package ch.inventsoft.scalabase
package binary

/**
 * Infrastructure to parse byte streams into objects. Use with binary protocols.
 * 
 * Example definition:
 *    <code>
 *    val mystruct = <<(integer, byte, integer)>>;
 *    </code>
 *    
 *    to serialize use apply:
 *    <code>
 *      mystruct(500, 23, 300) should be(List(
 *        0, 0, 1, -12,
 *        23,
 *        0, 0, 1, 44
 *      ))
 *    </code>
 *      
 *    and to parse use unapply:
 *    <code>
 *      List(0,0,1,-12,23,0,0,1,44).map(_.toByte) match {
 *        case p(v,rest) =>
 *          v should be((500,23,300))
 *        case _ => fail
 *      }
 *    </code> 
 *     
 * You can also map the value to an arbitary value type:
 *    <code>
 *    case class MyType(age: Int, become: Byte, calc: Int)
 *    val mystruct = <<(integer, byte, integer)>>>(
 *        (value: MyType) => (value.age, value.become, value.calc),
 *        t => MyType(t._1, t._2, t._3)
 *      )
 *    </code>
 *    
 * If you use fixed fields in the byte stream, use as follows:
 *    <code>
 *    val mystruct = <<( fix_byte(0x12), integer )>>
 *    </code>
 * to exclude the fixed value from the returned structure use drop    
 *    <code>
 *    val mystruct = <<( fix_byte(0x12), integer ) drop1
 *    mystruct(0x14) should be(0x12 :: 0x00 :: 0x00 :: 0x00 :: 0x15 :: Nil) 
 *    </code>
 */
object BytesParsing {
  def <<[A](a: Fragment[A]) =
    a
  def <<[A,B](a: Fragment[A], b: Fragment[B]) =
    new ComposedTuple2Fragment(a,b)
  def <<[A,B,C](a: Fragment[A], b: Fragment[B], c: Fragment[C]) =
    new ComposedTuple3Fragment(a,b,c)
  def <<[A,B,C,D](a: Fragment[A], b: Fragment[B], c: Fragment[C], d: Fragment[D]) =
    new ComposedTuple4Fragment(a,b,c,d)
  def <<[A,B,C,D,E](a: Fragment[A], b: Fragment[B], c: Fragment[C], d: Fragment[D], e: Fragment[E]) =
    new ComposedTuple5Fragment(a,b,c,d,e)
  def <<[A,B,C,D,E,F](a: Fragment[A], b: Fragment[B], c: Fragment[C], d: Fragment[D], e: Fragment[E], f: Fragment[F]) =
    new ComposedTuple6Fragment(a,b,c,d,e,f)
  def <<[A,B,C,D,E,F,G](a: Fragment[A], b: Fragment[B], c: Fragment[C], d: Fragment[D], e: Fragment[E], f: Fragment[F], g: Fragment[G]) =
    new ComposedTuple7Fragment(a,b,c,d,e,f,g)

  val long = new LongFragment
  def fix_long(value: Long) = fix(long, value)
  val integer = new IntFragment
  def fix_integer(value: Int) = fix(integer, value)
  val short = new ShortFragment
  def fix_short(value: Short) = fix(short, value)
  val byte = new ByteFragment
  def fix_byte(value: Byte) = fix(byte, value)
  val bit_byte = new BitByteFragment
  
  def fix[A](of: Fragment[A], value: A) = new FixedValueFragment(of, value)
  def prefix[A](prefix: Fragment[Unit], data: Fragment[A]) = {
    <<(prefix,data)>>>(
      (value: A) => ((),value),
      value => Some(value._2) 
    )
  }
  
  def list_to_end[A](element: Fragment[A]) = 
    new ListToEndFragment(element)
  def list[A](count: Int, element: Fragment[A]) = 
    new ListFixedCountFragment(element, count)
  def list[A,B](preample: Fragment[A], lengthExtractor: A => Int, lengthSetter: (A,Int) => A, element: Fragment[B]) = 
    new ListWithComplexPreampleFragment(element, preample, lengthExtractor, lengthSetter)
  def list_int_count[A](preample: Fragment[Int], element: Fragment[A]) = new ListWithCountPreampleFragment[Int,A](preample, element) {
    protected override def intToCount(v: Int) = Some(v)  
    protected override def countToInt(v: Int) = v  
  }
  def list_byte_count[A](preample: Fragment[Byte], element: Fragment[A]) = new ListWithCountPreampleFragment[Byte,A](preample, element) {
    protected override def intToCount(v: Int) = {
      val b = v.toByte
      if (b.toInt != v) None
      else Some(b)
    }
    protected override def countToInt(b: Byte) = b & 0xFF  
  }
  def list_short_count[A](preample: Fragment[Short], element: Fragment[A]) = new ListWithCountPreampleFragment[Short,A](preample, element) {
    protected override def intToCount(v: Int) = {
      val s = v.toShort
      if (s.toInt != v) None
      else Some(s)
    }
    protected override def countToInt(s: Short) = s & 0xFFFF  
  }
 
  def optional[A](frag: Fragment[A]): Fragment[Option[A]] = {
    new Fragment[Option[A]] {
      override def read(data: Seq[Byte]) = {
        frag.read(data) match {
          case Some((value, rest)) => Some(Some(value), rest)
          case None => Some((None, data))
        }
      }
      override def write(value: Option[A]) = value match {
        case Some(value) => frag.write(value)
        case None => Nil
      }
    }
  }

  val nothing = new Fragment[Unit] {
    override def read(bytes: Seq[Byte]) = Some((),bytes)
    override def write(value: Unit) = Nil
  }
  
  trait FragmentClass[T] extends Fragment[T] {
    protected val definition: Fragment[T]
    override def read(bytes: Seq[Byte]) = definition.read(bytes)
    override def write(value: T) = definition.write(value)
  }
  trait MappedFragmentClass[V,T] extends FragmentClass[V] {
    protected val rawDefinition: Fragment[T]
    override protected final lazy val definition: Fragment[V] = rawDefinition.map(fromValue, toValue)
    protected def toValue(t: T): Option[V]
    protected def fromValue(value: V): T
  }
  

  /**
   * Parser and serializer
   */
  trait Fragment[T] {
    def apply(value: T) = write(value)
    def unapply(bytes: Seq[Byte]) = read(bytes)
    def >> = this
    def >>>[A](f: A => T, g: T => Option[A]) = map(f,g)
    def map[A](f: A => T, g: T => Option[A]) = new MappedFragment(f, g, this)
    def read(bytes: Seq[Byte]): Option[(T,Seq[Byte])]
    def write(value: T): Seq[Byte]
  }
  
  class MappedFragment[A,T](fromValue: A => T, toValue: T => Option[A], protected val underlying: Fragment[T]) extends Fragment[A] {
    override def read(bytes: Seq[Byte]) = underlying.read(bytes) match {
      case Some((result, rest)) =>
        toValue(result) match {
          case Some(result) => Some((result,rest))
          case None =>None
        }
      case None => None
    } 
    override def write(value: A) = 
      underlying.write(fromValue(value))
  }
  
  trait ComposedFragment[T] extends Fragment[T] {
    override def write(value: T) = {
      val values = productToList(split(value))
      values.zip(fragments).flatMap { (t) => t._2.write(t._1) }
    }
    override def read(bytes: Seq[Byte]) = {
      val init: (Seq[Byte],List[Any],Boolean) = (bytes,Nil,true)
      val (rest,resultParts,_) = fragments.foldLeft(init) { (soFar,fragment) =>
        val (bytes, eles,ok) = soFar
        if (ok) {
          fragment.read(bytes) match {
            case Some((part, rest)) =>
              (rest, part :: eles, true)
            case None =>
              (Nil, Nil, false)
          }
        } else (Nil, Nil, false)
      }
      if (resultParts.isEmpty) None
      else {
        val result = assemble(resultParts.reverse)
        Some((result, rest))
      }
    }
    protected def fragments: List[Fragment[Any]]
    protected def split(value: T): Product
    protected def assemble(parts: List[Any]): T
    private def productToList(product: Product): List[Any] = product match {
      case list: List[_] => list
      case product =>
        (0 until product.productArity).map(i => product.productElement(i)).toList
    }
    protected def anyFrag[X](fragment: Fragment[X]) = {
      new Fragment[Any] {
        override def read(bytes: Seq[Byte]) = fragment.read(bytes)
        override def write(value: Any) = fragment.write(value.asInstanceOf[X])
      }
    }
  }
    
  
  trait FixedLengthFragment[T] extends Fragment[T] {
    def length: Int
    override def read(bytes: Seq[Byte]) = {
      val myBytes = bytes.take(length)
      if (myBytes.length != length) None
      else parse(myBytes) match {
        case Some(value) => Some((value, bytes.drop(length)))
        case None => None
      } 
    }
    def parse(bytes: Seq[Byte]): Option[T]
  }
  class LongFragment extends FixedLengthFragment[Long] {
    override val length = 8
    override def parse(b: Seq[Byte]) = {
      val value = shiftB2L(b(0), 56) | shiftB2L(b(1), 48) | shiftB2L(b(2), 40) | shiftB2L(b(3), 32) | 
        shiftB2L(b(4), 24) | shiftB2L(b(5), 16) | shiftB2L(b(6), 8) | shiftB2L(b(7), 0)
      Some(value)
    }
    override def write(value: Long) = {
      shiftL2B(value, 56) :: shiftL2B(value, 48) :: shiftL2B(value, 40) :: shiftL2B(value, 32) :: 
        shiftL2B(value, 24) :: shiftL2B(value, 16) :: shiftL2B(value, 8) :: shiftL2B(value, 0) :: Nil
    }
  }
  class IntFragment extends FixedLengthFragment[Int] {
    override val length = 4
    override def parse(b: Seq[Byte]) = {
      val value = shiftB2I(b(0), 24) | shiftB2I(b(1), 16) | shiftB2I(b(2), 8) | shiftB2I(b(3), 0)
      Some(value)
    }
    override def write(value: Int) =
      shiftI2B(value, 24) :: shiftI2B(value, 16) :: shiftI2B(value, 8) :: shiftI2B(value, 0) :: Nil      
  }
  class ShortFragment extends FixedLengthFragment[Short] {
    override val length = 2
    override def parse(b: Seq[Byte]) = {
      val value = shiftB2I(b(0), 8) | shiftB2I(b(1), 0)
      Some(value.toShort)
    }
    override def write(value: Short) =
      shiftI2B(value, 8) :: shiftI2B(value, 0) :: Nil      
  }
  class ByteFragment extends FixedLengthFragment[Byte] {
    override val length = 1
    override def parse(b: Seq[Byte]) =
      Some(b.head)
    override def write(value: Byte) =
      List(value)
  }
  type Bits8 = (Boolean,Boolean,Boolean,Boolean,Boolean,Boolean,Boolean,Boolean) 
  class BitByteFragment extends MappedFragmentClass[Bits8,Byte] {
    override protected val rawDefinition = byte
    override protected def toValue(b: Byte) = {
      Some((getBit(b, 7), getBit(b, 6), getBit(b, 5), getBit(b, 4),
          getBit(b, 3), getBit(b, 2), getBit(b, 1), getBit(b, 0)))
    }
    override protected def fromValue(value: Bits8) = {
      val v = bit(7, value._1) | bit(6, value._2) | bit(5, value._3) | bit(4, value._4) |
        bit(3, value._5) | bit(2, value._6) | bit(1, value._7) | bit(0, value._8)
      v.toByte
    }
    private def getBit(value: Byte, pos: Int): Boolean =
      (bit(pos,true) & value) > 0
    private def bit(pos: Int, value: Boolean): Int = 
      if (value) 1 << pos else 0
  }
  
  implicit val defaultValueUnit = ()
  trait Tuple2Fragment[A,B] extends Fragment[(A,B)] {
    def apply(a: A, b: B) = write((a,b))
    def drop1(implicit defaultValue: A) = {
      map((v: B) => (defaultValue,v),
          t => Some(t._2))
    }
    def drop2(implicit defaultValue: B) = {
      map((v: A) => (v,defaultValue),
          t => Some(t._1))
    }
    override def >> = this
  }
  trait Tuple3Fragment[A,B,C] extends Fragment[(A,B,C)] {
    def apply(a: A, b: B, c: C) = write((a,b,c))
    def drop1(implicit defaultValue: A) = {
      val f = (v: (B,C)) => (defaultValue,v._1,v._2)
      val g = (t: (A,B,C)) => Some((t._2,t._3))
      new MappedFragment(f,g,this) with Tuple2Fragment[B,C]
    }
    def drop2(implicit defaultValue: B) = {
      val f = (v: (A,C)) => (v._1,defaultValue,v._2)
      val g = (t: (A,B,C)) => Some((t._1,t._3))
      new MappedFragment(f,g,this) with Tuple2Fragment[A,C]
    }
    def drop3(implicit defaultValue: C) = {
      val f = (v: (A,B)) => (v._1,v._2,defaultValue)
      val g = (t: (A,B,C)) => Some((t._1,t._2))
      new MappedFragment(f,g,this) with Tuple2Fragment[A,B]
    }
    override def >> = this
  }
  trait Tuple4Fragment[A,B,C,D] extends Fragment[(A,B,C,D)] {
    def apply(a: A, b: B, c: C, d: D) = write((a,b,c,d))
    def drop1(implicit defaultValue: A) = {
      val f = (v: (B,C,D)) => (defaultValue,v._1,v._2,v._3)
      val g = (t: (A,B,C,D)) => Some((t._2,t._3,t._4))
      new MappedFragment(f,g,this) with Tuple3Fragment[B,C,D]
    }
    def drop2(implicit defaultValue: B) = {
      val f = (v: (A,C,D)) => (v._1,defaultValue,v._2,v._3)
      val g = (t: (A,B,C,D)) => Some((t._1,t._3,t._4))
      new MappedFragment(f,g,this) with Tuple3Fragment[A,C,D]
    }
    def drop3(implicit defaultValue: C) = {
      val f = (v: (A,B,D)) => (v._1,v._2,defaultValue,v._3)
      val g = (t: (A,B,C,D)) => Some((t._1,t._2,t._4))
      new MappedFragment(f,g,this) with Tuple3Fragment[A,B,D]
    }
    def drop4(implicit defaultValue: D) = {
      val f = (v: (A,B,C)) => (v._1,v._2,v._3,defaultValue)
      val g = (t: (A,B,C,D)) => Some((t._1,t._2,t._3))
      new MappedFragment(f,g,this) with Tuple3Fragment[A,B,C]
    }
    override def >> = this
  }
  trait Tuple5Fragment[A,B,C,D,E] extends Fragment[(A,B,C,D,E)] {
    def apply(a: A, b: B, c: C, d: D, e: E) = write((a,b,c,d,e))
    def drop1(implicit defaultValue: A) = {
      val f = (v: (B,C,D,E)) => (defaultValue,v._1,v._2,v._3,v._4)
      val g = (t: (A,B,C,D,E)) => Some((t._2,t._3,t._4,t._5))
      new MappedFragment(f,g,this) with Tuple4Fragment[B,C,D,E]
    }
    def drop2(implicit defaultValue: B) = {
      val f = (v: (A,C,D,E)) => (v._1,defaultValue,v._2,v._3,v._4)
      val g = (t: (A,B,C,D,E)) => Some((t._1,t._3,t._4,t._5))
      new MappedFragment(f,g,this) with Tuple4Fragment[A,C,D,E]
    }
    def drop3(implicit defaultValue: C) = {
      val f = (v: (A,B,D,E)) => (v._1,v._2,defaultValue,v._3,v._4)
      val g = (t: (A,B,C,D,E)) => Some((t._1,t._2,t._4,t._5))
      new MappedFragment(f,g,this) with Tuple4Fragment[A,B,D,E]
    }
    def drop4(implicit defaultValue: D) = {
      val f = (v: (A,B,C,E)) => (v._1,v._2,v._3,defaultValue,v._4)
      val g = (t: (A,B,C,D,E)) => Some((t._1,t._2,t._3,t._5))
      new MappedFragment(f,g,this) with Tuple4Fragment[A,B,C,E]
    }
    def drop5(implicit defaultValue: E) = {
      val f = (v: (A,B,C,D)) => (v._1,v._2,v._3,v._4,defaultValue)
      val g = (t: (A,B,C,D,E)) => Some((t._1,t._2,t._3,t._4))
      new MappedFragment(f,g,this) with Tuple4Fragment[A,B,C,D]
    }
    override def >> = this
  }
  
  class ComposedTuple2Fragment[A,B](fa: Fragment[A], fb: Fragment[B]) extends ComposedFragment[(A,B)] with Tuple2Fragment[A,B] {
    override val fragments = anyFrag(fa) :: anyFrag(fb) :: Nil
    override def split(value: Tuple2[A,B]) = value
    override def assemble(parts: List[Any]) = 
      (parts(0).asInstanceOf[A], parts(1).asInstanceOf[B])
  }
  class ComposedTuple3Fragment[A,B,C](fa: Fragment[A], fb: Fragment[B], fc: Fragment[C]) extends ComposedFragment[(A,B,C)] with Tuple3Fragment[A,B,C] {
    override val fragments = anyFrag(fa) :: anyFrag(fb) :: anyFrag(fc) :: Nil
    override def split(value: Tuple3[A,B,C]) = value
    override def assemble(parts: List[Any]) = 
      (parts(0).asInstanceOf[A], parts(1).asInstanceOf[B], parts(2).asInstanceOf[C])
  }
  class ComposedTuple4Fragment[A,B,C,D](fa: Fragment[A], fb: Fragment[B], fc: Fragment[C], fd: Fragment[D]) extends ComposedFragment[(A,B,C,D)] with Tuple4Fragment[A,B,C,D] {
    override val fragments = anyFrag(fa) :: anyFrag(fb) :: anyFrag(fc) :: anyFrag(fd) :: Nil
    override def split(value: Tuple4[A,B,C,D]) = value
    override def assemble(parts: List[Any]) = 
      (parts(0).asInstanceOf[A], parts(1).asInstanceOf[B], parts(2).asInstanceOf[C], parts(3).asInstanceOf[D])
  }
  class ComposedTuple5Fragment[A,B,C,D,E](fa: Fragment[A], fb: Fragment[B], fc: Fragment[C], fd: Fragment[D], fe: Fragment[E]) extends ComposedFragment[(A,B,C,D,E)] with Tuple5Fragment[A,B,C,D,E] {
    override val fragments = anyFrag(fa) :: anyFrag(fb) :: anyFrag(fc) :: anyFrag(fd) :: anyFrag(fe) :: Nil
    override def split(value: Tuple5[A,B,C,D,E]) = value
    override def assemble(parts: List[Any]) = 
      (parts(0).asInstanceOf[A], parts(1).asInstanceOf[B], parts(2).asInstanceOf[C], parts(3).asInstanceOf[D], parts(4).asInstanceOf[E])
  }  
  class ComposedTuple6Fragment[A,B,C,D,E,F](fa: Fragment[A], fb: Fragment[B], fc: Fragment[C], fd: Fragment[D], fe: Fragment[E], ff: Fragment[F]) extends ComposedFragment[Tuple6[A,B,C,D,E,F]] {
    def apply(a: A, b: B, c: C, d: D, e: E, f: F) = write((a,b,c,d,e,f))
    
    override val fragments = anyFrag(fa) :: anyFrag(fb) :: anyFrag(fc) :: anyFrag(fd) :: anyFrag(fe) :: anyFrag(ff) :: Nil
    override def split(value: Tuple6[A,B,C,D,E,F]) = value
    override def assemble(parts: List[Any]) = 
      (parts(0).asInstanceOf[A], parts(1).asInstanceOf[B], parts(2).asInstanceOf[C], parts(3).asInstanceOf[D], parts(4).asInstanceOf[E], parts(5).asInstanceOf[F])
  }  
  class ComposedTuple7Fragment[A,B,C,D,E,F,G](fa: Fragment[A], fb: Fragment[B], fc: Fragment[C], fd: Fragment[D], fe: Fragment[E], ff: Fragment[F], fg: Fragment[G]) extends ComposedFragment[Tuple7[A,B,C,D,E,F,G]] {
    def apply(a: A, b: B, c: C, d: D, e: E, f: F, g: G) = write((a,b,c,d,e,f,g))
    
    override val fragments = anyFrag(fa) :: anyFrag(fb) :: anyFrag(fc) :: anyFrag(fd) :: anyFrag(fe) :: anyFrag(ff) :: anyFrag(fg) :: Nil
    override def split(value: Tuple7[A,B,C,D,E,F,G]) = value
    override def assemble(parts: List[Any]) = 
      (parts(0).asInstanceOf[A], parts(1).asInstanceOf[B], parts(2).asInstanceOf[C], parts(3).asInstanceOf[D], parts(4).asInstanceOf[E], parts(5).asInstanceOf[F], parts(6).asInstanceOf[G])
  }
  
  class FixedValueFragment[T](val underlying: Fragment[T], val value: T) extends Fragment[Unit] {
    override def read(data: Seq[Byte]) = {
      underlying.read(data).flatMap { v =>
        val (read, rest) = v
        if (read == value) Some((value, rest)) else None
      }
    }
    override def write(a: Unit) =
      underlying.write(value)      
  }
  trait ListFragment[A] extends Fragment[Seq[A]] {
    protected def element: Fragment[A]
    override def read(bytes: Seq[Byte]) = {
      def readNext(bytes: Seq[Byte], soFar: List[A]): Option[Tuple2[List[A],Seq[Byte]]] = {
        if (shouldAbort(bytes, soFar)) Some((soFar.reverse, bytes))
        else element.read(bytes) match {
          case Some((part,rest)) => readNext(rest, part :: soFar)
          case None => None
        }
      }
      readNext(bytes, Nil)
    }
    protected def shouldAbort(bytes: Seq[Byte], soFar: List[A]): Boolean
    override def write(values: Seq[A]) = {
      val init: Seq[Byte] = Nil
      values.foldLeft(init)((soFar,e) => soFar ++ element.write(e))
    }
  }
  class ListToEndFragment[A](override val element: Fragment[A]) extends ListFragment[A] {
    override protected def shouldAbort(bytes: Seq[Byte], soFar: List[A]) =
      bytes.isEmpty
  }
  class ListFixedCountFragment[A](override val element: Fragment[A], val count: Int) extends ListFragment[A] {
    override protected def shouldAbort(bytes: Seq[Byte], soFar: List[A]) =
      soFar.length >= count
  }
  class ListWithComplexPreampleFragment[Pre,Element](element: Fragment[Element], preample: Fragment[Pre], lengthExtractor: Pre => Int, lengthSetter: (Pre,Int) => Pre) extends Fragment[(Pre,Seq[Element])] {
    override def read(bytes: Seq[Byte]) = {
      preample.read(bytes) match {
        case Some((pre, rest)) =>
          val len = lengthExtractor(pre)
          readElement(rest, Nil, len) match {
            case Some((elements, rest)) => Some(((pre, elements), rest))
            case None => None
          }
        case otherwise => None
      }
    }
    private[this] def readElement(bytes: Seq[Byte], soFar: List[Element], left: Int): Option[(Seq[Element],Seq[Byte])] = {
      if (left > 0) {
        element.read(bytes) match {
          case Some((e,rest)) =>
            readElement(rest, e :: soFar, left-1)
          case None => None
        }
      } else Some((soFar.reverse, bytes))
    }
    override def write(values: (Pre,Seq[Element])) = {
      val (pre,elements) = values
      val preWithLength = lengthSetter(pre, elements.size)
      val init = preample.write(pre)
      elements.foldLeft(init)((soFar,e) => soFar ++ element.write(e))
    }
  }
  abstract class ListWithCountPreampleFragment[L,Element](preample: Fragment[L], element: Fragment[Element]) extends Fragment[Seq[Element]] {
    override def read(bytes: Seq[Byte]) = {
      preample.read(bytes) match {
        case Some((c, rest)) =>
          val count = countToInt(c)
          readElement(rest, Nil, count)
        case otherwise => None
      }
    }
    private[this] def readElement(bytes: Seq[Byte], soFar: List[Element], left: Int): Option[(Seq[Element],Seq[Byte])] = {
      if (left > 0) {
        element.read(bytes) match {
          case Some((e,rest)) =>
            readElement(rest, e :: soFar, left-1)
          case None => None
        }
      } else Some((soFar.reverse, bytes))
    }
    protected def intToCount(length: Int): Option[L]
    protected def countToInt(length: L): Int
    override def write(values: (Seq[Element])) = {
      val count = intToCount(values.size) match {
        case Some(c) => c
        case None => throw new IllegalArgumentException("List has invalid size: "+values.size)
      }
      val init = preample.write(count)
      values.foldLeft(init)((soFar,e) => soFar ++ element.write(e))
    }
  }
  
  private def shiftB2I(value: Byte, shiftBy: Int): Int =
      (value & 0xFF) << shiftBy
  private def shiftB2L(value: Byte, shiftBy: Int): Long =
      (value & 0xFF).toLong << shiftBy
  private def shiftI2B(value: Int, shiftBy: Int): Byte =
    ((value >> shiftBy) & 0xFF).toByte
  private def shiftL2B(value: Long, shiftBy: Int): Byte =
    ((value >> shiftBy) & 0xFF).toByte
}

