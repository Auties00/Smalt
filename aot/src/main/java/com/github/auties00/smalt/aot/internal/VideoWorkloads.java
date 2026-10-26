package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.aot.TrainingFamily;
import com.github.auties00.smalt.aot.TrainingWorkload;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.av1.Av1Decoder;
import com.github.auties00.smalt.video.av1.Av1DecoderOptions;
import com.github.auties00.smalt.video.av1.Av1Encoder;
import com.github.auties00.smalt.video.av1.Av1EncoderOptions;
import com.github.auties00.smalt.video.av1.Av1EncoderSpeed;
import com.github.auties00.smalt.video.av1.Av1ScreenContent;
import com.github.auties00.smalt.video.av1.Av1TimingInfo;
import com.github.auties00.smalt.image.avif.AvifDecoder;
import com.github.auties00.smalt.image.avif.AvifEncoder;
import com.github.auties00.smalt.image.avif.AvifEncoderOptions;
import com.github.auties00.smalt.video.dnxhd.DnxhdDecoder;
import com.github.auties00.smalt.video.dnxhd.DnxhdDecoderOptions;
import com.github.auties00.smalt.video.dnxhd.DnxhdEncoder;
import com.github.auties00.smalt.video.dnxhd.DnxhdEncoderOptions;
import com.github.auties00.smalt.video.dnxhd.DnxhdProfile;
import com.github.auties00.smalt.video.prores.ProResDecoder;
import com.github.auties00.smalt.video.prores.ProResDecoderOptions;
import com.github.auties00.smalt.video.prores.ProResEncoder;
import com.github.auties00.smalt.video.prores.ProResEncoderOptions;
import com.github.auties00.smalt.video.prores.ProResProfile;
import com.github.auties00.smalt.video.vp9.Vp9Decoder;
import com.github.auties00.smalt.video.vp9.Vp9Encoder;
import com.github.auties00.smalt.video.vp9.Vp9EncoderOptions;
import com.github.auties00.smalt.video.vp9.Vp9EncoderSpeed;
import com.github.auties00.smalt.video.vp9.Vp9Subsampling;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

import static com.github.auties00.smalt.aot.internal.VideoStreams.HEIGHT;
import static com.github.auties00.smalt.aot.internal.VideoStreams.TIME_BASE;
import static com.github.auties00.smalt.aot.internal.VideoStreams.WIDTH;
import static com.github.auties00.smalt.aot.internal.WorkloadFactory.file;
import static com.github.auties00.smalt.aot.internal.WorkloadFactory.realtime;

/**
 * Creates the workloads of {@link TrainingFamily#VIDEO}, the codecs of {@code smalt-video}: VP9,
 * AV1 and AVIF, and the ProRes and DNxHR intra codecs.
 */
final class VideoWorkloads {
    /**
     * The bitrate of the real-time streams, in bits per second.
     */
    private static final int BITRATE = 800_000;

    /**
     * The bitrate of the AV1 real-time sessions, in bits per second.
     */
    private static final int AV1_SESSION_BITRATE = 600_000;

    /**
     * The width of the frames of the good-quality, intra-only and intra codec workloads.
     */
    private static final int SMALL_WIDTH = 320;

    /**
     * The height of the frames of the good-quality, intra-only and intra codec workloads.
     */
    private static final int SMALL_HEIGHT = 180;

    /**
     * Prevents instantiation.
     */
    private VideoWorkloads() {
        throw new AssertionError();
    }

