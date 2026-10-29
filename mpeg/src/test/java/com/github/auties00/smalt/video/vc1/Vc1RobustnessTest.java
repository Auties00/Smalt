package com.github.auties00.smalt.video.vc1;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Feeds the VC-1 decoder truncated and corrupted streams, synthetic ones and the first packets of conformance
 * streams with interlace field and frame pictures, and checks that it fails only with {@link MediaException}s, within
 * bounded time, and keeps decoding the packets after a corrupt one.
 */
@DisplayName("VC-1 robustness")
class Vc1RobustnessTest {
    private static final VideoDecoderOptions OPTIONS = VideoDecoderOptions.builder()
            .limits(MediaLimits.builder().maxDimension(4096).build())
            .build();

    /**
     * Decodes a stream, recording the media failures and failing on any other exception.
     *
     * @return the number of frames
     */
    private static int decodeTolerantly(Vc1TestStreams.Stream stream, String what) {
        var frames = new int[1];
        Vc1Decoder decoder;
        try {
            decoder = new Vc1Decoder(Vc1TestStreams.track(stream), OPTIONS);
        } catch (MediaException exception) {
            return 0;
        }
        try (decoder) {
            for (var packet : stream.packets()) {
                try {
                    decoder.decode(packet, frame -> frames[0]++);
                } catch (MediaException exception) {
                    // a corrupt packet is reported and the next one decodes
                } catch (RuntimeException exception) {
                    throw new AssertionError(what + ": " + exception, exception);
                }
            }
            try {
                decoder.flush(frame -> frames[0]++);
            } catch (MediaException exception) {
                // reported
            }
        }
        return frames[0];
    }

    private static Vc1TestStreams.Stream replace(Vc1TestStreams.Stream stream, int index, byte[] data) {
        var packets = new ArrayList<>(stream.packets());
        var old = packets.get(index);
        packets.set(index, new Packet(old.trackId(), data, old.pts(), old.dts(), old.duration(), old.keyframe()));
        return new Vc1TestStreams.Stream(stream.config(), stream.width(), stream.height(), List.copyOf(packets));
    }

    private static List<Vc1TestStreams.Stream> synthetic() {
        return List.of(
                Vc1TestStreams.elementary(Vc1Synthetic.advanced(21, 48, 32, "IPBSPB", true)),
                Vc1TestStreams.elementary(Vc1Synthetic.advanced(22, 34, 18, "IBPI", false)),
                Vc1Synthetic.simple(23, 32, 32, "IPSPI"));
    }

    @Test
    @DisplayName("decodes every truncation of every packet of the synthetic streams")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void truncations() {
        for (var stream : synthetic()) {
            for (var index = 0; index < stream.packets().size(); index++) {
                var data = stream.packets().get(index).data();
                for (var length = 0; length < data.length; length++) {
                    decodeTolerantly(replace(stream, index, Arrays.copyOf(data, length)),
                            "packet " + index + " cut to " + length);
                }
            }
        }
    }

    @Test
    @DisplayName("decodes the synthetic streams with random bit flips and still decodes the clean packets after")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void bitFlips() {
        var random = new Random(31);
        for (var stream : synthetic()) {
            var clean = decodeTolerantly(stream, "clean");
            assertTrue(clean > 0);
            for (var trial = 0; trial < 300; trial++) {
                var index = random.nextInt(stream.packets().size());
                var data = stream.packets().get(index).data().clone();
                if (data.length == 0) {
                    continue;
                }
                for (var flips = 1 + random.nextInt(4); flips > 0; flips--) {
                    var bit = random.nextInt(data.length * 8);
                    data[bit >> 3] ^= (byte) (1 << (bit & 7));
                }
                decodeTolerantly(replace(stream, index, data), "trial " + trial);
            }
        }
    }

    @Test
    @DisplayName("decodes a configuration that is garbage or truncated without other exceptions")
    void configurations() {
        var random = new Random(41);
        var stream = Vc1Synthetic.simple(24, 32, 32, "IP");
        for (var trial = 0; trial < 200; trial++) {
            var config = new byte[random.nextInt(48)];
            random.nextBytes(config);
            if (config.length > 4 && trial % 2 == 0) {
                config[0] = 0;
                config[1] = 0;
                config[2] = 1;
                config[3] = 0x0F;
            }
            var mutated = new Vc1TestStreams.Stream(config, 32, 32, stream.packets());
            decodeTolerantly(mutated, "configuration " + trial);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"vc1-fate-SA10143.vc1", "vc1-fate-ilaced_twomv.vc1", "vc1-fate-SMM0015.rcv"})
    @DisplayName("decodes conformance streams with corrupted packets")
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void corruptedConformance(String name) throws IOException {
        var bytes = Files.readAllBytes(Conformance.fetchListed(name));
        var stream = name.endsWith(".rcv") ? Vc1TestStreams.rcv(bytes) : Vc1TestStreams.elementary(bytes);
        var packets = stream.packets().subList(0, Math.min(8, stream.packets().size()));
        var head = new Vc1TestStreams.Stream(stream.config(), stream.width(), stream.height(), packets);
        var random = new Random(name.hashCode());
        for (var trial = 0; trial < 12; trial++) {
            var index = random.nextInt(packets.size());
            var data = packets.get(index).data().clone();
            if (trial % 3 == 0) {
                data = Arrays.copyOf(data, random.nextInt(data.length));
            } else {
                for (var flips = 0; flips < 8; flips++) {
                    var at = random.nextInt(data.length);
                    data[at] = (byte) random.nextInt(256);
                }
            }
            decodeTolerantly(replace(head, index, data), name + " trial " + trial);
        }
        if (decodeTolerantly(head, name) == 0) {
            fail(name + " produces no frame");
        }
    }
}
