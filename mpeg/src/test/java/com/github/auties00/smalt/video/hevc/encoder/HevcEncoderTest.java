package com.github.auties00.smalt.video.hevc.encoder;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MasteringDisplay;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.nal.NalRecords;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.H264Decoder;
import com.github.auties00.smalt.video.h264.encoder.H264Encoder;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderOptions;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderSpeed;
import com.github.auties00.smalt.video.h264.encoder.H264RateControl;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the HEVC encoder end to end: every stream carries MD5 decoded picture hashes of the encoder's own
 * reconstruction, which Smalt's decoder verifies picture by picture (a reconstruction that drifted from the
 * normative decoding process fails the hash), ffmpeg's decoder verifies them again and must output exactly what
 * Smalt's decoder outputs, and the decoded pictures must stay close to the source.
 */
@DisplayName("HEVC encoder")
class HevcEncoderTest {
    private static HevcEncoderOptions.Builder quality(int qp) {
        return HevcEncoderOptions.builder().rateControl(HevcRateControl.CONSTANT_QUALITY).quality(qp)
                .pictureHash(HevcPictureHash.MD5);
    }

    @Nested
    @DisplayName("reconstruction")
    class Reconstruction {
        static Stream<Arguments> matrix() {
            var arguments = new ArrayList<Arguments>();
            for (var speed : HevcEncoderSpeed.values()) {
                arguments.add(Arguments.of(speed, 176, 144, 6, 3, 30, HevcPacketFormat.LENGTH_PREFIXED));
                arguments.add(Arguments.of(speed, 64, 64, 5, 0, 24, HevcPacketFormat.ANNEX_B));
            }
            arguments.add(Arguments.of(HevcEncoderSpeed.BALANCED, 1, 1, 3, 1, 30, HevcPacketFormat.ANNEX_B));
            arguments.add(Arguments.of(HevcEncoderSpeed.BALANCED, 33, 17, 4, 2, 30, HevcPacketFormat.LENGTH_PREFIXED));
            arguments.add(Arguments.of(HevcEncoderSpeed.FAST, 130, 2, 3, 1, 30, HevcPacketFormat.ANNEX_B));
            arguments.add(Arguments.of(HevcEncoderSpeed.FAST, 2, 130, 3, 1, 30, HevcPacketFormat.ANNEX_B));
            arguments.add(Arguments.of(HevcEncoderSpeed.FAST, 200, 136, 9, 7, 34, HevcPacketFormat.LENGTH_PREFIXED));
            arguments.add(Arguments.of(HevcEncoderSpeed.FASTEST, 96, 64, 4, 0, 0, HevcPacketFormat.ANNEX_B));
            arguments.add(Arguments.of(HevcEncoderSpeed.FASTEST, 96, 64, 4, 0, 51, HevcPacketFormat.ANNEX_B));
            return arguments.stream();
        }

        @ParameterizedTest(name = "{0} {1}x{2}x{3} b={4} q={5} {6}")
        @MethodSource("matrix")
        @DisplayName("decodes with every picture hash verified, as ffmpeg decodes it, close to the source")
        void verified(HevcEncoderSpeed speed, int width, int height, int frames, int bFrames, int qp,
                      HevcPacketFormat format) {
            var options = quality(qp).speed(speed).bFrames(bFrames).packetFormat(format).build();
            var pictures = HevcEncoderHarness.sequence(width, height, frames, width * 31L + height);
            var encoded = HevcEncoderHarness.encode(width, height, options, pictures);
            assertEquals(frames, encoded.packets().size(), "one packet per frame");
            var decoded = HevcEncoderHarness.decode(encoded);
            assertEquals(frames, decoded.size(), "decoded frames");
            var floor = qp >= 45 ? 15 : qp <= 10 ? 45 : 28;
            for (var i = 0; i < frames; i++) {
                assertEquals(i * 3000L, decoded.get(i).pts(), "output order");
                var psnr = HevcEncoderHarness.psnr(decoded.get(i).picture(), pictures.get(i));
                assertTrue(psnr > floor, "frame " + i + " PSNR " + psnr);
            }
            var name = "recon-" + speed + "-" + width + "x" + height + "-b" + bFrames + "-q" + qp + "-" + format;
            var reference = HevcEncoderHarness.ffmpeg(name, encoded.annexB(), "yuv420p");
            assertArrayEquals(HevcEncoderHarness.raw(decoded), reference, name + ": ffmpeg's decode differs");
        }