    /**
     * Returns the factories of the family's workloads.
     *
     * @return the factories, in order
     */
    static List<WorkloadFactory> factories() {
        return List.of(
                realtime("av1-encode-rt-t1", () -> av1Encode(1, BITRATE)),
                realtime("av1-encode-rt-t2", () -> av1Encode(2, BITRATE)),
                realtime("av1-encode-rt-t2-400k", () -> av1Encode(2, 400_000)),
                realtime("av1-decode-rt", () -> VideoStreams.decode("av1-decode-rt", av1Stream(BITRATE),
                        new Av1Decoder(VideoStreams.track(Codec.AV1, null)))),
                realtime("av1-decode-rt-400k", () -> VideoStreams.decode("av1-decode-rt-400k", av1Stream(400_000),
                        new Av1Decoder(VideoStreams.track(Codec.AV1, null)))),
                realtime("av1-decode-rt-shapes", VideoWorkloads::av1DecodeShapes),
                realtime("av1-session-t2", () -> VideoStreams.session("av1-session-t2", Codec.AV1,
                        () -> new Av1Encoder(WIDTH, HEIGHT, Av1EncoderOptions.forRealtime().toBuilder()
                                .bitrate(AV1_SESSION_BITRATE).speed(Av1EncoderSpeed.FASTER).threads(2)
                                .tileColumnsLog2(1).timeBase(TIME_BASE).build()),
                        Av1Decoder::new)),
                realtime("vp9-encode-rt-s6-t2", () -> vp9Encode(2, 6)),
                realtime("vp9-encode-rt-s8-t1", () -> vp9Encode(1, 8)),
                realtime("vp9-decode-rt", () -> VideoStreams.decode("vp9-decode-rt", vp9Stream(0),
                        new Vp9Decoder(VideoStreams.track(Codec.VP9, null)))),
                realtime("vp9-alpha-session-t1", () -> VideoStreams.session("vp9-alpha-session-t1", Codec.VP9,
                        () -> new Vp9Encoder(WIDTH, HEIGHT, Vp9EncoderOptions.forRealtime().toBuilder()
                                .timeBase(TIME_BASE).alpha(true).build()),
                        track -> VideoDecoders.create(track.toBuilder().alpha(true).build()))),
                file("vp9-decode-t2", () -> VideoStreams.decode("vp9-decode-t2", vp9Stream(1),
                        new Vp9Decoder(VideoStreams.track(Codec.VP9, null),
                                VideoDecoderOptions.builder().threads(2).build()))),
                file("av1-encode-quality-t2", VideoWorkloads::av1EncodeQuality),
                file("vp9-encode-quality-t2", VideoWorkloads::vp9EncodeQuality),
                file("av1-encode-intra", VideoWorkloads::av1EncodeIntra),
                file("av1-encode-tools", VideoWorkloads::av1EncodeTools),
                file("av1-decode-t2", VideoWorkloads::av1Decode),
                file("avif-encode", VideoWorkloads::avifEncode),
                file("avif-decode", VideoWorkloads::avifDecode),
                file("prores-encode", VideoWorkloads::proresEncode),
                file("prores-decode", VideoWorkloads::proresDecode),
                file("dnxhd-encode", VideoWorkloads::dnxhdEncode),
                file("dnxhd-decode", VideoWorkloads::dnxhdDecode));
    }

    /**
     * Returns a workload that encodes with the real-time AV1 encoder at the {@code FASTER} speed,
     * without frame dropping, on two tile columns when it has two threads.
     *
     * @param threads the encoder threads
     * @param bitrate the target bitrate in bits per second; the transform and partition choices,
     *                and so the kernels the encoder runs, depend on it
     * @return the workload
     */
    private static TrainingWorkload av1Encode(int threads, int bitrate) {
        var encoder = new Av1Encoder(WIDTH, HEIGHT, Av1EncoderOptions.forRealtime().toBuilder().bitrate(bitrate)
                .speed(Av1EncoderSpeed.FASTER).dropFrames(false).threads(threads)
                .tileColumnsLog2(threads > 1 ? 1 : 0).timeBase(TIME_BASE).build());
        return VideoStreams.encode("av1-encode-rt-t" + threads + (bitrate == BITRATE ? "" : "-" + bitrate / 1000 + "k"),
                encoder);
    }

    /**
     * Returns {@value VideoStreams#DECODED_FRAMES} synthetic frames coded by the real-time AV1
     * encoder.
     *
     * @param bitrate the bitrate in bits per second; below 800 kbit/s the stream is coded at the
     *                {@code FASTER} speed on two tile columns, with the smaller and rectangular
     *                transforms of a low-bitrate stream
     * @return the packets
     */
    private static List<Packet> av1Stream(int bitrate) {
        var options = Av1EncoderOptions.forRealtime().toBuilder().bitrate(bitrate).timeBase(TIME_BASE).build();
        var encoder = new Av1Encoder(WIDTH, HEIGHT, bitrate < BITRATE
                ? options.toBuilder().speed(Av1EncoderSpeed.FASTER).threads(2).tileColumnsLog2(1).build() : options);
        return VideoStreams.encodeAll(encoder, SyntheticVideo.natural(WIDTH, HEIGHT, VideoStreams.DECODED_FRAMES));
    }

