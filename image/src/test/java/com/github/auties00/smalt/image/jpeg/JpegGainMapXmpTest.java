package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.gainmap.GainMap;
import com.github.auties00.smalt.image.gainmap.GainMapChannel;
import com.github.auties00.smalt.image.gainmap.GainMapComputeOptions;
import com.github.auties00.smalt.image.gainmap.GainMapMetadata;
import com.github.auties00.smalt.image.gainmap.GainMaps;
import com.github.auties00.smalt.image.jpeg.internal.JpegContainer;
import com.github.auties00.smalt.image.metadata.ImageMetadata;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.metadata.XmpValue;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the caller's XMP packets of an Ultra HDR file: read back by Smalt and by exiftool next to the container
 * directory and the {@code hdrgm} metadata, split into Extended XMP when large, and decoded by libultrahdr's
 * {@code ultrahdr_app}; the caller packets hold Dublin Core, XMP basic and EXIF properties and a namespace whose
 * prefix clashes with the container one.
 */
@DisplayName("Ultra HDR JPEG with the caller's XMP")
class JpegGainMapXmpTest {
    static final String OTHER = "http://example.com/smalt/other/";

    static final Duration TIMEOUT = Duration.ofMinutes(1);

    /** A packet of the primary image, with a stale Ultra HDR description that the writer replaces. */
    static byte[] primaryXmp() {
        return ("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                + " xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" xmlns:exif=\"http://ns.adobe.com/exif/1.0/\""
                + " xmlns:Container=\"" + OTHER + "\" xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\""
                + " xmp:CreatorTool=\"Smalt test\" exif:ExposureTime=\"1/125\" exif:FNumber=\"28/10\""
                + " Container:Thing=\"kept\" hdrgm:Version=\"9.9\">"
                + "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">Sunset &amp; sea</rdf:li></rdf:Alt></dc:title>"
                + "<dc:creator><rdf:Seq><rdf:li>Ada</rdf:li><rdf:li>Grace</rdf:li></rdf:Seq></dc:creator>"
                + "<dc:subject><rdf:Bag><rdf:li>hdr</rdf:li><rdf:li>test</rdf:li></rdf:Bag></dc:subject>"
                + "</rdf:Description>"
                + "<rdf:Description rdf:about=\"\" xmlns:GContainer=\"http://ns.google.com/photos/1.0/container/\""
                + " xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\">"
                + "<GContainer:Directory><rdf:Seq><rdf:li rdf:parseType=\"Resource\"><GContainer:Item"
                + " Item:Semantic=\"Primary\" Item:Mime=\"image/jpeg\"/></rdf:li><rdf:li rdf:parseType=\"Resource\">"
                + "<GContainer:Item Item:Semantic=\"MotionPhoto\" Item:Mime=\"video/mp4\" Item:Length=\"12345\"/>"
                + "</rdf:li></rdf:Seq></GContainer:Directory></rdf:Description>"
                + "</rdf:RDF></x:xmpmeta>\n<?xpacket end=\"w\"?>").getBytes(StandardCharsets.UTF_8);
    }

    /** A packet of the gain map image, with an hdrgm property that the writer replaces. */
    static byte[] gainMapXmp() {
        return ("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                + " xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\" hdrgm:GainMapMax=\"7.5\">"
                + "<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">the gain map</rdf:li></rdf:Alt>"
                + "</dc:description></rdf:Description></rdf:RDF></x:xmpmeta>").getBytes(StandardCharsets.UTF_8);
    }

    /** A text of words of at least a length. */
    static String words(int length) {
        var text = new StringBuilder(length + 16);
        for (var i = 0; text.length() < length; i++) {
            text.append("word").append(i).append(' ');
        }
        return text.toString().strip();
    }

    /** A packet whose description is too large for one segment. */
    static byte[] largeXmp(int length) {
        return XmpPacket.empty().with(XmpPacket.DC, "title", "Large").with(XmpPacket.DC, "description", words(length))
                .with(XmpPacket.XMP, "CreatorTool", "Smalt test").toBytes();
    }

