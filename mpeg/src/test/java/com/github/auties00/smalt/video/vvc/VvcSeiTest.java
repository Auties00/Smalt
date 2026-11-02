package com.github.auties00.smalt.video.vvc;

import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MasteringDisplay;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.internal.sei.SeiWriter;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.FramePacking;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the display SEI messages the VVC decoder reports or renders, injected as prefix SEI NAL units into the
 * VVenC test streams before the first NAL unit of a picture (its picture header, or its first slice when that holds
 * the picture header): the mastering display, content light level and alternative transfer characteristics in the
 * color description, the frame packing arrangement and display orientation with their persistence, the ATSC caption
 * data, the frame-field information, and film grain, whose expected samples come from the RDD 5 renderer applied to
 * the plain decode with each picture's order count (the low-delay stream outputs its pictures in decoding order, so
 * the order count of a frame is its index).
 */
@DisplayName("VVC SEI messages")
class VvcSeiTest {
    private static final List<List<SeiWriter.Interval>> GRAIN = List.of(
            List.of(new SeiWriter.Interval(0, 120, 60, 8, 6), new SeiWriter.Interval(121, 255, 100, 4, 10)),
            List.of(new SeiWriter.Interval(0, 127, 40, 6, 6), new SeiWriter.Interval(128, 255, 90, 12, 3)),
            List.of(new SeiWriter.Interval(0, 127, 80, 3, 12), new SeiWriter.Interval(128, 255, 30, 10, 10)));

    /**
     * Wraps SEI messages in a VVC prefix SEI NAL unit ({@code nal_unit_type} 23, layer 0, temporal id 0).
     */
    static byte[] sei(int type, byte[] payload) {
        var hevc = SeiWriter.nal(true, type, payload);
        var unit = hevc.clone();
        unit[0] = 0;
        unit[1] = (byte) ((23 << 3) | 1);
        return unit;
    }

    /**
     * Inserts NAL units before the first NAL unit of each picture, numbered in decoding order.
     */
    static byte[] insert(byte[] stream, IntFunction<List<byte[]>> inserts) {
        var out = new ByteArrayOutputStream();
        var picture = 0;
        for (var unit : VvcTestStreams.accessUnits(VvcTestStreams.nalUnits(stream))) {
            var inserted = false;
            for (var nal : unit) {
                var type = VvcTestStreams.type(nal);
                if (!inserted && (type == 19 || type <= 11)) {
                    for (var extra : inserts.apply(picture)) {
                        out.writeBytes(new byte[]{0, 0, 0, 1});
                        out.writeBytes(extra);
                    }
                    inserted = true;
                }
                out.writeBytes(new byte[]{0, 0, 0, 1});
                out.writeBytes(nal);
            }
            picture++;
        }
        return out.toByteArray();
    }

    /**
     * Decodes an Annex B stream one access unit per packet, keeping the frames and a copy of their samples.
     */
    static List<VideoFrame> decode(byte[] stream, VvcDecoderOptions options, List<byte[]> samples) {
        var frames = new ArrayList<VideoFrame>();
        try (var decoder = new VvcDecoder(VvcTestStreams.track(), options)) {
            Consumer<VideoFrame> sink = frame -> {
                // the decoder recycles its output picture, so the frames keep copies
                frames.add(VvcDecoderTest.copy(frame));
                samples.add(SeiWriter.raw(frame.picture()));
            };
            for (var unit : VvcTestStreams.accessUnits(VvcTestStreams.nalUnits(stream))) {
                var data = new ByteArrayOutputStream();
                for (var nal : unit) {
                    data.writeBytes(new byte[]{0, 0, 0, 1});
                    data.writeBytes(nal);
                }
                decoder.decode(VvcTestStreams.packet(data.toByteArray()), sink);
            }
            decoder.flush(sink);
        }
        return frames;
    }

    static com.github.auties00.smalt.image.ColorInfo color(VideoFrame frame) {
        return switch (frame.picture()) {
            case com.github.auties00.smalt.image.YuvPicture picture -> picture.color();
            case com.github.auties00.smalt.image.Yuv16Picture picture -> picture.color();
            default -> throw new AssertionError(frame.picture());
        };
    }

    static byte[] stream(String name) {
        return VvcDecoderTest.vector(name).stream();
    }

    @Nested
    @DisplayName("color")
    class Color {
        static byte[] masteringDisplay() {
            // green, blue, red and white chromaticities in units of 0.00002, then the luminances in 0.0001 cd/m2
            return new SeiWriter.Bits().u(16, 8500).u(16, 39850).u(16, 6550).u(16, 2300).u(16, 35400).u(16, 14600)
                    .u(16, 15635).u(16, 16450).u(32, 10_000_000).u(32, 50).bytes();
        }

