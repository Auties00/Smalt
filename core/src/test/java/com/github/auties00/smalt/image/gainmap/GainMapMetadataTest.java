package com.github.auties00.smalt.image.gainmap;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.metadata.XmpPacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the gain map metadata model: the binary structure of ISO 21496-1 (both fraction layouts, every
 * truncation, invalid fields and version rules), the {@code hdrgm} XMP properties of the Adobe gain map
 * specification, and the weight of a display headroom. Binary vectors are written field by field in the test, in
 * the layout libavif's {@code avifParseGainMapMetadata} and libultrahdr's {@code decodeGainmapMetadata} read.
 */
@DisplayName("Gain map metadata")
class GainMapMetadataTest {
    /** Writes the binary structure of one channel with its own denominators. */
    static byte[] iso(int writerVersion, int flags, long[] headrooms, long[]... channels) {
        var out = new ByteArrayOutputStream();
        u16(out, 0);
        u16(out, writerVersion);
        out.write(flags);
        for (var value : headrooms) {
            u32(out, value);
        }
        for (var channel : channels) {
            for (var value : channel) {
                u32(out, value);
            }
        }
        return out.toByteArray();
    }

    static void u16(ByteArrayOutputStream out, int value) {
        out.write(value >>> 8);
        out.write(value);
    }

    static void u32(ByteArrayOutputStream out, long value) {
        out.write((int) (value >>> 24));
        out.write((int) (value >>> 16));
        out.write((int) (value >>> 8));
        out.write((int) value);
    }

    /** A single-channel structure: headroom 0 and 2.5, min -0.25, max 3, gamma 1, offsets 1/64. */
    static byte[] single() {
        return iso(0, 0x40, new long[]{0, 1, 5, 2},
                new long[]{-1 & 0xFFFFFFFFL, 4, 3, 1, 1, 1, 1, 64, 1, 64});
    }

    @Nested
    @DisplayName("binary ISO 21496-1")
    class Binary {
        @Test
        @DisplayName("reads a single-channel structure")
        void single() {
            var metadata = GainMapMetadata.parse(GainMapMetadataTest.single());
            assertEquals(0, metadata.baseHeadroom());
            assertEquals(2.5, metadata.alternateHeadroom());
            assertTrue(metadata.useBaseColorSpace());
            assertFalse(metadata.isMultichannel());
            var channel = metadata.channel(2);
            assertEquals(-0.25, channel.minimum());
            assertEquals(3, channel.maximum());
            assertEquals(1, channel.gamma());
            assertEquals(1.0 / 64, channel.baseOffset());
            assertEquals(1.0 / 64, channel.alternateOffset());
        }

        @Test
        @DisplayName("writes a structure that reads back to the same bytes and values")
        void roundTrip() {
            var bytes = GainMapMetadataTest.single();
            var metadata = GainMapMetadata.parse(bytes);
            assertArrayEquals(bytes, metadata.toByteArray());
            assertEquals(metadata, GainMapMetadata.parse(metadata.toByteArray()));
        }

        @Test
        @DisplayName("reads three channels and the alternate color space flag")
        void multichannel() {
            var bytes = iso(0, 0x80, new long[]{3, 2, 0, 1},
                    new long[]{0, 1, 1, 1, 1, 1, 0, 1, 0, 1},
                    new long[]{0, 1, 2, 1, 2, 1, 1, 10, 1, 10},
                    new long[]{1, 2, 3, 1, 1, 2, 0, 1, 1, 100});
            var metadata = GainMapMetadata.parse(bytes);
            assertTrue(metadata.isMultichannel());
            assertFalse(metadata.useBaseColorSpace());
            assertEquals(1.5, metadata.baseHeadroom());
            assertEquals(0, metadata.alternateHeadroom());
            assertTrue(metadata.isBaseBrighter());
            assertEquals(new GainMapChannel(0, 2, 2, 0.1, 0.1), metadata.channel(1));
            assertEquals(new GainMapChannel(0.5, 3, 0.5, 0, 0.01), metadata.channel(2));
            assertArrayEquals(bytes, metadata.toByteArray());
        }

        @Test
        @DisplayName("reads the common denominator layout of the 2025 edition")
        void commonDenominator() {
            var out = new ByteArrayOutputStream();
            u16(out, 0);
            u16(out, 0);
            out.write(0x40 | 0x08);
            u32(out, 8);
            u32(out, 0);
            u32(out, 20);
            for (var value : new long[]{-2, 24, 8, 1, 3}) {
                u32(out, value & 0xFFFFFFFFL);
            }
            var metadata = GainMapMetadata.parse(out.toByteArray());
            assertEquals(2.5, metadata.alternateHeadroom());
            assertEquals(new GainMapChannel(-0.25, 3, 1, 0.125, 0.375), metadata.channel(0));
        }

