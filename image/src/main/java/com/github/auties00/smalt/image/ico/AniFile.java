package com.github.auties00.smalt.image.ico;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.SlicedSource;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Parses the RIFF structure of an animated cursor: {@code RIFF('ACON' [LIST('INFO' ...)] anih
 * [rate] [seq ] LIST('fram' icon ...))}.
 *
 * <p>Damage is tolerated where the intent is clear: the RIFF size is clipped to the input, chunks
 * of unknown identifiers are skipped, the first chunk of each kind wins, an {@code icon} chunk that
 * runs past the input keeps the bytes present, and the {@code anih} frame count yields to the
 * number of {@code icon} chunks. A zero step count means one step per frame (or per {@code seq }
 * entry), a {@code seq } chunk shorter than the step count shortens it, without a {@code seq } chunk
 * the steps never outnumber the frames, and a step past the end of a short {@code rate} chunk is
 * shown for the default display time.
 */
final class AniFile {
    /**
     * The {@code anih} flag declaring that the frames are icon or cursor files.
     */
    private static final int AF_ICON = 1;

    /**
     * The size of the {@code anih} fields read.
     */
    private static final int HEADER_SIZE = 36;

    /**
     * The largest {@code INFO} string read, in bytes.
     */
    private static final int MAX_TEXT = 1 << 16;

    /**
     * The character set of {@code INFO} strings, the Windows ANSI code page.
     */
    static final Charset TEXT = Charset.forName("windows-1252");

    /**
     * The input.
     */
    private final MediaSource source;

    /**
     * The limits applied to the frames.
     */
    private final MediaLimits limits;

    /**
     * The input offset of each frame's icon file.
     */
    private final long[] frameOffsets;

    /**
     * The size of each frame's icon file, clipped to the input.
     */
    private final long[] frameSizes;

    /**
     * The declared header and steps.
     */
    private final AniHeader header;

    /**
     * Constructs a parsed file.
     *
     * @param source the input
     * @param limits the limits applied to the frames
     * @param frameOffsets the offset of each frame
     * @param frameSizes the size of each frame
     * @param header the declared header and steps
     */
    private AniFile(MediaSource source, MediaLimits limits, long[] frameOffsets, long[] frameSizes, AniHeader header) {
        this.source = source;
        this.limits = limits;
        this.frameOffsets = frameOffsets;
        this.frameSizes = frameSizes;
        this.header = header;
    }

    /**
     * Returns whether bytes start an animated cursor.
     *
     * @param data the leading bytes
     * @param length the number of valid bytes
     * @return {@code true} for a RIFF file of form type {@code ACON}
     */
    static boolean matches(byte[] data, int length) {
        return length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'A' && data[9] == 'C' && data[10] == 'O' && data[11] == 'N';
    }

