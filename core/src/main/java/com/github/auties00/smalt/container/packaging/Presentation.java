package com.github.auties00.smalt.container.packaging;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.Segment;
import com.github.auties00.smalt.container.internal.Languages;
import com.github.auties00.smalt.container.mp4.CommonEncryption;
import com.github.auties00.smalt.container.mp4.FragmentedMp4Muxer;
import com.github.auties00.smalt.container.mp4.FragmentedMp4MuxerOptions;
import com.github.auties00.smalt.container.ts.TsMuxer;
import com.github.auties00.smalt.container.ts.TsMuxerOptions;
import com.github.auties00.smalt.internal.tags.TagCarry;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.subtitle.SubtitleDecoder;
import com.github.auties00.smalt.subtitle.SubtitleEncoder;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackDisposition;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Packages the renditions of one presentation from their packets: it creates the muxers of every stream, routes
 * each packet to the muxers of its rendition, cuts the WebVTT subtitle segments at the reference stream's segment
 * boundaries, and writes the playlists and the manifest, after every segment for an event or live presentation and
 * once at the end.
 *
 * <p>Packets are written per rendition in decoding order; across renditions they may come in any order, although
 * subtitle segments are written only once the audio and video of every rendition have passed their end, so a cue
 * written after the audio and video it overlaps may miss its segment. Instances are not thread-safe.
 */
final class Presentation implements AutoCloseable {
    /**
     * The minimum buffer time declared in DASH manifests, in microseconds.
     */
    static final long MIN_BUFFER_MICROS = 2_000_000;

    /**
     * The 90 kHz timestamp of media time zero in transport stream segments: the multiplexing delay of the muxer
     * options.
     */
    static final long TS_ZERO = TsMuxerOptions.defaults().delayMicros() * 9 / 100;

    /**
     * Enumerates the kinds of streams written.
     */
    enum Role {
        /**
         * Transport stream segments of a variant: its video and, without audio renditions, its audio.
         */
        MUXED,

        /**
         * The CMAF video track of a variant.
         */
        VIDEO,

        /**
         * Audio: the CMAF audio track of a variant or an audio rendition, or the transport stream segments of an
         * audio rendition.
         */
        AUDIO,

        /**
         * The WebVTT segments of a subtitle rendition, for HLS.
         */
        SUBTITLES,

        /**
         * The CMAF text track of a subtitle rendition, for DASH.
         */
        TEXT,

        /**
         * The CMAF key frames of a variant's video, for DASH trick play.
         */
        TRICK
    }

    /**
     * One rendition, with the tracks chosen for it.
     */
    static final class Member {
        /**
         * The position of the rendition in the presentation.
         */
        final int index;

        /**
         * The rendition name.
         */
        final String name;

        /**
         * The rendition.
         */
        final Rendition rendition;

        /**
         * The video track of a variant, or {@code null}.
         */
        final VideoTrack video;

        /**
         * The audio track packaged, or {@code null}.
         */
        final AudioTrack audio;

        /**
         * The text subtitle track of a subtitle rendition, or {@code null}.
         */
        final Track text;

        /**
         * The tags of the rendition, or {@code null} when the options do not use them.
         */
        final MediaTags tags;

        /**
         * The dispositions of the audio or text track.
         */
        final Set<TrackDisposition> dispositions;

        /**
         * Where the packets of each track go, by track identifier.
         */
        final Map<Integer, List<Route>> routes = new HashMap<>();

        /**
         * Constructs a member.
         *
         * @param index the position of the rendition
         * @param name the rendition name
         * @param rendition the rendition as the caller gave it
         * @param video the video track, or {@code null}
         * @param audio the audio track, or {@code null}
         * @param text the text subtitle track, or {@code null}
         * @param tags the tags, or {@code null}
         * @param dispositions the dispositions of the audio or text track
         */
        Member(int index, String name, Rendition rendition, VideoTrack video, AudioTrack audio, Track text,
               MediaTags tags, Set<TrackDisposition> dispositions) {
            this.index = index;
            this.name = name;
            this.rendition = rendition;
            this.video = video;
            this.audio = audio;
            this.text = text;
            this.tags = tags;
            this.dispositions = Set.copyOf(dispositions);
        }

        /**
         * {@return the kind of the rendition}
         */
        Rendition.Kind kind() {
            return rendition.kind();
        }

