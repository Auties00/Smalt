/* Ports Shaka Packager v3.9.3 (packager/media/crypto/subsample_generator.cc, the subsample rules of NAL-structured,
 * VP9 and AV1 video, and encryption_handler.cc, the pattern of audio). Copyright 2018 Google LLC; BSD 3-Clause
 * License. Modified for Smalt. */
package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.cenc.Av1Tiles;
import com.github.auties00.smalt.internal.cenc.CencCipher;
import com.github.auties00.smalt.internal.cenc.CencScheme;
import com.github.auties00.smalt.internal.cenc.SliceHeaders;
import com.github.auties00.smalt.internal.cenc.Vp9Frames;
import com.github.auties00.smalt.internal.codec.NalConfigs;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.TrackKind;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Encrypts the samples of one track of a {@link FragmentedMp4Muxer} as {@link CommonEncryption} describes, and writes
 * the boxes that describe the encryption: the {@code sinf} of the sample entry and the {@code saiz}, {@code saio} and
 * {@code senc} of each track fragment. Instances are not thread-safe.
 */
final class TrackEncryptor {
    /**
     * The largest clear byte count of one subsample entry.
     */
    private static final int MAX_CLEAR = 0xFFFF;

    /**
     * The largest size of the auxiliary information of one sample, which a {@code saiz} box stores in one byte.
     */
    private static final int MAX_INFO = 0xFF;

    private final CommonEncryption options;
    private final CencScheme scheme;
    private final CencCipher cipher;

    /**
     * The size of the per-sample initialization vectors, 0 for a constant one.
     */
    private final int ivSize;

    /**
     * The initialization vector of the next sample, or the constant one, 16 bytes with the unused tail zero.
     */
    private final byte[] iv = new byte[16];
    private final int crypt;
    private final int skip;

    /**
     * The NAL unit length size of H.264 and HEVC samples, or 0 for the other codecs.
     */
    private final int lengthSize;
    private final SliceHeaders slices;
    private final Vp9Frames vp9;
    private final Av1Tiles av1;

    /**
     * The track, whose configuration an AV1 track may only gain from its first key frame.
     */
    private final MuxTrack track;
    private boolean av1Configured;

    /**
     * Whether the samples are video of a codec without subsample rules under a pattern scheme, which carry one
     * subsample covering the whole sample so that the pattern is applied to them unambiguously.
     */
    private final boolean wholeVideoSubsample;

    /**
     * Whether the protected bytes of a subsample are cut to whole blocks.
     */
    private final boolean aligned;

    private int[] pairs = new int[64];
    private int pairCount;
    private int pendingClear;

    private TrackEncryptor(CommonEncryption options, MuxTrack track, SecureRandom random) {
        this.options = options;
        this.scheme = switch (options.scheme()) {
            case CENC -> CencScheme.CENC;
            case CBC1 -> CencScheme.CBC1;
            case CENS -> CencScheme.CENS;
            case CBCS -> CencScheme.CBCS;
        };
        this.cipher = new CencCipher(options.key());
        var video = track.kind() == TrackKind.VIDEO;
        // audio takes every whole block: 1:0 for cens, whose 0:0 decryptors read differently, 0:0 for cbcs
        this.crypt = video && scheme.usesPattern() ? options.cryptBlocks() : scheme == CencScheme.CENS ? 1 : 0;
        this.skip = video && scheme.usesPattern() ? options.skipBlocks() : 0;
        var given = options.initializationVector().orElse(null);
        if (given == null) {
            given = new byte[scheme == CencScheme.CENC || scheme == CencScheme.CENS ? 8 : 16];
            random.nextBytes(given);
        }
        System.arraycopy(given, 0, iv, 0, given.length);
        this.ivSize = scheme == CencScheme.CBCS ? 0 : given.length;
        // VP9 frames are cut to whole blocks for every scheme, so that the counter of each frame of a superframe
        // can be computed (VP Codec ISO Media File Format Binding)
        this.aligned = scheme != CencScheme.CBCS || track.codec() == Codec.VP9;
        this.track = track;
        this.vp9 = track.codec() == Codec.VP9 ? new Vp9Frames() : null;
        this.av1 = track.codec() == Codec.AV1 ? new Av1Tiles() : null;
        this.wholeVideoSubsample = video && scheme.usesPattern() && vp9 == null && av1 == null
                && track.codec() != Codec.H264 && track.codec() != Codec.HEVC;
        if (video && (track.codec() == Codec.H264 || track.codec() == Codec.HEVC)) {
            var hevc = track.codec() == Codec.HEVC;
            var parsed = hevc ? NalConfigs.parseHevc(track.config) : NalConfigs.parseAvc(track.config);
            if (parsed == null) {
                throw new MediaException.Unsupported("mp4 encryption", "a " + track.codec().displayName()
                        + " track without its decoder configuration");
            }
            this.lengthSize = parsed.lengthSize();
            this.slices = new SliceHeaders(hevc);
            for (var set : parsed.parameterSets()) {
                slices.parameterSet(set, 0, set.length);
            }
        } else {
            this.lengthSize = 0;
            this.slices = null;
        }
    }