    /**
     * Returns a workload that decodes on one thread two streams the good-quality AV1 encoder codes at
     * the {@code FASTEST} speed: 12 frames of fast motion at quality 95, decoded twice, and three
     * frames of every content class at quality 60.
     *
     * <p>These streams are rich in the transforms and filters the real-time streams seldom select, so
     * the vector kernels behind them get trained, and the encoder's reconstruction runs the same
     * kernels: the 32-point DCT, the 4-point and 8-point ADSTs of 8x4 and 4x8 blocks, the 16-wide
     * deblocking filter and CDEF with only a secondary strength.
     *
     * @return the workload
     */
    private static TrainingWorkload av1DecodeShapes() {
        var motion = VideoStreams.encodeAll(new Av1Encoder(WIDTH, HEIGHT, Av1EncoderOptions.ofQuality(95).toBuilder()
                .speed(Av1EncoderSpeed.FASTEST).threads(2).timeBase(TIME_BASE).build()),
                SyntheticVideo.content(2, WIDTH, HEIGHT, 12));
        var classes = new ArrayList<YuvPicture>();
        for (var kind = 0; kind < SyntheticVideo.CONTENT_CLASSES; kind++) {
            classes.addAll(SyntheticVideo.content(kind, WIDTH, HEIGHT, 3));
        }
        var mixed = VideoStreams.encodeAll(new Av1Encoder(WIDTH, HEIGHT, Av1EncoderOptions.ofQuality(60).toBuilder()
                .speed(Av1EncoderSpeed.FASTEST).threads(2).timeBase(TIME_BASE).build()), classes);
        var track = VideoStreams.track(Codec.AV1, null);
        var motionRound = VideoStreams.decodeRound(motion, new Av1Decoder(track));
        var mixedRound = VideoStreams.decodeRound(mixed, new Av1Decoder(track));
        return TrainingWorkload.of("av1-decode-rt-shapes", () -> {
            motionRound.run();
            motionRound.run();
            mixedRound.run();
        });
    }

    /**
     * Returns a workload that encodes with the real-time VP9 encoder, error resilient, with
     * adaptive quantization, four tile columns and a one-second buffer model.
     *
     * @param threads the encoder threads
     * @param speed the speed level, as {@code Vp9EncoderSpeed.of} takes it
     * @return the workload
     */
    private static TrainingWorkload vp9Encode(int threads, int speed) {
        var encoder = new Vp9Encoder(WIDTH, HEIGHT, Vp9EncoderOptions.builder()
                .timeBase(TIME_BASE)
                .bitrate(BITRATE)
                .quantizers(2, 52)
                .noPeriodicKeyframes()
                .speed(Vp9EncoderSpeed.of(speed))
                .errorResilient(true)
                .adaptiveQuantization(true)
                .temporalLayers(1)
                .bufferSize(Duration.ofMillis(1000))
                .initialBufferLevel(Duration.ofMillis(500))
                .optimalBufferLevel(Duration.ofMillis(600))
                .tileColumnsLog2(2)
                .threads(threads)
                .build());
        return VideoStreams.encode("vp9-encode-rt-s" + speed + "-t" + threads, encoder);
    }

    /**
     * Returns {@value VideoStreams#DECODED_FRAMES} synthetic frames coded by the real-time VP9
     * encoder.
     *
     * @param tileColumnsLog2 the base-2 logarithm of the tile columns, which a multi-threaded
     *                        decoder decodes in parallel
     * @return the packets
     */
    private static List<Packet> vp9Stream(int tileColumnsLog2) {
        var encoder = new Vp9Encoder(WIDTH, HEIGHT, Vp9EncoderOptions.forRealtime().toBuilder()
                .timeBase(TIME_BASE).bitrate(BITRATE).tileColumnsLog2(tileColumnsLog2).build());
        return VideoStreams.encodeAll(encoder, SyntheticVideo.natural(WIDTH, HEIGHT, VideoStreams.DECODED_FRAMES));
    }

