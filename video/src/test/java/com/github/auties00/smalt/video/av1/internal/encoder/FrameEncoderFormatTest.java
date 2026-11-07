package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.av1.Av1Decoder;
import com.github.auties00.smalt.video.av1.Av1DecoderOptions;
import com.github.auties00.smalt.video.av1.internal.Av1Picture;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;
import com.github.auties00.smalt.video.vp9.internal.Vp9TestVideo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Drives the frame encoder over every chroma layout and bit depth, in the real-time and the
 * good-quality searches, and requires every frame to decode to exactly the encoder's
 * reconstruction: the references the encoder predicts from are the decoder's.
 */
class FrameEncoderFormatTest {
    static Stream<Arguments> formats() {
        var out = new ArrayList<Arguments>();
        for (var format : List.of(ChromaFormat.YUV420, ChromaFormat.YUV422, ChromaFormat.YUV444,
                ChromaFormat.MONOCHROME)) {
            for (var depth : List.of(8, 10, 12)) {
                for (var good : List.of(false, true)) {
                    out.add(Arguments.of(format, depth, good));
                }
            }
        }
        return out.stream();
    }

    /** Returns the samples of a decoded picture as ints, plane after plane. */
    static int[] samples(Picture p) {
        var out = new ArrayList<Integer>();
        if (p instanceof YuvPicture yuv) {
            var planes = yuv.chromaFormat().hasChroma() ? 3 : 1;
            for (var pl = 0; pl < planes; pl++) {
                var w = pl == 0 ? yuv.width() : yuv.chromaWidth();
                var h = pl == 0 ? yuv.height() : yuv.chromaHeight();
                var d = pl == 0 ? yuv.y() : pl == 1 ? yuv.u() : yuv.v();
                var s = pl == 0 ? yuv.yStride() : yuv.uvStride();
                for (var y = 0; y < h; y++) {
                    for (var x = 0; x < w; x++) {
                        out.add(d[y * s + x] & 0xFF);
                    }
                }
            }
        } else {
            var yuv = (Yuv16Picture) p;
            var planes = yuv.chromaFormat().hasChroma() ? 3 : 1;
            for (var pl = 0; pl < planes; pl++) {
                var w = pl == 0 ? yuv.width() : yuv.chromaWidth();
                var h = pl == 0 ? yuv.height() : yuv.chromaHeight();
                var d = pl == 0 ? yuv.y() : pl == 1 ? yuv.u() : yuv.v();
                var s = pl == 0 ? yuv.yStride() : yuv.uvStride();
                for (var y = 0; y < h; y++) {
                    for (var x = 0; x < w; x++) {
                        out.add((int) d[y * s + x]);
                    }
                }
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Returns the visible samples of a reconstruction as ints, plane after plane. */
    static int[] samples(Av1Picture recon, int width, int height) {
        var layout = recon.layout;
        var ssHor = layout == SequenceHeader.LAYOUT_I444 ? 0 : 1;
        var ssVer = layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var planes = layout == SequenceHeader.LAYOUT_I400 ? 1 : 3;
        var out = new ArrayList<Integer>();
        for (var pl = 0; pl < planes; pl++) {
            var w = pl == 0 ? width : (width + ssHor) >> ssHor;
            var h = pl == 0 ? height : (height + ssVer) >> ssVer;
            var s = recon.stride[pl != 0 ? 1 : 0];
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    out.add((int) recon.data[pl][y * s + x]);
                }
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    @ParameterizedTest(name = "{0} {1}-bit good {2}")
    @MethodSource("formats")
    @DisplayName("every frame decodes to the encoder's reconstruction")
    void bitExact(ChromaFormat format, int depth, boolean good) {
        var width = 136;
        var height = 72;
        var layout = SequenceHeader.layoutOf(format);
        var seq = good
                ? FrameEncoder.sequenceHeader(width, height, ColorInfo.BT709_LIMITED, 8, true, true, layout, depth)
                : FrameEncoder.sequenceHeader(width, height, ColorInfo.BT709_LIMITED, 8, 1, layout, depth);
        var fe = new FrameEncoder(width, height, seq, MediaLimits.DEFAULT);
        var units = new ArrayList<byte[]>();
        var recons = new ArrayList<int[]>();
        var pictures = Vp9TestVideo.synthesize(width, height, 6);
        for (var i = 0; i < pictures.size(); i++) {
            Picture source = depth == 8 ? ImageOps.toYuv(pictures.get(i), format, ColorInfo.BT709_LIMITED)
                    : ImageOps.toYuv16(pictures.get(i), format, ColorInfo.BT709_LIMITED, depth);
            var p = new FrameParams();
            p.key = i == 0;
            p.orderHint = i;
            p.qindex = 90 + 10 * (i % 3);
            p.refreshFlags = 1 << (i % 3);
            for (var r = 0; r < 7; r++) {
                p.refIdx[r] = (i + 2 - r % 3) % 3;
            }
            p.refSearch = good ? 0x7F : 1 | 1 << 3 | 1 << 6;
            p.allowHighPrecisionMv = true;
            p.interpFilter = i == 0 ? 0 : com.github.auties00.smalt.video.av1.internal.FrameHeader.FILTER_SWITCHABLE;
            p.txSwitchable = i > 0;
            p.goodSpeed = good && i > 0 ? 4 : 0;
            p.referenceSelect = good && i > 0;
            p.skipMode = good && i > 0;
            p.warpedMotion = good && i > 0;
            p.refFrameMvs = good && i > 0;
            p.loopFilterSearch = good && i > 0;
            p.cdefSearch = good && i > 0 ? 2 : 0;
            Av1RtEncoder.filterModel(p, p.key, p.qindex, depth);
            fe.load(source);
            units.add(fe.encode(p));
            recons.add(samples(fe.reference(i == 0 ? 0 : Integer.numberOfTrailingZeros(p.refreshFlags)), width,
                    height));
        }
        fe.close();
        var track = VideoTrack.builder().id(1).codec(Codec.AV1).timeBase(Rational.MICROSECONDS).width(width)
                .height(height).build();
        var decoded = new ArrayList<int[]>();
        try (var decoder = new Av1Decoder(track, Av1DecoderOptions.builder().highBitDepth(true).build())) {
            for (var i = 0; i < units.size(); i++) {
                decoder.decode(new Packet(1, units.get(i), i, i, 1, false), frame -> decoded.add(samples(frame.picture())));
            }
        }
        assertEquals(units.size(), decoded.size(), "frames");
        for (var i = 0; i < decoded.size(); i++) {
            assertArrayEquals(recons.get(i), decoded.get(i), "frame " + i);
        }
    }
}
