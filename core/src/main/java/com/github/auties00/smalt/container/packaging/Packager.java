package com.github.auties00.smalt.container.packaging;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.Segment;
import com.github.auties00.smalt.container.mp4.FragmentedMp4Muxer;
import com.github.auties00.smalt.container.mp4.FragmentedMp4MuxerOptions;
import com.github.auties00.smalt.container.ts.TsMuxer;
import com.github.auties00.smalt.container.ts.TsMuxerOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Packages media files for adaptive streaming: HLS (RFC 8216) and DASH (ISO/IEC 23009-1) video
 * on demand, without re-encoding.
 *
 * <p>Each {@link Rendition} is one encode of the content (feed several encodes at different
 * sizes and bit rates for an adaptive ladder, for example the outputs of
 * {@link com.github.auties00.smalt.transcode.Transcoder}); its first video track and first audio
 * track are packaged, other tracks are ignored. Segments are cut at key frames of the video track
 * (at any audio frame for an audio-only rendition) once they last
 * {@link PackagingOptions#segmentMicros()}, so encodes with closed GOPs whose key frames align
 * produce switchable renditions.
 *
 * <p>Files written, side by side through the {@link PackageOutput} (with {@code r} a rendition
 * name):
 * <ul>
 *   <li>transport stream segments: {@code r_00001.ts}, ... with the rendition's audio and video
 *       muxed, and the media playlist {@code r.m3u8};</li>
 *   <li>CMAF segments: per track, {@code r_video_init.mp4} or {@code r_audio_init.mp4} and
 *       {@code r_video_00001.m4s}, ..., with the media playlists {@code r_video.m3u8} and
 *       {@code r_audio.m3u8} when HLS is written;</li>
 *   <li>with byte-range addressing, one file per stream instead ({@code r.ts},
 *       {@code r_video.mp4}, {@code r_audio.mp4}) whose segments the manifests address by byte
 *       range;</li>
 *   <li>the HLS master playlist and the DASH manifest
 *       ({@link PackagingOptions#masterPlaylistName()}, {@link PackagingOptions#manifestName()}).</li>
 * </ul>
 * The master playlist lists one variant per rendition with its peak and average bandwidth,
 * {@code CODECS}, resolution and frame rate; CMAF audio becomes an audio rendition group of its
 * variant. The DASH manifest is static, with one adaptation set per kind, codec and language and
 * a {@code SegmentTemplate} with a {@code SegmentTimeline} per representation (a
 * {@code SegmentList} with media ranges under byte-range addressing, and a {@code BaseURL} with a
 * {@code SegmentBase} in the on-demand profile when {@link PackagingOptions#segmentIndex()} puts a
 * single {@code sidx} in each stream's file). Nothing is encrypted.
 *
 * <p>The packager is stateless; every method may be called from any thread, each call reading its
 * own sources.
 *
 * @apiNote Packaging copies the coded streams; transcode first to change codecs, sizes or key
 * frame spacing. Transport stream segments take H.264, HEVC, AAC, MP3, MP2, AC-3, E-AC-3 and
 * Opus; CMAF segments take H.264, HEVC, VP9, AV1, AAC, Opus, MP3, FLAC and ALAC, although HLS
 * players support only some of them in CMAF (Apple players: H.264, HEVC, AAC, AC-3, E-AC-3,
 * FLAC, ALAC, Opus).
 */
public final class Packager {
    /**
     * The minimum buffer time declared in DASH manifests, in microseconds.
     */
    private static final long MIN_BUFFER_MICROS = 2_000_000;

    private Packager() {
        throw new AssertionError();
    }

    /**
     * Packages one media file into a directory.
     *
     * @param input the media file
     * @param directory the output directory, created when needed
     * @param options the packaging options
     * @return the description of what was written
     * @throws MediaException.Unsupported if the file has no video or audio track the segment format
     * carries
     * @throws MediaException.Malformed if the file is corrupt
     * @throws MediaException.Io if reading or writing fails
     */
    public static PackagingResult pack(Path input, Path directory, PackagingOptions options) {
        Objects.requireNonNull(input, "input");
        try (var source = MediaSource.of(input)) {
            return pack(List.of(Rendition.of(source)), PackageOutput.directory(directory), options);
        }
    }

    /**
     * Packages renditions through an output.
     *
     * @param renditions the renditions, at least one, with distinct names
     * @param output opens the files written
     * @param options the packaging options
     * @return the description of what was written
     * @throws IllegalArgumentException if there is no rendition, or two share a name
     * @throws MediaException.Unsupported if a rendition has no video or audio track the segment
     * format carries, or its configuration cannot be expressed in it
     * @throws MediaException.Malformed if an input is corrupt
     * @throws MediaException.Io if reading or writing fails
     */
    public static PackagingResult pack(List<Rendition> renditions, PackageOutput output, PackagingOptions options) {
        Objects.requireNonNull(renditions, "renditions");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        if (renditions.isEmpty()) {
            throw new IllegalArgumentException("no rendition to package");
        }
        var names = new ArrayList<String>();
        var unique = new HashSet<String>();
        for (var index = 0; index < renditions.size(); index++) {
            var rendition = Objects.requireNonNull(renditions.get(index), "rendition");
            var name = rendition.name().orElse("r" + index);
            if (!unique.add(name)) {
                throw new IllegalArgumentException("two renditions are named " + name);
            }
            names.add(name);
        }
        var files = new ArrayList<String>();
        var grouped = new ArrayList<Manifests.RenditionStreams>();
        var all = new ArrayList<SegmentedStream>();
        for (var index = 0; index < renditions.size(); index++) {
            var streams = packRendition(renditions.get(index), names.get(index), output, options, files);
            grouped.add(streams);
            for (var stream : new SegmentedStream[]{streams.video(), streams.audio(), streams.muxed()}) {
                if (stream != null) {
                    all.add(stream);
                }
            }
        }
        var duration = 0L;
        for (var stream : all) {
            duration = Math.max(duration, stream.durationMicros());
        }
        var cmaf = options.segmentFormat() == PackagingOptions.SegmentFormat.CMAF;
        String master = null;
        if (options.hlsEnabled()) {
            var version = Manifests.hlsVersion(cmaf, options.byteRanges());
            for (var stream : all) {
                write(output, Manifests.playlistName(stream), Manifests.mediaPlaylist(stream, version), files);
            }
            master = options.masterPlaylistName();
            write(output, master, Manifests.masterPlaylist(grouped, version), files);
        }
        String manifest = null;
        if (options.dashEnabled()) {
            manifest = options.manifestName();
            write(output, manifest, Manifests.dashManifest(all, duration, MIN_BUFFER_MICROS), files);
        }
        var described = new ArrayList<PackagedStream>();
        for (var stream : all) {
            var codecs = stream.tracks.stream().map(Track::codec).toList();
            var kinds = stream.tracks.stream().map(Track::kind).toList();
            described.add(new PackagedStream(stream.name, stream.rendition, codecs, kinds, stream.codecString(),
                    stream.segments.size(), stream.longestSegment(), stream.durationMicros(), stream.peakBandwidth(),
                    stream.averageBandwidth(),
                    options.hlsEnabled() ? Optional.of(Manifests.playlistName(stream)) : Optional.empty()));
        }
        return new PackagingResult(files, Optional.ofNullable(master), Optional.ofNullable(manifest), duration, described);
    }

    /**
     * Segments one rendition.
     *
     * @param rendition the rendition to segment
     * @param name its name
     * @param output the destination of the segments
     * @param options the packaging options
     * @param files receives the names of the files written
     * @return the streams written
     */
    private static Manifests.RenditionStreams packRendition(Rendition rendition, String name, PackageOutput output,
                                                            PackagingOptions options, List<String> files) {
        try (var demuxer = Containers.openDemuxer(rendition.source())) {
            var ts = options.segmentFormat() == PackagingOptions.SegmentFormat.MPEG_TS;
            VideoTrack video = null;
            AudioTrack audio = null;
            for (var track : demuxer.tracks()) {
                var carried = ts ? TsMuxer.supports(track.codec()) : FragmentedMp4Muxer.supports(track.codec());
                if (!carried) {
                    continue;
                }
                if (video == null && track instanceof VideoTrack candidate) {
                    video = candidate;
                } else if (audio == null && track instanceof AudioTrack candidate) {
                    audio = candidate;
                }
            }
            if (video == null && audio == null) {
                throw new MediaException.Unsupported("packaging", "rendition " + name + " has no video or audio track "
                        + (ts ? "a transport stream" : "a CMAF segment") + " carries");
            }
            return ts ? packTs(demuxer, name, video, audio, output, options, files)
                    : packCmaf(demuxer, name, video, audio, output, options, files);
        }
    }

    /**
     * Segments a rendition into transport stream segments.
     *
     * @param demuxer the demuxer of the rendition
     * @param name the rendition name
     * @param video the video track, or {@code null}
     * @param audio the audio track, or {@code null}
     * @param output the destination of the segments
     * @param options the packaging options
     * @param files receives the names of the files written
     * @return the streams written
     */
    private static Manifests.RenditionStreams packTs(Demuxer demuxer, String name, VideoTrack video, AudioTrack audio,
                                                     PackageOutput output, PackagingOptions options, List<String> files) {
        var tracks = new ArrayList<Track>();
        if (video != null) {
            tracks.add(video);
        }
        if (audio != null) {
            tracks.add(audio);
        }
        var stream = new SegmentedStream(name, name, tracks, ".ts", options.byteRanges(), output, files);
        var muxerOptions = TsMuxerOptions.builder().segmentMicros(options.segmentMicros()).build();
        try (var muxer = new TsMuxer(segment -> stream.openSegment(segment.sequenceNumber(), segment.startMicros(),
                segment.durationMicros(), segment.independent()), muxerOptions)) {
            for (var track : tracks) {
                declare(muxer, track, name);
            }
            copy(demuxer, Map.of(trackKey(video), muxer, trackKey(audio), muxer), video, stream);
            muxer.finish();
        } finally {
            stream.close();
        }
        return new Manifests.RenditionStreams(null, null, stream);
    }

    /**
     * Segments a rendition into CMAF tracks.
     *
     * @param demuxer the demuxer of the rendition
     * @param name the rendition name
     * @param video the video track, or {@code null}
     * @param audio the audio track, or {@code null}
     * @param output the destination of the segments
     * @param options the packaging options
     * @param files receives the names of the files written
     * @return the streams written
     */
    private static Manifests.RenditionStreams packCmaf(Demuxer demuxer, String name, VideoTrack video, AudioTrack audio,
                                                       PackageOutput output, PackagingOptions options, List<String> files) {
        var fragmentOptions = (options.segmentIndex() ? FragmentedMp4MuxerOptions.dashOnDemand()
                : FragmentedMp4MuxerOptions.cmaf()).toBuilder().fragmentMicros(options.segmentMicros()).build();
        SegmentedStream videoStream = null;
        SegmentedStream audioStream = null;
        var muxers = new HashMap<Integer, Muxer>();
        var opened = new ArrayList<Muxer>();
        var indexed = new HashMap<FragmentedMp4Muxer, SegmentedStream>();
        try {
            if (video != null) {
                var stream = new SegmentedStream(name + "_video", name, List.of(video), ".m4s", options.byteRanges(), output, files);
                var muxer = cmafMuxer(stream, fragmentOptions, indexed);
                declare(muxer, video, name);
                muxers.put(video.id(), muxer);
                opened.add(muxer);
                videoStream = stream;
            }
            if (audio != null) {
                var stream = new SegmentedStream(name + "_audio", name, List.of(audio), ".m4s", options.byteRanges(), output, files);
                var muxer = cmafMuxer(stream, fragmentOptions, indexed);
                declare(muxer, audio, name);
                muxers.put(audio.id(), muxer);
                opened.add(muxer);
                audioStream = stream;
            }
            copy(demuxer, muxers, video, videoStream);
            for (var muxer : opened) {
                muxer.finish();
            }
            for (var entry : indexed.entrySet()) {
                entry.getValue().indexed(entry.getKey().indexLayout().orElseThrow());
            }
        } finally {
            for (var muxer : opened) {
                muxer.close();
            }
            if (videoStream != null) {
                videoStream.close();
            }
            if (audioStream != null) {
                audioStream.close();
            }
        }
        return new Manifests.RenditionStreams(videoStream, audioStream, null);
    }

    /**
     * Creates the CMAF muxer of a stream: a segmented muxer, or a single-file muxer whose layout
     * is recorded when the options ask for a single segment index.
     *
     * @param stream the segmented stream the muxer writes
     * @param options the fragment options
     * @param indexed the single-file muxers and their streams, completed with this muxer when
     * it writes a single segment index
     * @return the muxer
     */
    private static FragmentedMp4Muxer cmafMuxer(SegmentedStream stream, FragmentedMp4MuxerOptions options,
                                                Map<FragmentedMp4Muxer, SegmentedStream> indexed) {
        if (options.segmentIndex() == FragmentedMp4MuxerOptions.SegmentIndex.SINGLE) {
            var muxer = new FragmentedMp4Muxer(stream.openIndexed(), options);
            indexed.put(muxer, stream);
            return muxer;
        }
        return new FragmentedMp4Muxer(segment -> segment.kind() == Segment.Kind.INIT
                ? stream.openInit()
                : stream.openSegment(segment.sequenceNumber(), segment.startMicros(), segment.durationMicros(),
                segment.independent()), options);
    }

    /**
     * Returns the identifier of a track, or a value no track has.
     *
     * @param track the track, or {@code null}
     * @return the identifier, or {@link Integer#MIN_VALUE}
     */
    private static int trackKey(Track track) {
        return track == null ? Integer.MIN_VALUE : track.id();
    }

    /**
     * Copies the packets of the packaged tracks to their muxers.
     *
     * @param demuxer the demuxer of the rendition
     * @param muxers the muxer of each packaged track, by track identifier
     * @param video the video track, or {@code null}
     * @param stream the stream that keeps the video track's first key frame, or {@code null}
     */
    private static void copy(Demuxer demuxer, Map<Integer, Muxer> muxers, VideoTrack video, SegmentedStream stream) {
        Packet packet;
        while ((packet = demuxer.read()) != null) {
            var muxer = muxers.get(packet.trackId());
            if (muxer == null) {
                continue;
            }
            if (video != null && stream != null && stream.keyFrame == null && packet.trackId() == video.id()
                    && packet.keyframe()) {
                stream.keyFrame = packet.data();
            }
            try {
                muxer.writePacket(packet);
            } catch (IllegalArgumentException exception) {
                throw new MediaException.Malformed("a packet of track " + packet.trackId() + " cannot be packaged: "
                        + exception.getMessage(), exception);
            }
        }
    }

    /**
     * Declares a track to a muxer, reporting a track the segment format cannot carry as
     * unsupported.
     *
     * @param muxer the muxer of the stream
     * @param track the track to declare
     * @param rendition the rendition name, for the message
     * @throws MediaException.Unsupported if the muxer rejects the track, for example an AAC
     * configuration ADTS cannot express
     */
    private static void declare(Muxer muxer, Track track, String rendition) {
        try {
            muxer.addTrack(track);
        } catch (IllegalArgumentException exception) {
            throw new MediaException.Unsupported("packaging", "rendition " + rendition + ": " + exception.getMessage());
        }
    }

    /**
     * Writes a text file.
     *
     * @param output the destination of the file
     * @param name the file name
     * @param text the content
     * @param files receives the file name
     */
    private static void write(PackageOutput output, String name, String text, List<String> files) {
        var sink = output.open(name);
        try {
            sink.write(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
        } finally {
            sink.close();
        }
        files.add(name);
    }
}