    /**
     * Returns a workload that encodes twelve frames with the good-quality VP9 encoder (lookahead,
     * hidden alternate references, temporal filtering, the rate-distortion partition and mode
     * search, compound prediction) at a constant quality and the {@code SPEED_2} speed on two
     * threads, as 8-bit 4:2:0 and as 10-bit 4:4:4, new encoders every round, flushed at the end.
     *
     * @return the workload
     */
    private static TrainingWorkload vp9EncodeQuality() {
        var pictures = SyntheticVideo.natural(SMALL_WIDTH, SMALL_HEIGHT, 12);
        var options = Vp9EncoderOptions.ofQuality(32).toBuilder().speed(Vp9EncoderSpeed.SPEED_2).threads(2)
                .timeBase(TIME_BASE).build();
        var high = options.toBuilder().pictureFormat(Vp9Subsampling.YUV444, 10).build();
        return TrainingWorkload.of("vp9-encode-quality-t2", () -> {
            for (var settings : List.of(options, high)) {
                for (var packet : VideoStreams.encodeAll(new Vp9Encoder(SMALL_WIDTH, SMALL_HEIGHT, settings),
                        pictures)) {
                    Blackhole.consume(packet.size());
                }
            }
        });
    }

    /**
     * Returns a workload that encodes twelve frames with the good-quality AV1 encoder at a constant
     * quality, the {@code FAST} speed and two threads (lookahead, alternate reference frames,
     * temporal filtering, compound prediction), a new encoder every round, flushed at the end.
     *
     * @return the workload
     */
    private static TrainingWorkload av1EncodeQuality() {
        var pictures = SyntheticVideo.natural(SMALL_WIDTH, SMALL_HEIGHT, 12);
        var options = Av1EncoderOptions.ofQuality(60).toBuilder().speed(Av1EncoderSpeed.FAST).threads(2)
                .timeBase(TIME_BASE).build();
        return TrainingWorkload.of("av1-encode-quality-t2", () -> {
            for (var packet : VideoStreams.encodeAll(new Av1Encoder(SMALL_WIDTH, SMALL_HEIGHT, options), pictures)) {
                Blackhole.consume(packet.size());
            }
        });
    }

    /**
     * Returns a workload that encodes pictures with the intra-only AV1 encoder, as 8-bit 4:2:0 and
     * as 10-bit 4:4:4.
     *
     * @return the workload
     */
    private static TrainingWorkload av1EncodeIntra() {
        var pictures = SyntheticVideo.natural(SMALL_WIDTH, SMALL_HEIGHT, 2);
        var encoders = List.of(
                new Av1Encoder(SMALL_WIDTH, SMALL_HEIGHT, Av1EncoderOptions.forIntraOnly(70).toBuilder()
                        .timeBase(TIME_BASE).build()),
                new Av1Encoder(SMALL_WIDTH, SMALL_HEIGHT, Av1EncoderOptions.forIntraOnly(80).toBuilder()
                        .pictureFormat(ChromaFormat.YUV444, 10).timeBase(TIME_BASE).build()));
        long[] pts = {0};
        return TrainingWorkload.of("av1-encode-intra", () -> {
            for (var encoder : encoders) {
                for (var picture : pictures) {
                    encoder.encode(new VideoFrame(picture, pts[0]++, 1, true), packet -> Blackhole.consume(packet.size()));
                }
            }
        });
    }

