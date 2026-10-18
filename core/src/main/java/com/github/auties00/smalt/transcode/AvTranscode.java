package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.AudioStreamDecoder;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.container.mp4.Mp4Remuxer;
import com.github.auties00.smalt.container.mp4.RemuxOptions;
import com.github.auties00.smalt.container.mp4.RemuxResult;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.internal.provider.Artifacts;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.VideoFrame;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.LongConsumer;
import java.util.function.Predicate;

/**
 * Runs one audio/video transcode: plans a stage per selected stream, then pumps the input through
 * the stages into the output muxer.
 *
 * <p>Planning happens before anything is written. Each selected stream is resolved to a
 * {@link CopyStage} or an encoding stage following its {@link StreamMode}; every codec the plan
 * needs is checked against the registries, so a missing artifact fails here with a
 * {@link MediaException.Unsupported} naming it. When the plan copies every stream into an MP4 or
 * M4A file without trimming, and the input is an MP4 or Matroska file, the job is handed to
 * {@link Mp4Remuxer}, which plans the file layout from the input's index and repairs Matroska
 * timing. Otherwise one demuxer feeds every stage in file order, so memory stays bounded by the
 * codecs' own buffers, one group of pictures per copied video stream and the packets that arrive
 * before the first video frame is decoded. Text subtitle streams get a {@link SubtitleStage},
 * which copies or converts them; an audio stream with a loudness normalization is measured by a
 * first pass over a second demuxer before the job starts.
 *
 * <p>An instance runs once and is not thread-safe.
 */
final class AvTranscode {
    /**
     * The shortest display time of an animation frame; shorter stored times play at
     * {@value #ANIMATION_DEFAULT_FRAME_MICROS} microseconds, as browsers render them.
     */
    private static final long ANIMATION_MIN_FRAME_MICROS = 10_000;

    /**
     * The display time given to animation frames stored with a shorter one.
     */
    private static final long ANIMATION_DEFAULT_FRAME_MICROS = 100_000;

    /**
     * How far before the start of the range the demuxer seeks, in microseconds, so that copied
     * audio keeps a packet of decoder pre-roll and decoders converge before the first kept sample.
     *
     * @implNote This implementation uses 100 ms, which covers the 80 ms Opus pre-roll of RFC 7845
     * section 4.6 and the one-frame overlap of AAC and MP3.
     */
    private static final long SEEK_PRE_ROLL_MICROS = 100_000;

    /**
     * How much earlier than the media the demuxer seeks when subtitle streams are kept, in
     * microseconds, so that the cues still shown at the start of the range, which are stored at
     * the time they appear, are read too.
     *
     * @implNote This implementation uses 10 s, longer than nearly every subtitle cue; the media
     * stages drop what precedes their own start, so seeking earlier only costs reading time.
     */
    private static final long SUBTITLE_PRE_ROLL_MICROS = 10_000_000;

    /**
     * The smallest media advance between two progress reports, in microseconds.
     */
    private static final long PROGRESS_STEP_MICROS = 50_000;

    private final MediaSource input;

    private final MediaSink output;

    private final TranscodeOptions options;

    private final Cancellation cancellation;

    /**
     * The start of the kept range in microseconds.
     */
    private final long start;

    /**
     * The end of the kept range in microseconds, or {@code -1} for the end of the input.
     */
    private final long end;

    /**
     * The last position reported to the listener in microseconds, or {@code -1} before the first
     * report.
     */
    private long reported = -1;

    /**
     * The expected output duration in microseconds, or {@code -1} when unknown.
     */
    private long expected = -1;

    /**
     * Constructs a run.
     *
     * @param input the input to transcode; not closed
     * @param output the sink the output is written to; not closed
     * @param options the job description
     * @param cancellation the cancellation of the job
     */
    AvTranscode(MediaSource input, MediaSink output, TranscodeOptions options, Cancellation cancellation) {
        this.input = input;
        this.output = output;
        this.options = options;
        this.cancellation = cancellation;
        this.start = options.startMicros();
        this.end = options.effectiveEndMicros();
    }

