package com.github.auties00.smalt.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.container.DecryptionKeys;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.mp4.CommonEncryption;
import com.github.auties00.smalt.container.mp4.FragmentedMp4Muxer;
import com.github.auties00.smalt.container.mp4.FragmentedMp4MuxerOptions;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;
import com.github.auties00.smalt.testkit.fuzz.FuzzPackets;
import com.github.auties00.smalt.testkit.fuzz.FuzzRun;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Fuzz target of Common Encryption muxing, whose input is in the {@link FuzzPackets} format: the fragmented MP4
 * muxer encrypts a video track, whose codec the options bits 0 to 2 choose (H.264, HEVC, VP9, AV1, MPEG-4 Part 2,
 * modulo five) and whose scheme bits 3 and 4 choose (cenc, cbc1, cens, cbcs), so that the subsample rules read
 * arbitrary slices, VP9 frames and AV1 temporal units. Whatever the muxer accepts, the demuxer must decrypt to the
 * samples of the same track muxed clear. The seeds are made by tools/testdata/cenc/make-fuzz-packets.py.
 */
class EncryptionFuzzTest {
    private static final Codec[] CODECS = {Codec.H264, Codec.HEVC, Codec.VP9, Codec.AV1, Codec.MPEG4_PART2};
    private static final byte[] KEY_ID = HexFormat.of().parseHex("0c1d2e3f405162738495a6b7c8d9eafb");
    private static final byte[] KEY = HexFormat.of().parseHex("8899aabbccddeeff0011223344556677");

    @FuzzTest
    void encryptedMux(byte[] data) {
        var run = Fuzz.start("Common Encryption muxing");
        var input = FuzzPackets.parse(data, FuzzPackets.VIDEO_FIELDS);
        var header = input.header();
        var codec = CODECS[(header.options() & 7) % CODECS.length];
        var scheme = CommonEncryption.Scheme.values()[(header.options() >>> 3) & 3];
        var builder = VideoTrack.builder().id(1).codec(codec).timeBase(new Rational(1, 90_000))
                .width(Math.max(1, header.unsigned(1, 2))).height(Math.max(1, header.unsigned(3, 2)));
        header.config().ifPresent(builder::codecConfig);
        var track = builder.build();
        var packets = new ArrayList<Packet>();
        for (var index = 0; index < Math.min(input.packets().size(), 64); index++) {
            var part = input.packets().get(index);
            var pts = index * 3000L;
            packets.add(new Packet(1, part.data(), pts, pts, 3000, part.keyframe() || index == 0));
        }
        var clear = mux(run, track, packets, null);
        if (clear == null) {
            return;
        }
        var encrypted = mux(run, track, packets, CommonEncryption.builder().scheme(scheme).keyId(KEY_ID).key(KEY)
                .build());
        if (encrypted == null) {
            return;
        }
        var expected = new ArrayList<byte[]>();
        if (!run.step("demux clear", () -> expected.addAll(demux(run, clear)))) {
            return;
        }
        run.step("decrypt", () -> {
            var decrypted = demux(run, encrypted);
            if (decrypted.size() != expected.size()) {
                throw new IllegalStateException("the clear file has " + expected.size() + " samples, the encrypted "
                        + decrypted.size());
            }
            for (var index = 0; index < expected.size(); index++) {
                if (!Arrays.equals(expected.get(index), decrypted.get(index))) {
                    throw new IllegalStateException("sample " + index + " does not decrypt to the clear sample");
                }
            }
        });
    }

    /**
     * Muxes a track to a fragmented MP4 file, or returns {@code null} when the muxer rejects the input.
     */
    private static byte[] mux(FuzzRun run, VideoTrack track, List<Packet> packets, CommonEncryption encryption) {
        var options = FragmentedMp4MuxerOptions.builder().fragmentMicros(100_000);
        if (encryption != null) {
            options.encryption(encryption);
        }
        var out = new ByteArrayOutputStream();
        try {
            var muxed = run.step(encryption == null ? "mux clear" : "mux encrypted", () -> {
                try (var sink = MediaSink.of(out); var muxer = new FragmentedMp4Muxer(sink, options.build())) {
                    muxer.addTrack(track);
                    for (var packet : packets) {
                        muxer.writePacket(packet);
                    }
                    muxer.finish();
                }
            });
            return muxed ? out.toByteArray() : null;
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            // the muxer's documented rejection of a track or packet it cannot write
            run.skip("the muxer rejects the input: " + rejected.getMessage());
            return null;
        }
    }

    private static List<byte[]> demux(FuzzRun run, byte[] file) {
        var result = new ArrayList<byte[]>();
        try (var source = MediaSource.of(file); var demuxer = new Mp4Demuxer(source, DemuxerOptions.builder()
                .decryptionKeys(DecryptionKeys.of(KEY_ID, KEY)).limits(Fuzz.LIMITS).build())) {
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                result.add(packet.data());
                run.output();
            }
        }
        return result;
    }
}
