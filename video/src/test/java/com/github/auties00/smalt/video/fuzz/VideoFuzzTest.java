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
import com.github.auties00.smalt.video.av1.Av1Encoder;
import com.github.auties00.smalt.video.av1.Av1EncoderOptions;
import com.github.auties00.smalt.video.av1.Av1EncoderSpeed;
import com.github.auties00.smalt.video.av1.Av1FilmGrainTable;
import com.github.auties00.smalt.video.av1.Av1FirstPassStatistics;
import com.github.auties00.smalt.image.ChromaFormat;

import java.nio.charset.StandardCharsets;
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
 * decodes the rest, the camera frame header and the tile lists, in large scale tile mode. The film grain table
 * target parses the text form, checks that it round-trips and signals the table's grain in two real-time frames
 * that it decodes; the first pass statistics target parses and reserializes them and, for pictures of at most
 * 32x32 samples, at least 16x16, runs a two-pass good-quality encode over them that it decodes.
 */
class VideoFuzzTest {
    @FuzzTest
    void vp9(byte[] data) {
        FuzzMedia.video(Codec.VP9, data);
    }

    @FuzzTest
    void vp9Alpha(byte[] data) {
        FuzzMedia.videoAlpha(Codec.VP9, data);
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
    void dv(byte[] data) {
        FuzzMedia.video(Codec.DV, data);
    }

    @FuzzTest
    void theora(byte[] data) {
        FuzzMedia.video(Codec.THEORA, data);
    }

    @FuzzTest
    void ffv1(byte[] data) {
        FuzzMedia.video(Codec.FFV1, data);
    }

    @FuzzTest
    void qtrle(byte[] data) {
        FuzzMedia.video(Codec.QTRLE, data);
    }

    @FuzzTest
    void cfhd(byte[] data) {
        FuzzMedia.video(Codec.CFHD, data);
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

    @FuzzTest
    void av1FilmGrainTable(byte[] data) {
        var run = Fuzz.start("AV1 film grain table");
        Av1FilmGrainTable table;
        try {
            table = Av1FilmGrainTable.parse(new String(data, StandardCharsets.ISO_8859_1));
        } catch (IllegalArgumentException rejected) {
            run.reject("parse: " + rejected.getMessage());
            return;
        }
        run.output();
        var text = table.format();
        if (!table.equals(Av1FilmGrainTable.parse(text)) || !text.equals(Av1FilmGrainTable.parse(text).format())) {
            throw new AssertionError("the text form does not round-trip: " + text);
        }
        for (var entry : table.entries()) {
            table.lookup(entry.start());
        }
        // the first two frames, at 0 and 1/30 s, signal the grain the table gives them
        var options = Av1EncoderOptions.forRealtime().toBuilder().bitrate(100_000).dropFrames(false).threads(1)
                .timeBase(new Rational(1, 90_000)).filmGrain(table).build();
        Av1Encoder encoder;
        try {
            encoder = new Av1Encoder(GRAIN_SIZE, GRAIN_SIZE, options);
        } catch (IllegalArgumentException rejected) {
            run.reject("encoder: " + rejected.getMessage());
            return;
        }
        var packets = new ArrayList<Packet>();
        try (encoder) {
            for (var i = 0; i < 2; i++) {
                var picture = YuvPicture.allocate(GRAIN_SIZE, GRAIN_SIZE, ChromaFormat.YUV420, false,
                        options.color());
                java.util.Arrays.fill(picture.y(), (byte) (100 + 20 * i));
                java.util.Arrays.fill(picture.u(), (byte) 128);
                java.util.Arrays.fill(picture.v(), (byte) 128);
                encoder.encode(new VideoFrame(picture, i * 3000L, 3000, false), packets::add);
            }
            encoder.flush(packets::add);
        }
        var track = VideoTrack.builder().id(1).codec(Codec.AV1).timeBase(new Rational(1, 90_000))
                .width(GRAIN_SIZE).height(GRAIN_SIZE).build();
        run.step("decode", () -> {
            try (var decoder = new Av1Decoder(track, Av1DecoderOptions.builder().limits(Fuzz.LIMITS).threads(1)
                    .build())) {
                for (var packet : packets) {
                    decoder.decode(packet, frame -> touch(run, frame.picture(), false));
                }
            }
        });
    }

    @FuzzTest
    void av1FirstPassStatistics(byte[] data) {
        var run = Fuzz.start("AV1 first pass statistics");
        Av1FirstPassStatistics statistics;
        try {
            statistics = Av1FirstPassStatistics.parse(data);
        } catch (IllegalArgumentException rejected) {
            run.reject("parse: " + rejected.getMessage());
            return;
        }
        run.output();
        if (!java.util.Arrays.equals(data, statistics.toByteArray())) {
            throw new AssertionError("the serialized form does not round-trip");
        }
        var width = statistics.width();
        var height = statistics.height();
        if (width > STATISTICS_SIZE || height > STATISTICS_SIZE || width < 16 || height < 16) {
            run.skip("pictures of a size the target does not encode");
            return;
        }
        // a second pass over a few more frames than the statistics measure, at a variable bitrate
        var options = Av1EncoderOptions.ofBitrate(200_000).toBuilder().speed(Av1EncoderSpeed.FASTEST).lookahead(4)
                .threads(1).timeBase(new Rational(1, 90_000)).firstPassStatistics(statistics).build();
        var frames = Math.min(statistics.frameCount() + 2, STATISTICS_FRAMES);
        var packets = new ArrayList<Packet>();
        try (var encoder = new Av1Encoder(width, height, options)) {
            for (var i = 0; i < frames; i++) {
                var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, options.color());
                for (var y = 0; y < height; y++) {
                    for (var x = 0; x < width; x++) {
                        picture.y()[y * picture.yStride() + x] = (byte) ((x * 5 + y * 3 + i * 7) & 0xFF);
                    }
                }
                java.util.Arrays.fill(picture.u(), (byte) 128);
                java.util.Arrays.fill(picture.v(), (byte) 128);
                encoder.encode(new VideoFrame(picture, i * 3000L, 3000, false), packets::add);
            }
            encoder.flush(packets::add);
        }
        var track = VideoTrack.builder().id(1).codec(Codec.AV1).timeBase(new Rational(1, 90_000)).width(width)
                .height(height).build();
        run.step("decode", () -> {
            try (var decoder = new Av1Decoder(track, Av1DecoderOptions.builder().limits(Fuzz.LIMITS).threads(1)
                    .build())) {
                for (var packet : packets) {
                    decoder.decode(packet, frame -> touch(run, frame.picture(), false));
                }
            }
        });
        if (run.outputs() != 1 + frames) {
            throw new AssertionError(frames + " frames coded, " + (run.outputs() - 1) + " decoded");
        }
    }

    private static final int GRAIN_SIZE = 16;

    private static final int STATISTICS_SIZE = 32;

    private static final int STATISTICS_FRAMES = 10;

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
