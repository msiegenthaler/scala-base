package ch.inventsoft.scalabase
package io

import scala.xml._
import java.nio.{ByteBuffer,CharBuffer}
import java.nio.charset._
import log._
import process._
import oip._
import time._


/**
 * Source that reads an xml-byte/charstream and emmits xml. The xml is chunked at a defined depth
 * (default is 1). Usefull i.e. for XMPP communication.
 * @see XmlChunker
 */
object XmlChunkSource extends SpawnableCompanion[Source[Elem] with Spawnable] {
  type ChunkFun = XmlChunk => Option[Elem]
  def returnChunksOnly(chunk: XmlChunk) =  chunk.xml
  def rootedChunks(chunk: XmlChunk) = chunk.xmlNoContext flatMap { xml => chunk.context.map { context =>
    val nc = context.child ++ xml
    context.copy(child=nc)
  }}

  def fromBytes(byteSource: Source[Byte], encoding: Charset, nodeDepth: Int = 1, chunkFun: ChunkFun = returnChunksOnly _, as: SpawnStrategy = SpawnAsRequiredChild) = {
    val xmlSource = new ByteXmlChunkSource {
      override protected val source = byteSource
      override protected val depth = nodeDepth
      override protected val charset = encoding
      override protected def mapFun(chunk: XmlChunk) = chunkFun(chunk)
    }
    start(as)(xmlSource)
  }
  def fromChars(charSource: Source[Char], nodeDepth: Int = 1, chunkFun: ChunkFun = returnChunksOnly _, as: SpawnStrategy = SpawnAsRequiredChild) = {
    val xmlSource = new CharXmlChunkSource {
      override protected val source = charSource
      override protected val depth = nodeDepth
      override protected def mapFun(chunk: XmlChunk) = chunkFun(chunk)
    }
    start(as)(xmlSource)
  }

  private trait ByteXmlChunkSource extends Source[Elem] with StateServer {
    protected val depth: Int
    protected val source: Source[Byte]
    protected val charset: Charset
    protected val closeTimeout = 20 s
    protected def mapFun(chunk: XmlChunk): Option[Elem]
    protected[this] override type State = ByteParseState

    protected[this] override def init = ByteParseState(charset.newDecoder, XmlChunker(depth))
    protected[this] override def termination(state: State) = source.close.receiveWithin(closeTimeout)

    override def read = call(nextChunks(_))
    protected[this] def nextChunks(state: State): (Read[Elem],State) @process = {
      def decode(bytes: Seq[Byte]) = {
        def decode_(in: ByteBuffer, soFar: Seq[Char]): Seq[Char] = {
          val outEstimatedSize: Int = (in.remaining*state.decoder.averageCharsPerByte).round max 2
          val out = CharBuffer.allocate(outEstimatedSize)
          state.decoder.decode(in, out, false) match {
            case CoderResult.UNDERFLOW => 
              out.flip
              soFar ++ new CharBufferSeq(out)
            case CoderResult.OVERFLOW =>
              out.flip
              decode_(in, soFar ++ new CharBufferSeq(out))
            case other => //error
              out.flip
              decode_(in, soFar ++ new CharBufferSeq(out))
          }
        }
        val in = ByteBuffer.wrap(bytes.toArray)
        decode_(in, Nil)
      }

      val data = readFromUnderlying
      data match {
        case Data(bytes) =>
          val chars = decode(bytes)
          val newchunker = state.chunker + chars
          val xmlChunks = newchunker.chunks.map(mapFun _).filter(_.isDefined).map(_.get)
          val chunker2 = newchunker.consumeAll
          if (xmlChunks.isEmpty) {
            nextChunks(state.copy(chunker=chunker2))
          } else {
            noop; (Data(xmlChunks), state.copy(chunker=chunker2))
          }
        case EndOfData => noop
          state.decoder.reset
          (EndOfData, state)
      }
    }
    protected[this] def readFromUnderlying = source.read.receive
    
    override def close = stopAndWait    
  }
  private case class ByteParseState(decoder: CharsetDecoder, chunker: XmlChunker)
  private class CharBufferSeq(buffer: CharBuffer) extends scala.collection.Seq[Char] {
    override def apply(index: Int) = buffer.charAt(index)
    override def length = buffer.remaining
    override def iterator = new Iterator[Char] {
      private var pos = 0
      override def hasNext = pos < buffer.remaining
      override def next = {
        val value = apply(pos)
        pos = pos + 1
        value
      }
    }
  }

  private trait CharXmlChunkSource extends Source[Elem] with StateServer {
    protected val depth: Int
    protected val source: Source[Char]
    protected val closeTimeout = 20 s
    protected def mapFun(chunk: XmlChunk): Option[Elem]
    protected[this] override type State = XmlChunker
    
    protected[this] override def init = XmlChunker(depth)
    protected[this] override def termination(state: State) = source.close.receiveWithin(closeTimeout)

