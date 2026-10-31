package com.github.auties00.smalt.container.dsd;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.internal.Annotations;
import com.github.auties00.smalt.container.internal.SeekIndex;
import com.github.auties00.smalt.container.mpeg.internal.Id3v2;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads Philips DSD Interchange File Format files ({@code .dff}, DSDIFF 1.5): a {@code "FRM8"} form of type
 * {@code "DSD "} holding a property chunk and the sound data, uncompressed ({@code "DSD "}) or DST-coded
 * ({@code "DST "}).
 *
 * <p>The single track is {@link Codec#DSD_MSBF} for uncompressed sound and {@link Codec#DST} for DST-coded sound,
 * at the DSD rate of the {@code "FS  "} chunk, with the speakers of the {@code "CHNL"} chunk's channel identifiers
 * ({@code SLFT}, {@code SRGT}, {@code MLFT}, {@code MRGT}, {@code C   }, {@code LFE }, {@code LS  }, {@code RS  };
 * other identifiers are of {@link Speaker#UNKNOWN} position) and a time base of one DSD sample. Uncompressed packets
 * hold 4096 bytes of every channel, interleaved as stored; DST packets are the {@code "DSTF"} frames, each of
 * {@code sampleRate / 75} samples per channel, carrying the CRC of the {@code "DSTC"} chunk that follows a frame,
 * when present, as the four bytes of side data {@value #CRC_KEY}.
 *
 * <p>The texts of the edited master information ({@code "DIAR"} artist and {@code "DITI"} title), the general
 * comments of {@code "COMT"} and the ID3v2 tag of an {@code "ID3 "} chunk become {@link #metadata()} (under
 * {@code DIAR}, {@code DITI}, {@code COMT} and the ID3v2 frame names); the {@code TrackStart} markers become
 * {@link #chapters()}, positioned relative to the absolute start time.
 *
 * <p>Every packet is a key frame; a file of known size seeks to the packet holding the target sample, through the
 * {@code "DSTI"} index of a DST file when it has one.
 *
 * <p>Instances are not thread-safe.
 */
public final class DsdiffDemuxer implements Demuxer {
    /**
     * The side data key of the CRC of a DST frame: its four bytes as stored, most significant first.
     */
    public static final String CRC_KEY = "dsdiff:crc";

    private static final int DSD_BYTES_PER_PACKET = 4096;

    private final MediaSource source;

    private final MediaLimits limits;

    private final SourceReader reader;

    private final AudioTrack track;

    private final List<Track> tracks;

    private final Map<String, String> metadata;

    private final List<AttachedPicture> pictures;

    private final List<Chapter> chapters;

    private final boolean dst;

    private final int channels;

    private final int rate;

    private final long soundStart;

    private final long soundEnd;

    private final long samples;

    private final int frameSamples;

    /**
     * The sample rate of the property chunk, which precedes the edited master information in conforming files.
     */
    private long rateHint;

    private long dstIndexOffset = -1;

    private long dstIndexSize;

    private long position;

    private long nextPts;

    private SeekIndex index;

    /**
     * Constructs a demuxer over a DSDIFF file with {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, not closed by this demuxer
     * @throws MediaException.Malformed if the input is not a valid DSDIFF file
     * @throws MediaException.Unsupported if the file's sound data uses a compression other than DST
     * @throws MediaException.Io if reading fails
     */
    public DsdiffDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over a DSDIFF file.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if the input is not a valid DSDIFF file
     * @throws MediaException.Unsupported if the file's sound data uses a compression other than DST
     * @throws MediaException.LimitExceeded if the channel count exceeds the limits of {@code options}
     * @throws MediaException.Io if reading fails
     */
    public DsdiffDemuxer(MediaSource source, DemuxerOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        this.source = source;
        this.limits = options.limits();
        this.reader = new SourceReader(source, 64 * 1024);
        try {
            reader.seek(0);
            if (reader.remaining(16) < 16 || reader.fourCc() != fourCc("FRM8")) {
                throw new MediaException.Malformed("not a DSDIFF file");
            }
            var formSize = reader.u64be();
            if (reader.fourCc() != fourCc("DSD ")) {
                throw new MediaException.Malformed("not a DSDIFF file");
            }
            var formEnd = 12 + formSize;
            if (formSize < 4 || source.size() >= 0 && formEnd > source.size()) {
                formEnd = source.size() >= 0 ? source.size() : Long.MAX_VALUE;
            }
            var tags = new LinkedHashMap<String, String>();
            var annotations = new Annotations();
            var markers = new ArrayList<long[]>();
            var markerTitles = new ArrayList<String>();
            var sampleRate = 0L;
            var channelIds = new ArrayList<Integer>();
            var compression = -1;
            long start = -1;
            long end = -1;
            var soundType = 0;
            long startTime = 0;
            long frames = -1;
            var at = 16L;
            while (at + 12 <= formEnd) {
                reader.seek(at);
                if (reader.remaining(12) < 12) {
                    break;
                }
                var id = reader.fourCc();
                var size = reader.u64be();
                var body = at + 12;
                if (size < 0 || size > formEnd - body) {
                    size = formEnd - body;
                }
                switch (id) {
                    case 0x50524F50 -> {
                        // PROP
                        if (size >= 4 && reader.fourCc() == fourCc("SND ")) {
                            var props = readProperties(body + 4, body + size, channelIds);
                            sampleRate = props[0];
                            rateHint = sampleRate;
                            compression = (int) props[1];
                            startTime = props[2];
                        }
                    }
                    case 0x44534420 -> {
                        // DSD
                        start = body;
                        end = body + size;
                        soundType = id;
                    }
                    case 0x44535420 -> {
                        // DST
                        start = body;
                        end = body + size;
                        soundType = id;
                        if (size >= 18) {
                            reader.seek(body);
                            if (reader.fourCc() == fourCc("FRTE")) {
                                reader.u64be();
                                frames = reader.u32be();
                            }
                        }
                    }
                    case 0x44535449 -> {
                        // DSTI
                        dstIndexOffset = body;
                        dstIndexSize = size;
                    }
                    case 0x434F4D54 -> readComments(body, body + size, tags);
                    case 0x4449494E -> readMasterInfo(body, body + size, tags, markers, markerTitles);
                    case 0x49443320 -> {
                        // ID3, as many writers store a tag
                        if (ForwardOnlySource.isCheapToReach(source, body)) {
                            Id3v2.read(source, body, body + size, tags, annotations);
                        }
                    }
                    default -> {
                    }
                }
                at = body + size + (size & 1);
            }
            if (sampleRate <= 0 || sampleRate > Integer.MAX_VALUE || channelIds.isEmpty()) {
                throw new MediaException.Malformed("DSDIFF file without sample rate or channels");
            }
            if (start < 0) {
                throw new MediaException.Malformed("DSDIFF file without sound data");
            }
            if (compression != fourCc("DSD ") && compression != fourCc("DST ")) {
                throw new MediaException.Unsupported("dsdiff", "DSDIFF compression type "
                        + fourCcName(compression));
            }
            this.dst = compression == fourCc("DST ");
            if (dst != (soundType == fourCc("DST "))) {
                throw new MediaException.Malformed("DSDIFF sound data does not match its compression type");
            }
            this.rate = (int) sampleRate;
            this.channels = channelIds.size();
            LimitChecks.checkChannels(limits, channels);
            this.soundStart = start;
            this.soundEnd = end;
            this.frameSamples = rate / 75;
            if (dst) {
                if (frames < 0) {
                    frames = countFrames();
                }
                this.samples = frames * frameSamples;
            } else {
                this.samples = (end - start) / channels * 8;
            }
            this.metadata = Collections.unmodifiableMap(tags);
            this.pictures = annotations.pictures();
            this.track = AudioTrack.builder()
                    .codec(dst ? Codec.DST : Codec.DSD_MSBF)
                    .timeBase(Rational.timeBaseOf(rate))
                    .duration(samples)
                    .sampleRate(rate)
                    .layout(layoutOf(channelIds))
                    .bitsPerSample(1)
                    .build();
            for (var i = 0; i < markers.size(); i++) {
                var marker = markers.get(i);
                var startSample = marker[0] - startTime;
                var next = i + 1 < markers.size() ? markers.get(i + 1)[0] - startTime : samples;
                if (startSample >= 0 && startSample < samples) {
                    var title = markerTitles.get(i);
                    annotations.addChapter(track.timeBase().toMicros(startSample),
                            track.timeBase().toMicros(Math.max(startSample, Math.min(next, samples))),
                            title.isEmpty() ? null : title);
                }
            }
            this.chapters = annotations.chapters(durationMicros());
            this.tracks = List.of(track);
            this.position = dst ? dstFramesStart() : soundStart;
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid DSDIFF file", exception);
        }
    }

    /**
     * Returns whether the leading bytes of a file are the form chunk of a DSDIFF file.
     *
     * @param header the leading bytes
     * @return {@code true} for {@code "FRM8"} followed by the form type {@code "DSD "}
     */
    public static boolean matches(byte[] header) {
        return header.length >= 16 && header[0] == 'F' && header[1] == 'R' && header[2] == 'M' && header[3] == '8'
                && header[12] == 'D' && header[13] == 'S' && header[14] == 'D' && header[15] == ' ';
    }

    private static int fourCc(String id) {
        return id.charAt(0) << 24 | id.charAt(1) << 16 | id.charAt(2) << 8 | id.charAt(3);
    }

    private static String fourCcName(int id) {
        if (id == -1) {
            return "(none)";
        }
        var chars = new char[4];
        for (var i = 0; i < 4; i++) {
            var c = (char) ((id >>> (24 - 8 * i)) & 0xFF);
            chars[i] = c >= 0x20 && c < 0x7F ? c : '?';
        }
        return new String(chars);
    }

    /**
     * Reads the local chunks of the sound property chunk.
     *
     * @return the sample rate, the compression type and the absolute start time in samples
     */
    private long[] readProperties(long start, long end, List<Integer> channelIds) {
        var rate = 0L;
        var compression = -1L;
        var hours = 0L;
        var minutes = 0L;
        var seconds = 0L;
        var startSamples = 0L;
        var at = start;
        while (at + 12 <= end) {
            reader.seek(at);
            var id = reader.fourCc();
            var size = Math.min(reader.u64be(), end - at - 12);
            if (size < 0) {
                break;
            }
            switch (id) {
                case 0x46532020 -> {
                    // FS
                    if (size >= 4) {
                        rate = reader.u32be();
                    }
                }
                case 0x43484E4C -> {
                    // CHNL
                    if (size >= 2) {
                        var count = reader.u16be();
                        if (size < 2 + 4L * count) {
                            throw new MediaException.Malformed("DSDIFF channels chunk of " + size + " bytes");
                        }
                        LimitChecks.checkChannels(limits, count);
                        channelIds.clear();
                        for (var i = 0; i < count; i++) {
                            channelIds.add(reader.fourCc());
                        }
                    }
                }
                case 0x434D5052 -> {
                    // CMPR
                    if (size >= 4) {
                        compression = reader.fourCc();
                    }
                }
                case 0x41425353 -> {
                    // ABSS
                    if (size >= 8) {
                        hours = reader.u16be();
                        minutes = reader.u8();
                        seconds = reader.u8();
                        startSamples = reader.u32be();
                    }
                }
                default -> {
                }
            }
            at += 12 + size + (size & 1);
        }
        return new long[]{rate, compression, ((hours * 60 + minutes) * 60 + seconds) * rate + startSamples};
    }

    private void readComments(long start, long end, Map<String, String> tags) {
        if (end - start < 2) {
            return;
        }
        reader.seek(start);
        var count = reader.u16be();
        var at = start + 2;
        var general = new StringBuilder();
        for (var i = 0; i < count && at + 14 <= end; i++) {
            reader.seek(at + 6);
            var type = reader.u16be();
            reader.u16be();
            var length = reader.u32be();
            if (length > end - at - 14) {
                break;
            }
            var text = text(at + 14, (int) length);
            if (type == 0 && !text.isEmpty()) {
                if (!general.isEmpty()) {
                    general.append("; ");
                }
                general.append(text);
            }
            at += 14 + length + (length & 1);
        }
        if (!general.isEmpty()) {
            tags.put("COMT", general.toString());
        }
    }

    private void readMasterInfo(long start, long end, Map<String, String> tags, List<long[]> markers,
                                List<String> titles) {
        var at = start;
        while (at + 12 <= end) {
            reader.seek(at);
            var id = reader.fourCc();
            var size = Math.min(reader.u64be(), end - at - 12);
            if (size < 0) {
                break;
            }
            switch (id) {
                case 0x44494152, 0x44495449 -> {
                    // DIAR, DITI
                    if (size >= 4) {
                        var length = reader.u32be();
                        if (length <= size - 4) {
                            var text = text(at + 16, (int) length);
                            if (!text.isEmpty()) {
                                tags.put(id == 0x44494152 ? "DIAR" : "DITI", text);
                            }
                        }
                    }
                }
                case 0x4D41524B -> {
                    // MARK
                    if (size >= 22) {
                        var hours = reader.u16be();
                        var minutes = reader.u8();
                        var seconds = reader.u8();
                        var samples = reader.u32be();
                        var offset = (int) reader.u32be();
                        var type = reader.u16be();
                        reader.u16be();
                        reader.u16be();
                        var length = reader.u32be();
                        if (type == 0) {
                            var sample = ((hours * 60L + minutes) * 60 + seconds) * Math.max(rateHint, 1) + samples
                                    + offset;
                            markers.add(new long[]{sample});
                            titles.add(length <= size - 22 ? text(at + 34, (int) length) : "");
                        }
                    }
                }
                default -> {
                }
            }
            at += 12 + size + (size & 1);
        }
    }

    private String text(long at, int length) {
        if (length <= 0) {
            return "";
        }
        LimitChecks.checkAllocation(limits, length);
        reader.seek(at);
        var bytes = new byte[length];
        reader.readFully(bytes, 0, length);
        var end = length;
        while (end > 0 && bytes[end - 1] == 0) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.ISO_8859_1).strip();
    }

    private static ChannelLayout layoutOf(List<Integer> ids) {
        var speakers = new ArrayList<Speaker>(ids.size());
        for (var id : ids) {
            var speaker = switch (id) {
                case 0x534C4654, 0x4D4C4654 -> Speaker.FRONT_LEFT;
                case 0x53524754, 0x4D524754 -> Speaker.FRONT_RIGHT;
                case 0x43202020 -> Speaker.FRONT_CENTER;
                case 0x4C464520 -> Speaker.LOW_FREQUENCY;
                case 0x4C532020 -> Speaker.BACK_LEFT;
                case 0x52532020 -> Speaker.BACK_RIGHT;
                default -> Speaker.UNKNOWN;
            };
            speakers.add(speakers.contains(speaker) && speaker != Speaker.UNKNOWN ? Speaker.UNKNOWN : speaker);
        }
        if (speakers.size() == 1 && speakers.getFirst() == Speaker.UNKNOWN) {
            return ChannelLayout.MONO;
        }
        return new ChannelLayout(speakers);
    }

    /**
     * Returns the offset of the first chunk after the frame information chunk of the DST sound data.
     */
    private long dstFramesStart() {
        reader.seek(soundStart);
        if (soundEnd - soundStart >= 12 && reader.fourCc() == fourCc("FRTE")) {
            var size = reader.u64be();
            return soundStart + 12 + size + (size & 1);
        }
        return soundStart;
    }

    private long countFrames() {
        var count = 0L;
        var at = dstFramesStart();
        while (at + 12 <= soundEnd) {
            reader.seek(at);
            var id = reader.fourCc();
            var size = reader.u64be();
            if (size < 0 || size > soundEnd - at - 12) {
                break;
            }
            if (id == fourCc("DSTF")) {
                count++;
            }
            at += 12 + size + (size & 1);
        }
        return count;
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.DSDIFF;
    }

    @Override
    public List<Track> tracks() {
        return tracks;
    }

    @Override
    public Map<String, String> metadata() {
        return metadata;
    }

    @Override
    public List<AttachedPicture> attachedPictures() {
        return pictures;
    }

    @Override
    public List<Chapter> chapters() {
        return chapters;
    }

    @Override
    public long durationMicros() {
        return track.timeBase().toMicros(samples);
    }

    @Override
    public Packet read() {
        try {
            return dst ? readDst() : readDsd();
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid DSDIFF sound data", exception);
        }
    }

    private Packet readDsd() {
        var available = (soundEnd - position) / channels * channels;
        if (available <= 0 || nextPts >= samples) {
            return null;
        }
        var bytes = (int) Math.min(available, (long) DSD_BYTES_PER_PACKET * channels);
        reader.seek(position);
        if (reader.remaining(bytes) < bytes) {
            bytes = (int) (reader.remaining(bytes) / channels * channels);
            if (bytes == 0) {
                return null;
            }
        }
        var data = new byte[bytes];
        reader.readFully(data, 0, bytes);
        position += bytes;
        var pts = nextPts;
        var duration = (long) bytes / channels * 8;
        nextPts += duration;
        ForwardOnlySource.release(source, reader.position());
        return new Packet(track.id(), data, pts, pts, duration, true);
    }

    private Packet readDst() {
        while (position + 12 <= soundEnd) {
            reader.seek(position);
            if (reader.remaining(12) < 12) {
                return null;
            }
            var id = reader.fourCc();
            var size = reader.u64be();
            if (size < 0 || size > soundEnd - position - 12) {
                return null;
            }
            var next = position + 12 + size + (size & 1);
            if (id != fourCc("DSTF")) {
                position = next;
                continue;
            }
            LimitChecks.checkAllocation(limits, size);
            if (reader.remaining(size) < size) {
                return null;
            }
            var data = new byte[(int) size];
            reader.readFully(data, 0, data.length);
            position = next;
            var pts = nextPts;
            nextPts += frameSamples;
            var packet = new Packet(track.id(), data, pts, pts, frameSamples, true);
            if (position + 16 <= soundEnd) {
                reader.seek(position);
                if (reader.remaining(16) >= 16 && reader.fourCc() == fourCc("DSTC")) {
                    var crcSize = reader.u64be();
                    if (crcSize >= 4 && crcSize <= soundEnd - position - 12) {
                        var crc = new byte[4];
                        reader.readFully(crc, 0, 4);
                        packet = packet.withSideData(PacketSideData.builder().extra(CRC_KEY, crc).build());
                        position += 12 + crcSize + (crcSize & 1);
                    }
                }
            }
            ForwardOnlySource.release(source, reader.position());
            return packet;
        }
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true} for a file of known size
     */
    @Override
    public boolean isSeekable() {
        return source.size() >= 0;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The next packet is the one holding the target sample, and none for a target at or past the end.
     *
     * @throws MediaException.Unsupported if the size of the source is unknown
     */
    @Override
    public void seek(long micros) {
        if (!isSeekable()) {
            throw new MediaException.Unsupported("seek", "a DSDIFF file of unknown size cannot seek");
        }
        try {
            var target = micros <= 0 ? 0 : Rational.MICROSECONDS.rescale(micros, track.timeBase());
            if (target >= samples) {
                position = soundEnd;
                nextPts = samples;
                return;
            }
            if (!dst) {
                var packetSamples = DSD_BYTES_PER_PACKET * 8L;
                var packet = target / packetSamples;
                position = soundStart + packet * DSD_BYTES_PER_PACKET * channels;
                nextPts = packet * packetSamples;
                return;
            }
            if (index == null) {
                index = buildIndex();
            }
            var frame = target / frameSamples;
            if (frame < index.size()) {
                position = index.offset((int) frame);
                nextPts = frame * frameSamples;
            } else {
                position = soundEnd;
                nextPts = samples;
            }
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid DSDIFF file", exception);
        }
    }

    /**
     * Indexes the chunk offsets of the DST frames, from the {@code "DSTI"} chunk when its entries point at frame
     * chunks and by walking the sound data otherwise.
     */
    private SeekIndex buildIndex() {
        var built = new SeekIndex();
        if (dstIndexOffset >= 0 && dstIndexSize >= 12) {
            var entries = dstIndexSize / 12;
            var valid = true;
            for (var i = 0L; i < entries && valid; i++) {
                reader.seek(dstIndexOffset + i * 12);
                var offset = reader.u64be();
                reader.u32be();
                var chunk = offset - 12;
                if (chunk < soundStart || chunk + 12 > soundEnd) {
                    valid = false;
                    break;
                }
                reader.seek(chunk);
                if (reader.fourCc() != fourCc("DSTF")) {
                    valid = false;
                    break;
                }
                built.add(i * frameSamples, chunk, 0, 0);
            }
            if (valid && built.size() > 0) {
                return built;
            }
            built = new SeekIndex();
        }
        var at = dstFramesStart();
        var frame = 0L;
        while (at + 12 <= soundEnd) {
            reader.seek(at);
            var id = reader.fourCc();
            var size = reader.u64be();
            if (size < 0 || size > soundEnd - at - 12) {
                break;
            }
            if (id == fourCc("DSTF")) {
                built.add(frame++ * frameSamples, at, 0, 0);
            }
            at += 12 + size + (size & 1);
        }
        return built;
    }
}
