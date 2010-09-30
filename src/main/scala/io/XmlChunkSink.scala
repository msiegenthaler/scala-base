package ch.inventsoft.scalabase
package io

import scala.xml._
import java.nio.charset.Charset
import process._
import Messages._
import oip._
import executionqueue._
import time._


/**
 * Sink that writes an XML-document. The root-element is statically defined and all the Elems sent to the
 * sink are added under that root. When the sink is closed the root-element will be closed.
 */
object XmlChunkSink {
  def withRoot(root: Elem): Creator1 = new Creator1 {
    override def addingChunksUnder(parent: Elem) = {
      val realParent = {
        findEqualElement(root, parent).
          getOrElse(throw new IllegalArgumentException("parent not found under root"))
      }
      new Creator2 {
        override def outputingBytesTo(sink: => Sink[Byte] @process, encoding: Charset = UTF8) =
          createByte(realParent, sink, encoding)
        override def outputingCharsTo(sink: => Sink[Char] @process) =
          createChar(realParent, sink)
      }
    }
    private def findEqualElement(elem: Elem, toFind: Elem): Option[Elem] = {
      if (elem.label==toFind.label && elem.namespace==toFind.namespace) Some(elem)
      else {
        elem.child.map(_ match {
          case sub: Elem => findEqualElement(sub, toFind)
          case _ => None
        }).find(_.isDefined) match {
          case Some(value) => value
          case None => None
        }
      }
    }

    override def outputingCharsTo(sink: => Sink[Char] @process) = createChar(findChunkParent, sink)
    override def outputingBytesTo(sink: => Sink[Byte] @process, encoding: Charset = UTF8) = createByte(findChunkParent, sink, encoding)
    def createByte(parent: Elem, s: => Sink[Byte] @process, enc: Charset) = {
      val r = root
      val sink = new XmlChunkByteSink {
        override val root = r
        override val chunkParent = parent
        override val encoding = enc
        override def openSink = s
      }
      new Creator3Impl(sink)
    }
    def createChar(parent: Elem, s: => Sink[Char] @process) = {
      val r = root
      val sink = new XmlChunkCharSink {
        override val root = r
        override val chunkParent = parent
        override def openSink = s
      }
      new Creator3Impl(sink)
    }
    def findChunkParent = root
  }

  private class Creator3Impl[T](instance: XmlChunkSink[T]) extends Creator3[T] {
    def apply(as: SpawnStrategy = SpawnAsRequiredChild) = {
      Spawner.start(instance, as)
    }
  }

  trait Creator1 extends Creator2 {
    def addingChunksUnder(parent: Elem): Creator2
  }
  trait Creator2 {
    def outputingCharsTo(sink: => Sink[Char] @process): Creator3[Char]
    def outputingBytesTo(sink: => Sink[Byte] @process, encoding: Charset = UTF8): Creator3[Byte]
  }
  trait Creator3[T] {
    def apply(as: SpawnStrategy = SpawnAsRequiredChild): XmlChunkSink[T] @process
  }

  def UTF8 = Charset.forName("UTF-8")
}


trait XmlChunkCharSink extends XmlChunkSink[Char] {
  protected[this] override def convertToTargetType(data: Seq[Char]) = data
}
trait XmlChunkByteSink extends XmlChunkSink[Byte] {
  protected val encoding: Charset
  protected[this] override def head =
    "<?xml version=\"1.0\" encoding=\""+encoding.toString+"\"?>\n"
  protected[this] override def convertToTargetType(data: Seq[Char]) =
    data.mkString.getBytes(encoding)
}
trait XmlChunkSink[Target] extends TransformingSink[Elem,Target,Seq[Char]] {
  protected val root: Elem
  /** must be an (indirect) child of root */
  protected val chunkParent: Elem

  protected[this] def convertToTargetType(data: Seq[Char]): Seq[Target]

  protected[this] def head: Seq[Char] = ""
  protected[this] override def createAccumulator = {
    val h: Seq[Char] = head ++ mkBeforeAfterFragment._1
    h
  }

  protected[this] override def process(h: Seq[Char], chunks: Seq[Elem]) = {
    val d = chunks.foldLeft(h)(_ ++ serialize(_))
    val out = convertToTargetType(d)
    (out, Nil)
  }
  protected[this] override def processEnd(h: Seq[Char]) = {
    val d = h ++ mkBeforeAfterFragment._2
    convertToTargetType(d)
  }

  protected[this] def mkBeforeAfterFragment = {
    val xml = addToElement(root, _ == chunkParent, Text("{99xml-chunk-content21}"))
    val parts = xml.toString.split("\\{99xml-chunk-content21\\}")
    if (parts.size < 2) throw new IllegalArgumentException("chunkParent is invalid (not a child of root)")
    else if (parts.size > 2) throw new IllegalArgumentException("unsupported root (contains reserved pattern)")
    (parts(0), parts(1))
  }
  protected[this] def addToElement(elem: Elem, to: Elem => Boolean, toAdd: Node): Elem = {
    if (to(elem)) {
      val nc = elem.child ++ toAdd
      elem.copy(child=nc)
    } else {
      val nc = elem.child.map { _ match {
        case e: Elem => addToElement(e, to, toAdd)
        case other => other
      }}
      elem.copy(child=nc)
    }
  }
  protected[this] def serialize(elem: Elem): Seq[Char] = {
    Utility.toXML(x=elem, minimizeTags=true).toString
  }
}
