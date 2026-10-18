package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.io.MediaSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks the top-level boxes of an ISO base media file without reading their payloads, recording
 * where the {@code ftyp}, {@code moov}, {@code moof} and {@code mdat} boxes are.
 *
 * <p>Only box headers are read, so a scan of a multi-gigabyte file touches a few kilobytes; the
 * bytes a header read brings in beyond the header are used for the next header or the
 * {@code ftyp} payload rather than read again, so the source is read forwards only. A
 * 32-bit size of {@code 1} announces a 64-bit size, and a size of {@code 0} extends the box to the
 * end of the file. The walk stops at trailing bytes too short for a header, at a box that runs
 * past the end of the file, and after {@value #MAX_BOXES} boxes.
 *
 * <p>A scan given a prefetch budget also reads the payloads of the first {@code moov} and of the
 * {@code moof} boxes as it meets them, straight after their headers, while their total stays
 * within the budget: a file or network source is then read forwards once, instead of being
 * walked for headers and read again, backwards, for the payloads. A payload is prefetched only
 * when reading it later could not fail on its size or position, and a payload whose read comes
 * up short or fails is dropped and stops prefetching, so the caller reads it again and meets the
 * same failure at the same point.
 */
final class TopLevelScan {
    /**
     * The largest number of top-level boxes walked.
     */
    static final int MAX_BOXES = 1 << 20;

    /**
     * The largest {@code ftyp} payload kept.
     */
    private static final int MAX_FTYP = 4096;

    /**
     * The payload of the {@code ftyp} box, or {@code null} when absent.
     */
    byte[] fileType;

    /**
     * The absolute offset of the first {@code moov} box, or {@code -1}.
     */
    long moovOffset = -1;

    /**
     * The header size of the first {@code moov} box.
     */
    int moovHeader;

    /**
     * The total size of the first {@code moov} box.
     */
    long moovSize;

    /**
     * The absolute offset of the first {@code mdat} box, or {@code -1}.
     */
    long firstMdat = -1;

    /**
     * The absolute offsets, header sizes and total sizes of the {@code moof} boxes, three values
     * per box.
     */
    final List<long[]> fragments = new ArrayList<>();

    /**
     * The offset of the first top-level box the walk did not examine because it stopped early,
     * or {@code -1} when it walked every box.
     */
    long next = -1;

    /**
     * The prefetched payload of the first {@code moov} box, or {@code null} when it was not
     * prefetched.
     */
    byte[] moovPayload;

    /**
     * The prefetched payloads of the {@code moof} boxes, in the order of {@link #fragments}, with
     * {@code null} for every payload that was not prefetched.
     */
    final List<byte[]> fragmentPayloads = new ArrayList<>();

    /**
     * The bytes of payloads that may still be prefetched, counting {@value #PAYLOAD_OVERHEAD}
     * bytes of overhead per payload; negative once prefetching stopped or when it is disabled.
     */
    private long budget;

    /**
     * The heap size charged to the prefetch budget for each payload beyond its bytes.
     */
    private static final int PAYLOAD_OVERHEAD = 32;

    /**
     * Scans a source without prefetching any payload.
     *
     * @param source the source to scan
     * @param stopEarly whether to stop once the order of {@code moov} and the first {@code mdat} is
     * known
     * @return the scan
     * @throws MediaException.Malformed if the source does not start with a box header
     */
    static TopLevelScan scan(MediaSource source, boolean stopEarly) {
        return scan(source, stopEarly, 0);
    }

    /**
     * Scans a source, prefetching the payloads of the first {@code moov} and of the
     * {@code moof} boxes within a budget.
     *
     * @param source the source to scan
     * @param stopEarly whether to stop once the order of {@code moov} and the first {@code mdat} is
     * known
     * @param budget the bytes of payloads that may be prefetched, counting
     * {@value #PAYLOAD_OVERHEAD} bytes per payload, or {@code 0} to prefetch none; at most
     * {@code Integer.MAX_VALUE - 16}
     * @return the scan
     * @throws MediaException.Malformed if the source does not start with a box header
     */
    static TopLevelScan scan(MediaSource source, boolean stopEarly, long budget) {
        var scan = new TopLevelScan();
        scan.budget = budget > 0 ? budget : -1;
        var size = source.size();
        var header = new byte[16];
        var headerStart = -1L;
        var headerLength = 0;
        var position = 0L;
        for (var boxes = 0; boxes < MAX_BOXES; boxes++) {
            if (size >= 0 && size - position < 8) {
                break;
            }
            int read;
            if (headerStart >= 0 && position >= headerStart && position < headerStart + headerLength) {
                var kept = (int) (headerStart + headerLength - position);
                System.arraycopy(header, (int) (position - headerStart), header, 0, kept);
                read = kept + SourceBytes.readAvailable(source, position + kept, header, kept, header.length - kept);
            } else {
                read = SourceBytes.readAvailable(source, position, header, 0, header.length);
            }
            headerStart = position;
            headerLength = read;
            if (read < 8) {
                if (position == 0) {
                    throw new MediaException.Malformed("not an ISO base media file: " + read + " bytes");
                }
                break;
            }
            var boxSize = ByteArrays.i32be(header, 0) & 0xFFFFFFFFL;
            var type = ByteArrays.i32be(header, 4);
            var headerSize = 8;
            if (position == 0 && !BoxTypes.isPlausible(type)) {
                throw new MediaException.Malformed("not an ISO base media file");
            }
            if (boxSize == 1) {
                if (read < 16) {
                    break;
                }
                boxSize = ByteArrays.i64be(header, 8);
                headerSize = 16;
            } else if (boxSize == 0) {
                boxSize = size < 0 ? Long.MAX_VALUE - position : size - position;
            }
            if (boxSize < headerSize) {
                if (position == 0) {
                    throw new MediaException.Malformed("not an ISO base media file: first box size " + boxSize);
                }
                break;
            }
            switch (type) {
                case BoxTypes.FTYP -> {
                    if (scan.fileType == null) {
                        var length = (int) Math.min(boxSize - headerSize, MAX_FTYP);
                        var payload = new byte[length];
                        var buffered = Math.max(0, Math.min(read - headerSize, length));
                        System.arraycopy(header, headerSize, payload, 0, buffered);
                        var available = buffered + SourceBytes.readAvailable(source, position + headerSize + buffered, payload,
                                buffered, payload.length - buffered);
                        scan.fileType = available == length ? payload : java.util.Arrays.copyOf(payload, Math.max(available, 0));
                    }
                }
                case BoxTypes.MOOV -> {
                    if (scan.moovOffset < 0) {
                        scan.moovOffset = position;
                        scan.moovHeader = headerSize;
                        scan.moovSize = boxSize;
                        scan.moovPayload = scan.prefetch(source, size, position, headerSize, boxSize, header, read);
                    }
                }
                case BoxTypes.MOOF -> {
                    scan.fragments.add(new long[]{position, headerSize, boxSize});
                    scan.fragmentPayloads.add(scan.prefetch(source, size, position, headerSize, boxSize, header, read));
                }
                case BoxTypes.MDAT -> {
                    if (scan.firstMdat < 0) {
                        scan.firstMdat = position;
                    }
                }
                default -> {
                }
            }
            var end = position + boxSize;
            if (stopEarly && scan.moovOffset >= 0 && scan.firstMdat >= 0) {
                scan.next = end <= position || end == Long.MAX_VALUE || (size >= 0 && end > size) ? -1 : end;
                break;
            }
            if (end <= position || (size >= 0 && end > size)) {
                break;
            }
            position = end;
        }
        return scan;
    }

    /**
     * Reads the payload of a box whose header was just read, continuing from the header bytes,
     * when it fits the remaining budget.
     *
     * <p>Nothing is read, and {@code null} is returned, when prefetching is disabled or stopped,
     * the payload exceeds the remaining budget, or the box runs past the end of a source of known
     * size; a payload whose read comes up short or fails is dropped and stops prefetching, so the
     * caller reads it again.
     *
     * @param source the input
     * @param size the source size, or {@code -1} when unknown
     * @param position the offset of the box
     * @param headerSize the size of the box header
     * @param boxSize the size of the box
     * @param header the bytes read at {@code position}
     * @param read the number of valid bytes in {@code header}
     * @return the payload, or {@code null} when it was not prefetched
     */
    private byte[] prefetch(MediaSource source, long size, long position, int headerSize, long boxSize, byte[] header,
                            int read) {
        var length = boxSize - headerSize;
        if (budget < 0 || length + PAYLOAD_OVERHEAD > budget || (size >= 0 && position + boxSize > size)) {
            return null;
        }
        var payload = new byte[(int) length];
        var buffered = (int) Math.max(0, Math.min(read - headerSize, length));
        System.arraycopy(header, headerSize, payload, 0, buffered);
        try {
            var done = buffered;
            while (done < payload.length) {
                var count = source.read(position + headerSize + done, payload, done, payload.length - done);
                if (count <= 0) {
                    budget = -1;
                    return null;
                }
                done += count;
            }
        } catch (RuntimeException exception) {
            budget = -1;
            return null;
        }
        budget -= length + PAYLOAD_OVERHEAD;
        return payload;
    }

    /**
     * Reads the header of the top-level box at an offset.
     *
     * <p>A 32-bit size of {@code 1} announces a 64-bit size; a size of {@code 0} extends the box
     * to the end of the file, reported as {@code -1} when the size of the file is unknown.
     *
     * @param source the input
     * @param position the offset of the box
     * @param size the source size, or {@code -1} when unknown
     * @return the header size, the box size and the box type, or {@code null} when the input ends
     * before a whole header or the header is damaged
     */
    static long[] readHeader(MediaSource source, long position, long size) {
        if (size >= 0 && size - position < 8) {
            return null;
        }
        var header = new byte[16];
        var read = SourceBytes.readAvailable(source, position, header, 0, header.length);
        if (read < 8) {
            return null;
        }
        var boxSize = ByteArrays.i32be(header, 0) & 0xFFFFFFFFL;
        var type = ByteArrays.i32be(header, 4);
        var headerSize = 8;
        if (boxSize == 1) {
            if (read < 16) {
                return null;
            }
            boxSize = ByteArrays.i64be(header, 8);
            headerSize = 16;
        } else if (boxSize == 0) {
            boxSize = size < 0 ? -1 : size - position;
        }
        if (boxSize != -1 && boxSize < headerSize) {
            return null;
        }
        return new long[]{headerSize, boxSize, type};
    }

    /**
     * Returns whether the {@code moov} box precedes the first {@code mdat} box.
     *
     * @return {@code true} when a {@code moov} exists and no {@code mdat} comes before it
     */
    boolean isFastStart() {
        return moovOffset >= 0 && (firstMdat < 0 || moovOffset < firstMdat);
    }
}