    /**
     * Runs the transcode.
     *
     * @return what was written
     * @throws IllegalArgumentException if the options select a track the input lacks or ask for
     * something the container cannot carry
     * @throws MediaException.Unsupported if the input is not recognized or a needed codec or
     * container feature is missing
     */
    TranscodeResult run() {
        cancellation.check();
        var format = Containers.detect(input).orElse(null);
        if (format == null) {
            var image = Images.detect(input).orElse(null);
            if (image != null && Images.isDecodable(image) && Images.probe(input).animated()) {
                return runAnimation();
            }
            throw new MediaException.Unsupported("unknown container", "the input is not a recognized media container"
                    + (image != null ? " but a still " + image.name().toLowerCase(Locale.ROOT) + " image" : ""));
        }
        try (var demuxer = Containers.openDemuxer(input, DemuxerOptions.builder().limits(options.limits()).build())) {
            return runDemuxed(format, demuxer);
        }
    }

    /**
     * Plans and runs a transcode of a demuxed input.
     *
     * @param format the input container
     * @param demuxer the open demuxer of the input
     * @return what was written
     */
    private TranscodeResult runDemuxed(ContainerFormat format, Demuxer demuxer) {
        var container = options.container();
        var tracks = demuxer.tracks();
        var video = selectVideo(tracks);
        var audio = selectAudio(tracks);
        var subtitles = planSubtitles(tracks);
        if (video == null && audio.isEmpty() && subtitles.isEmpty()) {
            throw new IllegalArgumentException("the options select no stream of the input");
        }
        var videoPlan = video == null ? null : planVideo(video);
        var audioPlans = new ArrayList<AudioPlan>();
        for (var track : audio) {
            audioPlans.add(planAudio(track));
        }
        expected = end >= 0 ? end - start : demuxer.durationMicros() >= 0 ? Math.max(0, demuxer.durationMicros() - start) : -1;
        if (start == 0 && end < 0 && subtitles.isEmpty() && (container == OutputContainer.MP4 || container == OutputContainer.M4A)
                && (format == ContainerFormat.MP4 || format == ContainerFormat.MATROSKA) && audioPlans.size() <= 1
                && (videoPlan == null || videoPlan.copy() && remuxable(videoPlan.track().codec()))
                && audioPlans.stream().allMatch(plan -> plan.copy() && remuxable(plan.track().codec()))) {
            return remux(video, audio.isEmpty() ? null : audio.getFirst());
        }
        var capacity = (videoPlan == null ? 0 : 1) + audioPlans.size() + subtitles.size();
        var stages = new ArrayList<Stage>();
        try (var muxer = new OutputMuxer(() -> container.createMuxer(output), capacity)) {
            if (videoPlan != null) {
                stages.add(createVideoStage(videoPlan, muxer.reserve(), muxer));
            }
            for (var plan : audioPlans) {
                stages.add(createAudioStage(plan, muxer.reserve(), muxer));
            }
            for (var plan : subtitles) {
                stages.add(new SubtitleStage(plan.track(), plan.codec(), plan.convert(), muxer.reserve(), muxer, start, end));
            }
            normalizeLoudness(stages);
            if (start > 0 && options.seeking() && demuxer.isSeekable()) {
                seek(demuxer, stages);
            }
            var byTrack = new HashMap<Integer, Stage>();
            for (var stage : stages) {
                byTrack.put(stage.sourceTrackId(), stage);
            }
            while (!allDone(stages)) {
                cancellation.check();
                var packet = demuxer.read();
                if (packet == null) {
                    break;
                }
                var stage = byTrack.get(packet.trackId());
                if (stage != null) {
                    stage.accept(packet);
                    report(stages, false);
                }
            }
            for (var stage : stages) {
                cancellation.check();
                stage.finish();
            }
            muxer.finish();
            var results = new ArrayList<TranscodedStream>();
            var remuxed = true;
            for (var stage : stages) {
                results.add(stage.result());
                remuxed &= stage.result().copied();
            }
            var duration = report(stages, true);
            return new TranscodeResult(container, duration, results, remuxed);
        } finally {
            for (var stage : stages) {
                stage.close();
            }
        }
    }

