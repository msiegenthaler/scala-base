package ch.inventsoft.scalabase.io

import org.scalatest._
import matchers._
import java.io._
import ch.inventsoft.scalabase.oip._
import ch.inventsoft.scalabase.process._
import ch.inventsoft.scalabase.process.cps.CpsUtils._
import ch.inventsoft.scalabase.time._


class XmlChunkSourceSpec extends ProcessSpec with ShouldMatchers {

  describe("XmlChunker") {
    it("should not return any chunks in initial state") {
      val c = XmlChunker()
      c.chunks should be(Nil)
      c.hasChunks should be(false)
      val (chunk, nc)  = c.consumeChunk
      chunk should be(None)
      nc should be(c)
    }
    it("should not return any chunks after processing an empty iterator") {
      val c = XmlChunker() + ""
      c.chunks should be(Nil)
      c.hasChunks should be(false)
    }

    it("should return no chunks after the root xml element has been partialy opened") {
      val c = XmlChunker() + "<root"
      c.chunks should be(Nil)
      c.hasChunks should be(false)
    }
    it("should return no chunks after the root xml element has been opened") {
      val c = XmlChunker() + "<root>"
      c.chunks should be(Nil)
      c.hasChunks should be(false)
    }
    it("should return no chunks after the root xml element has been opened and some additional data is received") {
      val c = XmlChunker() + "<root>bla"
      c.chunks should be(Nil)
      c.hasChunks should be(false)
    }
    it("should return no chunks on xmls with no content") {
      val c = XmlChunker() + "<root></root>"
      c.chunks should be(Nil)
      c.hasChunks should be(false)
    }
    it("should return no chunks on xmls with no content (inline closed)") {
      val c = XmlChunker() + "<root/>"
      c.chunks should be(Nil)
      c.hasChunks should be(false)
    }
    it("should return no chunks on xmls with no first level elements") {
      val c = XmlChunker() + "<root>bla</root>"
      c.chunks should be(Nil)
      c.hasChunks should be(false)
    }
    it("should be able to get the root-element for a chunk") {
      val c = XmlChunker() + "<root><a/>"
      val (Some(e),nc) = c.consumeChunk
      e.string should be("<a/>")
      e.context should be(Some(<root/>))
      nc.hasChunks should be(false)
    }
    it("should be able to get the root-element with attribute for a chunk") {
      val c = XmlChunker() + "<root a=\"hallo welt\"><a/>"
      c.consumeChunk match {
        case (Some(chunk),c) =>
          chunk.string should be("<a/>")
          chunk.xml should be(Some(<a/>))
          chunk.context should be(Some(<root a="hallo welt"/>))
          c.hasChunks should be(false)
        case _ => fail
      }
    }
    it("should detect a simple, empty element") {
      val c = XmlChunker() + "<root><bla></bla></root>"
      c.consumeChunk match {
        case (Some(chunk),c) =>
          chunk.string should be("<bla></bla>")
          chunk.xml should be(Some(<bla></bla>))
          chunk.context should be(Some(<root/>))
          c.hasChunks should be(false)
        case _ => fail
      }
    }
    it("should detect a simple, empty element in still open root") {
      val c = XmlChunker() + "<root><bla></bla>"
      c.consumeChunk match {
        case (Some(chunk),c) =>
          chunk.string should be("<bla></bla>")
          chunk.xml should be(Some(<bla></bla>))
          chunk.context should be(Some(<root/>))
          c.hasChunks should be(false)
        case _ => fail
      }
    }
    it("should detect a simple, empty element (inline closed)") {
      val c = XmlChunker() + "<root><bla/>"
      c.consumeChunk match {
        case (Some(chunk),c) =>
          chunk.string should be("<bla/>")
          chunk.xml should be(Some(<bla/>))
          chunk.context should be(Some(<root/>))
          c.hasChunks should be(false)
        case _ => fail
      }
    }
    it("should detect a simple, empty element with attributes") {
      val c = XmlChunker() + "<root><bla a=\"b\"></bla>"
      c.consumeChunk match {
        case (Some(chunk),c) =>
          chunk.string should be("<bla a=\"b\"></bla>")
          chunk.xml should be(Some(<bla a="b"></bla>))
          chunk.context should be(Some(<root/>))
          c.hasChunks should be(false)
        case _ => fail
      }
    }
    it("should detect an emtpy namespaced element") {
      val c = XmlChunker() + "<root><x:bla xmlns:x=\"http://myapp\"></x:bla>"
      c.consumeChunk match {
        case (Some(chunk),c) =>
          chunk.string should be("<x:bla xmlns:x=\"http://myapp\"></x:bla>")
          chunk.xml should be(Some(<x:bla xmlns:x="http://myapp"></x:bla>))
          chunk.context should be(Some(<root/>))
          c.hasChunks should be(false)
        case _ => fail
      }
    }
    it("should detect a simple element with content") {
      val c = XmlChunker() + "<root><bla>mycontent</bla>"
      c.consumeChunk match {
        case (Some(chunk),c) =>
          chunk.string should be("<bla>mycontent</bla>")
          chunk.context should be(Some(<root/>))
          c.hasChunks should be(false)
        case _ => fail
      }
    }
    it("should detect a simple element with element content") {
      val c = XmlChunker() + "<root><bla>my<value>content</value> is <b><i>test</i></b></bla>"
      c.consumeChunk match {
        case (Some(chunk),c) =>
          chunk.string should be("<bla>my<value>content</value> is <b><i>test</i></b></bla>")
          chunk.context should be(Some(<root/>))
          c.hasChunks should be(false)
        case _ => fail
      }
    }
    it("should detect multiple elements inside one root") {
      val c = XmlChunker() + "<root><one/><two>2</two><three/>"
      c.hasChunks should be(true)
      c.chunks.size should be(3)
      val chunk1 :: chunk2 :: chunk3 :: Nil = c.chunks
      chunk1.string should be("<one/>")
      chunk1.xml should be(Some(<one/>))
      chunk1.context should be(Some(<root/>))
      chunk2.string should be("<two>2</two>")
      chunk2.context should be(Some(<root/>))
      chunk3.string should be("<three/>")
      chunk3.context should be(Some(<root/>))
    }
    it("should inherit namespaces from parents") {
      val c = XmlChunker() + "<root xmlns:a=\"urn:test\"><value a:name=\"Mario\"/><a:b/>"
      c.hasChunks should be(true)
      c.chunks.size should be(2)
      val chunk1 :: chunk2 :: Nil = c.chunks
      chunk1.string should be("<value a:name=\"Mario\"/>")
      val a1 = chunk1.xml.get.attribute("urn:test", "name")
      a1.isDefined should be(true)
      a1.get.text should be("Mario")
      chunk2.string should be("<a:b/>")
      chunk2.xml.get.namespace should be("urn:test")
    }

    it("should also work with a depth of two (simple)") {
      val c = XmlChunker(2) + """<root><group id="a"><value>asdad</value><value/></group><group id="b"><name>Mario</name><age>29</age></group></root>"""
      c.hasChunks should be(true)
      c.chunks.size should be(4)
      val chunk1 :: chunk2 :: chunk3 :: chunk4 :: Nil = c.chunks
      chunk1.string should be("<value>asdad</value>")
      chunk1.xml should be(Some(<value>asdad</value>))
      chunk1.context should be(Some(<root><group id="a"/></root>))
      chunk2.string should be("<value/>")
      chunk2.xml should be(Some(<value/>))
      chunk2.context should be(Some(<root><group id="a"/></root>))
      chunk3.string should be("<name>Mario</name>")
      chunk3.xml should be(Some(<name>Mario</name>))
      chunk3.context should be(Some(<root><group id="b"/></root>))
      chunk4.string should be("<age>29</age>")
      chunk4.xml should be(Some(<age>29</age>))
      chunk4.context should be(Some(<root><group id="b"/></root>))
    }
    it("should also work with a depth of two with a inline closed element") {
      val c = XmlChunker(2) + """<root><group id="a"><value>asdad</value><value/></group><group bla="bla"/><group id="b"><name>Mario</name><age>29</age></group></root>"""
      c.hasChunks should be(true)
      c.chunks.size should be(4)
      val chunk1 :: chunk2 :: chunk3 :: chunk4 :: Nil = c.chunks
      chunk1.string should be("<value>asdad</value>")
      chunk1.xml should be(Some(<value>asdad</value>))
      chunk1.context should be(Some(<root><group id="a"/></root>))
      chunk2.string should be("<value/>")
      chunk2.xml should be(Some(<value/>))
      chunk2.context should be(Some(<root><group id="a"/></root>))
      chunk3.string should be("<name>Mario</name>")
      chunk3.xml should be(Some(<name>Mario</name>))
      chunk3.context should be(Some(<root><group id="b"/></root>))
      chunk4.string should be("<age>29</age>")
      chunk4.xml should be(Some(<age>29</age>))
      chunk4.context should be(Some(<root><group id="b"/></root>))
    }
    it("should have the second-level elements as roots with a depth of two") {
      val c = XmlChunker(2) + """<root><group id="a"><value>asdad</value><value/>"""
      c.hasChunks should be(true)
      c.chunks.size should be(2)
      val chunk1 :: chunk2 :: Nil = c.chunks
      chunk1.string should be("<value>asdad</value>")
      chunk1.xml should be(Some(<value>asdad</value>))
      chunk1.context should be(Some(<root><group id="a"/></root>))
      chunk2.string should be("<value/>")
      chunk2.xml should be(Some(<value/>))
      chunk2.context should be(Some(<root><group id="a"/></root>))
    }
    it("should support depth of three") {
      val c = XmlChunker(3) + """<a><b><c><one/><two/></c><c2><three/>"""
      c.hasChunks should be(true)
      c.chunks.size should be(3)
      val chunk1 :: chunk2 :: chunk3 :: Nil = c.chunks
      chunk1.string should be("<one/>")
      chunk1.xml should be(Some(<one/>))
      chunk1.context should be(Some(<a><b><c/></b></a>))
      chunk2.string should be("<two/>")
      chunk2.xml should be(Some(<two/>))
      chunk2.context should be(Some(<a><b><c/></b></a>))
      chunk3.string should be("<three/>")
      chunk3.xml should be(Some(<three/>))
      chunk3.context should be(Some(<a><b><c2/></b></a>))
    }

    it("should work with a depth of zero") {
      val c = XmlChunker(0) + """<a/><bla></bla><c a="b">hi</c>"""
      c.hasChunks should be(true)
      c.chunks.size should be(3)
      val chunk1 :: chunk2 :: chunk3 :: Nil = c.chunks
      chunk1.string should be("<a/>")
      chunk1.context should be(None)
      chunk2.string should be("<bla></bla>")
      chunk2.context should be(None)
      chunk3.string should be("<c a=\"b\">hi</c>")
      chunk3.context should be(None)
    }


    it("should support xmpp (example1)") {
      val string = """<?xml version='1.0'?>
      <stream:stream
          to='example.com'
          xmlns='jabber:client'
          xmlns:stream='http://etherx.jabber.org/streams'
          version='1.0'>
         <message from='juliet@example.com'
                   to='romeo@example.net'
                   xml:lang='en'>
           <body>Art thou not Romeo, and a Montague?</body>
         </message>
         <message from='romeo@example.net'
                   to='juliet@example.com'
                   xml:lang='en'>
          <body>Neither, fair saint, if either thee dislike.</body>
        </message>
      </stream:stream>"""
      
      (1 to string.length).foreach { i =>
        val c = feedChunker(XmlChunker(1), string, i)
        c.chunks.size should be(2)
        val chunk1 :: chunk2 :: Nil = c.chunks
        chunk1.xml should be(Some(<message from='juliet@example.com'
                   to='romeo@example.net'
                   xml:lang='en'>
           <body>Art thou not Romeo, and a Montague?</body>
         </message>))
        chunk2.xml should be(Some(<message from='romeo@example.net'
                   to='juliet@example.com'
                   xml:lang='en'>
          <body>Neither, fair saint, if either thee dislike.</body>
        </message>))
      }
    }

    def feedChunker(chunker: XmlChunker, rest: Iterable[Char], fragmentSize: Int): XmlChunker = {
      if (rest.size <= fragmentSize) chunker + rest
      else {
        val (h,t) = rest.splitAt(fragmentSize)
        feedChunker(chunker + h, t, fragmentSize)
      }
    }
  }


}