        @ParameterizedTest(name = "{0} at {1} bits, quantizer {2}")
        @MethodSource("extremes")
        @DisplayName("noise, flat and full-swing pictures at the ends of the quantizer range decode with every hash verified")
        void extremes(String content, int depth, int qp) {
            var width = 72;
            var height = 40;
            var max = (1 << depth) - 1;
            var random = new java.util.Random(content.hashCode() * 31L + depth * 7L + qp);
            var pictures = new ArrayList<com.github.auties00.smalt.image.Picture>();
            for (var f = 0; f < 3; f++) {
                var luma = new int[width * height];
                var chroma = new int[2 * (width / 2) * (height / 2)];
                for (var i = 0; i < luma.length; i++) {
                    var x = i % width;
                    var y = i / width;
                    luma[i] = switch (content) {
                        case "noise" -> random.nextInt(max + 1);
                        case "flat" -> f == 0 ? 0 : f == 1 ? max : max / 2;
                        default -> ((x / 4 + y / 4 + f) & 1) == 0 ? 0 : max;
                    };
                }
                for (var i = 0; i < chroma.length; i++) {
                    chroma[i] = content.equals("noise") ? random.nextInt(max + 1) : content.equals("flat") ? max / 2
                            : (i & 1) == 0 ? 0 : max;
                }
                pictures.add(HevcEncoderHarness.planar(width, height, depth, luma, chroma));
            }
            var options = quality(qp).bFrames(1).speed(HevcEncoderSpeed.SLOW).packetFormat(HevcPacketFormat.ANNEX_B);
            if (depth == 10) {
                options.profile(HevcProfile.MAIN_10).bitDepth(10);
            }
            var encoded = HevcEncoderHarness.encode(width, height, options.build(), pictures);
            var decoded = HevcEncoderHarness.decode(encoded);
            assertEquals(3, decoded.size());
            if (qp == 0) {
                for (var i = 0; i < 3; i++) {
                    var psnr = HevcEncoderHarness.psnr(decoded.get(i).picture(), pictures.get(i));
                    assertTrue(psnr > 40, content + " frame " + i + " PSNR " + psnr);
                }
            }
            var name = "extreme-" + content + "-" + depth + "-q" + qp;
            var reference = HevcEncoderHarness.ffmpeg(name, encoded.annexB(), depth == 8 ? "yuv420p" : "yuv420p10le");
            assertArrayEquals(HevcEncoderHarness.raw(decoded), reference, name + ": ffmpeg's decode differs");
        }

        static Stream<Arguments> extremes() {
            var arguments = new ArrayList<Arguments>();
            for (var content : List.of("noise", "flat", "checker")) {
                for (var depth : new int[]{8, 10}) {
                    for (var qp : new int[]{0, 51}) {
                        arguments.add(Arguments.of(content, depth, qp));
                    }
                }
            }
            return arguments.stream();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = HevcPictureHash.class, names = {"MD5", "CRC", "CHECKSUM"})
        @DisplayName("writes every kind of decoded picture hash, which the decoders verify")
        void hashes(HevcPictureHash hash) {
            var options = quality(32).pictureHash(hash).packetFormat(HevcPacketFormat.ANNEX_B).build();
            var pictures = HevcEncoderHarness.sequence(80, 72, 5, 3);
            var encoded = HevcEncoderHarness.encode(80, 72, options, pictures);
            assertEquals(5, HevcEncoderHarness.decode(encoded).size());
            HevcEncoderHarness.ffmpeg("hash-" + hash, encoded.annexB(), "yuv420p");
        }

        @Test
        @DisplayName("a decoded picture hash altered by one bit is reported as a mismatch")
        void alteredHash() {
            var options = quality(32).packetFormat(HevcPacketFormat.ANNEX_B).bFrames(0).build();
            var encoded = HevcEncoderHarness.encode(64, 64, options, HevcEncoderHarness.sequence(64, 64, 2, 9));
            var packets = new ArrayList<>(encoded.packets());
            var data = packets.get(1).data().clone();
            data[data.length - 3] ^= 0x04;
            packets.set(1, packets.get(1).withData(data));
            var altered = new HevcEncoderHarness.Encoded(packets, null, encoded.options());
            var exception = assertThrows(MediaException.Malformed.class, () -> HevcEncoderHarness.decode(altered));
            assertTrue(exception.getMessage().contains("hash"), exception.getMessage());
        }

        @Test
        @DisplayName("the same input gives the same stream")
        void deterministic() {
            var options = quality(30).build();
            var pictures = HevcEncoderHarness.sequence(144, 80, 6, 5);
            var first = HevcEncoderHarness.encode(144, 80, options, pictures);
            var second = HevcEncoderHarness.encode(144, 80, options, pictures);
            for (var i = 0; i < first.packets().size(); i++) {
                assertArrayEquals(first.packets().get(i).data(), second.packets().get(i).data(), "packet " + i);
            }
        }

        @Test
        @DisplayName("every thread count gives the same stream")
        void threads() {
            var pictures = HevcEncoderHarness.sequence(320, 200, 5, 11);
            var single = HevcEncoderHarness.encode(320, 200, quality(30).threads(1).build(), pictures);
            var multi = HevcEncoderHarness.encode(320, 200, quality(30).threads(4).build(), pictures);
            for (var i = 0; i < single.packets().size(); i++) {
                assertArrayEquals(single.packets().get(i).data(), multi.packets().get(i).data(), "packet " + i);
            }
        }

