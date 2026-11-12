package com.github.auties00.smalt.video;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.testkit.DolbyVisionReference;
import com.github.auties00.smalt.testkit.DolbyVisionRpus;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DolbyVision;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.av1.Av1Encoder;
import com.github.auties00.smalt.video.av1.Av1EncoderOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers profile 10.0 end to end, the AV1 Dolby Vision profile without a backward compatible base layer: a Smalt-encoded
 * 10-bit stream, the RPUs of known coefficients in ITU-T T.35 metadata OBUs as the dolby_vision crate of dovi_tool writes
 * them, decoded through {@link VideoDecoders} and compared with the literal implementation of ETSI GS CCM 001 over the
 * stream's base layer.
 */
@DisplayName("Dolby Vision reconstruction of AV1 streams")
class Av1DolbyVisionTest {
    private static final VideoDecoderOptions HIGH = VideoDecoderOptions.builder().highBitDepth(true).build();
    private static final VideoDecoderOptions BASE = HIGH.toBuilder().dolbyVisionBaseLayer(true).build();

    private static DolbyVisionReference.Mapping mapping(int id, int variant) {
        var one = 1L << 23;
        var regression = new long[8];
        regression[0] = one / 128;
        regression[2] = one - one / 64;
        regression[4] = one / 32;
        var regressionCr = new long[8];
        regressionCr[0] = -one / 128;
        regressionCr[3] = one;
        regressionCr[6] = one / 16;
        return new DolbyVisionReference.Mapping(id, 23, new DolbyVisionReference.Curve[]{
                new DolbyVisionReference.Curve(new int[]{0, 400, 1023}, new DolbyVisionReference.Piece[]{
                        new DolbyVisionReference.Piece(false, 1, new long[]{variant * one / 512, one}),
                        new DolbyVisionReference.Piece(false, 2, new long[]{0, one + one / 16, -one / 16})}),
                new DolbyVisionReference.Curve(new int[]{0, 1023}, new DolbyVisionReference.Piece[]{
                        new DolbyVisionReference.Piece(true, 1, regression)}),
                new DolbyVisionReference.Curve(new int[]{0, 1023}, new DolbyVisionReference.Piece[]{
                        new DolbyVisionReference.Piece(true, 1, regressionCr)})});
    }

    private record Stream(byte[] config, List<Packet> packets) {
    }

