package com.github.auties00.smalt.video.dnxhd;

import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxerOptions;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.dnxhd.DnxhdEncoderOptions.AlphaStorage;
import com.github.auties00.smalt.video.intra.IntraTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the alpha Smalt's encoder stores after the video of each frame, as Avid's QuickTime
 * encoders do: the frame layout (the video coding units unchanged, the alpha, the four-byte size of
 * the video), the {@code AALP} extension of the sample description against the Avid files attached
 * to public ffmpeg bug reports, round trips through QuickTime and Matroska and through
 * {@link DnxhdDecoder}, and ffmpeg and ffprobe as black boxes.
 */
@DisplayName("DNxHD Avid out-of-band alpha")
class DnxhdAvidAlphaTest {
    static Packet encode(DnxhdEncoder encoder, Picture picture) {
        return encoder.encodeFrame(new VideoFrame(picture, 0, 3600, true));
    }

    static int u32(byte[] data, int at) {
        return (data[at] & 0xFF) << 24 | (data[at + 1] & 0xFF) << 16 | (data[at + 2] & 0xFF) << 8 | data[at + 3] & 0xFF;
    }

    /** Returns the alpha plane of a decoded picture as integers at the picture's depth. */
    static int[] alpha(Picture picture) {
        var width = picture.width();
        var height = picture.height();
        var result = new int[width * height];
        switch (picture) {
            case Yuv16Picture p -> {
                for (var y = 0; y < height; y++) {
                    for (var x = 0; x < width; x++) {
                        result[y * width + x] = p.alpha()[y * p.alphaStride() + x] & 0xFFFF;
                    }
                }
            }
            case YuvPicture p -> {
                for (var y = 0; y < height; y++) {
                    for (var x = 0; x < width; x++) {
                        result[y * width + x] = p.alpha()[y * p.alphaStride() + x] & 0xFF;
                    }
                }
            }
            case Rgba16Picture p -> {
                for (var i = 0; i < result.length; i++) {
                    result[i] = p.pixels()[i * 4 + 3] & 0xFFFF;
                }
            }
            default -> throw new IllegalArgumentException(picture.toString());
        }
        return result;
    }

