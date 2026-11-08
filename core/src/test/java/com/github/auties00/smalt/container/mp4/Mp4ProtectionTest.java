package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.DecryptionKeys;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.internal.cenc.CencCipher;
import com.github.auties00.smalt.internal.cenc.CencScheme;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import static com.github.auties00.smalt.container.mp4.Mp4Builder.audioEntry;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.box;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.concat;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.fourCc;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.ftyp;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.full;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.mvhd;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.sinf;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.stsz;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.table;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.tenc;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.trak;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.u16;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.u32;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.u64;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.u8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Builds protected MP4 files box by box, their samples encrypted with the sample cipher, and checks that the demuxer
 * finds every sample's initialization vector, subsample map and key: in a {@code senc} box of the sample table, in
 * the {@code mdat} through {@code saiz} and {@code saio} (one offset, or one per chunk), with keys of {@code seig}
 * sample groups, with constant initialization vectors, and in the PIFF sample encryption box of a fragment.
 */
@DisplayName("MP4 protected sample layouts")
class Mp4ProtectionTest {
    private static final byte[] KID_1 = HexFormat.of().parseHex("00112233445566778899aabbccddeeff");
    private static final byte[] KEY_1 = HexFormat.of().parseHex("0f0e0d0c0b0a09080706050403020100");
    private static final byte[] KID_2 = HexFormat.of().parseHex("ffeeddccbbaa99887766554433221100");
    private static final byte[] KEY_2 = HexFormat.of().parseHex("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf");
    private static final DecryptionKeys KEYS = DecryptionKeys.ofHex(Map.of(HexFormat.of().formatHex(KID_1),
            HexFormat.of().formatHex(KEY_1), HexFormat.of().formatHex(KID_2), HexFormat.of().formatHex(KEY_2)));
    private static final int SAMPLES = 12;

    /**
     * One encrypted sample: the clear bytes, the stored bytes, its initialization vector and subsample map.
     */
    private record Sample(byte[] plain, byte[] stored, byte[] iv, int[] subsamples, int group) {
        byte[] auxiliary() {
            var out = new ByteArrayOutputStream();
            out.writeBytes(iv);
            if (subsamples != null) {
                out.writeBytes(u16(subsamples.length / 2));
                for (var pair = 0; pair < subsamples.length / 2; pair++) {
                    out.writeBytes(u16(subsamples[2 * pair]));
                    out.writeBytes(u32(subsamples[2 * pair + 1]));
                }
            }
            return out.toByteArray();
        }
    }

    private static List<Sample> encrypt(CencScheme scheme, int ivSize, byte[] constantIv, int crypt, int skip,
                                        boolean subsamples, boolean groups, long seed) {
        var random = new Random(seed);
        var result = new ArrayList<Sample>();
        for (var index = 0; index < SAMPLES; index++) {
            var size = 4 * (8 + random.nextInt(120));
            var plain = new byte[size];
            random.nextBytes(plain);
            var iv = new byte[ivSize];
            random.nextBytes(iv);
            int[] map = null;
            if (subsamples) {
                var pairs = new ArrayList<Integer>();
                var left = size;
                while (left > 0) {
                    var clear = Math.min(left, random.nextInt(30));
                    left -= clear;
                    var protectedBytes = Math.min(left, random.nextInt(200));
                    left -= protectedBytes;
                    pairs.add(clear);
                    pairs.add(protectedBytes);
                }
                map = pairs.stream().mapToInt(Integer::intValue).toArray();
            }
            var group = groups && index % 3 == 1 ? 1 : 0;
            var stored = plain.clone();
            var key = group == 1 ? KEY_2 : KEY_1;
            var vector = ivSize > 0 ? iv : constantIv;
            new CencCipher(key).encrypt(scheme, stored, 0, size, vector, 0, vector.length, map, 0,
                    map == null ? 0 : map.length / 2, crypt, skip);
            result.add(new Sample(plain, stored, iv, map, group));
        }
        return result;
    }

