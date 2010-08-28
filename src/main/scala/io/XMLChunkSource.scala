package ch.inventsoft.scalabase.io

import java.nio.charset.Charset
import ch.inventsoft.scalabase.log._
import scala.xml._
import ch.inventsoft.scalabase.process._
import ch.inventsoft.scalabase.oip._
import ch.inventsoft.scalabase.time._


/**
 * Source that reads an xml-byte/charstream and emmits xml. The xml is chunked at a defined depth
 * (default is 1). Usefull i.e. for XMPP communication.
 * @see XmlChunker
 */
object XmlChunkSource extends SpawnableCompanion[Source[Elem] with Spawnable] {
  def fromBytes(source: Source[Byte], encoding: Charset, nodeDepth: Int = 1, as: SpawnStrategy = SpawnAsRequiredChild) = {
/*
    val xmlSource = new XmlChunkSource {
      override protected type NT = Byte
      override protected val underlying = source
      override protected val depth = nodeDepth
      override protected def readFromUnderlying = {
        val data = source.read.receive
        //TODO map
        null
      }
    }
    start(as)(xmlSource)*/
  }
  def fromChars(charSource: Source[Char], nodeDepth: Int = 1, as: SpawnStrategy = SpawnAsRequiredChild) = {
    val xmlSource = new CharXmlChunkSource {
      override protected val source = charSource
      override protected val depth = nodeDepth
    }
    start(as)(xmlSource)
  }

  private trait CharXmlChunkSource extends Source[Elem] with StateServer {
    protected val depth: Int
    protected val source: Source[Char]
    protected val closeTimeout = 20 s
    protected[this] override type State = XmlChunker
    
    protected[this] override def init = XmlChunker(depth)
    protected[this] override def termination(state: State) = {
      source.close.receiveWithin(closeTimeout)
    }

    override def read = call(nextChunks(_))
    protected[this] def nextChunks(chunker: XmlChunker): (Read[Elem],XmlChunker) @processCps = {
      val data = readFromUnderlying
      data match {
        case Data(items) =>
          val newchunker = chunker + items
          val xmlChunks = newchunker.chunks.map(_.xml).filterNot(_ == None).map(_.get)
          val chunker2 = newchunker.consumeAll
          if (xmlChunks.isEmpty) {
            nextChunks(chunker2)
          } else {
            noop; (Data(xmlChunks), chunker2)
          }
        case EndOfData =>
          noop; (EndOfData, chunker)
      }
    }
    protected[this] def readFromUnderlying = source.read.receive
    
    override def close = stopAndWait
  }
}

/**
 * Splits XML into chunks on the first level.
 * I.e. <root><ele1>abcdef</ele1><ele2>aa</ele2> will return two chunks
 *  - <ele1>abcedf</ele1>
 *  - <ele2>aa</ele2>
 */
trait XmlChunker {
  /** Process a chunk of data */
  def push(data: Iterable[Char]): XmlChunker
  def +(data: Iterable[Char]) = push(data)

  /** Chunks that were discovered (earliest discovered is first in list)*/
  def chunks: List[XmlChunk]
  def hasChunks = chunks.nonEmpty
  /** Consume all the chunks (free the memory) */
  def consumeAll: XmlChunker
  def consumeChunk: (Option[XmlChunk], XmlChunker)
}
trait XmlChunk {
  /** the content of this chunk as character array */
  def chars: Iterable[Char]
  def string = new String(chars.toArray)
  /** this chunk as xml-data (if parsable) */
  lazy val xml: Option[Elem] = {
    try {
      val content = chars.toArray
      val nsdecls = context.map(_.scope).map(" "+_.buildString(null)).getOrElse("")
      val str = new StringBuilder(nsdecls.length+content.length+8).append("<a").append(nsdecls).append(">").appendAll(content).append("</a>")
      val reader = new java.io.StringReader(str.toString)
      val elem = XML.load(reader)
      elem.child.filter(_.isInstanceOf[Elem]).headOption.asInstanceOf[Option[Elem]]
    } catch {
      case e: Exception => None
    }
  }
  def context: Option[Elem]
  override def toString = string
}

object XmlChunker extends Log {
  def apply(depth: Int = 1) = new XmlChunkers(depth).init