        /**
         * {@return the language tag the manifests declare, or {@code null}}
         */
        String language() {
            if (rendition.language().isPresent()) {
                return rendition.language().get();
            }
            var track = text != null ? text : audio;
            return track == null ? null : track.language().filter(code -> !code.equals("und"))
                    .map(Languages::languageTag).orElse(null);
        }

        /**
         * {@return the name players show}
         */
        String label() {
            return rendition.label().orElse(name);
        }
    }

    /**
     * One stream written: its files and segments, and what writes them.
     */
    static final class Output {
        /**
         * The files and segments.
         */
        final SegmentedStream stream;

        /**
         * The kind of stream.
         */
        final Role role;

        /**
         * The rendition it comes from.
         */
        final Member member;

        /**
         * The muxer, or {@code null} for WebVTT segments.
         */
        Muxer muxer;

        /**
         * The WebVTT segmenter, or {@code null}.
         */
        WebVttSegments subtitles;

        /**
         * The stream a trick-play stream takes its key frames from, or {@code null}.
         */
        Output main;

        /**
         * Whether an HLS I-frame playlist describes the stream.
         */
        boolean iFrames;

        /**
         * The audio group the stream belongs to, or {@code null}.
         */
        String group;

        /**
         * The single-file muxer of a stream indexed by one segment index, or {@code null}.
         */
        FragmentedMp4Muxer indexed;

        /**
         * The number of video packets written: every frame of a video stream, the key frames of a trick-play
         * stream.
         */
        long samples;

        /**
         * Constructs an output.
         *
         * @param stream the files and segments
         * @param role the kind of stream
         * @param member the rendition it comes from
         */
        Output(SegmentedStream stream, Role role, Member member) {
            this.stream = stream;
            this.role = role;
            this.member = member;
        }

        /**
         * {@return the HLS media playlist name}
         */
        String playlistName() {
            return stream.name + ".m3u8";
        }

        /**
         * {@return the HLS I-frame playlist name}
         */
        String iFramePlaylistName() {
            return stream.name + "_iframes.m3u8";
        }

        /**
         * {@return whether the stream carries video}
         */
        boolean hasVideo() {
            return stream.video() != null;
        }
    }

    /**
     * Receives the packets of one track.
     */
    @FunctionalInterface
    interface Route {
        /**
         * Receives one packet.
         *
         * @param packet the packet, carrying the track's identifier in its rendition
         */
        void write(Packet packet);
    }

    /**
     * The renditions, in order.
     */
    final List<Member> members;

    /**
     * The streams, in rendition order.
     */
    final List<Output> outputs = new ArrayList<>();

    /**
     * The packaging options.
     */
    final PackagingOptions options;

    /**
     * The destination of the files.
     */
    private final PackageOutput output;

    /**
     * The names of the files written, in the order they were first completed.
     */
    final List<String> files = new ArrayList<>();

    /**
     * The actions that write what the subtitle converters hold back, run before the muxers finish.
     */
    private final List<Runnable> drains = new ArrayList<>();

    /**
     * The encryption, or {@code null} for clear segments.
     */
    final Keys keys;

    /**
     * Whether the presentation has audio renditions.
     */
    final boolean alternateAudio;

    /**
     * The stream whose segments set the boundaries of the WebVTT segments: the first with video, or else the
     * first audio stream.
     */
    private Output reference;

    /**
     * The latest decoding time written for each audio and video track, in microseconds, by clock slot.
     */
    private long[] latest = new long[0];

    /**
     * The clock slot of each audio and video track, by rendition index and track identifier.
     */
    private final Map<Long, Integer> slots = new HashMap<>();

    /**
     * The availability start of a dynamic DASH manifest and the time of HLS program dates.
     */
    final Instant start;

    /**
     * The HLS protocol version of every playlist.
     */
    final int version;

    /**
     * Whether the master playlist has been written during an event or live presentation.
     */
    private boolean masterWritten;

    /**
     * Whether the presentation has ended.
     */
    boolean ended;

    /**
     * Whether the muxers were finished or closed.
     */
    private boolean released;

    /**
     * The latest publish time of the dynamic DASH manifest.
     */
    Instant published;