    /**
     * Returns a workload that encodes with the coding tools of the predicted AV1 encoders beyond
     * 8-bit 4:2:0 camera content: a real-time 10-bit stream with the screen content tools, and a
     * good-quality stream with super-resolution and the decoder model's timing, new encoders every
     * round, flushed at the end.
     *
     * @return the workload
     */
    private static TrainingWorkload av1EncodeTools() {
        var pictures = SyntheticVideo.natural(SMALL_WIDTH, SMALL_HEIGHT, 8);
        var realtime = Av1EncoderOptions.forRealtime().toBuilder().bitrate(BITRATE)
                .pictureFormat(ChromaFormat.YUV420, 10).screenContent(Av1ScreenContent.ALWAYS).timeBase(TIME_BASE)
                .build();
        var good = Av1EncoderOptions.ofQuality(60).toBuilder().speed(Av1EncoderSpeed.FASTEST).lookahead(4)
                .superResolution(12).timingInfo(Av1TimingInfo.DECODER_MODEL).timeBase(TIME_BASE).build();
        return TrainingWorkload.of("av1-encode-tools", () -> {
            for (var packet : VideoStreams.encodeAll(new Av1Encoder(SMALL_WIDTH, SMALL_HEIGHT, realtime), pictures)) {
                Blackhole.consume(packet.size());
            }
            for (var packet : VideoStreams.encodeAll(new Av1Encoder(SMALL_WIDTH, SMALL_HEIGHT, good), pictures)) {
                Blackhole.consume(packet.size());
            }
        });
    }

    /**
     * Returns a workload that decodes on two threads a good-quality AV1 stream and a 10-bit 4:4:4
     * intra-only stream, the latter at its full depth.
     *
     * @return the workload
     */
    private static TrainingWorkload av1Decode() {
        var quality = VideoStreams.encodeAll(new Av1Encoder(SMALL_WIDTH, SMALL_HEIGHT,
                Av1EncoderOptions.ofQuality(50).toBuilder().speed(Av1EncoderSpeed.FASTEST).timeBase(TIME_BASE).build()),
                SyntheticVideo.natural(SMALL_WIDTH, SMALL_HEIGHT, 16));
        var intra = VideoStreams.encodeAll(new Av1Encoder(SMALL_WIDTH, SMALL_HEIGHT,
                Av1EncoderOptions.forIntraOnly(80).toBuilder().pictureFormat(ChromaFormat.YUV444, 10)
                        .timeBase(TIME_BASE).build()),
                SyntheticVideo.natural(SMALL_WIDTH, SMALL_HEIGHT, 4));
        var track = VideoStreams.track(Codec.AV1, SMALL_WIDTH, SMALL_HEIGHT, null);
        var options = Av1DecoderOptions.builder().threads(2).build();
        var qualityRound = VideoStreams.decodeRound(quality, new Av1Decoder(track, options));
        var intraRound = VideoStreams.decodeRound(intra,
                new Av1Decoder(track, options.toBuilder().highBitDepth(true).build()));
        return TrainingWorkload.of("av1-decode-t2", () -> {
            qualityRound.run();
            intraRound.run();
        });
    }

    /**
     * Returns a workload that encodes AVIF images: a 384x256 picture with alpha at quality 60, the
     * same picture as 10-bit 4:4:4 at a faster speed, and a 128x128 picture losslessly.
     *
     * @return the workload
     */
    private static TrainingWorkload avifEncode() {
        var alpha = SyntheticImages.rgba(SyntheticVideo.natural(384, 256, 1).getFirst(), true);
        var small = SyntheticImages.rgba(SyntheticVideo.natural(128, 128, 1).getFirst(), false);
        var deep = AvifEncoderOptions.lossy(70).toBuilder().chromaFormat(ChromaFormat.YUV444).bitDepth(10).speed(8)
                .build();
        return TrainingWorkload.of("avif-encode", () -> {
            Blackhole.consume(AvifEncoder.encode(alpha, AvifEncoderOptions.lossy(60)).length);
            Blackhole.consume(AvifEncoder.encode(alpha, deep).length);
            Blackhole.consume(AvifEncoder.encode(small, AvifEncoderOptions.lossless()).length);
        });
    }

