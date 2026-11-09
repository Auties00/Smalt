package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.mpeg.MpegAudioDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks how the decoder groups Layer I frames for the multichannel extension, over the ISO/IEC 13818-4 test 22 stream,
 * whose channels {@link Mp2MultichannelConformanceTest} compares with their references: a stream joined at any frame, a
 * lost or damaged frame, where the extension frame comes, and plain Layer I streams, which must not change.
 */
class Layer1MultichannelTest {
    private static final int FRAME = 384;

    private static final Map<String, byte[]> FILES = Mp2MultichannelConformanceTest.files("22");

    private static List<Packet> packets() {
        return Mp2MultichannelConformanceTest.packets(FILES.get("test22.mpg"), FILES.get("test22.ext"));
    }

    private static com.github.auties00.smalt.track.AudioTrack track() {
        try (var demuxer = new MpegAudioDemuxer(MediaSource.of(FILES.get("test22.mpg")))) {
            return AudioPipeline.firstAudio(demuxer);
        }
    }

    private record Output(Map<Speaker, float[]> planes, List<ChannelLayout> layouts, List<Integer> sizes) {
    }

    private static Output decode(List<Packet> packets, Mp3DecoderOptions options) {
        var decoder = new Mp3Decoder(track(), options);
        var output = new LinkedHashMap<Speaker, AudioPipeline.FloatList>();
        var layouts = new ArrayList<ChannelLayout>();
        var sizes = new ArrayList<Integer>();
        Consumer<AudioBuffer> sink = buffer -> {
            if (!layouts.contains(buffer.layout())) {
                layouts.add(buffer.layout());
            }
            sizes.add(buffer.frames());
            for (var c = 0; c < buffer.channels(); c++) {
                var list = output.computeIfAbsent(buffer.layout().speakers().get(c),
                        ignored -> new AudioPipeline.FloatList());
                for (var i = 0; i < buffer.frames(); i++) {
                    list.add(buffer.plane(c)[i]);
                }
            }
        };
        for (var packet : packets) {
            decoder.decode(packet, sink);
        }
        decoder.flush(sink);
        var planes = new LinkedHashMap<Speaker, float[]>();
        output.forEach((speaker, list) -> planes.put(speaker, list.toArray()));
        return new Output(planes, layouts, sizes);
    }

    private static int total(List<Integer> sizes) {
        return sizes.stream().mapToInt(Integer::intValue).sum();
    }

    /** Asserts that the ends of two planes agree, once any filterbank history of an earlier difference is gone. */
    private static void assertSameEnd(String what, float[] expected, float[] actual, int samples) {
        for (var i = 1; i <= samples; i++) {
            assertEquals(expected[expected.length - i], actual[actual.length - i], 1e-5f, what + " sample -" + i);
        }
    }

    @Test
    @DisplayName("each group comes out at once as one buffer of 1152 samples in the five channel layout")
    void groups() {
        var output = decode(packets(), Mp3DecoderOptions.defaults());
        assertEquals(1, output.layouts().size());
        assertEquals(5, output.layouts().getFirst().channels());
        assertEquals(Set.of(1152), Set.copyOf(output.sizes()));
        assertEquals(20, output.sizes().size());
    }

    @Test
    @DisplayName("a stream joined within a group plays its frames as stereo until the next group starts")
    void joinedLate() {
        var full = decode(packets(), Mp3DecoderOptions.defaults());
        for (var skipped = 1; skipped <= 2; skipped++) {
            var output = decode(packets().subList(skipped, 60), Mp3DecoderOptions.defaults());
            assertEquals(List.of(ChannelLayout.STEREO, full.layouts().getFirst()), output.layouts());
            assertEquals((60 - skipped) * FRAME, total(output.sizes()));
            assertEquals(1152, output.sizes().getLast());
            assertEquals(3 - skipped, output.sizes().stream().filter(size -> size == FRAME).count());
            for (var speaker : full.planes().keySet()) {
                assertSameEnd(speaker.toString(), full.planes().get(speaker), output.planes().get(speaker), 1152);
            }
        }
    }

    @Test
    @DisplayName("after a lost frame the groups are found again, and the layout and the time line hold")
    void lostFrame() {
        var packets = new ArrayList<>(packets());
        packets.remove(31);
        var full = decode(packets(), Mp3DecoderOptions.defaults());
        var output = decode(packets, Mp3DecoderOptions.defaults());
        assertEquals(full.layouts(), output.layouts());
        assertEquals(59 * FRAME, total(output.sizes()));
        for (var speaker : full.planes().keySet()) {
            assertSameEnd(speaker.toString(), full.planes().get(speaker), output.planes().get(speaker), 1152);
        }
    }

