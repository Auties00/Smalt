package com.github.auties00.smalt.container.avi;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.internal.WaveFormat;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.internal.codec.NalConfigs;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Writes AVI files with the OpenDML 1.02 extensions.
 *
 * <p>Video tracks of Motion-JPEG, MPEG-4 Part 2, H.264, HEVC, H.263, VP8 and VP9, and audio tracks
 * of PCM (8-bit unsigned, 16, 24 and 32-bit signed and 32 and 64-bit float little-endian, A-law,
 * mu-law), MP3, MP2, AAC and AC-3 are accepted, at most 99 tracks in all. Video is stored at a
 * constant frame rate, the track's frame rate (or the inverse of its time base when that is a
 * plausible frame period, 25 frames per second otherwise); a gap in the timestamps is filled with
 * empty chunks, which players treat as dropped frames, so presentation times survive. H.264 and
 * HEVC packets, length-prefixed in Smalt, are written as Annex B byte streams with the parameter
 * sets of the track's configuration record in front of every key frame. The decoder configuration
 * of MPEG-4 Part 2 and AAC tracks is stored as the extra data of the stream format. PCM audio is a
 * constant bitrate stream of sample blocks; compressed audio is a variable bitrate stream of one
 * frame per chunk.
 *
 * <p>Packets of the tracks are interleaved by decoding time: the muxer holds each packet until
 * every track has one waiting, or until it holds two seconds or 512 packets, then writes the
 * earliest. The file is split into RIFF forms of at most
 * {@link AviMuxerOptions#riffSize()} bytes and indexed as {@link AviMuxerOptions} describes;
 * {@link #finish()} writes the indexes of the last form and rewrites the headers with the final
 * lengths, which needs a seekable sink.
 *
 * <p>Instances are not thread-safe.
 */
public final class AviMuxer implements Muxer {
    /**
     * The number of super index entries reserved per stream.
     */
    private static final int SUPER_ENTRIES = 256;

    /**
     * The size of the main header body.
     */
    private static final int AVIH_SIZE = 56;

    /**
     * The size of the stream header body.
     */
    private static final int STRH_SIZE = 56;

    /**
     * The size of the OpenDML extended header body.
     */
    private static final int DMLH_SIZE = 248;

    /**
     * The longest time the interleaver holds packets, in microseconds.
     */
    private static final long HOLD_MICROS = 2_000_000;

    /**
     * The most packets the interleaver holds.
     */
    private static final int HOLD_PACKETS = 512;

    /**
     * The longest gap of empty video chunks inserted for missing frames.
     */
    private static final long MAX_GAP_FRAMES = 1 << 16;

    /**
     * The output.
     */
    private final MediaSink output;

    /**
     * The options.
     */
    private final AviMuxerOptions options;

    /**
     * The position of the RIFF header of the file.
     */
    private final long base;

    /**
     * The streams, in track order.
     */
    private final List<Stream> streams = new ArrayList<>();

    /**
     * The packets held for interleaving.
     */
    private int held;

    /**
     * Whether the headers were written.
     */
    private boolean started;

    /**
     * Whether {@link #finish()} ran.
     */
    private boolean finished;

    /**
     * The position of the current RIFF form's header.
     */
    private long riffStart;

    /**
     * The position of the current {@code movi} FourCC.
     */
    private long moviStart;

    /**
     * The number of RIFF forms started.
     */
    private int forms;

    /**
     * The {@code idx1} entries of the first form: chunk identifier, flags, offset and size.
     */
    private final ByteWriter legacyIndex = new ByteWriter(4096);

    /**
     * The position of the main header body.
     */
    private long avihPosition;

    /**
     * The position of the OpenDML extended header body.
     */
    private long dmlhPosition;

    /**
     * The position after the last byte written.
     */
    private long endPosition;

    /**
     * Constructs a muxer with the default options.
     *
     * @param output the sink, which must be seekable; written from its position and not closed
     * @throws IllegalArgumentException if the sink is not seekable
     * @throws MediaException.Io if the sink position cannot be read
     */
    public AviMuxer(MediaSink output) {
        this(output, AviMuxerOptions.defaults());
    }

    /**
     * Constructs a muxer.
     *
     * @param output the sink, which must be seekable; written from its position and not closed
     * @param options the index and OpenDML settings
     * @throws IllegalArgumentException if the sink is not seekable
     * @throws MediaException.Io if the sink position cannot be read
     */
    public AviMuxer(MediaSink output, AviMuxerOptions options) {
        this.output = Objects.requireNonNull(output, "output");
        this.options = Objects.requireNonNull(options, "options");
        if (!output.isSeekable()) {
            throw new IllegalArgumentException("AVI output needs a seekable sink");
        }
        this.base = output.position();
    }

    /**
     * Returns whether AVI can carry a codec.
     *
     * @param codec the codec of the track to write
     * @return {@code true} for the codecs listed in the class documentation
     */
    public static boolean supports(Codec codec) {
        Objects.requireNonNull(codec, "codec");
        return codec.kind() == TrackKind.VIDEO ? RiffCodecs.videoFourCc(codec) != null
                : WaveFormat.tagOf(codec) >= 0;
    }

    @Override
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        if (started) {
            throw new IllegalStateException("tracks must be added before the first packet");
        }
        if (!supports(track.codec())) {
            throw new IllegalArgumentException("AVI cannot carry " + track.codec().displayName());
        }
        if (streams.size() >= 99) {
            throw new IllegalArgumentException("AVI carries at most 99 tracks");
        }
        for (var stream : streams) {
            if (stream.track.id() == track.id()) {
                throw new IllegalArgumentException("duplicate track id " + track.id());
            }
        }
        if (track instanceof AudioTrack audio && audio.sampleRate() <= 0) {
            throw new IllegalArgumentException("audio track without a sample rate");
        }
        streams.add(new Stream(streams.size(), track));
    }

    @Override
    public void writePacket(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        if (finished) {
            throw new IllegalStateException("muxer is finished");
        }
        var stream = find(packet.trackId());
        if (stream == null) {
            throw new IllegalArgumentException("packet of undeclared track " + packet.trackId());
        }
        if (!started) {
            if (streams.isEmpty()) {
                throw new IllegalStateException("no track was added");
            }
            writeHeaders();
            started = true;
        }
        stream.queue.add(packet);
        held++;
        drain(false);
    }

    @Override
    public void finish() {
        if (finished) {
            throw new IllegalStateException("muxer is already finished");
        }
        if (!started) {
            if (streams.isEmpty()) {
                throw new IllegalStateException("no track was added");
            }
            writeHeaders();
            started = true;
        }
        drain(true);
        closeForm();
        patchHeaders();
        output.position(endPosition);
        finished = true;
    }

    @Override
    public void close() {
    }

    /**
     * Returns the stream of a track.
     *
     * @param trackId the track identifier
     * @return the stream, or {@code null}
     */
    private Stream find(int trackId) {
        for (var stream : streams) {
            if (stream.track.id() == trackId) {
                return stream;
            }
        }
        return null;
    }

    /**
     * Writes held packets in decoding time order: while every stream holds one, or, when
     * {@code all} is set or the hold limits are reached, regardless.
     *
     * @param all whether every held packet is written
     */
    private void drain(boolean all) {
        while (held > 0) {
            Stream earliest = null;
            var earliestTime = Long.MAX_VALUE;
            var latestTime = Long.MIN_VALUE;
            var everyStream = true;
            for (var stream : streams) {
                var head = stream.queue.peek();
                if (head == null) {
                    everyStream = false;
                    continue;
                }
                var time = stream.micros(head);
                if (time < earliestTime) {
                    earliestTime = time;
                    earliest = stream;
                }
                var last = stream.micros(stream.queue.peekLast());
                latestTime = Math.max(latestTime, last);
            }
            if (!all && !everyStream && held < HOLD_PACKETS && latestTime - earliestTime < HOLD_MICROS) {
                return;
            }
            var packet = earliest.queue.poll();
            held--;
            earliest.write(packet);
        }
    }

    /**
     * Writes the RIFF header, the header list with reserved indexes and the first {@code movi}
     * list header.
     */
    private void writeHeaders() {
        var header = new ByteWriter(8192);
        header.fourCc("RIFF");
        header.u32le(0);
        header.fourCc("AVI ");
        header.fourCc("LIST");
        var hdrlSize = header.size();
        header.u32le(0);
        header.fourCc("hdrl");
        header.fourCc("avih");
        header.u32le(AVIH_SIZE);
        var avih = header.size();
        header.zeros(AVIH_SIZE);
        for (var stream : streams) {
            header.fourCc("LIST");
            var strlSize = header.size();
            header.u32le(0);
            header.fourCc("strl");
            header.fourCc("strh");
            header.u32le(STRH_SIZE);
            stream.strhOffset = header.size();
            header.zeros(STRH_SIZE);
            var format = stream.format();
            header.fourCc("strf");
            header.u32le(format.length);
            header.bytes(format);
            if ((format.length & 1) != 0) {
                header.u8(0);
            }
            header.fourCc("indx");
            header.u32le(24 + 16 * SUPER_ENTRIES);
            stream.indxOffset = header.size();
            header.zeros(24 + 16 * SUPER_ENTRIES);
            header.patch32le(strlSize, header.size() - strlSize - 4);
        }
        header.fourCc("LIST");
        header.u32le(4 + 8 + DMLH_SIZE);
        header.fourCc("odml");
        header.fourCc("dmlh");
        header.u32le(DMLH_SIZE);
        var dmlh = header.size();
        header.zeros(DMLH_SIZE);
        header.patch32le(hdrlSize, header.size() - hdrlSize - 4);
        header.fourCc("LIST");
        header.u32le(4 + 8 + 6);
        header.fourCc("INFO");
        header.fourCc("ISFT");
        header.u32le(6);
        header.bytes("Smalt".getBytes(StandardCharsets.US_ASCII));
        header.u8(0);
        header.fourCc("LIST");
        header.u32le(0);
        var moviFourCc = header.size();
        header.fourCc("movi");
        header.writeTo(output);
        avihPosition = base + avih;
        dmlhPosition = base + dmlh;
        for (var stream : streams) {
            stream.strhPosition = base + stream.strhOffset;
            stream.indxPosition = base + stream.indxOffset;
        }
        riffStart = base;
        moviStart = base + moviFourCc;
        endPosition = output.position();
        forms = 1;
    }

    /**
     * Writes one chunk into the current {@code movi} list, starting a new RIFF form first when the
     * chunk would make the current one exceed its size.
     *
     * @param stream the stream the chunk belongs to
     * @param data the payload
     * @param offset the index of the payload in {@code data}
     * @param length the payload length
     * @param key whether the chunk is a key frame
     */
    private void writeChunk(Stream stream, byte[] data, int offset, int length, boolean key) {
        var indexReserve = 0L;
        for (var each : streams) {
            indexReserve += 32 + 8L * (each.formEntries + 1);
        }
        if (forms == 1 && options.legacyIndex()) {
            indexReserve += 8 + legacyIndex.size() + 16;
        }
        if (endPosition - riffStart + 8 + length + 1 + indexReserve > options.riffSize() && countFormChunks() > 0) {
            closeForm();
            startForm();
        }
        var header = new byte[8];
        var id = stream.chunkId();
        for (var i = 0; i < 4; i++) {
            header[i] = (byte) id.charAt(i);
        }
        putU32(header, 4, length);
        var chunkPosition = endPosition;
        output.position(endPosition);
        output.write(header, 0, 8);
        output.write(data, offset, length);
        if ((length & 1) != 0) {
            output.write(new byte[1], 0, 1);
        }
        endPosition = output.position();
        stream.addEntry(chunkPosition + 8, length, key);
        if (forms == 1 && options.legacyIndex()) {
            legacyIndex.fourCc(id);
            legacyIndex.u32le(key ? 0x10 : 0);
            legacyIndex.u32le(chunkPosition - moviStart);
            legacyIndex.u32le(length);
        }
    }

    /**
     * {@return the number of chunks of every stream in the current form}
     */
    private int countFormChunks() {
        var total = 0;
        for (var stream : streams) {
            total += stream.formChunks;
        }
        return total;
    }

    /**
     * Ends the current RIFF form: writes the standard index of every stream, the {@code idx1}
     * index of the first form, and the form and list sizes.
     */
    private void closeForm() {
        output.position(endPosition);
        for (var stream : streams) {
            stream.writeStandardIndex();
        }
        patch32(moviStart - 4, endPosition - moviStart);
        if (forms == 1 && options.legacyIndex()) {
            var index = new ByteWriter(8 + legacyIndex.size());
            index.fourCc("idx1");
            index.u32le(legacyIndex.size());
            index.bytes(legacyIndex.toByteArray());
            output.position(endPosition);
            index.writeTo(output);
            endPosition = output.position();
        }
        patch32(riffStart + 4, endPosition - riffStart - 8);
    }

    /**
     * Starts an OpenDML {@code AVIX} RIFF form and its {@code movi} list.
     */
    private void startForm() {
        var header = new ByteWriter(24);
        header.fourCc("RIFF");
        header.u32le(0);
        header.fourCc("AVIX");
        header.fourCc("LIST");
        header.u32le(0);
        header.fourCc("movi");
        riffStart = endPosition;
        moviStart = endPosition + 20;
        output.position(endPosition);
        header.writeTo(output);
        endPosition = output.position();
        forms++;
    }

    /**
     * Rewrites the headers with the final lengths, buffer sizes and super indexes.
     */
    private void patchHeaders() {
        Stream firstVideo = null;
        for (var stream : streams) {
            if (stream.video && firstVideo == null) {
                firstVideo = stream;
            }
        }
        var avih = new ByteWriter(AVIH_SIZE);
        var microsPerFrame = firstVideo != null ? firstVideo.scale * 1_000_000L / firstVideo.rate : 0;
        avih.u32le(microsPerFrame);
        var duration = Math.max(1, totalMicros());
        var bytes = endPosition - base;
        avih.u32le(Math.min(0xFFFFFFFFL, bytes * 1_000_000L / duration));
        avih.u32le(0);
        avih.u32le(0x10 | 0x100);
        avih.u32le(firstVideo != null ? Math.min(0xFFFFFFFFL, firstVideo.firstFormLength) : 0);
        avih.u32le(0);
        avih.u32le(streams.size());
        var buffer = 0L;
        for (var stream : streams) {
            buffer = Math.max(buffer, stream.largest);
        }
        avih.u32le(buffer + 8);
        avih.u32le(firstVideo != null ? ((VideoTrack) firstVideo.track).width() : 0);
        avih.u32le(firstVideo != null ? ((VideoTrack) firstVideo.track).height() : 0);
        avih.zeros(16);
        write(avihPosition, avih);
        for (var stream : streams) {
            write(stream.strhPosition, stream.header());
            write(stream.indxPosition, stream.superIndex());
        }
        var dmlh = new ByteWriter(4);
        dmlh.u32le(firstVideo != null ? Math.min(0xFFFFFFFFL, firstVideo.length) : 0);
        write(dmlhPosition, dmlh);
    }

    /**
     * {@return the duration of the longest stream, in microseconds}
     */
    private long totalMicros() {
        var longest = 0L;
        for (var stream : streams) {
            longest = Math.max(longest, stream.durationMicros());
        }
        return longest;
    }

    /**
     * Writes bytes at a position.
     *
     * @param position the offset relative to the start of the file
     * @param data the bytes
     */
    private void write(long position, ByteWriter data) {
        output.position(position);
        data.writeTo(output);
    }

    /**
     * Writes a little-endian 32-bit value at a position.
     *
     * @param position the offset of the field relative to the start of the file
     * @param value the field value, an unsigned 32-bit integer
     */
    private void patch32(long position, long value) {
        var bytes = new byte[4];
        putU32(bytes, 0, value);
        output.position(position);
        output.write(bytes, 0, 4);
    }

    /**
     * Stores a little-endian 32-bit value.
     *
     * @param data the array
     * @param offset the index
     * @param value the field value, an unsigned 32-bit integer
     */
    private static void putU32(byte[] data, int offset, long value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }

    /**
     * Holds the state of one stream.
     */
    private final class Stream {
        /**
         * The stream number.
         */
        final int number;

        /**
         * The track.
         */
        final Track track;

        /**
         * Whether the stream is a video stream.
         */
        final boolean video;

        /**
         * The packets held for interleaving.
         */
        final ArrayDeque<Packet> queue = new ArrayDeque<>();

        /**
         * The {@code dwScale} of the stream header.
         */
        long scale;

        /**
         * The {@code dwRate} of the stream header.
         */
        long rate;

        /**
         * The {@code dwSampleSize} of the stream header.
         */
        int sampleSize;

        /**
         * The block alignment of PCM audio.
         */
        int blockAlign;

        /**
         * The stream's length in frames, chunks or sample blocks.
         */
        long length;

        /**
         * The stream's length within the first RIFF form.
         */
        long firstFormLength;

        /**
         * The largest chunk written.
         */
        long largest;

        /**
         * The offset of the stream header body in the header block.
         */
        int strhOffset;

        /**
         * The offset of the super index body in the header block.
         */
        int indxOffset;

        /**
         * The position of the stream header body.
         */
        long strhPosition;

        /**
         * The position of the super index body.
         */
        long indxPosition;

        /**
         * The standard index entries of the current form: offset relative to the form's
         * {@code movi} list, and size with bit 31 set for non-key frames.
         */
        final ByteWriter formIndex = new ByteWriter(1024);

        /**
         * The number of entries in {@link #formIndex}.
         */
        int formEntries;

        /**
         * The number of chunks of the current form.
         */
        int formChunks;

        /**
         * The duration of the current form's chunks in stream units.
         */
        long formDuration;

        /**
         * The super index entries: position and size of each standard index, and its duration.
         */
        final ByteWriter superEntries = new ByteWriter(256);

        /**
         * The number of super index entries.
         */
        int superCount;

        /**
         * The decoding timestamp of the first packet, or {@code Long.MIN_VALUE}.
         */
        long firstTime = Long.MIN_VALUE;

        /**
         * The H.264 or HEVC parameter sets written in front of key frames, as an Annex B byte
         * stream.
         */
        byte[] parameterSets = new byte[0];

        /**
         * The NAL unit length prefix size of H.264 or HEVC packets, or 0.
         */
        int lengthSize;

        /**
         * Constructs the state of a stream.
         *
         * @param number the stream number
         * @param track the track the stream writes
         */
        Stream(int number, Track track) {
            this.number = number;
            this.track = track;
            this.video = track instanceof VideoTrack;
            if (video) {
                var frameRate = ((VideoTrack) track).frameRate().orElse(null);
                if (frameRate == null || frameRate.numerator() <= 0 || frameRate.denominator() <= 0) {
                    var inverse = track.timeBase().invert();
                    frameRate = inverse.toDouble() >= 1 && inverse.toDouble() <= 240 ? inverse : new Rational(25, 1);
                }
                frameRate = frameRate.reduce();
                while (frameRate.numerator() > 0xFFFFFFFFL || frameRate.denominator() > 0xFFFFFFFFL) {
                    frameRate = new Rational(Math.max(1, frameRate.numerator() / 2),
                            Math.max(1, frameRate.denominator() / 2));
                }
                scale = frameRate.denominator();
                rate = frameRate.numerator();
                var config = track.codecConfig().orElse(null);
                var parsed = track.codec() == Codec.H264 ? NalConfigs.parseAvc(config)
                        : track.codec() == Codec.HEVC ? NalConfigs.parseHevc(config) : null;
                if (parsed != null) {
                    lengthSize = parsed.lengthSize();
                    parameterSets = NalUnits.annexBParameterSets(parsed.parameterSets());
                }
            } else {
                var audio = (AudioTrack) track;
                var channels = audio.layout().map(layout -> layout.channels()).orElse(2);
                var bits = track.codec().pcmBitsPerSample().orElse(0);
                if (bits > 0) {
                    blockAlign = Math.max(1, channels * bits / 8);
                    scale = blockAlign;
                    rate = (long) audio.sampleRate() * blockAlign;
                    sampleSize = blockAlign;
                } else {
                    blockAlign = 1;
                    scale = switch (track.codec()) {
                        case MP3 -> audio.sampleRate() >= 32000 ? 1152 : 576;
                        case MP2 -> 1152;
                        case AC3 -> 1536;
                        default -> 1024;
                    };
                    rate = audio.sampleRate();
                    sampleSize = 0;
                }
            }
        }

        /**
         * {@return the chunk identifier of the stream's data chunks}
         */
        String chunkId() {
            var digits = (char) ('0' + number / 10) + "" + (char) ('0' + number % 10);
            return digits + (video ? "dc" : "wb");
        }

        /**
         * Returns the decoding time of a packet in microseconds.
         *
         * @param packet a packet of this stream
         * @return the time, or 0 without a timestamp
         */
        long micros(Packet packet) {
            var time = packet.dts() != Packet.NO_TIMESTAMP ? packet.dts() : packet.pts();
            return time == Packet.NO_TIMESTAMP ? 0 : track.timeBase().toMicros(time);
        }

        /**
         * Writes one packet, preceded by empty chunks for the frames a video timestamp gap skips.
         *
         * @param packet a packet of this stream
         */
        void write(Packet packet) {
            var data = packet.data();
            if (video) {
                var time = packet.dts() != Packet.NO_TIMESTAMP ? packet.dts() : packet.pts();
                if (time != Packet.NO_TIMESTAMP) {
                    if (firstTime == Long.MIN_VALUE) {
                        firstTime = time;
                    }
                    var frame = Math.round((time - firstTime) * track.timeBase().toDouble() * rate / scale);
                    var gap = Math.min(MAX_GAP_FRAMES, frame - length);
                    for (var i = 0; i < gap; i++) {
                        writeChunk(this, new byte[0], 0, 0, false);
                        length++;
                    }
                }
                if (lengthSize > 0) {
                    data = annexB(data, packet.keyframe());
                }
                writeChunk(this, data, 0, data.length, packet.keyframe());
                length++;
            } else {
                writeChunk(this, data, 0, data.length, true);
                length += sampleSize > 0 ? data.length / sampleSize : 1;
            }
            largest = Math.max(largest, data.length);
        }

        /**
         * Converts a length-prefixed H.264 or HEVC packet into an Annex B byte stream, with the
         * configuration's parameter sets in front of key frames.
         *
         * @param data the packet
         * @param keyframe whether the packet is a key frame
         * @return the byte stream
         */
        private byte[] annexB(byte[] data, boolean keyframe) {
            var out = new ByteArrayOutputStream(data.length + parameterSets.length + 16);
            if (keyframe) {
                out.writeBytes(parameterSets);
            }
            var end = NalUnits.forEachLengthPrefixed(data, lengthSize, (unit, offset, length) -> {
                out.write(0);
                out.write(0);
                out.write(0);
                out.write(1);
                out.write(unit, offset, length);
            });
            if (end + lengthSize <= data.length) {
                throw new IllegalArgumentException("NAL unit length " + NalUnits.readLength(data, end, lengthSize)
                        + " exceeds the packet");
            }
            return out.toByteArray();
        }

        /**
         * Records a chunk in the standard index of the current form.
         *
         * @param dataPosition the position of the chunk's data
         * @param size the chunk size
         * @param key whether the chunk is a key frame
         */
        void addEntry(long dataPosition, int size, boolean key) {
            formIndex.u32le(dataPosition - moviStart);
            formIndex.u32le((size & 0x7FFFFFFFL) | (key ? 0 : 0x80000000L));
            formEntries++;
            formChunks++;
            formDuration += sampleSize > 0 ? size / sampleSize : 1;
            if (forms == 1) {
                firstFormLength += sampleSize > 0 ? size / sampleSize : 1;
            }
        }

        /**
         * Writes the standard index of the current form, if it has entries, and records it in the
         * super index.
         */
        void writeStandardIndex() {
            if (formEntries == 0) {
                return;
            }
            var index = new ByteWriter(32 + formIndex.size());
            var id = chunkId();
            index.fourCc("ix" + id.substring(0, 2));
            index.u32le(24 + formIndex.size());
            index.u16le(2);
            index.u8(0);
            index.u8(1);
            index.u32le(formEntries);
            index.fourCc(id);
            index.u64le(moviStart);
            index.u32le(0);
            index.bytes(formIndex.toByteArray());
            var position = endPosition;
            output.position(position);
            index.writeTo(output);
            endPosition = output.position();
            if (superCount < SUPER_ENTRIES) {
                superEntries.u64le(position);
                superEntries.u32le(index.size());
                superEntries.u32le(formDuration);
                superCount++;
            }
            formIndex.reset();
            formEntries = 0;
            formChunks = 0;
            formDuration = 0;
        }

        /**
         * Returns the stream format ({@code strf}) body: a {@code BITMAPINFOHEADER} or a
         * {@code WAVEFORMATEX} with the codec's extra data.
         *
         * @return the body
         */
        byte[] format() {
            var config = track.codecConfig().orElse(new byte[0]);
            if (video) {
                var videoTrack = (VideoTrack) track;
                var extra = switch (track.codec()) {
                    case MPEG4_PART2 -> config;
                    case H264, HEVC -> parameterSets;
                    default -> new byte[0];
                };
                var out = new ByteWriter(40 + extra.length);
                out.u32le(40 + extra.length);
                out.u32le(videoTrack.width());
                out.u32le(videoTrack.height());
                out.u16le(1);
                out.u16le(24);
                out.fourCc(RiffCodecs.videoFourCc(track.codec()));
                out.u32le((long) videoTrack.width() * videoTrack.height() * 3);
                out.zeros(16);
                out.bytes(extra);
                return out.toByteArray();
            }
            var audio = (AudioTrack) track;
            var channels = audio.layout().map(layout -> layout.channels()).orElse(2);
            var bits = track.codec().pcmBitsPerSample().orElse(0);
            var extra = switch (track.codec()) {
                case AAC -> config;
                case MP3 -> mp3Extra();
                default -> new byte[0];
            };
            var wave = new WaveFormat(WaveFormat.tagOf(track.codec()), channels, audio.sampleRate(),
                    bits > 0 ? (long) audio.sampleRate() * blockAlign : 0, bits > 0 ? blockAlign : 1, bits, bits, 0, false,
                    true, extra);
            var out = new ByteWriter(wave.size(true));
            wave.write(out, true);
            return out.toByteArray();
        }

        /**
         * {@return the {@code MPEGLAYER3WAVEFORMAT} extension of MP3 streams}
         */
        private byte[] mp3Extra() {
            var out = new ByteWriter(12);
            out.u16le(1);
            out.u32le(2);
            out.u16le((int) Math.min(0xFFFF, scale * 144 / 1152 * 2));
            out.u16le(1);
            out.u16le(1393);
            return out.toByteArray();
        }

        /**
         * {@return the stream header ({@code strh}) body with the final lengths}
         */
        ByteWriter header() {
            var out = new ByteWriter(STRH_SIZE);
            out.fourCc(video ? "vids" : "auds");
            out.fourCc(video ? RiffCodecs.videoFourCc(track.codec()) : "\0\0\0\0");
            out.u32le(0);
            out.u16le(0);
            out.u16le(0);
            out.u32le(0);
            out.u32le(scale);
            out.u32le(rate);
            out.u32le(0);
            out.u32le(Math.min(0xFFFFFFFFL, length));
            out.u32le(largest + 8);
            out.u32le(0xFFFFFFFFL);
            out.u32le(sampleSize);
            if (video) {
                var videoTrack = (VideoTrack) track;
                out.u16le(0);
                out.u16le(0);
                out.u16le(videoTrack.width());
                out.u16le(videoTrack.height());
            } else {
                out.zeros(8);
            }
            return out;
        }

        /**
         * {@return the super index ({@code indx}) body, its unused entries zeroed}
         */
        ByteWriter superIndex() {
            var out = new ByteWriter(24 + 16 * SUPER_ENTRIES);
            out.u16le(4);
            out.u8(0);
            out.u8(0);
            out.u32le(superCount);
            out.fourCc(chunkId());
            out.zeros(12);
            out.bytes(superEntries.toByteArray());
            out.zeros(16 * (SUPER_ENTRIES - superCount));
            return out;
        }

        /**
         * {@return the duration of the stream, in microseconds}
         */
        long durationMicros() {
            if (rate == 0) {
                return 0;
            }
            return length * scale * 1_000_000L / rate;
        }
    }
}
