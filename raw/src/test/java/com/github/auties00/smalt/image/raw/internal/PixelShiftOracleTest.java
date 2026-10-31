package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the merged image of pixel-shift files (Pentax PEF files of the K-1 and K-3 Mark III, the DNG file a K-70
 * writes, and a Sony ARQ file of the A7R III) value for value against the linear DNG file Adobe DNG Converter, run
 * as a black box, merges their frames into, summarized by {@code tools/testdata/raw-lens-oracle.py} in
 * {@code <conformance cache>/raw-lens-oracle} ({@code -Dsmalt.test.rawLensOracle}): the SHA-256 of each sample's
 * values over the whole image and its first row. Skipped without the summaries.
 */
class PixelShiftOracleTest {
    private static final Path ORACLE = Path.of(System.getProperty("smalt.test.rawLensOracle",
            Conformance.directory().resolve("raw-lens-oracle").toString()));

    private static final List<String> SAMPLES = List.of(
            "rawpixls-pentax-k-1-14bit-3-2-pixel-shift.pef",
            "rawpixls-pentax-k-3-mark-iii-14bit-3-2-pixel-shift.pef",
            "rawpixls-pentax-k-70-14bit-3-2-pixel-shift.dng",
            "rawpixls-sony-ilce-7rm3-14bit-14bit-uncompressed-3-2.arq");

    private static final MediaLimits LIMITS = MediaLimits.builder().maxPixels(1L << 28).maxBytes(1L << 31).build();

    @Test
    @DisplayName("the merged image equals the converter's, sample for sample")
    void merged() throws IOException, NoSuchAlgorithmException {
        Skip.unless(Files.isDirectory(ORACLE), "data:raw-lens-oracle", "no pixel-shift oracle files in " + ORACLE);
        for (var name : SAMPLES) {
            var stem = name.substring(0, name.lastIndexOf('.'));
            var values = new HashMap<String, String>();
            for (var line : Files.readAllLines(ORACLE.resolve(stem + ".merged"))) {
                if (!line.startsWith("#")) {
                    var space = line.indexOf(' ', line.indexOf(' ') + 1);
                    var key = line.startsWith("size") ? "size" : line.substring(0, space);
                    values.put(key, line.substring(key.length() + 1));
                }
            }
            var size = values.get("size").split(" ");
            var path = Conformance.fetchListed(name);
            var file = RawDecoding.open(MediaSource.of(FileChannel.open(path, StandardOpenOption.READ)), LIMITS);
            var sensor = file.sensor;
            assertEquals(Integer.parseInt(size[0]), sensor.width, name);
            assertEquals(Integer.parseInt(size[1]), sensor.height, name);
            assertEquals(3, sensor.samplesPerPixel, name);
            assertEquals(name.endsWith(".arq") ? 1 : 4, RawDecoding.facts(file).frames(), name);
            var plane = SensorPlane.of(sensor, LIMITS);
            sensor.reader.read(plane, LIMITS);
            for (var sample = 0; sample < 3; sample++) {
                var bytes = ByteBuffer.allocate(sensor.width * sensor.height * 2).order(ByteOrder.LITTLE_ENDIAN);
                for (var index = sample; index < plane.integers.length; index += 3) {
                    bytes.putShort(plane.integers[index]);
                }
                var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
                var first = HexFormat.of().formatHex(bytes.array(), 0, sensor.width * 2);
                assertEquals(values.get("row " + sample), first, name + " first row of sample " + sample);
                assertEquals(values.get("digest " + sample), digest, name + " sample " + sample);
            }
        }
    }

    @Test
    @DisplayName("each frame of a Pentax file decodes alone as a color filter array image")
    void frames() throws IOException {
        var path = Conformance.fetchListed("rawpixls-pentax-k-3-mark-iii-14bit-3-2-pixel-shift.pef");
        var data = Files.readAllBytes(path);
        assertEquals(4, RawDecoder.info(MediaSource.of(data), LIMITS).frameCount());
        var options = RawDecoderOptions.builder().limits(LIMITS);
        var merged = RawDecoder.decodeRaw(MediaSource.of(data), options.build());
        assertEquals(3, merged.channels());
        assertTrue(merged.cfaPattern().isEmpty());
        long[] previous = null;
        for (var frame = 0; frame < 4; frame++) {
            var raw = RawDecoder.decodeRaw(MediaSource.of(data), options.frame(frame).build());
            assertEquals(1, raw.channels());
            assertTrue(raw.cfaPattern().isPresent());
            var sum = new long[1];
            for (var value : raw.samples()) {
                sum[0] += value & 0xFFFF;
            }
            if (previous != null) {
                assertTrue(previous[0] != sum[0], "frames " + (frame - 1) + " and " + frame + " are the same");
            }
            previous = sum;
        }
        assertThrows(MediaException.Unsupported.class,
                () -> RawDecoder.decodeRaw(MediaSource.of(data), options.frame(4).build()));
    }
}
