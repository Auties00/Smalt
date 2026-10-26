package com.github.auties00.smalt.container.hls;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.internal.FragmentKeyFrames;
import com.github.auties00.smalt.container.internal.Languages;
import com.github.auties00.smalt.container.internal.SegmentSource;
import com.github.auties00.smalt.container.internal.StreamMerge;
import com.github.auties00.smalt.container.internal.TsKeyFrames;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Demuxes an HTTP Live Streaming presentation (RFC 8216): a master playlist with every media playlist it
 * references, or one media playlist.
 *
 * <p>The playlist is the source; every other file (media playlists, initialization sections, segments, keys) is
 * opened through the {@link DemuxerOptions#dataReferences() resolver} of the options, which receives each URI
 * resolved against the playlist that names it, relative to the first playlist unless it is absolute:
 * {@link DataReferenceResolver#directory(Path)} over the playlist's directory reads a presentation on disk, as
 * {@link #open(Path)} does, and an application's resolver reads one served over HTTP. Without a resolver the demuxer fails with
 * {@link MediaException.Unsupported}.
 *
 * <p>Every media playlist of a master playlist is a stream: the variants, then the renditions with a URI, each
 * read once even when several variants or groups name it. A stream's segments are read as one file: transport
 * stream segments joined, an initialization section ({@code EXT-X-MAP}) followed by its fragmented MP4 segments,
 * the WebVTT segments of a subtitle rendition (cue times moved by each segment's {@code X-TIMESTAMP-MAP}, a cue
 * repeated in the segments it spans read once), or the segments of another container joined; byte ranges
 * ({@code EXT-X-BYTERANGE}) are honored and gaps ({@code EXT-X-GAP}) skipped. An I-frame playlist read on its own
 * gives its key frames: those of transport stream segments, each range read on its own behind the program tables of
 * its {@code EXT-X-MAP}, and
 * for fragmented MP4 the first sample of each range's movie fragment, timed by its {@code tfdt} and composition
 * offset alone and not decrypted. The tracks of all streams are numbered from one in stream order, and their
 * packets are interleaved in decoding order on one timeline: transport streams share their PES timestamps, shifted
 * so that the earliest presentation time of the first transport stream is zero, and fragmented MP4 streams keep
 * their media times. A rendition's {@code LANGUAGE} becomes its tracks' language when they declare none, its
 * {@code NAME} their {@code title} metadata, and its {@code DEFAULT}, {@code FORCED} and accessibility
 * {@code CHARACTERISTICS} their dispositions.
 *
 * <p>Segments encrypted with {@code METHOD=AES-128} are decrypted with the key their URI names; fragmented MP4
 * segments of {@code SAMPLE-AES} and {@code SAMPLE-AES-CTR} are decrypted with the
 * {@link DemuxerOptions#decryptionKeys() keys of the options}, or else, for the {@code identity} key format, with
 * the key the URI names. A live playlist without {@code EXT-X-ENDLIST} is read as far as it lists.
 *
 * <p>Instances are not thread-safe. The source is not closed by the demuxer; the files it resolves are closed by
 * {@link #close()}.
 *
 * @apiNote To read one variant of a master playlist, open its media playlist instead: every stream of a master
 * playlist is read, so a ladder of variants is read in full once per variant.
 */
public final class HlsDemuxer implements Demuxer {
    /**
     * The tag of a WebVTT file.
     */
    private static final String WEBVTT = "WEBVTT";

    /**
     * The streams.
     */
    private final StreamMerge merge;

    /**
     * The duration the playlists declare, in microseconds.
     */
    private final long playlistDuration;

    /**
     * Opens a presentation under the default options, which resolve no file: a playlist that names a file fails,
     * so a presentation is read with {@link #HlsDemuxer(MediaSource, DemuxerOptions)} and a resolver.
     *
     * @param source the playlist
     * @throws MediaException.Unsupported if a file the playlist names cannot be resolved
     * @throws MediaException.Malformed if a playlist or a segment is malformed
     * @throws MediaException.Io if reading fails
     */
    public HlsDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Opens a presentation.
     *
     * @param source the playlist, master or media, not closed by the demuxer
     * @param options the limits, the resolver of the files the playlist names, and the decryption keys
     * @throws MediaException.Unsupported if a file the playlist names cannot be resolved, a key method or format
     * cannot be decrypted, or a media playlist changes its initialization section
     * @throws MediaException.Malformed if a playlist or a segment is malformed
     * @throws MediaException.LimitExceeded if a playlist or a decrypted segment exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public HlsDemuxer(MediaSource source, DemuxerOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        var lines = HlsPlaylist.lines(text(SourceBytes.readAll(source, options.limits(), true)));
        var resolver = options.dataReferences();
        var planned = new ArrayList<Planned>();
        if (HlsPlaylist.isMaster(lines)) {
            var master = HlsPlaylist.master(lines, "");
            var seen = new HashSet<String>();
            for (var variant : master.variants()) {
                seen.add(variant);
                planned.add(new Planned(variant, readPlaylist(variant, resolver, options), null));
            }
            for (var media : master.media()) {
                if (media.uri() == null || !seen.add(media.uri())) {
                    continue;
                }
                planned.add(new Planned(media.uri(), readPlaylist(media.uri(), resolver, options), media));
            }
        } else {
            planned.add(new Planned("", HlsPlaylist.media(lines, ""), null));
        }
        var streams = new ArrayList<StreamMerge.Stream>();
        var opened = new ArrayList<Opened>();
        var duration = 0L;
        try {
            Long origin = null;
            for (var plan : planned) {
                var segments = plan.playlist().segments();
                if (segments.isEmpty()) {
                    continue;
                }
                var total = 0L;
                for (var segment : segments) {
                    total += segment.durationMicros();
                }
                duration = Math.max(duration, total);
                var stream = open(plan, resolver, options);
                opened.add(stream);
                if (origin == null) {
                    origin = originOf(stream.demuxer());
                }
            }
            var originTicks = origin == null ? 0 : origin;
            for (var stream : opened) {
                var demuxer = stream.demuxer();
                var resources = new ArrayList<AutoCloseable>(stream.resources());
                var offset = 0L;
                if (demuxer == null) {
                    demuxer = WebVttSegmentReader.open(stream.parts(), resolver, options, originTicks);
                } else if (originOf(demuxer) != null) {
                    var ticks = Math.floorMod(originOf(demuxer) - originTicks, 1L << 33);
                    if (ticks >= 1L << 32) {
                        ticks -= 1L << 33;
                    }
                    offset = ticks * 100 / 9;
                }
                streams.add(stream(demuxer, offset, stream.plan().media(), resources));
            }
        } catch (RuntimeException exception) {
            for (var stream : opened) {
                closeQuietly(stream.demuxer(), stream.resources());
            }
            for (var stream : streams) {
                if (opened.stream().noneMatch(candidate -> candidate.demuxer() == stream.demuxer())) {
                    closeQuietly(stream.demuxer(), List.of());
                }
            }
            throw exception;
        }
        this.merge = new StreamMerge(streams);
        this.playlistDuration = duration;
    }

    /**
     * Opens a presentation on disk under the default limits: the playlist file, and the files it names in its
     * directory and the directories below it.
     *
     * @param playlist the master or media playlist
     * @return the demuxer, which the caller closes
     * @throws MediaException.Unsupported if a file the playlist names is not in its directory, or a key method or
     * format cannot be decrypted
     * @throws MediaException.Malformed if a playlist or a segment is malformed
     * @throws MediaException.LimitExceeded if a playlist or a decrypted segment exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public static HlsDemuxer open(Path playlist) {
        return open(playlist, DemuxerOptions.defaults());
    }

    /**
     * Opens a presentation on disk: the playlist file, and the files it names in its directory and the directories
     * below it, whatever resolver the options hold.
     *
     * @param playlist the master or media playlist
     * @param options the limits and the decryption keys
     * @return the demuxer, which the caller closes
     * @throws MediaException.Unsupported if a file the playlist names is not in its directory, or a key method or
     * format cannot be decrypted
     * @throws MediaException.Malformed if a playlist or a segment is malformed
     * @throws MediaException.LimitExceeded if a playlist or a decrypted segment exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public static HlsDemuxer open(Path playlist, DemuxerOptions options) {
        Objects.requireNonNull(playlist, "playlist");
        var directory = playlist.toAbsolutePath().getParent();
        // the playlist is read whole when the demuxer opens, so its file can be closed at once
        try (var source = MediaSource.of(playlist)) {
            return new HlsDemuxer(source, options.toBuilder().dataReferences(DataReferenceResolver.directory(directory))
                    .build());
        }
    }

    /**
     * A media playlist to read, with the rendition that names it.
     *
     * @param uri the location of the playlist
     * @param playlist the parsed playlist
     * @param media the rendition, or {@code null} for a variant or a playlist read alone
     */
    private record Planned(String uri, HlsPlaylist.MediaPlaylist playlist, HlsPlaylist.Media media) {
    }

    /**
     * A media playlist opened: its demuxer, or the parts of its WebVTT segments.
     *
     * @param plan the playlist
     * @param demuxer the demuxer, or {@code null} for WebVTT segments
     * @param parts the WebVTT segments
     * @param resources the sources the demuxer reads
     */
    private record Opened(Planned plan, Demuxer demuxer, List<SegmentSource.Part> parts,
                          List<AutoCloseable> resources) {
    }

    /**
     * Reads a media playlist.
     *
     * @param uri the location of the playlist
     * @param resolver opens it
     * @param options the limits
     * @return the parsed playlist
     */
    private static HlsPlaylist.MediaPlaylist readPlaylist(String uri, DataReferenceResolver resolver,
                                                          DemuxerOptions options) {
        try (var source = new SegmentSource(List.of(SegmentSource.Part.of(uri)), resolver, options.limits())) {
            var lines = HlsPlaylist.lines(text(SourceBytes.readAll(source, options.limits(), false)));
            if (HlsPlaylist.isMaster(lines)) {
                throw new MediaException.Malformed("the media playlist " + uri + " is a master playlist");
            }
            return HlsPlaylist.media(lines, uri);
        }
    }

    /**
     * Opens the demuxer of a media playlist's segments.
     *
     * @param plan the playlist
     * @param resolver opens the files
     * @param options the demuxing options
     * @return the opened stream
     */
    private static Opened open(Planned plan, DataReferenceResolver resolver, DemuxerOptions options) {
        var segments = plan.playlist().segments();
        HlsPlaylist.Section section = null;
        HlsPlaylist.Key sampleKey = null;
        var parts = new ArrayList<SegmentSource.Part>();
        var keys = new HashMap<String, byte[]>();
        for (var segment : segments) {
            if (segment.section() != null) {
                if (section != null && !section.equals(segment.section())) {
                    // TODO: read a media playlist whose initialization section changes (a discontinuity to
                    // another encoding), as a stream per section
                    throw new MediaException.Unsupported("hls", "a media playlist whose initialization section "
                            + "changes");
                }
                section = segment.section();
            }
            var key = segment.key();
            byte[] aesKey = null;
            byte[] iv = null;
            if (key != null && key.method().equals("AES-128")) {
                if (key.uri() == null) {
                    throw new MediaException.Malformed("an AES-128 key without a URI");
                }
                aesKey = keys.computeIfAbsent(key.uri(), uri -> keyBytes(uri, resolver, options));
                iv = key.iv() != null ? key.iv() : sequenceIv(segment.sequence());
            } else if (key != null) {
                sampleKey = key;
            }
            parts.add(new SegmentSource.Part(segment.uri(), segment.offset(), segment.length(), aesKey, iv));
        }
        var fragmented = false;
        if (section != null) {
            var part = new SegmentSource.Part(section.uri(), section.offset(), section.length(), null, null);
            parts.addFirst(part);
            // the map of an I-frame playlist of transport stream segments is their program tables
            fragmented = !isTransportStream(part, resolver, options);
        }
        if (section == null && isWebVtt(segments.getFirst(), parts.getFirst(), resolver, options)) {
            if (sampleKey != null) {
                throw new MediaException.Unsupported("hls", "encrypted WebVTT segments");
            }
            return new Opened(plan, null, parts, List.of());
        }
        var decryption = options.decryptionKeys();
        if (sampleKey != null) {
            if (!fragmented) {
                // TODO: decrypt SAMPLE-AES transport stream segments (H.264 slices, AAC and AC-3 frames)
                throw new MediaException.Unsupported("hls", "SAMPLE-AES transport stream segments are not decrypted");
            }
            var given = decryption;
            var fallback = sampleKey.uri() != null && sampleKey.format().equals("identity")
                    ? keyBytes(sampleKey.uri(), resolver, options) : null;
            decryption = fallback == null ? given : keyId -> given.find(keyId).or(() -> Optional.of(fallback.clone()));
        }
        var inner = options.toBuilder().noDataReferences().decryptionKeys(decryption).build();
        if (!fragmented && plan.playlist().iFramesOnly()) {
            var tables = section != null ? parts.getFirst() : null;
            var ranges = section != null ? parts.subList(1, parts.size()) : parts;
            return new Opened(plan, new TsKeyFrames(tables, ranges, resolver, inner), parts, List.of());
        }
        if (fragmented && plan.playlist().iFramesOnly()) {
            return new Opened(plan, new FragmentKeyFrames(parts.getFirst(), parts.subList(1, parts.size()), resolver,
                    inner), parts, List.of());
        }
        var source = new SegmentSource(parts, resolver, options.limits());
        try {
            Demuxer demuxer;
            if (fragmented) {
                demuxer = new Mp4Demuxer(source, inner);
            } else {
                var format = Containers.detect(source).orElseThrow(() -> new MediaException.Unsupported("hls",
                        "the segments of " + plan.uri() + " are not a recognized container"));
                if (format == ContainerFormat.MPEG_TS) {
                    demuxer = new TsDemuxer(source, inner);
                } else {
                    // packed audio: each segment opens with the ID3 tag of its timestamp, which joins nothing
                    source.close();
                    var joined = new ArrayList<SegmentSource.Part>();
                    for (var part : parts) {
                        joined.add(withoutId3(part, resolver, options));
                    }
                    source = new SegmentSource(joined, resolver, options.limits());
                    demuxer = Containers.openDemuxer(source, inner);
                }
            }
            return new Opened(plan, demuxer, parts, List.of(source));
        } catch (RuntimeException exception) {
            source.close();
            throw exception;
        }
    }

    /**
     * Returns whether a media playlist's segments are WebVTT files: their name ends in {@code .vtt} or
     * {@code .webvtt}, or the first starts with the WebVTT signature.
     *
     * @param segment the first segment
     * @param part its part
     * @param resolver opens it
     * @param options the limits
     * @return {@code true} for WebVTT segments
     */
    private static boolean isWebVtt(HlsPlaylist.Segment segment, SegmentSource.Part part,
                                    DataReferenceResolver resolver, DemuxerOptions options) {
        var path = segment.uri();
        var query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        var lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".vtt") || lower.endsWith(".webvtt")) {
            return true;
        }
        if (part.key() != null) {
            return false;
        }
        try (var source = new SegmentSource(List.of(part), resolver, options.limits())) {
            var head = new byte[Math.min(16, (int) source.size())];
            source.readFully(0, head, 0, head.length);
            var text = new String(head, StandardCharsets.UTF_8);
            if (text.startsWith("﻿")) {
                text = text.substring(1);
            }
            return text.startsWith(WEBVTT);
        }
    }

    /**
     * Returns a segment of packed audio without the ID3 tag that opens it.
     *
     * @param part the segment
     * @param resolver opens it
     * @param options the limits
     * @return the part past the tag, or {@code part} when it is encrypted or opens with no tag
     */
    private static SegmentSource.Part withoutId3(SegmentSource.Part part, DataReferenceResolver resolver,
                                                 DemuxerOptions options) {
        if (part.key() != null) {
            return part;
        }
        try (var source = new SegmentSource(List.of(part), resolver, options.limits())) {
            if (source.size() < 10) {
                return part;
            }
            var header = new byte[10];
            source.readFully(0, header, 0, 10);
            if (header[0] != 'I' || header[1] != 'D' || header[2] != '3') {
                return part;
            }
            var size = 10L + ((header[6] & 0x7F) << 21 | (header[7] & 0x7F) << 14 | (header[8] & 0x7F) << 7
                    | header[9] & 0x7F) + ((header[5] & 0x10) != 0 ? 10 : 0);
            if (size >= source.size()) {
                return part;
            }
            return new SegmentSource.Part(part.uri(), part.offset() + size, source.size() - size, null, null);
        }
    }

    /**
     * Returns the PES timestamp a transport stream demuxer's timeline starts from.
     *
     * @param demuxer the demuxer of a stream
     * @return the 90 kHz origin, or {@code null} for a stream that is not a transport stream
     */
    private static Long originOf(Demuxer demuxer) {
        if (demuxer instanceof TsDemuxer ts) {
            return ts.originTicks();
        }
        return demuxer instanceof TsKeyFrames keyFrames ? keyFrames.originTicks() : null;
    }

    /**
     * Returns whether a part starts with a transport stream packet.
     *
     * @param part the part to look at
     * @param resolver opens it
     * @param options the limits
     * @return {@code true} when its first byte is the sync byte {@code 0x47}
     */
    private static boolean isTransportStream(SegmentSource.Part part, DataReferenceResolver resolver,
                                             DemuxerOptions options) {
        try (var source = new SegmentSource(List.of(part), resolver, options.limits())) {
            var head = new byte[1];
            return source.size() >= 188 && source.read(0, head, 0, 1) == 1 && head[0] == 0x47;
        }
    }

    /**
     * Reads an AES-128 key.
     *
     * @param uri the location of the key
     * @param resolver opens it
     * @param options the limits
     * @return the 16-byte key
     * @throws MediaException.Malformed if the key is not 16 bytes
     */
    private static byte[] keyBytes(String uri, DataReferenceResolver resolver, DemuxerOptions options) {
        try (var source = new SegmentSource(List.of(SegmentSource.Part.of(uri)), resolver, options.limits())) {
            if (source.size() != 16) {
                throw new MediaException.Malformed("the AES-128 key " + uri + " is " + source.size()
                        + " bytes, not 16");
            }
            var key = new byte[16];
            source.readFully(0, key, 0, 16);
            return key;
        }
    }

    /**
     * Returns the initialization vector of a segment without an {@code IV}: its media sequence number as a 128-bit
     * big-endian integer.
     *
     * @param sequence the media sequence number
     * @return the initialization vector
     */
    private static byte[] sequenceIv(long sequence) {
        var iv = new byte[16];
        for (var index = 0; index < 8; index++) {
            iv[15 - index] = (byte) (sequence >>> (8 * index));
        }
        return iv;
    }

    /**
     * Describes one stream of the merge.
     *
     * @param demuxer the demuxer of the stream, or {@code null}
     * @param offsetMicros the time added to its timestamps
     * @param media the rendition that names it, or {@code null}
     * @param resources the sources it reads
     * @return the stream
     */
    private static StreamMerge.Stream stream(Demuxer demuxer, long offsetMicros, HlsPlaylist.Media media,
                                             List<AutoCloseable> resources) {
        if (media == null) {
            return new StreamMerge.Stream(demuxer, offsetMicros, null, null, Set.of(), resources);
        }
        var attributes = media.attributes();
        var language = attributes.get("LANGUAGE");
        var dispositions = new HashSet<TrackDisposition>();
        if ("YES".equals(attributes.get("DEFAULT"))) {
            dispositions.add(TrackDisposition.DEFAULT);
        }
        if ("YES".equals(attributes.get("FORCED"))) {
            dispositions.add(TrackDisposition.FORCED);
        }
        var characteristics = attributes.getOrDefault("CHARACTERISTICS", "");
        if (characteristics.contains("public.accessibility.transcribes-spoken-dialog")) {
            dispositions.add(TrackDisposition.HEARING_IMPAIRED);
        }
        if (characteristics.contains("public.accessibility.describes-video")) {
            dispositions.add(TrackDisposition.VISUAL_IMPAIRED);
        }
        return new StreamMerge.Stream(demuxer, offsetMicros, language == null ? null
                : Languages.bibliographicCode(language), attributes.get("NAME"), dispositions, resources);
    }

    /**
     * Closes a stream after a failure, keeping the failure.
     *
     * @param demuxer the demuxer of the stream, or {@code null}
     * @param resources the sources it reads
     */
    private static void closeQuietly(Demuxer demuxer, List<AutoCloseable> resources) {
        try {
            if (demuxer != null) {
                demuxer.close();
            }
        } catch (RuntimeException ignored) {
            // the failure being reported matters more
        }
        for (var resource : resources) {
            try {
                resource.close();
            } catch (Exception ignored) {
                // the failure being reported matters more
            }
        }
    }

    /**
     * Decodes playlist text: UTF-8, a byte order mark removed, line breaks normalized.
     *
     * @param bytes the playlist bytes
     * @return the text
     */
    private static String text(byte[] bytes) {
        var text = TextFiles.normalizeLineBreaks(new String(bytes, StandardCharsets.UTF_8));
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.HLS;
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
     * <p>The duration is the longest stream's, or the longest sum of a media playlist's segment durations.
     */
    @Override
    public long durationMicros() {
        var measured = merge.durationMicros();
        return measured >= 0 ? measured : playlistDuration;
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