    private static byte[] protection(CencScheme scheme, int ivSize, byte[] constantIv, int crypt, int skip) {
        var version = scheme.usesPattern() ? 1 : 0;
        return sinf("sowt", scheme.name().toLowerCase(Locale.ROOT),
                tenc(version, crypt, skip, true, ivSize, KID_1, ivSize == 0 ? constantIv : null));
    }

    private static byte[] groupBoxes(List<Sample> samples, int ivSize, byte[] constantIv, int crypt, int skip) {
        var constant = ivSize == 0 ? concat(u8(constantIv.length), constantIv) : new byte[0];
        var entry = concat(u8(0), u8(crypt << 4 | skip), u8(1), u8(ivSize), KID_2, constant);
        var sgpd = full("sgpd", 1, 0, fourCc("seig"), u32(entry.length), u32(1), entry);
        var runs = new ByteArrayOutputStream();
        var count = 0;
        var index = 0;
        while (index < samples.size()) {
            var group = samples.get(index).group();
            var run = 0;
            while (index < samples.size() && samples.get(index).group() == group) {
                run++;
                index++;
            }
            runs.writeBytes(u32(run));
            runs.writeBytes(u32(group));
            count++;
        }
        var sbgp = full("sbgp", 0, 0, fourCc("seig"), u32(count), runs.toByteArray());
        return concat(sgpd, sbgp);
    }

    /**
     * Builds a progressive file whose samples sit in two chunks, the auxiliary information in a {@code senc} box of
     * the sample table, or after the samples in the {@code mdat} with one {@code saio} offset or one per chunk.
     */
    private static byte[] progressive(CencScheme scheme, int ivSize, byte[] constantIv, int crypt, int skip,
                                      List<Sample> samples, String layout, boolean groups) {
        var data = new ByteArrayOutputStream();
        var sizes = new int[samples.size()];
        for (var index = 0; index < samples.size(); index++) {
            data.writeBytes(samples.get(index).stored());
            sizes[index] = samples.get(index).stored().length;
        }
        var start = Mp4Builder.DATA_START;
        var first = samples.size() / 2;
        var secondChunk = start;
        for (var index = 0; index < first; index++) {
            secondChunk += sizes[index];
        }
        var withSubsamples = samples.getFirst().subsamples() != null;
        var tables = new ArrayList<byte[]>(List.of(table("stts", 0, 2, samples.size(), 1024),
                table("stsc", 0, 3, 1, first, 1, 2, samples.size() - first, 1), stsz(0, sizes),
                table("stco", 0, 1, start, secondChunk)));
        var auxiliary = new ByteArrayOutputStream();
        var infoSizes = new ByteArrayOutputStream();
        var secondInfo = 0;
        for (var index = 0; index < samples.size(); index++) {
            if (index == first) {
                secondInfo = auxiliary.size();
            }
            var info = samples.get(index).auxiliary();
            auxiliary.writeBytes(info);
            infoSizes.writeBytes(u8(info.length));
        }
        if (groups) {
            tables.add(groupBoxes(samples, ivSize, constantIv, crypt, skip));
        }
        switch (layout) {
            case "senc" -> tables.add(full("senc", 0, withSubsamples ? 2 : 0, u32(samples.size()),
                    auxiliary.toByteArray()));
            case "mdat", "chunks" -> {
                var auxStart = start + data.size();
                data.writeBytes(auxiliary.toByteArray());
                tables.add(full("saiz", 0, 0, u8(0), u32(samples.size()), infoSizes.toByteArray()));
                tables.add(layout.equals("mdat") ? full("saio", 0, 0, u32(1), u32(auxStart))
                        : full("saio", 1, 0, u32(2), u64(auxStart), u64(auxStart + secondInfo)));
            }
            default -> throw new IllegalArgumentException(layout);
        }
        var entry = audioEntry("enca", 2, 44100, protection(scheme, ivSize, constantIv, crypt, skip));
        return Mp4Builder.file(data.toByteArray(), mvhd(1000, 0),
                trak(1, "soun", 44100, 0, entry, tables.toArray(new byte[0][])));
    }