        @Test
        @DisplayName("takes the mastering display and content light level of the last messages")
        void hdrMetadata() {
            var messages = sei(137, masteringDisplay());
            var light = sei(144, new SeiWriter.Bits().u(16, 1000).u(16, 400).bytes());
            var frames = decode(insert(stream("hdr-420-10bit"), picture -> picture == 0 ? List.of(messages, light)
                    : List.of()), VvcDecoderOptions.defaults(), new ArrayList<>());
            assertEquals(4, frames.size());
            var expected = new MasteringDisplay(new Chromaticity(35400 * 0.00002, 14600 * 0.00002),
                    new Chromaticity(8500 * 0.00002, 39850 * 0.00002), new Chromaticity(6550 * 0.00002, 2300 * 0.00002),
                    new Chromaticity(15635 * 0.00002, 16450 * 0.00002), 1000.0, 0.005);
            for (var frame : frames) {
                var color = color(frame);
                assertEquals(expected, color.masteringDisplay().orElseThrow());
                assertEquals(new ContentLightLevel(1000, 400), color.contentLightLevel().orElseThrow());
                assertEquals(TransferCharacteristics.PQ, color.transfer());
            }
            var plain = decode(stream("hdr-420-10bit"), VvcDecoderOptions.defaults(), new ArrayList<>());
            assertTrue(color(plain.getFirst()).masteringDisplay().isEmpty());
        }

        @Test
        @DisplayName("prefers the alternative transfer characteristics for the rest of the sequence")
        void alternativeTransfer() {
            // the low-delay stream outputs its pictures in decoding order
            var hlg = sei(147, new byte[]{18});
            var frames = decode(insert(stream("ld-420-8bit"), picture -> picture == 3 ? List.of(hlg) : List.of()),
                    VvcDecoderOptions.defaults(), new ArrayList<>());
            for (var i = 0; i < frames.size(); i++) {
                var transfer = color(frames.get(i)).transfer();
                if (i < 3) {
                    assertNotEquals(TransferCharacteristics.HLG, transfer, "frame " + i);
                } else {
                    assertEquals(TransferCharacteristics.HLG, transfer, "frame " + i);
                }
            }
        }

        @Test
        @DisplayName("ignores messages too short for their syntax")
        void truncated() {
            var shortMastering = sei(137, Arrays.copyOf(masteringDisplay(), 23));
            var shortLight = sei(144, new byte[]{1, 2, 3});
            var frames = decode(insert(stream("hdr-420-10bit"), picture -> List.of(shortMastering, shortLight)),
                    VvcDecoderOptions.defaults(), new ArrayList<>());
            for (var frame : frames) {
                assertTrue(color(frame).masteringDisplay().isEmpty());
                assertTrue(color(frame).contentLightLevel().isEmpty());
            }
        }
    }

    @Nested
    @DisplayName("display information")
    class Display {
        static byte[] orientation(int type, boolean persistence) {
            return sei(47, new SeiWriter.Bits().flag(false).flag(persistence).u(3, type).u(3, 0).bytes());
        }

        @ParameterizedTest(name = "transform type {0}")
        @CsvSource({"0,NORMAL", "1,FLIP_HORIZONTAL", "2,ROTATE_180", "3,FLIP_VERTICAL", "4,TRANSVERSE",
                "5,ROTATE_270", "6,TRANSPOSE", "7,ROTATE_90"})
        @DisplayName("maps each display_orientation_transform_type, rotation before mirroring")
        void orientations(int type, Orientation expected) {
            var frames = decode(insert(stream("ld-420-8bit"), picture -> picture == 0 ? List.of(orientation(type,
                    false)) : List.of()), VvcDecoderOptions.defaults(), new ArrayList<>());
            assertEquals(expected, frames.getFirst().sideData().orientation().orElseThrow());
            assertTrue(frames.get(1).sideData().orientation().isEmpty(), "a message without persistence applies once");
        }

