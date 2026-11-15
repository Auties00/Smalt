package com.github.auties00.smalt.spi;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.TagWriteOptions;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A container a test registers through the provider interface: the "toy" format, one audio or data track whose
 * packets follow a header with a padded tag block.
 *
 * <p>The layout, big-endian: {@code "TOYC"}, a version byte (1), a track kind byte (0 audio, 1 data), the length and
 * ASCII name of the {@link Codec} constant, the time base numerator and denominator (32 bits each), for audio the
 * sample rate (32 bits) and channel count (8 bits), the tag block capacity and used length (32 bits each) followed by
 * that many bytes of {@code key=value} lines padded with zeros, then until the end of the file packets of a 64-bit
 * presentation time, a 64-bit duration, a flag byte (1 for a key frame) and a 32-bit length before the data. A file
 * may start with an ID3v2 tag, which the demuxer skips.
 */
public final class ToyContainerProvider implements ContainerProvider {
    /**
     * The magic number of a toy file.
     */
    static final byte[] MAGIC = {'T', 'O', 'Y', 'C'};

    /**
     * The spare bytes a written tag block holds beyond its tags.
     */
    static final int TAG_PADDING = 64;

    /**
     * The codecs the muxer carries.
     */
    private static final Set<Codec> CODECS = EnumSet.of(Codec.PCM_S16LE, Codec.SUBRIP, Codec.WEBVTT);

    /**
     * The offset of the last header {@link #matches} was asked about.
     */
    static volatile long lastOffset = -1;

    public ToyContainerProvider() {
    }

    @Override
    public String name() {
        return "toy";
    }

    @Override
    public int probeLength() {
        return MAGIC.length;
    }

    @Override
    public boolean matches(byte[] header, long offset) {
        if (header.length > MAGIC.length) {
            // an Error, which recognition does not take as a mismatch, so a longer probe fails the scenarios
            throw new AssertionError("handed " + header.length + " bytes for a probe of " + MAGIC.length);
        }
        lastOffset = offset;
        return header.length == MAGIC.length && Arrays.equals(header, MAGIC);
    }

    @Override
    public boolean demuxes() {
        return true;
    }

    @Override
    public Demuxer openDemuxer(MediaSource source, DemuxerOptions options) {
        return new ToyDemuxer(Layout.parse(source, options.limits()), source);
    }

    @Override
    public boolean muxes() {
        return true;
    }

    @Override
    public boolean carries(Codec codec) {
        return CODECS.contains(codec);
    }

    @Override
    public Muxer openMuxer(MediaSink sink) {
        return new ToyMuxer(sink);
    }

    @Override
    public boolean editsTags() {
        return true;
    }

    @Override
    public MediaTags readTags(MediaSource source, MediaLimits limits) {
        return Layout.parse(source, limits).tags();
    }

    @Override
    public TagRewrite planTags(MediaSource source, MediaTags tags, TagWriteOptions options) {
        var layout = Layout.parse(source, options.limits());
        var encoded = encodeTags(tags);
        var size = source.size();
        var plan = TagRewrite.builder().copy(0, layout.tagFieldsOffset());
        var capacity = encoded.length <= layout.tagCapacity() && options.inPlace() ? layout.tagCapacity()
                : encoded.length + TAG_PADDING;
        plan.bytes(tagBlock(encoded, capacity));
        return plan.copy(layout.dataOffset(), size - layout.dataOffset()).build();
    }

