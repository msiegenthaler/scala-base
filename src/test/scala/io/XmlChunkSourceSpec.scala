package ch.inventsoft.scalabase
package io

import org.scalatest._
import matchers._
import java.io._
import java.nio.charset.Charset
import oip._
import process._
import time._


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
    it("should override namespaces from parent") {
      val c = XmlChunker() + "<stream:stream xmlns='jabber:component:accept' xmlns:stream='http://etherx.jabber.org/streams' to='plays.shakespeare.lit'><iq to='hallo' from='mario' xmlns='jabber:iq'/>"
      c.hasChunks should be(true)
      c.chunks.size should be(1)
      val chunk1 :: Nil = c.chunks
      chunk1.string should be("<iq to='hallo' from='mario' xmlns='jabber:iq'/>")
      chunk1.xml.get.toString should be("""<iq from="mario" to="hallo" xmlns:stream="http://etherx.jabber.org/streams" xmlns="jabber:iq"></iq>""")
    }
    it("should override namespaces from parent in subelements") {
      val c = XmlChunker() + "<stream:stream xmlns='jabber:component:accept' xmlns:stream='http://etherx.jabber.org/streams' to='plays.shakespeare.lit'><iq to='hallo' from='mario' id='1'><query xmlns='http://jabber.org/protocol/disco#info'/></iq>"
      c.hasChunks should be(true)
      c.chunks.size should be(1)
      val chunk1 :: Nil = c.chunks
      chunk1.string should be("<iq to='hallo' from='mario' id='1'><query xmlns='http://jabber.org/protocol/disco#info'/></iq>")
      chunk1.xml.get.toString should be("""<iq id="1" from="mario" to="hallo" xmlns="jabber:component:accept" xmlns:stream="http://etherx.jabber.org/streams"><query xmlns="http://jabber.org/protocol/disco#info"></query></iq>""")
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

    it("should ignore text before chunks") {
      val c = XmlChunker() + "<root>huhu it's fun<a>some text</a>"
      val (Some(e),nc) = c.consumeChunk
      e.string should be("<a>some text</a>")
      e.context should be(Some(<root/>))
      nc.hasChunks should be(false)
    }
    it("should ignore text after chunks") {
      val c = XmlChunker() + "<root><a>some text</a>huhu it's fun"
      val (Some(e),nc) = c.consumeChunk
      e.string should be("<a>some text</a>")
      e.context should be(Some(<root/>))
      nc.hasChunks should be(false)
    }
    it("should ignore text after chunks when root closed") {
      val c = XmlChunker() + "<root><a>some text</a>huhu it's fun</root>"
      val (Some(e),nc) = c.consumeChunk
      e.string should be("<a>some text</a>")
      e.context should be(Some(<root/>))
      nc.hasChunks should be(false)
    }

    it("should support basic CDATA inside chunks") {
      val c = XmlChunker() + "<root><a>some text <![CDATA[hi there]]></a>"
      val (Some(e),nc) = c.consumeChunk
      e.string should be("<a>some text <![CDATA[hi there]]></a>")
      e.context should be(Some(<root/>))
      nc.hasChunks should be(false)
    }
    it("should support CDATA with balanced elements inside chunks") {
      val c = XmlChunker() + "<root><a>some text <![CDATA[hi <b>there</b>]]></a>"
      val (Some(e),nc) = c.consumeChunk
      e.string should be("<a>some text <![CDATA[hi <b>there</b>]]></a>")
      e.context should be(Some(<root/>))
      nc.hasChunks should be(false)
    }
    it("should support CDATA with other balanced elements inside chunks") {
      val c = XmlChunker() + "<root><a>some text <![CDATA[hi <b>there</b>, is it fun?]]></a>"
      val (Some(e),nc) = c.consumeChunk
      e.string should be("<a>some text <![CDATA[hi <b>there</b>, is it fun?]]></a>")
      e.context should be(Some(<root/>))
      nc.hasChunks should be(false)
    }
    it("should support CDATA with unbalanced elements inside chunks") {
      val c = XmlChunker() + "<root><a>some text <![CDATA[hi there<br>is it fun?]]></a>"
      val (Some(e),nc) = c.consumeChunk
      e.string should be("<a>some text <![CDATA[hi there<br>is it fun?]]></a>")
      e.context should be(Some(<root/>))
      nc.hasChunks should be(false)
    }
    it("should support CDATA with multiple unbalanced elements inside chunks") {
      val c = XmlChunker() + "<root><a>some text <![CDATA[hi <b>there<br>is it <a>fun?]]></a>"
      val (Some(e),nc) = c.consumeChunk
      e.string should be("<a>some text <![CDATA[hi <b>there<br>is it <a>fun?]]></a>")
      e.context should be(Some(<root/>))
      nc.hasChunks should be(false)
    }
    it("should support CDATA with unbalanced elements nested inside chunks") {
      val c = XmlChunker() + "<root><a>hi<b>some text <![CDATA[hi there<br>is it fun?]]></b></a>"
      val (Some(e),nc) = c.consumeChunk
      e.string should be("<a>hi<b>some text <![CDATA[hi there<br>is it fun?]]></b></a>")
      e.context should be(Some(<root/>))
      nc.hasChunks should be(false)
    }
    it("should support CDATA ending with unbalanced elements inside chunks") {
      val c = XmlChunker() + "<root><a>some text <![CDATA[hi there<br>]]></a>"
      val (Some(e),nc) = c.consumeChunk
      e.string should be("<a>some text <![CDATA[hi there<br>]]></a>")
      e.context should be(Some(<root/>))
      nc.hasChunks should be(false)
    }
    it("should support CDATA with balanced elements inside root") {
      val c = XmlChunker() + "<root><![CDATA[hi there <b>Mario</b>!]]><a>some text</a>"
      val (Some(e),nc) = c.consumeChunk
      e.string should be("<a>some text</a>")
      e.context should be(Some(<root/>))
      nc.hasChunks should be(false)
    }
    it("should support CDATA ending with balanced elements inside root") {
      val c = XmlChunker() + "<root><![CDATA[hi there <b>Mario</b>]]><a>some text</a>"
      val (Some(e),nc) = c.consumeChunk
      e.string should be("<a>some text</a>")
      e.context should be(Some(<root/>))
      nc.hasChunks should be(false)
    }
    it("should support CDATA with unbalanced elements inside root") {
      val c = XmlChunker() + "<root><![CDATA[hi there<br>Mario]]><a>some text</a>"
      val (Some(e),nc) = c.consumeChunk
      e.string should be("<a>some text</a>")
      e.context should be(Some(<root/>))
      nc.hasChunks should be(false)
    }
    it("should support CDATA ending with unbalanced elements inside root") {
      val c = XmlChunker() + "<root><![CDATA[hi there<br>]]><a>some text</a>"
      val (Some(e),nc) = c.consumeChunk
      e.string should be("<a>some text</a>")
      e.context should be(Some(<root/>))
      nc.hasChunks should be(false)
    }
    it("should support empty CDATA inside root") {
      val c = XmlChunker() + "<root><![CDATA[]]><a>some text</a>"
      val (Some(e),nc) = c.consumeChunk
      e.string should be("<a>some text</a>")
      e.context should be(Some(<root/>))
      nc.hasChunks should be(false)
    }
    it("should support empty CDATA inside element") {
      val c = XmlChunker() + "<root><a>some<![CDATA[]]> text</a>"
      val (Some(e),nc) = c.consumeChunk
      e.string should be("<a>some<![CDATA[]]> text</a>")
      e.context should be(Some(<root/>))
      nc.hasChunks should be(false)
    }
    it("should support CDATA with all push sizes") {
      (1 to 100).foreach { i =>
        val text = "<root><![CDATA[hi there<br>]]><a><![CDATA[some<a> and not <b>]]> text</a>"
        val c = feedChunker(XmlChunker(), text, i)
        val (Some(e),nc) = c.consumeChunk
        e.string should be("<a><![CDATA[some<a> and not <b>]]> text</a>")
        e.context should be(Some(<root/>))
        nc.hasChunks should be(false)
      }
    }
    it("should not return duplicates when chunks are consumed while chunker is inside a CData") {
      val c1 = XmlChunker() + "<root><a/><b><![CDATA[hi"
      val (Some(e1), c2) = c1.consumeChunk
      e1.string should be("<a/>")
      c2.hasChunks should be(false)
      
      val c3 = c2 + "]]> there</b>"
      val (Some(e2), c4) = c3.consumeChunk
      e2.string should be("<b><![CDATA[hi]]> there</b>")
      c4.hasChunks should be(false)
    }
    it("should return the proper chunks no matter where they are consumed") {
      (1 to 100).foreach { i =>
        val text = "<root><![CDATA[hi there<br>]]><a><![CDATA[some<a> and not <b>]]> text</a>"
        val (c, chunks) = feedAndConsume(XmlChunker(), text, i)
        c.hasChunks should be(false)
        chunks.size should be(1)
        val e :: Nil = chunks
        e.string should be("<a><![CDATA[some<a> and not <b>]]> text</a>")
        e.context should be(Some(<root/>))
      }
    }
    it("should ignore a simple xml-declaration") {
      val c = XmlChunker() + """<?xml version="1.0"?>"""
      c.hasChunks should be(false)
      c.parents should be(Nil)
    }
    it("should ignore an xml-declaration with encoding") {
      val c = XmlChunker() + """<?xml version="1.0" encoding="UTF-8"?>"""
      c.hasChunks should be(false)
      c.parents should be(Nil)
    }
    it("should ignore a simple xml-declaration and still parse body") {
      val c = XmlChunker() + """<?xml version="1.0"?><body>"""
      c.hasChunks should be(false)
      c.parents should be(<body/> :: Nil)
    }
    it("should ignore a simple xml-declaration and still parse body spaces") {
      val c = XmlChunker() + """<?xml version="1.0" ?><body>"""
      c.hasChunks should be(false)
      c.parents should be(<body/> :: Nil)
    }
    it("should parse content after a simple xml-declaration") {
      val text = """<?xml version="1.0"?><body><a/></body>"""
      (1 to 100).foreach { i =>
        val (_, chunks) = feedAndConsume(XmlChunker(), text, i)
        chunks.size should be(1)
        chunks(0).string should be("<a/>")
        chunks(0).xml should be(Some(<a/>))
        chunks(0).context should be(Some(<body/>))
      }
    }
    it("should parse content after an xml-declaration with encoding") {
      val text = """<?xml version="1.0" encoding="UTF-8"?><body><a/></body>"""
      (1 to 100).foreach { i =>
        val (_, chunks) = feedAndConsume(XmlChunker(), text, i)
        chunks.size should be(1)
        chunks(0).string should be("<a/>")
        chunks(0).xml should be(Some(<a/>))
        chunks(0).context should be(Some(<body/>))
      }
    }
    it("should parse content after an xml-declaration with encoding and '") {
      val text = """<?xml version='1.0' encoding='UTF-8'?><body><a/></body>"""
      (1 to 100).foreach { i =>
        val (_, chunks) = feedAndConsume(XmlChunker(), text, i)
        chunks.size should be(1)
        chunks(0).string should be("<a/>")
        chunks(0).xml should be(Some(<a/>))
        chunks(0).context should be(Some(<body/>))
      }
    }
    it("should parse content after an xml-declaration with encoding and standalone=yes") {
      val text = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><body><a/></body>"""
      (1 to 100).foreach { i =>
        val (_, chunks) = feedAndConsume(XmlChunker(), text, i)
        chunks.size should be(1)
        chunks(0).string should be("<a/>")
        chunks(0).xml should be(Some(<a/>))
        chunks(0).context should be(Some(<body/>))
      }
    }

    it("should support xmpp (example1)") {
      val string = XmppExample1.string
      (1 to string.length).foreach { i =>
        val c = feedChunker(XmlChunker(1), string, i)
        c.chunks.size should be(2)
        val chunk1 :: chunk2 :: Nil = c.chunks
        chunk1.xml should be(Some(XmppExample1.msg1))
        chunk2.xml should be(Some(XmppExample1.msg2))
      }
    }

    def feedChunker(chunker: XmlChunker, rest: Seq[Char], fragmentSize: Int): XmlChunker = {
      if (rest.size <= fragmentSize) chunker + rest
      else {
        val (h,t) = rest.splitAt(fragmentSize)
        feedChunker(chunker + h, t, fragmentSize)
      }
    }
    def feedAndConsume(chunker: XmlChunker, rest: Seq[Char], fragmentSize: Int, soFar: List[XmlChunk] = Nil): (XmlChunker, List[XmlChunk]) = {
      if (rest.size <= fragmentSize) {
        val nc = chunker + rest
        (nc.consumeAll, soFar ::: nc.chunks)
      }
      else {
        val (h,t) = rest.splitAt(fragmentSize)
        val nc = chunker + h
        val nsf = soFar ::: nc.chunks
        feedAndConsume(nc.consumeAll, t, fragmentSize, nsf)
      }
    }
  }

  describe("XmlChunkSource") {
    describe("from chars") {
      it_("should parse the xmpp example1 successfully in two reads") {
        val charSource = CharsFromStringSource(XmppExample1.string)
        val source = XmlChunkSource.fromChars(charSource)
        val read = collectAll(source)
        read.length should be(2)
        val read1 :: read2 :: Nil = read
        read1.items.size should be(1)
        read1.items.head should be(XmppExample1.msg1)
        read2.items.size should be(1)
        read2.items.head should be(XmppExample1.msg2)
        source.close.receive
      }
      it_("should support maxItems reads (1)") {
        val charSource = CharsFromStringSource(XmppExample1.string, 1000)
        val source = XmlChunkSource.fromChars(charSource)
        val read = source.read(1)
        read match {
          case Data(data) =>
            data.size should be(1)
            data.head should be(XmppExample1.msg1)
          case other => fail
        }
        source.close.receive
      }
      it_("should support maxItems reads (2)") {
        val charSource = CharsFromStringSource(XmppExample1.string, 1000)
        val source = XmlChunkSource.fromChars(charSource)
        val read = source.read(2)
        read match {
          case Data(data) =>
            data.size should be(2)
            data.head should be(XmppExample1.msg1)
            data.drop(1).head should be(XmppExample1.msg2)
          case other => fail
        }
        source.close.receive
      }
      it_("should support maxItems reads (1) with timeout") {
        val charSource = CharsFromStringSource(XmppExample1.string, 1000)
        val source = XmlChunkSource.fromChars(charSource)
        val read = source.readWithin(1 s, 1)
        read match {
          case Some(Data(data)) =>
            data.size should be(1)
            data.head should be(XmppExample1.msg1)
          case other => fail
        }
        source.close.receive
      }
      it_("should support maxItems reads (2) with timeout") {
        val charSource = CharsFromStringSource(XmppExample1.string, 1000)
        val source = XmlChunkSource.fromChars(charSource)
        val read = source.readWithin(1 s, 2)
        read match {
          case Some(Data(data)) =>
            data.size should be(2)
            data.head should be(XmppExample1.msg1)
            data.drop(1).head should be(XmppExample1.msg2)
          case other => fail
        }
        source.close.receive
      }
      it_("should parse the xmpp example1 no matter which readPerRequest value is used") {
        val toRead = XmppExample1.string
        (1 to (toRead.size*2)).foreach_cps { readPerRequest =>
          val source = XmlChunkSource.fromChars(CharsFromStringSource(toRead, readPerRequest))
          val chunks = collectAll(source).flatMap(_.items).toList
          chunks.size should be(2)
          chunks(0) should be(XmppExample1.msg1)
          chunks(1) should be(XmppExample1.msg2)
          source.close.receive
        }
      }
      it_("should be possible to have it return 'rooted' elems") {
        val charSource = CharsFromStringSource(XmppExample1.string)
        val source = XmlChunkSource.fromChars(charSource=charSource, chunkFun=XmlChunkSource.rootedChunks _)
        val read = collectAll(source)
        read.length should be(2)
        val read1 :: read2 :: Nil = read
        read1.items.size should be(1)
        read1.items.head.toString should be("""<stream:stream version="1.0" to="example.com" xmlns:stream="http://etherx.jabber.org/streams" xmlns="jabber:client"><message xml:lang="en" to="romeo@example.net" from="juliet@example.com">
           <body>Art thou not Romeo, and a Montague?</body>
         </message></stream:stream>""")
      }
      it_("should support getting the root as a first chunk") {
        val charSource = CharsFromStringSource(XmppExample1.string)
        val source = XmlChunkSource.fromChars(charSource=charSource, sendRoot=true)
        val read = collectAll(source)
        read.length should be(3)
        val root :: read1 :: read2 :: Nil = read
        root.items.size should be(1)
        root.items.head.label should be("stream")
        read1.items.size should be(1)
        read1.items.head should be(XmppExample1.msg1)
        read2.items.size should be(1)
        read2.items.head should be(XmppExample1.msg2)
        source.close.receive
      }
    }
    describe("from bytes") {
      val encoding = Charset.forName("UTF-8")
      it_("should parse the xmpp example1 successfully in two reads") {
        val bytesSource = BytesFromStringSource(XmppExample1.string)
        val source = XmlChunkSource.fromBytes(bytesSource, encoding)
        val read = collectAll(source)
        read.length should be(2)
        val read1 :: read2 :: Nil = read
        read1.items.size should be(1)
        read1.items.head should be(XmppExample1.msg1)
        read2.items.size should be(1)
        read2.items.head should be(XmppExample1.msg2)
        source.close.receive
      }
      it_("should support maxItems reads (1)") {
        val bytesSource = BytesFromStringSource(XmppExample1.string, 1000)
        val source = XmlChunkSource.fromBytes(bytesSource, encoding)
        val read = source.read(1)
        read match {
          case Data(data) =>
            data.size should be(1)
            data.head should be(XmppExample1.msg1)
          case other => fail
        }
        source.close.receive
      }
      it_("should support maxItems reads (2)") {
        val bytesSource = BytesFromStringSource(XmppExample1.string, 1000)
        val source = XmlChunkSource.fromBytes(bytesSource, encoding)
        val read = source.read(2)
        read match {
          case Data(data) =>
            data.size should be(2)
            data.head should be(XmppExample1.msg1)
            data.drop(1).head should be(XmppExample1.msg2)
          case other => fail
        }
        source.close.receive
      }
      it_("should support maxItems reads (1) with timeout") {
        val bytesSource = BytesFromStringSource(XmppExample1.string, 1000)
        val source = XmlChunkSource.fromBytes(bytesSource, encoding)
        val read = source.readWithin(1 s, 1)
        read match {
          case Some(Data(data)) =>
            data.size should be(1)
            data.head should be(XmppExample1.msg1)
          case other => fail
        }
        source.close.receive
      }
      it_("should support maxItems reads (2) with timeout") {
        val bytesSource = BytesFromStringSource(XmppExample1.string, 1000)
        val source = XmlChunkSource.fromBytes(bytesSource, encoding)
        val read = source.readWithin(1 s, 2)
        read match {
          case Some(Data(data)) =>
            data.size should be(2)
            data.head should be(XmppExample1.msg1)
            data.drop(1).head should be(XmppExample1.msg2)
          case other => fail
        }
        source.close.receive
      }
      it_("should parse the xmpp example1 no matter which readPerRequest value is used") {
        val toRead = XmppExample1.string
        (1 to (toRead.size*2)).foreach_cps { readPerRequest =>
          val source = XmlChunkSource.fromBytes(BytesFromStringSource(toRead, readPerRequest), encoding)
          val chunks = collectAll(source).flatMap(_.items).toList
          chunks.size should be(2)
          chunks(0) should be(XmppExample1.msg1)
          chunks(1) should be(XmppExample1.msg2)
          source.close.receive
        }
      }
      it_("should be possible to have it return 'rooted' elems") {
        val bytesSource = BytesFromStringSource(XmppExample1.string)
        val source = XmlChunkSource.fromBytes(byteSource=bytesSource, encoding=encoding, chunkFun=XmlChunkSource.rootedChunks _)
        val read = collectAll(source)
        read.length should be(2)
        val read1 :: read2 :: Nil = read
        read1.items.size should be(1)
        read1.items.head.toString should be("""<stream:stream version="1.0" to="example.com" xmlns:stream="http://etherx.jabber.org/streams" xmlns="jabber:client"><message xml:lang="en" to="romeo@example.net" from="juliet@example.com">
           <body>Art thou not Romeo, and a Montague?</body>
         </message></stream:stream>""")
      }
      it_("should support getting the root as a first chunk") {
        val bytesSource = BytesFromStringSource(XmppExample1.string)
        val source = XmlChunkSource.fromBytes(byteSource=bytesSource, encoding=encoding, sendRoot=true)
        val read = collectAll(source)
        read.length should be(3)
        val root :: read1 :: read2 :: Nil = read
        root.items.size should be(1)
        root.items.head.label should be("stream")
        read1.items.size should be(1)
        read1.items.head should be(XmppExample1.msg1)
        read2.items.size should be(1)
        read2.items.head should be(XmppExample1.msg2)
        source.close.receive
      }
    }
  }

  def collectAll[A](source: Source[A], soFar: List[Data[A]] = Nil): List[Data[A]] @process = {
    val read = source.readWithin(5 s)
    read.get match {
      case data: Data[A] =>
        data.items.nonEmpty should be(true)
        collectAll(source, data :: soFar)
      case EndOfData => noop; soFar.reverse
    }
  }
  class CharsFromStringSource(data: String, readPerRequest: Int = 10) extends Source[Char] with StateServer {
    override type State = Seq[Char]
    override def init = new scala.collection.immutable.WrappedString(data)
    override def read(max: Int) = call { left =>
      if (left.isEmpty) (EndOfData, left)
      else if (left.length > readPerRequest) {
        val (h,t) = left.splitAt(readPerRequest)
        (Data(h), t)
      } else (Data(left), Nil)
    }.receive
    override def readWithin(timeout: Duration, max: Int) = Some(read())
    override def close = stopAndWait
  }
  object CharsFromStringSource {
    def apply(string: String, perRequest: Int = 10) =
      Spawner.start(new CharsFromStringSource(string, perRequest), SpawnAsRequiredChild)
  }
  class BytesFromStringSource(string: String, readPerRequest: Int = 10) extends Source[Byte] with StateServer {
    override type State = Seq[Byte]
    override def init = new scala.collection.mutable.WrappedArray.ofByte(string.getBytes("UTF-8"))
    override def read(max: Int) = call { left =>
      if (left.isEmpty) (EndOfData, left)
      else if (left.length > readPerRequest) {
        val (h,t) = left.splitAt(readPerRequest)
        (Data(h), t)
      } else (Data(left), Nil)
    }.receive
    override def readWithin(timeout: Duration, max: Int) = Some(read())
    override def close = stopAndWait
  }
  object BytesFromStringSource {
    def apply(string: String, perRequest: Int = 10) =
      Spawner.start(new BytesFromStringSource(string, perRequest), SpawnAsRequiredChild)
  }


  object XmppExample1 {
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
    val msg1 = <message from='juliet@example.com'
                   to='romeo@example.net'
                   xml:lang='en'>
           <body>Art thou not Romeo, and a Montague?</body>
         </message>
    val msg2 = <message from='romeo@example.net'
                   to='juliet@example.com'
                   xml:lang='en'>
          <body>Neither, fair saint, if either thee dislike.</body>
        </message>
  }

}
