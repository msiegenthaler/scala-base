package ch.inventsoft.scalabase.io

import ch.inventsoft.scalabase.log._
import scala.xml._

object XmlChunkSource {
}
/*
trait XmlChunkSource extends Source[Elem] {
  val maxChunkSize = 1024*1024L // 1Mb
  val source: Source[Byte]

  override def read = {
    
  }

  override def close = {
    //TODO
  }
}
*/

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

  /** The root-element (without children) */
  def root: Option[Elem]
  /** true if inside a valid root-element (root.isDefined) */
  def insideXml = root.isDefined

  /** Chunks that were discovered in the last (last discovered is first in list)*/
  def chunks: List[Iterable[Char]]
  def chunksAsString: List[String] = chunks.map(it => new String(it.toArray))
  //TODO chunks as xml (mapped)
  def hasChunks = chunks.nonEmpty

  /** Consume all the chunks (free the memory) */
  def consumed: XmlChunker
}
object XmlChunker extends Log {
  def apply(): XmlChunker = Initial(Nil)

  private trait OutOfDataChunker extends XmlChunker {
    override def root = None
  }
  private case class Initial(chunks: List[Iterable[Char]]) extends OutOfDataChunker {
    override def push(data: Iterable[Char]) = {
      val i = data.dropWhile(_ != '<')
      if (i.isEmpty) this
      else InHead(Nil, Nil).push(i)
    }
    override def consumed = copy(chunks = Nil)
  }
  private case class InHead(rootData: Iterable[Char], chunks: List[Iterable[Char]]) extends OutOfDataChunker {
    override def push(data: Iterable[Char]) = {
      val (h,t) = data.span(_ != '>')
      val nd = rootData ++ h
      if (t.nonEmpty) {
        val tail = t.drop(1)
        if (nd.nonEmpty) {
          if (nd.last == '/') {
            //closed again -> ignore
            Initial(chunks).push(tail)
          } else {
            val elementData = nd ++ "/>"
            val reader = new java.io.CharArrayReader(elementData.toArray)
            try {
              val xml = XML.load(reader)
              LookingForElement(xml, chunks, Nil, 0).push(tail)
            } catch {
              case e: Exception =>
                log.info("Invalid XML received: {}", e)
              Initial(chunks).push(tail)
            }
          }
        } else Initial(chunks).push(tail)
      } else copy(rootData = nd)
    }
    override def consumed = copy(chunks = Nil)
  }

  //TODO cdata handling
  //TODO max size of parsed stuff (1Mb or so): mostly collected and elementData

  private trait InRoot extends XmlChunker {
    val collected: Iterable[Char]
    def rootElem: Elem
    def root = Some(rootElem)
  }
  private case class LookingForElement(rootElem: Elem, chunks: List[Iterable[Char]], collected: Iterable[Char], depth: Int) extends InRoot {
    override def push(data: Iterable[Char]) = {
      val (h,t) = data.span(_ != '<')
      val nc = collected ++ h
      if (t.nonEmpty) InElementTag(rootElem, chunks, nc, depth, Nil).push(t)
      else copy(collected = nc)
    }
    override def consumed = copy(chunks=Nil)
  }
  private case class InElementTag(rootElem: Elem, chunks: List[Iterable[Char]], collected: Iterable[Char], depth: Int, elementData: Iterable[Char]) extends InRoot {
    override def push(data: Iterable[Char]) = {
      val (h,t) = data.span(_ != '>')
      if (t.nonEmpty) {
        val tail = t.drop(1)
        val tag = elementData ++ h ++ ">"
        val chunk = collected ++ tag
        if (tag.drop(1).head == '/') {
          //Close of element
          val next: XmlChunker = {
            if (depth==0) Initial(chunks)
            else if (depth == 1) {
              val newChunks = if (chunk.isEmpty) chunks else chunk :: chunks
              LookingForElement(rootElem, newChunks, Nil, 0)
            } else LookingForElement(rootElem, chunks, chunk, depth-1)
          }
          next.push(tail)
        } else {
          //Open of element
          if (tag.takeRight(2).head == '/') {
            if (depth==0) LookingForElement(rootElem, chunk :: chunks, Nil, 0).push(tail)
            else LookingForElement(rootElem, chunks, chunk, depth).push(tail)
          } else LookingForElement(rootElem, chunks, chunk, depth+1).push(tail)
        }
      } else copy(elementData = elementData ++ h)
    }
    override def consumed = copy(chunks=Nil)
  }

  private val cdataStart = "<![CDATA[".toCharArray
  private val cdataEnd = "]]>".toCharArray
}
