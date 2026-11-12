package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.MediaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the XMP packet model: the RDF forms writers use, the canonical serialization round trip,
 * edits that keep the property types, and hostile XML.
 */
@DisplayName("XmpPacket")
class XmpPacketTest {
    static final String ADOBE = """
            <?xpacket begin="\uFEFF" id="W5M0MpCehiHzreSzNTczkc9d"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about=""
                xmlns:xmp="http://ns.adobe.com/xap/1.0/"
                xmlns:tiff="http://ns.adobe.com/tiff/1.0/"
                xmlns:exif="http://ns.adobe.com/exif/1.0/"
                xmlns:dc="http://purl.org/dc/elements/1.1/"
                xmlns:xmpMM="http://ns.adobe.com/xap/1.0/mm/"
                xmlns:stEvt="http://ns.adobe.com/xap/1.0/sType/ResourceEvent#"
               xmp:Rating="5" tiff:Orientation="6" exif:GPSLatitude="45,27.85N">
               <dc:title>
                <rdf:Alt>
                 <rdf:li xml:lang="x-default">Duomo &amp; friends</rdf:li>
                 <rdf:li xml:lang="it-IT">Duomo e amici</rdf:li>
                </rdf:Alt>
               </dc:title>
               <dc:creator><rdf:Seq><rdf:li>Ada</rdf:li><rdf:li>Grace</rdf:li></rdf:Seq></dc:creator>
               <dc:subject><rdf:Bag><rdf:li>church</rdf:li><rdf:li>&#x4E2D;</rdf:li></rdf:Bag></dc:subject>
               <exif:Flash rdf:parseType="Resource"><exif:Fired>False</exif:Fired><exif:Mode>2</exif:Mode></exif:Flash>
               <xmpMM:History>
                <rdf:Seq>
                 <rdf:li stEvt:action="created" stEvt:when="2024-05-01T12:00:00"/>
                 <rdf:li><rdf:Description stEvt:action="saved"><stEvt:changed>/metadata</stEvt:changed></rdf:Description></rdf:li>
                </rdf:Seq>
               </xmpMM:History>
               <xmp:BaseURL rdf:resource="https://example.com/"/>
               <xmp:Label><![CDATA[<raw> text]]></xmp:Label>
              </rdf:Description>
              <rdf:Description rdf:about="" xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/">
               <photoshop:City>Milano</photoshop:City>
              </rdf:Description>
             </rdf:RDF>
            </x:xmpmeta>
                                                                                \s
            <?xpacket end="w"?>""";

