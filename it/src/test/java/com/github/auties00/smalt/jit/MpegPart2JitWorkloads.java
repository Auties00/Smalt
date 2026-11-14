package com.github.auties00.smalt.jit;

import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h263.H263Encoder;
import com.github.auties00.smalt.video.h263.H263EncoderOptions;
import com.github.auties00.smalt.video.mpeg4.Mpeg4Decoder;
import com.github.auties00.smalt.video.mpeg4.Mpeg4Encoder;
import com.github.auties00.smalt.video.mpeg4.Mpeg4EncoderOptions;
import com.github.auties00.smalt.video.mpeg4.Mpeg4Profile;

import java.util.ArrayList;

/**
 * The MPEG-4 Part 2 and H.263 workloads of the JIT guard: encoding synthetic 352x288 frames in the Advanced Simple
 * profile and in H.263 with its optional modes, and decoding the encoder's Advanced Simple stream, which exercise
 * the motion search, the forward DCT and the reconstruction kernels of {@code video.mpeg4}.
 */
final class MpegPart2JitWorkloads {
    /** Keeps the results alive so the compiler cannot discard the work. */
    static volatile long sink;

    private static final int WIDTH = 352;
    private static final int HEIGHT = 288;
    private static final Rational TIME_BASE = new Rational(1, 30);

    private MpegPart2JitWorkloads() {
    }

    private static Mpeg4EncoderOptions mpeg4Options(int threads) {
        return Mpeg4EncoderOptions.builder().profile(Mpeg4Profile.ADVANCED_SIMPLE).bFrames(2).quarterSample(true)
                .timeBase(TIME_BASE).frameRate(new Rational(30, 1)).bitrate(800_000).threads(threads).build();
    }

    static JitWorkload.Workload mpeg4Encode(int threads) {
        var pictures = JitWorkload.synthesize(WIDTH, HEIGHT, 12);
        var encoder = new Mpeg4Encoder(WIDTH, HEIGHT, mpeg4Options(threads));
        long[] pts = {0};
        return JitWorkload.named("mpeg4enc-t" + threads, () -> {
            for (var picture : pictures) {
                encoder.encode(new VideoFrame(picture, pts[0]++, 1, false), packet -> sink += packet.size());
            }
            return pictures.size();
        });
    }

    static JitWorkload.Workload h263Encode(int threads) {
        var pictures = JitWorkload.synthesize(WIDTH, HEIGHT, 12);
        var encoder = new H263Encoder(WIDTH, HEIGHT, H263EncoderOptions.builder().timeBase(TIME_BASE)
                .frameRate(new Rational(30, 1)).bitrate(400_000).advancedPrediction(true).deblockingFilter(true)
                .advancedIntraCoding(true).modifiedQuantization(true).threads(threads).build());
        long[] pts = {0};
        return JitWorkload.named("h263enc-t" + threads, () -> {
            for (var picture : pictures) {
                encoder.encode(new VideoFrame(picture, pts[0]++, 1, false), packet -> sink += packet.size());
            }
            return pictures.size();
        });
    }

    static JitWorkload.Workload mpeg4Decode(int threads) {
        var packets = new ArrayList<Packet>();
        byte[] config;
        try (var encoder = new Mpeg4Encoder(WIDTH, HEIGHT, mpeg4Options(2))) {
            config = encoder.codecConfig().orElseThrow();
            long pts = 0;
            for (var picture : JitWorkload.synthesize(WIDTH, HEIGHT, 24)) {
                encoder.encode(new VideoFrame(picture, pts++, 1, false), packets::add);
            }
            encoder.flush(packets::add);
        }
        var track = VideoTrack.builder().id(1).codec(Codec.MPEG4_PART2).timeBase(TIME_BASE).width(WIDTH)
                .height(HEIGHT).codecConfig(config).build();
        var decoder = new Mpeg4Decoder(track, VideoDecoderOptions.builder().threads(threads).build());
        var span = packets.size() + 1L;
        long[] offset = {0};
        return JitWorkload.named("mpeg4dec-t" + threads, () -> {
            int[] count = {0};
            for (var packet : packets) {
                decoder.decode(packet.withTiming(packet.pts() + offset[0], packet.dts() + offset[0],
                        packet.duration()), frame -> count[0]++);
            }
            decoder.flush(frame -> count[0]++);
            offset[0] += span;
            sink += count[0];
            return count[0];
        });
    }
}