        @Test
        @DisplayName("a finer quantizer gives a larger stream of higher quality")
        void quantizerMonotonic() {
            var pictures = HevcEncoderHarness.sequence(128, 96, 4, 13);
            var fine = HevcEncoderHarness.encode(128, 96, quality(22).build(), pictures);
            var coarse = HevcEncoderHarness.encode(128, 96, quality(38).build(), pictures);
            assertTrue(fine.bytes() > coarse.bytes() * 2, fine.bytes() + " vs " + coarse.bytes());
            var fineFrames = HevcEncoderHarness.decode(fine);
            var coarseFrames = HevcEncoderHarness.decode(coarse);
            for (var i = 0; i < 4; i++) {
                assertTrue(HevcEncoderHarness.psnr(fineFrames.get(i).picture(), pictures.get(i))
                        > HevcEncoderHarness.psnr(coarseFrames.get(i).picture(), pictures.get(i)) + 3, "frame " + i);
            }
        }
    }

    @Nested
    @DisplayName("profiles")
    class Profiles {
        @Test
        @DisplayName("Main 10 codes 10-bit pictures, verified by both decoders")
        void main10() {
            var options = quality(30).profile(HevcProfile.MAIN_10).bitDepth(10).packetFormat(HevcPacketFormat.ANNEX_B)
                    .build();
            var pictures = HevcEncoderHarness.sequence(120, 72, 5, 21, 10);
            var encoded = HevcEncoderHarness.encode(120, 72, options, pictures);
            var decoded = HevcEncoderHarness.decode(encoded);
            assertEquals(5, decoded.size());
            for (var i = 0; i < 5; i++) {
                var picture = assertInstance(decoded.get(i).picture());
                assertEquals(10, picture.bitDepth());
                assertTrue(HevcEncoderHarness.psnr(picture, pictures.get(i)) > 28, "frame " + i);
            }
            var reference = HevcEncoderHarness.ffmpeg("main10", encoded.annexB(), "yuv420p10le");
            assertArrayEquals(HevcEncoderHarness.raw(decoded), reference);
            assertEquals(2, sps(encoded)[3] & 0x1F, "general_profile_idc");
        }

        private static Yuv16Picture assertInstance(com.github.auties00.smalt.image.Picture picture) {
            assertTrue(picture instanceof Yuv16Picture, "10-bit output: " + picture);
            return (Yuv16Picture) picture;
        }

        @Test
        @DisplayName("Main 10 takes 8-bit input and codes it at 10 bits")
        void main10From8Bit() {
            var options = quality(30).profile(HevcProfile.MAIN_10).bitDepth(10).build();
            var pictures = HevcEncoderHarness.sequence(64, 64, 2, 23);
            var decoded = HevcEncoderHarness.decode(HevcEncoderHarness.encode(64, 64, options, pictures));
            var picture = (Yuv16Picture) decoded.getFirst().picture();
            var source = (YuvPicture) pictures.getFirst();
            var sse = 0.0;
            for (var y = 0; y < 64; y++) {
                for (var x = 0; x < 64; x++) {
                    var e = ((source.y()[y * source.yStride() + x] & 0xFF) << 2) - picture.y()[y * picture.yStride() + x];
                    sse += e * e;
                }
            }
            assertTrue(sse / 4096 < 200, "mean squared error " + sse / 4096);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = HevcProfile.class, names = {"MAIN_STILL_PICTURE", "MAIN_10_STILL_PICTURE"})
        @DisplayName("a still picture profile codes one intra picture and refuses a second")
        void still(HevcProfile profile) {
            var options = HevcEncoderOptions.forStillPicture().toBuilder().profile(profile)
                    .pictureHash(HevcPictureHash.MD5).packetFormat(HevcPacketFormat.ANNEX_B).build();
            var pictures = HevcEncoderHarness.sequence(100, 60, 1, 31);
            var packets = new ArrayList<Packet>();
            try (var encoder = new HevcEncoder(100, 60, options)) {
                encoder.encode(new VideoFrame(pictures.getFirst(), 0, 0, false), packets::add);
                assertThrows(IllegalStateException.class,
                        () -> encoder.encode(new VideoFrame(pictures.getFirst(), 1, 0, false), packets::add));
                encoder.flush(packets::add);
            }
            assertEquals(1, packets.size());
            assertTrue(packets.getFirst().keyframe());
            var encoded = new HevcEncoderHarness.Encoded(packets, null, options);
            assertEquals(1, HevcEncoderHarness.decode(encoded).size());
            HevcEncoderHarness.ffmpeg("still-" + profile, encoded.annexB(), "yuv420p");
            var sps = annexBUnit(packets.getFirst().data(), 33);
            assertEquals(profile.profileIdc(), sps[3] & 0x1F, "general_profile_idc");
        }
    }