    /**
     * Constructs the presentation, creating the muxers of every stream.
     *
     * @param members the renditions, with their tracks
     * @param output the destination of the files
     * @param options the packaging options
     * @throws MediaException.Unsupported if a muxer rejects a track
     */
    Presentation(List<Member> members, PackageOutput output, PackagingOptions options) {
        this.members = List.copyOf(members);
        this.output = output;
        this.options = options;
        this.keys = Keys.of(options);
        this.alternateAudio = members.stream().anyMatch(member -> member.kind() == Rendition.Kind.AUDIO);
        this.start = options.programDateTime().orElseGet(() -> Instant.now().truncatedTo(ChronoUnit.MILLIS));
        var cmaf = options.segmentFormat() == PackagingOptions.SegmentFormat.CMAF;
        var version = Manifests.hlsVersion(cmaf, options.byteRanges());
        if (keys != null) {
            version = Math.max(version, keys.hlsVersion());
        }
        if (options.trickPlay() && options.hlsEnabled()) {
            version = Math.max(version, cmaf ? 4 : 5);
        }
        this.version = version;
        try {
            writeKeyFile();
            for (var member : this.members) {
                plan(member);
            }
            checkNames();
            for (var candidate : outputs) {
                if (candidate.role == Role.SUBTITLES || candidate.role == Role.TEXT || candidate.role == Role.TRICK) {
                    continue;
                }
                if (reference == null || candidate.hasVideo() && !reference.hasVideo()) {
                    reference = candidate;
                }
            }
            for (var candidate : outputs) {
                candidate.stream.onSegment(stream -> segmentDone(candidate));
            }
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }
    }

    /**
     * Writes the key file of the encryption, when it names one.
     */
    private void writeKeyFile() {
        if (keys == null || keys.settings.keyFileName().isEmpty()) {
            return;
        }
        var keyFile = keys.settings.keyFileName().get();
        var sink = output.open(keyFile);
        try {
            sink.write(ByteBuffer.wrap(keys.settings.key()));
        } finally {
            sink.close();
        }
        files.add(keyFile);
    }

    /**
     * Creates the streams of a rendition and routes its tracks to them.
     *
     * @param member the rendition
     */
    private void plan(Member member) {
        var ts = options.segmentFormat() == PackagingOptions.SegmentFormat.MPEG_TS;
        switch (member.kind()) {
            case VARIANT -> {
                Output videoOutput;
                var iFrames = member.video != null && options.trickPlay() && options.hlsEnabled();
                if (ts) {
                    var tracks = new ArrayList<Track>();
                    if (member.video != null) {
                        tracks.add(member.video);
                    }
                    if (member.audio != null) {
                        tracks.add(member.audio);
                    }
                    var muxed = tsOutput(member, member.name, Role.MUXED, tracks, iFrames);
                    videoOutput = member.video != null ? muxed : null;
                } else {
                    videoOutput = member.video != null ? cmafOutput(member, member.name + "_video", Role.VIDEO,
                            member.video, iFrames) : null;
                    if (member.audio != null) {
                        cmafOutput(member, member.name + "_audio", Role.AUDIO, member.audio, false);
                    }
                }
                if (videoOutput != null && options.trickPlay() && options.dashEnabled()) {
                    var trick = cmafOutput(member, member.name + "_trick", Role.TRICK, member.video, false);
                    trick.main = videoOutput;
                }
            }
            case AUDIO -> {
                if (ts) {
                    tsOutput(member, member.name, Role.AUDIO, List.of(member.audio), false);
                } else {
                    cmafOutput(member, member.name + "_audio", Role.AUDIO, member.audio, false);
                }
            }
            case SUBTITLES -> {
                if (options.hlsEnabled()) {
                    var stream = new SegmentedStream(member.name, member.name, List.of(member.text), ".vtt", false,
                            output, files);
                    var subtitles = new Output(stream, Role.SUBTITLES, member);
                    subtitles.subtitles = new WebVttSegments(stream, member.text, ts ? TS_ZERO : 0);
                    outputs.add(subtitles);
                    route(member, member.text, packet -> subtitles.subtitles.add(packet));
                }
                if (options.dashEnabled()) {
                    textOutput(member);
                }
            }
        }
    }

