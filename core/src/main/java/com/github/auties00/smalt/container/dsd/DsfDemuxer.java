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
import com.github.auties00.smalt.container.mpeg.internal.Id3v2;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads Sony DSD Stream Files ({@code .dsf}, DSF File Format Specification 1.01): a {@code "DSD "} chunk, a
 * {@code "fmt "} chunk, a {@code "data"} chunk of uncompressed DSD and an optional ID3v2 tag at the end.
 *
 * <p>The single track is {@link Codec#DSD_LSBF_PLANAR} (1 bit per sample) or {@link Codec#DSD_MSBF_PLANAR}
 * (8 bits per sample), at the file's DSD rate, with the speakers of its channel type, a time base of one DSD sample
 * and the length the {@code fmt } chunk declares. Every packet is one block of every channel (4096 bytes per
 * channel in conforming files), channel after channel, timestamped in DSD samples; the last packet's duration
 * excludes the padding of its blocks. The ID3v2 tag the {@code "DSD "} chunk points at becomes
 * {@link #metadata()}, {@link #attachedPictures()} and {@link #chapters()}.
 *
 * <p>Every packet is a key frame and a file of known size seeks to the block holding the target sample.
 *
 * <p>Instances are not thread-safe.
 */
public final class DsfDemuxer implements Demuxer {
    private static final int DSD_CHUNK = 28;

    private final MediaSource source;

    private final MediaLimits limits;

    private final SourceReader reader;

    private final AudioTrack track;

    private final List<Track> tracks;

    private final Map<String, String> metadata;

    private final List<AttachedPicture> pictures;

    private final List<Chapter> chapters;

    private final int channels;

    private final int blockSize;

    private final long dataStart;

    private final long dataEnd;

    private final long samples;

    private long block;

    /**
     * Constructs a demuxer over a DSF file with {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, not closed by this demuxer
     * @throws MediaException.Malformed if the input is not a valid DSF file
     * @throws MediaException.Unsupported if the file declares a format version or format other than raw DSD
     * @throws MediaException.Io if reading fails
     */
    public DsfDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over a DSF file.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if the input is not a valid DSF file
     * @throws MediaException.Unsupported if the file declares a format version or format other than raw DSD
     * @throws MediaException.LimitExceeded if the channel count or block size exceeds the limits of {@code options}
     * @throws MediaException.Io if reading fails
     */
    public DsfDemuxer(MediaSource source, DemuxerOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        this.source = source;
        this.limits = options.limits();
        this.reader = new SourceReader(source, 64 * 1024);
        try {
            reader.seek(0);
            if (reader.remaining(DSD_CHUNK) < DSD_CHUNK || reader.fourCc() != fourCc("DSD ")) {
                throw new MediaException.Malformed("not a DSF file");
            }
            var dsdSize = reader.u64le();
            if (dsdSize < DSD_CHUNK) {
                throw new MediaException.Malformed("DSF DSD chunk of " + dsdSize + " bytes");
            }
            reader.u64le();
            var metadataOffset = reader.u64le();
            reader.seek(dsdSize);
            if (reader.remaining(12) < 12 || reader.fourCc() != fourCc("fmt ")) {
                throw new MediaException.Malformed("DSF file without a fmt chunk");
            }
            var fmtStart = dsdSize;
            var fmtSize = reader.u64le();
            if (fmtSize < 52 || reader.remaining(40) < 40) {
                throw new MediaException.Malformed("DSF fmt chunk of " + fmtSize + " bytes");
            }
            var version = reader.u32le();
            var formatId = reader.u32le();
            if (version != 1) {
                throw new MediaException.Unsupported("dsf", "DSF format version " + version);
            }
            if (formatId != 0) {
                throw new MediaException.Unsupported("dsf", "DSF format " + formatId);
            }
            var channelType = (int) reader.u32le();
            var channelCount = reader.u32le();
            var rate = reader.u32le();
            var bits = (int) reader.u32le();
            var sampleCount = reader.u64le();
            var size = reader.u32le();
            if (channelCount < 1 || channelCount > 64) {
                throw new MediaException.Malformed("DSF file of " + channelCount + " channels");
            }
            this.channels = (int) channelCount;
            LimitChecks.checkChannels(limits, channels);
            if (rate < 8 * 1000 || rate > Integer.MAX_VALUE) {
                throw new MediaException.Malformed("DSF sampling frequency " + rate);
            }
            if (bits != 1 && bits != 8) {
                throw new MediaException.Malformed("DSF bits per sample " + bits);
            }
            if (size < 1 || size > 1 << 24) {
                throw new MediaException.Malformed("DSF block size " + size);
            }
            this.blockSize = (int) size;
            LimitChecks.checkAllocation(limits, (long) blockSize * channels);
            if (sampleCount < 0) {
                throw new MediaException.Malformed("DSF sample count " + sampleCount);
            }
            reader.seek(fmtStart + fmtSize);
            if (reader.remaining(12) < 12 || reader.fourCc() != fourCc("data")) {
                throw new MediaException.Malformed("DSF file without a data chunk");
            }
            var dataSize = reader.u64le();
            if (dataSize < 12) {
                throw new MediaException.Malformed("DSF data chunk of " + dataSize + " bytes");
            }
            this.dataStart = reader.position();
            var end = dataStart + dataSize - 12;
            if (source.size() >= 0 && end > source.size()) {
                end = source.size();
            }
            this.dataEnd = end;
            var blocks = (dataEnd - dataStart) / ((long) blockSize * channels);
            this.samples = Math.min(sampleCount, blocks * blockSize * 8L);
            var tags = new LinkedHashMap<String, String>();
            var annotations = new Annotations();
            if (metadataOffset > 0 && source.size() >= 0 && metadataOffset < source.size()
                    && ForwardOnlySource.isCheapToReach(source, metadataOffset)) {
                Id3v2.read(source, metadataOffset, source.size(), tags, annotations);
                reader.seek(dataStart);
            }
            this.metadata = Collections.unmodifiableMap(tags);
            this.pictures = annotations.pictures();
            this.track = AudioTrack.builder()
                    .codec(bits == 1 ? Codec.DSD_LSBF_PLANAR : Codec.DSD_MSBF_PLANAR)
                    .timeBase(Rational.timeBaseOf(rate))
                    .duration(samples)
                    .sampleRate((int) rate)
                    .layout(layoutOf(channelType, channels))
                    .bitsPerSample(1)
                    .build();
            this.chapters = annotations.chapters(durationMicros());
            this.tracks = List.of(track);
            reader.seek(dataStart);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid DSF file", exception);
        }
    }

    /**
     * Returns whether the leading bytes of a file are the {@code "DSD "} chunk of a DSF file.
     *
     * @param header the leading bytes
     * @return {@code true} for the {@code "DSD "} magic followed by a chunk size of 28 and a {@code "fmt "} chunk
     */
    public static boolean matches(byte[] header) {
        if (header.length < 32 || header[0] != 'D' || header[1] != 'S' || header[2] != 'D' || header[3] != ' ') {
            return false;
        }
        for (var i = 5; i < 12; i++) {
            if (header[i] != 0) {
                return false;
            }
        }
        return header[4] == DSD_CHUNK && header[28] == 'f' && header[29] == 'm' && header[30] == 't'
                && header[31] == ' ';
    }

    private static int fourCc(String id) {
        return id.charAt(0) << 24 | id.charAt(1) << 16 | id.charAt(2) << 8 | id.charAt(3);
    }

    /**
     * Returns the speakers of a DSF channel type, or the default layout of the channel count when the type does not
     * match the count.
     */
    static ChannelLayout layoutOf(int channelType, int channels) {
        var speakers = switch (channelType) {
            case 1 -> List.of(Speaker.FRONT_CENTER);
            case 2 -> List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT);
            case 3 -> List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER);
            case 4 -> List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.BACK_LEFT, Speaker.BACK_RIGHT);
            case 5 -> List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY);
            case 6 -> List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.BACK_LEFT,
                    Speaker.BACK_RIGHT);
            case 7 -> List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY,
                    Speaker.BACK_LEFT, Speaker.BACK_RIGHT);
            default -> List.<Speaker>of();
        };
        return speakers.size() == channels ? new ChannelLayout(speakers) : ChannelLayout.defaultFor(channels);
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.DSF;
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
            var pts = block * blockSize * 8;
            if (pts >= samples) {
                return null;
            }
            var bytes = blockSize * channels;
            var offset = dataStart + block * bytes;
            reader.seek(offset);
            if (reader.remaining(bytes) < bytes) {
                return null;
            }
            var data = new byte[bytes];
            reader.readFully(data, 0, bytes);
            block++;
            ForwardOnlySource.release(source, reader.position());
            return new Packet(track.id(), data, pts, pts, Math.min(blockSize * 8L, samples - pts), true);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid DSF data", exception);
        }
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
     * <p>The next packet is the block holding the target sample, and none for a target at or past the end.
     *
     * @throws MediaException.Unsupported if the size of the source is unknown
     */
    @Override
    public void seek(long micros) {
        if (!isSeekable()) {
            throw new MediaException.Unsupported("seek", "a DSF file of unknown size cannot seek");
        }
        var target = micros <= 0 ? 0 : Rational.MICROSECONDS.rescale(micros, track.timeBase());
        block = Math.min(target, samples) / (blockSize * 8L);
        if (target >= samples) {
            block = (samples + blockSize * 8L - 1) / (blockSize * 8L);
        }
    }
}
