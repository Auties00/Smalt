package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feeds damaged scalable streams and streams with auxiliary pictures to a decoder that decodes every layer, view,
 * depth view and alpha plane: bytes flipped anywhere (NAL unit headers and their extensions, prefix NAL units,
 * subset sequence parameter sets and auxiliary slices included) and NAL unit types swapped among the types of the
 * extensions, so that the data of one extension reaches the parser of another. The decoder may only report
 * {@link MediaException}s. A last test nests aggregators of a length-prefixed track deeper than any stack.
 */
@DisplayName("H.264 layered streams under damage")
class H264LayersRobustnessTest {
    /** The NAL unit types a swap picks from: slices, parameter sets and every extension type. */
    private static final int[] TYPES = {1, 5, 7, 8, 13, 14, 15, 19, 20, 21, 30};

    static Stream<Arguments> streams() {
        var streams = new ArrayList<Arguments>();
        for (var vector : H264SvcStreamsTest.vectors()) {
            streams.add(Arguments.of(vector.name(), vector.bytes()));
        }
        for (var name : List.of("aux9-cabac-slices", "aux8-cavlc-fields-inverted")) {
            streams.add(Arguments.of(name, H264AuxiliaryAlphaTest.resource(name + ".264")));
        }
        return streams.stream();
    }

    static H264DecoderOptions options(int variant) {
        var builder = H264DecoderOptions.builder().highestLayer().allViews().depth(true).enhancedResolution(true);
        return switch (variant) {
            case 0 -> builder.build();
            case 1 -> builder.realtime(true).build();
            default -> builder.threads(4).maxTemporalId(1).build();
        };
    }