    /**
     * Returns the encryptor of a track, or {@code null} for a track that stays clear.
     *
     * @param options the encryption
     * @param track the track to encrypt
     * @param random the source of random initialization vectors
     * @return the encryptor, or {@code null} for a text or data track
     * @throws MediaException.Unsupported if the track is H.264 or HEVC video without its decoder configuration
     */
    static TrackEncryptor of(CommonEncryption options, MuxTrack track, SecureRandom random) {
        return switch (track.kind()) {
            case AUDIO, VIDEO -> new TrackEncryptor(options, track, random);
            default -> null;
        };
    }

    /**
     * {@return whether the samples carry subsample maps: those of H.264, HEVC, VP9 and AV1 video, and of the other
     * video codecs under a pattern scheme}
     */
    boolean usesSubsamples() {
        return lengthSize > 0 || vp9 != null || av1 != null || wholeVideoSubsample;
    }

    /**
     * Encrypts a sample into a new array and records its initialization vector and subsample map.
     *
     * @param data the clear sample, not modified
     * @param result receives the initialization vector, as the {@code senc} entry stores it, and the subsample map
     * @return the encrypted sample
     * @throws MediaException.Malformed if a NAL unit length runs past the sample
     * @throws MediaException.Unsupported if the sample needs so many subsamples that its auxiliary information
     * exceeds the 255 bytes a {@code saiz} box describes
     */
    byte[] encrypt(byte[] data, Encrypted result) {
        int[] map = null;
        if (lengthSize > 0) {
            map = subsamples(data);
        } else if (vp9 != null) {
            map = vp9Subsamples(data);
        } else if (av1 != null) {
            map = av1Subsamples(data);
        } else if (wholeVideoSubsample) {
            startMap();
            addSubsample(0, data.length);
            map = finishMap();
        }
        if (map != null && ivSize + 2 + 3 * map.length > MAX_INFO) {
            throw new MediaException.Unsupported("mp4 encryption", "a " + track.codec().displayName() + " sample of "
                    + map.length / 2 + " subsamples, whose auxiliary information exceeds the " + MAX_INFO
                    + " bytes of a saiz entry");
        }
        var out = data.clone();
        cipher.encrypt(scheme, out, 0, out.length, iv, 0, ivSize > 0 ? ivSize : 16, map, 0, map == null ? 0 : map.length / 2,
                crypt, skip);
        result.iv = ivSize > 0 ? Arrays.copyOf(iv, ivSize) : new byte[0];
        result.subsamples = map;
        if (ivSize == 8) {
            increment(iv, 0, 8, 1);
        } else if (ivSize == 16) {
            increment(iv, 8, 16, scheme.isCbc() ? 1 : (out.length + 15L) / 16);
        }
        return out;
    }

    /**
     * Divides a length-prefixed H.264 or HEVC sample into subsamples: slices are protected after their slice
     * header, every other NAL unit stays clear.
     */
    private int[] subsamples(byte[] sample) {
        startMap();
        var position = 0;
        while (position < sample.length) {
            if (sample.length - position < lengthSize) {
                throw new MediaException.Malformed("a NAL unit length runs past the end of the sample");
            }
            var size = NalUnits.readLength(sample, position, lengthSize);
            var start = position + lengthSize;
            if (size < 0 || size > sample.length - start) {
                throw new MediaException.Malformed("a NAL unit of " + size + " bytes runs past the end of the sample");
            }
            var total = lengthSize + (int) size;
            var clear = total;
            if (size > 0 && slices.isSlice(sample[start])) {
                var header = slices.headerSize(sample, start, (int) size);
                if (header >= 0) {
                    clear = lengthSize + header;
                }
            } else if (size > 0) {
                slices.parameterSet(sample, start, (int) size);
            }
            addAligned(clear, total - clear);
            position = start + (int) size;
        }
        return finishMap();
    }