    @Nested
    @DisplayName("stream structure")
    class Structure {
        @Test
        @DisplayName("the configuration record holds the parameter sets and the packets are length-prefixed")
        void lengthPrefixed() {
            var options = quality(30).build();
            var encoded = HevcEncoderHarness.encode(96, 64, options, HevcEncoderHarness.sequence(96, 64, 5, 41));
            var parsed = NalRecords.parseHevcStrict(encoded.config());
            assertEquals(4, parsed.lengthSize());
            assertEquals(List.of(32, 33, 34), parsed.parameterSets().stream().map(unit -> (unit[0] >> 1) & 0x3F)
                    .toList());
            assertEquals(1, encoded.config()[0]);
            for (var packet : encoded.packets()) {
                var data = packet.data();
                var offset = 0;
                while (offset < data.length) {
                    var length = ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                            | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
                    var type = (data[offset + 4] >> 1) & 0x3F;
                    assertTrue(type < 32 || type == 39 || type == 40, "NAL unit type " + type);
                    offset += 4 + length;
                }
                assertEquals(data.length, offset);
            }
        }

        @Test
        @DisplayName("Annex B IDR access units start with the parameter sets")
        void annexB() {
            var options = quality(30).packetFormat(HevcPacketFormat.ANNEX_B).keyframeInterval(3).bFrames(0).build();
            var encoded = HevcEncoderHarness.encode(64, 48, options, HevcEncoderHarness.sequence(64, 48, 7, 43));
            try (var encoder = new HevcEncoder(64, 48, options)) {
                assertTrue(encoder.codecConfig().isEmpty());
            }
            for (var i = 0; i < 7; i++) {
                var packet = encoded.packets().get(i);
                assertEquals(i % 3 == 0, packet.keyframe(), "packet " + i);
                var types = types(packet.data());
                if (i % 3 == 0) {
                    assertEquals(List.of(32, 33, 34, 19), types.subList(0, 4), "packet " + i);
                } else {
                    assertEquals(List.of(1, 40), types, "packet " + i);
                }
            }
        }

        @Test
        @DisplayName("B pictures leave in decoding order with decoding timestamps before their presentation")
        void reordering() {
            var options = quality(30).bFrames(3).keyframeInterval(100).build();
            var encoded = HevcEncoderHarness.encode(64, 64, options, HevcEncoderHarness.sequence(64, 64, 9, 47));
            var pts = encoded.packets().stream().mapToLong(Packet::pts).toArray();
            assertArrayEquals(new long[]{0, 12000, 6000, 3000, 9000, 24000, 18000, 15000, 21000}, pts);
            var previous = Long.MIN_VALUE;
            for (var packet : encoded.packets()) {
                assertTrue(packet.dts() <= packet.pts(), "dts " + packet.dts() + " after pts " + packet.pts());
                assertTrue(packet.dts() > previous, "decoding timestamps increase");
                previous = packet.dts();
            }
            assertTrue(encoded.packets().get(3).flags().contains(PacketFlag.DISCARDABLE), "non-reference B picture");
            assertFalse(encoded.packets().get(2).flags().contains(PacketFlag.DISCARDABLE), "reference B picture");
        }

        @Test
        @DisplayName("a keyframe request makes the next frame an IDR picture")
        void keyframeRequest() {
            var options = quality(30).noPeriodicKeyframes().bFrames(0).build();
            var pictures = HevcEncoderHarness.sequence(64, 64, 6, 51);
            var packets = new ArrayList<Packet>();
            byte[] config;
            try (var encoder = new HevcEncoder(64, 64, options)) {
                config = encoder.codecConfig().orElseThrow();
                for (var i = 0; i < 6; i++) {
                    if (i == 3) {
                        encoder.requestKeyframe();
                        encoder.requestKeyframe();
                    }
                    encoder.encode(new VideoFrame(pictures.get(i), i * 3000L, 3000, false), packets::add);
                }
                assertEquals(2, encoder.stats().keyframesEncoded());
                assertEquals(2, encoder.stats().keyframeRequests());
                assertEquals(6, encoder.stats().framesEncoded());
            }
            for (var i = 0; i < 6; i++) {
                assertEquals(i == 0 || i == 3, packets.get(i).keyframe(), "packet " + i);
            }
            assertEquals(6, HevcEncoderHarness.decode(new HevcEncoderHarness.Encoded(packets, config, options)).size());
        }

