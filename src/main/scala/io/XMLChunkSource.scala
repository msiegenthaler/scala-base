package ch.inventsoft.scalabase
package io

import scala.xml._
import java.nio.{ByteBuffer,CharBuffer}
import java.nio.charset._
import log._
import process._
import oip._
import time._
import scala.collection.immutable.Set


/**
 * Source that reads an xml-byte/charstream and emmits xml. The xml is chunked at a defined depth
 * (default is 1). Usefull i.e. for XMPP communication.
 * @see XmlChunker
 */
object XmlChunkSource {
  type ChunkFun = XmlChunk => Option[Elem]
  def returnChunksOnly(chunk: XmlChunk) =  chunk.xml
  def rootedChunks(chunk: XmlChunk) = chunk.xmlNoContext flatMap { xml => chunk.context.map { context =>
    val nc = context.child ++ xml
    context.copy(child=nc)
  }}

  def fromBytes(byteSource: => Source[Byte] @process, encoding: Charset, nodeDepth: Int = 1, chunkFun: ChunkFun = returnChunksOnly _, sendRoot: Boolean = false, as: SpawnStrategy = SpawnAsRequiredChild): Source[Elem] @process = {
    val sr = sendRoot
    val xmlSource = new ByteXmlChunkSource {
      override protected def openSource = byteSource
      override protected val depth = nodeDepth
      override protected val charset = encoding
      override protected val sendRoot = sr
      override protected def mapFun(chunk: XmlChunk) = chunkFun(chunk)
    }
    Spawner.start(xmlSource, as)
  }
  def fromChars(charSource: => Source[Char] @process, nodeDepth: Int = 1, chunkFun: ChunkFun = returnChunksOnly _, sendRoot: Boolean = false, as: SpawnStrategy = SpawnAsRequiredChild): Source[Elem] @process = {
    val sr = sendRoot
    val xmlSource = new CharXmlChunkSource {
      override protected def openSource = charSource
      override protected val depth = nodeDepth
      override protected val sendRoot = sr
      override protected def mapFun(chunk: XmlChunk) = chunkFun(chunk)
    }
    Spawner.start(xmlSource, as)
  }

  private trait ByteXmlChunkSource extends TransformingSource[Byte,Elem,ByteParseState] {
    protected val depth: Int
    protected val charset: Charset
    protected val closeTimeout = 20 s
    protected val sendRoot: Boolean
    protected def mapFun(chunk: XmlChunk): Option[Elem]

    protected override def createAccumulator = ByteParseState(charset.newDecoder, XmlChunker(depth), sendRoot)
    protected override def process(state: ByteParseState, add: Seq[Byte]) = {
      val items = bytesToChars(state.decoder, add, false)
      processChars(state, items)
    }
    protected override def processEnd(state: ByteParseState) = {
      val items = bytesToChars(state.decoder, Nil, true)
      processChars(state, items)._1
    }
    protected def bytesToChars(decoder: CharsetDecoder, bytes: Seq[Byte], last: Boolean): Seq[Char] = {
      def decode(in: ByteBuffer, soFar: Seq[Char]): Seq[Char] = {
        val outEstimatedSize: Int = (in.remaining*decoder.averageCharsPerByte).round max 2
        val out = CharBuffer.allocate(outEstimatedSize)
        decoder.decode(in, out, false) match {
          case CoderResult.UNDERFLOW => 
            out.flip
            soFar ++ new CharBufferSeq(out)
          case CoderResult.OVERFLOW =>
            out.flip
            decode(in, soFar ++ new CharBufferSeq(out))
          case other => //error
            out.flip
            decode(in, soFar ++ new CharBufferSeq(out))
        }
      }
      val in = ByteBuffer.wrap(bytes.toArray)
      decode(in, Nil)
    }
    protected def processChars(state: ByteParseState, items: Seq[Char]) = {
      val newchunker = state.chunker + items
      if (state.rootNeedsToBeSent && newchunker.parents.size==depth) {
        val xmlChunks = newchunker.parents.last :: Nil ++ newchunker.chunks.view.map(mapFun _).filter(_.isDefined).map(_.get)
        (xmlChunks, state.copy(chunker=newchunker.consumeAll, rootNeedsToBeSent=false))
      } else {
        val xmlChunks = newchunker.chunks.view.map(mapFun _).filter(_.isDefined).map(_.get)
        (xmlChunks, state.copy(chunker=newchunker.consumeAll))
      }
    }
    override def toString = "ByteXmlChunkSource"
  }
  private case class ByteParseState(decoder: CharsetDecoder, chunker: XmlChunker, rootNeedsToBeSent: Boolean)
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