    /**
     * Seeks the demuxer before the start of the kept range, far enough for every stage: copied
     * streams need {@value #SEEK_PRE_ROLL_MICROS} microseconds, and every encoded audio stream the
     * pre-roll and timing its decoder measures, so that it restarts sample-accurately. When the
     * demuxer turns out unable to seek, or an encoded audio stream's timestamps are not
     * sample-accurate ({@link AudioStreamDecoder.Timing#isExact()}), the demuxer is left at the
     * start and the stages decode and drop.
     *
     * @param demuxer the demuxer, at the start of the input
     * @param stages the stages, none of which received a packet
     */
    private void seek(Demuxer demuxer, List<Stage> stages) {
        var target = Math.max(0, start - SEEK_PRE_ROLL_MICROS);
        var timings = new ArrayList<AudioStreamDecoder.Timing>();
        var exact = true;
        try {
            for (var stage : stages) {
                if (stage instanceof AudioEncodeStage audio) {
                    var timing = audio.measure(demuxer);
                    timings.add(timing);
                    exact &= timing.isExact();
                    target = Math.min(target, timing.seekMicros(start));
                }
            }
            for (var stage : stages) {
                if (stage instanceof SubtitleStage) {
                    // TODO: a cue shown for longer than the pre-roll before the start is lost after the seek
                    target = Math.max(0, target - SUBTITLE_PRE_ROLL_MICROS);
                    break;
                }
            }
            demuxer.seek(target);
        } catch (MediaException.Unsupported unsupported) {
            // the first seek failed before any packet was read: decode from the start and drop instead
            return;
        }
        if (!exact) {
            // the target is before every packet: the stages decode from the start and drop
            return;
        }
        var index = 0;
        for (var stage : stages) {
            if (stage instanceof AudioEncodeStage audio) {
                audio.startAfterSeek(timings.get(index++));
            }
        }
    }

    /**
     * Runs the loudness pass of every encoded audio stream that normalizes its loudness, each over
     * its own demuxer of the input.
     *
     * @param stages the stages, none of which received a packet
     */
    private void normalizeLoudness(List<Stage> stages) {
        var normalization = options.audio().loudnessNormalization();
        if (normalization.isEmpty()) {
            return;
        }
        for (var stage : stages) {
            if (stage instanceof AudioEncodeStage audio) {
                try (var measuring = Containers.openDemuxer(input, DemuxerOptions.builder().limits(options.limits())
                        .build())) {
                    audio.normalize(measuring, normalization.get(), cancellation);
                }
            }
        }
    }

    /**
     * Describes how a text subtitle stream is processed.
     *
     * @param track the input track
     * @param codec the output codec
     * @param convert whether the stream is decoded and encoded rather than copied
     */
    private record SubtitlePlan(Track track, Codec codec, boolean convert) {
    }