    /**
     * Encodes tags as {@code key=value} lines.
     */
    static byte[] encodeTags(MediaTags tags) {
        var text = new StringBuilder();
        for (var entry : tags.entries()) {
            text.append(entry.key()).append('=').append(entry.value().replace('\n', ' ')).append('\n');
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Builds the tag fields: capacity, used length and the padded tags.
     */
    static byte[] tagBlock(byte[] tags, int capacity) {
        return ByteBuffer.allocate(8 + capacity).putInt(capacity).putInt(tags.length).put(tags).array();
    }

    /**
     * Builds a whole toy file.
     *
     * @param track the track, an audio track for an audio file
     * @param tags the tags
     * @param packets the packets
     * @return the file
     */
    public static byte[] file(Track track, MediaTags tags, List<Packet> packets) {
        var sink = new ByteArrayOutputStream();
        sink.writeBytes(header(track, tags));
        for (var packet : packets) {
            sink.writeBytes(packet(packet));
        }
        return sink.toByteArray();
    }

    /**
     * Builds the header of a toy file, up to the first packet.
     */
    static byte[] header(Track track, MediaTags tags) {
        var name = track.codec().name().getBytes(StandardCharsets.US_ASCII);
        var encoded = encodeTags(tags);
        var buffer = ByteBuffer.allocate(64 + name.length + encoded.length + TAG_PADDING);
        buffer.put(MAGIC).put((byte) 1).put((byte) (track instanceof AudioTrack ? 0 : 1)).put((byte) name.length)
                .put(name).putInt((int) track.timeBase().numerator()).putInt((int) track.timeBase().denominator());
        if (track instanceof AudioTrack audio) {
            buffer.putInt(audio.sampleRate()).put((byte) audio.layout().orElseThrow().channels());
        }
        buffer.put(tagBlock(encoded, encoded.length + TAG_PADDING));
        return Arrays.copyOf(buffer.array(), buffer.position());
    }

    /**
     * Encodes one packet record.
     */
    static byte[] packet(Packet packet) {
        return ByteBuffer.allocate(21 + packet.data().length).putLong(packet.pts()).putLong(packet.duration())
                .put((byte) (packet.flags().contains(PacketFlag.KEY) ? 1 : 0)).putInt(packet.data().length)
                .put(packet.data()).array();
    }

    /**
     * The parsed header of a toy file.
     *
     * @param track the track
     * @param tags the tags
     * @param tagFieldsOffset the offset of the tag capacity field
     * @param tagCapacity the capacity of the tag block
     * @param dataOffset the offset of the first packet record
     * @param packets the offsets of the packet records
     * @param durationMicros the end of the last packet, in microseconds
     */
    record Layout(Track track, MediaTags tags, long tagFieldsOffset, int tagCapacity, long dataOffset,
                  long[] packets, long durationMicros) {
        static Layout parse(MediaSource source, MediaLimits limits) {
            var size = source.size();
            if (size < 0) {
                throw new MediaException.Unsupported("toy", "a toy file needs a sized source");
            }
            var reader = new Reader(source, size);
            var base = 0L;
            if (size >= 10 && reader.u8(0) == 'I' && reader.u8(1) == 'D' && reader.u8(2) == '3') {
                var tagSize = ((reader.u8(6) & 0x7F) << 21) | ((reader.u8(7) & 0x7F) << 14)
                        | ((reader.u8(8) & 0x7F) << 7) | (reader.u8(9) & 0x7F);
                base = 10L + tagSize + ((reader.u8(5) & 0x10) != 0 ? 10 : 0);
            }
            for (var i = 0; i < MAGIC.length; i++) {
                if (reader.u8(base + i) != MAGIC[i]) {
                    throw new MediaException.Malformed("not a toy file");
                }
            }
            var position = base + 4;
            if (reader.u8(position++) != 1) {
                throw new MediaException.Unsupported("toy", "unknown toy version");
            }
            var kind = reader.u8(position++);
            var nameLength = reader.u8(position++);
            var name = new String(reader.bytes(position, nameLength), StandardCharsets.US_ASCII);
            position += nameLength;
            Codec codec;
            try {
                codec = Codec.valueOf(name);
            } catch (IllegalArgumentException exception) {
                throw new MediaException.Malformed("unknown toy codec " + name);
            }
            var numerator = reader.u32(position);
            var denominator = reader.u32(position + 4);
            position += 8;
            if (numerator <= 0 || denominator <= 0) {
                throw new MediaException.Malformed("invalid toy time base");
            }
            var timeBase = new Rational(numerator, denominator);
            var sampleRate = 0;
            var channels = 0;
            if (kind == 0) {
                sampleRate = (int) reader.u32(position);
                channels = reader.u8(position + 4);
                position += 5;
                if (sampleRate <= 0 || channels == 0 || channels > limits.maxChannels()) {
                    throw new MediaException.Malformed("invalid toy audio format");
                }
            } else if (kind != 1) {
                throw new MediaException.Malformed("unknown toy track kind " + kind);
            }
            var tagFieldsOffset = position;
            var capacity = reader.u32(position);
            var used = reader.u32(position + 4);
            position += 8;
            if (used > capacity || capacity > Integer.MAX_VALUE || position + capacity > size
                    || capacity > limits.maxBytes()) {
                throw new MediaException.Malformed("invalid toy tag block");
            }
            var tags = MediaTags.builder();
            for (var line : new String(reader.bytes(position, (int) used), StandardCharsets.UTF_8).split("\n")) {
                var separator = line.indexOf('=');
                if (separator > 0) {
                    tags.add(line.substring(0, separator), line.substring(separator + 1));
                }
            }
            var dataOffset = position + capacity;
            var packets = new ArrayList<Long>();
            var end = 0L;
            for (var record = dataOffset; record < size; ) {
                var pts = reader.u64(record);
                var duration = reader.u64(record + 8);
                var length = reader.u32(record + 17);
                if (pts < 0 || duration < 0 || record + 21 + length > size) {
                    throw new MediaException.Malformed("truncated toy packet at " + record);
                }
                packets.add(record);
                end = Math.max(end, pts + duration);
                record += 21 + length;
            }
            var duration = end;
            Track track = kind == 0
                    ? AudioTrack.builder().id(1).codec(codec).timeBase(timeBase).duration(duration)
                    .sampleRate(sampleRate).layout(ChannelLayout.defaultFor(channels)).build()
                    : DataTrack.builder().id(1).codec(codec).timeBase(timeBase).duration(duration).build();
            return new Layout(track, tags.build(), tagFieldsOffset, (int) capacity, dataOffset,
                    packets.stream().mapToLong(Long::longValue).toArray(), timeBase.toMicros(end));
        }
    }

    /**
     * Reads big-endian fields of a source, failing as malformed past its end.
     */
    private record Reader(MediaSource source, long size) {
        byte[] bytes(long position, int length) {
            if (length < 0 || position < 0 || position > size - length) {
                throw new MediaException.Malformed("the toy file ends at " + size);
            }
            var bytes = new byte[length];
            source.readFully(position, bytes, 0, length);
            return bytes;
        }

        int u8(long position) {
            return bytes(position, 1)[0] & 0xFF;
        }

        long u32(long position) {
            return ByteBuffer.wrap(bytes(position, 4)).getInt() & 0xFFFFFFFFL;
        }

        long u64(long position) {
            return ByteBuffer.wrap(bytes(position, 8)).getLong();
        }
    }

    /**
     * Reads the packets of a toy file.
     */
    private static final class ToyDemuxer implements Demuxer {
        private final Layout layout;
        private final Reader reader;
        private int next;

        ToyDemuxer(Layout layout, MediaSource source) {
            this.layout = layout;
            this.reader = new Reader(source, source.size());
        }

        @Override
        public ContainerFormat format() {
            return ContainerFormat.OTHER;
        }

        @Override
        public String formatName() {
            return "toy";
        }

        @Override
        public List<Track> tracks() {
            return List.of(layout.track());
        }

        @Override
        public Map<String, String> metadata() {
            var map = new LinkedHashMap<String, String>();
            for (var entry : layout.tags().entries()) {
                map.putIfAbsent(entry.key(), entry.value());
            }
            return Map.copyOf(map);
        }

        @Override
        public long durationMicros() {
            return layout.durationMicros();
        }

        @Override
        public Packet read() {
            if (next == layout.packets().length) {
                return null;
            }
            var record = layout.packets()[next++];
            var pts = reader.u64(record);
            var duration = reader.u64(record + 8);
            var key = reader.u8(record + 16) == 1;
            var data = reader.bytes(record + 21, (int) reader.u32(record + 17));
            return new Packet(1, data, pts, pts, duration, key ? Set.of(PacketFlag.KEY) : Set.of(),
                    PacketSideData.NONE);
        }
    }

    /**
     * Writes a toy file.
     */
    private static final class ToyMuxer implements Muxer {
        private final MediaSink sink;
        private Track track;
        private MediaTags tags = MediaTags.empty();
        private boolean started;
        private boolean finished;

        ToyMuxer(MediaSink sink) {
            this.sink = sink;
        }

        @Override
        public void addTrack(Track track) {
            if (this.track != null) {
                throw new IllegalArgumentException("a toy file holds one track");
            }
            if (!CODECS.contains(track.codec())) {
                throw new IllegalArgumentException("toy cannot carry " + track.codec().displayName());
            }
            this.track = track;
        }

        @Override
        public boolean supportsTags() {
            return true;
        }

        @Override
        public void setTags(MediaTags tags) {
            if (started) {
                throw new IllegalStateException("the tags are written with the first packet");
            }
            this.tags = tags;
        }

        @Override
        public void writePacket(Packet packet) {
            start();
            sink.write(packet(packet));
        }

        private void start() {
            if (track == null) {
                throw new IllegalStateException("no track");
            }
            if (finished) {
                throw new IllegalStateException("finished");
            }
            if (!started) {
                started = true;
                sink.write(header(track, tags));
            }
        }

        @Override
        public void finish() {
            start();
            finished = true;
        }

        @Override
        public void close() {
        }
    }
}
