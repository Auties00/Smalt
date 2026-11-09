package com.github.auties00.smalt.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.internal.codec.DolbyVisionConfigs;
import com.github.auties00.smalt.internal.dolbyvision.DolbyVisionVideoDecoder;
import com.github.auties00.smalt.internal.nal.DolbyVisionRpu;
import com.github.auties00.smalt.testkit.DolbyVisionRpus;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;
import com.github.auties00.smalt.testkit.fuzz.FuzzRun;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;

import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.function.Consumer;

/**
 * Fuzz targets of the Dolby Vision parsers: an unescaped reference processing unit, parsed as it is and as the
 * HEVC NAL unit of type 62 that carries it, and its first bytes read as a configuration record and as a transport
 * stream descriptor; and of the reconstruction: an RPU applied to a small picture of a scripted base layer decoder, its
 * packet framed as HEVC and as AV1.
 */
class DolbyVisionFuzzTest {
    /** Decodes a packet into one 10-bit picture of noise whose content follows the packet. */
    private static final class ScriptedBase implements VideoDecoder {
        @Override
        public void decode(Packet packet, Consumer<VideoFrame> output) {
            var seed = Arrays.hashCode(packet.data());
            var picture = Yuv16Picture.allocate(18, 10, ChromaFormat.YUV420, 10, false, ColorInfo.BT709_LIMITED);
            var random = new SplittableRandom(seed);
            for (var plane : new short[][]{picture.y(), picture.u(), picture.v()}) {
                for (var index = 0; index < plane.length; index++) {
                    plane[index] = (short) random.nextInt(1024);
                }
            }
            output.accept(new VideoFrame(picture, packet.pts(), packet.duration(), packet.keyframe()));
        }

        @Override
        public void flush(Consumer<VideoFrame> output) {
        }
    }

    /**
     * Hands a packet to the reconstructing decoder, twice to exercise reuse: after a first byte that names the framing,
     * an unescaped RPU to wrap in an HEVC NAL unit (bit 0 clear) or an AV1 temporal unit (bit 0 set).
     */
    @FuzzTest
    void reconstruct(byte[] input) {
        if (input.length == 0) {
            return;
        }
        var run = Fuzz.start("Dolby Vision reconstruction");
        var data = Arrays.copyOfRange(input, 1, input.length);
        if ((input[0] & 1) != 0) {
            run.step("av1", () -> reconstruct(Codec.AV1, true, data, run));
            run.step("av1 undeclared", () -> reconstruct(Codec.AV1, false, data, run));
            return;
        }
        var escaped = DolbyVisionRpus.escape(data);
        var nal = new byte[escaped.length + 2];
        nal[0] = 0x7C;
        nal[1] = 0x01;
        System.arraycopy(escaped, 0, nal, 2, escaped.length);
        var hevc = DolbyVisionRpus.appendNal(new byte[0], nal);
        run.step("hevc", () -> reconstruct(Codec.HEVC, true, hevc, run));
        run.step("hevc undeclared", () -> reconstruct(Codec.HEVC, false, hevc, run));
    }

    private static void reconstruct(Codec codec, boolean declared, byte[] data, FuzzRun run) {
        var options = VideoDecoderOptions.builder().threads(1).highBitDepth(data.length % 2 == 0).build();
        try (var decoder = new DolbyVisionVideoDecoder(codec, null, declared, options, ignored -> new ScriptedBase())) {
            Consumer<VideoFrame> sink = frame -> {
                if (frame.picture().width() != 18 || frame.picture().height() != 10) {
                    throw new AssertionError("picture of " + frame.picture().width() + "x" + frame.picture().height());
                }
                run.output();
            };
            decoder.decode(new Packet(0, data, 0, 0, 1, true), sink);
            decoder.decode(new Packet(0, data, 1, 1, 1, false), sink);
            decoder.flush(sink);
        }
    }

    @FuzzTest
    void rpu(byte[] data) {
        var run = Fuzz.start("Dolby Vision RPU");
        run.step("parse", () -> {
            var rpu = DolbyVisionRpu.parse(data, 0, data.length);
            if (rpu.profile() < 0 || rpu.baseLayerBitDepth() != 10) {
                throw new AssertionError("profile " + rpu.profile() + ", base layer depth " + rpu.baseLayerBitDepth());
            }
            run.output();
        });
        run.step("hevc nal", () -> {
            var escaped = DolbyVisionRpus.escape(data);
            var nal = new byte[escaped.length + 2];
            nal[0] = 0x7C;
            nal[1] = 0x01;
            System.arraycopy(escaped, 0, nal, 2, escaped.length);
            var rpu = DolbyVisionRpu.tryParseHevcNal(nal, 0, nal.length);
            if (rpu == null) {
                throw new MediaException.Malformed("not an RPU NAL unit");
            }
            run.output();
        });
        run.step("configurations", () -> {
            var record = DolbyVisionConfigs.parseRecord(data, 0, Math.min(data.length, DolbyVisionConfigs.RECORD_LENGTH));
            var descriptor = DolbyVisionConfigs.parseDescriptor(data, 0, Math.min(data.length, 7));
            if (record != null && !record.equals(DolbyVisionConfigs.parseRecord(DolbyVisionConfigs.record(record)))) {
                throw new AssertionError("record round trip of " + record);
            }
            if (descriptor != null) {
                var written = DolbyVisionConfigs.descriptor(descriptor, 0x100);
                if (!descriptor.equals(DolbyVisionConfigs.parseDescriptor(written, 0, written.length))) {
                    throw new AssertionError("descriptor round trip of " + descriptor);
                }
            }
            run.output();
        });
    }
}