  private trait CharXmlChunkSource extends TransformingSource[Char,Elem,CXCSState] {
    protected val depth: Int
    protected val sendRoot: Boolean
    protected val closeTimeout = 20 s
    protected def mapFun(chunk: XmlChunk): Option[Elem]

    protected override def createAccumulator = CXCSState(XmlChunker(depth), sendRoot)
    protected override def process(state: CXCSState, items: Seq[Char]) = {
      val chunker = state.chunker
      val newchunker = chunker + items
      if (state.rootNeedsToBeSent && chunker.parents.size==depth) {
        val xmlChunks = (chunker.parents.last :: Nil) ++ (newchunker.chunks.view.map(mapFun _).filter(_.isDefined).map(_.get))
        (xmlChunks, CXCSState(newchunker.consumeAll, false))
      } else {
        val xmlChunks = newchunker.chunks.view.map(mapFun _).filter(_.isDefined).map(_.get)
        (xmlChunks, state.copy(chunker=newchunker.consumeAll))
      }
    }
    override def toString = "CharXmlChunkSource"
  }
  protected case class CXCSState(chunker: XmlChunker, rootNeedsToBeSent: Boolean)
}

/**
 * Splits XML into chunks on a given level.
 * I.e. <root><ele1>abcdef</ele1><ele2>aa</ele2> will return two chunks
 *  - <ele1>abcedf</ele1>
 *  - <ele2>aa</ele2>
 */
trait XmlChunker {
  /** Process a chunk of data */
  def push(data: Seq[Char]): XmlChunker
  def +(data: Seq[Char]) = push(data)

  /**
   * The parent element currently known to the chunker. The first item in the
   * list is the parent lowest in the hierarchy, the last is the root-element.
   * Attention: They may or may not be the parents of the chunks, they
   * represent the CURRENT parent, depending on the state of the parser.
   */
  def parents: List[Elem]

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
  lazy val xml: Option[Elem] = xmlNoContext.map { elem =>
    val elemScope = elem.scope
    val contextScope = context.map(_.scope).getOrElse(TopScope)
    elem.copy(scope=mergeNamespaces(elemScope, contextScope))
  }
  private[this] def mergeNamespaces(child: NamespaceBinding, parent: NamespaceBinding) = {
    def collectPrefixes(b: NamespaceBinding, soFar: Set[String] = Set()): Set[String] = {
      val s = soFar + b.prefix
      if (b.parent == null) s
      else collectPrefixes(b.parent, s)
    }
    val onlyParent = collectPrefixes(parent) -- collectPrefixes(child)
    val nb = onlyParent.foldLeft(child) { (nb,prefix) =>
      val uri = parent.getURI(prefix)
      NamespaceBinding(prefix, uri, nb)
    }
    lazy val pd = parent.getURI(null)
    if (child.getURI(null)==null && pd != null) NamespaceBinding(null, pd, nb)
    else nb
  }

  lazy val xmlNoContext: Option[Elem] = {
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
            } else if (tag.drop(1).head == '?') {
              //processing instruction -> ignore
              LookingForElement(parents, chunks, Nil, depth).push(tail)
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
      override def parents = context.parents
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
