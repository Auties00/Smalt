package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.cenc.CencScheme;
import com.github.auties00.smalt.internal.heif.BoxCursor;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Stores the Common Encryption auxiliary information of the samples of one protected track (ISO/IEC 23001-7
 * clause 7): each sample's initialization vector, its subsample map and the sample encryption information group
 * ({@code seig}) it belongs to.
 *
 * <p>The information comes from sample encryption boxes ({@code senc}, or the PIFF {@code uuid} box), from the sample
 * auxiliary information that {@code saiz} and {@code saio} boxes locate when no sample encryption box is present, and
 * from {@code sbgp} and {@code sgpd} boxes of the {@code seig} grouping type. A sample of the {@link SampleTable} names
 * its record through {@link SampleTable#crypto(int)}; a sample without a record uses the defaults of its sample
 * description. Instances are not thread-safe.
 *
 * <p>The information of a sample whose group is a multi-key one (ISO/IEC 23001-7:2023) has its own layout, which
 * the multi-key files of the MPEG file format conformance suite show:
 * {@snippet :
 * u16 ivCount                       // then ivCount times:
 *     u16 keyIndex                  //   1-based index in the group's keys
 *     u8[keys[keyIndex].ivSize] iv  //   the key's initialization vector for this sample
 * u32 subsampleCount                // when subsamples follow, then subsampleCount times:
 *     u16 keyIndex                  //   the key of the subsample, 0 for a clear one
 *     u16 clearBytes
 *     u32 protectedBytes
 * }
 * Its initialization vectors are kept in {@link #ivs} as the index (two bytes, big-endian) followed by the vector,
 * and the key of each subsample in {@link #subsampleKeys}.
 */
final class CencSamples {
    /**
     * The group of a record that belongs to no {@code seig} group.
     */
    static final int NO_GROUP = -1;

    /**
     * The {@code senc} flag announcing subsample maps.
     */
    private static final int USE_SUBSAMPLES = 2;

    /**
     * The PIFF {@code senc} flag announcing an override of the track defaults.
     */
    private static final int PIFF_OVERRIDE = 1;

    /**
     * The limits bounding the stored information.
     */
    private final MediaLimits limits;

    /**
     * The sample encryption information groups: those of the {@code moov} first, then those of each fragment.
     */
    final List<CencParams> groups = new ArrayList<>();

    /**
     * The number of groups the {@code moov} describes, which {@code sbgp} indices up to 65536 name.
     */
    int movieGroups;

    /**
     * The initialization vectors of every record, back to back.
     */
    byte[] ivs = new byte[256];

    /**
     * The number of bytes of {@link #ivs} in use.
     */
    private int ivBytes;

    /**
     * The clear and protected byte counts of every record's subsamples, in pairs.
     */
    int[] subsamples = new int[256];

    /**
     * The number of ints of {@link #subsamples} in use.
     */
    private int subsampleInts;

    /**
     * The key index of each subsample of a multi-key record, at half the index of its pair in {@link #subsamples}.
     */
    int[] subsampleKeys = new int[128];

    /**
     * The number of initialization vectors of each multi-key record, -1 for a single-key record.
     */
    int[] multiIvCount = new int[64];

    /**
     * The index in {@link #ivs} of each record's initialization vector.
     */
    int[] ivOffset = new int[64];

    /**
     * The length of each record's initialization vector, 0 for none.
     */
    byte[] ivLength = new byte[64];

    /**
     * The index in {@link #subsamples} of each record's first pair.
     */
    int[] subsampleOffset = new int[64];

    /**
     * The number of subsamples of each record, 0 when the whole sample is protected.
     */
    int[] subsampleCount = new int[64];

    /**
     * The group of each record, an index in {@link #groups}, or {@link #NO_GROUP}.
     */
    int[] group = new int[64];

    /**
     * Whether each record holds auxiliary information, as opposed to a group only.
     */
    boolean[] hasInfo = new boolean[64];

    /**
     * The number of records.
     */
    int count;

    /**
     * Constructs an empty store.
     *
     * @param limits the limits bounding the stored information
     */
    CencSamples(MediaLimits limits) {
        this.limits = limits;
    }

    /**
     * Appends a record without auxiliary information.
     *
     * @param groupIndex the group, or {@link #NO_GROUP}
     * @return the index of the record
     */
    int addRecord(int groupIndex) {
        if (count == ivOffset.length) {
            var capacity = count * 2;
            LimitChecks.checkAllocation(limits, (long) capacity * 18);
            ivOffset = Arrays.copyOf(ivOffset, capacity);
            ivLength = Arrays.copyOf(ivLength, capacity);
            subsampleOffset = Arrays.copyOf(subsampleOffset, capacity);
            subsampleCount = Arrays.copyOf(subsampleCount, capacity);
            group = Arrays.copyOf(group, capacity);
            hasInfo = Arrays.copyOf(hasInfo, capacity);
            multiIvCount = Arrays.copyOf(multiIvCount, capacity);
        }
        group[count] = groupIndex;
        multiIvCount[count] = -1;
        return count++;
    }

    /**
     * Reads the auxiliary information of one sample into a record.
     *
     * @param record the record index
     * @param info the cursor at the sample's information
     * @param ivSize the size of its initialization vector
     * @param withSubsamples whether a subsample map follows the initialization vector
     * @throws MediaException.Malformed if the information is truncated
     */
    void readInfo(int record, BoxCursor info, int ivSize, boolean withSubsamples) {
        if (ivSize > 0) {
            if (info.remaining() < ivSize) {
                throw info.truncated();
            }
            ensureIvs(ivSize);
            System.arraycopy(info.data(), info.position(), ivs, ivBytes, ivSize);
            info.skip(ivSize);
            ivOffset[record] = ivBytes;
            ivBytes += ivSize;
        }
        ivLength[record] = (byte) ivSize;
        hasInfo[record] = true;
        if (!withSubsamples) {
            subsampleCount[record] = 0;
            return;
        }
        var pairs = info.u16();
        if (pairs * 6L > info.remaining()) {
            throw new MediaException.Malformed(pairs + " subsamples in " + info.remaining() + " bytes");
        }
        ensureSubsamples(pairs * 2);
        subsampleOffset[record] = subsampleInts;
        for (var pair = 0; pair < pairs; pair++) {
            subsamples[subsampleInts++] = info.u16();
            subsamples[subsampleInts++] = (int) info.u32();
        }
        subsampleCount[record] = pairs;
    }

    /**
     * Reads the initialization vectors of the multi-key auxiliary information of one sample into a record.
     *
     * @param record the record index
     * @param info the cursor at the sample's information
     * @param params the sample's multi-key protection
     * @throws MediaException.Malformed if the information is truncated or names a key the group does not have
     */
    void readMultiKeyIvs(int record, BoxCursor info, CencParams params) {
        var keys = params.keys();
        var count = info.u16();
        ivOffset[record] = ivBytes;
        for (var index = 0; index < count; index++) {
            var key = info.u16();
            if (key == 0 || key > keys.size()) {
                throw new MediaException.Malformed("multi-key initialization vector of key " + key + " of "
                        + keys.size());
            }
            var size = keys.get(key - 1).ivSize();
            if (info.remaining() < size) {
                throw info.truncated();
            }
            ensureIvs(2 + size);
            ivs[ivBytes] = (byte) (key >>> 8);
            ivs[ivBytes + 1] = (byte) key;
            System.arraycopy(info.data(), info.position(), ivs, ivBytes + 2, size);
            info.skip(size);
            ivBytes += 2 + size;
        }
        multiIvCount[record] = count;
        ivLength[record] = 0;
        hasInfo[record] = true;
    }

    /**
     * Reads the subsample map of the multi-key auxiliary information of one sample, after its initialization
     * vectors, into a record.
     *
     * @param record the record index
     * @param info the cursor after the sample's initialization vectors
     * @param params the sample's multi-key protection
     * @param withSubsamples whether a subsample map follows, {@code false} for a sample protected whole
     * @throws MediaException.Malformed if the map is truncated or names a key the group does not have
     */
    void readMultiKeySubsamples(int record, BoxCursor info, CencParams params, boolean withSubsamples) {
        var keys = params.keys();
        if (!withSubsamples) {
            subsampleCount[record] = 0;
            return;
        }
        var pairs = info.u32();
        if (pairs * 8 > info.remaining()) {
            throw new MediaException.Malformed(pairs + " multi-key subsamples in " + info.remaining() + " bytes");
        }
        ensureSubsamples((int) pairs * 2);
        subsampleOffset[record] = subsampleInts;
        for (var pair = 0; pair < pairs; pair++) {
            var key = info.u16();
            if (key > keys.size()) {
                throw new MediaException.Malformed("multi-key subsample of key " + key + " of " + keys.size());
            }
            subsampleKeys[subsampleInts / 2] = key;
            subsamples[subsampleInts++] = info.u16();
            subsamples[subsampleInts++] = (int) info.u32();
        }
        subsampleCount[record] = (int) pairs;
    }

    private void ensureIvs(int extra) {
        if (ivBytes + extra > ivs.length) {
            var capacity = Math.max(ivBytes + extra, ivs.length * 2);
            LimitChecks.checkAllocation(limits, capacity);
            ivs = Arrays.copyOf(ivs, capacity);
        }
    }

    private void ensureSubsamples(int extra) {
        if (subsampleInts + extra > subsamples.length) {
            var capacity = Math.max(subsampleInts + extra, subsamples.length * 2);
            LimitChecks.checkAllocation(limits, (long) capacity * 6);
            subsamples = Arrays.copyOf(subsamples, capacity);
            subsampleKeys = Arrays.copyOf(subsampleKeys, (capacity + 1) / 2);
        }
    }

    /**
     * Returns the protection of a record's sample.
     *
     * @param record the record index, or {@code -1} for a sample without a record
     * @param defaults the defaults of the sample's description, or {@code null} for none
     * @return the protection, or {@code null} for a sample of a description without defaults and of no group
     */
    CencParams params(int record, CencParams defaults) {
        if (record < 0 || group[record] == NO_GROUP) {
            return defaults;
        }
        return groups.get(group[record]);
    }

    /**
     * Parses the {@code seig} entries of an {@code sgpd} box and appends them to the groups.
     *
     * @param sgpd the box payload
     * @return the number of entries appended, or -1 when the box describes another grouping type
     * @throws MediaException.Malformed if an entry is truncated
     */
    int addGroups(BoxCursor sgpd) {
        var version = sgpd.fullBoxHeader() >>> 24;
        if (sgpd.s32() != BoxTypes.SEIG) {
            return -1;
        }
        var defaultLength = version == 1 ? sgpd.u32() : 0;
        if (version >= 2) {
            sgpd.u32();
        }
        var entries = sgpd.u32();
        if (entries > sgpd.remaining() / 20) {
            throw new MediaException.Malformed("sgpd declares " + entries + " seig entries in " + sgpd.remaining()
                    + " bytes");
        }
        for (var entry = 0L; entry < entries; entry++) {
            var length = version == 1 ? defaultLength == 0 ? sgpd.u32() : defaultLength : -1;
            if (length >= 0) {
                if (length > sgpd.remaining()) {
                    throw sgpd.truncated();
                }
                groups.add(TrackProtection.parseSeig(sgpd.slice((int) length)));
            } else {
                groups.add(TrackProtection.parseSeig(sgpd));
            }
        }
        return (int) entries;
    }

    /**
     * Assigns samples to {@code seig} groups through an {@code sbgp} box, creating their records.
     *
     * @param sbgp the box payload, of the {@code seig} grouping type
     * @param table the sample index
     * @param first the index in {@code table} of the first sample the box describes
     * @param end the index after the last such sample
     * @param fragmentGroups the index in {@link #groups} of the first group of the enclosing track fragment, or
     * {@code -1} in a {@code moov}
     * @throws MediaException.Malformed if the box names a group that does not exist
     */
    void assignGroups(BoxCursor sbgp, SampleTable table, int first, int end, int fragmentGroups) {
        var version = sbgp.fullBoxHeader() >>> 24;
        sbgp.s32();
        if (version == 1) {
            sbgp.u32();
        }
        var entries = sbgp.u32();
        var sample = (long) first;
        for (var entry = 0L; entry < entries && sample < end; entry++) {
            var run = sbgp.u32();
            var index = sbgp.u32();
            long resolved;
            if (index == 0) {
                resolved = NO_GROUP;
            } else if (index > 0x10000 && fragmentGroups >= 0) {
                resolved = fragmentGroups + index - 0x10001;
            } else {
                resolved = index - 1;
                if (resolved >= movieGroups) {
                    resolved = -2;
                }
            }
            if (resolved < NO_GROUP || resolved >= groups.size()) {
                throw new MediaException.Malformed("sbgp names seig group " + index + " of " + groups.size());
            }
            for (var count = 0L; count < run && sample < end; count++, sample++) {
                var record = table.crypto((int) sample);
                if (record < 0) {
                    table.setCrypto((int) sample, addRecord((int) resolved));
                } else {
                    group[record] = (int) resolved;
                }
            }
        }
    }

    /**
     * Reads a sample encryption box ({@code senc}, or the PIFF {@code uuid} box after its user type) into the
     * records of samples.
     *
     * <p>The PIFF box may override the track defaults for its samples, with its algorithm (0 for clear samples),
     * initialization vector size and key identifier; the override becomes a group of its own that the samples
     * belong to.
     *
     * @param senc the box payload, after the user type of a PIFF box
     * @param piff whether the box is the PIFF one, which may override the track defaults
     * @param track the track whose samples the box describes
     * @param first the index in the track's samples of the first sample the box describes
     * @param end the index after the last such sample
     * @throws MediaException.Malformed if the box is truncated, describes more samples than the run holds or
     * overrides the initialization vector size with a size other than 0, 8 or 16 bytes
     */
    void readSampleEncryption(BoxCursor senc, boolean piff, Mp4Track track, int first, int end) {
        var flags = senc.fullBoxHeader() & 0xFFFFFF;
        var overrideIvSize = -1;
        var overrideGroup = NO_GROUP;
        if (piff && (flags & PIFF_OVERRIDE) != 0) {
            var algorithm = senc.u24();
            overrideIvSize = senc.u8();
            var keyId = senc.bytes(16);
            if (overrideIvSize != 0 && overrideIvSize != 8 && overrideIvSize != 16) {
                throw new MediaException.Malformed("PIFF sample encryption overrides the initialization vector size "
                        + "with " + overrideIvSize + " bytes");
            }
            groups.add(new CencParams(algorithm != 0, overrideIvSize, keyId, null, 0, 0, null));
            overrideGroup = groups.size() - 1;
        }
        var entries = senc.u32();
        if (entries > end - first) {
            throw new MediaException.Malformed("senc describes " + entries + " samples of a run of " + (end - first));
        }
        var table = track.samples;
        for (var index = 0; index < entries; index++) {
            var sample = first + index;
            var record = table.crypto(sample);
            if (record < 0) {
                record = addRecord(NO_GROUP);
                table.setCrypto(sample, record);
            }
            if (overrideGroup != NO_GROUP) {
                group[record] = overrideGroup;
            }
            var multiKey = overrideIvSize < 0 ? multiKeyParams(track, sample, record) : null;
            if (multiKey != null) {
                readMultiKeyIvs(record, senc, multiKey);
                readMultiKeySubsamples(record, senc, multiKey, (flags & USE_SUBSAMPLES) != 0);
                continue;
            }
            var ivSize = overrideIvSize >= 0 ? overrideIvSize : ivSize(track, sample, record);
            readInfo(record, senc, ivSize, (flags & USE_SUBSAMPLES) != 0);
        }
    }

    /**
     * Reads the sample auxiliary information that {@code saiz} and {@code saio} boxes locate.
     *
     * @param saiz the {@code saiz} payload
     * @param saio the {@code saio} payload
     * @param track the track whose samples the information describes
     * @param runs the index in the track's samples of the first sample of each run (each {@code trun} of a track
     * fragment, each chunk of a sample table), followed by the index after the last sample
     * @param base the offset the {@code saio} offsets count from
     * @param source reads the information
     * @param scheme the protection scheme of the track
     * @throws MediaException.Malformed if the boxes are inconsistent or the information cannot be read
     */
    void readAuxiliaryInfo(BoxCursor saiz, BoxCursor saio, Mp4Track track, int[] runs, long base, AuxSource source,
                           CencScheme scheme) {
        var sizeFlags = saiz.fullBoxHeader() & 0xFFFFFF;
        if ((sizeFlags & 1) != 0 && !isCencInfo(saiz.s32(), scheme, saiz)) {
            return;
        }
        var defaultSize = saiz.u8();
        var sampleCount = saiz.u32();
        var first = runs[0];
        var end = runs[runs.length - 1];
        if (sampleCount > end - first) {
            throw new MediaException.Malformed("saiz describes " + sampleCount + " samples of a run of " + (end - first));
        }
        if (defaultSize == 0 && sampleCount > saiz.remaining()) {
            throw saiz.truncated();
        }
        var sizes = new int[(int) sampleCount];
        var total = 0L;
        for (var index = 0; index < sizes.length; index++) {
            sizes[index] = defaultSize != 0 ? defaultSize : saiz.u8();
            total += sizes[index];
        }
        var offsetFlags = saio.fullBoxHeader();
        var version = offsetFlags >>> 24;
        if ((offsetFlags & 1) != 0 && !isCencInfo(saio.s32(), scheme, saio)) {
            return;
        }
        var offsetCount = saio.u32();
        if (offsetCount == 0 || sampleCount == 0) {
            return;
        }
        if (offsetCount != 1 && offsetCount != runs.length - 1) {
            throw new MediaException.Malformed("saio has " + offsetCount + " offsets for " + (runs.length - 1) + " runs");
        }
        if (offsetCount * (version == 0 ? 4 : 8) > saio.remaining()) {
            throw saio.truncated();
        }
        LimitChecks.checkAllocation(limits, total);
        var table = track.samples;
        var sample = 0;
        for (var run = 0; run < offsetCount && sample < sizes.length; run++) {
            var offset = base + (version == 0 ? saio.u32() : saio.u64());
            var last = offsetCount == 1 ? sizes.length : Math.min(sizes.length, runs[run + 1] - first);
            var length = 0L;
            for (var index = sample; index < last; index++) {
                length += sizes[index];
            }
            if (length > Integer.MAX_VALUE) {
                throw new MediaException.Malformed("sample auxiliary information of " + length + " bytes");
            }
            var bytes = source.read(offset, (int) length);
            var cursor = BoxCursor.of(bytes, BoxTypes.SAIO);
            for (; sample < last; sample++) {
                var size = sizes[sample];
                var index = first + sample;
                var record = table.crypto(index);
                if (record < 0) {
                    record = addRecord(NO_GROUP);
                    table.setCrypto(index, record);
                }
                if (size == 0) {
                    continue;
                }
                var multiKey = multiKeyParams(track, index, record);
                if (multiKey != null) {
                    var info = cursor.slice(size);
                    readMultiKeyIvs(record, info, multiKey);
                    readMultiKeySubsamples(record, info, multiKey, info.remaining() > 0);
                    continue;
                }
                var ivSize = ivSize(track, index, record);
                if (size < ivSize) {
                    throw new MediaException.Malformed("sample auxiliary information of " + size + " bytes for a "
                            + ivSize + "-byte initialization vector");
                }
                var info = cursor.slice(size);
                readInfo(record, info, ivSize, size > ivSize);
            }
        }
    }

    /**
     * Returns whether an auxiliary information type names Common Encryption information, reading its parameter: 0,
     * or 1 for the multi-key layout, which the samples' groups announce too.
     *
     * @throws MediaException.Unsupported if the parameter has another value
     */
    private static boolean isCencInfo(int type, CencScheme scheme, BoxCursor box) {
        var parameter = box.u32();
        var cenc = type == 0 || type == scheme.fourCc() || CencScheme.of(type) != null || type == 0x70696666;
        if (cenc && parameter != 0 && parameter != 1) {
            throw new MediaException.Unsupported("mp4 encryption", "sample auxiliary information of parameter "
                    + parameter);
        }
        return cenc;
    }

    /**
     * Returns the multi-key protection of a sample, or {@code null} when it has a single key.
     */
    private CencParams multiKeyParams(Mp4Track track, int sample, int record) {
        var description = track.samples.description(sample);
        var entry = description < track.entries.length ? track.entries[description] : null;
        if (entry == null || entry.protection() == null) {
            return null;
        }
        var params = params(record, entry.protection().defaults());
        return params != null && params.isProtected() && params.multiKey() ? params : null;
    }

    /**
     * Returns the size of the per-sample initialization vector of a sample.
     */
    private int ivSize(Mp4Track track, int sample, int record) {
        var description = track.samples.description(sample);
        var entry = description < track.entries.length ? track.entries[description] : null;
        if (entry == null || entry.protection() == null) {
            return 0;
        }
        var params = params(record, entry.protection().defaults());
        return params != null && params.isProtected() ? params.ivSize() : 0;
    }

    /**
     * Reads the bytes that sample auxiliary information offsets point at.
     */
    @FunctionalInterface
    interface AuxSource {
        /**
         * Reads bytes of the file.
         *
         * @param offset the absolute offset of the first byte
         * @param length the number of bytes
         * @return the bytes
         * @throws MediaException.Malformed if they lie outside the file
         */
        byte[] read(long offset, int length);
    }
}