    /**
     * Returns a workload that probes and decodes AVIF images: a lossy picture with alpha, a 10-bit
     * 4:4:4 picture at its full depth, an image overlay of an identity derivation, a 16-bit sample
     * transform, a 10-bit animation with alpha played as 16-bit frames and sought back, and the
     * triangle-filter resampling of 8-bit and 10-bit alpha planes that alpha images of another size
     * than their color take.
     *
     * @return the workload
     */
    private static TrainingWorkload avifDecode() {
        var picture = SyntheticImages.rgba(SyntheticVideo.natural(384, 256, 1).getFirst(), true);
        var lossy = AvifEncoder.encode(picture, AvifEncoderOptions.lossy(60));
        var deepOptions = AvifEncoderOptions.lossy(70).toBuilder().chromaFormat(ChromaFormat.YUV444).bitDepth(10).speed(8)
                .build();
        var deep = AvifEncoder.encode(picture, deepOptions);
        var frames = SyntheticVideo.natural(128, 96, 3);
        var animation = new java.io.ByteArrayOutputStream();
        try (var writer = AvifEncoder.openAnimationWriter(128, 96, deepOptions, MediaSink.of(animation))) {
            for (var frame : frames) {
                writer.write(SyntheticImages.rgba(frame, true), 40_000);
            }
            writer.finish();
        }
        var animated = animation.toByteArray();
        var luma = SyntheticVideo.natural(96, 64, 1).getFirst();
        var alpha8 = YuvPicture.builder().size(luma.width(), luma.height()).chromaFormat(ChromaFormat.MONOCHROME)
                .color(luma.color()).luma(luma.y(), luma.yStride()).build();
        var alpha10 = ImageOps.toBitDepth(alpha8, 10);
        var highBitDepth = ImageDecoderOptions.builder().highBitDepth(true).build();
        var tiles = SyntheticVideo.natural(96, 64, 2);
        var first = SyntheticAvif.Coded.of(AvifEncoder.encode(tiles.getFirst(), AvifEncoderOptions.lossy(70)));
        var second = SyntheticAvif.Coded.of(AvifEncoder.encode(tiles.getLast(), AvifEncoderOptions.lossy(70)));
        var overlay = SyntheticAvif.overlay(first, second);
        var transform = SyntheticAvif.sampleTransform(first, second);
        return TrainingWorkload.of("avif-decode", () -> {
            Blackhole.consume(AvifDecoder.probe(MediaSource.of(lossy)).hashCode());
            Blackhole.consume(AvifDecoder.decode(MediaSource.of(lossy), ImageDecoderOptions.defaults()).picture().width());
            Blackhole.consume(AvifDecoder.decode(MediaSource.of(deep), highBitDepth).picture().width());
            Blackhole.consume(AvifDecoder.decode(MediaSource.of(overlay)).picture().width());
            Blackhole.consume(AvifDecoder.decode(MediaSource.of(transform), highBitDepth).picture().width());
            try (var reader = AvifDecoder.openAnimation(MediaSource.of(animated), highBitDepth)) {
                for (var frame = reader.read(); frame != null; frame = reader.read()) {
                    Blackhole.consume(frame.picture().width());
                }
                reader.seek(40_000);
                Blackhole.consume(reader.read().picture().width());
            }
            Blackhole.consume(ImageOps.resize(alpha8, 192, 128, ResizeFilter.BILINEAR).width());
            Blackhole.consume(ImageOps.resize(alpha10, 48, 32, ResizeFilter.BILINEAR).width());
        });
    }

    /**
     * Returns a workload that encodes frames with the ProRes 422 encoder and with the ProRes 4444
     * encoder with alpha, on the calling thread.
     *
     * @return the workload
     */
    private static TrainingWorkload proresEncode() {
        var pictures = SyntheticVideo.natural(SMALL_WIDTH, SMALL_HEIGHT, 2);
        var alpha = SyntheticImages.rgba(pictures.getFirst(), true);
        var standard = new ProResEncoder(SMALL_WIDTH, SMALL_HEIGHT,
                ProResEncoderOptions.of(ProResProfile.STANDARD).toBuilder().threads(1).build());
        var full = new ProResEncoder(SMALL_WIDTH, SMALL_HEIGHT,
                ProResEncoderOptions.of(ProResProfile.PRORES_4444).toBuilder().alpha(true).threads(1).build());
        return TrainingWorkload.of("prores-encode", () -> {
            for (var picture : pictures) {
                Blackhole.consume(standard.encodeFrame(new VideoFrame(picture, Packet.NO_TIMESTAMP, Track.UNKNOWN_DURATION, true)).size());
            }
            Blackhole.consume(full.encodeFrame(new VideoFrame(alpha, Packet.NO_TIMESTAMP, Track.UNKNOWN_DURATION, true)).size());
        });
    }

