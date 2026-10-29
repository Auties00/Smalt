package com.github.auties00.smalt.container.mxf;

import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.nal.NalUnits;

/**
 * Reads the picture order count of H.264 access units (ITU-T H.264 clause 8.2.1.1, picture order count type 0), to
 * put the pictures of a stream that carries no presentation order of its own in display order.
 *
 * <p>Instances keep the state the computation carries from one reference picture to the next, and are not
 * thread-safe.
 */
final class AvcOrder {
    /**
     * The bits of {@code frame_num}.
     */
    private final int frameNumBits;

    /**
     * The bits of {@code pic_order_cnt_lsb}.
     */
    private final int pocBits;

    /**
     * Whether the sequence holds only frames.
     */
    private final boolean frameMbsOnly;

    /**
     * Whether the colour planes are coded separately.
     */
    private final boolean separatePlanes;

    /**
     * The most significant part of the order count of the previous reference picture.
     */
    private long previousMsb;

    /**
     * The least significant part of the order count of the previous reference picture.
     */
    private long previousLsb;

    private AvcOrder(int frameNumBits, int pocBits, boolean frameMbsOnly, boolean separatePlanes) {
        this.frameNumBits = frameNumBits;
        this.pocBits = pocBits;
        this.frameMbsOnly = frameMbsOnly;
        this.separatePlanes = separatePlanes;
    }

    /**
     * Reads the fields of a sequence parameter set that the order count needs.
     *
     * @param sps the sequence parameter set, a whole NAL unit
     * @return the reader, or {@code null} when the sequence does not use order count type 0 or the set is unreadable
     */
    static AvcOrder of(byte[] sps) {
        try {
            var bits = new MsbBitReader(NalUnits.unescape(sps, 1, sps.length - 1));
            var profile = bits.read(8);
            bits.skip(16);
            bits.readUe();
            var separate = false;
            if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44
                    || profile == 83 || profile == 86 || profile == 118 || profile == 128 || profile == 138
                    || profile == 139 || profile == 134 || profile == 135) {
                var chroma = bits.readUe();
                if (chroma == 3) {
                    separate = bits.readFlag();
                }
                bits.readUe();
                bits.readUe();
                bits.readFlag();
                if (bits.readFlag()) {
                    for (var list = 0; list < (chroma != 3 ? 8 : 12); list++) {
                        if (bits.readFlag()) {
                            var last = 8;
                            var next = 8;
                            for (var index = 0; index < (list < 6 ? 16 : 64) && next != 0; index++) {
                                next = (last + bits.readSe() + 256) % 256;
                                last = next == 0 ? last : next;
                            }
                        }
                    }
                }
            }
            var frameNumBits = bits.readUe() + 4;
            var pocType = bits.readUe();
            if (pocType != 0) {
                return null;
            }
            var pocBits = bits.readUe() + 4;
            bits.readUe();
            bits.readFlag();
            bits.readUe();
            bits.readUe();
            var frameMbsOnly = bits.readFlag();
            if (bits.overrun() || frameNumBits > 16 || pocBits > 16) {
                return null;
            }
            return new AvcOrder(frameNumBits, pocBits, frameMbsOnly, separate);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * Returns the picture order count of an access unit and advances the state past it.
     *
     * @param data the access unit, as length-prefixed NAL units of four-byte lengths
     * @return the order count, or {@link Long#MIN_VALUE} when the access unit holds no readable slice
     */
    long next(byte[] data) {
        for (var at = 0; at + 4 < data.length; ) {
            var length = (int) NalUnits.readLength(data, at, 4);
            if (length <= 0 || length > data.length - at - 4) {
                return Long.MIN_VALUE;
            }
            var type = data[at + 4] & 0x1F;
            if (type == 1 || type == 5) {
                return slice(data, at + 4, Math.min(length, 64), type == 5, (data[at + 4] & 0x60) != 0);
            }
            at += 4 + length;
        }
        return Long.MIN_VALUE;
    }

    /**
     * Computes the order count of a slice.
     */
    private long slice(byte[] data, int offset, int length, boolean idr, boolean reference) {
        try {
            var bits = new MsbBitReader(NalUnits.unescape(data, offset + 1, length - 1));
            bits.readUe();
            bits.readUe();
            bits.readUe();
            if (separatePlanes) {
                bits.skip(2);
            }
            bits.skip(frameNumBits);
            if (!frameMbsOnly && bits.readFlag()) {
                bits.readFlag();
            }
            if (idr) {
                bits.readUe();
            }
            var lsb = bits.read(pocBits);
            if (bits.overrun()) {
                return Long.MIN_VALUE;
            }
            if (idr) {
                previousMsb = 0;
                previousLsb = 0;
            }
            var max = 1L << pocBits;
            long msb;
            if (lsb < previousLsb && previousLsb - lsb >= max / 2) {
                msb = previousMsb + max;
            } else if (lsb > previousLsb && lsb - previousLsb > max / 2) {
                msb = previousMsb - max;
            } else {
                msb = previousMsb;
            }
            if (reference) {
                previousMsb = msb;
                previousLsb = lsb;
            }
            return msb + lsb;
        } catch (RuntimeException exception) {
            return Long.MIN_VALUE;
        }
    }
}