    /**
     * Divides a VP9 sample into subsamples: each frame is protected after its uncompressed header, and the
     * superframe index stays clear.
     */
    private int[] vp9Subsamples(byte[] sample) {
        startMap();
        var layout = vp9.divide(sample, 0, sample.length);
        for (var frame = 0; frame < layout[0]; frame++) {
            var size = layout[1 + 2 * frame];
            var header = layout[2 + 2 * frame];
            addAligned(header, size - header);
        }
        addSubsample(layout[1 + 2 * layout[0]], 0);
        return finishMap();
    }

    /**
     * Divides an AV1 sample into subsamples: the tile data is protected and every other byte stays clear, the whole
     * sample when its OBUs cannot be read.
     */
    private int[] av1Subsamples(byte[] sample) {
        if (!av1Configured && track.config != null) {
            av1.configure(track.config);
            av1Configured = true;
        }
        startMap();
        var tiles = av1.find(sample, 0, sample.length);
        var end = 0;
        if (tiles != null) {
            for (var tile = 0; tile < tiles[0]; tile++) {
                var start = tiles[1 + 2 * tile];
                var size = tiles[2 + 2 * tile];
                addAligned(start - end, size);
                end = start + size;
            }
        }
        addSubsample(sample.length - end, 0);
        return finishMap();
    }

    private void startMap() {
        pairCount = 0;
        pendingClear = 0;
    }

    private int[] finishMap() {
        if (pendingClear > 0 || pairCount == 0) {
            push(pendingClear, 0);
        }
        return Arrays.copyOf(pairs, pairCount * 2);
    }

    /**
     * Adds a subsample whose protected bytes, under a scheme that needs whole blocks, are cut to whole blocks at
     * their end, the cut bytes joining the clear ones.
     */
    private void addAligned(int clear, int protectedBytes) {
        if (aligned) {
            var misaligned = protectedBytes % 16;
            clear += misaligned;
            protectedBytes -= misaligned;
        }
        addSubsample(clear, protectedBytes);
    }

    private void addSubsample(int clear, int protectedBytes) {
        pendingClear += clear;
        if (protectedBytes == 0) {
            return;
        }
        push(pendingClear, protectedBytes);
        pendingClear = 0;
    }

    private void push(int clear, int protectedBytes) {
        while (clear > MAX_CLEAR) {
            pushPair(MAX_CLEAR, 0);
            clear -= MAX_CLEAR;
        }
        pushPair(clear, protectedBytes);
    }

    private void pushPair(int clear, int protectedBytes) {
        if (2 * pairCount + 2 > pairs.length) {
            pairs = Arrays.copyOf(pairs, pairs.length * 2);
        }
        pairs[2 * pairCount] = clear;
        pairs[2 * pairCount + 1] = protectedBytes;
        pairCount++;
    }

    /**
     * Adds to a big-endian counter held in part of an array, wrapping within it.
     */
    private static void increment(byte[] value, int from, int to, long amount) {
        var carry = amount;
        for (var index = to - 1; index >= from && carry != 0; index--) {
            var sum = (value[index] & 0xFF) + (carry & 0xFF);
            value[index] = (byte) sum;
            carry = (carry >>> 8) + (sum >>> 8);
        }
    }