    /**
     * Parses an animated cursor.
     *
     * @param source the input, read from offset zero
     * @param limits the limits checked against the frame and step counts and the chunk sizes
     * @return the parsed file
     * @throws MediaException.Malformed if the RIFF header, the {@code anih} chunk or the frames are
     * missing, or a {@code seq } entry names no frame
     * @throws MediaException.Unsupported if the frames are raw data rather than icon files
     * @throws MediaException.LimitExceeded if the frame or step count exceeds the frame limit
     */
    static AniFile read(MediaSource source, MediaLimits limits) {
        var head = new byte[12];
        if (SourceBytes.readAvailable(source, 0, head, 0, 12) < 12 || !matches(head, 12)) {
            throw new MediaException.Malformed("missing RIFF ACON header");
        }
        var size = source.size();
        var end = 8 + ByteArrays.u32le(head, 4);
        if (size >= 0) {
            end = Math.min(end, size);
        }
        byte[] anih = null;
        long[] rate = null;
        long[] seq = null;
        String title = null;
        String artist = null;
        var offsets = new ArrayList<Long>();
        var sizes = new ArrayList<Long>();
        var chunk = new byte[12];
        var position = 12L;
        while (position + 8 <= end) {
            var read = SourceBytes.readAvailable(source, position, chunk, 0, 12);
            if (read < 8) {
                break;
            }
            Arrays.fill(chunk, Math.max(read, 8), 12, (byte) 0);
            var length = ByteArrays.u32le(chunk, 4);
            var body = position + 8;
            var bodyEnd = Math.min(end, body + length);
            if (is(chunk, 0, "anih")) {
                if (anih == null && length >= HEADER_SIZE) {
                    var fields = bytes(source, body, HEADER_SIZE);
                    anih = fields.length == HEADER_SIZE ? fields : null;
                }
            } else if (is(chunk, 0, "rate")) {
                if (rate == null) {
                    rate = dwords(source, body, bodyEnd - body, limits);
                }
            } else if (is(chunk, 0, "seq ")) {
                if (seq == null) {
                    seq = dwords(source, body, bodyEnd - body, limits);
                }
            } else if (is(chunk, 0, "LIST") && body + 4 <= bodyEnd) {
                if (is(chunk, 8, "fram") && offsets.isEmpty()) {
                    readFrames(source, limits, body + 4, bodyEnd, offsets, sizes);
                } else if (is(chunk, 8, "INFO")) {
                    var info = readInfo(source, body + 4, bodyEnd);
                    title = title != null ? title : info[0];
                    artist = artist != null ? artist : info[1];
                }
            }
            position = body + length + (length & 1);
        }
        if (anih == null) {
            throw new MediaException.Malformed("animated cursor without its anih chunk");
        }
        if (offsets.isEmpty()) {
            throw new MediaException.Malformed("animated cursor without frames");
        }
        var frames = offsets.size();
        var frameOffsets = new long[frames];
        var frameSizes = new long[frames];
        for (var i = 0; i < frames; i++) {
            frameOffsets[i] = offsets.get(i);
            frameSizes[i] = sizes.get(i);
        }
        var file = new AniFile(source, limits, frameOffsets, frameSizes, null);
        if ((ByteArrays.i32le(anih, 32) & AF_ICON) == 0 && !file.framesAreIcons()) {
            // TODO: decode raw frames (anih without AF_ICON), whose layout no published document specifies
            throw new MediaException.Unsupported("ani", "animated cursors with raw frame data are not supported");
        }
        var displayRate = ByteArrays.u32le(anih, 28);
        var declaredSteps = ByteArrays.u32le(anih, 8);
        long steps;
        if (seq != null) {
            steps = declaredSteps == 0 ? seq.length : Math.min(declaredSteps, seq.length);
        } else {
            steps = declaredSteps == 0 ? frames : Math.min(declaredSteps, frames);
        }
        if (steps == 0) {
            throw new MediaException.Malformed("animated cursor without steps");
        }
        LimitChecks.checkFrames(limits, steps);
        var list = new ArrayList<AniStep>((int) steps);
        for (var i = 0; i < steps; i++) {
            var frame = seq != null ? seq[i] : i;
            if (frame >= frames) {
                throw new MediaException.Malformed("animated cursor step " + i + " shows frame " + frame + " of "
                        + frames);
            }
            list.add(new AniStep((int) frame, rate != null && i < rate.length ? rate[i] : displayRate));
        }
        var header = new AniHeader(frames, list, displayRate, Optional.ofNullable(title), Optional.ofNullable(artist));
        return new AniFile(source, limits, frameOffsets, frameSizes, header);
    }

    /**
     * Collects the {@code icon} chunks of a {@code fram} list.
     *
     * @param source the input
     * @param limits the limits the frame count is checked against
     * @param position the offset of the first sub-chunk
     * @param end the end of the list
     * @param offsets receives the offset of each frame
     * @param sizes receives the size of each frame
     * @throws MediaException.LimitExceeded if the frames exceed the frame limit
     */
    private static void readFrames(MediaSource source, MediaLimits limits, long position, long end,
                                   List<Long> offsets, List<Long> sizes) {
        var chunk = new byte[8];
        while (position + 8 <= end) {
            if (SourceBytes.readAvailable(source, position, chunk, 0, 8) < 8) {
                return;
            }
            var length = ByteArrays.u32le(chunk, 4);
            var body = position + 8;
            if (is(chunk, 0, "icon") && body < end) {
                LimitChecks.checkFrames(limits, offsets.size() + 1L);
                offsets.add(body);
                sizes.add(Math.min(length, end - body));
            }
            position = body + length + (length & 1);
        }
    }