    @Test
    @DisplayName("a packet flagged corrupt conceals its frame and those held with it, then the groups resume")
    void corruptPacket() {
        var packets = new ArrayList<>(packets());
        var corrupt = packets.get(31);
        packets.set(31, corrupt.withFlags(Set.of(PacketFlag.CORRUPT)));
        var full = decode(packets(), Mp3DecoderOptions.defaults());
        var output = decode(packets, Mp3DecoderOptions.defaults());
        assertEquals(full.layouts(), output.layouts());
        assertEquals(60 * FRAME, total(output.sizes()));
        for (var speaker : full.planes().keySet()) {
            assertSameEnd(speaker.toString(), full.planes().get(speaker), output.planes().get(speaker), 1152);
        }
    }

    @Test
    @DisplayName("concealment within a group keeps the layout and resumes the groups")
    void concealment() {
        var decoder = new Mp3Decoder(track());
        var packets = packets();
        var layouts = new ArrayList<ChannelLayout>();
        var counted = new int[1];
        Consumer<AudioBuffer> sink = buffer -> {
            counted[0] += buffer.frames();
            layouts.add(buffer.layout());
        };
        var fromConceal = 0;
        for (var i = 0; i < 30; i++) {
            decoder.decode(packets.get(i), sink);
        }
        decoder.decode(packets.get(30), sink);
        var before = counted[0];
        fromConceal = decoder.conceal(2 * FRAME, sink);
        assertTrue(fromConceal >= 2 * FRAME, "conceals the span: " + fromConceal);
        for (var i = 33; i < 60; i++) {
            decoder.decode(packets.get(i), sink);
        }
        decoder.flush(sink);
        assertTrue(counted[0] > before);
        assertEquals(Set.of(layouts.getFirst()), Set.copyOf(layouts), "the layout held");
    }

    @Test
    @DisplayName("an extension frame may come with any frame of its group")
    void extensionFrameAnywhere() {
        var base = Mp2MultichannelConformanceTest.packets(FILES.get("test22.mpg"), null);
        var frames = Mp2MultichannelConformanceTest.extensionFrames(FILES.get("test22.ext"));
        var moved = new ArrayList<Packet>();
        for (var i = 0; i < base.size(); i++) {
            var packet = base.get(i);
            if (i % 3 != 0) {
                moved.add(packet);
                continue;
            }
            var frame = frames.get(i / 3);
            var data = Arrays.copyOf(packet.data(), packet.size() + frame.length);
            System.arraycopy(frame, 0, data, packet.size(), frame.length);
            moved.add(packet.withData(data));
        }
        var full = decode(packets(), Mp3DecoderOptions.defaults());
        var output = decode(moved, Mp3DecoderOptions.defaults());
        for (var speaker : full.planes().keySet()) {
            assertArrayEquals(full.planes().get(speaker), output.planes().get(speaker), speaker.toString());
        }
    }

    @Test
    @DisplayName("without its extension frame an established stream plays the base channels, the others silent")
    void missingExtensionFrame() {
        var packets = new ArrayList<>(packets());
        var base = Mp2MultichannelConformanceTest.packets(FILES.get("test22.mpg"), null);
        for (var i = 30; i < 33; i++) {
            packets.set(i, base.get(i));
        }
        var output = decode(packets, Mp3DecoderOptions.defaults());
        assertEquals(1, output.layouts().size());
        assertEquals(60 * FRAME, total(output.sizes()));
    }

    @Test
    @DisplayName("the extension's ancillary data removed from the frames, a stream is plain Layer I: frame by frame, no flush")
    void plainWithoutAncillary() {
        var base = Mp2MultichannelConformanceTest.packets(FILES.get("test22.mpg"), null);
        var decoder = new Mp3Decoder(track());
        for (var packet : blanked(base, new SplittableRandom(1), false)) {
            var count = new int[1];
            decoder.decode(packet, buffer -> {
                count[0]++;
                assertEquals(FRAME, buffer.frames());
                assertEquals(ChannelLayout.STEREO, buffer.layout());
            });
            assertEquals(1, count[0], "the frame came out at once");
        }
    }

    @Test
    @DisplayName("ancillary noise in plain Layer I frames loses no frame and changes no sample")
    void plainWithNoise() {
        var base = Mp2MultichannelConformanceTest.packets(FILES.get("test22.mpg"), null);
        var noisy = blanked(base, new SplittableRandom(2), true);
        var on = decode(noisy, Mp3DecoderOptions.defaults());
        var off = decode(noisy, Mp3DecoderOptions.builder().multichannel(false).build());
        assertEquals(List.of(ChannelLayout.STEREO), on.layouts());
        assertEquals(60 * FRAME, total(on.sizes()));
        assertArrayEquals(off.planes().get(Speaker.FRONT_LEFT), on.planes().get(Speaker.FRONT_LEFT));
        assertArrayEquals(off.planes().get(Speaker.FRONT_RIGHT), on.planes().get(Speaker.FRONT_RIGHT));
    }