    /**
     * Creates a transport stream output and routes its tracks to it.
     *
     * @param member the rendition
     * @param name the stream name
     * @param role the kind of stream
     * @param tracks the tracks, video first
     * @param iFrames whether an I-frame playlist describes the stream
     * @return the output
     */
    private Output tsOutput(Member member, String name, Role role, List<Track> tracks, boolean iFrames) {
        var stream = new SegmentedStream(name, member.name, tracks, ".ts", options.byteRanges(), output, files);
        var target = new Output(stream, role, member);
        outputs.add(target);
        if (iFrames) {
            target.iFrames = true;
            stream.scanKeyFrames(SegmentedStream.Scan.TRANSPORT_STREAM);
        }
        var builder = TsMuxerOptions.builder().segmentMicros(options.segmentMicros()).timestampOriginMicros(0);
        if (keys != null && keys.settings.method() == PackagingEncryption.Method.SAMPLE_AES) {
            builder.sampleAes(keys.settings.key(), keys.iv);
        } else if (keys != null) {
            stream.encryptSegments(keys.settings.key(), keys.iv);
        }
        var muxer = new TsMuxer(segment -> stream.openSegment(segment.sequenceNumber(), segment.startMicros(),
                segment.durationMicros(), segment.independent()), builder.build());
        target.muxer = muxer;
        for (var track : tracks) {
            declare(muxer, track, member.name);
        }
        setTags(muxer, member, tracks);
        for (var track : tracks) {
            route(member, track, packet -> {
                if (track instanceof VideoTrack) {
                    target.samples++;
                }
                if (track instanceof VideoTrack && packet.keyframe()) {
                    if (stream.keyFrame == null) {
                        stream.keyFrame = packet.data();
                    }
                    if (target.iFrames) {
                        stream.pendingKeyTimes.add(track.timeBase().toMicros(presentationTime(packet)));
                    }
                }
                writeTo(muxer, packet);
            });
        }
        return target;
    }

    /**
     * Creates a CMAF output of one track and routes the track to it.
     *
     * @param member the rendition
     * @param name the stream name
     * @param role the kind of stream: video, audio or trick play
     * @param track the track the output carries
     * @param iFrames whether an I-frame playlist describes the stream
     * @return the output
     */
    private Output cmafOutput(Member member, String name, Role role, Track track, boolean iFrames) {
        var declared = role == Role.AUDIO ? TagCarry.withoutEmbeddedTags(withLanguage(track, member)) : track;
        var stream = new SegmentedStream(name, member.name, List.of(declared), ".m4s", options.byteRanges(), output,
                files);
        var target = new Output(stream, role, member);
        outputs.add(target);
        if (iFrames) {
            target.iFrames = true;
            stream.scanKeyFrames(SegmentedStream.Scan.FRAGMENTS);
        }
        if (keys != null && keys.settings.method() == PackagingEncryption.Method.AES_128) {
            stream.encryptSegments(keys.settings.key(), keys.iv);
        }
        var muxer = cmafMuxer(target, fragmentOptions(keys != null && keys.settings.method()
                != PackagingEncryption.Method.AES_128));
        declare(muxer, declared, member.name);
        setTags(muxer, member, List.of(declared));
        if (role == Role.TRICK) {
            route(member, track, packet -> {
                if (!packet.keyframe()) {
                    return;
                }
                target.samples++;
                if (stream.keyFrame == null) {
                    stream.keyFrame = packet.data();
                }
                var time = presentationTime(packet);
                writeTo(muxer, packet.withTiming(time, time, Packet.UNKNOWN_DURATION));
            });
        } else {
            route(member, track, packet -> {
                if (track instanceof VideoTrack) {
                    target.samples++;
                }
                if (stream.keyFrame == null && track instanceof VideoTrack && packet.keyframe()) {
                    stream.keyFrame = packet.data();
                }
                writeTo(muxer, packet);
            });
        }
        return target;
    }

    /**
     * Creates the CMAF text output of a subtitle rendition: WebVTT ({@code wvtt}) cues, converted from other text
     * subtitle codecs, or TTML documents ({@code stpp}).
     *
     * @param member the subtitle rendition
     */
    private void textOutput(Member member) {
        var source = member.text;
        var passThrough = source.codec() == Codec.WEBVTT || source.codec() == Codec.TTML;
        Track declared = passThrough ? source : SubtitleEncoder.track(Codec.WEBVTT, source.id());
        declared = withLanguage(declared, member);
        if (declared.language().isEmpty() && source.language().isPresent() && !passThrough) {
            declared = ((DataTrack) declared).toBuilder().language(source.language().get()).build();
        }
        var stream = new SegmentedStream(member.name + "_text", member.name, List.of(declared), ".m4s",
                options.byteRanges(), output, files);
        var target = new Output(stream, Role.TEXT, member);
        outputs.add(target);
        var muxer = cmafMuxer(target, fragmentOptions(false));
        declare(muxer, declared, member.name);
        setTags(muxer, member, List.of(declared));
        if (passThrough) {
            route(member, source, packet -> {
                if (stream.keyFrame == null) {
                    stream.keyFrame = packet.data();
                }
                writeTo(muxer, packet);
            });
            return;
        }
        var decoder = new SubtitleDecoder(source);
        var encoder = new SubtitleEncoder(declared);
        route(member, source, packet -> {
            for (var cue : decoder.decodeAll(packet, SubtitleMarkup.WEBVTT)) {
                writeTo(muxer, encoder.encode(cue, SubtitleMarkup.WEBVTT));
            }
        });
        drains.add(() -> {
            for (var cue : decoder.flush(SubtitleMarkup.WEBVTT)) {
                writeTo(muxer, encoder.encode(cue, SubtitleMarkup.WEBVTT));
            }
        });
    }

