package com.github.auties00.smalt.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.container.DecryptionKeys;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.mp4.CommonEncryption;
import com.github.auties00.smalt.container.mp4.FragmentedMp4Muxer;
import com.github.auties00.smalt.container.mp4.FragmentedMp4MuxerOptions;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.container.ts.TsDemuxer;
import com.github.auties00.smalt.container.ts.TsDemuxerOptions;
import com.github.auties00.smalt.container.ts.TsMuxer;
import com.github.auties00.smalt.container.ts.TsMuxerOptions;
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
 *
 * <p>The SAMPLE-AES targets cover the transport stream demuxer's decryption: {@link #sampleAesMux} muxes the H.264
 * packets of a {@link FuzzPackets} input clear and SAMPLE-AES encrypted and must demux both to the same packets, so
 * that arbitrary slices (odd lengths, emulation prevention bytes anywhere) are decrypted; {@link #sampleAesDemux}
 * demuxes an encrypted transport stream (seeds by {@code FuzzSeedWriter}: the committed clip encrypted by the muxer and
 * by the test encryptor, and cuts of them inside encrypted slices and frames) with its key.
 */
class EncryptionFuzzTest {
    private static final Codec[] CODECS = {Codec.H264, Codec.HEVC, Codec.VP9, Codec.AV1, Codec.MPEG4_PART2};
    private static final byte[] KEY_ID = HexFormat.of().parseHex("0c1d2e3f405162738495a6b7c8d9eafb");
    private static final byte[] KEY = HexFormat.of().parseHex("8899aabbccddeeff0011223344556677");
    private static final byte[] SAMPLE_AES_IV = HexFormat.of().parseHex("f0e1d2c3b4a5968778695a4b3c2d1e0f");

    @FuzzTest
    void sampleAesMux(byte[] data) {
        var run = Fuzz.start("SAMPLE-AES transport stream muxing");
        var input = FuzzPackets.parse(data, FuzzPackets.VIDEO_FIELDS);
        var header = input.header();
        var builder = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(new Rational(1, 90_000))
                .width(Math.max(1, header.unsigned(1, 2))).height(Math.max(1, header.unsigned(3, 2)));
        header.config().ifPresent(builder::codecConfig);
        var track = builder.build();
        var packets = new ArrayList<Packet>();
        for (var index = 0; index < Math.min(input.packets().size(), 64); index++) {
            var part = input.packets().get(index);
            var pts = index * 3000L;
            packets.add(new Packet(1, part.data(), pts, pts, 3000, part.keyframe() || index == 0));
        }
        var clear = muxTs(run, track, packets, TsMuxerOptions.defaults());
        if (clear == null) {
            return;
        }
        if (!annexBCarries(packets, track.codecConfig().orElse(null))) {
            // the clear stream splits such a NAL unit at its start code emulation, the escaped encrypted one keeps it
            run.skip("a NAL unit holds a start code emulation, which an Annex B byte stream cannot carry");
            return;
        }
        var encrypted = muxTs(run, track, packets, TsMuxerOptions.builder().sampleAes(KEY, SAMPLE_AES_IV).build());
        if (encrypted == null) {
            return;
        }
        var expected = new ArrayList<String>();
        if (!run.step("demux clear", () -> expected.addAll(demuxTs(run, clear, TsDemuxerOptions.defaults())))) {
            return;
        }
        run.step("decrypt", () -> {
            var decrypted = demuxTs(run, encrypted, TsDemuxerOptions.builder().limits(Fuzz.LIMITS)
                    .sampleAes(KEY, SAMPLE_AES_IV).build());
            if (!decrypted.equals(expected)) {
                throw new IllegalStateException("the encrypted stream does not decrypt to the clear one: "
                        + expected.size() + " clear packets, " + decrypted.size() + " decrypted");
            }
        });
    }

    @FuzzTest
    void sampleAesDemux(byte[] data) {
        var run = Fuzz.start("SAMPLE-AES transport stream demuxing");
        run.step("demux", () -> {
            var options = TsDemuxerOptions.builder().limits(Fuzz.LIMITS).sampleAes(KEY, SAMPLE_AES_IV).build();
            try (var source = MediaSource.of(data); var demuxer = new TsDemuxer(source, options)) {
                var count = 0;
                for (var packet = demuxer.read(); packet != null && count < Fuzz.MAX_PACKETS; packet = demuxer.read()) {
                    packet.size();
                    run.output();
                    count++;
                }
                if (demuxer.isSeekable()) {
                    demuxer.seek(0);
                    for (var index = 0; index < 16 && demuxer.read() != null; index++) {
                        run.output();
                    }
                }
            }
        });
    }

    /**
     * Tells whether every NAL unit of the length-prefixed packets survives an Annex B byte stream: none holds
     * {@code 00 00 00}, {@code 00 00 01} or {@code 00 00 02}, and none ends with a zero byte, which a demuxer reads
     * as a trailing zero of the next start code.
     */
    private static boolean annexBCarries(List<Packet> packets, byte[] config) {
        var lengthSize = config != null && config.length > 4 && config[0] == 1 ? (config[4] & 3) + 1 : 4;
        for (var packet : packets) {
            var data = packet.data();
            var position = 0;
            while (data.length - position >= lengthSize) {
                long size = 0;
                for (var index = 0; index < lengthSize; index++) {
                    size = size << 8 | (data[position + index] & 0xFF);
                }
                var start = position + lengthSize;
                if (size > data.length - start) {
                    break;
                }
                var end = start + (int) size;
                if (end > start && data[end - 1] == 0) {
                    return false;
                }
                for (var index = start; index + 2 < end; index++) {
                    if (data[index] == 0 && data[index + 1] == 0 && (data[index + 2] & 0xFF) <= 2) {
                        return false;
                    }
                }
                position = end;
            }
        }
        return true;
    }

    /**
     * Muxes a track to a transport stream, or returns {@code null} when the muxer rejects the input.
     */
    private static byte[] muxTs(FuzzRun run, VideoTrack track, List<Packet> packets, TsMuxerOptions options) {
        var sink = MediaSink.memory();
        try {
            var muxed = run.step(options.sampleAesKey().isPresent() ? "mux encrypted" : "mux clear", () -> {
                try (var muxer = new TsMuxer(sink, options)) {
                    muxer.addTrack(track);
                    for (var packet : packets) {
                        muxer.writePacket(packet);
                    }
                    muxer.finish();
                }
            });
            return muxed ? sink.toByteArray() : null;
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            // the muxer's documented rejection of a track or packet it cannot write
            run.skip("the muxer rejects the input: " + rejected.getMessage());
            return null;
        }
    }

    private static List<String> demuxTs(FuzzRun run, byte[] file, TsDemuxerOptions options) {
        var result = new ArrayList<String>();
        try (var source = MediaSource.of(file); var demuxer = new TsDemuxer(source, options.toBuilder()
                .limits(Fuzz.LIMITS).build())) {
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                result.add(packet.pts() + " " + packet.flags() + " " + HexFormat.of().formatHex(packet.data()));
                run.output();
            }
        }
        return result;
    }

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