    /** Decodes NAL units one per packet, allowing the decoder to reject any of them as malformed or unsupported. */
    static int decode(List<byte[]> nals, H264DecoderOptions options) {
        var frames = new int[1];
        Consumer<VideoFrame> sink = frame -> {
            assertTrue(frame.picture().width() > 0 && frame.picture().height() > 0);
            frames[0]++;
        };
        try (var decoder = new H264Decoder(H264ConformanceTest.track(), options)) {
            for (var nal : nals) {
                try {
                    decoder.decode(new Packet(1, H264TestStreams.annexB(List.of(nal)), Packet.NO_TIMESTAMP,
                            Packet.NO_TIMESTAMP, 0, false), sink);
                } catch (MediaException expected) {
                    // a damaged unit is reported, decoding continues
                }
            }
            try {
                decoder.flush(sink);
            } catch (MediaException expected) {
                // as above
            }
        }
        return frames[0];
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streams")
    @DisplayName("reports flipped bytes anywhere in the stream as media failures")
    void flippedBytes(String name, byte[] stream) {
        var random = new SplittableRandom(name.hashCode());
        var nals = H264TestStreams.nalUnits(stream);
        for (var round = 0; round < 45; round++) {
            var damaged = new ArrayList<byte[]>();
            for (var nal : nals) {
                var copy = nal.clone();
                if (random.nextInt(4) == 0) {
                    for (var flips = 1 + random.nextInt(3); flips > 0; flips--) {
                        // favor the header and its extension, where the layer and view identifiers sit
                        var position = random.nextBoolean() ? random.nextInt(Math.min(copy.length, 6))
                                : random.nextInt(copy.length);
                        copy[position] ^= (byte) (1 << random.nextInt(8));
                    }
                }
                damaged.add(copy);
            }
            decode(damaged, options(round % 3));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streams")
    @DisplayName("reports NAL units carrying the type of another extension as media failures")
    void swappedTypes(String name, byte[] stream) {
        var random = new SplittableRandom(~name.hashCode());
        var nals = H264TestStreams.nalUnits(stream);
        for (var round = 0; round < 30; round++) {
            var damaged = new ArrayList<byte[]>();
            for (var nal : nals) {
                var copy = nal.clone();
                if (random.nextInt(5) == 0) {
                    copy[0] = (byte) ((copy[0] & 0xE0) | TYPES[random.nextInt(TYPES.length)]);
                }
                damaged.add(copy);
            }
            decode(damaged, options(round % 3));
        }
    }

    @Test
    @DisplayName("skips aggregators nested in aggregators, however deep, and decodes the units of the outer one")
    void nestedAggregators() {
        var vector = H264SvcStreamsTest.vector("svc-spatial");
        var nals = H264TestStreams.nalUnits(vector.bytes());
        var track = H264TestStreams.track(Optional.of(H264TestStreams.avcC(nals, 4, false)), Optional.empty());
        // a chain of 200000 aggregators, each holding the next, is a packet of about 1.6 MB
        var levels = 200_000;
        var nested = new byte[8 * levels - 4 + 2];
        for (var level = 0; level < levels; level++) {
            var at = 8 * level;
            nested[at] = 0x7E;
            nested[at + 1] = (byte) 0x80;
            if (level + 1 < levels) {
                var inner = nested.length - at - 8;
                nested[at + 4] = (byte) (inner >>> 24);
                nested[at + 5] = (byte) (inner >>> 16);
                nested[at + 6] = (byte) (inner >>> 8);
                nested[at + 7] = (byte) inner;
            }
        }
        // the innermost aggregator ends with two bytes too short for a unit
        nested[nested.length - 2] = 0x09;
        nested[nested.length - 1] = (byte) 0xF0;
        var options = H264DecoderOptions.builder().highestLayer().build();
        var plain = H264SvcStreamsTest.decode(vector.bytes(), options).count();
        var frames = new int[1];
        try (var decoder = new H264Decoder(track, options)) {
            var first = true;
            for (var unit : H264TestStreams.accessUnits(nals)) {
                var payload = first ? concat(H264TestStreams.lengthPrefixed(List.of(nested), 4),
                        H264TestStreams.lengthPrefixed(List.of(aggregator(unit)), 4))
                        : H264TestStreams.lengthPrefixed(List.of(aggregator(unit)), 4);
                first = false;
                decoder.decode(new Packet(1, payload, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, false),
                        frame -> frames[0]++);
            }
            decoder.flush(frame -> frames[0]++);
        }
        assertEquals(plain, frames[0], "the units of the outer aggregators decode as the plain stream does");
    }

    @Test
    @DisplayName("rejects an aggregator whose unit runs past its end")
    void oversizedAggregatedUnit() {
        var nals = H264TestStreams.nalUnits(H264SvcStreamsTest.vector("svc-spatial").bytes());
        var track = H264TestStreams.track(Optional.of(H264TestStreams.avcC(nals, 4, false)), Optional.empty());
        var aggregator = aggregator(List.of(nals.getFirst()));
        // the length of the held unit, after the four-byte aggregator header, claims one byte more than there is
        var length = aggregator.length - 8;
        aggregator[7] = (byte) (length + 1);
        aggregator[6] = (byte) ((length + 1) >>> 8);
        try (var decoder = new H264Decoder(track, H264DecoderOptions.defaults())) {
            var packet = new Packet(1, H264TestStreams.lengthPrefixed(List.of(aggregator), 4), Packet.NO_TIMESTAMP,
                    Packet.NO_TIMESTAMP, 0, false);
            try {
                decoder.decode(packet, frame -> {
                });
            } catch (MediaException.Malformed expected) {
                return;
            }
        }
        throw new AssertionError("the oversized unit was accepted");
    }

    /** Wraps NAL units in an aggregator: an SVC NAL unit header of type 30 and the length-prefixed units. */
    static byte[] aggregator(List<byte[]> units) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{0x7E, (byte) 0x80, 0, 0});
        out.writeBytes(H264TestStreams.lengthPrefixed(units, 4));
        return out.toByteArray();
    }

    static byte[] concat(byte[] first, byte[] second) {
        var out = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }
}
