package com.github.auties00.smalt.container.dash;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.internal.Languages;
import com.github.auties00.smalt.container.internal.SegmentSource;
import com.github.auties00.smalt.container.internal.StreamMerge;
import com.github.auties00.smalt.container.internal.WebVttSegmentReader;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.container.ts.TsDemuxer;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.subtitle.TextFiles;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackDisposition;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Demuxes an MPEG-DASH presentation (ISO/IEC 23009-1): a media presentation description with the segments of
 * every representation it describes.
 *
 * <p>The manifest is the source; every segment is opened through the {@link DemuxerOptions#dataReferences()
 * resolver} of the options, which receives each URL resolved against the base URLs of the manifest, its period,
 * adaptation set and representation, relative to the manifest unless it is absolute:
 * {@link DataReferenceResolver#directory(Path)} over the manifest's directory reads a presentation on disk, as
 * {@link #open(Path)} does, and an application's resolver reads one served over HTTP. Without a
 * resolver the demuxer fails with {@link MediaException.Unsupported}.
 *
 * <p>Every representation of every period is a stream, except those of adaptation sets with an
 * {@code EssentialProperty} (trick play, or a property the reader would have to understand), and its segments are
 * read as one file: the initialization segment and the media segments of a {@code SegmentTemplate} (with a
 * {@code SegmentTimeline}, or a segment duration over the period or, in a dynamic manifest, the segments that end
 * within its {@code timeShiftBufferDepth} before its {@code publishTime}), a {@code SegmentList} (with media ranges),
 * or the single file of a {@code SegmentBase} or
 * a {@code BaseURL}. Fragmented MP4 segments are read with {@link Mp4Demuxer}, transport stream segments with
 * {@link TsDemuxer}, WebVTT segments as one track (each segment's {@code X-TIMESTAMP-MAP}, if any, moving its cue
 * times), and a single file of another container, such as a WebVTT or TTML subtitle file, with the demuxer
 * {@link Containers} recognizes. The tracks of all streams are numbered from one in stream order and their packets
 * interleaved in decoding order, each stream's timestamps moved to the presentation timeline by its period start
 * less its {@code presentationTimeOffset}. An adaptation set's (or representation's) {@code lang} becomes its
 * tracks' language when they declare none, its {@code Label} their {@code title} metadata, and its roles and audio
 * description accessibility their dispositions. Protected fragmented MP4 segments are decrypted with the
 * {@link DemuxerOptions#decryptionKeys() keys of the options}. A dynamic manifest is read as far as it lists.
 *
 * <p>Instances are not thread-safe. The source is not closed by the demuxer; the files it resolves are closed by
 * {@link #close()}.
 *
 * @apiNote Every representation is read, so a ladder of representations is read in full once per
 * representation.
 */
public final class DashDemuxer implements Demuxer {
    /**
     * The streams.
     */
    private final StreamMerge merge;

    /**
     * The presentation duration the manifest declares, or -1.
     */
    private final long declaredDuration;

    /**
     * Opens a presentation under the default options, which resolve no file: a manifest that names a segment
     * fails, so a presentation is read with {@link #DashDemuxer(MediaSource, DemuxerOptions)} and a resolver.
     *
     * @param source the manifest
     * @throws MediaException.Unsupported if a segment cannot be resolved
     * @throws MediaException.Malformed if the manifest or a segment is malformed
     * @throws MediaException.Io if reading fails
     */
    public DashDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Opens a presentation.
     *
     * @param source the manifest, not closed by the demuxer
     * @param options the limits, the resolver of the segments, and the decryption keys
     * @throws MediaException.Unsupported if a segment cannot be resolved, a live manifest addresses segments by
     * duration without a publish time, or a representation's segments are in no container Smalt reads
     * @throws MediaException.Malformed if the manifest or a segment is malformed
     * @throws MediaException.LimitExceeded if the manifest or a representation exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public DashDemuxer(MediaSource source, DemuxerOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        var bytes = SourceBytes.readAll(source, options.limits(), true);
        var text = TextFiles.normalizeLineBreaks(TextFiles.decode(bytes, StandardCharsets.UTF_8));
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        var manifest = DashManifest.parse(text);
        var resolver = options.dataReferences();
        var inner = options.toBuilder().noDataReferences().build();
        var streams = new ArrayList<StreamMerge.Stream>();
        try {
            for (var representation : manifest.representations()) {
                streams.add(open(representation, resolver, options, inner));
            }
        } catch (RuntimeException exception) {
            for (var stream : streams) {
                closeQuietly(stream);
            }
            throw exception;
        }
        this.merge = new StreamMerge(streams);
        this.declaredDuration = manifest.durationMicros();
    }

    /**
     * Opens a presentation on disk under the default limits: the manifest file, and the segments it addresses in its
     * directory and the directories below it.
     *
     * @param manifest the manifest file
     * @return the demuxer, which the caller closes
     * @throws MediaException.Unsupported if a segment is not in the manifest's directory, a live manifest addresses
     * segments by duration without a publish time, or a representation's segments are in no container Smalt reads
     * @throws MediaException.Malformed if the manifest or a segment is malformed
     * @throws MediaException.LimitExceeded if the manifest or a representation exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public static DashDemuxer open(Path manifest) {
        return open(manifest, DemuxerOptions.defaults());
    }

    /**
     * Opens a presentation on disk: the manifest file, and the segments it addresses in its directory and the
     * directories below it, whatever resolver the options hold.
     *
     * @param manifest the manifest file
     * @param options the limits and the decryption keys
     * @return the demuxer, which the caller closes
     * @throws MediaException.Unsupported if a segment is not in the manifest's directory, a live manifest addresses
     * segments by duration without a publish time, or a representation's segments are in no container Smalt reads
     * @throws MediaException.Malformed if the manifest or a segment is malformed
     * @throws MediaException.LimitExceeded if the manifest or a representation exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public static DashDemuxer open(Path manifest, DemuxerOptions options) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(options, "options");
        var directory = manifest.toAbsolutePath().getParent();
        // the manifest is read whole when the demuxer opens, so its file can be closed at once
        try (var source = MediaSource.of(manifest)) {
            return new DashDemuxer(source, options.toBuilder().dataReferences(DataReferenceResolver.directory(directory))
                    .build());
        }
    }

    /**
     * Opens the demuxer of one representation.
     *
     * @param representation the representation and its segments
     * @param resolver opens the segments
     * @param options the demuxing options of the presentation
     * @param inner the options of the segment demuxers, which resolve no data reference
     * @return the stream
     */
    private static StreamMerge.Stream open(DashManifest.Representation representation,
                                           DataReferenceResolver resolver,
                                           DemuxerOptions options, DemuxerOptions inner) {
        var parts = representation.parts();
        var mime = representation.mimeType();
        Demuxer demuxer;
        var resources = new ArrayList<AutoCloseable>();
        if (mime.startsWith("text/vtt") && parts.size() > 1) {
            demuxer = WebVttSegmentReader.open(parts, resolver, options, 0);
        } else {
            var source = new SegmentSource(parts, resolver, options.limits());
            resources.add(source);
            try {
                if (mime.endsWith("/mp4")) {
                    demuxer = new Mp4Demuxer(source, inner);
                } else if (mime.equals("video/mp2t")) {
                    demuxer = new TsDemuxer(source, inner);
                } else {
                    demuxer = Containers.openDemuxer(source, inner);
                }
            } catch (RuntimeException exception) {
                source.close();
                throw exception;
            }
        }
        var language = representation.language();
        return new StreamMerge.Stream(demuxer, representation.offsetMicros(),
                language == null ? null : Languages.bibliographicCode(language), representation.label(),
                representation.dispositions(), resources);
    }

    /**
     * Closes a stream after a failure, keeping the failure.
     *
     * @param stream the stream that failed to open
     */
    private static void closeQuietly(StreamMerge.Stream stream) {
        try {
            stream.demuxer().close();
        } catch (RuntimeException ignored) {
            // the failure being reported matters more
        }
        for (var resource : stream.resources()) {
            try {
                resource.close();
            } catch (Exception ignored) {
                // the failure being reported matters more
            }
        }
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.DASH;
    }

    @Override
    public List<Track> tracks() {
        return merge.tracks();
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of();
    }

    @Override
    public Map<String, String> trackMetadata(int trackId) {
        return merge.trackMetadata(trackId);
    }

    @Override
    public Set<TrackDisposition> trackDisposition(int trackId) {
        return merge.trackDisposition(trackId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The duration is the manifest's {@code mediaPresentationDuration}, or the longest stream's.
     */
    @Override
    public long durationMicros() {
        return declaredDuration >= 0 ? declaredDuration : merge.durationMicros();
    }

    @Override
    public Packet read() {
        return merge.read();
    }

    @Override
    public boolean isSeekable() {
        return merge.isSeekable();
    }

    @Override
    public void seek(long micros) {
        merge.seek(micros);
    }

    @Override
    public void close() {
        merge.close();
    }
}