    @Nested
    @DisplayName("parse")
    class Parse {
        @Test
        @DisplayName("reads attributes, arrays, language alternatives, structures, resources and CDATA")
        void forms() {
            var packet = XmpPacket.parse(ADOBE);
            assertEquals("5", packet.text(XmpPacket.XMP, "Rating").orElseThrow());
            assertEquals("6", packet.text(XmpPacket.TIFF, "Orientation").orElseThrow());
            assertEquals("Duomo & friends", packet.text(XmpPacket.DC, "title").orElseThrow());
            var title = (XmpValue.Array) packet.get(XmpPacket.DC, "title").orElseThrow();
            assertEquals(XmpValue.Array.Kind.ALTERNATIVE, title.kind());
            assertEquals(new XmpValue.Text("Duomo e amici", "it-IT"), title.items().get(1));
            assertEquals(List.of("Ada", "Grace"), packet.texts(XmpPacket.DC, "creator"));
            assertEquals(List.of("church", "\u4E2D"), packet.texts(XmpPacket.DC, "subject"));
            var flash = assertInstanceOf(XmpValue.Struct.class, packet.get(XmpPacket.EXIF, "Flash").orElseThrow());
            assertEquals("False", flash.field(XmpPacket.EXIF, "Fired").flatMap(XmpValue::asText).orElseThrow());
            var history = (XmpValue.Array) packet.get(XmpPacket.XMP_MM, "History").orElseThrow();
            assertEquals(2, history.items().size());
            var event = assertInstanceOf(XmpValue.Struct.class, history.items().get(1));
            assertEquals("/metadata", event.field("http://ns.adobe.com/xap/1.0/sType/ResourceEvent#", "changed")
                    .flatMap(XmpValue::asText).orElseThrow());
            assertEquals("https://example.com/", packet.text(XmpPacket.XMP, "BaseURL").orElseThrow());
            assertEquals("<raw> text", packet.text(XmpPacket.XMP, "Label").orElseThrow());
            assertEquals("Milano", packet.text(XmpPacket.PHOTOSHOP, "City").orElseThrow());
            assertEquals("stEvt", packet.prefixes().get("http://ns.adobe.com/xap/1.0/sType/ResourceEvent#"));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"UTF-8", "UTF-16BE", "UTF-16LE"})
        @DisplayName("decodes the encodings a byte order mark announces")
        void encodings(String charset) {
            var encoding = java.nio.charset.Charset.forName(charset);
            var bom = charset.equals("UTF-8") ? new byte[0] : charset.equals("UTF-16BE") ? new byte[]{(byte) 0xFE, (byte) 0xFF}
                    : new byte[]{(byte) 0xFF, (byte) 0xFE};
            var text = ADOBE.replace("\uFEFF", "").getBytes(encoding);
            var bytes = new byte[bom.length + text.length];
            System.arraycopy(bom, 0, bytes, 0, bom.length);
            System.arraycopy(text, 0, bytes, bom.length, text.length);
            assertEquals(XmpPacket.parse(ADOBE), XmpPacket.parse(bytes));
        }
    }

    @Nested
    @DisplayName("serialization")
    class Serialization {
        @Test
        @DisplayName("round trips through the canonical form")
        void roundTrip() {
            var packet = XmpPacket.parse(ADOBE);
            var xml = packet.toXml();
            assertTrue(xml.startsWith("<?xpacket begin=\"\uFEFF\""));
            assertTrue(xml.endsWith("<?xpacket end=\"w\"?>"));
            assertEquals(packet, XmpPacket.parse(xml));
            assertEquals(packet, XmpPacket.parse(packet.toBytes()));
            assertEquals(packet, XmpPacket.parse(packet.toXml(2048)));
        }

        @Test
        @DisplayName("escapes markup and drops characters XML cannot hold")
        void escaping() {
            var packet = XmpPacket.empty().with(XmpPacket.DC, "description", "a < b & \"c\" \u0001\r\n");
            assertEquals("a < b & \"c\" \r\n", XmpPacket.parse(packet.toXml()).text(XmpPacket.DC, "description").orElseThrow());
        }

        @Test
        @DisplayName("assigns prefixes to unknown namespaces")
        void prefixes() {
            var packet = XmpPacket.empty().with("http://example.com/ns/", "Value", "1").with("http://other.example/", "Value", "2");
            var read = XmpPacket.parse(packet.toXml());
            assertEquals("1", read.text("http://example.com/ns/", "Value").orElseThrow());
            assertEquals("2", read.text("http://other.example/", "Value").orElseThrow());
        }
    }

    @Nested
    @DisplayName("edits")
    class Edits {
        @Test
        @DisplayName("keep the array kind of existing and Dublin Core properties")
        void types() {
            var packet = XmpPacket.parse(ADOBE).with(XmpPacket.DC, "creator", "Linus").with(XmpPacket.DC, "rights", "CC0");
            assertEquals(XmpValue.ordered("Linus"), packet.get(XmpPacket.DC, "creator").orElseThrow());
            assertEquals(XmpValue.alternative("CC0"), packet.get(XmpPacket.DC, "rights").orElseThrow());
            assertEquals(XmpValue.text("x"), XmpPacket.empty().with(XmpPacket.XMP, "Label", "x").get(XmpPacket.XMP, "Label")
                    .orElseThrow());
        }

        @Test
        @DisplayName("remove properties, namespaces and filtered properties")
        void removal() {
            var packet = XmpPacket.parse(ADOBE);
            assertTrue(packet.without(XmpPacket.DC, "title").get(XmpPacket.DC, "title").isEmpty());
            assertTrue(packet.withoutNamespace(XmpPacket.DC).properties().stream().noneMatch(p -> p.namespace().equals(XmpPacket.DC)));
            var noGps = packet.withoutIf(property -> property.name().startsWith("GPS"));
            assertTrue(noGps.get(XmpPacket.EXIF, "GPSLatitude").isEmpty());
            assertEquals(packet.properties().size() - 1, noGps.properties().size());
        }
    }

    @Nested
    @DisplayName("hostile input")
    class Hostile {
        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "<!DOCTYPE x [<!ENTITY a \"aaaa\">]><x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>",
                "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><a>&unknown;</a></rdf:RDF>",
                "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><b:c/></rdf:RDF>",
                "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><a></b></rdf:RDF>",
                "<a x=\"1\" x=\"2\"/>",
                "<a>",
                "",
                "no xml at all",
                "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>"
        })
        @DisplayName("rejects malformed XML, document types, unknown entities and missing RDF")
        void malformed(String xml) {
            assertThrows(MediaException.Malformed.class, () -> XmpPacket.parse(xml));
        }

        @Test
        @DisplayName("drops control characters, so a namespace holding one is the one written back")
        void controlCharacters() {
            var xml = "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                    + "<rdf:Description xmlns:dc=\"http://purl.org\u0001\u0000/dc/elements/1.1/\">"
                    + "<dc:title rdf:parseType=\"Resource\"><dc:x>a\u0002b</dc:x></dc:title>"
                    + "<dc:source><![CDATA[c\u0003d]]></dc:source><dc:format>e\tf</dc:format>"
                    + "</rdf:Description></rdf:RDF>";
            var packet = XmpPacket.parse(xml);
            assertInstanceOf(XmpValue.Struct.class, packet.get(XmpPacket.DC, "title").orElseThrow());
            assertEquals("cd", packet.text(XmpPacket.DC, "source").orElseThrow());
            assertEquals("e\tf", packet.text(XmpPacket.DC, "format").orElseThrow());
            var edited = XmpPacket.parse(packet.with(XmpPacket.DC, "title", "fuzz").toBytes());
            assertEquals("fuzz", edited.text(XmpPacket.DC, "title").orElseThrow());
            assertEquals(packet, XmpPacket.parse(packet.toXml()));
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {"&#1;", "&#x1F;", "&#0;", "&#xD800;", "&#xFFFE;", "&#xFFFF;"})
        @DisplayName("rejects character references to characters XML cannot hold")
        void illegalReferences(String reference) {
            var xml = "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                    + "<rdf:Description xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:source>" + reference
                    + "</dc:source></rdf:Description></rdf:RDF>";
            assertThrows(MediaException.Malformed.class, () -> XmpPacket.parse(xml));
        }

        @Test
        @DisplayName("rejects nesting deeper than the bound instead of overflowing the stack")
        void deep() {
            var xml = "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" + "<a>".repeat(100_000);
            assertThrows(MediaException.Malformed.class, () -> XmpPacket.parse(xml));
        }

        @Test
        @DisplayName("raises nothing but MediaException on mutated packets")
        void mutations() {
            var random = new Random(11);
            var base = ADOBE.getBytes(StandardCharsets.UTF_8);
            for (var round = 0; round < 500; round++) {
                var mutated = base.clone();
                for (var flips = 0; flips < 1 + random.nextInt(6); flips++) {
                    mutated[random.nextInt(mutated.length)] = (byte) random.nextInt(256);
                }
                if (random.nextInt(3) == 0) {
                    mutated = java.util.Arrays.copyOf(mutated, random.nextInt(mutated.length));
                }
                var input = mutated;
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                    try {
                        var packet = XmpPacket.parse(input);
                        XmpPacket.parse(packet.toXml());
                    } catch (MediaException expected) {
                        // malformed input is reported this way
                    }
                });
            }
        }
    }
}