    private static Stream encode(int frames) {
        var color = new ColorInfo(MatrixCoefficients.UNSPECIFIED, ColorRange.FULL, ColorPrimaries.UNSPECIFIED,
                TransferCharacteristics.UNSPECIFIED);
        var packets = new ArrayList<Packet>();
        try (var encoder = new Av1Encoder(64, 48, Av1EncoderOptions.builder().timeBase(new Rational(1, 25)).pictureFormat(ChromaFormat.YUV420, 10).build())) {
            for (var frame = 0; frame < frames; frame++) {
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
            return new Stream(encoder.codecConfig().orElseThrow(), packets);
        }
    }

    /** Puts a metadata OBU after the temporal delimiter of a temporal unit. */
    private static Packet withRpu(Packet packet, byte[] obu) {
        var data = packet.data();
        var out = new ByteArrayOutputStream();
        out.write(data, 0, 2);
        out.writeBytes(obu);
        out.write(data, 2, data.length - 2);
        return packet.withData(out.toByteArray());
    }

    private static VideoTrack track(byte[] config, DolbyVision dolbyVision) {
        var builder = VideoTrack.builder().id(1).codec(Codec.AV1).timeBase(new Rational(1, 25)).width(64).height(48)
                .codecConfig(config);
        return dolbyVision == null ? builder.build() : builder.dolbyVision(dolbyVision).build();
    }

    private static int[] ints(short[] plane, int stride, int width, int height) {
        var out = new int[width * height];
        for (var row = 0; row < height; row++) {
            for (var x = 0; x < width; x++) {
                out[row * width + x] = plane[row * stride + x] & 0xFFFF;
            }
        }
        return out;
    }

    private static List<int[][]> decode(VideoDecoder decoder, List<Packet> packets) {
        var frames = new ArrayList<int[][]>();
        Consumer<VideoFrame> sink = frame -> {
            var picture = (Yuv16Picture) frame.picture();
            var width = picture.chromaFormat() == ChromaFormat.YUV420 ? picture.chromaWidth() : picture.width();
            var height = picture.chromaFormat() == ChromaFormat.YUV420 ? picture.chromaHeight() : picture.height();
            frames.add(new int[][]{ints(picture.y(), picture.yStride(), picture.width(), picture.height()),
                    ints(picture.u(), picture.uvStride(), width, height), ints(picture.v(), picture.uvStride(), width, height),
                    {picture.bitDepth()}});
        };
        try (decoder) {
            for (var packet : packets) {
                decoder.decode(packet, sink);
            }
            decoder.flush(sink);
        }
        return frames;
    }

    private static int[] codes(double[] values, int scale) {
        var out = new int[values.length];
        for (var index = 0; index < values.length; index++) {
            out[index] = (int) Math.round(values[index] * scale);
        }
        return out;
    }

    private static void assertReconstructed(VideoTrack track) {
        var stream = encode(4);
        var packets = new ArrayList<Packet>();
        for (var index = 0; index < stream.packets().size(); index++) {
            packets.add(withRpu(stream.packets().get(index), DolbyVisionRpus.of(DolbyVisionRpus.Kind.PROFILE_5)
                    .mapping(mapping(index % 2, index)).iptPqC2().av1Obu()));
        }
        var bases = decode(VideoDecoders.create(track, BASE), packets);
        var outputs = decode(VideoDecoders.create(track, HIGH), packets);
        assertEquals(4, bases.size());
        assertEquals(4, outputs.size());
        var tolerance = Tolerance.maxAbs(1);
        for (var index = 0; index < outputs.size(); index++) {
            var base = bases.get(index);
            var planes = new DolbyVisionReference.Planes(64, 48, base[0], base[1], base[2]);
            var composed = DolbyVisionReference.compose(planes, mapping(index % 2, index), 12);
            var rgb = DolbyVisionReference.toRgb(composed, 12, DolbyVisionRpus.IPT_PQ_C2_YCC_TO_RGB,
                    DolbyVisionRpus.IPT_PQ_C2_OFFSET, DolbyVisionRpus.IPT_PQ_C2_RGB_TO_LMS);
            var output = outputs.get(index);
            assertEquals(12, output[3][0]);
            tolerance.assertWithin("green of picture " + index, codes(rgb[0], 4095), output[0]);
            tolerance.assertWithin("blue of picture " + index, codes(rgb[1], 4095), output[1]);
            tolerance.assertWithin("red of picture " + index, codes(rgb[2], 4095), output[2]);
        }
    }

    @Test
    @DisplayName("reconstructs a declared profile 10.0 stream from the RPUs of its metadata OBUs")
    void declared() {
        var config = encode(1).config();
        assertReconstructed(track(config, DolbyVision.of(10, 1, 0)));
    }

    @Test
    @DisplayName("reconstructs a stream that declares nothing from its first RPU of profile 5")
    void inBand() {
        assertReconstructed(track(encode(1).config(), null));
    }

    @Test
    @DisplayName("decodes a track without RPUs that declares nothing as plain video, in 8 bits by default")
    void plain() {
        var stream = encode(2);
        var track = track(stream.config(), null);
        var frames = new ArrayList<Integer>();
        try (var decoder = VideoDecoders.create(track)) {
            Consumer<VideoFrame> sink = frame -> frames.add(frame.picture().bitDepth());
            for (var packet : stream.packets()) {
                decoder.decode(packet, sink);
            }
            decoder.flush(sink);
        }
        assertEquals(List.of(8, 8), frames);
    }

    @Test
    @DisplayName("decodes the base layer of a declared stream when the options ask for it")
    void baseLayerOption() {
        var stream = encode(1);
        var options = VideoDecoderOptions.builder().dolbyVisionBaseLayer(true).highBitDepth(true).build();
        var pictures = new ArrayList<Integer>();
        try (var decoder = VideoDecoders.create(track(stream.config(), DolbyVision.of(10, 1, 0)), options)) {
            Consumer<VideoFrame> sink = frame -> {
                var picture = (com.github.auties00.smalt.image.Yuv16Picture) frame.picture();
                assertEquals(com.github.auties00.smalt.image.ChromaFormat.YUV420, picture.chromaFormat());
                pictures.add(picture.bitDepth());
            };
            for (var packet : stream.packets()) {
                decoder.decode(packet, sink);
            }
            decoder.flush(sink);
        }
        assertEquals(List.of(10), pictures);
    }

    @Test
    @DisplayName("refuses a declared stream that names an enhancement layer")
    void enhancementLayer() {
        var config = encode(1).config();
        var withLayer = new DolbyVision(1, 0, 10, 1, true, true, true, 0, 0);
        assertThrows(MediaException.Unsupported.class, () -> VideoDecoders.create(track(config, withLayer)));
    }
}