    /** Replaces every frame's bytes after its audio data with zeros or noise. */
    private static List<Packet> blanked(List<Packet> packets, SplittableRandom random, boolean noise) {
        var out = new ArrayList<Packet>();
        for (var packet : packets) {
            var data = packet.data().clone();
            var bs = new BitReader();
            bs.init(data, 4, data.length - 4);
            var allocation = new int[64];
            var bits = 0;
            for (var i = 0; i < 64; i++) {
                allocation[i] = bs.read(4);
            }
            for (var i = 0; i < 64; i++) {
                if (allocation[i] != 0) {
                    bits += 6 + 12 * (allocation[i] + 1);
                }
            }
            var end = bs.position + bits + 32;
            for (var i = end; i < data.length * 8; i++) {
                var bit = noise && random.nextBoolean() ? 1 : 0;
                var mask = 0x80 >>> (i & 7);
                data[i >> 3] = (byte) (bit == 1 ? data[i >> 3] | mask : data[i >> 3] & ~mask);
            }
            out.add(packet.withData(data));
        }
        return out;
    }

    @Test
    @DisplayName("a group whose frames have no audio at all still carries its extension across them")
    void silentGroup() {
        var output = new ArrayList<ChannelLayout>();
        var decoder = new Mp3Decoder(track());
        var frames = Mp2ProgramSeedWriter.layer1Group();
        for (var i = 0; i < frames.size(); i++) {
            decoder.decode(new Packet(1, frames.get(i), i * 384L, i * 384L, 384, true), buffer -> {
                output.add(buffer.layout());
                assertEquals(1152, buffer.frames());
            });
        }
        assertEquals(1, output.size());
        assertEquals(3, output.getFirst().channels());
    }

    @Test
    @DisplayName("a selected second stereo program comes out per group and keeps its format over frames without one")
    void secondProgram() {
        var frames = new ArrayList<>(Mp2ProgramSeedWriter.layer1Group(0, 3));
        var options = Mp3DecoderOptions.builder().program(Mp2Program.SECOND_STEREO).build();
        var decoder = new Mp3Decoder(track(), options);
        var sizes = new ArrayList<Integer>();
        Consumer<AudioBuffer> sink = buffer -> {
            sizes.add(buffer.frames());
            assertEquals(ChannelLayout.STEREO, buffer.layout());
        };
        for (var i = 0; i < 3; i++) {
            decoder.decode(new Packet(1, frames.get(i), i * 384L, i * 384L, 384, true), sink);
        }
        assertEquals(List.of(1152), sizes);
        var plain = new byte[384];
        System.arraycopy(frames.getFirst(), 0, plain, 0, 36);
        for (var i = 0; i < 6; i++) {
            decoder.decode(new Packet(1, plain, 0, 0, 384, true), sink);
        }
        assertEquals(List.of(1152, 1152, 1152), sizes, "six frames conceal two program frames");
    }

    @Test
    @DisplayName("with the option off the same stream decodes frame by frame to its Lo/Ro base")
    void optionOff() {
        var output = decode(Mp2MultichannelConformanceTest.packets(FILES.get("test22.mpg"), null),
                Mp3DecoderOptions.builder().multichannel(false).build());
        assertEquals(List.of(ChannelLayout.STEREO), output.layouts());
        assertEquals(Set.of(FRAME), Set.copyOf(output.sizes()));
        assertFalse(output.planes().containsKey(Speaker.FRONT_CENTER));
    }

    @Test
    @DisplayName("damaged groups only ever fail as media errors")
    void robustness() {
        var random = new SplittableRandom(3);
        var clean = packets();
        for (var round = 0; round < 200; round++) {
            var decoder = new Mp3Decoder(track(), Mp3DecoderOptions.builder().crcCheck(round % 2 == 0).build());
            for (var i = 0; i < 18; i++) {
                var data = clean.get(i).data().clone();
                for (var flips = random.nextInt(1 + round % 16); flips > 0; flips--) {
                    data[4 + random.nextInt(data.length - 4)] ^= (byte) (1 << random.nextInt(8));
                }
                if (round % 7 == 0) {
                    data = Arrays.copyOf(data, 8 + random.nextInt(data.length - 8));
                }
                try {
                    decoder.decode(clean.get(i).withData(data), buffer -> assertTrue(buffer.frames() > 0));
                } catch (MediaException expected) {
                    // a media error is the allowed failure
                }
            }
            decoder.flush(buffer -> assertTrue(buffer.frames() > 0));
        }
    }
}