    static GainMap gainMap(RgbaPicture base, boolean multichannel) {
        return GainMaps.compute(JpegGainMapTest.image(base, Optional.empty()), JpegGainMapTest.hdr(base),
                GainMapComputeOptions.builder().multichannel(multichannel).build());
    }

    static JpegEncoderOptions primaryOptions(byte[] xmp) {
        var builder = JpegEncoderOptions.builder().quality(95).chromaFormat(ChromaFormat.YUV444);
        return (xmp == null ? builder : builder.xmp(xmp)).build();
    }

    static JpegEncoderOptions gainMapOptions(byte[] xmp) {
        var builder = JpegEncoderOptions.ofQuality(95).toBuilder();
        return (xmp == null ? builder : builder.xmp(xmp)).build();
    }

    static List<JpegContainer.Segment> xmpSegments(byte[] file, List<JpegContainer.Segment> headers) {
        return headers.stream().filter(segment -> segment.marker() == 0xE1).toList();
    }

    static String text(byte[] file, JpegContainer.Segment segment, int skip) {
        return new String(file, segment.offset() + skip, segment.length() - skip, StandardCharsets.UTF_8);
    }

    /** Checks the caller's properties of {@link #primaryXmp()}. */
    static void assertPrimaryProperties(XmpPacket xmp) {
        assertEquals(List.of("Sunset & sea"), xmp.texts(XmpPacket.DC, "title"));
        assertEquals(List.of("Ada", "Grace"), xmp.texts(XmpPacket.DC, "creator"));
        assertEquals(List.of("hdr", "test"), xmp.texts(XmpPacket.DC, "subject"));
        assertEquals("Smalt test", xmp.text(XmpPacket.XMP, "CreatorTool").orElseThrow());
        assertEquals("1/125", xmp.text(XmpPacket.EXIF, "ExposureTime").orElseThrow());
        assertEquals("28/10", xmp.text(XmpPacket.EXIF, "FNumber").orElseThrow());
        assertEquals("kept", xmp.text(OTHER, "Thing").orElseThrow());
    }

    /** Checks the Ultra HDR structure of a file and returns its two images. */
    static List<JpegContainer.Image> assertStructure(byte[] file, GainMap gainMap) {
        var headers = JpegContainer.headers(file, 0, file.length);
        var xmp = JpegContainer.xmp(file, headers, MediaLimits.DEFAULT);
        assertNotNull(xmp);
        assertEquals("1.0", xmp.text(GainMapMetadata.XMP_NAMESPACE, "Version").orElseThrow());
        var images = JpegContainer.images(file, xmp);
        assertEquals(2, images.size());
        assertEquals(images.get(0).length(), images.get(1).offset());
        assertEquals(file.length, images.get(1).offset() + images.get(1).length());
        assertEquals("GainMap", images.get(1).semantic());
        assertEquals(0x030000, images.get(0).type());
        // the container directory alone locates the gain map too, with the length it states
        var withoutIndex = file.clone();
        var mpf = headers.stream().filter(segment -> segment.startsWith(file, JpegContainer.MPF_ID)).findFirst()
                .orElseThrow();
        withoutIndex[mpf.offset()] = 'X';
        var fallback = JpegContainer.images(withoutIndex, xmp);
        assertEquals(2, fallback.size());
        assertEquals(-1, fallback.get(1).type());
        assertEquals(images.get(1).offset(), fallback.get(1).offset());
        assertEquals(images.get(1).length(), fallback.get(1).length());
        var read = JpegDecoder.decodeGainMap(MediaSource.of(file)).orElseThrow();
        JpegGainMapTest.assertMetadata(gainMap.metadata(), read.metadata());
        JpegGainMapTest.assertMetadata(gainMap.metadata(),
                JpegDecoder.decodeGainMap(MediaSource.of(withoutIndex)).orElseThrow().metadata());
        return images;
    }

