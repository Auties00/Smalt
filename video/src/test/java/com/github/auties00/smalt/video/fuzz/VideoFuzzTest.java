package com.github.auties00.smalt.video.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AuxiliaryImage;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.avif.AvifDecoder;
import com.github.auties00.smalt.image.gainmap.GainMap;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.fuzz.FuzzGainMaps;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;
import com.github.auties00.smalt.testkit.fuzz.FuzzMedia;
import com.github.auties00.smalt.testkit.fuzz.FuzzPackets;
import com.github.auties00.smalt.testkit.fuzz.FuzzRun;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.av1.Av1Decoder;
import com.github.auties00.smalt.video.av1.Av1DecoderOptions;

import java.util.ArrayList;
import java.util.function.Consumer;

import java.util.List;
import java.util.Optional;

/**
 * Fuzz targets of the video decoders of this module, fed streams in the
 * {@link com.github.auties00.smalt.testkit.fuzz.FuzzPackets} format (see {@link FuzzMedia#video}), and of the
 * AVIF image decoder (see {@link FuzzMedia#image}). The AV1 Annex B target reads its options byte as bit 0 high bit
 * depth output, bits 1 and 2 the operating point and bit 3 all layers; the AV1 tile list target decodes the number of
 * packets of bits 4 to 7 of its options byte as anchor frames, sets the frame each shows as an anchor frame and
 * decodes the rest, the camera frame header and the tile lists, in large scale tile mode.
 */
class VideoFuzzTest {
    @FuzzTest
    void vp9(byte[] data) {
        FuzzMedia.video(Codec.VP9, data);
    }

    @FuzzTest
    void av1(byte[] data) {
        FuzzMedia.video(Codec.AV1, data);
    }

    @FuzzTest
    void av1AnnexB(byte[] data) {
        var run = Fuzz.start("AV1 Annex B");
        var input = FuzzPackets.parse(data, FuzzPackets.VIDEO_FIELDS);
        var options = input.header().options();
        var decoderOptions = Av1DecoderOptions.builder().limits(Fuzz.LIMITS).threads(1).annexB(true)
                .highBitDepth((options & 1) != 0).operatingPoint((options >> 1) & 3).allLayers((options & 8) != 0)
                .build();
        var decoder = run.value("open", () -> new Av1Decoder(track(input), decoderOptions));
        if (decoder.isEmpty()) {
            return;
        }
        var frames = new int[1];
        Consumer<VideoFrame> sink = frame -> touch(run, frame.picture(), frames[0]++ == 0);
        try (var opened = decoder.get()) {
            for (var index = 0; index < input.packets().size(); index++) {
                var packet = packet(input.packets().get(index), index);
                run.step("packet " + index, () -> opened.decode(packet, sink));
            }
            run.step("flush", () -> opened.flush(sink));
        }
    }

    @FuzzTest
    void av1TileList(byte[] data) {
        var run = Fuzz.start("AV1 tile list");
        var input = FuzzPackets.parse(data, FuzzPackets.VIDEO_FIELDS);
        var options = input.header().options();
        var anchorCount = (options >> 4) & 0xF;
        var decoderOptions = Av1DecoderOptions.builder().limits(Fuzz.LIMITS).threads(1)
                .highBitDepth((options & 1) != 0).build();
        var decoder = run.value("open", () -> new Av1Decoder(track(input), decoderOptions));
        if (decoder.isEmpty()) {
            return;
        }
        var anchors = new ArrayList<Picture>();
        var frames = new int[1];
        try (var opened = decoder.get()) {
            for (var index = 0; index < input.packets().size(); index++) {
                if (index == anchorCount && !anchors.isEmpty()) {
                    run.step("anchors", () -> opened.setAnchorFrames(anchors));
                }
                var packet = packet(input.packets().get(index), index);
                var anchor = index < anchorCount;
                var shown = new ArrayList<Picture>();
                run.step("packet " + index, () -> opened.decode(packet, frame -> {
                    touch(run, frame.picture(), frames[0]++ == 0);
                    if (anchor) {
                        shown.add(copy(frame.picture()));
                    }
                }));
                if (anchor && !shown.isEmpty()) {
                    anchors.add(shown.getLast());
                }
            }
            run.step("flush", () -> opened.flush(frame -> touch(run, frame.picture(), false)));
        }
    }