        @Test
        @DisplayName("the video usability information and SEI carry the color description and HDR metadata")
        void color() {
            var display = new MasteringDisplay(new Chromaticity(0.708, 0.292), new Chromaticity(0.17, 0.797),
                    new Chromaticity(0.131, 0.046), new Chromaticity(0.3127, 0.329), 1000, 0.0001);
            var color = new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED, ColorPrimaries.BT2020,
                    TransferCharacteristics.PQ).withMasteringDisplay(display)
                    .withContentLightLevel(new ContentLightLevel(1000, 400));
            var options = quality(30).profile(HevcProfile.MAIN_10).bitDepth(10).color(color).build();
            var encoded = HevcEncoderHarness.encode(64, 64, options, HevcEncoderHarness.sequence(64, 64, 2, 53, 10));
            var decoded = HevcEncoderHarness.decode(encoded);
            var info = ((Yuv16Picture) decoded.getFirst().picture()).color();
            assertEquals(MatrixCoefficients.BT2020_NCL, info.matrix());
            assertEquals(ColorPrimaries.BT2020, info.primaries());
            assertEquals(TransferCharacteristics.PQ, info.transfer());
            assertEquals(ColorRange.LIMITED, info.range());
            var mastering = info.masteringDisplay().orElseThrow();
            assertEquals(1000, mastering.maxLuminance(), 1e-3);
            assertEquals(0.708, mastering.red().x(), 1e-4);
            assertEquals(new ContentLightLevel(1000, 400), info.contentLightLevel().orElseThrow());
            var parsed = NalRecords.parseHevcStrict(encoded.config());
            assertEquals(39, (parsed.parameterSets().getLast()[0] >> 1) & 0x3F,
                    "prefix SEI in the configuration record");
        }

        @Test
        @DisplayName("RGB input converts with the configured matrix and range")
        void rgbInput() {
            var rgba = com.github.auties00.smalt.image.RgbaPicture.allocate(32, 32, false);
            Arrays.fill(rgba.pixels(), (byte) 0);
            for (var i = 0; i < 32 * 32; i++) {
                rgba.pixels()[i * 4] = (byte) 200;
                rgba.pixels()[i * 4 + 3] = (byte) 255;
            }
            var options = quality(10).color(ColorInfo.JPEG).build();
            var encoded = HevcEncoderHarness.encode(32, 32, options, List.of(rgba));
            var picture = (YuvPicture) HevcEncoderHarness.decode(encoded).getFirst().picture();
            // full-range BT.601 Y of R=200: 0.299 * 200
            assertEquals(60, picture.y()[0] & 0xFF, 1);
        }