        @Test
        @DisplayName("frame packing and display orientation persist until cancelled or replaced")
        void persistence() {
            var packing = sei(45, SeiWriter.framePacking(true, 2, 3, false, 1, true, false, false, false, true, true,
                    new int[]{4, 8, 4, 8}, 1, true));
            var cancel = sei(47, new SeiWriter.Bits().flag(true).bytes());
            IntFunction<List<byte[]>> messages = picture -> switch (picture) {
                case 1 -> List.of(packing, orientation(7, true));
                case 4 -> List.of(cancel);
                case 6 -> List.of(orientation(2, false));
                default -> List.of();
            };
            var frames = decode(insert(stream("ld-420-8bit"), messages), VvcDecoderOptions.defaults(),
                    new ArrayList<>());
            assertEquals(10, frames.size());
            assertTrue(frames.getFirst().sideData().isEmpty());
            var expected = new FramePacking(FramePacking.Arrangement.SIDE_BY_SIDE,
                    FramePacking.Interpretation.FRAME0_LEFT, false, true, false, false, false, true, true, 4, 8, 4, 8,
                    true);
            for (var i = 1; i < frames.size(); i++) {
                var sideData = frames.get(i).sideData();
                assertEquals(expected, sideData.framePacking().orElseThrow(), "frame " + i);
                var orientation = i < 4 ? Orientation.ROTATE_90 : i == 6 ? Orientation.ROTATE_180 : null;
                assertEquals(orientation, sideData.orientation().orElse(null), "frame " + i);
            }
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"progressive,false,false,0,PROGRESSIVE,0", "doubled,false,false,1,PROGRESSIVE,2",
                "tripled,false,false,2,PROGRESSIVE,4", "top first,true,true,1,TOP_FIRST,0",
                "bottom first,true,false,1,BOTTOM_FIRST,0", "top first repeated,true,true,2,TOP_FIRST,1",
                "bottom first repeated,true,false,2,BOTTOM_FIRST,1"})
        @DisplayName("frame pictures report the scan and repetition of their frame-field information")
        void frameFieldInfo(String name, boolean fromFrame, boolean topFirst, int periodsMinus1, FieldOrder order,
                            int repeated) {
            var bits = new SeiWriter.Bits().flag(false).flag(fromFrame);
            if (fromFrame) {
                bits.flag(topFirst);
            }
            var message = sei(168, bits.u(8, periodsMinus1).u(2, 1).flag(false).bytes());
            var frames = decode(insert(stream("ld-420-8bit"), picture -> picture == 2 ? List.of(message) : List.of()),
                    VvcDecoderOptions.defaults(), new ArrayList<>());
            assertEquals(order, frames.get(2).sideData().fieldOrder().orElseThrow());
            assertEquals(repeated, frames.get(2).sideData().repeatedFields());
            assertTrue(frames.get(3).sideData().fieldOrder().isEmpty(), "the message describes one picture");
        }

