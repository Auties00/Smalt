package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.AuxiliaryImageType;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.gainmap.GainMapChannel;
import com.github.auties00.smalt.image.gainmap.GainMapMetadata;
import com.github.auties00.smalt.image.gainmap.GainMapOptions;
import com.github.auties00.smalt.image.gainmap.GainMaps;
import com.github.auties00.smalt.image.heic.HeicStructureTest.Coded;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.HeifBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static com.github.auties00.smalt.image.heic.HeicStructureTest.encode;
import static com.github.auties00.smalt.image.heic.HeicStructureTest.heic;
import static com.github.auties00.smalt.image.heic.HeicStructureTest.still;
import static com.github.auties00.smalt.testkit.HeifBuilder.essential;
import static com.github.auties00.smalt.testkit.HeifBuilder.plain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers HEIC gain maps and auxiliary images in files built box by box around x265 payloads, laid out as iPhone
 * portrait photos are: a primary image with a half-size HEVC depth map ({@code urn:mpeg:hevc:2015:auxid:2}), a
 * portrait effects matte and Apple's HDR gain map, whose headroom comes from the tags 33 and 48 of the Apple maker
 * note in the EXIF item and whose XMP declares {@code HDRGainMap:HDRGainMapVersion}; and the ISO 21496-1
 * {@code tmap} derived image item. Apple's conversion is checked against the equation of Apple's "Applying Apple
 * HDR effect to your photos" written in the test.
 */
@DisplayName("HEIC gain maps and auxiliary images")
public class HeicGainMapTest {
    static final String DEPTH = "urn:mpeg:hevc:2015:auxid:2";
    static final String MATTE = "urn:com:apple:photo:2018:aux:portraiteffectsmatte";
    static final String APPLE = "urn:com:apple:photo:2020:aux:hdrgainmap";

    static Coded color(int width, int height) {
        return encode("scale=" + width + ":" + height, width, height, "yuv420p", "");
    }

    static Coded gray(int width, int height, String expression) {
        return encode("scale=" + width + ":" + height + ",format=gray,geq=lum='" + expression + "'", width, height,
                "gray", "");
    }

    static int auxiliary(HeifBuilder builder, Coded coded, String urn, int master) {
        var id = builder.item("hvc1", coded.payload(), plain(HeifBuilder.ispe(coded.width(), coded.height())),
                essential(HeifBuilder.box("hvcC", coded.config())), essential(HeifBuilder.auxC(urn)));
        builder.reference("auxl", id, master).hidden(id);
        return id;
    }

    /** An EXIF block whose Apple maker note holds tags 33 and 48 as signed rationals. */
    public static byte[] exif(int maker33Numerator, int maker33Denominator, int maker48Numerator, int maker48Denominator) {
        var note = new ByteArrayOutputStream();
        note.writeBytes("Apple iOS\0".getBytes(StandardCharsets.ISO_8859_1));
        note.writeBytes(new byte[]{0, 1, 'M', 'M'});
        u16(note, 2);
        entry(note, 33, 10, 1, 14 + 2 + 24 + 4);
        entry(note, 48, 10, 1, 14 + 2 + 24 + 4 + 8);
        u32(note, 0);
        u32(note, maker33Numerator);
        u32(note, maker33Denominator);
        u32(note, maker48Numerator);
        u32(note, maker48Denominator);
        var makerNote = note.toByteArray();
        var tiff = new ByteArrayOutputStream();
        tiff.writeBytes(new byte[]{'M', 'M', 0, 0x2A});
        u32(tiff, 8);
        u16(tiff, 1);
        entry(tiff, 0x8769, 4, 1, 8 + 2 + 12 + 4);
        u32(tiff, 0);
        u16(tiff, 1);
        entry(tiff, 0x927C, 7, makerNote.length, 8 + 2 + 12 + 4 + 2 + 12 + 4);
        u32(tiff, 0);
        tiff.writeBytes(makerNote);
        var payload = new ByteArrayOutputStream();
        u32(payload, 0);
        payload.writeBytes(tiff.toByteArray());
        return payload.toByteArray();
    }

