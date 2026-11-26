package com.github.auties00.smalt.video.hevc;

import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Decodes Netflix's HM encode of the Sintel "Weapon" scene (Main 10, 1920x816, QP 32, 156 frames of natural
 * content) and compares every frame with ffmpeg's decode, bit-exact for HEVC: the MD5 of each frame's 16-bit
 * little-endian planar samples against ffmpeg's {@code framemd5} of {@code yuv420p10le}.
 */
@DisplayName("HEVC real-world streams")
class HevcRealWorldTest {
    @Test
    @DisplayName("Netflix's HM Main 10 encode of Sintel decodes every frame as ffmpeg does")
    void netflixSintel() {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var file = Conformance.vector("hevc-netflix-Sintel_Weapon_1920x816_QP32.265");
        var actual = new ArrayList<String>();
        var options = HevcDecoderOptions.builder().highBitDepth(true).build();
        try (var decoder = new HevcDecoder(HevcTestStreams.track(), options)) {
            Consumer<VideoFrame> sink = frame -> {
                var picture = assertInstanceOf(Yuv16Picture.class, frame.picture());
                assertEquals(10, picture.bitDepth());
                assertEquals("1920x816", picture.width() + "x" + picture.height());
                try {
                    actual.add(HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                            .digest(HevcTestStreams.raw(picture))));
                } catch (NoSuchAlgorithmException exception) {
                    throw new AssertionError(exception);
                }
            };
            for (var nal : HevcTestStreams.splitAnnexB(Fixtures.read(file))) {
                decoder.decode(HevcTestStreams.packet(nal), sink);
            }
            decoder.flush(sink);
        }
        var text = new String(Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(10), List.of("-v", "error", "-i",
                file.toString(), "-pix_fmt", "yuv420p10le", "-f", "framemd5", "-")), StandardCharsets.US_ASCII);
        var expected = new ArrayList<String>();
        for (var line : text.split("\\R")) {
            if (!line.isBlank() && !line.startsWith("#")) {
                var fields = line.split(",");
                expected.add(fields[fields.length - 1].strip());
            }
        }
        assertEquals(156, expected.size(), "frames ffmpeg decoded");
        for (var i = 0; i < Math.min(expected.size(), actual.size()); i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                fail("frame " + i + " differs from ffmpeg");
            }
        }
        assertEquals(expected.size(), actual.size(), "frame count");
    }
}