    /**
     * Returns a track with the language its rendition declares.
     *
     * @param track the track the output carries
     * @param member the rendition
     * @return the track, with the rendition's language as an ISO 639-2 code when it declares one
     */
    private static Track withLanguage(Track track, Member member) {
        if (member.rendition.language().isEmpty()) {
            return track;
        }
        var code = Languages.bibliographicCode(member.rendition.language().get());
        return switch (track) {
            case VideoTrack video -> video.toBuilder().language(code).build();
            case AudioTrack audio -> audio.toBuilder().language(code).build();
            case DataTrack data -> data.toBuilder().language(code).build();
        };
    }

    /**
     * Returns the fragment options of CMAF streams.
     *
     * @param encrypted whether the samples are encrypted with Common Encryption
     * @return the options
     */
    private FragmentedMp4MuxerOptions fragmentOptions(boolean encrypted) {
        var builder = (options.segmentIndex() ? FragmentedMp4MuxerOptions.dashOnDemand()
                : FragmentedMp4MuxerOptions.cmaf()).toBuilder().fragmentMicros(options.segmentMicros());
        if (encrypted) {
            builder.encryption(keys.commonEncryption());
        }
        return builder.build();
    }

    /**
     * Creates the CMAF muxer of an output: a segmented muxer, or a single-file muxer whose layout is recorded at
     * the end when the options ask for a single segment index.
     *
     * @param target the output
     * @param fragmentOptions the fragment options
     * @return the muxer
     */
    private FragmentedMp4Muxer cmafMuxer(Output target, FragmentedMp4MuxerOptions fragmentOptions) {
        var stream = target.stream;
        FragmentedMp4Muxer muxer;
        if (fragmentOptions.segmentIndex() == FragmentedMp4MuxerOptions.SegmentIndex.SINGLE) {
            muxer = new FragmentedMp4Muxer(stream.openIndexed(), fragmentOptions);
            target.indexed = muxer;
        } else {
            muxer = new FragmentedMp4Muxer(segment -> segment.kind() == Segment.Kind.INIT ? stream.openInit()
                    : stream.openSegment(segment.sequenceNumber(), segment.startMicros(), segment.durationMicros(),
                    segment.independent()), fragmentOptions);
        }
        target.muxer = muxer;
        return muxer;
    }

    /**
     * Adds a route of a track, giving an audio or video track a clock slot.
     *
     * @param member the rendition
     * @param track the track whose packets the route receives
     * @param route the receiver of the packets
     */
    private void route(Member member, Track track, Route route) {
        member.routes.computeIfAbsent(track.id(), ignored -> new ArrayList<>()).add(route);
        if (track.kind() != TrackKind.DATA) {
            var key = (long) member.index << 32 | (track.id() & 0xFFFFFFFFL);
            if (!slots.containsKey(key)) {
                slots.put(key, slots.size());
                latest = new long[slots.size()];
                Arrays.fill(latest, Long.MIN_VALUE);
            }
        }
    }

    /**
     * Rejects two streams or playlists with one name.
     *
     * @throws IllegalArgumentException if two names collide
     */
    private void checkNames() {
        var names = new HashSet<String>();
        for (var candidate : outputs) {
            for (var name : List.of(candidate.stream.name + "#", candidate.playlistName(),
                    candidate.iFramePlaylistName())) {
                if (!names.add(name)) {
                    throw new IllegalArgumentException("two streams of the presentation are named " + name
                            + "; rename a rendition");
                }
            }
        }
        for (var name : List.of(options.masterPlaylistName(), options.manifestName())) {
            if (names.contains(name)) {
                throw new IllegalArgumentException("a stream playlist is named as the manifest " + name);
            }
        }
    }