    private static List<Packet> demux(byte[] file, DecryptionKeys keys) {
        try (var demuxer = new Mp4Demuxer(MediaSource.of(file), DemuxerOptions.builder().decryptionKeys(keys).build())) {
            var packets = new ArrayList<Packet>();
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                packets.add(packet);
            }
            return packets;
        }
    }

    private static void assertDecrypts(byte[] file, List<Sample> samples) {
        var packets = demux(file, KEYS);
        assertEquals(samples.size(), packets.size(), "packet count");
        for (var index = 0; index < samples.size(); index++) {
            assertArrayEquals(samples.get(index).plain(), packets.get(index).data(), "sample " + index);
        }
    }

    @ParameterizedTest(name = "{0} {1}-byte IV, subsamples {2}, {3}, groups {4}")
    @CsvSource({
            "CENC, 8, false, senc, false",
            "CENC, 16, true, senc, false",
            "CENC, 8, true, mdat, true",
            "CBC1, 16, false, mdat, false",
            "CBC1, 16, true, chunks, true",
            "CENS, 8, true, senc, true",
            "CENS, 16, true, chunks, false",
            "CBCS, 16, true, mdat, true",
            "CBCS, 0, true, senc, false",
            "CBCS, 0, false, senc, true",
    })
    @DisplayName("decrypts the samples of a progressive file")
    void progressiveLayouts(CencScheme scheme, int ivSize, boolean subsamples, String layout, boolean groups) {
        var constantIv = HexFormat.of().parseHex("0123456789abcdeffedcba9876543210");
        var crypt = scheme.usesPattern() ? 1 : 0;
        var skip = scheme.usesPattern() ? 9 : 0;
        var samples = encrypt(scheme, ivSize, constantIv, crypt, skip, subsamples, groups, scheme.ordinal() * 7L + ivSize);
        assertDecrypts(progressive(scheme, ivSize, constantIv, crypt, skip, samples, layout, groups), samples);
    }

    @Test
    @DisplayName("rejects a sample whose subsamples do not cover it, and a key of a group it is not given")
    void failures() {
        var samples = encrypt(CencScheme.CENC, 8, null, 0, 0, true, true, 3);
        var file = progressive(CencScheme.CENC, 8, null, 0, 0, samples, "senc", true);
        var onlyFirst = DecryptionKeys.of(KID_1, KEY_1);
        assertEquals("mp4 encryption", assertThrows(MediaException.Unsupported.class, () -> demux(file, onlyFirst))
                .format());
        var broken = samples.stream().map(sample -> new Sample(sample.plain(), sample.stored(), sample.iv(),
                new int[]{1, sample.plain().length}, sample.group())).toList();
        assertThrows(MediaException.Malformed.class,
                () -> demux(progressive(CencScheme.CENC, 8, null, 0, 0, broken, "senc", true), KEYS));
    }

    @ParameterizedTest(name = "override {0}")
    @CsvSource({"false", "true"})
    @DisplayName("decrypts a fragment whose initialization vectors are in the PIFF sample encryption box")
    void piff(boolean override) {
        var encrypted = encrypt(CencScheme.CENC, 8, null, 0, 0, true, false, 11);
        if (override) {
            // the box overrides the track defaults with 16-byte initialization vectors and the second key
            var random = new Random(5);
            encrypted = encrypted.stream().map(sample -> {
                var iv = new byte[16];
                random.nextBytes(iv);
                var stored = sample.plain().clone();
                var map = sample.subsamples();
                new CencCipher(KEY_2).encrypt(CencScheme.CENC, stored, 0, stored.length, iv, 0, 16, map, 0,
                        map.length / 2, 0, 0);
                return new Sample(sample.plain(), stored, iv, map, 1);
            }).toList();
        }
        var samples = encrypted;
        var piffTenc = box("uuid", HexFormat.of().parseHex("8974dbce7be74c5184f97148f9882554"), u32(0), u8(0), u16(1),
                u8(8), KID_1);
        var protection = box("sinf", box("frma", fourCc("sowt")), full("schm", 0, 0, fourCc("piff"), u32(0x10001)),
                box("schi", piffTenc));
        var moov = box("moov", mvhd(1000, 0), trak(1, "soun", 44100, 0, audioEntry("enca", 2, 44100, protection),
                        table("stts", 0, 2), table("stsc", 0, 3), stsz(0), table("stco", 0, 1)),
                box("mvex", full("trex", 0, 0, u32(1), u32(1), u32(1024), u32(0), u32(0))));
        var data = new ByteArrayOutputStream();
        var sizes = new ByteArrayOutputStream();
        var auxiliary = new ByteArrayOutputStream();
        for (var sample : samples) {
            data.writeBytes(sample.stored());
            sizes.writeBytes(u32(sample.stored().length));
            auxiliary.writeBytes(sample.auxiliary());
        }
        byte[] moof = null;
        for (var offset = 0; ; ) {
            var trun = full("trun", 0, 0x201, u32(samples.size()), u32(offset), sizes.toByteArray());
            var senc = override
                    ? box("uuid", HexFormat.of().parseHex("a2394f525a9b4f14a2446c427c648df4"), u32(3), u8(0), u16(1),
                    u8(16), KID_2, u32(samples.size()), auxiliary.toByteArray())
                    : box("uuid", HexFormat.of().parseHex("a2394f525a9b4f14a2446c427c648df4"), u32(2),
                    u32(samples.size()), auxiliary.toByteArray());
            moof = box("moof", full("mfhd", 0, 0, u32(1)), box("traf", full("tfhd", 0, 0x20000, u32(1)),
                    full("tfdt", 1, 0, u64(0)), trun, senc));
            if (offset == moof.length + 8) {
                break;
            }
            offset = moof.length + 8;
        }
        var file = concat(ftyp(), moov, moof, box("mdat", data.toByteArray()));
        assertDecrypts(file, samples);
        // the samples need the key of the identifier that protects them: the track's, or the override's
        var other = override ? DecryptionKeys.of(KID_1, KEY_1) : DecryptionKeys.of(KID_2, KEY_2);
        assertThrows(MediaException.Unsupported.class, () -> demux(file, other));
    }

    /**
     * One sample protected with several keys: the clear bytes, the stored bytes, the initialization vector of each
     * key (empty for a key with a constant one), and the subsamples as key, clear and protected triples.
     */
    private record MultiKeySample(byte[] plain, byte[] stored, byte[][] ivs, int[] subsamples) {
        byte[] auxiliary(boolean withSubsamples) {
            var out = new ByteArrayOutputStream();
            var count = 0;
            for (var iv : ivs) {
                count += iv.length > 0 ? 1 : 0;
            }
            out.writeBytes(u16(count));
            for (var key = 0; key < ivs.length; key++) {
                if (ivs[key].length > 0) {
                    out.writeBytes(u16(key + 1));
                    out.writeBytes(ivs[key]);
                }
            }
            if (withSubsamples) {
                out.writeBytes(u32(subsamples.length / 3));
                for (var index = 0; index < subsamples.length; index += 3) {
                    out.writeBytes(u16(subsamples[index]));
                    out.writeBytes(u16(subsamples[index + 1]));
                    out.writeBytes(u32(subsamples[index + 2]));
                }
            }
            return out.toByteArray();
        }
    }

    /**
     * Encrypts samples with two keys, each subsample with the key it names (0 for a clear one), each key's
     * subsamples as one sample whose other subsamples are clear.
     */
    private static List<MultiKeySample> encryptMultiKey(CencScheme scheme, int[] ivSizes, byte[] constantIv,
                                                        int crypt, int skip, long seed) {
        var random = new Random(seed);
        var keys = new byte[][]{KEY_1, KEY_2};
        var result = new ArrayList<MultiKeySample>();
        for (var index = 0; index < SAMPLES; index++) {
            var size = 8 * (16 + random.nextInt(100));
            var plain = new byte[size];
            random.nextBytes(plain);
            var triples = new ArrayList<Integer>();
            for (var left = size; left > 0; ) {
                var key = random.nextInt(3);
                var clear = Math.min(left, random.nextInt(40));
                left -= clear;
                var protectedBytes = key == 0 ? 0 : Math.min(left, random.nextInt(300));
                left -= protectedBytes;
                triples.add(key);
                triples.add(clear);
                triples.add(protectedBytes);
            }
            var subsamples = triples.stream().mapToInt(Integer::intValue).toArray();
            var stored = plain.clone();
            var ivs = new byte[2][];
            for (var key = 1; key <= 2; key++) {
                ivs[key - 1] = new byte[ivSizes[key - 1]];
                random.nextBytes(ivs[key - 1]);
                var pairs = new int[subsamples.length / 3 * 2];
                for (var triple = 0; triple < subsamples.length / 3; triple++) {
                    var own = subsamples[3 * triple] == key;
                    pairs[2 * triple] = own ? subsamples[3 * triple + 1]
                            : subsamples[3 * triple + 1] + subsamples[3 * triple + 2];
                    pairs[2 * triple + 1] = own ? subsamples[3 * triple + 2] : 0;
                }
                var vector = ivs[key - 1].length > 0 ? ivs[key - 1] : constantIv;
                new CencCipher(keys[key - 1]).encrypt(scheme, stored, 0, size, vector, 0, vector.length, pairs, 0,
                        pairs.length / 2, crypt, skip);
            }
            result.add(new MultiKeySample(plain, stored, ivs, subsamples));
        }
        return result;
    }

    /**
     * Builds a progressive file of multi-key samples: no tenc box, a multi-key seig group every sample belongs to,
     * and the auxiliary information in a version 1 senc box or in the mdat through saiz and saio.
     */
    private static byte[] multiKeyFile(CencScheme scheme, int[] ivSizes, byte[] constantIv, int crypt, int skip,
                                       List<MultiKeySample> samples, boolean senc, int keyCount) {
        var data = new ByteArrayOutputStream();
        var sizes = new int[samples.size()];
        var auxiliary = new ByteArrayOutputStream();
        var infoSizes = new ByteArrayOutputStream();
        for (var index = 0; index < samples.size(); index++) {
            data.writeBytes(samples.get(index).stored());
            sizes[index] = samples.get(index).stored().length;
            var info = samples.get(index).auxiliary(true);
            auxiliary.writeBytes(info);
            infoSizes.writeBytes(u8(info.length));
        }
        var start = Mp4Builder.DATA_START;
        var tables = new ArrayList<byte[]>(List.of(table("stts", 0, 2, samples.size(), 1024),
                table("stsc", 0, 3, 1, samples.size(), 1), stsz(0, sizes), table("stco", 0, 1, start)));
        var entry = new ByteArrayOutputStream();
        entry.writeBytes(concat(u8(0x80), u8(crypt << 4 | skip), u8(1), u16(keyCount)));
        var ids = new byte[][]{KID_1, KID_2};
        for (var key = 0; key < keyCount; key++) {
            entry.writeBytes(u8(ivSizes[key]));
            entry.writeBytes(ids[key]);
            if (ivSizes[key] == 0) {
                entry.writeBytes(u8(constantIv.length));
                entry.writeBytes(constantIv);
            }
        }
        tables.add(full("sgpd", 1, 0, fourCc("seig"), u32(entry.size()), u32(1), entry.toByteArray()));
        tables.add(full("sbgp", 0, 0, fourCc("seig"), u32(1), u32(samples.size()), u32(1)));
        if (senc) {
            tables.add(full("senc", 1, 2, u32(samples.size()), auxiliary.toByteArray()));
        } else {
            var auxStart = start + data.size();
            data.writeBytes(auxiliary.toByteArray());
            tables.add(full("saiz", 0, 1, u32(0), u32(1), u8(0), u32(samples.size()), infoSizes.toByteArray()));
            tables.add(full("saio", 0, 0, u32(1), u32(auxStart)));
        }
        var protection = box("sinf", box("frma", fourCc("sowt")), full("schm", 0, 0,
                fourCc(scheme.name().toLowerCase(Locale.ROOT)), u32(0x10000)), box("schi"));
        var sampleEntry = audioEntry("enca", 2, 44100, protection);
        return Mp4Builder.file(data.toByteArray(), mvhd(1000, 0),
                trak(1, "soun", 44100, 0, sampleEntry, tables.toArray(new byte[0][])));
    }

    @ParameterizedTest(name = "{0}, IV sizes {1} and {2}, senc {3}")
    @CsvSource({
            "CENC, 16, 16, true",
            "CENC, 8, 16, false",
            "CBC1, 16, 16, false",
            "CENS, 8, 8, true",
            "CBCS, 0, 0, true",
            "CBCS, 16, 0, false",
    })
    @DisplayName("decrypts multi-key samples, each subsample with the key it names")
    void multiKey(CencScheme scheme, int firstIv, int secondIv, boolean senc) {
        var constantIv = HexFormat.of().parseHex("0123456789abcdeffedcba9876543210");
        var crypt = scheme.usesPattern() ? 1 : 0;
        var skip = scheme.usesPattern() ? 9 : 0;
        var ivSizes = new int[]{firstIv, secondIv};
        var samples = encryptMultiKey(scheme, ivSizes, constantIv, crypt, skip, scheme.ordinal() * 13L + firstIv);
        var file = multiKeyFile(scheme, ivSizes, constantIv, crypt, skip, samples, senc, 2);
        var packets = demux(file, KEYS);
        assertEquals(samples.size(), packets.size());
        for (var index = 0; index < samples.size(); index++) {
            assertArrayEquals(samples.get(index).plain(), packets.get(index).data(), "sample " + index);
        }
        assertEquals("mp4 encryption", assertThrows(MediaException.Unsupported.class,
                () -> demux(file, DecryptionKeys.of(KID_1, KEY_1))).format());
    }

    @Test
    @DisplayName("rejects multi-key samples naming a key their group lacks, and a protected sample of no group")
    void multiKeyFailures() {
        var ivSizes = new int[]{16, 16};
        var samples = encryptMultiKey(CencScheme.CENC, ivSizes, null, 0, 0, 5);
        // the group lists one key, the subsamples name two
        assertThrows(MediaException.Malformed.class, () -> demux(multiKeyFile(CencScheme.CENC, ivSizes, null, 0, 0,
                samples, true, 1), KEYS));
        // no tenc and no seig group: the samples have no protection parameters
        var data = new ByteArrayOutputStream();
        var sizes = new int[samples.size()];
        for (var index = 0; index < samples.size(); index++) {
            data.writeBytes(samples.get(index).stored());
            sizes[index] = samples.get(index).stored().length;
        }
        var protection = box("sinf", box("frma", fourCc("sowt")), full("schm", 0, 0, fourCc("cenc"),
                u32(0x10000)), box("schi"));
        var file = Mp4Builder.file(data.toByteArray(), mvhd(1000, 0), trak(1, "soun", 44100, 0,
                audioEntry("enca", 2, 44100, protection), table("stts", 0, 2, samples.size(), 1024),
                table("stsc", 0, 3, 1, samples.size(), 1), stsz(0, sizes), table("stco", 0, 1,
                        Mp4Builder.DATA_START)));
        assertThrows(MediaException.Malformed.class, () -> demux(file, KEYS));
    }
}