    /**
     * Reads the title and author of an {@code INFO} list.
     *
     * @param source the input
     * @param position the offset of the first sub-chunk
     * @param end the end of the list
     * @return the {@code INAM} and {@code IART} strings, each {@code null} when absent
     */
    private static String[] readInfo(MediaSource source, long position, long end) {
        var result = new String[2];
        var chunk = new byte[8];
        while (position + 8 <= end) {
            if (SourceBytes.readAvailable(source, position, chunk, 0, 8) < 8) {
                break;
            }
            var length = ByteArrays.u32le(chunk, 4);
            var body = position + 8;
            var slot = is(chunk, 0, "INAM") ? 0 : is(chunk, 0, "IART") ? 1 : -1;
            if (slot >= 0 && result[slot] == null) {
                var data = bytes(source, body, (int) Math.min(MAX_TEXT, Math.min(length, end - body)));
                var stop = 0;
                while (stop < data.length && data[stop] != 0) {
                    stop++;
                }
                result[slot] = new String(data, 0, stop, TEXT);
            }
            position = body + length + (length & 1);
        }
        return result;
    }

    /**
     * Reads a chunk of little-endian 32-bit values.
     *
     * @param source the input
     * @param position the offset of the chunk data
     * @param length the size of the chunk data present
     * @param limits the limits the values are checked against
     * @return the values, as many as whole values fit
     * @throws MediaException.LimitExceeded if the chunk exceeds the allocation limit
     */
    private static long[] dwords(MediaSource source, long position, long length, MediaLimits limits) {
        var count = (int) Math.min(Integer.MAX_VALUE / 8, Math.max(0, length) / 4);
        LimitChecks.checkAllocation(limits, count * 12L);
        var data = bytes(source, position, count * 4);
        var values = new long[data.length / 4];
        for (var i = 0; i < values.length; i++) {
            values[i] = ByteArrays.u32le(data, i * 4);
        }
        return values;
    }

    /**
     * Reads up to {@code length} bytes.
     *
     * @param source the input
     * @param position the offset
     * @param length the number of bytes wanted
     * @return the bytes present
     */
    private static byte[] bytes(MediaSource source, long position, int length) {
        var data = new byte[length];
        var read = SourceBytes.readAvailable(source, position, data, 0, length);
        return read < length ? Arrays.copyOf(data, Math.max(0, read)) : data;
    }

    /**
     * Returns whether four bytes spell an identifier.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @param identifier the four-character identifier
     * @return {@code true} when they match
     */
    private static boolean is(byte[] data, int offset, String identifier) {
        for (var i = 0; i < 4; i++) {
            if (data[offset + i] != identifier.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether every frame starts with an icon or cursor directory header.
     *
     * @return {@code true} when the frames are icon files although the {@code anih} flags say otherwise
     */
    private boolean framesAreIcons() {
        var head = new byte[4];
        for (var i = 0; i < frameOffsets.length; i++) {
            if (SourceBytes.readAvailable(source, frameOffsets[i], head, 0, 4) < 4 || head[0] != 0 || head[1] != 0
                    || (head[2] != 1 && head[2] != 2) || head[3] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@return the declared header and steps}
     */
    AniHeader header() {
        return header;
    }

    /**
     * {@return the number of stored frames}
     */
    int frameCount() {
        return frameOffsets.length;
    }

    /**
     * Returns the icon file of a frame as a source of its own.
     *
     * @param index the frame index
     * @return the frame's bytes, offset zero being its first byte
     */
    MediaSource frame(int index) {
        return new SlicedSource(source, frameOffsets[index], frameSizes[index]);
    }

    /**
     * Copies the icon file of a frame.
     *
     * @param index the frame index
     * @return the frame's bytes
     * @throws MediaException.LimitExceeded if the frame exceeds the allocation limit
     */
    byte[] frameBytes(int index) {
        LimitChecks.checkAllocation(limits, frameSizes[index]);
        return bytes(source, frameOffsets[index], (int) frameSizes[index]);
    }

    /**
     * {@return the limits applied to the frames}
     */
    MediaLimits limits() {
        return limits;
    }
}