    @FuzzTest
    void proRes(byte[] data) {
        FuzzMedia.video(Codec.PRORES, data);
    }

    @FuzzTest
    void dnxhd(byte[] data) {
        FuzzMedia.video(Codec.DNXHD, data);
    }

    @FuzzTest
    void avif(byte[] data) {
        FuzzMedia.image(ImageFormat.AVIF, data);
    }

    @FuzzTest
    void avifGainMap(byte[] data) {
        FuzzGainMaps.run("avifGainMap", data, new FuzzGainMaps.Decoder() {
            @Override
            public DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
                return AvifDecoder.decode(source, options);
            }

            @Override
            public Optional<GainMap> gainMap(MediaSource source, ImageDecoderOptions options) {
                return AvifDecoder.decodeGainMap(source, options);
            }

            @Override
            public List<AuxiliaryImage> auxiliaryImages(MediaSource source, MediaLimits limits) {
                return AvifDecoder.auxiliaryImages(source, limits);
            }

            @Override
            public DecodedImage auxiliaryImage(MediaSource source, int index, ImageDecoderOptions options) {
                return AvifDecoder.decodeAuxiliaryImage(source, index, options);
            }
        });
    }

    private static VideoTrack track(FuzzPackets input) {
        var header = input.header();
        var track = VideoTrack.builder()
                .id(1)
                .codec(Codec.AV1)
                .timeBase(new Rational(1, 90_000))
                .width(header.unsigned(1, 2))
                .height(header.unsigned(3, 2));
        header.config().ifPresent(track::codecConfig);
        return track.build();
    }

    private static Packet packet(FuzzPackets.Part part, int index) {
        var pts = index * 3000L;
        return new Packet(1, part.data(), pts, pts, 3000, part.keyframe() || index == 0);
    }

    private static void touch(FuzzRun run, Picture picture, boolean convert) {
        if (picture.width() <= 0 || picture.height() <= 0) {
            throw new IllegalStateException("decoded an empty picture " + picture.width() + "x" + picture.height());
        }
        picture.hasAlpha();
        picture.bitDepth();
        if (convert && (long) picture.width() * picture.height() <= Fuzz.MAX_CONVERTED_PIXELS) {
            ImageOps.toRgba(picture);
        }
        run.output();
    }

    private static Picture copy(Picture picture) {
        return switch (picture) {
            case YuvPicture yuv -> {
                var copy = YuvPicture.allocate(yuv.width(), yuv.height(), yuv.chromaFormat(), false, yuv.color());
                copyPlane(yuv.y(), yuv.yStride(), copy.y(), copy.yStride(), yuv.width(), yuv.height());
                if (yuv.chromaFormat().hasChroma()) {
                    copyPlane(yuv.u(), yuv.uvStride(), copy.u(), copy.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                    copyPlane(yuv.v(), yuv.uvStride(), copy.v(), copy.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                }
                yield copy;
            }
            case Yuv16Picture yuv -> {
                var copy = Yuv16Picture.allocate(yuv.width(), yuv.height(), yuv.chromaFormat(), yuv.bitDepth(), false,
                        yuv.color());
                copyPlane(yuv.y(), yuv.yStride(), copy.y(), copy.yStride(), yuv.width(), yuv.height());
                if (yuv.chromaFormat().hasChroma()) {
                    copyPlane(yuv.u(), yuv.uvStride(), copy.u(), copy.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                    copyPlane(yuv.v(), yuv.uvStride(), copy.v(), copy.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                }
                yield copy;
            }
            default -> throw new IllegalStateException("AV1 decoded a " + picture.getClass().getSimpleName());
        };
    }

    private static void copyPlane(Object src, int srcStride, Object dst, int dstStride, int width, int height) {
        for (var y = 0; y < height; y++) {
            System.arraycopy(src, y * srcStride, dst, y * dstStride, width);
        }
    }
}