        @Test
        @DisplayName("rejects every truncation")
        void truncated() {
            var bytes = GainMapMetadataTest.single();
            for (var length = 0; length < bytes.length; length++) {
                var cut = java.util.Arrays.copyOf(bytes, length);
                assertThrows(MediaException.Malformed.class, () -> GainMapMetadata.parse(cut), "length " + length);
            }
        }

        @ParameterizedTest(name = "denominator field {0}")
        @ValueSource(ints = {9, 17, 25, 33, 41, 49, 57})
        @DisplayName("rejects zero denominators")
        void zeroDenominator(int offset) {
            var bytes = GainMapMetadataTest.single();
            bytes[offset] = bytes[offset + 1] = bytes[offset + 2] = bytes[offset + 3] = 0;
            assertThrows(MediaException.Malformed.class, () -> GainMapMetadata.parse(bytes));
        }

        @Test
        @DisplayName("rejects a zero gamma and a maximum below the minimum")
        void invalidValues() {
            var zeroGamma = iso(0, 0, new long[]{0, 1, 1, 1}, new long[]{0, 1, 1, 1, 0, 1, 0, 1, 0, 1});
            assertThrows(MediaException.Malformed.class, () -> GainMapMetadata.parse(zeroGamma));
            var inverted = iso(0, 0, new long[]{0, 1, 1, 1}, new long[]{2, 1, 1, 1, 1, 1, 0, 1, 0, 1});
            assertThrows(MediaException.Malformed.class, () -> GainMapMetadata.parse(inverted));
        }

        @Test
        @DisplayName("applies the version rules of libavif's test files")
        void versions() {
            var minimum = GainMapMetadataTest.single();
            minimum[1] = 99;
            assertThrows(MediaException.Unsupported.class, () -> GainMapMetadata.parse(minimum));
            var extra = java.util.Arrays.copyOf(GainMapMetadataTest.single(), GainMapMetadataTest.single().length + 3);
            assertThrows(MediaException.Malformed.class, () -> GainMapMetadata.parse(extra));
            extra[3] = 99;
            assertEquals(2.5, GainMapMetadata.parse(extra).alternateHeadroom());
        }

        @Test
        @DisplayName("refuses the backward direction flag")
        void backward() {
            var bytes = GainMapMetadataTest.single();
            bytes[4] |= 0x04;
            assertThrows(MediaException.Unsupported.class, () -> GainMapMetadata.parse(bytes));
        }

        @Test
        @DisplayName("writes values as close fractions")
        void fractions() {
            var random = new Random(21496);
            for (var i = 0; i < 2000; i++) {
                var minimum = random.nextGaussian() * 4;
                var maximum = minimum + random.nextDouble() * 8;
                var channel = new GainMapChannel(minimum, maximum, 0.2 + random.nextDouble() * 3,
                        random.nextDouble() / 8, random.nextDouble() / 8);
                var metadata = new GainMapMetadata(random.nextDouble() * 4, random.nextDouble() * 6, List.of(channel),
                        random.nextBoolean());
                var read = GainMapMetadata.parse(metadata.toByteArray());
                assertEquals(metadata.baseHeadroom(), read.baseHeadroom(), 1e-9);
                assertEquals(metadata.alternateHeadroom(), read.alternateHeadroom(), 1e-9);
                assertEquals(channel.minimum(), read.channel(0).minimum(), 1e-8);
                assertEquals(channel.maximum(), read.channel(0).maximum(), 1e-8);
                assertEquals(channel.gamma(), read.channel(0).gamma(), 1e-9);
                assertEquals(channel.baseOffset(), read.channel(0).baseOffset(), 1e-9);
                assertEquals(metadata.useBaseColorSpace(), read.useBaseColorSpace());
            }
        }

        @Test
        @DisplayName("writes back the extreme values of its fields")
        void extremes() {
            // the smallest and largest numerators of each field, over a denominator of 1
            var bytes = iso(0, 0, new long[]{0xFFFFFFFFL, 1, 0, 1},
                    new long[]{0x80000000L, 1, 0x7FFFFFFFL, 1, 0xFFFFFFFFL, 1, 0x80000000L, 1, 0x7FFFFFFFL, 1});
            var metadata = GainMapMetadata.parse(bytes);
            assertEquals(4294967295.0, metadata.baseHeadroom());
            assertEquals(-2147483648.0, metadata.channel(0).minimum());
            assertEquals(2147483647.0, metadata.channel(0).maximum());
            assertArrayEquals(bytes, metadata.toByteArray());
        }