        @Test
        @DisplayName("a picture of another size, a decreasing timestamp and a closed encoder are refused")
        void refusals() {
            var encoder = new HevcEncoder(64, 64, quality(30).build());
            var picture = HevcEncoderHarness.picture(32, 32, 0, 1, 8);
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.encode(new VideoFrame(picture, 0, 0, false), packet -> { }));
            var good = HevcEncoderHarness.picture(64, 64, 0, 1, 8);
            encoder.encode(new VideoFrame(good, 10, 0, false), packet -> { });
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.encode(new VideoFrame(good, 10, 0, false), packet -> { }));
            encoder.close();
            assertThrows(IllegalStateException.class,
                    () -> encoder.encode(new VideoFrame(good, 20, 0, false), packet -> { }));
        }
    }

    @Nested
    @DisplayName("compression")
    class Compression {
        @ParameterizedTest(name = "quantizer {0}")
        @ValueSource(ints = {26, 34})
        @DisplayName("codes a sequence in fewer bytes and at a higher PSNR than the H.264 encoder at the same quantizer")
        void againstH264(int qp) {
            var width = 320;
            var height = 184;
            var frames = 10;
            var pictures = HevcEncoderHarness.sequence(width, height, frames, 21);
            var hevc = HevcEncoderHarness.encode(width, height, quality(qp).speed(HevcEncoderSpeed.BALANCED).bFrames(3)
                    .build(), pictures);
            var hevcPsnr = 0.0;
            for (var frame : HevcEncoderHarness.decode(hevc)) {
                hevcPsnr += HevcEncoderHarness.psnr(frame.picture(), pictures.get((int) (frame.pts() / 3000)));
            }
            hevcPsnr /= frames;
            var packets = new ArrayList<Packet>();
            byte[] config;
            try (var encoder = new H264Encoder(width, height, H264EncoderOptions.builder()
                    .rateControl(H264RateControl.CONSTANT_QUALITY).quality(qp).speed(H264EncoderSpeed.BALANCED)
                    .bFrames(3).timeBase(new Rational(1, 90_000)).build())) {
                for (var i = 0; i < frames; i++) {
                    encoder.encode(new VideoFrame(pictures.get(i), i * 3000L, 3000, false), packets::add);
                }
                encoder.flush(packets::add);
                config = encoder.codecConfig().orElse(null);
            }
            var avcBytes = packets.stream().mapToLong(Packet::size).sum();
            var track = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(new Rational(1, 90_000)).width(width)
                    .height(height).codecConfig(config).build();
            var avcPsnr = new double[1];
            var avcFrames = new int[1];
            try (var decoder = new H264Decoder(track)) {
                java.util.function.Consumer<VideoFrame> sink = frame -> {
                    // the decoder reuses its pictures, so each is measured while it is lent
                    avcPsnr[0] += HevcEncoderHarness.psnr(frame.picture(), pictures.get((int) (frame.pts() / 3000)));
                    avcFrames[0]++;
                };
                for (var packet : packets) {
                    decoder.decode(packet, sink);
                }
                decoder.flush(sink);
            }
            assertEquals(frames, avcFrames[0]);
            var h264Psnr = avcPsnr[0] / frames;
            assertTrue(hevc.bytes() < avcBytes, "HEVC " + hevc.bytes() + " bytes, H.264 " + avcBytes);
            assertTrue(hevcPsnr > h264Psnr + 1, "HEVC " + hevcPsnr + " dB, H.264 " + h264Psnr + " dB");
        }

        @Test
        @DisplayName("each slower speed codes a sequence in fewer bytes at about the same PSNR")
        void speeds() {
            var width = 320;
            var height = 184;
            var pictures = HevcEncoderHarness.sequence(width, height, 8, 23);
            var previousBytes = Long.MAX_VALUE;
            var previousPsnr = 0.0;
            for (var speed : HevcEncoderSpeed.values()) {
                var encoded = HevcEncoderHarness.encode(width, height, quality(32).speed(speed).bFrames(3).build(),
                        pictures);
                var psnr = 0.0;
                for (var frame : HevcEncoderHarness.decode(encoded)) {
                    psnr += HevcEncoderHarness.psnr(frame.picture(), pictures.get((int) (frame.pts() / 3000)));
                }
                psnr /= pictures.size();
                assertTrue(encoded.bytes() < previousBytes, speed + ": " + encoded.bytes() + " bytes after "
                        + previousBytes);
                assertTrue(psnr > previousPsnr - 0.3, speed + ": " + psnr + " dB after " + previousPsnr);
                previousBytes = encoded.bytes();
                previousPsnr = psnr;
            }
        }
    }

    @Nested
    @DisplayName("rate control")
    class RateControl {
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = HevcRateControl.class, names = {"AVERAGE_BITRATE", "CONSTANT_BITRATE"})
        @DisplayName("approaches the target bitrate")
        void bitrate(HevcRateControl mode) {
            var frames = 60;
            var target = 300_000L;
            var options = HevcEncoderOptions.builder().rateControl(mode).bitrate(target).bFrames(0)
                    .speed(HevcEncoderSpeed.FASTEST).build();
            var encoded = HevcEncoderHarness.encode(160, 96, options, HevcEncoderHarness.sequence(160, 96, frames, 61));
            var rate = encoded.bytes() * 8.0 / (frames / 30.0);
            assertTrue(rate > target * 0.6 && rate < target * 1.4, "rate " + rate + " for target " + target);
            assertEquals(frames, HevcEncoderHarness.decode(encoded).size());
        }

        @Test
        @DisplayName("a new target bitrate changes the produced rate")
        void retarget() {
            var options = HevcEncoderOptions.forRealtime().toBuilder().bitrate(400_000).build();
            var pictures = HevcEncoderHarness.sequence(160, 96, 60, 67);
            var first = 0L;
            var second = 0L;
            try (var encoder = new HevcEncoder(160, 96, options)) {
                for (var i = 0; i < 60; i++) {
                    if (i == 30) {
                        encoder.setTargetBitrate(100_000);
                        assertEquals(400_000, encoder.stats().targetBitrate());
                    }
                    var sizes = new long[1];
                    encoder.encode(new VideoFrame(pictures.get(i), i * 3000L, 3000, false),
                            packet -> sizes[0] += packet.size());
                    if (i >= 10 && i < 30) {
                        first += sizes[0];
                    } else if (i >= 40) {
                        second += sizes[0];
                    }
                }
                assertEquals(100_000, encoder.stats().targetBitrate());
            }
            assertTrue(second < first * 0.6, "rate after the change " + second + " vs " + first);
        }

        @Test
        @DisplayName("constant quality has no target bitrate")
        void constantQuality() {
            try (var encoder = new HevcEncoder(64, 64, quality(30).build())) {
                assertThrows(IllegalStateException.class, () -> encoder.setTargetBitrate(1000));
                assertThrows(IllegalArgumentException.class, () -> encoder.setTargetBitrate(0));
                encoder.encode(new VideoFrame(HevcEncoderHarness.picture(64, 64, 0, 1, 8), 0, 0, false), p -> { });
                assertEquals(28, encoder.stats().lastQuantizer());
                assertEquals(0, encoder.stats().targetBitrate());
            }
        }
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("the builder checks the ranges and the profile's bit depth")
        void validation() {
            assertThrows(IllegalArgumentException.class, () -> HevcEncoderOptions.builder().bitDepth(10).build());
            assertThrows(IllegalArgumentException.class,
                    () -> HevcEncoderOptions.builder().profile(HevcProfile.MAIN_10).bitDepth(12).build());
            assertThrows(IllegalArgumentException.class, () -> HevcEncoderOptions.builder().quality(52).build());
            assertThrows(IllegalArgumentException.class, () -> HevcEncoderOptions.builder().bFrames(8).build());
            assertThrows(IllegalArgumentException.class, () -> HevcEncoderOptions.builder().threads(0).build());
            assertThrows(IllegalArgumentException.class, () -> HevcEncoderOptions.builder().quantizers(30, 20).build());
            assertThrows(IllegalArgumentException.class,
                    () -> HevcEncoderOptions.builder().bitrate(1000).maxBitrate(500).build());
            assertThrows(IllegalArgumentException.class,
                    () -> HevcEncoderOptions.builder().bufferSize(Duration.ZERO).build());
            assertThrows(IllegalArgumentException.class,
                    () -> HevcEncoderOptions.builder().frameRate(new Rational(0, 1)).build());
            assertThrows(IllegalArgumentException.class,
                    () -> HevcEncoderOptions.builder().color(ColorInfo.JPEG.withMatrix(MatrixCoefficients.IDENTITY))
                            .build());
            assertThrows(IllegalArgumentException.class, () -> new HevcEncoder(0, 10));
            assertThrows(IllegalArgumentException.class, () -> new HevcEncoder(1920, 1080,
                    HevcEncoderOptions.builder().level(HevcLevel.LEVEL_2).build()));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("invalidOptions")
        @DisplayName("the builder rejects each value outside its range")
        void invalid(String name, UnaryOperator<HevcEncoderOptions.Builder> change) {
            assertThrows(IllegalArgumentException.class, () -> change.apply(HevcEncoderOptions.builder()).build());
        }

        static Stream<Arguments> invalidOptions() {
            return Stream.of(
                    option("frame rate above 1000", b -> b.frameRate(new Rational(1001, 1))),
                    option("frame rate numerator past the timing fields",
                            b -> b.frameRate(new Rational(Integer.MAX_VALUE, Integer.MAX_VALUE))),
                    option("zero time base numerator", b -> b.timeBase(new Rational(0, 1))),
                    option("zero bitrate", b -> b.bitrate(0)),
                    option("bitrate past 32 bits", b -> b.bitrate(Integer.MAX_VALUE + 1L)),
                    option("zero maximum bitrate", b -> b.maxBitrate(0)),
                    option("maximum below the bitrate in constant bitrate mode",
                            b -> b.rateControl(HevcRateControl.CONSTANT_BITRATE).bitrate(2000).maxBitrate(1000)),
                    option("negative buffer", b -> b.bufferSize(Duration.ofMillis(-1))),
                    option("buffer past 32-bit milliseconds", b -> b.bufferSize(Duration.ofDays(30))),
                    option("negative quality", b -> b.quality(-1)),
                    option("zero key frame interval", b -> b.keyframeInterval(0)),
                    option("65 threads", b -> b.threads(65)),
                    option("negative B pictures", b -> b.bFrames(-1)),
                    option("quantizer above 51", b -> b.quantizers(0, 52)),
                    option("negative quantizer", b -> b.quantizers(-1, 51)),
                    option("7-bit samples", b -> b.bitDepth(7)),
                    option("zero aspect ratio", b -> b.sampleAspectRatio(new Rational(0, 1))),
                    option("aspect ratio term past 16 bits", b -> b.sampleAspectRatio(new Rational(65536, 1))),
                    option("aspect ratio denominator past 16 bits", b -> b.sampleAspectRatio(new Rational(1, 65536))));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("changedOptions")
        @DisplayName("each setting takes part in equality and survives toBuilder")
        void everySetting(String name, UnaryOperator<HevcEncoderOptions.Builder> change) {
            var base = HevcEncoderOptions.builder().frameRate(new Rational(30, 1)).timeBase(new Rational(1, 90_000))
                    .profile(HevcProfile.MAIN_10).level(HevcLevel.LEVEL_4).bitDepth(8)
                    .rateControl(HevcRateControl.AVERAGE_BITRATE).bitrate(1_000_000).maxBitrate(2_000_000)
                    .bufferSize(Duration.ofSeconds(1)).quality(30).keyframeInterval(60).speed(HevcEncoderSpeed.BALANCED)
                    .color(ColorInfo.BT709_LIMITED).threads(2).packetFormat(HevcPacketFormat.LENGTH_PREFIXED)
                    .quantizers(10, 40).realtime(false).bFrames(3).limits(MediaLimits.DEFAULT)
                    .pictureHash(HevcPictureHash.MD5).sampleAspectRatio(new Rational(1, 1)).build();
            var changed = change.apply(base.toBuilder()).build();
            assertNotEquals(base, changed);
            assertNotEquals(changed, base);
            assertEquals(changed, changed.toBuilder().build());
            assertEquals(changed.hashCode(), changed.toBuilder().build().hashCode());
            assertNotEquals(base.toString(), changed.toString());
        }

        static Stream<Arguments> changedOptions() {
            return Stream.of(
                    option("frame rate", b -> b.frameRate(new Rational(25, 1))),
                    option("time base", b -> b.timeBase(new Rational(1, 1000))),
                    option("profile", b -> b.profile(HevcProfile.MAIN)),
                    option("level", b -> b.level(HevcLevel.LEVEL_5)),
                    option("bit depth", b -> b.bitDepth(10)),
                    option("rate control", b -> b.rateControl(HevcRateControl.CONSTANT_BITRATE)),
                    option("bitrate", b -> b.bitrate(1_500_000)),
                    option("maximum bitrate", b -> b.maxBitrate(3_000_000)),
                    option("buffer size", b -> b.bufferSize(Duration.ofMillis(1500))),
                    option("quality", b -> b.quality(31)),
                    option("key frame interval", b -> b.keyframeInterval(61)),
                    option("no periodic key frames", HevcEncoderOptions.Builder::noPeriodicKeyframes),
                    option("speed", b -> b.speed(HevcEncoderSpeed.SLOW)),
                    option("color", b -> b.color(ColorInfo.BT601_LIMITED)),
                    option("threads", b -> b.threads(3)),
                    option("packet format", b -> b.packetFormat(HevcPacketFormat.ANNEX_B)),
                    option("minimum quantizer", b -> b.quantizers(11, 40)),
                    option("maximum quantizer", b -> b.quantizers(10, 41)),
                    option("real time", b -> b.realtime(true)),
                    option("B pictures", b -> b.bFrames(2)),
                    option("limits", b -> b.limits(MediaLimits.builder().maxFrames(5).build())),
                    option("picture hash", b -> b.pictureHash(HevcPictureHash.CRC)),
                    option("sample aspect ratio", b -> b.sampleAspectRatio(new Rational(4, 3))));
        }

        private static Arguments option(String name, UnaryOperator<HevcEncoderOptions.Builder> change) {
            return Arguments.of(name, change);
        }

        @Test
        @DisplayName("toBuilder keeps every setting and the presets differ")
        void roundTrip() {
            var options = HevcEncoderOptions.builder().profile(HevcProfile.MAIN_10).bitDepth(10).level(HevcLevel.LEVEL_4)
                    .rateControl(HevcRateControl.CONSTANT_BITRATE).bitrate(123_456).maxBitrate(200_000)
                    .bufferSize(Duration.ofMillis(700)).quality(33).keyframeInterval(17).speed(HevcEncoderSpeed.SLOW)
                    .threads(3).packetFormat(HevcPacketFormat.ANNEX_B).quantizers(5, 45).realtime(true).bFrames(2)
                    .pictureHash(HevcPictureHash.CRC).sampleAspectRatio(new Rational(4, 3)).build();
            assertEquals(options, options.toBuilder().build());
            assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
            assertNotEquals(HevcEncoderOptions.forFile(), HevcEncoderOptions.forRealtime());
            assertEquals(HevcProfile.MAIN_STILL_PICTURE, HevcEncoderOptions.forStillPicture().profile());
            assertTrue(options.toString().contains("LEVEL_4"));
            var automatic = options.toBuilder().automaticLevel().automaticMaxBitrate().automaticBufferSize().build();
            assertTrue(automatic.level().isEmpty());
            assertTrue(automatic.maxBitrate().isEmpty());
            assertTrue(automatic.bufferSize().isEmpty());
        }

        @Test
        @DisplayName("the lowest level that fits the stream is signaled")
        void level() {
            var options = quality(30).frameRate(new Rational(30, 1)).build();
            var encoded = HevcEncoderHarness.encode(176, 144, options, HevcEncoderHarness.sequence(176, 144, 1, 71));
            assertEquals(HevcLevel.LEVEL_2.levelIdc(), encoded.config()[12] & 0xFF);
            var high = HevcEncoderHarness.encode(1280, 720, quality(40).speed(HevcEncoderSpeed.FASTEST).build(),
                    HevcEncoderHarness.sequence(1280, 720, 1, 73));
            assertEquals(HevcLevel.LEVEL_3_1.levelIdc(), high.config()[12] & 0xFF);
        }
    }

    /** Returns the sequence parameter set of a stream's configuration record, header included. */
    private static byte[] sps(HevcEncoderHarness.Encoded encoded) {
        var bytes = encoded.annexB();
        return annexBUnit(bytes, 33);
    }

    /** Returns the first NAL unit of a type of an Annex B stream, header included and escaped. */
    private static byte[] annexBUnit(byte[] stream, int type) {
        for (var i = 0; i + 4 < stream.length; i++) {
            if (stream[i] == 0 && stream[i + 1] == 0 && stream[i + 2] == 1 && ((stream[i + 3] >> 1) & 0x3F) == type) {
                var end = i + 3;
                while (end + 3 < stream.length && !(stream[end] == 0 && stream[end + 1] == 0
                        && (stream[end + 2] == 1 || stream[end + 2] == 0 && stream[end + 3] == 1))) {
                    end++;
                }
                return Arrays.copyOfRange(stream, i + 3, end + 3 >= stream.length ? stream.length : end);
            }
        }
        throw new AssertionError("no NAL unit of type " + type);
    }

    /** Returns the NAL unit types of an Annex B access unit. */
    private static List<Integer> types(byte[] data) {
        var types = new ArrayList<Integer>();
        for (var i = 0; i + 4 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1) {
                types.add((data[i + 4] >> 1) & 0x3F);
                i += 4;
            }
        }
        return types;
    }
}