    /**
     * Writes a packet of a rendition to the streams of its track.
     *
     * @param member the rendition index
     * @param packet the packet, carrying the track identifier of the rendition
     * @throws MediaException.Malformed if a muxer rejects the packet
     */
    void write(int member, Packet packet) {
        var routes = members.get(member).routes.get(packet.trackId());
        if (routes == null) {
            return;
        }
        for (var route : routes) {
            route.write(packet);
        }
        var slot = slots.get((long) member << 32 | (packet.trackId() & 0xFFFFFFFFL));
        if (slot != null) {
            var track = trackOf(members.get(member), packet.trackId());
            var time = packet.dts() != Packet.NO_TIMESTAMP ? packet.dts() : packet.pts();
            if (time != Packet.NO_TIMESTAMP && track != null) {
                latest[slot] = Math.max(latest[slot], track.timeBase().toMicros(time));
                flushSubtitles(clock());
            }
        }
    }

    /**
     * Returns a track of a rendition.
     *
     * @param member the rendition
     * @param id the track identifier
     * @return the track, or {@code null}
     */
    private static Track trackOf(Member member, int id) {
        for (var track : new Track[]{member.video, member.audio, member.text}) {
            if (track != null && track.id() == id) {
                return track;
            }
        }
        return null;
    }

    /**
     * {@return the time up to which every audio and video track has been written, or {@link Long#MIN_VALUE}}
     */
    private long clock() {
        var clock = Long.MAX_VALUE;
        for (var value : latest) {
            clock = Math.min(clock, value);
        }
        return latest.length == 0 ? Long.MIN_VALUE : clock;
    }

    /**
     * Writes the WebVTT segments the reference stream and the clock allow.
     *
     * @param clock the time up to which every cue has been written
     */
    private void flushSubtitles(long clock) {
        if (reference == null) {
            return;
        }
        for (var candidate : outputs) {
            if (candidate.subtitles != null) {
                candidate.subtitles.flush(reference.stream.segments, clock);
            }
        }
    }

    /**
     * Reacts to a media segment of an output: cuts subtitle segments and, for an event or live presentation,
     * rewrites the manifests.
     *
     * @param target the output that wrote the segment
     */
    private void segmentDone(Output target) {
        if (target == reference) {
            flushSubtitles(clock());
        }
        if (options.presentation() == PackagingOptions.Presentation.ON_DEMAND || ended) {
            return;
        }
        if (options.hlsEnabled()) {
            writePlaylists(target);
            if (!masterWritten && ready()) {
                masterWritten = true;
                writeFile(options.masterPlaylistName(), Manifests.masterPlaylist(this));
            }
        }
        if (options.dashEnabled() && ready()) {
            writeFile(options.manifestName(), Manifests.dashManifest(this));
        }
        if (options.removeExpiredSegments()) {
            var first = Manifests.windowStart(this, target.stream);
            target.stream.removeBefore(first - (target.stream.segments.size() - first));
        }
    }