        @Test
        @DisplayName("refuses values no 32-bit fraction holds")
        void tooLarge() {
            var metadata = GainMapMetadata.of(0, 1, GainMapChannel.of(-1e10, 1));
            assertThrows(IllegalArgumentException.class, metadata::toByteArray);
        }

        @Test
        @DisplayName("never throws an unchecked exception on random input")
        void random() {
            var random = new Random(7);
            for (var i = 0; i < 20000; i++) {
                var bytes = new byte[random.nextInt(160)];
                random.nextBytes(bytes);
                if (bytes.length > 2 && random.nextBoolean()) {
                    bytes[0] = bytes[1] = 0;
                }
                try {
                    GainMapMetadata.parse(bytes);
                } catch (MediaException expected) {
                    // rejected cleanly
                }
            }
        }
    }

    @Nested
    @DisplayName("XMP hdrgm")
    class Xmp {
        static final String ULTRA_HDR = """
                <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.2">
                 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                  <rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0"
                    hdrgm:GainMapMin="0" hdrgm:GainMapMax="2.3219281" hdrgm:Gamma="1" hdrgm:OffsetSDR="0"
                    hdrgm:OffsetHDR="0" hdrgm:HDRCapacityMin="0" hdrgm:HDRCapacityMax="2.3219281"
                    hdrgm:BaseRenditionIsHDR="False"/>
                 </rdf:RDF>
                </x:xmpmeta>""";

        @Test
        @DisplayName("reads the attributes libultrahdr writes")
        void ultraHdr() {
            var metadata = GainMapMetadata.fromXmp(XmpPacket.parse(ULTRA_HDR)).orElseThrow();
            assertEquals(0, metadata.baseHeadroom());
            assertEquals(2.3219281, metadata.alternateHeadroom(), 1e-12);
            assertEquals(new GainMapChannel(0, 2.3219281, 1, 0, 0), metadata.channel(0));
            assertTrue(metadata.useBaseColorSpace());
        }

        @Test
        @DisplayName("defaults the optional properties")
        void defaults() {
            var xml = """
                    <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                    <rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0"
                     hdrgm:GainMapMax="3" hdrgm:HDRCapacityMax="3"/></rdf:RDF></x:xmpmeta>""";
            var metadata = GainMapMetadata.fromXmp(XmpPacket.parse(xml)).orElseThrow();
            assertEquals(GainMapMetadata.of(0, 3, GainMapChannel.of(0, 3)), metadata);
        }

        @Test
        @DisplayName("reads rdf:Seq per-channel values and an HDR base")
        void sequences() {
            var xml = """
                    <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                    <rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0"
                     hdrgm:HDRCapacityMin="0.5" hdrgm:HDRCapacityMax="3" hdrgm:BaseRenditionIsHDR="True"
                     hdrgm:OffsetSDR="0.01" hdrgm:OffsetHDR="0.02">
                     <hdrgm:GainMapMax><rdf:Seq><rdf:li>1</rdf:li><rdf:li>2</rdf:li><rdf:li>3</rdf:li></rdf:Seq></hdrgm:GainMapMax>
                     <hdrgm:Gamma><rdf:Seq><rdf:li>1</rdf:li><rdf:li>1.5</rdf:li><rdf:li>2</rdf:li></rdf:Seq></hdrgm:Gamma>
                    </rdf:Description></rdf:RDF></x:xmpmeta>""";
            var metadata = GainMapMetadata.fromXmp(XmpPacket.parse(xml)).orElseThrow();
            assertTrue(metadata.isMultichannel());
            assertEquals(3, metadata.baseHeadroom());
            assertEquals(0.5, metadata.alternateHeadroom());
            assertEquals(new GainMapChannel(0, 2, 1.5, 0.02, 0.01), metadata.channel(1));
            assertEquals(metadata, GainMapMetadata.fromXmp(metadata.toXmp()).orElseThrow());
        }

