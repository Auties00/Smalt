package com.github.auties00.smalt.container.mxf;

import com.github.auties00.smalt.MediaException;

/**
 * The fields of a partition pack (SMPTE ST 377-1 clause 7.1).
 *
 * <p>Offsets are relative to the first byte of the header partition pack, as the pack stores them; a run-in before
 * it is not counted.
 *
 * @param offset the offset of this partition pack, as {@code ThisPartition} states it
 * @param kind 2 for the header partition, 3 for a body partition, 4 for the footer partition
 * @param status 1 open incomplete, 2 closed incomplete, 3 open complete, 4 closed complete
 * @param kagSize the KLV alignment grid of the partition, in bytes
 * @param previous the offset of the previous partition pack
 * @param footer the offset of the footer partition pack, or 0 when unknown
 * @param headerByteCount the bytes of header metadata in the partition
 * @param indexByteCount the bytes of index table segments in the partition
 * @param indexSid the index stream id of the segments in the partition, or 0
 * @param bodyOffset the offset in its essence container stream of the first essence byte of the partition
 * @param bodySid the essence container stream id of the essence in the partition, or 0
 * @param operationalPattern the operational pattern label
 */
record MxfPartition(long offset, int kind, int status, long kagSize, long previous, long footer,
                    long headerByteCount, long indexByteCount, long indexSid, long bodyOffset, long bodySid,
                    byte[] operationalPattern) {
    /**
     * The size of the fixed fields of a partition pack, through the operational pattern.
     */
    static final int FIXED_SIZE = 80;

    /**
     * Parses a partition pack.
     *
     * @param key the pack key
     * @param value the pack value
     * @return the partition
     * @throws MediaException.Malformed if the value is shorter than the fixed fields or a field is out of range
     */
    static MxfPartition parse(byte[] key, byte[] value) {
        if (value.length < FIXED_SIZE) {
            throw new MediaException.Malformed("MXF partition pack of " + value.length + " bytes");
        }
        var partition = new MxfPartition(MxfSet.s64(value, 8), key[13], key[14], MxfSet.u32(value, 4),
                MxfSet.s64(value, 16), MxfSet.s64(value, 24), MxfSet.s64(value, 32), MxfSet.s64(value, 40),
                MxfSet.u32(value, 48), MxfSet.s64(value, 52), MxfSet.u32(value, 60), MxfSet.slice(value, 64, 16));
        if (partition.offset < 0 || partition.previous < 0 || partition.footer < 0 || partition.headerByteCount < 0
                || partition.indexByteCount < 0 || partition.bodyOffset < 0) {
            throw new MediaException.Malformed("MXF partition pack with a negative offset or count");
        }
        return partition;
    }

    /**
     * {@return whether the partition is closed, so that its header metadata, when it has any, is final}
     */
    boolean isClosed() {
        return status == 2 || status == 4;
    }

    /**
     * {@return whether the partition's header metadata is complete}
     */
    boolean isComplete() {
        return status == 3 || status == 4;
    }

    /**
     * {@return the preference of the partition's header metadata over that of others: closed and complete first}
     */
    int rank() {
        return (isClosed() ? 2 : 0) + (isComplete() ? 1 : 0);
    }
}