    /**
     * {@return whether every stream has a segment, so that the manifests can describe them all}
     */
    private boolean ready() {
        for (var candidate : outputs) {
            if (candidate.stream.segments.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Writes the HLS media playlist of an output, and its I-frame playlist.
     *
     * @param target the output
     */
    private void writePlaylists(Output target) {
        if (target.role == Role.TEXT || target.role == Role.TRICK) {
            return;
        }
        writeFile(target.playlistName(), Manifests.mediaPlaylist(this, target));
        if (target.iFrames) {
            writeFile(target.iFramePlaylistName(), Manifests.iFramePlaylist(this, target));
        }
    }

    /**
     * Ends the presentation: finishes every muxer and writes the complete manifests.
     *
     * @return the description of what was written
     * @throws MediaException.Malformed if a muxer rejects what was written
     */
    PackagingResult finish() {
        released = true;
        for (var drain : drains) {
            drain.run();
        }
        for (var candidate : outputs) {
            if (candidate.muxer != null) {
                candidate.muxer.finish();
            }
        }
        for (var candidate : outputs) {
            if (candidate.indexed != null) {
                candidate.stream.indexed(candidate.indexed.indexLayout().orElseThrow());
            }
        }
        for (var candidate : outputs) {
            if (candidate.subtitles != null) {
                candidate.subtitles.drain();
                if (reference != null) {
                    candidate.subtitles.flush(reference.stream.segments, Long.MAX_VALUE);
                }
            }
        }
        closeStreams();
        ended = true;
        if (options.hlsEnabled()) {
            for (var candidate : outputs) {
                writePlaylists(candidate);
            }
            writeFile(options.masterPlaylistName(), Manifests.masterPlaylist(this));
        }
        if (options.dashEnabled()) {
            writeFile(options.manifestName(), Manifests.dashManifest(this));
        }
        var described = new ArrayList<PackagedStream>();
        for (var candidate : outputs) {
            var stream = candidate.stream;
            var codecs = stream.tracks.stream().map(Track::codec).toList();
            var kinds = stream.tracks.stream().map(Track::kind).toList();
            var format = switch (candidate.role) {
                case MUXED -> PackagedStream.Format.TRANSPORT_STREAM;
                case AUDIO -> options.segmentFormat() == PackagingOptions.SegmentFormat.MPEG_TS
                        ? PackagedStream.Format.TRANSPORT_STREAM : PackagedStream.Format.CMAF;
                case SUBTITLES -> PackagedStream.Format.WEBVTT;
                case VIDEO, TEXT, TRICK -> PackagedStream.Format.CMAF;
            };
            var playlist = options.hlsEnabled() && candidate.role != Role.TEXT && candidate.role != Role.TRICK
                    ? Optional.of(candidate.playlistName()) : Optional.<String>empty();
            var iFrames = candidate.iFrames ? Optional.of(candidate.iFramePlaylistName()) : Optional.<String>empty();
            described.add(new PackagedStream(stream.name, stream.rendition, candidate.member.kind(), format,
                    candidate.role == Role.TRICK, codecs, kinds,
                    candidate.role == Role.SUBTITLES ? "" : stream.codecString(), stream.segments.size(),
                    stream.longestSegment(), stream.durationMicros(), stream.peakBandwidth(),
                    stream.averageBandwidth(), playlist, iFrames));
        }
        return new PackagingResult(files, Optional.ofNullable(options.hlsEnabled() ? options.masterPlaylistName()
                : null), Optional.ofNullable(options.dashEnabled() ? options.manifestName() : null), durationMicros(),
                described);
    }

    /**
     * {@return the duration of the presentation: the end of its longest stream, in microseconds}
     */
    long durationMicros() {
        var duration = 0L;
        for (var candidate : outputs) {
            duration = Math.max(duration, candidate.stream.durationMicros());
        }
        return duration;
    }

    /**
     * Closes the single files of byte-range streams.
     */
    private void closeStreams() {
        for (var candidate : outputs) {
            candidate.stream.close();
        }
    }

    /**
     * Releases the muxers and files of a presentation that did not finish; the manifests are not written.
     */
    @Override
    public void close() {
        if (released) {
            return;
        }
        released = true;
        for (var candidate : outputs) {
            if (candidate.muxer != null) {
                try {
                    candidate.muxer.close();
                } catch (RuntimeException ignored) {
                    // closing after a failure must not hide it
                }
            }
        }
        closeStreams();
    }

    /**
     * Writes a text file, recording its name the first time.
     *
     * @param name the file name
     * @param text the content
     */
    private void writeFile(String name, String text) {
        var sink = output.open(name);
        try {
            sink.write(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
        } finally {
            sink.close();
        }
        if (!files.contains(name)) {
            files.add(name);
        }
    }

    /**
     * Writes a packet to a muxer, reporting a packet it rejects as malformed input.
     *
     * @param muxer the muxer of a stream
     * @param packet the packet to write
     * @throws MediaException.Malformed if the muxer rejects the packet
     */
    private static void writeTo(Muxer muxer, Packet packet) {
        try {
            muxer.writePacket(packet);
        } catch (IllegalArgumentException exception) {
            throw new MediaException.Malformed("a packet of track " + packet.trackId() + " cannot be packaged: "
                    + exception.getMessage(), exception);
        }
    }

    /**
     * {@return the presentation timestamp of a packet, or its decoding timestamp when it has none}
     *
     * @param packet the packet whose time is returned
     */
    private static long presentationTime(Packet packet) {
        return packet.pts() != Packet.NO_TIMESTAMP ? packet.pts() : packet.dts();
    }

    /**
     * Declares a track to a muxer, reporting a track the segment format cannot carry as unsupported.
     *
     * @param muxer the muxer of the stream
     * @param track the track to declare
     * @param rendition the rendition name, for the message
     * @throws MediaException.Unsupported if the muxer rejects the track, for example an AAC configuration ADTS
     * cannot express
     */
    private static void declare(Muxer muxer, Track track, String rendition) {
        try {
            muxer.addTrack(track);
        } catch (IllegalArgumentException exception) {
            throw new MediaException.Unsupported("packaging", "rendition " + rendition + ": " + exception.getMessage());
        }
    }

    /**
     * Gives a muxer the tags of the tracks it writes, as the options select.
     *
     * @param muxer the muxer, with its tracks declared
     * @param member the rendition
     * @param tracks the tracks the muxer writes, under their identifiers in the rendition
     */
    private void setTags(Muxer muxer, Member member, List<Track> tracks) {
        var ids = new HashMap<Integer, Integer>();
        for (var track : tracks) {
            ids.put(track.id(), track.id());
        }
        var given = options.tags().orElse(null);
        var input = member.tags == null ? MediaTags.empty() : member.tags;
        var resolved = TagCarry.resolve(options.tagMode(), given, input,
                new TagCarry.Mapping(ids, Set.of(), false, false, 0, -1));
        TagCarry.apply(muxer, resolved, options.tagMode(), given);
    }

    /**
     * The encryption of one packaging: the settings and the initialization vector its segments use.
     *
     * @param settings the encryption settings
     * @param iv the initialization vector: the one of every AES-128 segment or {@code null} for their media sequence
     * numbers, the SAMPLE-AES one of transport stream samples and the constant {@code cbcs} one of CMAF samples, or
     * the first {@code cenc} one, or {@code null} for random ones
     */
    record Keys(PackagingEncryption settings, byte[] iv) {
        /**
         * Returns the encryption of a packaging, drawing the initialization vector it needs.
         *
         * @param options the packaging options
         * @return the encryption, or {@code null} for clear segments
         */
        static Keys of(PackagingOptions options) {
            var settings = options.encryption().orElse(null);
            if (settings == null) {
                return null;
            }
            var iv = settings.initializationVector().orElse(null);
            if (iv == null && settings.method() == PackagingEncryption.Method.SAMPLE_AES) {
                iv = new byte[16];
                new SecureRandom().nextBytes(iv);
            }
            return new Keys(settings, iv);
        }

        /**
         * {@return the Common Encryption of CMAF segments}
         */
        CommonEncryption commonEncryption() {
            var builder = CommonEncryption.builder()
                    .scheme(settings.method() == PackagingEncryption.Method.SAMPLE_AES ? CommonEncryption.Scheme.CBCS
                            : CommonEncryption.Scheme.CENC)
                    .keyId(settings.keyId().orElseThrow()).key(settings.key());
            if (iv != null) {
                builder.initializationVector(iv);
            }
            return builder.build();
        }

        /**
         * {@return the scheme DASH manifests declare, or {@code null} when they declare none}
         */
        String dashScheme() {
            return switch (settings.method()) {
                case SAMPLE_AES -> "cbcs";
                case SAMPLE_AES_CTR -> "cenc";
                case AES_128 -> null;
            };
        }

        /**
         * {@return the lowest HLS protocol version the key tag needs (RFC 8216 clause 7)}
         */
        int hlsVersion() {
            if (settings.method() != PackagingEncryption.Method.AES_128 || settings.keyFormat().isPresent()) {
                return 5;
            }
            return iv != null ? 2 : 1;
        }

        /**
         * {@return the {@code EXT-X-KEY} tag of the media playlists}
         */
        String playlistTag() {
            var tag = new StringBuilder("#EXT-X-KEY:METHOD=").append(switch (settings.method()) {
                case AES_128 -> "AES-128";
                case SAMPLE_AES -> "SAMPLE-AES";
                case SAMPLE_AES_CTR -> "SAMPLE-AES-CTR";
            });
            tag.append(",URI=\"").append(settings.keyUri().orElseThrow()).append('"');
            if (iv != null && iv.length == 16 && settings.method() != PackagingEncryption.Method.SAMPLE_AES_CTR) {
                tag.append(",IV=0x").append(HexFormat.of().formatHex(iv));
            }
            settings.keyFormat().ifPresent(format -> tag.append(",KEYFORMAT=\"").append(format).append('"'));
            return tag.toString();
        }
    }
}
