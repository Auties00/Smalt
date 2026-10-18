package com.github.auties00.smalt.video.vp8;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Decodes every libvpx VP8 test vector with several thread counts, which run the macroblock rows
 * of a frame on several threads, and compares each shown frame's MD5 with libvpx's.
 */
@DisplayName("VP8 decoder with several threads")
class Vp8ThreadingTest {
    static Stream<String> vectors() {
        return Vp8Vectors.SHA256.keySet().stream().filter(name -> name.endsWith(".ivf")).sorted();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("every shown frame matches libvpx's MD5 for every thread count")
    void vector(String name) throws IOException {
        var ivfPath = Conformance.fetch(name, Vp8Vectors.BASE_URL + name, Vp8Vectors.SHA256.get(name));
        var md5Path = Conformance.fetch(name + ".md5", Vp8Vectors.BASE_URL + name + ".md5", Vp8Vectors.SHA256.get(name + ".md5"));
        var expected = new ArrayList<String>();
        for (var line : Files.readString(md5Path, StandardCharsets.US_ASCII).split("\n")) {
            var trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                expected.add(trimmed.split("\s+")[0]);
            }
        }
        var ivf = Ivf.parse(Files.readAllBytes(ivfPath));
        for (var threads : new int[]{2, 3, 4, 8}) {
            var actual = new ArrayList<String>();
            var options = VideoDecoderOptions.builder().threads(threads).build();
            try (var decoder = new Vp8Decoder(Vp8ConformanceTest.track(ivf.width(), ivf.height()), options)) {
                for (var frame : ivf.frames()) {
                    decoder.decode(new Packet(1, frame.data(), frame.pts(), frame.pts(), 0, false),
                            out -> actual.add(Ivf.md5(Ivf.i420((YuvPicture) out.picture()))));
                }
            }
            assertEquals(expected, actual, "frames with " + threads + " threads");
        }
    }

    /**
     * Decodes the packets of a stream, recording for each the digest of the shown frame or the
     * exception it raised, so that two decodes of the same bytes can be compared.
     */
    private static List<String> outcomes(List<byte[]> frames, int threads) {
        var result = new ArrayList<String>();
        var options = VideoDecoderOptions.builder().threads(threads).build();
        try (var decoder = new Vp8Decoder(Vp8VideoEncoderTest.track(640, 480), options)) {
            for (var i = 0; i < frames.size(); i++) {
                try {
                    var shown = new ArrayList<String>();
                    decoder.decode(new Packet(1, frames.get(i), i, i, 1, false),
                            out -> shown.add(Ivf.md5(Ivf.i420((YuvPicture) out.picture()))));
                    result.add(shown.toString());
                } catch (MediaException exception) {
                    result.add(exception.getClass().getSimpleName() + ": " + exception.getMessage());
                }
            }
        }
        return result;
    }

    @Test
    @DisplayName("a 640x480 stream, intact and damaged, decodes alike on one thread and on eight")
    void wideFrames() {
        var config = Vp8VideoEncoderTest.with(Vp8VideoEncoderTest.config(640, 480, 900_000), 6, false, 1, 1, 1);
        var run = Vp8VideoEncoderTest.encode(config, RtTestVideo.synthesize(640, 480, 14));
        var frames = new ArrayList<byte[]>();
        for (var packet : run.packets()) {
            frames.add(packet.data());
        }
        assertEquals(outcomes(frames, 1), outcomes(frames, 8), "intact");
        var random = new Random(7);
        for (var round = 0; round < 24; round++) {
            var damaged = new ArrayList<byte[]>(frames);
            var target = 1 + random.nextInt(frames.size() - 1);
            var data = damaged.get(target).clone();
            switch (round % 3) {
                case 0 -> damaged.set(target, java.util.Arrays.copyOf(data, 4 + random.nextInt(data.length - 4)));
                case 1 -> {
                    for (var flips = 1 + random.nextInt(6); flips > 0; flips--) {
                        data[10 + random.nextInt(data.length - 10)] ^= (byte) (1 << random.nextInt(8));
                    }
                    damaged.set(target, data);
                }
                default -> {
                    var at = 10 + random.nextInt(data.length - 10);
                    java.util.Arrays.fill(data, at, Math.min(data.length, at + 1 + random.nextInt(64)), (byte) random.nextInt());
                    damaged.set(target, data);
                }
            }
            assertEquals(outcomes(damaged, 1), outcomes(damaged, 8), "round " + round + " damaging frame " + target);
        }
    }
}
