package ch.inventsoft.scalabase.io

import org.scalatest._
import matchers._
import java.io._
import java.nio.charset.Charset
import ch.inventsoft.scalabase.oip._
import ch.inventsoft.scalabase.process._
import ch.inventsoft.scalabase.process.cps.CpsUtils._
import ch.inventsoft.scalabase.time._


class XmlChunkSinkSpec extends ProcessSpec with ShouldMatchers {
  describe("XmlChunkSink") {
    describe("chars") {
      it_("should open the root tag at creation") {
        val chars = CharSink()
        val sink = XmlChunkSink.withRoot(<myRoot/>).outputingCharsTo(chars)()
        receiveWithin(200 ms) { case Timeout => () }
        val is = chars.value.receive
        is should be("<myRoot>")
        sink.close.await
        chars.shutdown.await
      }
      it_("should close the root tag at close") {
        val chars = CharSink()
        val sink = XmlChunkSink.withRoot(<myRoot/>).outputingCharsTo(chars)()
        sink.close.await
        val is = chars.value.receive
        is should be("<myRoot></myRoot>")
        chars.shutdown.await
      }
      it_("should write a basic chunks at level one") {
        val chars = CharSink()
        val sink = XmlChunkSink.withRoot(<myRoot/>).outputingCharsTo(chars)()
        sink.write(<hello/>).await
        sink.close.await
        val is = chars.value.receive
        is should be("<myRoot><hello /></myRoot>")
        chars.shutdown.await
      }
      it_("should write two equal basic chunks at level one") {
        val chars = CharSink()
        val sink = XmlChunkSink.withRoot(<myRoot/>).outputingCharsTo(chars)()
        sink.write(<hello/>).await
        sink.write(<hello/>).await
        sink.close.await
        val is = chars.value.receive
        is should be("<myRoot><hello /><hello /></myRoot>")
        chars.shutdown.await
      }
      it_("should write two different basic chunks at level one") {
        val chars = CharSink()
        val sink = XmlChunkSink.withRoot(<myRoot/>).outputingCharsTo(chars)()
        sink.write(<hello/>).await
        sink.write(<happy/>).await
        sink.close.await
        val is = chars.value.receive
        is should be("<myRoot><hello /><happy /></myRoot>")
        chars.shutdown.await
      }
      it_("should write two different basic chunks at level one with the parent already containing text data") {
        val chars = CharSink()
        val sink = XmlChunkSink.withRoot(<myRoot>hello there</myRoot>).outputingCharsTo(chars)()
        sink.write(<hello/>).await
        sink.write(<happy/>).await
        sink.close.await
        val is = chars.value.receive
        is should be("<myRoot>hello there<hello /><happy /></myRoot>")
        chars.shutdown.await
      }
      it_("should write two different basic chunks at level one with the parent already containing element data") {
        val chars = CharSink()
        val sink = XmlChunkSink.withRoot(<myRoot><head/></myRoot>).outputingCharsTo(chars)()
        sink.write(<hello/>).await
        sink.write(<happy/>).await
        sink.close.await
        val is = chars.value.receive
        is should be("<myRoot><head></head><hello /><happy /></myRoot>")
        chars.shutdown.await
      }
      it_("should write two complex chunks at level one") {
        val chars = CharSink()
        val sink = XmlChunkSink.withRoot(<myRoot a="b"/>).outputingCharsTo(chars)()
        sink.write(<ele1 value="fun" end="false"><no-content/></ele1>).await
        sink.write(<two><add><one/><two/></add></two>).await
        sink.close.await
        val is = chars.value.receive
        is should be("""<myRoot a="b"><ele1 value="fun" end="false"><no-content /></ele1><two><add><one /><two /></add></two></myRoot>""")
        chars.shutdown.await
      }
      it_("should write two basic chunks at level three with attribute left away") {
        val chars = CharSink()
        val sink = XmlChunkSink.withRoot(<root><body><part1>oO</part1><part2 attr="value"/></body></root>).addingChunksUnder(<part2/>).outputingCharsTo(chars)()
        sink.write(<one/>).await
        sink.write(<two/>).await
        sink.close.await
        val is = chars.value.receive
        is should be("""<root><body><part1>oO</part1><part2 attr="value"><one /><two /></part2></body></root>""")
        chars.shutdown.await
      }
      it_("should support namespaces") {
        val chars = CharSink()
        val sink = XmlChunkSink.withRoot(<myRoot xmlns="urn:abc"/>).outputingCharsTo(chars)()
        sink.write(<a:ele1 xmlns:a="urn:dce" fun="true"/>).await
        sink.write(<b xmlns="urn:fg"/>).await
        sink.close.await
        val is = chars.value.receive
        is should be("""<myRoot xmlns="urn:abc"><a:ele1 fun="true" xmlns:a="urn:dce" /><b xmlns="urn:fg" /></myRoot>""")
        chars.shutdown.await
      }
      it_("should support complex nesting") {
        val chars = CharSink()
        val sink = XmlChunkSink.withRoot(<myRoot><head>some <i>value</i></head><body /></myRoot>).addingChunksUnder(<body />).outputingCharsTo(chars)()
        sink.write(<value1 />).await
        sink.close.await
        val is = chars.value.receive
        is should be("""<myRoot><head>some <i>value</i></head><body><value1 /></body></myRoot>""")
        chars.shutdown.await
      }
      it_("should support complex nesting with stuff after") {
        val chars = CharSink()
        val sink = XmlChunkSink.withRoot(<myRoot><head>some value</head><body /><tail>is <b>fun</b></tail></myRoot>).addingChunksUnder(<body />).outputingCharsTo(chars)()
        sink.write(<value1 />).await
        sink.close.await
        val is = chars.value.receive
        is should be("""<myRoot><head>some value</head><body><value1 /></body><tail>is <b>fun</b></tail></myRoot>""")
        chars.shutdown.await
      }
      it_("should support complex nesting with namespaces") {
        val chars = CharSink()
        val sink = XmlChunkSink.withRoot(<myRoot xmlns="urn:a"><head>some value</head><body xmlns="urn:b"/></myRoot>).addingChunksUnder(<body xmlns="urn:b" />).outputingCharsTo(chars)()
        sink.write(<value1 />).await
        sink.close.await
        val is = chars.value.receive
        is should be("""<myRoot xmlns="urn:a"><head>some value</head><body xmlns="urn:b"><value1 /></body></myRoot>""")
        chars.shutdown.await
      }
      it_("should support complex nesting with namespaces and prefixes") {
        val chars = CharSink()
        val sink = XmlChunkSink.withRoot(<myRoot xmlns="urn:a" xmlns:b="urn:b"><head>some value</head><b:body/></myRoot>).addingChunksUnder(<body xmlns="urn:b" />).outputingCharsTo(chars)()
        sink.write(<value1 />).await
        sink.close.await
        val is = chars.value.receive
        is should be("""<myRoot xmlns="urn:a" xmlns:b="urn:b"><head>some value</head><b:body><value1 /></b:body></myRoot>""")
        chars.shutdown.await
      }
    }
    describe("bytes") {
      it_("should write two different basic chunks at level one") {
        val bos = new ByteArrayOutputStream
        val bytes = OutputStreamSink(bos)
        val sink = XmlChunkSink.withRoot(<myRoot/>).outputingBytesTo(bytes)()
        sink.write(<hello/>).await
        sink.write(<happy/>).await
        sink.close.await
        val is = bos.toByteArray
        is should be("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<myRoot><hello /><happy /></myRoot>".getBytes("UTF-8"))
      }
    }
  }

  class CharSink extends Sink[Char] with StateServer {
    override type State = Iterable[Char]
    override def init = Nil
    override def write(chars: Iterable[Char]) = call { soFar =>
      val v = soFar ++ chars
      ((), v)
    }
    override def writeCast(chars: Iterable[Char]) = cast { soFar =>
      val v = soFar ++ chars
      v
    }
    def value = get { v =>
      val chars: Array[Char] = v.toArray
      new String(chars)
    }
    override def close = get(_ => ())
    def shutdown = stopAndWait
  }
  object CharSink extends SpawnableCompanion[CharSink] {
    def apply() = {
      start(SpawnAsRequiredChild)(new CharSink)
    }
  }
}