    override def read = call(nextChunks(_))
    protected[this] def nextChunks(chunker: XmlChunker): (Read[Elem],XmlChunker) @process = {
      val data = readFromUnderlying
      data match {
        case Data(items) =>
          val newchunker = chunker + items
          val xmlChunks = newchunker.chunks.map(mapFun _).filter(_.isDefined).map(_.get)
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
  def push(data: Seq[Char]): XmlChunker
  def +(data: Seq[Char]) = push(data)

  /** Chunks that were discovered (earliest discovered is first in list)*/
  def chunks: List[XmlChunk]
  def hasChunks = chunks.nonEmpty
  /** Consume all the chunks (free the memory) */
  def consumeAll: XmlChunker
  def consumeChunk: (Option[XmlChunk], XmlChunker)
}
trait XmlChunk {
  /** the content of this chunk as character array */
  def chars: Seq[Char]
  def string = new String(chars.toArray)
  /** this chunk as xml-data (if parsable), including the namespaces from the context */
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
  def xmlNoContext: Option[Elem] = {
    try {
      val reader = new java.io.StringReader(string)
      val elem = XML.load(reader)
      Some(elem)
    } catch {
      case e: Exception => None
    }
  }
  def context: Option[Elem]
  override def toString = string
}

object XmlChunker extends Log {
  def apply(depth: Int = 1) = new XmlChunkers(depth).init

  //TODO max size of parsed stuff (1Mb or so): mostly collected and elementData

  private class XmlChunkImpl(val chars: Seq[Char], val context: Option[Elem]) extends XmlChunk

  private class XmlChunkers(rootDepth: Int) {
    def init: XmlChunker = LookingForElement(Nil, Nil, Nil, 0)

    private case class LookingForElement(parents: List[Elem], chunks: List[XmlChunk], collected: Seq[Char], depth: Int) extends XmlChunker {
      override def push(data: Seq[Char]) = {
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
    private case class InElementTag(parents: List[Elem], chunks: List[XmlChunk], collected: Seq[Char], depth: Int, elementData: Seq[Char]) extends XmlChunker {
      override def push(data: Seq[Char]) = {
        val dso = elementData ++ data
        if (dso.take(cdataStart.size).sameElements(cdataStart)) {
          //CDATA start
          val rest = dso.drop(cdataStart.size)
          handleCData(collected).push(rest)
        } else {
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
      }
      /** tag = <element> */
      protected def handleElementOpen(tag: Seq[Char], chunk: Seq[Char]): XmlChunker = {
        if (depth < rootDepth) {
          parseXml(tag.dropRight(1) ++ "/>") match {
            case Some(elem) =>
              LookingForElement(elem :: parents, chunks, Nil, depth+1)
            case None =>
              LookingForElement(parents, chunks, Nil, depth)
          }
        } else if (depth == rootDepth) {
          //ignore text data inside chunk parent
          LookingForElement(parents, chunks, tag, depth+1)
        } else LookingForElement(parents, chunks, chunk, depth+1)
      }
      /** tag = <element/> */
      protected def handleElementOpenInlineClose(tag: Seq[Char], chunk: Seq[Char]): XmlChunker = {
        if (depth == rootDepth) LookingForElement(parents, chunks ::: List(mkChunk(chunk)), Nil, depth)
        else LookingForElement(parents, chunks, chunk, depth)
      }
      /** tag = </element> */
      protected def handleElementClose(tag: Seq[Char], chunk: Seq[Char]): XmlChunker = {
        if (depth <= rootDepth) {
          val newDepth = (depth - 1) max 0
          LookingForElement(parents.drop(1), chunks, Nil, newDepth)
        } else if (depth == rootDepth+1) {
          val newChunks = if (chunk.isEmpty) chunks else chunks ::: List(mkChunk(chunk))
          LookingForElement(parents, newChunks, Nil, depth-1)
        } else LookingForElement(parents, chunks, chunk, depth-1)
      }
      /** inside a cdata section */
      protected def handleCData(chunk: Seq[Char]): XmlChunker = {
        CDataHandler(this, (chunker, data) => {
          val d = chunk ++ data
          LookingForElement(parents, chunker.chunks, d, depth)
        }, cdataStart)
      }

      protected def parseXml(data: Seq[Char]): Option[Elem] = {
        try {
          val reader = new java.io.CharArrayReader(data.toArray)
          Some(XML.load(reader))
        } catch { case e: Exception =>
          log.debug("Invalid XML received (possible root): {}", e)
          None
        }
      }
      protected def mkChunk(data: Seq[Char]): XmlChunk = {
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

    private case class CDataHandler(context: XmlChunker, onDone: (XmlChunker,Seq[Char]) => XmlChunker, soFar: Seq[Char], kept: Seq[Char] = Nil) extends XmlChunker {
      override def push(data: Seq[Char]) = {
        matchToCDataEnd(kept ++ data) match {
          case MatchResult(true, cdataAdd, rest) =>
            val cdata = soFar ++ cdataAdd
            onDone(context, cdata).push(rest)
          case MatchResult(false, h, t) =>
            copy(soFar=soFar ++ h, kept=t)
        }
      }
      case class MatchResult(matched: Boolean, left: Seq[Char], right: Seq[Char])
      def matchToCDataEnd(data: Seq[Char], left: Seq[Char]=Nil): MatchResult = {
        val (h_,t) = data.span(_ != ']')
        val h = left ++ h_
        if (t.nonEmpty) {
          //candidate, check if rest matches
          if (t.size >= cdataEnd.size) {
            val (candidate,r) = t.splitAt(cdataEnd.size)
            if (candidate.sameElements(cdataEnd)) {
              MatchResult(true, h ++ candidate, r)
            } else {
              val (h2,t2) = t.splitAt(1) //skip one char forward and try again
              matchToCDataEnd(t2, h2)
            }
          } else MatchResult(false, h, t)
        } else MatchResult(false, data, Nil)
      }

      override def chunks = context.chunks
      override def consumeAll = {
        val nc = context.consumeAll
        copy(context=nc)
      }
      override def consumeChunk = {
        val (chunk,nc) = context.consumeChunk
        (chunk, copy(context=nc))
      }
    }
  }

  private val cdataStart = "<![CDATA[".toCharArray
  private val cdataEnd = "]]>".toCharArray
}