    /**
     * Returns a workload that decodes ProRes 422 HQ frames on the calling thread, at 8 bits and at
     * their full depth, and on two threads.
     *
     * @return the workload
     */
    private static TrainingWorkload proresDecode() {
        var encoder = new ProResEncoder(SMALL_WIDTH, SMALL_HEIGHT,
                ProResEncoderOptions.of(ProResProfile.HQ).toBuilder().threads(1).build());
        var frames = new ArrayList<byte[]>();
        for (var picture : SyntheticVideo.natural(SMALL_WIDTH, SMALL_HEIGHT, 4)) {
            frames.add(encoder.encodeFrame(new VideoFrame(picture, Packet.NO_TIMESTAMP, Track.UNKNOWN_DURATION, true)).data());
        }
        var eight = new ProResDecoder(ProResDecoderOptions.builder().threads(1).highBitDepth(false).build());
        var deep = new ProResDecoder(ProResDecoderOptions.builder().threads(1).highBitDepth(true).build());
        var parallel = new ProResDecoder(ProResDecoderOptions.builder().threads(2).build());
        return intraDecode("prores-decode", frames,
                frame -> eight.decodeFrame(frame, 0, frame.length).width()
                        + deep.decodeFrame(frame, 0, frame.length).width()
                        + parallel.decodeFrame(frame, 0, frame.length).width());
    }

    /**
     * Returns a workload that encodes frames with the DNxHR HQ encoder and with the 10-bit DNxHR
     * 444 encoder, on the calling thread.
     *
     * @return the workload
     */
    private static TrainingWorkload dnxhdEncode() {
        var pictures = SyntheticVideo.natural(SMALL_WIDTH, SMALL_HEIGHT, 2);
        var rgb = SyntheticImages.rgba(pictures.getFirst(), false);
        var hq = new DnxhdEncoder(SMALL_WIDTH, SMALL_HEIGHT,
                DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQ).toBuilder().threads(1).build());
        var full = new DnxhdEncoder(SMALL_WIDTH, SMALL_HEIGHT,
                DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_444).toBuilder().threads(1).build());
        return TrainingWorkload.of("dnxhd-encode", () -> {
            for (var picture : pictures) {
                Blackhole.consume(hq.encodeFrame(new VideoFrame(picture, Packet.NO_TIMESTAMP, Track.UNKNOWN_DURATION, true)).size());
            }
            Blackhole.consume(full.encodeFrame(new VideoFrame(rgb, Packet.NO_TIMESTAMP, Track.UNKNOWN_DURATION, true)).size());
        });
    }

    /**
     * Returns a workload that decodes DNxHR SQ frames on the calling thread and on two threads.
     *
     * @return the workload
     */
    private static TrainingWorkload dnxhdDecode() {
        var encoder = new DnxhdEncoder(SMALL_WIDTH, SMALL_HEIGHT,
                DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_SQ).toBuilder().threads(1).build());
        var frames = new ArrayList<byte[]>();
        for (var picture : SyntheticVideo.natural(SMALL_WIDTH, SMALL_HEIGHT, 4)) {
            frames.add(encoder.encodeFrame(new VideoFrame(picture, Packet.NO_TIMESTAMP, Track.UNKNOWN_DURATION, true)).data());
        }
        var single = new DnxhdDecoder(DnxhdDecoderOptions.builder().threads(1).build());
        var parallel = new DnxhdDecoder(DnxhdDecoderOptions.builder().threads(2).build());
        return intraDecode("dnxhd-decode", frames,
                frame -> single.decodeFrame(frame, 0, frame.length).width()
                        + parallel.decodeFrame(frame, 0, frame.length).width());
    }

    /**
     * Returns a workload that decodes the same intra frames every round.
     *
     * @param name    the name of the workload
     * @param frames  the coded frames
     * @param decoder decodes one frame and returns a value of the decoded pictures
     * @return the workload
     */
    private static TrainingWorkload intraDecode(String name, List<byte[]> frames, ToIntFunction<byte[]> decoder) {
        return TrainingWorkload.of(name, () -> {
            for (var frame : frames) {
                Blackhole.consume(decoder.applyAsInt(frame));
            }
        });
    }
}
