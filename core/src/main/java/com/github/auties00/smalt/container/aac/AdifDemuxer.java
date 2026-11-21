package com.github.auties00.smalt.container.aac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.internal.AacRawBlocks;
import com.github.auties00.smalt.container.internal.RawAacPayloadDemuxer;
import com.github.auties00.smalt.container.mpeg.internal.Id3v2;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads AAC in the Audio Data Interchange Format ({@link ContainerFormat#ADIF}, ISO/IEC 13818-7 and ISO/IEC 14496-3
 * subclause 1.A.2): an ADIF header, magic {@code ADIF}, followed by raw data blocks back to back.
 *
 * <p>The track's {@code AudioSpecificConfig} is built from the first program configuration element of the header:
 * its object type (AAC Main, Low Complexity, Scalable Sample Rate or Long Term Prediction) and sampling frequency
 * index, channel configuration 0, and the element itself, so that a decoder maps the channels the element declares.
 * The blocks carry no lengths, so each one's end is found by walking its syntax; each block becomes one packet of
 * 1024 samples, a key frame, timestamped in samples of the core rate. The track is refined for HE-AAC and HE-AAC v2
 * from the first block, whose fill elements signal them. A block cut short by the end of the data, and bytes after
 * the last block that do not start one, end the stream. Leading ID3v2 tags are skipped, and their text frames
 * become {@link #metadata()}.
 *
 * <p>A source of known size is seekable: the next packet after a seek is the one that holds the sample presented at
 * the requested time. Its duration is exact when the stream is at most 16 MiB, which is walked at construction; a
 * larger stream reports an unknown duration until its first seek walks it.
 *
 * <p>Instances are not thread-safe.
 */
public final class AdifDemuxer implements Demuxer {
    /**
     * The demuxer of the blocks.
     */
    private final Demuxer frames;

    /**
     * The text frames of the leading ID3v2 tags.
     */
    private final Map<String, String> metadata;

    /**
     * Constructs a demuxer over {@code source} with {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, not closed by this demuxer
     * @throws MediaException.Malformed if the stream does not start with an ADIF header followed by a raw data block
     * @throws MediaException.Unsupported if the header declares an object type of no raw data block syntax
     * @throws MediaException.LimitExceeded if the channel count exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public AdifDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over {@code source} that enforces the limits of {@code options}.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if the stream does not start with an ADIF header followed by a raw data block
     * @throws MediaException.Unsupported if the header declares an object type of no raw data block syntax
     * @throws MediaException.LimitExceeded if the channel count exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public AdifDemuxer(MediaSource source, DemuxerOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        var size = source.size();
        var tags = new LinkedHashMap<String, String>();
        var start = Id3v2.skip(source, 0, size < 0 ? Long.MAX_VALUE : size, tags);
        var magic = new byte[4];
        if (SourceBytes.readAvailable(source, start, magic, 0, 4) < 4 || !AacRawBlocks.isAdif(magic, 0, 4)) {
            throw new MediaException.Malformed("not an ADIF stream");
        }
        this.frames = new RawAacPayloadDemuxer(ContainerFormat.ADIF, source, start, size, options.limits(), null, 0,
                0);
        tags.putAll(frames.metadata());
        this.metadata = Collections.unmodifiableMap(tags);
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.ADIF;
    }

    @Override
    public List<Track> tracks() {
        return frames.tracks();
    }

    @Override
    public Map<String, String> metadata() {
        return metadata;
    }

    @Override
    public long durationMicros() {
        return frames.durationMicros();
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true} when the size of the source is known
     */
    @Override
    public boolean isSeekable() {
        return frames.isSeekable();
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.Unsupported if the size of the source is unknown
     * @throws MediaException.Malformed {@inheritDoc}
     * @throws MediaException.Io {@inheritDoc}
     */
    @Override
    public void seek(long micros) {
        frames.seek(micros);
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.Malformed {@inheritDoc}
     * @throws MediaException.Io {@inheritDoc}
     */
    @Override
    public Packet read() {
        return frames.read();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The source is not closed.
     */
    @Override
    public void close() {
        frames.close();
    }
}