    /**
     * Selects and plans the text subtitle streams.
     *
     * @param tracks the input tracks
     * @return the plans, empty when subtitles are dropped, absent, or (unless requested
     * explicitly) not carried by the container in any form
     * @throws IllegalArgumentException if a requested track is missing, is not a subtitle track,
     * or cannot be written
     * @throws MediaException.Unsupported if a stream must be copied but the container cannot carry
     * its codec
     */
    private List<SubtitlePlan> planSubtitles(List<Track> tracks) {
        var subtitles = options.subtitles();
        if (subtitles.mode() == StreamMode.DROP) {
            return List.of();
        }
        var selected = new ArrayList<Track>();
        if (subtitles.trackId().isPresent()) {
            var id = subtitles.trackId().getAsInt();
            var track = trackById(tracks, id);
            if (!track.codec().isSubtitle()) {
                throw new IllegalArgumentException("track " + id + " is not a text subtitle track");
            }
            selected.add(track);
        } else {
            for (var track : tracks) {
                if (track.codec().isSubtitle()) {
                    selected.add(track);
                    if (!subtitles.allTracks()) {
                        break;
                    }
                }
            }
        }
        var container = options.container();
        var explicit = subtitles.mode() != StreamMode.AUTO || subtitles.trackId().isPresent();
        var plans = new ArrayList<SubtitlePlan>();
        for (var track : selected) {
            var codec = track.codec();
            Codec target;
            if (subtitles.codec().isPresent()) {
                target = subtitles.codec().get();
                if (!container.supports(target)) {
                    throw new IllegalArgumentException(container + " cannot carry " + target.displayName());
                }
            } else if (container.supports(codec)) {
                target = codec;
            } else if (!container.subtitleCodecs().isEmpty()) {
                target = container.subtitleCodecs().getFirst();
            } else if (explicit) {
                throw new IllegalArgumentException(container + " files cannot carry subtitles");
            } else {
                continue;
            }
            if (subtitles.mode() == StreamMode.COPY && target != codec) {
                throw new MediaException.Unsupported(codec.displayName(), "cannot copy the " + codec.displayName()
                        + " subtitles into " + container + ": " + (container.supports(codec)
                        ? "the options ask for " + target.displayName() : container + " cannot carry " + codec.displayName()));
            }
            plans.add(new SubtitlePlan(track, target, subtitles.mode() == StreamMode.ENCODE));
            if (!container.carriesSeveralTracks()) {
                break;
            }
        }
        return plans;
    }

    /**
     * Returns the track with an identifier.
     *
     * @param tracks the input tracks
     * @param id the identifier the options select
     * @return the track
     * @throws IllegalArgumentException if no track has the identifier
     */
    private static Track trackById(List<Track> tracks, int id) {
        for (var track : tracks) {
            if (track.id() == id) {
                return track;
            }
        }
        throw new IllegalArgumentException("the input has no track " + id);
    }

    /**
     * {@return whether {@link Mp4Remuxer} copies a codec: H.264, HEVC, AAC, Opus, MP3, ALAC and
     * FLAC}
     *
     * @param codec a codec of the input
     */
    private static boolean remuxable(Codec codec) {
        return switch (codec) {
            case H264, HEVC, AAC, OPUS, MP3, ALAC, FLAC -> true;
            default -> false;
        };
    }

