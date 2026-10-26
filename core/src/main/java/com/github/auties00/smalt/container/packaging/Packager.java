package com.github.auties00.smalt.container.packaging;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.mp4.FragmentedMp4Muxer;
import com.github.auties00.smalt.container.ts.TsMuxer;
import com.github.auties00.smalt.internal.tags.TagCarry;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.TagMode;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackDisposition;
import com.github.auties00.smalt.track.VideoTrack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * Packages media files for adaptive streaming: HLS (RFC 8216) and DASH (ISO/IEC 23009-1) presentations, on demand,
 * as events or live, without re-encoding the audio and video.
 *
 * <p>A presentation is a list of {@link Rendition}s: variants (each an encode of the content; feed several encodes
 * at different sizes and bit rates for an adaptive ladder, for example the outputs of
 * {@link com.github.auties00.smalt.transcode.Transcoder}), alternate audio renditions (languages, commentaries) and
 * subtitle renditions. A variant packages its first video track and, unless the presentation has audio renditions,
 * its first audio track; an audio or subtitle rendition packages one track. Segments are cut at key frames of the
 * video track (at any audio frame for audio alone) once they last {@link PackagingOptions#segmentMicros()}, so
 * encodes with closed GOPs whose key frames align produce switchable variants. Several renditions may read one
 * source, which is then demuxed once.
 *
 * <p>Files written, side by side through the {@link PackageOutput} (with {@code r} a rendition name):
 * <ul>
 *   <li>transport stream segments: {@code r_00001.ts}, ... with a variant's audio and video muxed, or an audio
 *       rendition's audio, and the media playlist {@code r.m3u8};</li>
 *   <li>CMAF segments: per track, {@code r_video_init.mp4}, {@code r_audio_init.mp4} or {@code r_text_init.mp4}
 *       and {@code r_video_00001.m4s}, ..., with the media playlists {@code r_video.m3u8} and {@code r_audio.m3u8}
 *       when HLS is written; the text track of a subtitle rendition and the key frames of a trick-play stream
 *       ({@code r_trick_init.mp4}, ...) are written for DASH only;</li>
 *   <li>the WebVTT segments of a subtitle rendition, {@code r_00001.vtt}, ... with the playlist {@code r.m3u8},
 *       for HLS: each spans one segment of the first video stream (or the first audio stream), holds every cue
 *       shown during it, cues of other text codecs converted to WebVTT, and maps the cue times to the media
 *       timestamps with an {@code X-TIMESTAMP-MAP} (RFC 8216 clause 3.5); cues that start after the last segment
 *       are kept in it;</li>
 *   <li>the I-frame playlists of trick play, {@code r_iframes.m3u8} or {@code r_video_iframes.m3u8};</li>
 *   <li>with byte-range addressing, one file per audio or video stream instead ({@code r.ts},
 *       {@code r_video.mp4}, {@code r_audio.mp4}) whose segments the manifests address by byte range;</li>
 *   <li>the HLS master playlist and the DASH manifest
 *       ({@link PackagingOptions#masterPlaylistName()}, {@link PackagingOptions#manifestName()}).</li>
 * </ul>
 * The master playlist lists each variant with its peak and average bandwidth, {@code CODECS}, resolution and frame
 * rate. Without audio renditions, CMAF audio becomes an audio group of its own variant; with them, the audio
 * renditions form one group per codec that every variant references, a variant listed once per group, and an
 * audio-only presentation lists one variant per group. Subtitle renditions form the group every variant names in
 * {@code SUBTITLES}. Languages, labels, the default and forced flags of each {@link Rendition}, and the accessibility
 * dispositions of its track, become the {@code EXT-X-MEDIA} attributes and the DASH {@code lang}, {@code Label},
 * {@code Role} and {@code Accessibility} of its adaptation set. The DASH manifest has one adaptation set per kind,
 * codec, language and role, and a {@code SegmentTemplate} with a {@code SegmentTimeline} per representation (a
 * {@code SegmentList} with media ranges under byte-range addressing, and a {@code BaseURL} with a
 * {@code SegmentBase} in the on-demand profile when {@link PackagingOptions#segmentIndex()} puts a single
 * {@code sidx} in each stream's file). {@link PackagingOptions#presentation()} chooses between video on demand, an
 * event and a live sliding window, {@link PackagingOptions#trickPlay()} adds trick play,
 * {@link PackagingOptions#encryption()} encrypts the audio and video segments (subtitles stay clear); a key file is
 * written only when {@link PackagingEncryption#keyFileName()} names one. The rendition's tags, cover art, chapters
 * and track tags go into the segments as {@link PackagingOptions#tagMode()} selects.
 *
 * <p>The packager is stateless; every method may be called from any thread, each call reading its own sources.
 *
 * @apiNote Packaging copies the coded streams; transcode first to change codecs, sizes or key frame spacing.
 * Transport stream segments take H.264, HEVC, AAC, MP3, MP2, AC-3, E-AC-3 and Opus; CMAF segments take the audio
 * and video codecs {@link FragmentedMp4Muxer#supports} lists, although HLS players support only some of them in
 * CMAF (Apple players: H.264, HEVC, AAC, AC-3, E-AC-3, FLAC, ALAC, Opus). Subtitle renditions take the text
 * subtitle codecs (SubRip, WebVTT, 3GPP timed text, ASS and TTML) from any container or subtitle file.
 */
public final class Packager {
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
     * Packages renditions read from media files through an output.
     *
     * <p>Each distinct source is demuxed once, and the sources side by side, their packets taken in decoding order,
     * so that the segments of all renditions progress together and an event or live presentation rewrites its
     * manifests as a live source would.
     *
     * @param renditions the renditions, at least one variant or audio rendition, with distinct names; each read
     * from a media file
     * @param output opens the files written
     * @param options the packaging options
     * @return the description of what was written
     * @throws IllegalArgumentException if there is no variant or audio rendition, two renditions share a name, two
     * audio or two subtitle renditions share a label, a rendition declares its tracks instead of reading a file, or
     * two streams of the presentation would have the same file names
     * @throws MediaException.Unsupported if a rendition has no track of its kind that the segment format carries,
     * a variant has no video track next to audio renditions, or a track's configuration cannot be expressed in the
     * segment format
     * @throws MediaException.Malformed if an input is corrupt
     * @throws MediaException.Io if reading or writing fails
     */
    public static PackagingResult pack(List<Rendition> renditions, PackageOutput output, PackagingOptions options) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        var names = names(renditions);
        for (var index = 0; index < renditions.size(); index++) {
            if (renditions.get(index).source().isEmpty()) {
                throw new IllegalArgumentException("rendition " + names.get(index)
                        + " declares its tracks: write its packets through Packager.open");
            }
        }
        var sources = new IdentityHashMap<MediaSource, Source>();
        var ordered = new ArrayList<Source>();
        try {
            var alternateAudio = hasAlternateAudio(renditions);
            var members = new ArrayList<Presentation.Member>();
            for (var index = 0; index < renditions.size(); index++) {
                var rendition = renditions.get(index);
                var mediaSource = rendition.source().orElseThrow();
                var source = sources.get(mediaSource);
                if (source == null) {
                    var demuxer = Containers.openDemuxer(mediaSource);
                    source = new Source(demuxer);
                    sources.put(mediaSource, source);
                    ordered.add(source);
                    var mode = options.tagMode();
                    source.tags = mode == TagMode.COPY || mode == TagMode.MERGE
                            ? TagCarry.read(mediaSource, demuxer, MediaLimits.DEFAULT) : null;
                }
                var demuxer = source.demuxer;
                members.add(resolve(index, names.get(index), rendition, demuxer.tracks(), demuxer::trackDisposition,
                        source.tags, options, alternateAudio));
                source.members.add(index);
            }
            try (var presentation = new Presentation(members, output, options)) {
                pump(ordered, presentation);
                return presentation.finish();
            }
        } finally {
            for (var source : ordered) {
                source.demuxer.close();
            }
        }
    }

    /**
     * Opens a presentation whose packets the application writes, for example the output of real-time encoders,
     * to be packaged as {@link PackagingOptions#presentation()} describes.
     *
     * @param renditions the renditions, at least one variant or audio rendition, with distinct names; each
     * declaring its tracks ({@link Rendition#builder(List)})
     * @param output opens the files written
     * @param options the packaging options
     * @return the writer, which the caller finishes and closes
     * @throws IllegalArgumentException if there is no variant or audio rendition, two renditions share a name, two
     * audio or two subtitle renditions share a label, a rendition reads a file, or two streams of the presentation
     * would have the same file names
     * @throws MediaException.Unsupported if a rendition has no track of its kind that the segment format carries,
     * a variant has no video track next to audio renditions, or a track's configuration cannot be expressed in the
     * segment format
     * @throws MediaException.Io if writing the key file fails
     */
    public static PackageWriter open(List<Rendition> renditions, PackageOutput output, PackagingOptions options) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        var names = names(renditions);
        var alternateAudio = hasAlternateAudio(renditions);
        var members = new ArrayList<Presentation.Member>();
        for (var index = 0; index < renditions.size(); index++) {
            var rendition = renditions.get(index);
            if (rendition.source().isPresent()) {
                throw new IllegalArgumentException("rendition " + names.get(index)
                        + " reads a file: package it with Packager.pack");
            }
            members.add(resolve(index, names.get(index), rendition, rendition.tracks(), id -> Set.of(), null, options,
                    alternateAudio));
        }
        return new PackageWriter(new Presentation(members, output, options), names);
    }

    /**
     * Names the renditions and checks the presentation they make.
     *
     * @param renditions the renditions as the caller gave them
     * @return the name of each rendition, in order
     * @throws IllegalArgumentException if there is no variant or audio rendition, or two renditions share a name,
     * or two audio or two subtitle renditions share a label
     */
    private static List<String> names(List<Rendition> renditions) {
        Objects.requireNonNull(renditions, "renditions");
        if (renditions.isEmpty()) {
            throw new IllegalArgumentException("no rendition to package");
        }
        var names = new ArrayList<String>();
        var unique = new HashSet<String>();
        var labels = new HashMap<Rendition.Kind, Set<String>>();
        var playable = false;
        for (var index = 0; index < renditions.size(); index++) {
            var rendition = Objects.requireNonNull(renditions.get(index), "rendition");
            var name = rendition.name().orElse("r" + index);
            if (!unique.add(name)) {
                throw new IllegalArgumentException("two renditions are named " + name);
            }
            names.add(name);
            playable |= rendition.kind() != Rendition.Kind.SUBTITLES;
            if (rendition.kind() != Rendition.Kind.VARIANT) {
                var label = rendition.label().orElse(name);
                if (!labels.computeIfAbsent(rendition.kind(), ignored -> new HashSet<>()).add(label)) {
                    throw new IllegalArgumentException("two " + rendition.kind().name().toLowerCase(Locale.ROOT)
                            + " renditions are labeled " + label);
                }
            }
        }
        if (!playable) {
            throw new IllegalArgumentException("a presentation needs a variant or an audio rendition");
        }
        return names;
    }

    /**
     * {@return whether a presentation has audio renditions}
     *
     * @param renditions the renditions as the caller gave them
     */
    private static boolean hasAlternateAudio(List<Rendition> renditions) {
        return renditions.stream().anyMatch(rendition -> rendition.kind() == Rendition.Kind.AUDIO);
    }

    /**
     * Chooses the tracks of a rendition.
     *
     * @param index the position of the rendition
     * @param name its name
     * @param rendition the rendition whose tracks are chosen
     * @param tracks the tracks of its file, or its declared tracks
     * @param dispositions the dispositions of a track, by identifier
     * @param tags the tags of its file, or {@code null}
     * @param options the packaging options
     * @param alternateAudio whether the presentation has audio renditions
     * @return the member of the presentation
     * @throws MediaException.Unsupported if the rendition has no track of its kind that the segment format carries,
     * or a variant has no video track next to audio renditions
     */
    private static Presentation.Member resolve(int index, String name, Rendition rendition, List<Track> tracks,
                                               IntFunction<Set<TrackDisposition>> dispositions, MediaTags tags,
                                               PackagingOptions options, boolean alternateAudio) {
        var ts = options.segmentFormat() == PackagingOptions.SegmentFormat.MPEG_TS;
        var format = ts ? "a transport stream" : "a CMAF segment";
        switch (rendition.kind()) {
            case VARIANT -> {
                VideoTrack video = null;
                AudioTrack audio = null;
                for (var track : tracks) {
                    if (!carries(ts, track.codec())) {
                        continue;
                    }
                    if (video == null && track instanceof VideoTrack candidate) {
                        video = candidate;
                    } else if (audio == null && track instanceof AudioTrack candidate) {
                        audio = candidate;
                    }
                }
                if (video == null && audio == null) {
                    throw new MediaException.Unsupported("packaging", "rendition " + name
                            + " has no video or audio track " + format + " carries");
                }
                if (alternateAudio) {
                    if (video == null) {
                        throw new MediaException.Unsupported("packaging", "rendition " + name + " has no video track "
                                + format + " carries; next to audio renditions, a variant carries video only");
                    }
                    audio = null;
                }
                return new Presentation.Member(index, name, rendition, video, audio, null, tags, Set.of());
            }
            case AUDIO -> {
                var track = select(rendition, tracks, name, "audio");
                if (!(track instanceof AudioTrack audio) || !carries(ts, track.codec())) {
                    throw new MediaException.Unsupported("packaging", "rendition " + name + " has no audio track "
                            + format + " carries");
                }
                return new Presentation.Member(index, name, rendition, null, audio, null, tags,
                        dispositions.apply(track.id()));
            }
            case SUBTITLES -> {
                var track = select(rendition, tracks, name, "text subtitle");
                if (track == null || !isText(track.codec())) {
                    throw new MediaException.Unsupported("packaging", "rendition " + name
                            + " has no text subtitle track");
                }
                return new Presentation.Member(index, name, rendition, null, null, track, tags,
                        dispositions.apply(track.id()));
            }
            default -> throw new IllegalStateException(rendition.kind().name());
        }
    }

    /**
     * Returns the track an audio or subtitle rendition packages.
     *
     * @param rendition the audio or subtitle rendition
     * @param tracks the tracks to choose from
     * @param name the rendition name, for the message
     * @param kind the kind of track, for the message
     * @return the track of the rendition's identifier, or else the first audio track of an audio rendition and
     * the first text subtitle track of a subtitle rendition, or {@code null}
     * @throws MediaException.Unsupported if the rendition names a track that does not exist
     */
    private static Track select(Rendition rendition, List<Track> tracks, String name, String kind) {
        if (rendition.trackId().isPresent()) {
            var id = rendition.trackId().getAsInt();
            for (var track : tracks) {
                if (track.id() == id) {
                    return track;
                }
            }
            throw new MediaException.Unsupported("packaging", "rendition " + name + " has no track " + id);
        }
        for (var track : tracks) {
            if (rendition.kind() == Rendition.Kind.AUDIO ? track instanceof AudioTrack : isText(track.codec())) {
                return track;
            }
        }
        return null;
    }

    /**
     * {@return whether a codec is a text subtitle codec a subtitle rendition packages}
     *
     * @param codec the codec to look up
     */
    private static boolean isText(Codec codec) {
        return codec.isSubtitle() || codec == Codec.TTML;
    }

    /**
     * Returns whether the segment format carries a codec.
     *
     * @param ts whether segments are transport streams
     * @param codec the codec to look up
     * @return {@code true} when the segment muxer accepts it
     */
    private static boolean carries(boolean ts, Codec codec) {
        return ts ? TsMuxer.supports(codec) : FragmentedMp4Muxer.supports(codec);
    }

    /**
     * Writes the packets of every source to the presentation, in decoding order across the sources.
     *
     * @param sources the sources, each with the renditions that read it
     * @param presentation the presentation the packets go to
     */
    private static void pump(List<Source> sources, Presentation presentation) {
        for (var source : sources) {
            source.advance();
        }
        while (true) {
            Source earliest = null;
            for (var source : sources) {
                if (source.next != null && (earliest == null || source.time < earliest.time)) {
                    earliest = source;
                }
            }
            if (earliest == null) {
                return;
            }
            var packet = earliest.next;
            for (var member : earliest.members) {
                presentation.write(member, packet);
            }
            earliest.advance();
        }
    }

    /**
     * One demuxed source and the renditions that read it.
     */
    private static final class Source {
        /**
         * The demuxer.
         */
        final Demuxer demuxer;

        /**
         * The time base of each track, by identifier.
         */
        private final Map<Integer, Rational> timeBases = new HashMap<>();

        /**
         * The indices of the renditions that read the source.
         */
        final List<Integer> members = new ArrayList<>();

        /**
         * The tags of the source, or {@code null}.
         */
        MediaTags tags;

        /**
         * The next packet, or {@code null} at the end.
         */
        Packet next;

        /**
         * The decoding time of the next packet, in microseconds.
         */
        long time = Long.MIN_VALUE;

        /**
         * Constructs a source.
         *
         * @param demuxer the demuxer of the source
         */
        Source(Demuxer demuxer) {
            this.demuxer = demuxer;
            for (var track : demuxer.tracks()) {
                timeBases.put(track.id(), track.timeBase());
            }
        }

        /**
         * Reads the next packet; a packet without a timestamp keeps the time of the previous one.
         */
        void advance() {
            next = demuxer.read();
            if (next == null) {
                return;
            }
            var value = next.dts() != Packet.NO_TIMESTAMP ? next.dts() : next.pts();
            var timeBase = timeBases.get(next.trackId());
            if (value != Packet.NO_TIMESTAMP && timeBase != null) {
                time = timeBase.toMicros(value);
            }
        }
    }
}
