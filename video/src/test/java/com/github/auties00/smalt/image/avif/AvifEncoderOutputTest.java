package com.github.auties00.smalt.image.avif;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.io.MediaSink;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.DisplayName;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the bytes of the AVIF files the encoder writes for small deterministic pictures, so that
 * changes to the file layout keep it exactly as it is: the CRC-32 of each file is compared with
 * the recorded value. A changed value is either a regression or a deliberate change of the
 * output, which then re-records the table.
 */
@DisplayName("AVIF encoder output")
class AvifEncoderOutputTest {
    static final Map<String, Long> EXPECTED = Map.ofEntries(
            Map.entry("still 4:2:0", 892671760L),
            Map.entry("still with alpha", 3162658803L),
            Map.entry("still premultiplied alpha", 2045495846L),
            Map.entry("still grid with alpha", 3743764543L),
            Map.entry("still lossless", 1809451712L),
            Map.entry("still 10-bit 4:4:4", 969899688L),
            Map.entry("still with metadata", 1896312792L),
            Map.entry("animation of key frames with alpha", 3313142257L),
            Map.entry("animation of predicted frames", 3562154087L)
    );

    static RgbaPicture rgba(int width, int height, int frame, boolean alpha) {
        var pixels = new byte[width * height * 4];
        var state = 12345 + frame * 7919;
        for (var i = 0; i < pixels.length; i += 4) {
            state = state * 1664525 + 1013904223;
            var x = i / 4 % width + frame * 2;
            var y = i / 4 / width + frame;
            pixels[i] = (byte) (x * 255 / width + ((state >>> 20) & 7));
            pixels[i + 1] = (byte) (y * 255 / height + ((state >>> 16) & 7));
            pixels[i + 2] = (byte) ((x ^ y) * 4);
            pixels[i + 3] = (byte) (alpha ? x * 255 / width : 255);
        }
        return RgbaPicture.of(width, height, width * 4, pixels, alpha);
    }

    static AvifEncoderOptions.Builder fast() {
        return AvifEncoderOptions.lossy(60).toBuilder().speed(10).threads(1);
    }

    static byte[] animation(AvifEncoderOptions options, boolean alpha) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var writer = AvifEncoder.openAnimationWriter(48, 32, options, MediaSink.of(out))) {
            for (var i = 0; i < 3; i++) {
                writer.write(rgba(48, 32, i, alpha && i > 0), 40_000L * (i + 1));
            }
            writer.finish();
        }
        return out.toByteArray();
    }

    record Case(String name, Callable<byte[]> file) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<Case> cases() {
        var cases = new ArrayList<Case>();
        cases.add(new Case("still 4:2:0", () -> AvifEncoder.encode(rgba(48, 32, 0, false), fast().build())));
        cases.add(new Case("still with alpha", () -> AvifEncoder.encode(rgba(48, 32, 0, true), fast().build())));
        cases.add(new Case("still premultiplied alpha", () -> AvifEncoder.encode(rgba(48, 32, 0, true),
                fast().premultipliedAlpha(true).build())));
        cases.add(new Case("still grid with alpha", () -> AvifEncoder.encode(rgba(96, 72, 0, true),
                fast().gridTileSize(64).build())));
        cases.add(new Case("still lossless", () -> AvifEncoder.encode(rgba(16, 16, 0, false),
                AvifEncoderOptions.lossless().toBuilder().speed(10).threads(1).build())));
        cases.add(new Case("still 10-bit 4:4:4", () -> AvifEncoder.encode(rgba(48, 32, 0, false),
                fast().bitDepth(10).chromaFormat(ChromaFormat.YUV444).build())));
        cases.add(new Case("still with metadata", () -> AvifEncoder.encode(rgba(48, 32, 0, false),
                fast().orientation(Orientation.ROTATE_90).colorProfile(ColorProfile.displayP3())
                        .exif(exif()).xmp("<x:xmpmeta/>".getBytes(StandardCharsets.UTF_8)).build())));
        cases.add(new Case("animation of key frames with alpha", () -> animation(fast().interFrames(false).build(),
                true)));
        cases.add(new Case("animation of predicted frames", () -> animation(fast().build(), false)));
        return cases;
    }

    static byte[] exif() {
        return new byte[]{'E', 'x', 'i', 'f', 0, 0, 'M', 'M', 0, 42, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0};
    }

    static long crc(byte[] data) {
        var crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("produces the recorded bytes")
    void recorded(Case c) throws Exception {
        assertEquals(EXPECTED.get(c.name()), crc(c.file().call()), c.name());
    }
}