    /**
     * {@return whether every stage reached the end of the kept range, so that no later packet is
     * needed}
     *
     * @param stages the stages of the job
     */
    private static boolean allDone(List<Stage> stages) {
        var media = media(stages);
        for (var stage : stages) {
            if ((!media || !(stage instanceof SubtitleStage)) && !stage.done()) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@return whether a job has an audio or video stage, which then decides when the input is
     * read far enough: subtitle packets are interleaved with the media they belong to, but sparse}
     *
     * @param stages the stages of the job
     */
    private static boolean media(List<Stage> stages) {
        for (var stage : stages) {
            if (!(stage instanceof SubtitleStage)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reports progress to the listener when the output advanced enough, or always at the end.
     *
     * @param stages the stages of the job
     * @param last whether the output is complete
     * @return the position reported, in microseconds
     */
    private long report(List<Stage> stages, boolean last) {
        var position = Long.MAX_VALUE;
        var furthest = 0L;
        var media = media(stages);
        for (var stage : stages) {
            if (media && stage instanceof SubtitleStage) {
                continue;
            }
            furthest = Math.max(furthest, stage.positionMicros());
            if (!stage.done()) {
                position = Math.min(position, stage.positionMicros());
            }
        }
        if (last || position == Long.MAX_VALUE) {
            position = furthest;
        }
        return report(position, last);
    }

    /**
     * Reports a position to the listener when it advanced enough, or always at the end.
     *
     * @param position the output position in microseconds
     * @param last whether the output is complete
     * @return the position, never below the last one reported
     */
    private long report(long position, boolean last) {
        position = Math.max(position, reported);
        var listener = options.listener();
        if (listener.isPresent() && (last || position - reported >= PROGRESS_STEP_MICROS || reported < 0)) {
            reported = position;
            listener.get().onProgress(new TranscodeProgress(position, last ? position : expected));
        }
        return position;
    }

    /**
     * Selects the video track.
     *
     * @param tracks the input tracks
     * @return the track, or {@code null} when video is dropped, absent, or (unless requested
     * explicitly) not carried by the container
     * @throws IllegalArgumentException if a requested track is missing or cannot be written
     */
    private VideoTrack selectVideo(List<Track> tracks) {
        var video = options.video();
        if (video.mode() == StreamMode.DROP) {
            return null;
        }
        VideoTrack selected = null;
        if (video.trackId().isPresent()) {
            var id = video.trackId().getAsInt();
            var track = trackById(tracks, id);
            if (!(track instanceof VideoTrack videoTrack)) {
                throw new IllegalArgumentException("track " + id + " is not a video track");
            }
            selected = videoTrack;
        } else {
            for (var track : tracks) {
                if (track instanceof VideoTrack videoTrack) {
                    selected = videoTrack;
                    break;
                }
            }
        }
        if (selected == null) {
            return null;
        }
        var container = options.container();
        if (container.videoCodecs().isEmpty() && !container.supports(selected.codec())) {
            if (video.mode() == StreamMode.AUTO && video.trackId().isEmpty()) {
                return null;
            }
            throw new IllegalArgumentException(container + " files cannot carry video");
        }
        return selected;
    }

    /**
     * Selects the audio tracks.
     *
     * @param tracks the input tracks
     * @return the tracks, empty when audio is dropped, absent, or (unless requested explicitly) not
     * carried by the container
     * @throws IllegalArgumentException if a requested track is missing or cannot be written
     */
    private List<AudioTrack> selectAudio(List<Track> tracks) {
        var audio = options.audio();
        if (audio.mode() == StreamMode.DROP) {
            return List.of();
        }
        var selected = new ArrayList<AudioTrack>();
        if (audio.trackId().isPresent()) {
            var id = audio.trackId().getAsInt();
            var track = trackById(tracks, id);
            if (!(track instanceof AudioTrack audioTrack)) {
                throw new IllegalArgumentException("track " + id + " is not an audio track");
            }
            selected.add(audioTrack);
        } else {
            for (var track : tracks) {
                if (track instanceof AudioTrack audioTrack) {
                    selected.add(audioTrack);
                    if (!audio.allTracks()) {
                        break;
                    }
                }
            }
        }
        var container = options.container();
        if (!selected.isEmpty() && container.audioCodecs().isEmpty() && !container.supports(selected.getFirst().codec())) {
            if (audio.mode() == StreamMode.AUTO && audio.trackId().isEmpty()) {
                return List.of();
            }
            throw new IllegalArgumentException(container + " files cannot carry audio");
        }
        if (selected.size() > 1 && !container.carriesSeveralTracks()) {
            throw new IllegalArgumentException(container + " files carry one audio track");
        }
        return selected;
    }

    /**
     * Describes how the video stream is processed.
     *
     * @param track the input track
     * @param copy whether its packets are copied
     * @param codec the codec produced when encoding
     * @param applyOrientation whether the orientation is applied to the pixels
     * @param frameRate the constant output rate, or {@code null} to keep the source timing
     */
    private record VideoPlan(VideoTrack track, boolean copy, Codec codec, boolean applyOrientation, Rational frameRate) {
    }

    /**
     * Describes how an audio stream is processed.
     *
     * @param track the input track
     * @param copy whether its packets are copied
     * @param codec the codec produced when encoding
     */
    private record AudioPlan(AudioTrack track, boolean copy, Codec codec) {
    }

    /**
     * Plans the video stream.
     *
     * @param track the input track
     * @return the plan
     * @throws IllegalArgumentException if the requested codec cannot be written to the container
     * @throws MediaException.Unsupported if the stream must be copied but cannot be, or must be
     * encoded and no encoder or decoder is available
     */
    private VideoPlan planVideo(VideoTrack track) {
        var video = options.video();
        var container = options.container();
        var apply = switch (video.orientation()) {
            case PRESERVE -> false;
            case APPLY -> true;
            default -> !container.storesOrientation();
        };
        var frameRate = video.effectiveFrameRate(track.frameRate().orElse(null));
        var reason = copyObstacle(track, apply);
        var copy = switch (video.mode()) {
            case COPY -> {
                if (reason != null) {
                    throw new MediaException.Unsupported(track.codec().displayName(), "cannot copy the "
                            + track.codec().displayName() + " video into " + container + ": " + reason);
                }
                yield true;
            }
            case ENCODE -> false;
            default -> reason == null;
        };
        if (copy) {
            return new VideoPlan(track, true, track.codec(), false, null);
        }
        if (!VideoDecoders.isSupported(track.codec())) {
            throw Artifacts.unsupported(track.codec().displayName(), "decodable", Artifacts.decoderOf(track.codec()));
        }
        return new VideoPlan(track, false, pickEncoder(video.codec(), container.videoCodecs(), VideoEncoders::isSupported,
                "video"), apply, frameRate);
    }

    /**
     * Returns why the video stream cannot be copied.
     *
     * @param track the input track
     * @param apply whether the orientation is applied to the pixels
     * @return the reason, or {@code null} when it can be copied
     */
    private String copyObstacle(VideoTrack track, boolean apply) {
        var video = options.video();
        var container = options.container();
        var codec = track.codec();
        if (!container.supports(codec)) {
            return container + " cannot carry " + codec.displayName();
        }
        if (container.needsConfig(codec) && track.codecConfig().isEmpty()) {
            return "the track has no codec configuration";
        }
        if (video.codec().isPresent() && video.codec().get() != codec) {
            return "the options ask for " + video.codec().get().displayName();
        }
        if (apply && track.orientation() != Orientation.NORMAL) {
            return "the orientation must be applied to the pixels";
        }
        var swaps = track.orientation().swapsDimensions();
        var width = swaps ? track.height() : track.width();
        var height = swaps ? track.width() : track.height();
        if (width <= 0 || height <= 0) {
            return video.hasPictureOps() ? "the picture operations need decoding" : null;
        }
        if (video.changesPicture(width, height, track.frameRate().orElse(null))) {
            return "the picture operations need decoding";
        }
        return null;
    }

    /**
     * Resolves the codec an encoded stream gets: the requested one, or else the first codec the
     * container prefers that an encoder produces.
     *
     * @param requested the codec the options ask for, or empty to let the container choose
     * @param preferred the codecs of the stream's kind the container prefers, in order
     * @param encodable whether an encoder produces a codec
     * @param kind the stream kind, {@code "video"} or {@code "audio"}, for messages
     * @return the codec
     * @throws IllegalArgumentException if the requested codec cannot be written to the container,
     * or the container carries no stream of the kind
     * @throws MediaException.Unsupported if no suitable codec is encodable
     */
    private Codec pickEncoder(Optional<Codec> requested, List<Codec> preferred, Predicate<Codec> encodable,
                              String kind) {
        var container = options.container();
        if (requested.isPresent()) {
            var codec = requested.get();
            if (!container.supports(codec)) {
                throw new IllegalArgumentException(container + " cannot carry " + codec.displayName());
            }
            if (!encodable.test(codec)) {
                throw Artifacts.unsupported(codec.displayName(), "encodable", Artifacts.encoderOf(codec));
            }
            return codec;
        }
        for (var codec : preferred) {
            if (encodable.test(codec)) {
                return codec;
            }
        }
        if (preferred.isEmpty()) {
            throw new IllegalArgumentException(container + " files cannot carry " + kind);
        }
        var first = preferred.getFirst();
        throw Artifacts.unsupported(first.displayName(), "encodable", Artifacts.encoderOf(first));
    }

    /**
     * Plans an audio stream.
     *
     * @param track the input track
     * @return the plan
     * @throws IllegalArgumentException if the requested codec cannot be written to the container
     * @throws MediaException.Unsupported if the stream must be copied but cannot be, or must be
     * encoded and no encoder or decoder is available
     */
    private AudioPlan planAudio(AudioTrack track) {
        var audio = options.audio();
        var container = options.container();
        var codec = track.codec();
        String reason = null;
        if (!container.supports(codec)) {
            reason = container + " cannot carry " + codec.displayName();
        } else if (container.needsConfig(codec) && track.codecConfig().isEmpty()) {
            reason = "the track has no codec configuration";
        } else if (audio.codec().isPresent() && audio.codec().get() != codec) {
            reason = "the options ask for " + audio.codec().get().displayName();
        } else if (audio.changesSamples(track.sampleRate(), track.layout().orElse(null))) {
            reason = "the sample operations need decoding";
        }
        var copy = switch (audio.mode()) {
            case COPY -> {
                if (reason != null) {
                    throw new MediaException.Unsupported(codec.displayName(), "cannot copy the " + codec.displayName()
                            + " audio into " + container + ": " + reason);
                }
                yield true;
            }
            case ENCODE -> false;
            default -> reason == null;
        };
        if (copy) {
            return new AudioPlan(track, true, codec);
        }
        if (!AudioDecoders.isSupported(codec)) {
            throw Artifacts.unsupported(codec.displayName(), "decodable", Artifacts.decoderOf(codec));
        }
        return new AudioPlan(track, false, pickEncoder(audio.codec(), container.audioCodecs(), AudioEncoders::isSupported,
                "audio"));
    }

    /**
     * Creates the stage of the video stream.
     *
     * @param plan the plan of the video stream
     * @param outputId the output track identifier
     * @param muxer the output
     * @return the stage
     */
    private Stage createVideoStage(VideoPlan plan, int outputId, OutputMuxer muxer) {
        if (plan.copy()) {
            return new CopyStage(plan.track(), outputId, muxer, start, end);
        }
        var decoderOptions = VideoDecoderOptions.builder().limits(options.limits()).build();
        var decoder = VideoDecoders.create(plan.track(), decoderOptions);
        return new VideoEncodeStage(plan.track(), decoder, plan.codec(), options.video(), plan.applyOrientation(),
                plan.frameRate(), outputId, muxer, start, end, cancellation);
    }

    /**
     * Creates the stage of an audio stream.
     *
     * @param plan the plan of the audio stream
     * @param outputId the output track identifier
     * @param muxer the output
     * @return the stage
     */
    private Stage createAudioStage(AudioPlan plan, int outputId, OutputMuxer muxer) {
        if (plan.copy()) {
            return new CopyStage(plan.track(), outputId, muxer, start, end);
        }
        return new AudioEncodeStage(plan.track(), plan.codec(), options.audio(), outputId, muxer, options.limits(), start,
                end);
    }

    /**
     * Hands a whole-file copy into MP4 to {@link Mp4Remuxer}.
     *
     * @param video the video track copied, or {@code null} for none
     * @param audio the audio track copied, or {@code null} for none
     * @return what was written
     */
    private TranscodeResult remux(VideoTrack video, AudioTrack audio) {
        var builder = RemuxOptions.builder()
                .limits(options.limits())
                .brand(options.container() == OutputContainer.M4A ? Mp4MuxerOptions.Brand.M4A : Mp4MuxerOptions.Brand.MP4);
        if (video == null) {
            builder.noVideo();
        } else {
            builder.videoTrack(video.id());
        }
        if (audio == null) {
            builder.noAudio();
        } else {
            builder.audioTrack(audio.id());
        }
        var size = input.size();
        var watched = new WatchedSink(output, cancellation, written -> {
            if (size > 0 && expected > 0) {
                report(Math.min(expected, (long) ((double) expected * written / size)), false);
            }
        });
        RemuxResult result;
        try {
            result = Mp4Remuxer.remux(input, watched, builder.build());
        } catch (MediaException exception) {
            // the remuxer reports any failure of its output as a media failure, a cancellation included
            if (exception.getCause() instanceof CancellationException canceled) {
                throw canceled;
            }
            throw exception;
        }
        var streams = new ArrayList<TranscodedStream>();
        for (var track : result.tracks()) {
            var source = track.kind() == TrackKind.VIDEO ? video : audio;
            streams.add(new TranscodedStream(source == null ? -1 : source.id(), track, true));
        }
        var duration = report(result.durationMicros(), true);
        return new TranscodeResult(options.container(), duration, streams, true);
    }

    /**
     * Transcodes an animated image into a video stream.
     *
     * @return what was written
     * @throws IllegalArgumentException if the options drop video or the container cannot carry it
     * @throws MediaException.Unsupported if the options ask to copy the animation
     */
    private TranscodeResult runAnimation() {
        var video = options.video();
        var container = options.container();
        if (video.mode() == StreamMode.DROP) {
            throw new IllegalArgumentException("the input is an animated image and the options drop video");
        }
        if (video.mode() == StreamMode.COPY) {
            throw new MediaException.Unsupported("animated image", "an animated image cannot be copied into " + container
                    + ": encode it");
        }
        var codec = pickEncoder(video.codec(), container.videoCodecs(), VideoEncoders::isSupported, "video");
        try (var reader = Images.openAnimation(input, ImageDecoderOptions.builder().limits(options.limits()).build())) {
            var first = reader.read();
            if (first == null) {
                throw new MediaException.Malformed("the animation has no frame");
            }
            var firstDuration = displayTime(first.durationMicros());
            var rate = video.frameRate().orElse(new Rational(Math.max(1, Math.round(1_000_000.0 / firstDuration)), 1));
            var frameRate = video.effectiveFrameRate(rate);
            var track = VideoTrack.builder()
                    .id(0)
                    .codec(Codec.GIF)
                    .timeBase(Rational.MICROSECONDS)
                    .width(reader.width())
                    .height(reader.height())
                    .frameRate(frameRate != null ? frameRate : rate)
                    .build();
            if (reader.frameCount() > 0) {
                var length = reader.frameCount() * firstDuration;
                expected = Math.max(0, (end >= 0 ? Math.min(end, length) : length) - start);
            }
            try (var muxer = new OutputMuxer(() -> container.createMuxer(output), 1)) {
                var stage = new VideoEncodeStage(track, null, codec, video, true, frameRate, muxer.reserve(), muxer, start, end,
                        cancellation);
                try (stage) {
                    var stages = List.<Stage>of(stage);
                    var pts = 0L;
                    for (var frame = first; frame != null && !stage.done(); frame = reader.read()) {
                        cancellation.check();
                        var duration = displayTime(frame.durationMicros());
                        stage.acceptFrame(new VideoFrame(frame.picture(), pts, duration, true));
                        pts += duration;
                        report(stages, false);
                    }
                    stage.finish();
                    muxer.finish();
                    var duration = report(stages, true);
                    return new TranscodeResult(container, duration, List.of(new TranscodedStream(-1, stage.result().track(), false)),
                            false);
                }
            }
        }
    }

    /**
     * {@return the display time of an animation frame as viewers play it, in microseconds}
     *
     * @param stored the stored display time in microseconds
     */
    private static long displayTime(long stored) {
        return stored < ANIMATION_MIN_FRAME_MICROS ? ANIMATION_DEFAULT_FRAME_MICROS : stored;
    }

    /**
     * Forwards writes to a sink, stopping on cancellation and reporting the bytes written.
     */
    private static final class WatchedSink implements MediaSink {
        private final MediaSink delegate;
        private final Cancellation cancellation;

        /**
         * Receives the number of bytes written so far.
         */
        private final LongConsumer progress;

        private long written;

        private WatchedSink(MediaSink delegate, Cancellation cancellation, LongConsumer progress) {
            this.delegate = delegate;
            this.cancellation = cancellation;
            this.progress = progress;
        }

        @Override
        public long position() {
            return delegate.position();
        }

        @Override
        public void position(long position) {
            delegate.position(position);
        }

        @Override
        public boolean isSeekable() {
            return delegate.isSeekable();
        }

        @Override
        public void write(ByteBuffer data) {
            cancellation.check();
            var length = data.remaining();
            delegate.write(data);
            written += length;
            progress.accept(written);
        }

        /**
         * Leaves the caller's sink open.
         */
        @Override
        public void close() {
        }
    }
}
