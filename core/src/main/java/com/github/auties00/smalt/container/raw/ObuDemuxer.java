package com.github.auties00.smalt.container.raw;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.internal.SeekTimes;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.codec.CodecConfigs;
import com.github.auties00.smalt.internal.codec.VideoConfigs;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads an AV1 elementary stream ({@code .obu}) in the low-overhead bitstream format of the AV1 specification,
 * section 5 ({@link ContainerFormat#AV1}), or in its length-delimited format, Annex B
 * ({@link ContainerFormat#AV1_ANNEX_B}).
 *
 * <p>The stream becomes one {@link VideoTrack} of {@link Codec#AV1}, with identifier 1, whose {@code av1C}
 * configuration record holds the first sequence header and which takes its maximum picture size and color from it.
 * Each temporal unit becomes one packet, as an MP4 file stores it: its OBUs with size fields (an Annex B OBU without
 * one gets it), the temporal delimiter removed. A packet is a key frame when its temporal unit holds a sequence
 * header before its first frame header and that frame is a shown key frame. A key frame whose sequence header
 * differs from the one last reported carries an {@code av1C} record of the new one as its
 * {@linkplain PacketSideData#newCodecConfig() new codec configuration}.
 *
 * <p>Every temporal unit holds one shown frame, so the demuxer times the packets one frame apart at the frame rate of
 * the {@linkplain ElementaryStreamOptions#frameRate() options}, by default the sequence header's timing information
 * or else 25 frames per second: the time base is one frame period and packet {@code n} has presentation and decoding
 * time {@code n}. An OBU or temporal unit cut short by the end of the input ends the stream.
 *
 * <p>The demuxer is seekable: the first {@link #seek(long)} walks the temporal unit headers of the whole stream once.
 * The source is read sequentially between seeks and is not closed. Instances are not thread-safe.
 */
public final class ObuDemuxer implements Demuxer {
    /**
     * The largest OBU or temporal unit accepted, in bytes.
     */
    private static final int CAPACITY = 64 << 20;

    private final MediaSource source;

    private final MediaLimits limits;

    /**
     * Whether the stream is in the length-delimited format of Annex B.
     */
    private final boolean annexB;

    private final VideoTrack track;

    private final SourceReader reader;

    /**
     * The sequence header OBU last reported, with its size field.
     */
    private byte[] established;

    /**
     * Whether the latest sequence header uses the reduced still picture header.
     */
    private boolean reduced;

    /**
     * The index of the next temporal unit.
     */
    private long next;

    /**
     * A low-overhead OBU read past the end of the previous temporal unit, or {@code null}.
     */
    private byte[] carried;

    /**
     * The offsets and indices of the key temporal units, built on the first seek, or {@code null}.
     */
    private long[][] index;

    /**
     * Whether the stream ended: a seek went past the last temporal unit, or a unit was cut short by the end of the
     * input.
     */
    private boolean ended;

    /**
     * The number of temporal units, known once indexed, or {@code -1}.
     */
    private long units = -1;

    /**
     * Constructs a demuxer with {@link ElementaryStreamOptions#defaults()}.
     *
     * @param source the stream, read from offset zero and not closed by this demuxer
     * @throws MediaException.Malformed if the content is not an AV1 OBU stream with a sequence header
     * @throws MediaException.LimitExceeded if an OBU or the picture size exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public ObuDemuxer(MediaSource source) {
        this(source, ElementaryStreamOptions.defaults());
    }

    /**
     * Constructs a demuxer with options.
     *
     * <p>The format, low-overhead or Annex B, is recognized from the stream.
     *
     * @param source the stream, read from offset zero and not closed by this demuxer
     * @param options the limits and frame rate
     * @throws MediaException.Malformed if the content is not an AV1 OBU stream with a sequence header
     * @throws MediaException.LimitExceeded if an OBU or the picture size exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public ObuDemuxer(MediaSource source, ElementaryStreamOptions options) {
        this.source = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        this.limits = options.limits();
        var head = new byte[(int) Math.min(64 * 1024, source.size() < 0 ? 64 * 1024 : source.size())];
        var read = 0;
        while (read < head.length) {
            var count = source.read(read, head, read, head.length - read);
            if (count <= 0) {
                break;
            }
            read += count;
        }
        head = Arrays.copyOf(head, read);
        if (ElementaryProbe.isLowOverheadObu(head)) {
            annexB = false;
        } else if (ElementaryProbe.isAnnexBObu(head)) {
            annexB = true;
        } else {
            throw new MediaException.Malformed("not an AV1 OBU stream");
        }
        this.reader = new SourceReader(source);
        byte[] config = null;
        long[] timing = null;
        while (config == null) {
            var unit = readUnit();
            if (unit == null) {
                throw new MediaException.Malformed("the stream holds no sequence header");
            }
            var sequence = sequenceHeader(unit);
            if (sequence != null) {
                config = CodecConfigs.av1Config(unit);
                if (config == null) {
                    throw new MediaException.Malformed("the sequence header cannot be parsed");
                }
                timing = ElementaryProbe.av1Timing(sequence, payloadOffset(sequence), sequence.length
                        - payloadOffset(sequence));
                established = sequence;
            }
        }
        reader.seek(0);
        carried = null;
        var streamRate = ElementaryProbe.av1FrameRate(timing);
        var frameRate = options.frameRate().orElse(streamRate != null ? streamRate
                : ElementaryStreamOptions.DEFAULT_FRAME_RATE);
        var info = VideoConfigs.describe(Codec.AV1, config);
        var builder = VideoTrack.builder()
                .id(1)
                .codec(Codec.AV1)
                .timeBase(frameRate.invert())
                .frameRate(frameRate)
                .codecConfig(config);
        if (info != null && info.width() > 0 && info.height() > 0) {
            LimitChecks.checkPicture(limits, info.width(), info.height());
            builder.width(info.width()).height(info.height());
            if (info.color() != null) {
                builder.color(info.color());
            }
        }
        this.track = builder.build();
    }

    /**
     * Recognizes an AV1 OBU stream from its leading bytes.
     *
     * <p>The bytes must hold a well-formed OBU structure up to a sequence header that parses: a low-overhead stream
     * starts with a temporal delimiter or a sequence header, an Annex B stream with the sizes of its first temporal
     * unit, frame unit and OBU, then a temporal delimiter.
     *
     * @param header the leading bytes of the input
     * @return {@link ContainerFormat#AV1} or {@link ContainerFormat#AV1_ANNEX_B}, or empty when the bytes start
     * neither
     */
    public static Optional<ContainerFormat> detect(byte[] header) {
        Objects.requireNonNull(header, "header");
        if (ElementaryProbe.isLowOverheadObu(header)) {
            return Optional.of(ContainerFormat.AV1);
        }
        return ElementaryProbe.isAnnexBObu(header) ? Optional.of(ContainerFormat.AV1_ANNEX_B) : Optional.empty();
    }

    /**
     * {@return {@link ContainerFormat#AV1} or {@link ContainerFormat#AV1_ANNEX_B}}
     */
    @Override
    public ContainerFormat format() {
        return annexB ? ContainerFormat.AV1_ANNEX_B : ContainerFormat.AV1;
    }

    @Override
    public List<Track> tracks() {
        return List.of(track);
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The duration is known once a seek has indexed the stream.
     */
    @Override
    public long durationMicros() {
        return units < 0 ? -1 : track.timeBase().toMicros(units);
    }

    /**
     * @throws MediaException.Malformed {@inheritDoc}
     * @throws MediaException.Io {@inheritDoc}
     */
    @Override
    public Packet read() {
        if (ended) {
            return null;
        }
        var unit = readUnit();
        if (unit == null) {
            return null;
        }
        var sequence = sequenceHeader(unit);
        var key = isKey(unit);
        byte[] newConfig = null;
        if (key && sequence != null && !Arrays.equals(sequence, established)) {
            newConfig = CodecConfigs.av1Config(unit);
            established = sequence;
        }
        var flags = key ? EnumSet.of(PacketFlag.KEY) : EnumSet.noneOf(PacketFlag.class);
        var side = newConfig == null ? PacketSideData.NONE
                : PacketSideData.builder().newCodecConfig(newConfig).build();
        var time = next++;
        return new Packet(1, unit, time, time, 1, flags, side);
    }

    @Override
    public boolean isSeekable() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The first seek walks the whole stream once.
     *
     * @throws MediaException.Malformed {@inheritDoc}
     * @throws MediaException.Io {@inheritDoc}
     */
    @Override
    public void seek(long micros) {
        if (index == null) {
            buildIndex();
        }
        var target = SeekTimes.floor(micros, track.timeBase());
        if (target >= units) {
            ended = true;
            next = units;
            return;
        }
        var offset = 0L;
        var number = 0L;
        for (var entry : index) {
            if (entry[1] <= target) {
                offset = entry[0];
                number = entry[1];
            }
        }
        var position = reader.position();
        var savedCarried = carried;
        var savedReduced = reduced;
        var savedEnded = ended;
        try {
            reader.seek(offset);
            carried = null;
            ended = false;
            var first = readUnit();
            established = first == null ? null : sequenceHeader(first);
        } catch (MediaException failure) {
            reader.seek(position);
            carried = savedCarried;
            reduced = savedReduced;
            ended = savedEnded;
            throw failure;
        }
        reader.seek(offset);
        carried = null;
        ended = false;
        next = number;
    }

    /**
     * Walks the whole stream and records the key temporal units.
     */
    private void buildIndex() {
        var position = reader.position();
        var saved = carried;
        var savedReduced = reduced;
        var savedEnded = ended;
        var entries = new ArrayList<long[]>();
        var count = 0L;
        try {
            reader.seek(0);
            carried = null;
            ended = false;
            while (true) {
                var start = carried != null ? reader.position() - carried.length : reader.position();
                var unit = readUnit();
                if (unit == null) {
                    break;
                }
                if (isKey(unit)) {
                    entries.add(new long[]{start, count});
                }
                count++;
            }
        } finally {
            reader.seek(position);
            carried = saved;
            reduced = savedReduced;
            ended = savedEnded;
        }
        index = entries.toArray(new long[0][]);
        units = count;
    }

    /**
     * Reads the next temporal unit, its OBUs with size fields and without the temporal delimiter.
     *
     * @return the unit, or {@code null} at the end of the stream
     */
    private byte[] readUnit() {
        if (ended) {
            return null;
        }
        return annexB ? readAnnexBUnit() : readLowOverheadUnit();
    }

    /**
     * Reads the next temporal unit of a low-overhead stream: the OBUs up to the next temporal delimiter.
     *
     * @return the unit, or {@code null} at the end of the stream
     */
    private byte[] readLowOverheadUnit() {
        var unit = new ByteWriter(4096);
        var first = true;
        while (true) {
            var obu = carried != null ? carried : readObu();
            carried = null;
            if (obu == null) {
                break;
            }
            var type = (obu[0] >> 3) & 0x0F;
            if (type == ElementaryProbe.OBU_TEMPORAL_DELIMITER) {
                if (!first) {
                    carried = obu;
                    break;
                }
                first = false;
                continue;
            }
            first = false;
            if ((long) unit.size() + obu.length > CAPACITY) {
                throw new MediaException.LimitExceeded("a temporal unit exceeds " + CAPACITY + " bytes");
            }
            LimitChecks.checkAllocation(limits, (long) unit.size() + obu.length);
            unit.bytes(obu);
        }
        if (first) {
            return null;
        }
        return unit.toByteArray();
    }

    /**
     * Reads one low-overhead OBU with its header and size field.
     *
     * @return the OBU, or {@code null} at the end of the input or when the OBU is cut short
     * @throws MediaException.Malformed if the OBU header is invalid
     */
    private byte[] readObu() {
        if (reader.atEnd()) {
            return null;
        }
        var start = reader.position();
        var header = reader.u8();
        if ((header & 0x80) != 0 || (header & 0x02) == 0) {
            throw new MediaException.Malformed("invalid OBU header at offset " + start);
        }
        var extension = (header & 0x04) != 0;
        if (extension && reader.atEnd()) {
            return null;
        }
        if (extension) {
            reader.u8();
        }
        var size = 0L;
        var length = 0;
        while (true) {
            if (reader.atEnd()) {
                return null;
            }
            var b = reader.u8();
            size |= (long) (b & 0x7F) << (7 * length);
            length++;
            if ((b & 0x80) == 0) {
                break;
            }
            if (length == 8) {
                throw new MediaException.Malformed("invalid OBU size at offset " + start);
            }
        }
        var total = reader.position() - start + size;
        if (total > CAPACITY) {
            throw new MediaException.LimitExceeded("an OBU exceeds " + CAPACITY + " bytes");
        }
        if (reader.remaining(size) < size) {
            ended = true;
            return null;
        }
        LimitChecks.checkAllocation(limits, total);
        var obu = new byte[(int) total];
        reader.seek(start);
        reader.readFully(obu, 0, obu.length);
        return obu;
    }

    /**
     * Reads the next temporal unit of an Annex B stream, giving every OBU a size field.
     *
     * @return the unit, or {@code null} at the end of the stream or when the unit is cut short
     * @throws MediaException.Malformed if the unit's sizes are inconsistent
     */
    private byte[] readAnnexBUnit() {
        if (reader.atEnd()) {
            return null;
        }
        var unitSize = readLeb128();
        if (unitSize < 0) {
            return null;
        }
        if (unitSize > CAPACITY) {
            throw new MediaException.LimitExceeded("a temporal unit exceeds " + CAPACITY + " bytes");
        }
        if (reader.remaining(unitSize) < unitSize) {
            ended = true;
            return null;
        }
        LimitChecks.checkAllocation(limits, unitSize);
        var data = new byte[(int) unitSize];
        reader.readFully(data, 0, data.length);
        var unit = new ByteWriter(data.length + 64);
        var position = 0;
        while (position < data.length) {
            var frameSize = ElementaryProbe.leb128(data, position, data.length);
            if (frameSize < 0) {
                throw new MediaException.Malformed("invalid frame unit size");
            }
            position += ElementaryProbe.leb128Length(data, position);
            var frameEnd = position + frameSize;
            if (frameEnd > data.length) {
                throw new MediaException.Malformed("a frame unit runs past its temporal unit");
            }
            while (position < frameEnd) {
                var obuLength = ElementaryProbe.leb128(data, position, (int) frameEnd);
                if (obuLength <= 0) {
                    throw new MediaException.Malformed("invalid OBU length");
                }
                position += ElementaryProbe.leb128Length(data, position);
                var obuEnd = position + obuLength;
                if (obuEnd > frameEnd) {
                    throw new MediaException.Malformed("an OBU runs past its frame unit");
                }
                appendWithSize(unit, data, position, (int) obuLength);
                position = (int) obuEnd;
            }
        }
        return unit.toByteArray();
    }

    /**
     * Appends an Annex B OBU to a temporal unit with a size field, dropping a temporal delimiter.
     *
     * @param unit the temporal unit
     * @param data the bytes
     * @param offset the index of the OBU header
     * @param length the OBU length, header included
     * @throws MediaException.Malformed if the OBU header is invalid
     */
    private static void appendWithSize(ByteWriter unit, byte[] data, int offset, int length) {
        var header = data[offset] & 0xFF;
        if ((header & 0x80) != 0) {
            throw new MediaException.Malformed("invalid OBU header");
        }
        var type = (header >> 3) & 0x0F;
        if (type == ElementaryProbe.OBU_TEMPORAL_DELIMITER) {
            return;
        }
        var headerBytes = (header & 0x04) != 0 ? 2 : 1;
        if (length < headerBytes) {
            throw new MediaException.Malformed("truncated OBU header");
        }
        if ((header & 0x02) != 0) {
            var size = ElementaryProbe.leb128(data, offset + headerBytes, offset + length);
            var sizeBytes = size < 0 ? 0 : ElementaryProbe.leb128Length(data, offset + headerBytes);
            if (size < 0 || headerBytes + sizeBytes + size > length) {
                throw new MediaException.Malformed("an OBU size exceeds its length");
            }
            unit.bytes(Arrays.copyOfRange(data, offset, offset + headerBytes + sizeBytes + (int) size));
            return;
        }
        unit.u8(header | 0x02);
        if (headerBytes == 2) {
            unit.u8(data[offset + 1] & 0xFF);
        }
        var payload = length - headerBytes;
        var value = payload;
        do {
            var b = value & 0x7F;
            value >>>= 7;
            unit.u8(value != 0 ? b | 0x80 : b);
        } while (value != 0);
        unit.bytes(Arrays.copyOfRange(data, offset + headerBytes, offset + length));
    }

    /**
     * Reads an LEB128 value from the source.
     *
     * @return the value, or {@code -1} when the input ends first
     * @throws MediaException.Malformed if the value is longer than 8 bytes
     */
    private long readLeb128() {
        var value = 0L;
        for (var i = 0; i < 8; i++) {
            if (reader.atEnd()) {
                return -1;
            }
            var b = reader.u8();
            value |= (long) (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new MediaException.Malformed("invalid LEB128 value");
    }

    /**
     * Returns the first sequence header OBU of a temporal unit, when it precedes the first frame header.
     *
     * @param unit the temporal unit
     * @return the OBU with its header and size field, or {@code null}
     */
    private byte[] sequenceHeader(byte[] unit) {
        var position = 0;
        while (position < unit.length) {
            var header = unit[position] & 0xFF;
            var type = (header >> 3) & 0x0F;
            var payload = position + ((header & 0x04) != 0 ? 2 : 1);
            var size = ElementaryProbe.leb128(unit, payload, unit.length);
            if (size < 0) {
                return null;
            }
            payload += ElementaryProbe.leb128Length(unit, payload);
            var end = payload + size;
            if (end > unit.length) {
                return null;
            }
            if (type == ElementaryProbe.OBU_SEQUENCE_HEADER) {
                var timing = ElementaryProbe.av1Timing(unit, payload, (int) size);
                if (timing == null) {
                    return null;
                }
                reduced = timing[0] == 1;
                return Arrays.copyOfRange(unit, position, (int) end);
            }
            if (type == ElementaryProbe.OBU_FRAME || type == ElementaryProbe.OBU_FRAME_HEADER) {
                return null;
            }
            position = (int) end;
        }
        return null;
    }

    /**
     * Returns the index of the payload of an OBU with a size field.
     *
     * @param obu the OBU
     * @return the index of its first payload byte
     */
    private static int payloadOffset(byte[] obu) {
        var payload = (obu[0] & 0x04) != 0 ? 2 : 1;
        return payload + ElementaryProbe.leb128Length(obu, payload);
    }

    /**
     * Returns whether a temporal unit is a key frame: a sequence header precedes its first frame header, which
     * codes a shown key frame (AV1 specification section 5.9.2).
     *
     * @param unit the temporal unit
     * @return {@code true} for a key frame
     */
    private boolean isKey(byte[] unit) {
        var sequence = false;
        var position = 0;
        while (position < unit.length) {
            var header = unit[position] & 0xFF;
            var type = (header >> 3) & 0x0F;
            var payload = position + ((header & 0x04) != 0 ? 2 : 1);
            var size = ElementaryProbe.leb128(unit, payload, unit.length);
            if (size < 0) {
                return false;
            }
            payload += ElementaryProbe.leb128Length(unit, payload);
            if (payload + size > unit.length) {
                return false;
            }
            if (type == ElementaryProbe.OBU_SEQUENCE_HEADER) {
                var timing = ElementaryProbe.av1Timing(unit, payload, (int) size);
                if (timing != null) {
                    reduced = timing[0] == 1;
                    sequence = true;
                }
            } else if (type == ElementaryProbe.OBU_FRAME || type == ElementaryProbe.OBU_FRAME_HEADER) {
                if (!sequence || size == 0) {
                    return false;
                }
                if (reduced) {
                    return true;
                }
                var bits = new MsbBitReader(unit, payload, (int) Math.min(size, 4));
                if (bits.readFlag()) {
                    return false;
                }
                var frameType = bits.read(2);
                return frameType == 0 && bits.readFlag();
            }
            position = (int) (payload + size);
        }
        return false;
    }
}