        @Test
        @DisplayName("is empty for the primary image of an Ultra HDR file")
        void primary() {
            var xml = """
                    <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                    <rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0"/>
                    </rdf:RDF></x:xmpmeta>""";
            assertTrue(GainMapMetadata.fromXmp(XmpPacket.parse(xml)).isEmpty());
            assertTrue(GainMapMetadata.fromXmp(XmpPacket.empty()).isEmpty());
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = ';', value = {
                "not a number; hdrgm:GainMapMax=\"abc\" hdrgm:HDRCapacityMax=\"1\"",
                "missing capacity; hdrgm:GainMapMax=\"1\"",
                "bad boolean; hdrgm:GainMapMax=\"1\" hdrgm:HDRCapacityMax=\"1\" hdrgm:BaseRenditionIsHDR=\"yes\"",
                "inverted range; hdrgm:GainMapMin=\"2\" hdrgm:GainMapMax=\"1\" hdrgm:HDRCapacityMax=\"1\"",
                "negative headroom; hdrgm:GainMapMax=\"1\" hdrgm:HDRCapacityMax=\"-1\"",
                "infinite; hdrgm:GainMapMax=\"Infinity\" hdrgm:HDRCapacityMax=\"1\""})
        @DisplayName("rejects invalid properties")
        void invalid(String name, String attributes) {
            var xml = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                    + "<rdf:Description xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\" hdrgm:Version=\"1.0\" "
                    + attributes + "/></rdf:RDF></x:xmpmeta>";
            assertThrows(MediaException.Malformed.class, () -> GainMapMetadata.fromXmp(XmpPacket.parse(
                    xml.getBytes(StandardCharsets.UTF_8))), name);
        }

        @Test
        @DisplayName("writes single values and reads them back")
        void roundTrip() {
            var metadata = GainMapMetadata.of(0, 2.75, new GainMapChannel(-0.5, 2.75, 1.25, 1.0 / 64, 1e-7));
            var xmp = metadata.toXmp();
            assertEquals("1.0", xmp.text(GainMapMetadata.XMP_NAMESPACE, "Version").orElseThrow());
            assertEquals("False", xmp.text(GainMapMetadata.XMP_NAMESPACE, "BaseRenditionIsHDR").orElseThrow());
            // an XMP Real has no exponent
            assertEquals("0.0000001", xmp.text(GainMapMetadata.XMP_NAMESPACE, "OffsetHDR").orElseThrow());
            assertEquals("2.75", xmp.text(GainMapMetadata.XMP_NAMESPACE, "HDRCapacityMax").orElseThrow());
            assertEquals(metadata, GainMapMetadata.fromXmp(XmpPacket.parse(xmp.toBytes())).orElseThrow());
        }
    }

    @Nested
    @DisplayName("model")
    class Model {
        @ParameterizedTest(name = "headroom {0} -> {1}")
        @CsvSource({"-1, 0", "0, 0", "1, 0.25", "2, 0.5", "4, 1", "9, 1", "NaN, 0"})
        @DisplayName("weighs display headrooms between the renditions")
        void weight(double headroom, double weight) {
            var metadata = GainMapMetadata.of(0, 4, GainMapChannel.of(0, 4));
            assertEquals(weight, metadata.weight(headroom), 1e-12);
            var reversed = GainMapMetadata.of(4, 0, GainMapChannel.of(0, 4));
            assertEquals(Double.isNaN(headroom) ? 0 : 1 - weight, reversed.weight(headroom), 1e-12);
        }

        @Test
        @DisplayName("gives no weight between renditions of equal headroom")
        void equalHeadrooms() {
            assertEquals(0, GainMapMetadata.of(2, 2, GainMapChannel.of(0, 1)).weight(3));
        }

        @Test
        @DisplayName("maps samples to log2 gains through the gamma")
        void log2Gain() {
            var channel = new GainMapChannel(-1, 3, 2, 0, 0);
            assertEquals(-1, channel.log2Gain(0));
            assertEquals(3, channel.log2Gain(1));
            assertEquals(-1 + 4 * Math.sqrt(0.25), channel.log2Gain(0.25), 1e-12);
            assertEquals(3, channel.log2Gain(7));
            assertEquals(-1, channel.log2Gain(Double.NaN));
        }

        @Test
        @DisplayName("validates its values")
        void validation() {
            assertThrows(IllegalArgumentException.class, () -> new GainMapChannel(1, 0, 1, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> new GainMapChannel(0, 1, 0, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> new GainMapChannel(0, Double.NaN, 1, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> GainMapMetadata.of(-1, 0, GainMapChannel.of(0, 1)));
            assertThrows(IllegalArgumentException.class, () -> new GainMapMetadata(0, 1,
                    List.of(GainMapChannel.of(0, 1), GainMapChannel.of(0, 1)), true));
            assertThrows(IndexOutOfBoundsException.class, () -> GainMapMetadata.of(0, 1, GainMapChannel.of(0, 1))
                    .channel(3));
        }
    }
}