        @Test
        @DisplayName("hands out the cc_data of ATSC A/53 caption messages")
        void captions() {
            var payload = new byte[]{(byte) 0xB5, 0x00, 0x31, 'G', 'A', '9', '4', 0x03, (byte) 0xC2, (byte) 0xFF,
                    (byte) 0xFC, (byte) 0x94, 0x20, (byte) 0xFC, (byte) 0x94, (byte) 0xAE, (byte) 0xFF};
            var other = new byte[]{(byte) 0xB5, 0x00, 0x2F, 1, 2, 3};
            var frames = decode(insert(stream("ld-420-8bit"), picture -> picture == 3 ? List.of(sei(4, payload),
                    sei(4, other)) : List.of()), VvcDecoderOptions.defaults(), new ArrayList<>());
            assertArrayEquals(new byte[]{(byte) 0xFC, (byte) 0x94, 0x20, (byte) 0xFC, (byte) 0x94, (byte) 0xAE},
                    frames.get(3).sideData().closedCaptions().orElseThrow());
            assertTrue(frames.get(2).sideData().closedCaptions().isEmpty());
            assertTrue(frames.get(4).sideData().closedCaptions().isEmpty());
        }
    }

    @Nested
    @DisplayName("film grain")
    class FilmGrain {
        static byte[] grain(int persistence) {
            return sei(19, SeiWriter.filmGrain(true, 3, persistence, GRAIN));
        }

        @ParameterizedTest(name = "{0} threads")
        @ValueSource(ints = {1, 4})
        @DisplayName("renders the RDD 5 grain of each picture with its picture order count")
        void rendered(int threads) {
            var clean = stream("ld-420-8bit");
            var params = SeiWriter.parseFilmGrain(SeiWriter.filmGrain(true, 3, 0, GRAIN), true);
            var plain = decode(clean, VvcDecoderOptions.builder().filmGrain(false).build(), new ArrayList<>());
            var samples = new ArrayList<byte[]>();
            decode(insert(clean, picture -> List.of(grain(0))), VvcDecoderOptions.builder().threads(threads).build(),
                    samples);
            assertEquals(plain.size(), samples.size());
            for (var poc = 0; poc < plain.size(); poc++) {
                assertArrayEquals(SeiWriter.grained(plain.get(poc).picture(), params, poc), samples.get(poc),
                        "frame " + poc);
            }
        }

        @Test
        @DisplayName("renders 10-bit pictures at their depth")
        void deep() {
            var clean = stream("ra-420-10bit");
            var params = SeiWriter.parseFilmGrain(SeiWriter.filmGrain(true, 3, 0, GRAIN), true);
            var options = VvcDecoderOptions.builder().highBitDepth(true).build();
            var plain = decode(clean, options.toBuilder().filmGrain(false).build(), new ArrayList<>());
            var samples = new ArrayList<byte[]>();
            decode(insert(clean, picture -> List.of(grain(0))), options, samples);
            assertEquals(12, samples.size());
            for (var poc = 0; poc < plain.size(); poc++) {
                // the single IDR picture starts the order counts at 0, so a frame's order count is its output index
                assertArrayEquals(SeiWriter.grained(plain.get(poc).picture(), params, poc), samples.get(poc),
                        "frame " + poc);
            }
        }

        @Test
        @DisplayName("a persisting message lasts until a cancel")
        void persistence() {
            var clean = stream("ld-420-8bit");
            IntFunction<List<byte[]>> messages = picture -> switch (picture) {
                case 0 -> List.of(grain(1));
                case 5 -> List.of(sei(19, SeiWriter.filmGrainCancel()));
                default -> List.of();
            };
            var plainSamples = new ArrayList<byte[]>();
            decode(clean, VvcDecoderOptions.defaults(), plainSamples);
            var everySamples = new ArrayList<byte[]>();
            decode(insert(clean, picture -> picture < 5 ? List.of(grain(0)) : List.of()), VvcDecoderOptions.defaults(),
                    everySamples);
            var samples = new ArrayList<byte[]>();
            decode(insert(clean, messages), VvcDecoderOptions.defaults(), samples);
            for (var i = 0; i < samples.size(); i++) {
                assertArrayEquals(i < 5 ? everySamples.get(i) : plainSamples.get(i), samples.get(i), "frame " + i);
            }
        }

        @Test
        @DisplayName("is left out when the option is clear, and decoded picture hashes check the plain samples")
        void disabled() {
            var clean = stream("ra-420-10bit");
            var stream = insert(clean, picture -> List.of(grain(0)));
            var plain = new ArrayList<byte[]>();
            decode(clean, VvcDecoderOptions.defaults(), plain);
            var off = new ArrayList<byte[]>();
            decode(stream, VvcDecoderOptions.builder().filmGrain(false).hashVerification(true).build(), off);
            assertEquals(plain.size(), off.size());
            for (var i = 0; i < plain.size(); i++) {
                assertArrayEquals(plain.get(i), off.get(i));
            }
            var on = new ArrayList<byte[]>();
            decode(stream, VvcDecoderOptions.builder().hashVerification(true).build(), on);
            assertFalse(Arrays.equals(plain.getFirst(), on.getFirst()));
        }

        @Test
        @DisplayName("leaves the samples of a multiplicative blending message unchanged")
        void unsupportedBlending() {
            var clean = stream("ld-420-8bit");
            // blending_mode_id 1 (multiplicative) is the third and fourth bits after the cancel flag, model id and
            // colour description flag
            var payload = SeiWriter.filmGrain(true, 3, 0, GRAIN);
            payload[0] |= 0x04;
            var plain = new ArrayList<byte[]>();
            decode(clean, VvcDecoderOptions.defaults(), plain);
            var samples = new ArrayList<byte[]>();
            decode(insert(clean, picture -> List.of(sei(19, payload))), VvcDecoderOptions.defaults(), samples);
            for (var i = 0; i < plain.size(); i++) {
                assertArrayEquals(plain.get(i), samples.get(i));
            }
        }

        @Test
        @DisplayName("the option is on by default and survives toBuilder, equals, hashCode and toString")
        void option() {
            var defaults = VvcDecoderOptions.defaults();
            assertTrue(defaults.filmGrain());
            var off = defaults.toBuilder().filmGrain(false).build();
            assertFalse(off.filmGrain());
            assertEquals(off, off.toBuilder().build());
            assertEquals(off.hashCode(), off.toBuilder().build().hashCode());
            assertNotEquals(defaults, off);
            assertTrue(off.toString().contains("filmGrain=false"), off.toString());
        }
    }
}