    @Nested
    @DisplayName("read back by Smalt")
    class Smalt {
        @ParameterizedTest(name = "multichannel {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("keeps the caller's properties next to the container directory and the hdrgm metadata")
        void merged(boolean multichannel) {
            var base = JpegGainMapTest.sdr(64, 48);
            var gainMap = gainMap(base, multichannel);
            var file = JpegEncoder.encode(base, gainMap, primaryOptions(primaryXmp()), gainMapOptions(gainMapXmp()));
            var images = assertStructure(file, gainMap);
            var headers = JpegContainer.headers(file, 0, file.length);
            assertEquals(1, xmpSegments(file, headers).size());
            var xmp = JpegContainer.xmp(file, headers, MediaLimits.DEFAULT);
            assertPrimaryProperties(xmp);
            assertTrue(GainMapMetadata.fromXmp(xmp).isEmpty());
            // the stale directory and version are replaced by the file's own
            var packet = text(file, xmpSegments(file, headers).getFirst(), 29);
            assertFalse(packet.contains("MotionPhoto"), packet);
            assertFalse(packet.contains("9.9"), packet);
            var directory = (XmpValue.Array) xmp.get(JpegContainer.CONTAINER, "Directory").orElseThrow();
            assertEquals(2, directory.items().size());
            assertEquals(xmp, ImageMetadata.read(file).xmp().orElseThrow());
            var second = JpegContainer.headers(file, images.get(1).offset(), file.length);
            assertEquals(1, xmpSegments(file, second).size());
            var gainMapPacket = JpegContainer.xmp(file, second, MediaLimits.DEFAULT);
            assertEquals(List.of("the gain map"), gainMapPacket.texts(XmpPacket.DC, "description"));
            JpegGainMapTest.assertMetadata(gainMap.metadata(), GainMapMetadata.fromXmp(gainMapPacket).orElseThrow());
            assertFalse(text(file, xmpSegments(file, second).getFirst(), 0).contains("7.5"));
            // the pictures are those of files without the packets
            var plain = JpegEncoder.encode(base, gainMap, primaryOptions(null), gainMapOptions(null));
            assertArrayEquals(((YuvPicture) JpegDecoder.decode(MediaSource.of(plain)).picture()).y(),
                    ((YuvPicture) JpegDecoder.decode(MediaSource.of(file)).picture()).y());
        }

        @Test
        @DisplayName("writes the Ultra HDR description first, in the main packet, with the caller's own after it")
        void layout() {
            var base = JpegGainMapTest.sdr(32, 32);
            var file = JpegEncoder.encode(base, gainMap(base, false), primaryOptions(primaryXmp()),
                    gainMapOptions(null));
            var segment = xmpSegments(file, JpegContainer.headers(file, 0, file.length)).getFirst();
            assertTrue(segment.startsWith(file, "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.US_ASCII)));
            var packet = text(file, segment, 29);
            var ultraHdr = packet.indexOf("hdrgm:Version=\"1.0\"");
            var caller = packet.indexOf("Sunset");
            assertTrue(ultraHdr > 0 && caller > ultraHdr, packet);
            assertTrue(packet.startsWith("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Smalt\">\n"), packet);
            assertEquals(2, packet.split("<rdf:Description ", -1).length - 1, packet);
            // the caller's namespace bound to the container prefix is renamed, since readers match it literally
            assertFalse(packet.contains("<Container:Thing"), packet);
            assertTrue(packet.contains("<Container:Directory>"), packet);
            assertTrue(packet.contains("xmlns:Container1=\"" + OTHER + "\""), packet);
            assertTrue(packet.contains("<Container1:Thing>kept</Container1:Thing>"), packet);
            assertEquals("kept", XmpPacket.parse(packet).text(OTHER, "Thing").orElseThrow());
        }

        @ParameterizedTest(name = "base color space {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("renames the Ultra HDR prefixes the caller binds to other namespaces")
        void reservedPrefixes(boolean baseColorSpace) {
            var base = JpegGainMapTest.sdr(32, 32);
            var computed = gainMap(base, false);
            var gainMap = baseColorSpace ? computed : new GainMap(new GainMapMetadata(0, 2,
                    List.of(GainMapChannel.of(0, 2)), false), computed.image(), Optional.of(ColorProfile.of(
                    new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL, ColorPrimaries.BT2020,
                            TransferCharacteristics.PQ))), Optional.empty());
            var caller = ("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF"
                    + " xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\""
                    + " xmlns:hdrgm=\"" + OTHER + "\" xmlns:Item=\"http://example.com/smalt/item/\""
                    + " xmlns:hdrgm1=\"http://example.com/smalt/third/\" hdrgm:GainMapMax=\"99\" Item:Mime=\"x\""
                    + " hdrgm1:Other=\"y\"/></rdf:RDF></x:xmpmeta>").getBytes(StandardCharsets.UTF_8);
            var file = JpegEncoder.encode(base, gainMap, primaryOptions(caller), gainMapOptions(caller));
            var images = JpegContainer.images(file, null);
            for (var image : images) {
                var headers = JpegContainer.headers(file, image.offset(), image.offset() + image.length());
                var packet = text(file, xmpSegments(file, headers).getFirst(), 29);
                assertFalse(packet.contains("<hdrgm:GainMapMax"), packet);
                assertFalse(packet.contains("<Item:Mime"), packet);
                assertTrue(packet.contains("<hdrgm2:GainMapMax>99</hdrgm2:GainMapMax>"), packet);
                assertTrue(packet.contains("<Item1:Mime>x</Item1:Mime>"), packet);
                assertTrue(packet.contains("<hdrgm1:Other>y</hdrgm1:Other>"), packet);
                var read = XmpPacket.parse(packet);
                assertEquals("99", read.text(OTHER, "GainMapMax").orElseThrow());
                assertEquals("x", read.text("http://example.com/smalt/item/", "Mime").orElseThrow());
                assertEquals("y", read.text("http://example.com/smalt/third/", "Other").orElseThrow());
            }
            JpegGainMapTest.assertMetadata(gainMap.metadata(),
                    JpegDecoder.decodeGainMap(MediaSource.of(file)).orElseThrow().metadata());
        }

        @Test
        @DisplayName("writes nothing for a packet left with only a stale Extended XMP note")
        void staleExtendedNote() {
            var base = JpegGainMapTest.sdr(24, 24);
            var gainMap = gainMap(base, false);
            var note = XmpPacket.empty().with(XmpPacket.XMP_NOTE, "HasExtendedXMP",
                    "0123456789ABCDEF0123456789ABCDEF").with(GainMapMetadata.XMP_NAMESPACE, "Version", "1.0")
                    .toBytes();
            assertArrayEquals(JpegEncoder.encode(base, gainMap, primaryOptions(null), gainMapOptions(null)),
                    JpegEncoder.encode(base, gainMap, primaryOptions(note), gainMapOptions(note)));
        }

        @ParameterizedTest(name = "multichannel {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("moves large caller properties to Extended XMP, keeping the Ultra HDR ones in the main packet")
        void extended(boolean multichannel) {
            var base = JpegGainMapTest.sdr(48, 40);
            var gainMap = gainMap(base, multichannel);
            var file = JpegEncoder.encode(base, gainMap, primaryOptions(largeXmp(150_000)),
                    gainMapOptions(largeXmp(80_000)));
            var images = assertStructure(file, gainMap);
            for (var image : images) {
                var headers = JpegContainer.headers(file, image.offset(), image.offset() + image.length());
                var segments = xmpSegments(file, headers);
                assertTrue(segments.size() >= 3, "segments " + segments.size());
                var main = XmpPacket.parse(text(file, segments.getFirst(), 29));
                assertTrue(main.text(XmpPacket.XMP_NOTE, "HasExtendedXMP").isPresent());
                assertTrue(main.get(XmpPacket.DC, "description").isEmpty());
                assertEquals("Large", main.texts(XmpPacket.DC, "title").getFirst());
                if (image.offset() == 0) {
                    assertEquals("1.0", main.text(GainMapMetadata.XMP_NAMESPACE, "Version").orElseThrow());
                    assertTrue(main.get(JpegContainer.CONTAINER, "Directory").isPresent());
                } else {
                    JpegGainMapTest.assertMetadata(gainMap.metadata(), GainMapMetadata.fromXmp(main).orElseThrow());
                }
                var merged = JpegContainer.xmp(file, headers, MediaLimits.DEFAULT);
                assertTrue(merged.text(XmpPacket.XMP_NOTE, "HasExtendedXMP").isEmpty());
                var description = merged.texts(XmpPacket.DC, "description");
                assertEquals(1, description.size());
                assertTrue(description.getFirst().length() >= (image.offset() == 0 ? 150_000 : 80_000) - 16);
            }
        }

        @Test
        @DisplayName("writes the gain map packet alone when the gain map applies in the alternate color space")
        void alternateColorSpace() {
            var base = JpegGainMapTest.sdr(32, 32);
            var alternate = ColorProfile.of(new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL,
                    ColorPrimaries.BT2020, TransferCharacteristics.PQ));
            var metadata = new GainMapMetadata(0, 2, List.of(GainMapChannel.of(0, 2)), false);
            var gainMap = new GainMap(metadata, gainMap(base, false).image(), Optional.of(alternate), Optional.empty());
            var file = JpegEncoder.encode(base, gainMap, JpegEncoderOptions.defaults(), gainMapOptions(gainMapXmp()));
            var images = JpegContainer.images(file, null);
            var second = JpegContainer.headers(file, images.get(1).offset(), file.length);
            var xmp = JpegContainer.xmp(file, second, MediaLimits.DEFAULT);
            assertEquals(List.of("the gain map"), xmp.texts(XmpPacket.DC, "description"));
            assertTrue(xmp.properties().stream().noneMatch(p -> p.namespace().equals(GainMapMetadata.XMP_NAMESPACE)));
            assertFalse(JpegDecoder.decodeGainMap(MediaSource.of(file)).orElseThrow().metadata().useBaseColorSpace());
        }

        @Test
        @DisplayName("writes the same file without packets as with packets holding only Ultra HDR properties")
        void unchangedWithoutPackets() {
            var base = JpegGainMapTest.sdr(40, 24);
            var gainMap = gainMap(base, true);
            var plain = JpegEncoder.encode(base, gainMap, primaryOptions(null), gainMapOptions(null));
            var onlyUltraHdr = XmpPacket.parse(primaryXmp()).withoutIf(property ->
                    !property.namespace().equals(GainMapMetadata.XMP_NAMESPACE)
                            && !property.namespace().equals(JpegContainer.CONTAINER)).toBytes();
            var stale = XmpPacket.empty().with(GainMapMetadata.XMP_NAMESPACE, "GainMapMax", "3").toBytes();
            assertArrayEquals(plain, JpegEncoder.encode(base, gainMap, primaryOptions(onlyUltraHdr),
                    gainMapOptions(stale)));
            // the packet of the primary image, as the writer wrote it before it took the caller's packets
            var headers = JpegContainer.headers(plain, 0, plain.length);
            var images = JpegContainer.images(plain, JpegContainer.xmp(plain, headers, MediaLimits.DEFAULT));
            var expected = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Smalt\">\n"
                    + " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
                    + "  <rdf:Description rdf:about=\"\""
                    + " xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"\n"
                    + "    xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\""
                    + " xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\"\n    hdrgm:Version=\"1.0\">\n"
                    + "   <Container:Directory>\n    <rdf:Seq>\n"
                    + "     <rdf:li rdf:parseType=\"Resource\">\n"
                    + "      <Container:Item Item:Semantic=\"Primary\" Item:Mime=\"image/jpeg\"/>\n"
                    + "     </rdf:li>\n"
                    + "     <rdf:li rdf:parseType=\"Resource\">\n"
                    + "      <Container:Item Item:Semantic=\"GainMap\" Item:Mime=\"image/jpeg\" Item:Length=\""
                    + images.get(1).length() + "\"/>\n"
                    + "     </rdf:li>\n"
                    + "    </rdf:Seq>\n   </Container:Directory>\n  </rdf:Description>\n </rdf:RDF>\n</x:xmpmeta>";
            var segments = xmpSegments(plain, headers);
            assertEquals(1, segments.size());
            assertEquals(expected, text(plain, segments.getFirst(), 29));
            var second = JpegContainer.headers(plain, images.get(1).offset(), plain.length);
            var gainMapText = text(plain, xmpSegments(plain, second).getFirst(), 29);
            assertTrue(gainMapText.startsWith("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Smalt\">\n"
                    + " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
                    + "  <rdf:Description rdf:about=\"\" xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\"\n"
                    + "    hdrgm:Version=\"1.0\"\n"), gainMapText);
            assertEquals(1, gainMapText.split("<rdf:Description ", -1).length - 1, gainMapText);
            assertTrue(gainMapText.endsWith("  </rdf:Description>\n </rdf:RDF>\n</x:xmpmeta>"), gainMapText);
        }

        @Test
        @DisplayName("rejects a packet that does not parse")
        void malformed() {
            var base = JpegGainMapTest.sdr(16, 16);
            var gainMap = gainMap(base, false);
            var broken = "<x:xmpmeta><rdf:RDF".getBytes(StandardCharsets.UTF_8);
            assertThrows(IllegalArgumentException.class, () -> JpegEncoder.encode(base, gainMap,
                    primaryOptions(broken), gainMapOptions(null)));
            assertThrows(IllegalArgumentException.class, () -> JpegEncoder.encode(base, gainMap,
                    primaryOptions(null), gainMapOptions(broken)));
            var noRdf = "<a/>".getBytes(StandardCharsets.UTF_8);
            assertThrows(IllegalArgumentException.class, () -> JpegEncoder.encode(base, gainMap,
                    primaryOptions(noRdf), gainMapOptions(null)));
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
        @DisplayName("round-trips random caller packets of any size")
        void random(int seed) {
            var random = new java.util.Random(seed);
            var base = JpegGainMapTest.sdr(24, 16);
            var gainMap = gainMap(base, seed % 2 == 0);
            var packet = XmpPacket.empty();
            var count = 1 + random.nextInt(40);
            for (var i = 0; i < count; i++) {
                var value = new StringBuilder();
                var length = random.nextInt(10) == 0 ? 20_000 + random.nextInt(30_000) : random.nextInt(60);
                value.append('v');
                for (var j = 0; j < length; j++) {
                    value.append(random.nextBoolean() ? (char) ('a' + random.nextInt(26))
                            : " <>&\"'\u00e9\u4e2d".charAt(random.nextInt(8)));
                }
                packet = packet.with(i % 3 == 0 ? OTHER : XmpPacket.XMP, "Field" + i, value.toString());
            }
            var file = JpegEncoder.encode(base, gainMap, primaryOptions(packet.toBytes()),
                    gainMapOptions(packet.toBytes()));
            var images = assertStructure(file, gainMap);
            for (var image : images) {
                var headers = JpegContainer.headers(file, image.offset(), image.offset() + image.length());
                var read = JpegContainer.xmp(file, headers, MediaLimits.DEFAULT);
                for (var property : packet.properties()) {
                    assertEquals(property.value(), read.get(property.namespace(), property.name()).orElseThrow(),
                            property.name());
                }
            }
        }
    }

    @Nested
    @DisplayName("read by the reference tools")
    class Reference {
        /** Runs exiftool on a file and returns its tags as group:name to value. */
        static Map<String, String> exiftool(Path file) {
            var output = new String(Oracle.run(Oracle.Tool.EXIFTOOL, TIMEOUT, List.of("-args", "-G1", "-n",
                    "-charset", "UTF8", file.toString())), StandardCharsets.UTF_8);
            var tags = new LinkedHashMap<String, String>();
            for (var line : output.split("\r?\n")) {
                var equals = line.indexOf('=');
                if (line.startsWith("-") && equals > 0) {
                    tags.put(line.substring(1, equals), line.substring(equals + 1));
                }
            }
            return tags;
        }

        @ParameterizedTest(name = "large {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("exiftool reads the caller's properties, the container directory and the hdrgm metadata")
        void exiftool(boolean large) throws IOException {
            Oracle.assume(Oracle.Tool.EXIFTOOL);
            var base = JpegGainMapTest.sdr(64, 48);
            var gainMap = gainMap(base, false);
            var primaryPacket = large ? XmpPacket.parse(primaryXmp()).with(XmpPacket.DC, "description",
                    words(100_000)).toBytes() : primaryXmp();
            var file = JpegEncoder.encode(base, gainMap, primaryOptions(primaryPacket), gainMapOptions(gainMapXmp()));
            var directory = Scratch.directory("jpeg-gainmap-xmp");
            var path = directory.resolve("ultrahdr-xmp-" + large + ".jpg");
            Files.write(path, file);
            var tags = exiftool(path);
            var all = tags.toString();
            assertEquals("Sunset & sea", tags.get("XMP-dc:Title"), all);
            assertEquals("Ada, Grace", tags.get("XMP-dc:Creator"), all);
            assertEquals("hdr, test", tags.get("XMP-dc:Subject"), all);
            assertEquals("Smalt test", tags.get("XMP-xmp:CreatorTool"), all);
            assertEquals("0.008", tags.get("XMP-exif:ExposureTime"), all);
            assertEquals("2.8", tags.get("XMP-exif:FNumber"), all);
            assertEquals("1.0", tags.get("XMP-hdrgm:Version"), all);
            assertEquals("Primary, GainMap", tags.get("XMP-GContainer:DirectoryItemSemantic"), all);
            var images = JpegContainer.images(file, null);
            assertTrue(String.valueOf(tags.get("XMP-GContainer:DirectoryItemLength")).contains(
                    String.valueOf(images.get(1).length())), all);
            if (large) {
                assertTrue(tags.get("XMP-dc:Description").length() > 90_000, all);
            }
            assertTrue(tags.entrySet().stream().filter(entry -> entry.getKey().endsWith(":Warning")
                    || entry.getKey().endsWith(":Error")).noneMatch(entry -> entry.getValue().contains("XMP")), all);
            var gainMapPath = directory.resolve("ultrahdr-xmp-" + large + "-gainmap.jpg");
            Files.write(gainMapPath, Arrays.copyOfRange(file, images.get(1).offset(), file.length));
            var gainMapTags = exiftool(gainMapPath);
            var gainMapAll = gainMapTags.toString();
            assertEquals("the gain map", gainMapTags.get("XMP-dc:Description"), gainMapAll);
            assertEquals("1.0", gainMapTags.get("XMP-hdrgm:Version"), gainMapAll);
            assertNotNull(gainMapTags.get("XMP-hdrgm:GainMapMax"), gainMapAll);
            assertEquals(gainMap.metadata().channel(0).maximum(),
                    Double.parseDouble(gainMapTags.get("XMP-hdrgm:GainMapMax")), 1e-6, gainMapAll);
            assertNull(gainMapTags.get("XMP-GContainer:DirectoryItemSemantic"), gainMapAll);
        }

        @ParameterizedTest(name = "large {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("libultrahdr renders the file as Smalt does")
        void libultrahdr(boolean large) throws IOException {
            Oracle.assume(Oracle.Tool.ULTRAHDR_APP);
            var base = JpegGainMapTest.sdr(97, 61);
            var gainMap = gainMap(base, large);
            var file = JpegEncoder.encode(base, gainMap, primaryOptions(large ? largeXmp(120_000) : primaryXmp()),
                    gainMapOptions(large ? largeXmp(70_000) : gainMapXmp()));
            var path = Scratch.directory("jpeg-gainmap-xmp").resolve("ultrahdr-app-" + large + ".jpg");
            Files.write(path, file);
            var expected = JpegGainMapDifferentialTest.oracleDecode(path, 97, 61);
            var primary = JpegDecoder.decode(MediaSource.of(file));
            var read = JpegDecoder.decodeGainMap(MediaSource.of(file)).orElseThrow();
            Tolerance.offByOne().assertWithin("caller XMP, large " + large, expected,
                    JpegGainMapDifferentialTest.tenBit(JpegGainMapDifferentialTest.rendered(primary, read)));
            // and as it renders the file without the packets
            var plainPath = path.resolveSibling("ultrahdr-app-" + large + "-plain.jpg");
            Files.write(plainPath, JpegEncoder.encode(base, gainMap, primaryOptions(null), gainMapOptions(null)));
            assertArrayEquals(JpegGainMapDifferentialTest.oracleDecode(plainPath, 97, 61), expected);
        }
    }
}