  //TODO cdata handling
  //TODO max size of parsed stuff (1Mb or so): mostly collected and elementData

  private class XmlChunkImpl(val chars: Iterable[Char], val context: Option[Elem]) extends XmlChunk

  private class XmlChunkers(rootDepth: Int) {
    def init: XmlChunker = LookingForElement(Nil, Nil, Nil, 0)

    private case class LookingForElement(parents: List[Elem], chunks: List[XmlChunk], collected: Iterable[Char], depth: Int) extends XmlChunker {
      override def push(data: Iterable[Char]) = {
        val (h,t) = data.span(_ != '<')
        val nc = collected ++ h
        if (t.nonEmpty) InElementTag(parents, chunks, nc, depth, Nil).push(t)
        else copy(collected = nc)
      }
      override def consumeAll = copy(chunks = Nil)
      override def consumeChunk = chunks match {
        case chunk :: rest => (Some(chunk), copy(chunks = rest))
        case Nil => (None, this)
      }
    }
    private case class InElementTag(parents: List[Elem], chunks: List[XmlChunk], collected: Iterable[Char], depth: Int, elementData: Iterable[Char]) extends XmlChunker {
      override def push(data: Iterable[Char]) = {
        val (h,t) = data.span(_ != '>')
        if (t.nonEmpty) {
          val tail = t.drop(1)
          val tag = elementData ++ h ++ ">"
          val chunk = collected ++ tag
          if (tag.drop(1).head == '/') {
            //Close of element
            handleElementClose(tag, chunk).push(tail)
          } else if (tag.takeRight(2).head == '/') {
            //Inline close
            handleElementOpenInlineClose(tag, chunk).push(tail)
          } else {
            //Opening tag
            handleElementOpen(tag, chunk).push(tail)
          }
        } else copy(elementData = elementData ++ h)
      }
      /** tag = <element> */
      protected def handleElementOpen(tag: Iterable[Char], chunk: Iterable[Char]): XmlChunker = {
        if (depth < rootDepth) {
          parseXml(tag.dropRight(1) ++ "/>") match {
            case Some(elem) =>
              LookingForElement(elem :: parents, chunks, Nil, depth+1)
            case None =>
              LookingForElement(parents, chunks, Nil, depth)
          }
        } else LookingForElement(parents, chunks, chunk, depth+1)
      }
      /** tag = <element/> */
      protected def handleElementOpenInlineClose(tag: Iterable[Char], chunk: Iterable[Char]): XmlChunker = {
        if (depth == rootDepth) LookingForElement(parents, chunks ::: List(mkChunk(chunk)), Nil, depth)
        else LookingForElement(parents, chunks, chunk, depth)
      }
      /** tag = </element> */
      protected def handleElementClose(tag: Iterable[Char], chunk: Iterable[Char]): XmlChunker = {
        if (depth <= rootDepth) {
          val newDepth = (depth - 1) max 0
          LookingForElement(parents.drop(1), chunks, Nil, newDepth)
        } else if (depth == rootDepth+1) {
          val newChunks = if (chunk.isEmpty) chunks else chunks ::: List(mkChunk(chunk))
          LookingForElement(parents, newChunks, Nil, depth-1)
        } else LookingForElement(parents, chunks, chunk, depth-1)
      }

      protected def parseXml(data: Iterable[Char]): Option[Elem] = {
        try {
          val reader = new java.io.CharArrayReader(data.toArray)
          Some(XML.load(reader))
        } catch { case e: Exception =>
          log.debug("Invalid XML received (possible root): {}", e)
          None
        }
      }
      protected def mkChunk(data: Iterable[Char]): XmlChunk = {
        val root = parents match {
          case value :: Nil => Some(value)
          case first :: rest =>
            val elem = rest.foldLeft(first)((child,e) => e.copy(child = child :: Nil))
            Some(elem)
          case Nil => None
        }
        new XmlChunkImpl(data, root)
      }
      override def consumeAll = copy(chunks = Nil)
      override def consumeChunk = chunks match {
        case chunk :: rest => (Some(chunk), copy(chunks = rest))
        case Nil => (None, this)
      }
    }
  }

  private val cdataStart = "<![CDATA[".toCharArray
  private val cdataEnd = "]]>".toCharArray
}
