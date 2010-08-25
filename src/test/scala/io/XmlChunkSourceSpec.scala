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
      c.insideXml should be(false)
    }
    it("should not return any chunks after processing an empty iterator") {
      val c = XmlChunker() + ""
      c.chunks should be(Nil)
      c.hasChunks should be(false)
      c.insideXml should be(false)
    }

    it("should return no chunks after the root xml element has been partialy opened") {
      val c = XmlChunker() + "<root"
      c.chunks should be(Nil)
      c.hasChunks should be(false)
      c.insideXml should be(false)
    }
    it("should return no chunks after the root xml element has been opened") {
      val c = XmlChunker() + "<root>"
      c.chunks should be(Nil)
      c.hasChunks should be(false)
      c.insideXml should be(true)
    }
    it("should return no chunks after the root xml element has been opened and some additional data is received") {
      val c = XmlChunker() + "<root>bla"
      c.chunks should be(Nil)
      c.hasChunks should be(false)
      c.insideXml should be(true)
    }
    it("should return no chunks on xmls with no content") {
      val c = XmlChunker() + "<root></root>"
      c.chunks should be(Nil)
      c.hasChunks should be(false)
      c.insideXml should be(false)
    }
    it("should return no chunks on xmls with no content (inline closed)") {
      val c = XmlChunker() + "<root/>"
      c.chunks should be(Nil)
      c.hasChunks should be(false)
      c.insideXml should be(false)
    }
    it("should return no chunks on xmls with no first level elements") {
      val c = XmlChunker() + "<root>bla</root>"
      c.chunks should be(Nil)
      c.hasChunks should be(false)
      c.insideXml should be(false)
    }
    it("should be able to get the root-element if inside") {
      val c = XmlChunker() + "<root>"
      c.chunks should be(Nil)
      c.insideXml should be(true)
      c.root should be(Some(<root/>))
    }
    it("should be able to get the root-element with attribute if inside") {
      val c = XmlChunker() + "<root a=\"hallo welt\">"
      c.chunks should be(Nil)
      c.insideXml should be(true)
      c.root should be(Some(<root a="hallo welt"/>))
    }
    it("should detect a simple, empty element") {
      val c = XmlChunker() + "<root><bla></bla></root>"
      c.chunksAsString should be("<bla></bla>" :: Nil)
      c.insideXml should be(false)
    }
    it("should detect a simple, empty element in still open root") {
      val c = XmlChunker() + "<root><bla></bla>"
      c.chunksAsString should be("<bla></bla>" :: Nil)
      c.insideXml should be(true)
      val c2 = c + "</root>"
      c2.insideXml should be(false)
    }
    it("should detect a simple, empty element (inline closed)") {
      val c = XmlChunker() + "<root><bla/>"
      c.chunksAsString should be("<bla/>" :: Nil)
      c.insideXml should be(true)
    }
    it("should detect a simple, empty element with attributes") {
      val c = XmlChunker() + "<root><bla a=\"b\"></bla>"
      c.chunksAsString should be("<bla a=\"b\"></bla>" :: Nil)
      c.insideXml should be(true)
    }
    it("should detect an emtpy namespaced element") {
      val c = XmlChunker() + "<root><x:bla xmlns:x=\"http://myapp\"></x:bla>"
      c.chunksAsString should be("<x:bla xmlns:x=\"http://myapp\"></x:bla>" :: Nil)
      c.insideXml should be(true)
    }
    it("should detect a simple element with content") {
      val c = XmlChunker() + "<root><bla>mycontent</bla>"
      c.chunksAsString should be("<bla>mycontent</bla>" :: Nil)
      c.insideXml should be(true)
    }
    it("should detect a simple element with element content") {
      val c = XmlChunker() + "<root><bla>my<value>content</value> is <b><i>test</i></b></bla>"
      c.chunksAsString should be("<bla>my<value>content</value> is <b><i>test</i></b></bla>" :: Nil)
      c.insideXml should be(true)
    }
  }


}
