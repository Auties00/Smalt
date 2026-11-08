package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.cenc.CencScheme;
import com.github.auties00.smalt.internal.heif.BoxCursor;
import com.github.auties00.smalt.internal.io.FourCc;

import java.util.ArrayList;
import java.util.List;

/**
 * Describes the protection of an encrypted sample entry ({@code encv}, {@code enca}): the protection scheme
 * information box ({@code sinf}) of ISO/IEC 14496-12 clause 8.12 with the Common Encryption scheme and track
 * encryption box of ISO/IEC 23001-7, or the track encryption box of PIFF 1.1.
 *
 * @param originalFormat the sample entry type of the clear samples ({@code frma})
 * @param scheme the protection scheme
 * @param defaults the protection of the samples no sample group overrides, or {@code null} when the scheme
 * information has no track encryption box, as multi-key encryption (ISO/IEC 23001-7:2023) writes: every protected
 * sample then belongs to a {@code seig} group
 */
record TrackProtection(int originalFormat, CencScheme scheme, CencParams defaults) {
    /**
     * The scheme type of PIFF 1.1 ({@code piff}).
     */
    private static final int PIFF = 0x70696666;

    /**
     * The first eight bytes of the user type of the PIFF track encryption box,
     * {@code 8974dbce-7be7-4c51-84f9-7148f9882554}.
     */
    private static final long PIFF_TENC_HIGH = 0x8974DBCE7BE74C51L;

    /**
     * The last eight bytes of the user type of the PIFF track encryption box.
     */
    private static final long PIFF_TENC_LOW = 0x84F97148F9882554L;

    /**
     * The first eight bytes of the user type of the PIFF sample encryption box,
     * {@code a2394f52-5a9b-4f14-a244-6c427c648df4}.
     */
    static final long PIFF_SENC_HIGH = 0xA2394F525A9B4F14L;

    /**
     * The last eight bytes of the user type of the PIFF sample encryption box.
     */
    static final long PIFF_SENC_LOW = 0xA2446C427C648DF4L;

    /**
     * Parses the protection of an encrypted sample entry from its {@code sinf} boxes, the first one with a supported
     * scheme winning.
     *
     * @param sinfs the payloads of the entry's {@code sinf} boxes
     * @param format the entry type, for messages
     * @return the protection
     * @throws MediaException.Malformed if no {@code sinf} box is present or one is incomplete
     * @throws MediaException.Unsupported if no {@code sinf} box names a supported scheme
     */
    static TrackProtection parse(List<BoxCursor> sinfs, int format) {
        if (sinfs.isEmpty()) {
            throw new MediaException.Malformed("encrypted sample entry '" + FourCc.printable(format) + "' has no sinf");
        }
        MediaException.Unsupported unsupported = null;
        for (var sinf : sinfs) {
            var original = 0;
            var schemeType = 0;
            BoxCursor schi = null;
            while (sinf.hasBox()) {
                var box = sinf.nextBox();
                switch (box.type()) {
                    case BoxTypes.FRMA -> original = box.s32();
                    case BoxTypes.SCHM -> {
                        box.fullBoxHeader();
                        schemeType = box.s32();
                    }
                    case BoxTypes.SCHI -> schi = box;
                    default -> {
                    }
                }
            }
            if (original == 0) {
                throw new MediaException.Malformed("sinf of '" + FourCc.printable(format) + "' has no frma");
            }
            var scheme = CencScheme.of(schemeType);
            if (scheme == null && schemeType != PIFF) {
                unsupported = new MediaException.Unsupported("mp4 encryption", "the protection scheme '"
                        + FourCc.printable(schemeType) + "'");
                continue;
            }
            if (schi == null) {
                throw new MediaException.Malformed("sinf of '" + FourCc.printable(format) + "' has no schi");
            }
            CencParams defaults = null;
            var piffAlgorithm = -1;
            while (schi.hasBox()) {
                var box = schi.nextBox();
                if (box.type() == BoxTypes.TENC) {
                    defaults = parseTenc(box);
                } else if (box.type() == BoxTypes.UUID && box.remaining() >= 16 + 4 + 4 + 16
                        && box.u64() == PIFF_TENC_HIGH && box.u64() == PIFF_TENC_LOW) {
                    box.fullBoxHeader();
                    piffAlgorithm = box.u24();
                    var ivSize = box.u8();
                    var keyId = box.bytes(16);
                    if (defaults == null) {
                        defaults = params(piffAlgorithm != 0, ivSize, keyId, null, 0, 0);
                    }
                }
            }
            if (scheme == null) {
                scheme = switch (piffAlgorithm) {
                    case 2 -> CencScheme.CBC1;
                    case 0, 1, -1 -> CencScheme.CENC;
                    default -> null;
                };
                if (scheme == null) {
                    unsupported = new MediaException.Unsupported("mp4 encryption", "the PIFF algorithm "
                            + piffAlgorithm);
                    continue;
                }
            }
            return new TrackProtection(original, scheme, defaults);
        }
        throw unsupported;
    }