    /** Returns a test picture of a layout and depth whose alpha is the synthetic disc. */
    static Picture source(int width, int height, ChromaFormat format, int depth, boolean rgb) {
        if (!rgb) {
            return AlphaPlanes.synthetic(width, height, format, depth).toPicture();
        }
        return DnxhdPremultipliedAlphaTest.rgba(width, height);
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("DNxHR SQ", 256, 128, ChromaFormat.YUV422, 8, false,
                        DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_SQ)),
                Arguments.of("DNxHR LB, which has no in-band alpha", 256, 128, ChromaFormat.YUV422, 8, false,
                        DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_LB)),
                Arguments.of("DNxHR HQ 4:2:0", 256, 128, ChromaFormat.YUV420, 8, false,
                        DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQ).toBuilder().chromaFormat(ChromaFormat.YUV420)
                                .build()),
                Arguments.of("DNxHR HQX 10-bit", 256, 128, ChromaFormat.YUV422, 10, false,
                        DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQX)),
                Arguments.of("DNxHR HQX 12-bit odd height", 256, 130, ChromaFormat.YUV422, 12, false,
                        DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQX).toBuilder().bitDepth(12).build()),
                Arguments.of("DNxHR 444 RGB", 256, 128, ChromaFormat.YUV444, 12, true,
                        DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_444).toBuilder().rgb(true).bitDepth(12).build()),
                Arguments.of("DNxHD 1238 1080p", 1920, 1080, ChromaFormat.YUV422, 8, false,
                        DnxhdEncoderOptions.of(DnxhdProfile.DNXHD).toBuilder().compressionId(1238).build()),
                Arguments.of("DNxHD 1241 1080i 10-bit", 1920, 1080, ChromaFormat.YUV422, 10, false,
                        DnxhdEncoderOptions.of(DnxhdProfile.DNXHD).toBuilder().fieldOrder(FieldOrder.TOP_FIRST)
                                .bitDepth(10).build()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("stores 8-bit alpha samples after unchanged video coding units")
    void uncompressed(String label, int width, int height, ChromaFormat format, int depth, boolean rgb,
                      DnxhdEncoderOptions options) {
        var picture = source(width, height, format, depth, rgb);
        var plain = encode(new DnxhdEncoder(width, height, options), picture).data();
        var encoder = new DnxhdEncoder(width, height, options.toBuilder().alpha(true)
                .alphaStorage(AlphaStorage.AVID_UNCOMPRESSED).build());
        var frame = encode(encoder, picture).data();
        var area = depth == 8 ? width * height : (width * height * 5 + 3) / 4;
        assertEquals(plain.length + area + 4, frame.length, label + ": frame size");
        assertArrayEquals(plain, Arrays.copyOf(frame, plain.length), label + ": the video coding units are unchanged");
        assertEquals(plain.length, u32(frame, frame.length - 4), label + ": trailing size of the video");
        assertEquals(0, frame[7] & 1, label + ": no in-band alpha");
        var max = rgb ? 65535 : (1 << depth) - 1;
        var sourceAlpha = rgb ? alpha(picture) : AlphaPlanes.of(picture).a();
        for (var i = 0; i < width * height; i++) {
            var expected = (sourceAlpha[i] * 255 + max / 2) / max;
            // RGB alpha reaches the 8-bit samples through the stream's 12 bits, which may round once more
            assertTrue(Math.abs(expected - (frame[plain.length + i] & 0xFF)) <= (rgb ? 1 : 0),
                    label + ": alpha sample " + i);
        }
        for (var i = plain.length + width * height; i < frame.length - 4; i++) {
            assertEquals(0, frame[i], label + ": the rest of the alpha area is zero");
        }
        var decoder = new DnxhdDecoder();
        var video = IntraTesting.raw(decoder.decodeFrame(plain, 0, plain.length), false);
        var decoded = decoder.decodeFrame(frame, 0, frame.length);
        assertTrue(decoded.hasAlpha(), label);
        assertArrayEquals(video, IntraTesting.raw(decoded, false), label + ": the video decodes as without alpha");
        var codeMax = (1 << (rgb ? depth : decoded.bitDepth())) - 1;
        var decodedAlpha = alpha(decoded);
        for (var i = 0; i < width * height; i++) {
            var code = ((frame[plain.length + i] & 0xFF) * (long) codeMax + 127) / 255;
            // an RGB picture carries its alpha in 16 bits, expanded from the stream's codes
            var expected = rgb ? (code * 65535 + codeMax / 2) / codeMax : code;
            assertEquals(expected, decodedAlpha[i], label + ": decoded alpha " + i);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("stores alpha as coding units of the stream's compression ID after unchanged video coding units")
    void coded(String label, int width, int height, ChromaFormat format, int depth, boolean rgb,
               DnxhdEncoderOptions options) {
        var picture = source(width, height, format, depth, rgb);
        var plain = encode(new DnxhdEncoder(width, height, options), picture).data();
        var encoder = new DnxhdEncoder(width, height, options.toBuilder().alpha(true)
                .alphaStorage(AlphaStorage.AVID_CODED).build());
        var frame = encode(encoder, picture).data();
        assertEquals(2 * plain.length + 4, frame.length, label + ": the alpha takes the size of the video");
        assertArrayEquals(plain, Arrays.copyOf(frame, plain.length), label + ": the video coding units are unchanged");
        assertEquals(plain.length, u32(frame, frame.length - 4), label + ": trailing size of the video");
        var alphaUnits = Arrays.copyOfRange(frame, plain.length, 2 * plain.length);
        assertEquals(u32(plain, 0x28), u32(alphaUnits, 0x28), label + ": same compression ID");
        assertEquals(plain[5] & 3, alphaUnits[5] & 3, label + ": same field structure");
        assertEquals(0, alphaUnits[7] & 1, label + ": no in-band alpha in the alpha unit");
        assertEquals(0, alphaUnits[0x2C] & 1, label + ": the alpha unit is Y'CbCr");
        var decoder = new DnxhdDecoder();
        var luma = AlphaPlanes.of(withAlphaPlane(decoder.decodeFrame(alphaUnits, 0, alphaUnits.length)));
        var max = luma.max();
        var expected = rgb ? Arrays.stream(alpha(picture)).map(a -> (a * max + 32767) / 65535).toArray()
                : AlphaPlanes.of(picture).a();
        var psnr = psnr(expected, luma.y(), max);
        assertTrue(psnr > 40, label + ": the luma of the alpha unit is the alpha, " + psnr + " dB");
        for (var c : luma.u()) {
            assertTrue(Math.abs(c - luma.middle()) <= 1, label + ": neutral chroma");
        }
        var video = IntraTesting.raw(decoder.decodeFrame(plain, 0, plain.length), false);
        var decoded = decoder.decodeFrame(frame, 0, frame.length);
        assertArrayEquals(video, IntraTesting.raw(decoded, false), label + ": the video decodes as without alpha");
        var decodedAlpha = alpha(decoded);
        var outputMax = rgb ? 65535 : (1 << decoded.bitDepth()) - 1;
        for (var i = 0; i < decodedAlpha.length; i++) {
            var code = luma.y()[i];
            assertEquals(outputMax == max ? code : (code * (long) outputMax + max / 2) / max, decodedAlpha[i],
                    label + ": decoded alpha " + i);
        }
    }

    /** Returns a Y'CbCr picture with a zero alpha plane, so that {@link AlphaPlanes#of} reads it. */
    static Picture withAlphaPlane(Picture picture) {
        return switch (picture) {
            case Yuv16Picture p -> p.toBuilder().alpha(new short[p.width() * p.height()], p.width()).build();
            case YuvPicture p -> p.toBuilder().alpha(new byte[p.width() * p.height()], p.width()).build();
            default -> throw new IllegalArgumentException(picture.toString());
        };
    }

    static double psnr(int[] expected, int[] actual, int max) {
        var sum = 0.0;
        for (var i = 0; i < expected.length; i++) {
            var d = expected[i] - actual[i];
            sum += (double) d * d;
        }
        return sum == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10((double) max * max * expected.length / sum);
    }

    @Test
    @DisplayName("reads raw alpha whose first samples resemble a coding unit header as samples")
    void headerLikeSamples() {
        var planes = AlphaPlanes.synthetic(256, 128, ChromaFormat.YUV422, 8);
        // 00 00 02 80 01: the header size of a coding unit and header version 1
        System.arraycopy(new int[]{0, 0, 2, 128, 1, 0, 0, 0}, 0, planes.a(), 0, 8);
        var picture = planes.toPicture();
        var frame = encode(new DnxhdEncoder(256, 128, DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_SQ).toBuilder()
                .alpha(true).alphaStorage(AlphaStorage.AVID_UNCOMPRESSED).build()), picture).data();
        var decoded = alpha(new DnxhdDecoder().decodeFrame(frame, 0, frame.length));
        assertArrayEquals(planes.a(), decoded);
    }

    @Test
    @DisplayName("names the storage of the alpha in an AALP atom after the sample entry type")
    void codecConfig() {
        var base = DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_SQ).toBuilder().alpha(true);
        assertArrayEquals("AVdh".getBytes(StandardCharsets.US_ASCII),
                new DnxhdEncoder(64, 64, base.build()).codecConfig().orElseThrow(), "in band: the type alone");
        assertArrayEquals(aalp("AVdh", "AUA8"), new DnxhdEncoder(64, 64, base.alphaStorage(AlphaStorage.AVID_UNCOMPRESSED)
                .build()).codecConfig().orElseThrow());
        assertArrayEquals(aalp("AVdh", "AVdh"), new DnxhdEncoder(64, 64, base.alphaStorage(AlphaStorage.AVID_CODED)
                .build()).codecConfig().orElseThrow());
        assertArrayEquals(aalp("AVdn", "AUAX"), new DnxhdEncoder(1920, 1080, DnxhdEncoderOptions.of(DnxhdProfile.DNXHD)
                .toBuilder().bitDepth(10).alpha(true).alphaStorage(AlphaStorage.AVID_UNCOMPRESSED).build())
                .codecConfig().orElseThrow());
        assertArrayEquals("AVdh".getBytes(StandardCharsets.US_ASCII), new DnxhdEncoder(64, 64, base.alpha(false)
                .build()).codecConfig().orElseThrow(), "no alpha: the type alone");
    }

    /** Returns a sample entry type followed by the AALP atom Avid writes for an alpha storage. */
    static byte[] aalp(String type, String storage) {
        var atom = new byte[28];
        atom[3] = 28;
        System.arraycopy("AALPAALP0001".getBytes(StandardCharsets.US_ASCII), 0, atom, 4, 12);
        atom[19] = 1;
        System.arraycopy(storage.getBytes(StandardCharsets.US_ASCII), 0, atom, 20, 4);
        var config = Arrays.copyOf(type.getBytes(StandardCharsets.US_ASCII), 32);
        System.arraycopy(atom, 0, config, 4, 28);
        return config;
    }

    @Test
    @DisplayName("rejects alpha storage combinations the formats cannot carry")
    void options() {
        assertThrows(IllegalArgumentException.class, () -> DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_444).toBuilder()
                .alpha(true).losslessAlpha(true).alphaStorage(AlphaStorage.AVID_CODED).build());
        assertThrows(IllegalArgumentException.class, () -> DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_444).toBuilder()
                .alpha(true).losslessAlpha(true).alphaStorage(AlphaStorage.AVID_UNCOMPRESSED).build());
        assertThrows(IllegalArgumentException.class, () -> DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQ).toBuilder()
                .alpha(true).premultipliedAlpha(true).alphaStorage(AlphaStorage.AVID_CODED).build());
        assertThrows(NullPointerException.class, () -> DnxhdEncoderOptions.builder().alphaStorage(null));
        assertThrows(IllegalArgumentException.class, () -> new DnxhdEncoder(256, 128,
                DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_LB).toBuilder().alpha(true).build()), "LB has no in-band alpha");
        var options = DnxhdEncoderOptions.builder().alphaStorage(AlphaStorage.AVID_CODED).build();
        assertEquals(AlphaStorage.AVID_CODED, options.alphaStorage());
        assertEquals(AlphaStorage.IN_BAND, DnxhdEncoderOptions.defaults().alphaStorage());
        assertEquals(options, options.toBuilder().build());
        assertTrue(!options.equals(DnxhdEncoderOptions.defaults()));
    }

    @Nested
    @DisplayName("against Avid's files")
    class Avid {
        static Stream<Arguments> files() {
            // the Avid files of DnxhdAlphaTest: an 8-bit DNxHR SQ file with raw alpha, a 10-bit DNxHD file with raw
            // alpha in an area sized for 10-bit samples, and an 8-bit DNxHD file whose alpha is a coding unit
            return Stream.of(
                    Arguments.of("ffmpeg-trac-8427-dnxhd_with_alpha_straight.mov",
                            "https://trac.ffmpeg.org/raw-attachment/ticket/8427/dnxhd_with_alpha_straight.mov",
                            "10d46a0f07ac9ec5093dc7deac30446b5c42d86685e6bbc475ff05c570fb71a4",
                            DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_SQ), AlphaStorage.AVID_UNCOMPRESSED),
                    Arguments.of("ffmpeg-trac-3707-untitled_dnhd.mov",
                            "https://samples.ffmpeg.org/ffmpeg-bugs/trac/ticket3707/Untitled_dnhd.mov",
                            "ff237238ca50fe68bae53876e98d69507b07439b9323bd5271f305e52cbb8092",
                            DnxhdEncoderOptions.of(DnxhdProfile.DNXHD).toBuilder().fieldOrder(FieldOrder.TOP_FIRST)
                                    .bitDepth(10).build(), AlphaStorage.AVID_UNCOMPRESSED),
                    Arguments.of("ffmpeg-trac-3707-dnxhd_alpha.mov",
                            "https://samples.ffmpeg.org/ffmpeg-bugs/trac/ticket3707/DNxHD%20Alpha.mov",
                            "e52d761169e71fab3c413fec66543a61cfafa4472c1c7860444ba2058600b2c6",
                            DnxhdEncoderOptions.of(DnxhdProfile.DNXHD).toBuilder().compressionId(1238).build(),
                            AlphaStorage.AVID_CODED));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("files")
        @DisplayName("writes the AALP atom and the frame layout of Avid's encoder")
        void sameLayout(String name, String url, String sha256, DnxhdEncoderOptions options, AlphaStorage storage) {
            var stream = IntraTesting.demux(Conformance.fetch(name, url, sha256));
            var avidConfig = stream.track().codecConfig().orElseThrow();
            var avidFrame = stream.packets().getFirst().data();
            var width = stream.track().width();
            var height = stream.track().height();
            var encoder = new DnxhdEncoder(width, height, options.toBuilder().alpha(true).alphaStorage(storage).build());
            assertEquals(u32(avidFrame, 0x28), encoder.compressionId(), name);
            var config = encoder.codecConfig().orElseThrow();
            var atom = Arrays.copyOfRange(config, 4, config.length);
            var text = new String(avidConfig, StandardCharsets.ISO_8859_1);
            var at = text.indexOf("AALPAALP");
            assertTrue(at > 4, name + " carries an AALP atom");
            assertArrayEquals(Arrays.copyOfRange(avidConfig, at - 4, at + 24), atom, name + ": the AALP atom");
            var frame = encode(encoder, AlphaPlanes.synthetic(width, height, ChromaFormat.YUV422,
                    encoder.bitDepth()).toPicture()).data();
            assertEquals(avidFrame.length, frame.length, name + ": the frame size");
            assertEquals(u32(avidFrame, avidFrame.length - 4), u32(frame, frame.length - 4), name + ": the video size");
        }

        @Test
        @DisplayName("keeps Avid's sample description extensions when remuxing an Avid file to QuickTime")
        void remux(@TempDir Path directory) throws Exception {
            var stream = IntraTesting.demux(Conformance.fetch("ffmpeg-trac-8427-dnxhd_with_alpha_straight.mov",
                    "https://trac.ffmpeg.org/raw-attachment/ticket/8427/dnxhd_with_alpha_straight.mov",
                    "10d46a0f07ac9ec5093dc7deac30446b5c42d86685e6bbc475ff05c570fb71a4"));
            var config = stream.track().codecConfig().orElseThrow();
            var text = new String(config, StandardCharsets.ISO_8859_1);
            assertTrue(text.startsWith("AVdh") && text.contains("ACLRACLR0001") && text.contains("AALPAALP0001")
                    && text.contains("ADHR0001"), "the demuxer keeps the Avid extensions");
            var file = directory.resolve("remux.mov");
            try (var muxer = new Mp4Muxer(MediaSink.of(file), Mp4MuxerOptions.quickTime())) {
                muxer.addTrack(stream.track().toBuilder().id(1).build());
                for (var packet : stream.packets()) {
                    muxer.writePacket(packet.withTrackId(1));
                }
                muxer.finish();
            }
            var bytes = Files.readAllBytes(file);
            var entry = indexOf(bytes, "AVdh".getBytes(StandardCharsets.US_ASCII)) - 4;
            assertEquals(32, (bytes[entry + 82] & 0xFF) << 8 | bytes[entry + 83] & 0xFF, "sample entry depth");
            var extensions = Arrays.copyOfRange(config, 4, config.length);
            assertArrayEquals(extensions, Arrays.copyOfRange(bytes, entry + 86, entry + 86 + extensions.length),
                    "the extensions follow the color table ID");
            var remuxed = IntraTesting.demux(file);
            assertArrayEquals(config, remuxed.track().codecConfig().orElseThrow());
            var first = remuxed.packets().getFirst().data();
            assertArrayEquals(stream.packets().getFirst().data(), first);
            assertArrayEquals(alpha(new DnxhdDecoder().decodeFrame(stream.packets().getFirst().data(), 0, first.length)),
                    alpha(new DnxhdDecoder(remuxed.track()).decodeFrame(first, 0, first.length)));
        }
    }

    static int indexOf(byte[] data, byte[] pattern) {
        return DnxhdAlphaTest.InBand.indexOf(data, pattern);
    }

    @Nested
    @DisplayName("in containers")
    class Containers {
        static Stream<Arguments> storages() {
            return Stream.of(Arguments.of(AlphaStorage.AVID_UNCOMPRESSED), Arguments.of(AlphaStorage.AVID_CODED));
        }

        static List<Packet> packets(DnxhdEncoder encoder, Picture picture) {
            return DnxhdEncoderTest.encode(List.of(picture, picture), encoder);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("storages")
        @DisplayName("writes QuickTime files that ffmpeg decodes and ffprobe describes as DNxHR, and Smalt reads back")
        void quickTime(AlphaStorage storage, @TempDir Path directory) throws Exception {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var picture = AlphaPlanes.synthetic(320, 240, ChromaFormat.YUV422, 8).toPicture();
            var encoder = new DnxhdEncoder(320, 240, DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQ).toBuilder()
                    .alpha(true).alphaStorage(storage).frameRate(DnxhdEncoderTest.FRAME_RATE).build());
            var packets = packets(encoder, picture);
            var file = DnxhdEncoderTest.mux(packets, encoder, directory.resolve("avid.mov"));
            var bytes = Files.readAllBytes(file);
            var entry = indexOf(bytes, "AVdh".getBytes(StandardCharsets.US_ASCII)) - 4;
            assertEquals(32, (bytes[entry + 82] & 0xFF) << 8 | bytes[entry + 83] & 0xFF, "sample entry depth");
            var config = encoder.codecConfig().orElseThrow();
            assertArrayEquals(Arrays.copyOfRange(config, 4, config.length),
                    Arrays.copyOfRange(bytes, entry + 86, entry + 86 + config.length - 4), "the AALP atom");
            Oracle.assertDecodesCleanly(file);
            var probe = Oracle.probe(file);
            assertTrue(probe.contains("\"codec_name\": \"dnxhd\"") && probe.contains("\"codec_tag_string\": \"AVdh\"")
                    && probe.contains("\"width\": 320") && probe.contains("\"height\": 240")
                    && probe.contains("\"profile\": \"DNXHR HQ\""), probe);
            // ffmpeg decodes the video coding units and ignores what follows them
            var ffmpeg = IntraTesting.ffmpegDecode(file, "yuv422p", 1);
            var ours = IntraTesting.raw(new DnxhdDecoder().decodeFrame(packets.getFirst().data(), 0,
                    packets.getFirst().size()), false);
            var psnr = IntraTesting.psnr(ours, ffmpeg, false, 255);
            assertTrue(psnr > 40, "ffmpeg against Smalt " + psnr);
            var stream = IntraTesting.demux(file);
            assertArrayEquals(config, stream.track().codecConfig().orElseThrow(), "the configuration reads back");
            assertEquals(packets.size(), stream.packets().size());
            for (var i = 0; i < packets.size(); i++) {
                assertArrayEquals(packets.get(i).data(), stream.packets().get(i).data(), "packet " + i);
            }
            var decoded = new DnxhdDecoder(stream.track()).decodeFrame(stream.packets().getFirst().data(), 0,
                    stream.packets().getFirst().size());
            var psnrAlpha = psnr(AlphaPlanes.of(picture).a(), alpha(decoded), 255);
            assertTrue(psnrAlpha > (storage == AlphaStorage.AVID_UNCOMPRESSED ? 1000 : 40), "alpha " + psnrAlpha);
        }

        @Test
        @DisplayName("decodes the alpha coding units with ffmpeg as the alpha")
        void ffmpegReadsCodedAlpha(@TempDir Path directory) throws Exception {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var planes = AlphaPlanes.synthetic(1920, 1080, ChromaFormat.YUV422, 8);
            var encoder = new DnxhdEncoder(1920, 1080, DnxhdEncoderOptions.of(DnxhdProfile.DNXHD).toBuilder()
                    .compressionId(1238).alpha(true).alphaStorage(AlphaStorage.AVID_CODED).build());
            var frame = encode(encoder, planes.toPicture()).data();
            var videoSize = u32(frame, frame.length - 4);
            var unit = directory.resolve("alpha.dnxhd");
            Files.write(unit, Arrays.copyOfRange(frame, videoSize, frame.length - 4));
            var gray = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-f", "dnxhd",
                    "-i", unit.toString(), "-f", "rawvideo", "-pix_fmt", "gray", "-"));
            var luma = new int[1920 * 1080];
            for (var i = 0; i < luma.length; i++) {
                luma[i] = gray[i] & 0xFF;
            }
            var psnr = psnr(planes.a(), luma, 255);
            // ffmpeg reconstructs AC levels differently (see DnxhdDifferentialTest), hence the margin
            assertTrue(psnr > 35, "ffmpeg's luma of the alpha coding unit against the alpha " + psnr);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("storages")
        @DisplayName("writes Matroska files whose sample description carries the AALP atom and a 32-bit depth")
        void matroska(AlphaStorage storage, @TempDir Path directory) throws Exception {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var picture = AlphaPlanes.synthetic(320, 240, ChromaFormat.YUV422, 8).toPicture();
            var encoder = new DnxhdEncoder(320, 240, DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQ).toBuilder()
                    .alpha(true).alphaStorage(storage).build());
            var packets = packets(encoder, picture);
            var file = directory.resolve("avid.mkv");
            try (var muxer = new MatroskaMuxer(MediaSink.of(file), MatroskaMuxerOptions.matroska())) {
                muxer.addTrack(VideoTrack.builder().id(1).codec(Codec.DNXHD).timeBase(encoder.timeBase())
                        .codecConfig(encoder.codecConfig().orElseThrow()).width(320).height(240).build());
                for (var packet : packets) {
                    muxer.writePacket(packet.withTrackId(1));
                }
                muxer.finish();
            }
            var bytes = Files.readAllBytes(file);
            var entry = indexOf(bytes, "AVdh".getBytes(StandardCharsets.US_ASCII)) - 4;
            assertEquals(32, (bytes[entry + 82] & 0xFF) << 8 | bytes[entry + 83] & 0xFF, "sample description depth");
            var config = encoder.codecConfig().orElseThrow();
            assertArrayEquals(Arrays.copyOfRange(config, 4, config.length),
                    Arrays.copyOfRange(bytes, entry + 86, entry + 86 + config.length - 4), "the AALP atom");
            Oracle.assertDecodesCleanly(file);
            assertTrue(Oracle.probe(file).contains("\"codec_tag_string\": \"AVdh\""));
            var stream = IntraTesting.demux(file);
            assertArrayEquals(encoder.codecConfig().orElseThrow(), stream.track().codecConfig().orElseThrow());
            var decoded = new DnxhdDecoder(stream.track()).decodeFrame(stream.packets().getFirst().data(), 0,
                    stream.packets().getFirst().size());
            assertTrue(psnr(AlphaPlanes.of(picture).a(), alpha(decoded), 255) > 40);
        }
    }
}
