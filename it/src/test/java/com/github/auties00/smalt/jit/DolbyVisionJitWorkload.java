package com.github.auties00.smalt.jit;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.testkit.DolbyVisionReference;
import com.github.auties00.smalt.testkit.DolbyVisionRpus;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DolbyVision;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.hevc.encoder.HevcEncoder;
import com.github.auties00.smalt.video.hevc.encoder.HevcEncoderOptions;
import com.github.auties00.smalt.video.hevc.encoder.HevcEncoderSpeed;
import com.github.auties00.smalt.video.hevc.encoder.HevcProfile;

import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * The steady state of Dolby Vision reconstruction: a profile 5 stream of 640x360 pictures, an RPU with regression chroma
 * mappings on each, decoded through {@code VideoDecoders} round after round.
 */
final class DolbyVisionJitWorkload {
    private static int sink;

    private DolbyVisionJitWorkload() {
        throw new AssertionError();
    }

    static JitWorkload.Workload reconstruct(int threads) {
        var color = new ColorInfo(MatrixCoefficients.UNSPECIFIED, ColorRange.FULL, ColorPrimaries.UNSPECIFIED,
                TransferCharacteristics.UNSPECIFIED);
        var packets = new ArrayList<Packet>();
        byte[] config;
        try (var encoder = new HevcEncoder(640, 360, HevcEncoderOptions.builder().profile(HevcProfile.MAIN_10).bitDepth(10)
                .speed(HevcEncoderSpeed.FASTEST).build())) {
            for (var frame = 0; frame < 4; frame++) {
                var picture = Yuv16Picture.allocate(640, 360, ChromaFormat.YUV420, 10, false, color);
                for (var row = 0; row < 360; row++) {
                    for (var x = 0; x < 640; x++) {
                        picture.y()[row * picture.yStride() + x] = (short) (100 + x + row / 2 + 8 * frame);
                    }
                }
                for (var row = 0; row < 180; row++) {
                    for (var x = 0; x < 320; x++) {
                        picture.u()[row * picture.uvStride() + x] = (short) (300 + x + frame);
                        picture.v()[row * picture.uvStride() + x] = (short) (700 - row);
                    }
                }
                encoder.encode(new VideoFrame(picture, frame, 1, frame == 0), packets::add);
            }
            encoder.flush(packets::add);
            config = encoder.codecConfig().orElseThrow();
        }
        var regression = new long[22];
        regression[0] = 1L << 16;
        regression[2] = 1L << 23;
        regression[9] = 1L << 18;
        regression[21] = -(1L << 18);
        var luma = new DolbyVisionReference.Curve(new int[]{0, 1023}, new DolbyVisionReference.Piece[]{
                new DolbyVisionReference.Piece(false, 2, new long[]{0, 1L << 23, -(1L << 18)})});
        var chroma = new DolbyVisionReference.Curve(new int[]{0, 1023}, new DolbyVisionReference.Piece[]{
                new DolbyVisionReference.Piece(true, 3, regression)});
        var mapping = new DolbyVisionReference.Mapping(0, 23, new DolbyVisionReference.Curve[]{luma, chroma, chroma});
        var withRpus = new ArrayList<Packet>();
        for (var packet : packets) {
            withRpus.add(packet.withData(DolbyVisionRpus.appendNal(packet.data(),
                    DolbyVisionRpus.of(DolbyVisionRpus.Kind.PROFILE_5).mapping(mapping).iptPqC2().hevcNal())));
        }
        var track = VideoTrack.builder().id(1).codec(Codec.HEVC).timeBase(new Rational(1, 30)).width(640).height(360)
                .codecConfig(config).dolbyVision(DolbyVision.of(5, 1, 0)).build();
        var decoder = VideoDecoders.create(track, VideoDecoderOptions.builder().threads(threads).highBitDepth(true).build());
        int[] count = {0};
        Consumer<VideoFrame> output = frame -> {
            count[0]++;
            sink += ((Yuv16Picture) frame.picture()).y()[17];
        };
        return JitWorkload.named("dvrec-t" + threads, () -> {
            count[0] = 0;
            for (var packet : withRpus) {
                decoder.decode(packet, output);
            }
            decoder.flush(output);
            return count[0];
        });
    }
}
