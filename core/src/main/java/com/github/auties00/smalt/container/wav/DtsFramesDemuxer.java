package com.github.auties00.smalt.container.wav;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.internal.SeekIndex;
import com.github.auties00.smalt.internal.codec.DtsConfigs;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads DTS frames back to back: the payload of a WAVE {@code data} chunk of format tag {@code 0x2001}, or the 16-bit
 * stereo samples of a DTS audio CD image, which carry the frames instead of PCM.
 *
 * <p>Frames may be stored in any of the four forms of ETSI TS 102 114: 16-bit words, big-endian (sync word
 * {@code 0x7FFE8001}) or little-endian, or 14 bits of the bitstream in each 16-bit word (sync words
 * {@code 0x1FFFE800 0x07Fx}), big-endian or little-endian. Each frame becomes one packet in the 16-bit big-endian
 * form, the core frame followed by the DTS-HD extension substreams (sync word {@code 0x64582025}) that come with it,
 * timestamped in samples. The track is described by the first core frame. Bytes that do not start a frame are skipped
 * up to the next core sync word, and a frame cut short by the end of the data ends the stream.
 *
 * <p>When the data is at most {@value #MAX_WALK} bytes, a walk over the frames at construction gives an exact
 * duration and indexes every sixteenth frame for {@link #seek(long)}; a larger stream is walked on its first seek.
 *
 * <p>Instances are not thread-safe.
 */
final class DtsFramesDemuxer implements Demuxer {
    /**
     * The largest stored frame, core and extension substreams included, in bytes.
     */
    private static final int MAX_FRAME = 1 << 17;

    /**
     * The largest data walked at construction for an exact duration and seek index.
     */
    static final long MAX_WALK = 64L << 20;

    /**
     * The largest number of bytes searched for the first frame.
     */
    private static final int MAX_LEADING = 1 << 16;

    /**
     * The number of stored bytes that hold the {@value DtsConfigs#HEADER_SIZE} header bytes of a 14-bit frame.
     */
    private static final int PACKED_HEADER = 14;

    /**
     * The flags of a frame.
     */
    private static final Set<PacketFlag> KEY = Set.of(PacketFlag.KEY);

    /**
     * The forms a DTS stream can be stored in.
     */
    private enum Form {
        /**
         * 16-bit big-endian words.
         */
        BIG_16(false, false),

        /**
         * 16-bit little-endian words.
         */
        LITTLE_16(true, false),

        /**
         * 14 bits in each big-endian 16-bit word.
         */
        BIG_14(false, true),

        /**
         * 14 bits in each little-endian 16-bit word.
         */
        LITTLE_14(true, true);

        /**
         * Whether the bytes of every stored word are swapped.
         */
        private final boolean swapped;

        /**
         * Whether each stored word carries 14 bits.
         */
        private final boolean packed;

        /**
         * Constructs a constant.
         *
         * @param swapped whether the bytes of every stored word are swapped
         * @param packed whether each stored word carries 14 bits
         */
        Form(boolean swapped, boolean packed) {
            this.swapped = swapped;
            this.packed = packed;
        }
    }

    private final MediaSource source;
    private final long dataStart;

    /**
     * The offset of the end of the data, or {@link Long#MAX_VALUE} when unknown.
     */
    private final long dataEnd;

    /**
     * The form the frames are stored in.
     */
    private final Form form;

    /**
     * The offset of the first frame.
     */
    private final long first;

    private final AudioTrack track;
    private final List<Track> tracks;

    /**
     * The bytes read from the data, from {@link #bufferStart}, in big-endian word order.
     */
    private final byte[] buffer = new byte[2 * MAX_FRAME];

    /**
     * The header of the frame being measured, in the 16-bit big-endian form.
     */
    private final byte[] header = new byte[DtsConfigs.HEADER_SIZE + 1];

    /**
     * The source offset of the first byte of {@link #buffer}.
     */
    private long bufferStart;

    /**
     * The number of valid bytes in {@link #buffer}.
     */
    private int bufferLength;

    /**
     * The offset where the data was found to end, at or before {@link #dataEnd}.
     */
    private long end;

    /**
     * The length in the 16-bit form of the frame {@link #measure()} found.
     */
    private int frameBytes;

    /**
     * The index of every sixteenth frame, or {@code null} before the walk.
     */
    private SeekIndex index;

    /**
     * The number of samples the walk counted, or {@code -1}.
     */
    private long walkedSamples = -1;

    /**
     * The offset of the next frame.
     */
    private long position;

    /**
     * The timestamp of the next packet.
     */
    private long timestamp;

    /**
     * The timestamp before which packets are dropped after a seek.
     */
    private long skipBefore = Long.MIN_VALUE;

    /**
     * Constructs a demuxer over the frames of a data chunk.
     *
     * @param source the input, not closed by this demuxer
     * @param dataStart the offset of the first byte of the data
     * @param dataEnd the offset of the end of the data, or {@code -1} when unknown
     * @param limits the resource bounds
     * @throws MediaException.Malformed if no valid core frame starts within the first bytes of the data
     * @throws MediaException.LimitExceeded if the channel count exceeds the limits
     */
    DtsFramesDemuxer(MediaSource source, long dataStart, long dataEnd, MediaLimits limits) {
        this.source = source;
        this.dataStart = dataStart;
        this.dataEnd = dataEnd < 0 ? Long.MAX_VALUE : dataEnd;
        this.end = this.dataEnd;
        var lead = new byte[MAX_LEADING];
        var read = readFully(source, dataStart, lead, 0, (int) Math.min(MAX_LEADING, this.dataEnd - dataStart));
        Form found = null;
        var at = 0;
        for (; at + PACKED_HEADER <= read && found == null; at += 2) {
            found = formAt(lead, at);
        }
        if (found == null) {
            throw new MediaException.Malformed("no DTS frame found");
        }
        this.form = found;
        this.first = dataStart + at - 2;
        var stored = Arrays.copyOfRange(lead, at - 2, at - 2 + PACKED_HEADER);
        if (form.swapped) {
            swapWords(stored, 0, stored.length);
        }
        readHeader(stored, 0);
        var rate = DtsConfigs.sampleRate(header, 0);
        var layout = DtsConfigs.layout(header, 0);
        if (rate <= 0 || layout == null) {
            throw new MediaException.Malformed("DTS frame with an invalid sampling rate or channel arrangement");
        }
        LimitChecks.checkChannels(limits, layout.channels());
        var described = AudioTrack.builder()
                .codec(Codec.DTS)
                .timeBase(Rational.timeBaseOf(rate))
                .sampleRate(rate)
                .layout(layout)
                .build();
        if (dataEnd >= 0 && dataEnd - dataStart <= MAX_WALK && ForwardOnlySource.isCheapToReach(source, dataEnd)) {
            walk();
            described = described.toBuilder().duration(walkedSamples).build();
        }
        this.track = described;
        this.tracks = List.of(track);
        position = first;
        timestamp = 0;
    }

    /**
     * Returns whether the 16-bit stereo samples of a PCM data chunk carry DTS frames: a core frame in one of the
     * four forms at the start of the data, followed by another one.
     *
     * @param source the input
     * @param dataStart the offset of the first sample byte
     * @param dataEnd the offset of the end of the samples, or {@code -1} when unknown
     * @return {@code true} when the samples are DTS frames
     */
    static boolean startsWithFrames(MediaSource source, long dataStart, long dataEnd) {
        var limit = dataEnd < 0 ? MAX_FRAME : (int) Math.min(MAX_FRAME, dataEnd - dataStart);
        var lead = new byte[limit];
        var read = readFully(source, dataStart, lead, 0, limit);
        var form = read >= PACKED_HEADER ? formAt(lead, 0) : null;
        if (form == null) {
            return false;
        }
        var stored = Arrays.copyOf(lead, read);
        if (form.swapped) {
            swapWords(stored, 0, stored.length);
        }
        var header = new byte[DtsConfigs.HEADER_SIZE + 1];
        if (form.packed) {
            DtsConfigs.unpack(stored, 0, header, 0, header.length);
        } else {
            System.arraycopy(stored, 0, header, 0, header.length);
        }
        var bytes = DtsConfigs.frameSize(header, 0);
        var next = form.packed ? DtsConfigs.packedSize(bytes) : bytes;
        return bytes >= DtsConfigs.MIN_FRAME_SIZE && next + PACKED_HEADER <= read && formAt(lead, next) == form;
    }

    /**
     * Returns the form of the core sync word stored at an index.
     *
     * @param data the stored bytes
     * @param at the index, with at least {@value #PACKED_HEADER} bytes after it
     * @return the form, or {@code null} when no sync word starts there
     */
    private static Form formAt(byte[] data, int at) {
        if (DtsConfigs.isSync(data, at)) {
            return Form.BIG_16;
        }
        if (data[at] == (byte) 0xFE && data[at + 1] == 0x7F && data[at + 2] == 0x01 && data[at + 3] == (byte) 0x80) {
            return Form.LITTLE_16;
        }
        if (DtsConfigs.isPackedSync(data, at)) {
            return Form.BIG_14;
        }
        if (data[at] == (byte) 0xFF && data[at + 1] == 0x1F && data[at + 2] == 0x00 && data[at + 3] == (byte) 0xE8
                && (data[at + 4] & 0xF0) == 0xF0 && data[at + 5] == 0x07) {
            return Form.LITTLE_14;
        }
        return null;
    }

    /**
     * Reads the header of a frame into {@link #header}, in the 16-bit big-endian form.
     *
     * @param stored the stored bytes, in big-endian word order
     * @param at the index of the frame
     */
    private void readHeader(byte[] stored, int at) {
        if (form.packed) {
            DtsConfigs.unpack(stored, at, header, 0, header.length);
        } else {
            System.arraycopy(stored, at, header, 0, header.length);
        }
    }

    /**
     * Reads bytes until a count is read or the input ends.
     *
     * @param source the input
     * @param position the offset of the first byte
     * @param target the destination
     * @param offset the index of the first byte written
     * @param length the number of bytes wanted
     * @return the number of bytes read, fewer than {@code length} only at the end of the input
     */
    private static int readFully(MediaSource source, long position, byte[] target, int offset, int length) {
        var done = 0;
        while (done < length) {
            var read = source.read(position + done, target, offset + done, length - done);
            if (read <= 0) {
                break;
            }
            done += read;
        }
        return done;
    }

    /**
     * Swaps the bytes of every 16-bit word of a range.
     *
     * @param data the bytes
     * @param from the index of the first byte
     * @param to the index after the last byte
     */
    private static void swapWords(byte[] data, int from, int to) {
        for (var at = from; at + 1 < to; at += 2) {
            var byte0 = data[at];
            data[at] = data[at + 1];
            data[at + 1] = byte0;
        }
    }

    /**
     * Makes the bytes from an offset available in {@link #buffer}, up to {@value #MAX_FRAME} of them or the end of
     * the data.
     *
     * <p>Swapped words are swapped back in place, so the buffer of a swapped form always starts on a stored word,
     * an even distance from the start of the data, one byte before an offset at an odd distance.
     *
     * @param at the offset
     * @return the number of bytes available from {@code at}
     */
    private int fill(long at) {
        var base = form.swapped ? at - ((at - dataStart) & 1) : at;
        var held = bufferStart + bufferLength - base;
        if (base < bufferStart || held < 0) {
            bufferStart = base;
            bufferLength = 0;
            held = 0;
        }
        if (held - (at - base) >= MAX_FRAME || bufferStart + bufferLength >= end) {
            return (int) Math.max(0, Math.min(bufferStart + bufferLength - at, MAX_FRAME));
        }
        var keep = (int) held;
        System.arraycopy(buffer, (int) (base - bufferStart), buffer, 0, keep);
        bufferStart = base;
        bufferLength = keep;
        while (bufferLength < buffer.length && bufferStart + bufferLength < end) {
            var want = (int) Math.min(buffer.length - bufferLength, end - (bufferStart + bufferLength));
            want = form.swapped ? want & ~1 : want;
            if (want == 0) {
                end = bufferStart + bufferLength;
                break;
            }
            var from = bufferLength;
            var read = readFully(source, bufferStart + bufferLength, buffer, bufferLength, want);
            bufferLength += read;
            if (form.swapped) {
                swapWords(buffer, from, from + (read & ~1));
            }
            if (read < want) {
                end = bufferStart + bufferLength;
                break;
            }
        }
        return (int) Math.max(0, Math.min(bufferStart + bufferLength - at, MAX_FRAME));
    }

    /**
     * Returns whether a core sync word of this stream's form starts at an index of {@link #buffer}.
     *
     * @param at the index, with at least 6 bytes after it
     * @return {@code true} for a sync word
     */
    private boolean syncAt(int at) {
        if (!form.packed) {
            return DtsConfigs.isSync(buffer, at);
        }
        return DtsConfigs.isPackedSync(buffer, at);
    }

    /**
     * Measures the frame at {@link #position}, resynchronizing past bytes that do not start one, and reads its
     * header into {@link #header} and its length in the 16-bit form into {@link #frameBytes}.
     *
     * @return the stored length of the frame, read from the buffer at {@link #position}, or {@code -1} at the end of
     * the stream
     * @throws MediaException.Malformed if a frame is longer than {@value #MAX_FRAME} bytes
     */
    private int measure() {
        while (position < end) {
            var available = fill(position);
            var at = (int) (position - bufferStart);
            if (available < PACKED_HEADER) {
                position = end;
                return -1;
            }
            if (syncAt(at)) {
                readHeader(buffer, at);
                frameBytes = DtsConfigs.frameSize(header, 0);
            }
            if (!syncAt(at) || frameBytes < DtsConfigs.MIN_FRAME_SIZE) {
                // a frame of the 16-bit forms may end on an odd byte, so their sync words are searched at every byte
                var step = form.packed ? 2 : 1;
                var next = -1;
                for (var candidate = at + step; candidate + 6 <= at + available; candidate += step) {
                    if (syncAt(candidate)) {
                        next = candidate;
                        break;
                    }
                }
                position += next < 0 ? Math.max(2, available - 6) & -step : next - at;
                continue;
            }
            var length = form.packed ? DtsConfigs.packedSize(frameBytes) : frameBytes;
            if (!form.packed) {
                while (length + DtsConfigs.EXTENSION_HEADER_SIZE <= available && DtsConfigs.isExtensionSync(buffer, at + length)) {
                    length += DtsConfigs.extensionSize(buffer, at + length);
                }
                frameBytes = length;
            }
            if (length > available) {
                if (position + available < end) {
                    throw new MediaException.Malformed("DTS frame longer than " + MAX_FRAME + " bytes");
                }
                position = end;
                return -1;
            }
            return length;
        }
        return -1;
    }

    /**
     * Walks the frames of the data, counting the samples and indexing every sixteenth frame; a frame that is not
     * valid ends the walk.
     */
    private void walk() {
        var walkIndex = new SeekIndex();
        position = first;
        var time = 0L;
        var count = 0L;
        try {
            while (true) {
                var length = measure();
                if (length < 0) {
                    break;
                }
                if (count % 16 == 0) {
                    walkIndex.add(time, position, 0, 0);
                }
                time += DtsConfigs.samples(header, 0);
                position += length;
                count++;
            }
        } catch (MediaException.Malformed exception) {
            position = end;
        }
        index = walkIndex;
        walkedSamples = time;
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.WAV;
    }

    @Override
    public List<Track> tracks() {
        return tracks;
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of();
    }

    @Override
    public long durationMicros() {
        return track.duration() < 0 ? -1 : track.timeBase().toMicros(track.duration());
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true} when the end of the data is known
     */
    @Override
    public boolean isSeekable() {
        return dataEnd != Long.MAX_VALUE;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every frame is a key frame; the next packet is the one that holds the sample presented at {@code micros},
     * with its exact timestamp.
     *
     * @throws MediaException.Unsupported if the end of the data is unknown
     */
    @Override
    public void seek(long micros) {
        if (!isSeekable()) {
            throw new MediaException.Unsupported("seek", "DTS in a WAVE file of unknown size cannot seek");
        }
        if (index == null) {
            walk();
        }
        var rate = track.sampleRate();
        var target = micros <= 0 ? 0 : micros / 1_000_000 * rate + micros % 1_000_000 * rate / 1_000_000;
        var entry = index.size() == 0 ? -1 : Math.max(0, index.floor(target));
        if (entry < 0) {
            position = end;
            timestamp = walkedSamples;
        } else {
            position = index.offset(entry);
            timestamp = index.timestamp(entry);
        }
        skipBefore = target;
    }

    @Override
    public Packet read() {
        try {
            while (true) {
                var length = measure();
                if (length < 0) {
                    return null;
                }
                var at = (int) (position - bufferStart);
                var samples = DtsConfigs.samples(header, 0);
                byte[] data;
                if (form.packed) {
                    data = new byte[frameBytes];
                    DtsConfigs.unpack(buffer, at, data, 0, frameBytes);
                } else {
                    data = Arrays.copyOfRange(buffer, at, at + length);
                }
                position += length;
                ForwardOnlySource.release(source, position);
                var time = timestamp;
                timestamp += samples;
                if (time + samples <= skipBefore) {
                    continue;
                }
                return new Packet(track.id(), data, time, time, samples, KEY, PacketSideData.NONE);
            }
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid DTS data", exception);
        }
    }
}
