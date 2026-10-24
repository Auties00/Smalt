package com.github.auties00.smalt.jit;

import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoFrame;
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

import java.util.ArrayList;
import java.util.List;

/**
 * The ProRes and DNxHD workloads of the JIT guard: decoding and encoding synthetic 320x240
 * frames on the calling thread, which exercises the block kernels of {@code video.intra}.
 */
final class IntraJitWorkloads {
    /** Keeps the results alive so the compiler cannot discard the work. */
    static volatile long sink;

    private IntraJitWorkloads() {
    }

    private static List<byte[]> proresFrames() {
        var encoder = new ProResEncoder(320, 240, ProResEncoderOptions.of(ProResProfile.HQ).toBuilder()
                .threads(1).build());
        var frames = new ArrayList<byte[]>();
        for (var picture : JitWorkload.synthesize(320, 240, 4)) {
            frames.add(encoder.encodeFrame(new VideoFrame(picture, Packet.NO_TIMESTAMP, 0, true)).data());
        }
        return frames;
    }

    static JitWorkload.Workload proresDecode() {
        var frames = proresFrames();
        var decoder = new ProResDecoder(ProResDecoderOptions.builder().threads(1).build());
        return JitWorkload.named("proresdec", () -> {
            for (var frame : frames) {
                sink += decoder.decodeFrame(frame, 0, frame.length).width();
            }
            return frames.size();
        });
    }

    static JitWorkload.Workload proresEncode() {
        var pictures = JitWorkload.synthesize(320, 240, 2);
        var encoder = new ProResEncoder(320, 240, ProResEncoderOptions.of(ProResProfile.STANDARD).toBuilder()
                .threads(1).build());
        return JitWorkload.named("proresenc", () -> {
            for (var picture : pictures) {
                sink += encoder.encodeFrame(new VideoFrame(picture, Packet.NO_TIMESTAMP, 0, true)).size();
            }
            return pictures.size();
        });
    }

    static JitWorkload.Workload dnxhdDecode() {
        var encoder = new DnxhdEncoder(320, 240, DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_SQ).toBuilder()
                .threads(1).build());
        var frames = new ArrayList<byte[]>();
        for (var picture : JitWorkload.synthesize(320, 240, 4)) {
            frames.add(encoder.encodeFrame(new VideoFrame(picture, Packet.NO_TIMESTAMP, 0, true)).data());
        }
        var decoder = new DnxhdDecoder(DnxhdDecoderOptions.builder().threads(1).build());
        return JitWorkload.named("dnxhddec", () -> {
            for (var frame : frames) {
                sink += decoder.decodeFrame(frame, 0, frame.length).width();
            }
            return frames.size();
        });
    }

    static JitWorkload.Workload dnxhdEncode() {
        var pictures = JitWorkload.synthesize(320, 240, 2);
        var encoder = new DnxhdEncoder(320, 240, DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQ).toBuilder()
                .threads(1).build());
        return JitWorkload.named("dnxhdenc", () -> {
            for (var picture : pictures) {
                sink += encoder.encodeFrame(new VideoFrame(picture, Packet.NO_TIMESTAMP, 0, true)).size();
            }
            return pictures.size();
        });
    }
}
