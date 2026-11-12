package com.github.auties00.smalt.video.hevc;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.internal.nal.DolbyVisionRpu;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.raw.AnnexBDemuxer;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.DolbyVisionReference;
import com.github.auties00.smalt.testkit.DolbyVisionRpus;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DolbyVision;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.hevc.encoder.HevcEncoder;
import com.github.auties00.smalt.video.hevc.encoder.HevcEncoderOptions;
import com.github.auties00.smalt.video.hevc.encoder.HevcProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the HEVC decoder on Dolby Vision streams, the samples of core's {@code make-dolby-vision-samples.py} and
 * dovi_tool's mkvmerge sample: a backward compatible profile 8.4 stream decodes to the same pictures as its base layer
 * without RPUs, while a profile 5 stream, declared by its container or only by its in-band RPUs, is reconstructed by
 * {@link VideoDecoders} from its RPUs unless the options ask for its base layer, and refused by the decoder itself.
 * The reconstruction is compared with the literal implementation of ETSI GS CCM 001 in the test kit on Smalt-encoded
 * streams with known coefficients, and with what libplacebo (ffmpeg's filter, run as a black box) makes of the committed
 * stream of {@code tools/testdata/behavior/make-dolby-vision-reconstruction.py}, which also holds real RPUs of dovi_tool.
 */
@DisplayName("HEVC decoding of Dolby Vision streams")
class HevcDolbyVisionTest {
    private static final Path SAMPLES = Path.of("..", "core", "src", "test", "resources", "samples");

    private record Stream(VideoTrack track, List<Packet> packets) {
    }

    private static Stream read(String name) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(Files.readAllBytes(SAMPLES.resolve(name))))) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
            return new Stream(track, packets);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Returns the access units without their NAL units of type 62. */
    private static List<Packet> withoutRpus(List<Packet> packets) {
        return packets.stream().map(packet -> packet.withData(filter(packet.data()))).toList();
    }

    private static byte[] filter(byte[] accessUnit) {
        var out = new ByteArrayOutputStream();
        NalUnits.forEachLengthPrefixed(accessUnit, 4, (data, offset, length) -> {
            if (length > 0 && NalUnits.type(data[offset], true) != 62) {
                out.write(length >>> 24);
                out.write(length >>> 16);
                out.write(length >>> 8);
                out.write(length);
                out.write(data, offset, length);
            }
        });
        return out.toByteArray();
    }

    private static List<String> decode(VideoDecoder decoder, List<Packet> packets) {
        var hashes = new ArrayList<String>();
        Consumer<VideoFrame> sink = frame -> {
            var digest = HevcTestStreams.md5();
            HevcTestStreams.hash(digest, frame.picture());
            hashes.add(HexFormat.of().formatHex(digest.digest()));
        };
        try (decoder) {
            for (var packet : packets) {
                decoder.decode(packet, sink);
            }
            decoder.flush(sink);
        }
        return hashes;
    }

    private static List<String> baseLayer(Stream stream) {
        return decode(new HevcDecoder(stream.track().toBuilder().noDolbyVision().build()), withoutRpus(stream.packets()));
    }

    @Test
    @DisplayName("decodes a profile 8.4 stream to the pictures of its base layer, its RPUs ignored")
    void compatible() {
        var stream = read("mp4/dolby-vision-p84.mp4");
        assertEquals(DolbyVision.of(8, 1, 4), stream.track().dolbyVision().orElseThrow());
        var expected = baseLayer(stream);
        assertEquals(6, expected.size());
        assertEquals(expected, decode(VideoDecoders.create(stream.track()), stream.packets()));
        // without the container's configuration, the in-band profile 8 RPUs are checked and accepted
        assertEquals(expected, decode(new HevcDecoder(stream.track().toBuilder().noDolbyVision().build()),
                stream.packets()));
        assertEquals(expected, decode(VideoDecoders.create(stream.track().toBuilder().noDolbyVision().build()),
                stream.packets()));
    }

    @Test
    @DisplayName("decodes dovi_tool's mkvmerge profile 8.1 sample to the pictures of its base layer")
    void mkvmerge() throws IOException {
        Stream stream;
        try (var demuxer = Containers.openDemuxer(MediaSource.of(Files.readAllBytes(
                com.github.auties00.smalt.testkit.Conformance.fetchListed("dovi-tool-regular.mkv"))))) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                if (packet.trackId() == track.id()) {
                    packets.add(packet);
                }
            }
            stream = new Stream(track, packets);
        }
        assertEquals(DolbyVision.of(8, 1, 1), stream.track().dolbyVision().orElseThrow());
        var expected = baseLayer(stream);
        assertEquals(259, expected.size());
        assertEquals(expected, decode(VideoDecoders.create(stream.track()), stream.packets()));
        assertEquals(expected, decode(new HevcDecoder(stream.track().toBuilder().noDolbyVision().build()),
                stream.packets()));
    }

    @Nested
    @DisplayName("profile 5")
    class Profile5 {
        @Test
        @DisplayName("is reconstructed when the container declares it, and refused by the decoder itself")
        void declared() {
            var stream = read("mp4/dolby-vision-p5.mp4");
            assertFalse(stream.track().dolbyVision().orElseThrow().isBackwardCompatible());
            assertEquals(6, assertReconstructed(stream.track(), stream.packets()));
            var refused = assertThrows(MediaException.Unsupported.class, () -> new HevcDecoder(stream.track()));
            assertEquals("dolby vision reconstruction", refused.format());
        }

        @Test
        @DisplayName("is reconstructed from its first RPU when only the bitstream declares it")
        void inBandReconstructed() {
            var stream = read("mp4/dolby-vision-p5.mp4");
            var plain = stream.track().toBuilder().noDolbyVision().build();
            assertEquals(6, assertReconstructed(plain, stream.packets()));
            assertEquals(hashes(VideoDecoders.create(stream.track(), HIGH), stream.packets()),
                    hashes(VideoDecoders.create(plain, HIGH), stream.packets()));
        }

        @Test
        @DisplayName("gives the same pictures in 8 and in 12 bits, and with any thread count")
        void depths() {
            var stream = read("mp4/dolby-vision-p5.mp4");
            var serial = hashes(VideoDecoders.create(stream.track(), HIGH.toBuilder().threads(1).build()), stream.packets());
            assertEquals(serial, hashes(VideoDecoders.create(stream.track(), HIGH.toBuilder().threads(4).build()),
                    stream.packets()));
            var frames = new ArrayList<Integer>();
            try (var decoder = VideoDecoders.create(stream.track())) {
                Consumer<VideoFrame> sink = frame -> frames.add(frame.picture().bitDepth());
                for (var packet : stream.packets()) {
                    decoder.decode(packet, sink);
                }
                decoder.flush(sink);
            }
            assertEquals(List.of(8, 8, 8, 8, 8, 8), frames);
        }

        @Test
        @DisplayName("is reconstructed through VideoReader, also after a seek back to the start")
        void reader() throws IOException {
            var bytes = Files.readAllBytes(SAMPLES.resolve("mp4/dolby-vision-p5.mp4"));
            try (var reader = com.github.auties00.smalt.video.VideoReader.open(MediaSource.of(bytes))) {
                var first = new ArrayList<Long>();
                for (var frame = reader.read(); frame != null; frame = reader.read()) {
                    var picture = assertInstanceOf(com.github.auties00.smalt.image.YuvPicture.class, frame.picture());
                    assertEquals(ChromaFormat.YUV444, picture.chromaFormat());
                    assertEquals(MatrixCoefficients.IDENTITY, picture.color().matrix());
                    first.add(frame.pts());
                }
                assertEquals(6, first.size());
                reader.seek(0);
                var second = new ArrayList<Long>();
                for (var frame = reader.read(); frame != null; frame = reader.read()) {
                    second.add(frame.pts());
                }
                assertEquals(first, second);
            }
        }

        @Test
        @DisplayName("transcodes to a stream of its reconstructed colors that no longer claims to be Dolby Vision")
        void transcode() throws IOException {
            var bytes = Files.readAllBytes(SAMPLES.resolve("mp4/dolby-vision-p5.mp4"));
            var output = com.github.auties00.smalt.io.MediaSink.memory();
            com.github.auties00.smalt.transcode.Transcoder.transcode(MediaSource.of(bytes), output,
                    com.github.auties00.smalt.transcode.TranscodeOptions.builder()
                            .container(com.github.auties00.smalt.transcode.OutputContainer.MP4)
                            .video(com.github.auties00.smalt.transcode.VideoTranscodeOptions.encode(Codec.HEVC)).build());
            var expected = new ArrayList<byte[]>();
            try (var reader = com.github.auties00.smalt.video.VideoReader.open(MediaSource.of(bytes))) {
                for (var frame = reader.read(); frame != null; frame = reader.read()) {
                    expected.add(greens((com.github.auties00.smalt.image.YuvPicture) frame.picture()));
                }
            }
            var frames = 0;
            try (var reader = com.github.auties00.smalt.video.VideoReader.open(MediaSource.of(output.toByteArray()))) {
                assertFalse(reader.track().dolbyVision().isPresent());
                for (var frame = reader.read(); frame != null; frame = reader.read()) {
                    var picture = assertInstanceOf(com.github.auties00.smalt.image.YuvPicture.class, frame.picture());
                    assertEquals(com.github.auties00.smalt.image.TransferCharacteristics.PQ, picture.color().transfer());
                    assertEquals(MatrixCoefficients.IDENTITY, picture.color().matrix());
                    assertEquals(ChromaFormat.YUV444, picture.chromaFormat());
                    var actual = greens(picture);
                    var difference = 0L;
                    for (var index = 0; index < actual.length; index++) {
                        difference += Math.abs((actual[index] & 0xFF) - (expected.get(frames)[index] & 0xFF));
                    }
                    assertTrue(difference <= 8L * actual.length, "mean difference " + difference / actual.length);
                    frames++;
                }
            }
            assertEquals(6, frames);
        }

        @Test
        @DisplayName("decodes to its base layer when the options ask for it")
        void baseLayerOption() {
            var stream = read("mp4/dolby-vision-p5.mp4");
            var expected = baseLayer(stream);
            assertEquals(6, expected.size());
            var options = VideoDecoderOptions.builder().dolbyVisionBaseLayer(true).build();
            assertEquals(expected, decode(VideoDecoders.create(stream.track(), options), stream.packets()));
            assertEquals(expected, decode(new HevcDecoder(stream.track(), HevcDecoderOptions.builder()
                    .dolbyVisionBaseLayer(true).build()), stream.packets()));
            assertEquals(expected, decode(new HevcDecoder(stream.track().toBuilder().noDolbyVision().build(),
                    HevcDecoderOptions.builder().dolbyVisionBaseLayer(true).build()), stream.packets()));
        }

        @Test
        @DisplayName("is refused by the decoder itself at its first RPU when only the bitstream declares it")
        void inBand() {
            var stream = read("mp4/dolby-vision-p5.mp4");
            var plain = stream.track().toBuilder().noDolbyVision().build();
            try (var decoder = new HevcDecoder(plain)) {
                var frames = new ArrayList<VideoFrame>();
                var first = assertThrows(MediaException.Unsupported.class,
                        () -> decoder.decode(stream.packets().getFirst(), frames::add));
                assertEquals("dolby vision reconstruction", first.format());
                assertThrows(MediaException.Unsupported.class, () -> decoder.decode(stream.packets().get(1), frames::add));
                assertTrue(frames.isEmpty());
            }
        }

        @Test
        @DisplayName("is reconstructed in a raw Annex B stream, which declares nothing")
        void annexB() {
            var stream = read("mp4/dolby-vision-p5.mp4");
            var annexB = new ByteArrayOutputStream();
            var record = com.github.auties00.smalt.internal.nal.NalRecords.parseHevcStrict(
                    stream.track().codecConfig().orElseThrow());
            for (var set : record.parameterSets()) {
                annexB.writeBytes(new byte[]{0, 0, 0, 1});
                annexB.writeBytes(set);
            }
            for (var packet : stream.packets()) {
                NalUnits.forEachLengthPrefixed(packet.data(), 4, (data, offset, length) -> {
                    annexB.writeBytes(new byte[]{0, 0, 0, 1});
                    annexB.write(data, offset, length);
                });
            }
            try (var demuxer = new AnnexBDemuxer(MediaSource.of(annexB.toByteArray()))) {
                var track = (VideoTrack) demuxer.tracks().getFirst();
                assertFalse(track.dolbyVision().isPresent());
                var packets = new ArrayList<Packet>();
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    packets.add(packet);
                }
                assertEquals(hashes(VideoDecoders.create(stream.track(), HIGH), stream.packets()),
                        hashes(VideoDecoders.create(track, HIGH), packets));
            }
        }

        @Test
        @DisplayName("decodes when its RPUs are damaged, since only a valid RPU declares the profile")
        void damagedRpus() {
            var stream = read("mp4/dolby-vision-p5.mp4");
            var damaged = stream.packets().stream().map(packet -> {
                var data = packet.data().clone();
                // the byte before the 0x80 terminator is the last CRC byte of the RPU, the last NAL unit
                data[data.length - 2] ^= 0x5A;
                assertTrue(DolbyVisionRpus.rpuNals(data).size() == 1);
                return packet.withData(data);
            }).toList();
            var plain = stream.track().toBuilder().noDolbyVision().build();
            assertEquals(baseLayer(stream), decode(new HevcDecoder(plain), damaged));
            assertEquals(baseLayer(stream), decode(VideoDecoders.create(plain), damaged));
        }

        @Test
        @DisplayName("is reconstructed exactly as the equations say from a Smalt-encoded stream of known coefficients")
        void smaltEncoded() {
            var options = HevcEncoderOptions.builder().profile(HevcProfile.MAIN_10).bitDepth(10).build();
            var color = new ColorInfo(MatrixCoefficients.UNSPECIFIED, ColorRange.FULL, ColorPrimaries.UNSPECIFIED,
                    TransferCharacteristics.UNSPECIFIED);
            var packets = new ArrayList<Packet>();
            byte[] config;
            try (var encoder = new HevcEncoder(64, 48, options)) {
                for (var frame = 0; frame < 5; frame++) {
                    var picture = Yuv16Picture.allocate(64, 48, ChromaFormat.YUV420, 10, false, color);
                    for (var row = 0; row < 48; row++) {
                        for (var x = 0; x < 64; x++) {
                            picture.y()[row * picture.yStride() + x] = (short) (200 + 8 * x + 2 * row + 20 * frame);
                        }
                    }
                    for (var row = 0; row < 24; row++) {
                        for (var x = 0; x < 32; x++) {
                            picture.u()[row * picture.uvStride() + x] = (short) (400 + 6 * x + frame);
                            picture.v()[row * picture.uvStride() + x] = (short) (600 - 5 * row);
                        }
                    }
                    encoder.encode(new VideoFrame(picture, frame, 1, frame == 0), packets::add);
                }
                encoder.flush(packets::add);
                config = encoder.codecConfig().orElseThrow();
            }
            var withRpus = new ArrayList<Packet>();
            for (var index = 0; index < packets.size(); index++) {
                var rpus = DolbyVisionRpus.of(DolbyVisionRpus.Kind.PROFILE_5).iptPqC2();
                if (index % 4 == 3) {
                    rpus.reusePrevious(index % 8 == 3 ? 0 : 1);
                } else {
                    rpus.mapping(reference(knownMapping(index % 2, index)));
                }
                withRpus.add(packets.get(index).withData(DolbyVisionRpus.appendNal(packets.get(index).data(),
                        rpus.hevcNal())));
            }
            var track = VideoTrack.builder().id(1).codec(Codec.HEVC).timeBase(new com.github.auties00.smalt.track.Rational(1, 25))
                    .width(64).height(48).codecConfig(config).dolbyVision(DolbyVision.of(5, 1, 0)).build();
            assertEquals(5, assertReconstructed(track, withRpus));
        }

        @Test
        @DisplayName("agrees with libplacebo within two 10-bit codes, on synthetic and on real RPUs")
        void libplacebo() throws IOException {
            var directory = Path.of("src", "test", "resources", "dolbyvision");
            com.github.auties00.smalt.testkit.io.BehaviorSamples.assertPinned(directory);
            byte[] raw;
            try (var input = new GZIPInputStream(Files.newInputStream(directory.resolve("profile5-libplacebo.gbr16.gz")))) {
                raw = input.readAllBytes();
            }
            try (var demuxer = new AnnexBDemuxer(MediaSource.of(Files.readAllBytes(directory.resolve("profile5.hevc"))))) {
                var track = (VideoTrack) demuxer.tracks().getFirst();
                var packets = new ArrayList<Packet>();
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    packets.add(packet);
                }
                var outputs = pictures(VideoDecoders.create(track, HIGH), packets);
                assertEquals(6, outputs.size());
                var width = outputs.getFirst().width();
                var height = outputs.getFirst().height();
                assertEquals(raw.length, 6 * width * height * 3 * 2);
                var everywhere = Tolerance.maxAbs(2);
                // the last picture is near black, where a difference of one code is most of the picture
                var bright = everywhere.and(Tolerance.rms(1));
                var at = 0;
                for (var frame = 0; frame < outputs.size(); frame++) {
                    var output = outputs.get(frame);
                    assertEquals(12, output.bitDepth());
                    var what = "frame " + frame + " ";
                    for (var plane = 0; plane < 3; plane++) {
                        var expected = new int[width * height];
                        var actual = new int[width * height];
                        var samples = plane == 0 ? output.g() : plane == 1 ? output.b() : output.r();
                        for (var index = 0; index < expected.length; index++) {
                            var sample = (raw[at] & 0xFF) | (raw[at + 1] & 0xFF) << 8;
                            at += 2;
                            expected[index] = (int) Math.round(sample * 1023.0 / 65535);
                            actual[index] = (int) Math.round(samples[index] * 1023.0 / 4095);
                        }
                        (frame == outputs.size() - 1 ? everywhere : bright).assertWithin(what + "GBR".charAt(plane),
                                expected, actual);
                    }
                }
            }
        }
    }

    /** Returns a copy of the green samples of an 8-bit identity picture. */
    private static byte[] greens(com.github.auties00.smalt.image.YuvPicture picture) {
        var out = new byte[picture.width() * picture.height()];
        for (var row = 0; row < picture.height(); row++) {
            System.arraycopy(picture.y(), row * picture.yStride(), out, row * picture.width(), picture.width());
        }
        return out;
    }

    private static final VideoDecoderOptions HIGH = VideoDecoderOptions.builder().highBitDepth(true).build();

    private static final VideoDecoderOptions BASE = HIGH.toBuilder().dolbyVisionBaseLayer(true).build();

    private record Rgb(long pts, int width, int height, int bitDepth, int[] g, int[] b, int[] r) {
    }

    /** Returns a mapping of polynomial luma and chroma of regressions of order 3 or polynomials, near the identity. */
    private static DolbyVisionRpu.Mapping knownMapping(int id, int variant) {
        var denominator = 1L << 23;
        var luma = new DolbyVisionRpu.Curve(new int[]{0, 512, 1023}, new DolbyVisionRpu.Piece[]{
                new DolbyVisionRpu.Piece(false, 2, new long[]{denominator / 256, denominator + variant, -denominator / 64}),
                new DolbyVisionRpu.Piece(false, 1, new long[]{-denominator / 512, denominator - 7})});
        DolbyVisionRpu.Piece cb;
        DolbyVisionRpu.Piece cr;
        if (id == 0) {
            cb = new DolbyVisionRpu.Piece(false, 1, new long[]{denominator / 32, 15 * denominator / 16});
            cr = new DolbyVisionRpu.Piece(false, 2, new long[]{0, denominator + denominator / 16, -denominator / 16});
        } else {
            var regression = new long[22];
            regression[0] = denominator / 128;
            regression[1] = -denominator / 64;
            regression[2] = denominator;
            regression[4] = denominator / 32;
            regression[7] = -denominator / 32;
            regression[9] = denominator / 64;
            regression[15] = denominator / 128;
            regression[21] = denominator / 256;
            cb = new DolbyVisionRpu.Piece(true, 3, regression);
            var other = regression.clone();
            other[2] = 0;
            other[3] = denominator;
            cr = new DolbyVisionRpu.Piece(true, 2, java.util.Arrays.copyOf(other, 15));
        }
        return new DolbyVisionRpu.Mapping(id, 23, false, new DolbyVisionRpu.Curve[]{luma,
                new DolbyVisionRpu.Curve(new int[]{0, 1023}, new DolbyVisionRpu.Piece[]{cb}),
                new DolbyVisionRpu.Curve(new int[]{0, 1023}, new DolbyVisionRpu.Piece[]{cr})});
    }

    private static DolbyVisionReference.Mapping reference(DolbyVisionRpu.Mapping mapping) {
        var curves = new DolbyVisionReference.Curve[3];
        for (var component = 0; component < 3; component++) {
            var pieces = mapping.curves()[component].pieces();
            var converted = new DolbyVisionReference.Piece[pieces.length];
            for (var index = 0; index < pieces.length; index++) {
                converted[index] = new DolbyVisionReference.Piece(pieces[index].mmr(), pieces[index].order(),
                        pieces[index].coefficients());
            }
            curves[component] = new DolbyVisionReference.Curve(mapping.curves()[component].pivots(), converted);
        }
        return new DolbyVisionReference.Mapping(mapping.rpuId(), mapping.denominatorBits(), curves);
    }

    private static List<Rgb> pictures(VideoDecoder decoder, List<Packet> packets) {
        var outputs = new ArrayList<Rgb>();
        Consumer<VideoFrame> sink = frame -> {
            var picture = (Yuv16Picture) frame.picture();
            assertEquals(ChromaFormat.YUV444, picture.chromaFormat());
            assertEquals(MatrixCoefficients.IDENTITY, picture.color().matrix());
            assertEquals(ColorRange.FULL, picture.color().range());
            assertEquals(ColorPrimaries.BT2020, picture.color().primaries());
            assertEquals(TransferCharacteristics.PQ, picture.color().transfer());
            var size = picture.width() * picture.height();
            var g = new int[size];
            var b = new int[size];
            var r = new int[size];
            for (var row = 0; row < picture.height(); row++) {
                for (var x = 0; x < picture.width(); x++) {
                    var at = row * picture.yStride() + x;
                    g[row * picture.width() + x] = picture.y()[at] & 0xFFFF;
                    b[row * picture.width() + x] = picture.u()[at] & 0xFFFF;
                    r[row * picture.width() + x] = picture.v()[at] & 0xFFFF;
                }
            }
            outputs.add(new Rgb(frame.pts(), picture.width(), picture.height(), picture.bitDepth(), g, b, r));
        };
        try (decoder) {
            for (var packet : packets) {
                decoder.decode(packet, sink);
            }
            decoder.flush(sink);
        }
        return outputs;
    }

    private static List<String> hashes(VideoDecoder decoder, List<Packet> packets) {
        return decode(decoder, packets);
    }

    /**
     * Decodes a stream through {@link VideoDecoders}, and compares each picture with the reference over the base layer
     * of the same stream and the mapping the packet's RPU, as an independent parse, names or reuses.
     *
     * @return the number of pictures
     */
    private static int assertReconstructed(VideoTrack track, List<Packet> packets) {
        var bases = new ArrayList<DolbyVisionReference.Planes>();
        Consumer<VideoFrame> sink = frame -> {
            var picture = (Yuv16Picture) frame.picture();
            var chromaWidth = picture.chromaWidth();
            var chromaHeight = picture.chromaHeight();
            var y = new int[picture.width() * picture.height()];
            var cb = new int[chromaWidth * chromaHeight];
            var cr = new int[chromaWidth * chromaHeight];
            for (var row = 0; row < picture.height(); row++) {
                for (var x = 0; x < picture.width(); x++) {
                    y[row * picture.width() + x] = picture.y()[row * picture.yStride() + x] & 0xFFFF;
                }
            }
            for (var row = 0; row < chromaHeight; row++) {
                for (var x = 0; x < chromaWidth; x++) {
                    cb[row * chromaWidth + x] = picture.u()[row * picture.uvStride() + x] & 0xFFFF;
                    cr[row * chromaWidth + x] = picture.v()[row * picture.uvStride() + x] & 0xFFFF;
                }
            }
            bases.add(new DolbyVisionReference.Planes(picture.width(), picture.height(), y, cb, cr));
        };
        try (var decoder = VideoDecoders.create(track, BASE)) {
            for (var packet : packets) {
                decoder.decode(packet, sink);
            }
            decoder.flush(sink);
        }
        var outputs = pictures(VideoDecoders.create(track, HIGH), packets);
        assertEquals(bases.size(), outputs.size());
        var mappings = new DolbyVisionRpu.Mapping[16];
        var rpus = new HashMap<Long, DolbyVisionRpu>();
        var resolved = new HashMap<Long, DolbyVisionRpu.Mapping>();
        for (var packet : packets) {
            var nal = DolbyVisionRpus.rpuNals(packet.data()).getFirst();
            var rpu = DolbyVisionRpu.tryParseHevcNal(nal, 0, nal.length);
            var mapping = rpu.mapping();
            if (mapping != null) {
                mappings[mapping.rpuId()] = mapping;
            } else {
                mapping = mappings[rpu.previousRpuId()];
            }
            rpus.put(packet.pts(), rpu);
            resolved.put(packet.pts(), mapping);
        }
        var tolerance = Tolerance.maxAbs(1);
        for (var index = 0; index < outputs.size(); index++) {
            var rpu = rpus.get(outputs.get(index).pts());
            var mapping = resolved.get(outputs.get(index).pts());
            var management = rpu.displayManagement();
            var composed = DolbyVisionReference.compose(bases.get(index), reference(mapping), rpu.vdrBitDepth());
            var rgb = DolbyVisionReference.toRgb(composed, rpu.vdrBitDepth(), management.yccToRgb(),
                    management.yccToRgbOffset(), management.rgbToLms());
            var output = outputs.get(index);
            assertEquals(rpu.vdrBitDepth(), output.bitDepth());
            var scale = (1 << output.bitDepth()) - 1;
            tolerance.assertWithin("green of picture " + index, codes(rgb[0], scale), output.g());
            tolerance.assertWithin("blue of picture " + index, codes(rgb[1], scale), output.b());
            tolerance.assertWithin("red of picture " + index, codes(rgb[2], scale), output.r());
        }
        return outputs.size();
    }

    private static int[] codes(double[] values, int scale) {
        var out = new int[values.length];
        for (var index = 0; index < values.length; index++) {
            out[index] = (int) Math.round(values[index] * scale);
        }
        return out;
    }
}