    static void entry(ByteArrayOutputStream out, int tag, int type, int count, int value) {
        u16(out, tag);
        u16(out, type);
        u32(out, count);
        u32(out, value);
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

    static byte[] appleXmp() {
        return ("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\" xmlns:HDRGainMap=\"http://ns.apple.com/HDRGainMap/1.0/\""
                + " HDRGainMap:HDRGainMapVersion=\"65536\"/></rdf:RDF></x:xmpmeta>").getBytes(StandardCharsets.UTF_8);
    }

    /** A portrait photo: the primary image, a half-size depth map and matte, and a gain map of the given size. */
    public static byte[] portrait(int gainMapWidth, int gainMapHeight, byte[] exif) {
        var builder = heic();
        var primary = still(builder, color(128, 96));
        builder.primary(primary);
        auxiliary(builder, gray(64, 48, "X*4"), DEPTH, primary);
        auxiliary(builder, gray(64, 48, "Y*5"), MATTE, primary);
        var gainMap = auxiliary(builder, gray(gainMapWidth, gainMapHeight, "128+100*sin(X/9)*cos(Y/7)"), APPLE,
                primary);
        var xmp = builder.mimeItem("application/rdf+xml", appleXmp());
        builder.reference("cdsc", xmp, gainMap);
        var exifItem = builder.item("Exif", exif);
        builder.reference("cdsc", exifItem, primary);
        return builder.build();
    }

    static double bt709Inverse(double v) {
        return v < 0.081 ? v / 4.5 : Math.pow((v + 0.099) / 1.099, 1 / 0.45);
    }

    static double srgb(double v) {
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    static double pq(double nits) {
        var m1 = 2610.0 / 16384;
        var m2 = 2523.0 / 4096 * 128;
        var c1 = 3424.0 / 4096;
        var c2 = 2413.0 / 4096 * 32;
        var c3 = 2392.0 / 4096 * 32;
        var p = Math.pow(Math.min(Math.max(nits / 10000, 0), 1), m1);
        return Math.pow((c1 + c2 * p) / (1 + c3 * p), m2);
    }

    @Nested
    @DisplayName("iPhone portrait layout")
    class Portrait {
        @Test
        @DisplayName("lists the depth map, matte and gain map with their plane sizes")
        void listing() {
            var file = portrait(64, 48, exif(1, 2, 3, 100));
            var listed = HeicDecoder.auxiliaryImages(MediaSource.of(file));
            assertEquals(3, listed.size());
            assertEquals(AuxiliaryImageType.DEPTH, listed.get(0).type());
            assertEquals(DEPTH, listed.get(0).identifier());
            assertEquals(64, listed.get(0).width());
            assertEquals(48, listed.get(0).height());
            assertEquals(AuxiliaryImageType.MATTE, listed.get(1).type());
            assertEquals(AuxiliaryImageType.GAIN_MAP, listed.get(2).type());
            assertEquals(APPLE, listed.get(2).identifier());
            var depth = HeicDecoder.decodeAuxiliaryImage(MediaSource.of(file), 0);
            var picture = assertInstanceOf(YuvPicture.class, depth.picture());
            assertEquals(ChromaFormat.MONOCHROME, picture.chromaFormat());
            assertEquals(64, picture.width());
            assertEquals(48, picture.height());
            // the depth ramp X*4 rises left to right
            assertTrue((picture.y()[10] & 0xFF) < (picture.y()[50] & 0xFF));
            assertThrows(IndexOutOfBoundsException.class, () -> HeicDecoder.decodeAuxiliaryImage(
                    MediaSource.of(file), 3));
            var primary = HeicDecoder.decode(MediaSource.of(file));
            assertEquals(128, primary.picture().width());
        }

        @ParameterizedTest(name = "maker33 {0}/{1}, maker48 {2}/{3}")
        @CsvSource({"1, 2, 3, 100", "1, 2, 1, 500", "3, 2, 1, 500", "3, 2, 1, 10"})
        @DisplayName("converts Apple's gain map with the headroom of the maker note")
        void apple(int n33, int d33, int n48, int d48) {
            var file = portrait(128, 96, exif(n33, d33, n48, d48));
            var gainMap = HeicDecoder.decodeGainMap(MediaSource.of(file)).orElseThrow();
            var maker33 = (double) n33 / d33;
            var maker48 = (double) n48 / d48;
            double stops;
            if (maker33 < 1) {
                stops = maker48 <= 0.01 ? -20 * maker48 + 1.8 : -0.101 * maker48 + 1.601;
            } else {
                stops = maker48 <= 0.01 ? -70 * maker48 + 3 : -0.303 * maker48 + 2.303;
            }
            var headroom = Math.pow(2, stops);
            var metadata = gainMap.metadata();
            assertEquals(stops, metadata.alternateHeadroom(), 1e-9);
            assertEquals(0, metadata.baseHeadroom());
            assertEquals(new GainMapChannel(0, stops, 1, 0, 0), metadata.channel(0));
            var auxiliary = (YuvPicture) HeicDecoder.decodeAuxiliaryImage(MediaSource.of(file), 2).picture();
            var base = HeicDecoder.decode(MediaSource.of(file));
            var rendered = (Rgba16Picture) GainMaps.apply(base, gainMap, GainMapOptions.builder()
                    .primaries(ColorPrimaries.BT709).build()).picture();
            var rgb = (Rgba16Picture) ImageOps.toRgba16(base.picture());
            var transfer = ((YuvPicture) base.picture()).color().transfer();
            var bt709 = transfer == TransferCharacteristics.BT709 || transfer == TransferCharacteristics.SMPTE170M;
            for (var y = 0; y < 96; y += 5) {
                for (var x = 0; x < 128; x += 3) {
                    var g = bt709Inverse((auxiliary.y()[y * auxiliary.yStride() + x] & 0xFF) / 255.0);
                    for (var c = 0; c < 3; c++) {
                        var signal = (rgb.pixels()[y * rgb.stride() + x * 4 + c] & 0xFFFF) / 65535.0;
                        var sdr = bt709 ? bt709Inverse(signal) : srgb(signal);
                        var hdr = sdr * (1 + (headroom - 1) * g);
                        var expected = (int) Math.round(pq(hdr * 203) * 65535);
                        var actual = rendered.pixels()[y * rendered.stride() + x * 4 + c] & 0xFFFF;
                        assertTrue(Math.abs(expected - actual) <= 3, "pixel " + x + "," + y + ": " + expected + " vs "
                                + actual);
                    }
                }
            }
        }

        @Test
        @DisplayName("ignores Apple's gain map without the maker note or the version")
        void appleWithoutHeadroom() {
            var builder = heic();
            var primary = still(builder, color(64, 48));
            builder.primary(primary);
            auxiliary(builder, gray(32, 24, "X"), APPLE, primary);
            var file = builder.build();
            assertTrue(HeicDecoder.decodeGainMap(MediaSource.of(file)).isEmpty());
            assertEquals(AuxiliaryImageType.GAIN_MAP, HeicDecoder.auxiliaryImages(MediaSource.of(file)).getFirst()
                    .type());
        }
    }

    @Nested
    @DisplayName("tmap items")
    class ToneMap {
        static byte[] file(byte[] payload, boolean preferred) {
            var builder = heic();
            var primary = still(builder, color(64, 48));
            builder.primary(primary);
            var gainMap = still(builder, gray(32, 24, "X*8"));
            builder.hidden(gainMap);
            var tmap = builder.idatItem("tmap", payload, plain(HeifBuilder.ispe(64, 48)),
                    plain(HeifBuilder.nclx(9, 16, 0, true)), plain(HeifBuilder.clli(1000, 200)));
            builder.reference("dimg", tmap, primary, gainMap);
            builder.metaBox(HeifBuilder.grpl(HeifBuilder.entityGroup("altr", 100, preferred ? tmap : primary,
                    preferred ? primary : tmap)));
            return builder.build();
        }

        static byte[] payload(GainMapMetadata metadata) {
            var bytes = metadata.toByteArray();
            var payload = new byte[bytes.length + 1];
            System.arraycopy(bytes, 0, payload, 1, bytes.length);
            return payload;
        }

        @Test
        @DisplayName("reads the ISO 21496-1 gain map and the alternate color of the tmap item")
        void iso() {
            var metadata = GainMapMetadata.of(0, 2.5, new GainMapChannel(-0.5, 2.5, 1.2, 0.01, 0.02));
            var file = file(payload(metadata), true);
            var gainMap = HeicDecoder.decodeGainMap(MediaSource.of(file)).orElseThrow();
            assertEquals(metadata, gainMap.metadata());
            assertEquals(32, gainMap.image().width());
            var color = gainMap.alternateProfile().orElseThrow().colorInfo().orElseThrow();
            assertEquals(ColorPrimaries.BT2020, color.primaries());
            assertEquals(1000, gainMap.alternateLightLevel().orElseThrow().maxContentLightLevel());
            var listed = HeicDecoder.auxiliaryImages(MediaSource.of(file));
            assertEquals(1, listed.size());
            assertEquals("tmap", listed.getFirst().identifier());
            var rendered = GainMaps.apply(HeicDecoder.decode(MediaSource.of(file)), gainMap);
            assertEquals(ColorPrimaries.BT2020, rendered.colorProfile().orElseThrow().colorInfo().orElseThrow()
                    .primaries());
        }

        @Test
        @DisplayName("ignores a tmap item the alternatives group does not prefer, or of another version")
        void ignored() {
            var metadata = GainMapMetadata.of(0, 1, GainMapChannel.of(0, 1));
            assertTrue(HeicDecoder.decodeGainMap(MediaSource.of(file(payload(metadata), false))).isEmpty());
            var versioned = payload(metadata);
            versioned[0] = 1;
            assertTrue(HeicDecoder.decodeGainMap(MediaSource.of(file(versioned, true))).isEmpty());
        }

        @Test
        @DisplayName("rejects malformed metadata")
        void malformed() {
            assertThrows(MediaException.Malformed.class, () -> HeicDecoder.decodeGainMap(MediaSource.of(
                    file(new byte[]{0, 0, 0, 0, 0, 0, 0}, true))));
        }
    }

    @Test
    @DisplayName("never fails with an unchecked exception on damaged files")
    void damaged() {
        var file = portrait(64, 48, exif(1, 2, 3, 100));
        var random = new Random(5);
        for (var i = 0; i < 200; i++) {
            var damaged = file.clone();
            for (var k = 0; k < 3; k++) {
                damaged[random.nextInt(Math.min(damaged.length, 900))] = (byte) random.nextInt(256);
            }
            try {
                HeicDecoder.decodeGainMap(MediaSource.of(damaged));
                HeicDecoder.auxiliaryImages(MediaSource.of(damaged));
            } catch (MediaException expected) {
                // rejected cleanly
            }
        }
    }
}