    /**
     * Turns a sample entry into its encrypted form: the type becomes {@code encv} or {@code enca} and a {@code sinf}
     * box naming the original type ends the entry.
     *
     * @param entry the serialized sample entry, a box with a 32-bit size
     * @param video whether the entry is visual
     * @return the encrypted entry
     */
    byte[] protectEntry(byte[] entry, boolean video) {
        var original = (entry[4] & 0xFF) << 24 | (entry[5] & 0xFF) << 16 | (entry[6] & 0xFF) << 8 | (entry[7] & 0xFF);
        var sinf = new BoxWriter(128);
        sinf.begin(BoxTypes.SINF);
        sinf.begin(BoxTypes.FRMA);
        sinf.u32(original);
        sinf.end();
        sinf.beginFull(BoxTypes.SCHM, 0, 0);
        sinf.u32(scheme.fourCc());
        sinf.u32(0x00010000);
        sinf.end();
        sinf.begin(BoxTypes.SCHI);
        sinf.beginFull(BoxTypes.TENC, scheme.usesPattern() ? 1 : 0, 0);
        sinf.u8(0);
        sinf.u8(scheme.usesPattern() ? crypt << 4 | skip : 0);
        sinf.u8(1);
        sinf.u8(ivSize);
        sinf.bytes(options.keyId());
        if (ivSize == 0) {
            sinf.u8(16);
            sinf.bytes(iv);
        }
        sinf.end();
        sinf.end();
        sinf.end();
        var result = Arrays.copyOf(entry, entry.length + sinf.size());
        System.arraycopy(sinf.buffer(), 0, result, entry.length, sinf.size());
        var size = result.length;
        result[0] = (byte) (size >>> 24);
        result[1] = (byte) (size >>> 16);
        result[2] = (byte) (size >>> 8);
        result[3] = (byte) size;
        var type = video ? BoxTypes.ENCV : BoxTypes.ENCA;
        result[4] = (byte) (type >>> 24);
        result[5] = (byte) (type >>> 16);
        result[6] = (byte) (type >>> 8);
        result[7] = (byte) type;
        return result;
    }

    /**
     * Writes the protection system specific header boxes of an encryption into a movie box.
     *
     * @param writer the writer, inside the {@code moov}
     * @param options the encryption
     */
    static void writeProtectionSystems(BoxWriter writer, CommonEncryption options) {
        if (options.commonProtectionSystem()) {
            writer.beginFull(BoxTypes.PSSH, 1, 0);
            writer.bytes(HexFormat.of().parseHex(CommonEncryption.COMMON_SYSTEM_ID));
            writer.u32(1);
            writer.bytes(options.keyId());
            writer.u32(0);
            writer.end();
        }
        for (var box : options.protectionSystems()) {
            writer.bytes(box);
        }
    }

    /**
     * Writes the {@code saiz}, {@code saio} and {@code senc} boxes of a track fragment.
     *
     * @param moof the writer of the {@code moof}, inside the {@code traf}, whose offset 0 is the start of the
     * {@code moof}
     * @param samples the encryption of each sample of the run
     */
    void writeSampleEncryption(BoxWriter moof, Encrypted[] samples) {
        var sizes = new int[samples.length];
        var same = true;
        for (var index = 0; index < samples.length; index++) {
            var sample = samples[index];
            sizes[index] = sample.iv.length + (sample.subsamples == null ? 0 : 2 + 3 * sample.subsamples.length);
            same &= sizes[index] == sizes[0];
        }
        if (same && (sizes.length == 0 || sizes[0] == 0)) {
            // constant initialization vectors and whole samples: the senc box only counts the samples
            moof.beginFull(BoxTypes.SENC, 0, 0);
            moof.u32(samples.length);
            moof.end();
            return;
        }
        moof.beginFull(BoxTypes.SAIZ, 0, 0);
        moof.u8(same && sizes.length > 0 ? sizes[0] : 0);
        moof.u32(samples.length);
        if (!same) {
            for (var size : sizes) {
                moof.u8(size);
            }
        }
        moof.end();
        moof.beginFull(BoxTypes.SAIO, 0, 0);
        moof.u32(1);
        var offsetField = moof.size();
        moof.u32(0);
        moof.end();
        moof.beginFull(BoxTypes.SENC, 0, usesSubsamples() ? 2 : 0);
        moof.u32(samples.length);
        moof.patch32(offsetField, moof.size());
        for (var sample : samples) {
            moof.bytes(sample.iv);
            if (sample.subsamples != null) {
                moof.u16(sample.subsamples.length / 2);
                for (var pair = 0; pair < sample.subsamples.length / 2; pair++) {
                    moof.u16(sample.subsamples[2 * pair]);
                    moof.u32(Integer.toUnsignedLong(sample.subsamples[2 * pair + 1]));
                }
            }
        }
        moof.end();
    }

    /**
     * The encryption of one sample: its initialization vector as the {@code senc} entry stores it (empty with a
     * constant one) and its subsample map, clear and protected byte counts in pairs, or {@code null} when the whole
     * sample is protected.
     */
    static final class Encrypted {
        byte[] iv;
        int[] subsamples;
    }
}