    /**
     * Parses a track encryption box ({@code tenc}).
     *
     * @param tenc the box payload
     * @return the defaults it declares
     * @throws MediaException.Malformed if the box is truncated or declares an invalid size
     */
    static CencParams parseTenc(BoxCursor tenc) {
        var version = tenc.fullBoxHeader() >>> 24;
        tenc.u8();
        var pattern = tenc.u8();
        var isProtected = tenc.u8();
        var ivSize = tenc.u8();
        var keyId = tenc.bytes(16);
        byte[] constantIv = null;
        if (isProtected != 0 && ivSize == 0) {
            constantIv = tenc.bytes(tenc.u8());
        }
        return params(isProtected != 0, ivSize, keyId, constantIv, version > 0 ? pattern >>> 4 : 0,
                version > 0 ? pattern & 15 : 0);
    }

    /**
     * Parses one entry of a sample encryption information group description ({@code seig}): a single key, or, when
     * the first bit of the entry is set, the key count and keys of a multi-key entry (ISO/IEC 23001-7:2023), each
     * key its initialization vector size, identifier and, with a size of 0, constant initialization vector.
     *
     * @param entry the entry payload
     * @return the protection it declares
     * @throws MediaException.Malformed if the entry is truncated, declares an invalid size or a multi-key entry
     * without keys
     */
    static CencParams parseSeig(BoxCursor entry) {
        var multiKey = (entry.u8() & 0x80) != 0;
        var pattern = entry.u8();
        var isProtected = entry.u8();
        if (!multiKey) {
            return key(entry, isProtected != 0, pattern);
        }
        var count = entry.u16();
        if (count == 0) {
            throw new MediaException.Malformed("a multi-key seig entry without keys");
        }
        var keys = new ArrayList<CencParams>(Math.min(count, 256));
        for (var index = 0; index < count; index++) {
            keys.add(key(entry, isProtected != 0, pattern));
        }
        var first = keys.getFirst();
        return new CencParams(isProtected != 0, first.ivSize(), first.keyId(), first.constantIv(), pattern >>> 4,
                pattern & 15, List.copyOf(keys));
    }

    /**
     * Reads one key of a {@code seig} entry: its initialization vector size, identifier and constant
     * initialization vector.
     */
    private static CencParams key(BoxCursor entry, boolean isProtected, int pattern) {
        var ivSize = entry.u8();
        var keyId = entry.bytes(16);
        byte[] constantIv = null;
        if (isProtected && ivSize == 0) {
            constantIv = entry.bytes(entry.u8());
        }
        return params(isProtected, ivSize, keyId, constantIv, pattern >>> 4, pattern & 15);
    }

    /**
     * Checks and assembles protection parameters.
     */
    private static CencParams params(boolean isProtected, int ivSize, byte[] keyId, byte[] constantIv, int crypt,
                                     int skip) {
        if (ivSize != 0 && ivSize != 8 && ivSize != 16) {
            throw new MediaException.Malformed("per-sample initialization vector size " + ivSize);
        }
        if (constantIv != null && constantIv.length != 8 && constantIv.length != 16) {
            throw new MediaException.Malformed("constant initialization vector of " + constantIv.length + " bytes");
        }
        return new CencParams(isProtected, ivSize, keyId, constantIv, crypt, skip, null);
    }
}
