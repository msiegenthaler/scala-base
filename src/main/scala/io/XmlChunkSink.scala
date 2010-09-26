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
 * Sink that writes an XML-document. The root-element is statically defined and all the Elems sent to the sink are added under that
 * root. When the sink is closed the root-element will be closed.
 */
object XmlChunkSink extends SpawnableCompanion[XmlChunkSink] {
  def withRoot(root: Elem): Creator1 = new Creator1 {
    override def addingChunksUnder(parent: Elem) = {
      val realParent = findEqualElement(root, parent).getOrElse(throw new IllegalArgumentException("parent not found under root"))
      new Creator2 {
        override def outputingBytesTo(sink: Sink[Byte], encoding: Charset = UTF8) = createByte(realParent, sink, encoding)
        override def outputingCharsTo(sink: Sink[Char]) = createChar(realParent, sink)
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

    override def outputingCharsTo(sink: Sink[Char]) = createChar(findChunkParent, sink)
    override def outputingBytesTo(sink: Sink[Byte], encoding: Charset = UTF8) = createByte(findChunkParent, sink, encoding)
    def createByte(parent: Elem, s: Sink[Byte], enc: Charset) = {
      val r = root
      val sink = new XmlChunkByteSink {
        override val root = r
        override val chunkParent = parent
        override val sink = s
        override val encoding = enc
      }
      new Spawner(sink)
    }
    def createChar(parent: Elem, s: Sink[Char]) = {
      val r = root
      val sink = new XmlChunkCharSink {
        override val root = r
        override val chunkParent = parent
        override val sink = s
      }
      new Spawner(sink)
    }
    def findChunkParent = root
  }

  private class Spawner(instance: XmlChunkSink) extends Creator3 with SpawnableCompanion[XmlChunkSink] {
    def apply(as: SpawnStrategy = Spawn.asChild(Required)(executeForBlocking)) = {
      start(as)(instance)
    }
  }

  trait Creator1 extends Creator2 {
    def addingChunksUnder(parent: Elem): Creator2
  }
  trait Creator2 {
    def outputingCharsTo(sink: Sink[Char]): Creator3
    def outputingBytesTo(sink: Sink[Byte], encoding: Charset = UTF8): Creator3
  }
  trait Creator3 {
    def apply(as: SpawnStrategy = Spawn.asChild(Required)(executeForBlocking)): XmlChunkSink @process
  }

  def UTF8 = Charset.forName("UTF-8")
}

trait XmlChunkByteSink extends XmlChunkSink {
  protected val encoding: Charset

  override protected type To = Byte
  override protected[this] def writeHead = {
    writeToSink("<?xml version=\"1.0\" encoding=\""+encoding.toString+"\"?>\n")
  }
  override protected[this] def writeToSink(string: String) = {
    val bytes = string.getBytes(encoding)
    sink.write(bytes).await
  }
}
trait XmlChunkCharSink extends XmlChunkSink {
  override protected type To = Char
  override protected[this] def writeToSink(string: String) = {
    sink.write(string).await
  }
}

trait XmlChunkSink extends Sink[Elem] with StateServer {
  protected type To
  protected val sink: Sink[To]
  protected val root: Elem
  /** must be an (indirect) child of root */
  protected val chunkParent: Elem
  protected val closeTimeout = 20 s

  protected[this] override type State = Unit
  protected[this] override def init = {
    writeHead
    val (before,_) = mkBeforeAfterFragment
    writeToSink(before)
    noop
  }
  protected[this] def writeHead = noop
  protected[this] override def termination(state: State) = {
    val (_,after) = mkBeforeAfterFragment
    writeToSink(after)
    sink.close.await(closeTimeout)
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
  
  override def write(items: Seq[Elem]) = get { _ =>
    writeElems(items)
  }
  override def writeCast(items: Seq[Elem]) = cast { _ =>
    writeElems(items)
    ()
  }

  protected[this] def writeElems(items: Seq[Elem]): Unit @process = items.foreach_cps { elem =>
    val string = xmlToString(elem)
    writeToSink(string)
  }

  protected[this] def xmlToString(elem: Elem) = {
    Utility.toXML(x=elem, minimizeTags=true).toString
  }
  protected[this] def writeToSink(string: String): Unit @process

  override def close = stopAndWait
}
