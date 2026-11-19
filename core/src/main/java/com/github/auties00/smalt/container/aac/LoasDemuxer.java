package com.github.auties00.smalt.container.aac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.internal.LoasPayloadDemuxer;
import com.github.auties00.smalt.container.mpeg.internal.Id3v2;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Track;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads AAC in LOAS framing ({@link ContainerFormat#LOAS}): {@code AudioSyncStream} frames back to back, as
 * ISO/IEC 14496-3 clause 1.7 defines them, each the 11-bit sync word {@code 0x2B7}, a 13-bit length and one LATM
 * {@code AudioMuxElement} with in-band configuration.
 *
 * <p>The {@code StreamMuxConfig} may declare any number of programs and layers, with the same time framing for all
 * streams or chunked payloads, and streams whose payload lengths are coded in the element ({@code frameLengthType} 0)
 * or fixed ({@code frameLengthType} 1); the payloads of the first stream, program 0 and layer 0, become the track,
 * which must be AAC Main, Low Complexity, Scalable Sample Rate or Long Term Prediction, alone or as the core of
 * HE-AAC or HE-AAC v2. Each subframe becomes one packet, the raw data block of 1024 samples, a key frame,
 * timestamped in samples of the track's rate. The track is described by the first {@code StreamMuxConfig} and
 * refined for HE-AAC from its first payload; a later configuration that differs is announced on the next packet as
 * its {@link PacketSideData#newCodecConfig() new codec configuration}, and the durations of frames at another core
 * rate are converted to the track's. Elements that reuse a configuration before one was seen, and damaged elements,
 * are skipped; bytes that do not start a frame are skipped up to the next frame whose length leads to another frame
 * or to the end of the data. Leading ID3v2 tags are skipped, and their text frames become {@link #metadata()}.
 *
 * <p>A source of known size is seekable: the next packet after a seek is the one that holds the sample presented at
 * the requested time. Its duration is exact when the stream is at most 64 MiB, which is walked at construction; a
 * larger stream reports an unknown duration until its first seek walks it.
 *
 * <p>Instances are not thread-safe.
 */
public final class LoasDemuxer implements Demuxer {
    /**
     * The demuxer of the frames.
     */
    private final Demuxer frames;

    /**
     * The text frames of the leading ID3v2 tags, then the metadata of the frames.
     */
    private final Map<String, String> metadata;

    /**
     * Constructs a demuxer over {@code source} with {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, not closed by this demuxer
     * @throws MediaException.Malformed if no frame with a supported configuration starts the stream
     * @throws MediaException.LimitExceeded if the channel count exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public LoasDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over {@code source} that enforces the limits of {@code options}.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if no frame with a supported configuration starts the stream
     * @throws MediaException.LimitExceeded if the channel count exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public LoasDemuxer(MediaSource source, DemuxerOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        var size = source.size();
        var tags = new LinkedHashMap<String, String>();
        var start = Id3v2.skip(source, 0, size < 0 ? Long.MAX_VALUE : size, tags);
        this.frames = new LoasPayloadDemuxer(ContainerFormat.LOAS, source, start, size, options.limits());
        tags.putAll(frames.metadata());
        this.metadata = Collections.unmodifiableMap(tags);
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.LOAS;
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
