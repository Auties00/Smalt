package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.AudioStreamDecoder;
import com.github.auties00.smalt.audio.AudioStreamDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.loudness.SilenceDetector;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.container.mp4.Mp4Remuxer;
import com.github.auties00.smalt.container.mp4.RemuxOptions;
import com.github.auties00.smalt.container.mp4.RemuxResult;
import com.github.auties00.smalt.container.ts.PsMuxer;
import com.github.auties00.smalt.container.ts.PsMuxerOptions;
import com.github.auties00.smalt.container.ts.TsMuxer;
import com.github.auties00.smalt.container.y4m.Y4mMuxer;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.internal.codec.VideoConfigs;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.provider.Artifacts;
import com.github.auties00.smalt.internal.tags.TagCarry;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.TagMode;
import com.github.auties00.smalt.metadata.TrackTags;
import com.github.auties00.smalt.subtitle.CaptionExtractor;
import com.github.auties00.smalt.subtitle.CaptionService;
import com.github.auties00.smalt.subtitle.ClosedCaptions;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleDecoder;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.subtitle.Subtitles;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.VideoFrame;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.LongConsumer;
import java.util.function.Predicate;

/**
 * Runs one audio/video transcode: plans a stage per selected stream of every output, then pumps the inputs through
 * the stages into the output muxers.
 *
 * <p>Planning happens before anything is written. Each selected stream is resolved to a
 * {@link CopyStage} or an encoding stage following its {@link StreamMode}; every codec the plan
 * needs is checked against the registries, so a missing artifact fails here with a
 * {@link MediaException.Unsupported} naming it. When the job has one input file and one output, and the plan copies
 * every stream (subtitles included) into an MP4, M4A or QuickTime file without trimming or retiming, and the input
 * is an ISO base media, Matroska, MPEG transport stream, MPEG program stream, AVI or Ogg file, the job is handed to
 * {@link Mp4Remuxer}, which plans the file layout from the input's index and repairs the timing
 * of Matroska video and of audio stored in another time base than its sampling rate. Otherwise
 * one demuxer feeds every stage in file order, so memory stays bounded by the codecs' own
 * buffers, one group of pictures per copied video stream and the packets that arrive before the
 * first video frame is decoded. A concatenated input is read through a {@link ConcatDemuxer}, several inputs through a
 * {@link MergedDemuxer}; several outputs share the demuxer, and the encodes of one video track in several outputs
 * share its decoder ({@link VideoFanOut}). Text subtitle streams get a {@link SubtitleStage},
 * which copies or converts them; when the job seeks to a trimmed start, a second demuxer reads the
 * subtitle packets stored before the seek point, so that cues still on screen at the start are kept.
 * An audio stream with a loudness normalization is measured by a first pass over a second demuxer
 * before the job starts, so is the audio a silence trim looks at, and the cues of a subtitle track burned into the
 * video are read by a second demuxer before the job starts when the input can be read again, and otherwise taken from
 * the packets as they arrive. The tags, pictures, chapters and track tags of each output are resolved
 * from the inputs' ({@link TagCarry}) once the stages are planned, and reach the muxer with its tracks.
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
     * How far past the start of the range the subtitle scan reads, in microseconds, before it
     * concludes that the packets stored ahead of the seek point were all read.
     *
     * @implNote This implementation uses 10 s, more than the interleaving distance of the
     * containers it reads; a packet the scan misses still reaches its stage through the seeked
     * demuxer when that demuxer delivers it.
     */
    private static final long SUBTITLE_SCAN_SLACK_MICROS = 10_000_000;

    /**
     * The smallest media advance between two progress reports, in microseconds.
     */
    private static final long PROGRESS_STEP_MICROS = 50_000;

    /**
     * The number of samples per channel the silence pass reads at a time.
     */
    private static final int SILENCE_BLOCK_FRAMES = 4096;

    private final List<TranscodeInput> inputs;

    private final List<Output> outputs;

    /**
     * The options of the first output, whose input-side settings every output shares.
     */
    private final TranscodeOptions shared;

    private final Cancellation cancellation;

    /**
     * The speed as a fraction.
     */
    private final Rational speed;

    /**
     * The start of the kept range in microseconds, moved by a silence trim.
     */
    private long start;

    /**
     * The end of the kept range in microseconds, or {@code -1} for the end of the input; moved by a silence trim.
     */
    private long end;

    /**
     * The pauses removed from the audio, {@code start, end} pairs in microseconds from {@link #start}.
     */
    private long[] cuts = new long[0];

    /**
     * The merged demuxer of a job with several inputs, or {@code null}.
     */
    private MergedDemuxer merged;

    /**
     * Constructs a run of one input and one output.
     *
     * @param input the input to transcode; not closed
     * @param output the sink the output is written to; not closed
     * @param options the job description
     * @param cancellation the cancellation of the job
     */
    AvTranscode(MediaSource input, MediaSink output, TranscodeOptions options, Cancellation cancellation) {
        this(List.of(TranscodeInput.of(input)), List.of(new TranscodeOutput(output, options)), cancellation);
    }

    /**
     * Constructs a run.
     *
     * @param inputs the inputs, at least one
     * @param outputs the outputs, at least one, sharing their input-side settings
     * @param cancellation the cancellation of the job
     * @throws IllegalArgumentException if a list is empty or the outputs differ in a setting of the input side
     */
    AvTranscode(List<TranscodeInput> inputs, List<TranscodeOutput> outputs, Cancellation cancellation) {
        this.inputs = List.copyOf(inputs);
        if (this.inputs.isEmpty()) {
            throw new IllegalArgumentException("a transcode needs at least one input");
        }
        if (outputs.isEmpty()) {
            throw new IllegalArgumentException("a transcode needs at least one output");
        }
        this.shared = outputs.getFirst().options();
        for (var output : outputs) {
            checkShared(output.options());
        }
        this.outputs = new ArrayList<>();
        for (var output : outputs) {
            this.outputs.add(new Output(output.sink(), output.options()));
        }
        this.cancellation = cancellation;
        this.start = shared.startMicros();
        this.end = shared.effectiveEndMicros();
        this.speed = fraction(shared.speed());
    }

    /**
     * Rejects the options of an output that differ from the first output's in a setting of the input side.
     *
     * @param options the options of an output
     * @throws IllegalArgumentException if a setting differs
     */
    private void checkShared(TranscodeOptions options) {
        var differs = options.startMicros() != shared.startMicros()
                || options.effectiveEndMicros() != shared.effectiveEndMicros()
                || options.seeking() != shared.seeking() || !options.limits().equals(shared.limits())
                || !options.dataReferences().equals(shared.dataReferences()) || options.speed() != shared.speed()
                || !options.silenceTrim().equals(shared.silenceTrim());
        if (differs) {
            throw new IllegalArgumentException("the outputs of one transcode must share the range, duration limit, "
                    + "seeking, limits, data references, speed and silence trim");
        }
    }

    /**
     * {@return a speed as a fraction with a denominator of at most 1000}
     *
     * @param speed the speed factor
     */
    private static Rational fraction(double speed) {
        var numerator = Math.round(speed * 1000);
        var gcd = gcd(numerator, 1000);
        return new Rational(numerator / gcd, 1000 / gcd);
    }

    /**
     * {@return the greatest common divisor of two positive numbers}
     *
     * @param a the first number
     * @param b the second number
     */
    private static long gcd(long a, long b) {
        while (b != 0) {
            var t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    /**
     * {@return whether the job reads one file}
     */
    private boolean plain() {
        return inputs.size() == 1 && !inputs.getFirst().isConcatenation();
    }

    /**
     * {@return the first file of the first input}
     */
    private MediaSource firstSource() {
        return inputs.getFirst().sources().getFirst();
    }

    /**
     * {@return whether a file of the job is read once, front to back}
     */
    private boolean forwardOnly() {
        for (var input : inputs) {
            for (var source : input.sources()) {
                if (ForwardOnlySource.isForwardOnly(source)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Runs a transcode of one output.
     *
     * @return what was written
     */
    TranscodeResult run() {
        return runAll().getFirst();
    }

    /**
     * Runs the transcode.
     *
     * @return what was written, one result per output in order
     * @throws IllegalArgumentException if the options select a track the input lacks or ask for
     * something the container cannot carry
     * @throws MediaException.Unsupported if the input is not recognized or a needed codec or
     * container feature is missing
     */
    List<TranscodeResult> runAll() {
        cancellation.check();
        ContainerFormat format = null;
        for (var input = 0; input < inputs.size(); input++) {
            var sources = inputs.get(input).sources();
            for (var file = 0; file < sources.size(); file++) {
                var source = sources.get(file);
                var detected = Containers.detect(source).orElse(null);
                if (detected == null) {
                    var image = Images.detect(source).orElse(null);
                    if (plain() && image != null && Images.isDecodable(image) && Images.probe(source).animated()) {
                        if (outputs.size() > 1) {
                            throw new IllegalArgumentException("an animated image transcodes into one output");
                        }
                        return List.of(outputs.getFirst().runAnimation());
                    }
                    throw new MediaException.Unsupported("unknown container", (plain() ? "the input" : "file "
                            + (file + 1) + " of input " + input) + " is not a recognized media container"
                            + (image != null ? " but a still " + image.name().toLowerCase(Locale.ROOT) + " image" : ""));
                }
                if (format == null) {
                    format = detected;
                }
            }
        }
        try (var demuxer = openDemuxer()) {
            return runDemuxed(format, demuxer);
        }
    }

    /**
     * Opens a demuxer of the job's inputs, at their start.
     *
     * @return the demuxer of the one file, or a {@link ConcatDemuxer} or {@link MergedDemuxer} of several
     */
    private Demuxer openDemuxer() {
        if (plain()) {
            return Containers.openDemuxer(firstSource(), demuxerOptions(shared));
        }
        var opened = new ArrayList<Demuxer>();
        try {
            for (var input : inputs) {
                if (!input.isConcatenation()) {
                    opened.add(Containers.openDemuxer(input.sources().getFirst(), demuxerOptions(shared)));
                    continue;
                }
                var files = new ArrayList<Demuxer>();
                try {
                    for (var source : input.sources()) {
                        files.add(Containers.openDemuxer(source, demuxerOptions(shared)));
                    }
                    opened.add(new ConcatDemuxer(files));
                } catch (RuntimeException exception) {
                    closeAll(files, exception);
                    throw exception;
                }
            }
            return opened.size() == 1 ? opened.getFirst() : new MergedDemuxer(opened);
        } catch (RuntimeException exception) {
            closeAll(opened, exception);
            throw exception;
        }
    }

    /**
     * Closes demuxers after a failure, attaching their own failures to it.
     *
     * @param demuxers the demuxers opened so far
     * @param failure the failure being reported
     */
    private static void closeAll(List<Demuxer> demuxers, RuntimeException failure) {
        for (var demuxer : demuxers) {
            try {
                demuxer.close();
            } catch (RuntimeException exception) {
                failure.addSuppressed(exception);
            }
        }
    }

    /**
     * Plans and runs a transcode of a demuxed input.
     *
     * @param format the container of the first file
     * @param demuxer the open demuxer of the inputs
     * @return what was written, one result per output
     */
    private List<TranscodeResult> runDemuxed(ContainerFormat format, Demuxer demuxer) {
        merged = demuxer instanceof MergedDemuxer mergedDemuxer ? mergedDemuxer : null;
        concat = demuxer instanceof ConcatDemuxer concatDemuxer ? concatDemuxer : null;
        var tracks = demuxer.tracks();
        for (var output : outputs) {
            output.select(tracks);
        }
        var trim = shared.silenceTrim().orElse(null);
        if (trim != null) {
            trimSilence(trim, demuxer);
        }
        for (var output : outputs) {
            output.plan(tracks, demuxer.durationMicros());
        }
        var single = outputs.getFirst();
        if (plain() && outputs.size() == 1 && single.remuxable(format)) {
            return List.of(single.remux());
        }
        var muxers = new ArrayList<OutputMuxer>();
        var fanOuts = new ArrayList<VideoFanOut>();
        Throwable primary = null;
        try {
            for (var output : outputs) {
                muxers.add(output.openMuxer());
            }
            for (var index = 0; index < outputs.size(); index++) {
                outputs.get(index).setTags(muxers.get(index), demuxer);
            }
            for (var index = 0; index < outputs.size(); index++) {
                outputs.get(index).createStages(muxers.get(index), tracks);
            }
            var receivers = receivers(fanOuts);
            var allStages = new ArrayList<Stage>();
            var burning = false;
            for (var output : outputs) {
                allStages.addAll(output.stages);
                burning |= output.burnStage != null;
            }
            normalizeLoudness(allStages);
            if (start > 0 && shared.seeking() && demuxer.isSeekable() && !burning) {
                seek(demuxer, allStages);
            }
            var byTrack = new HashMap<Integer, List<TrackReceiver>>();
            for (var receiver : receivers) {
                byTrack.computeIfAbsent(receiver.sourceTrackId(), _ -> new ArrayList<>()).add(receiver);
            }
            var burns = new HashMap<Integer, List<VideoEncodeStage>>();
            for (var output : outputs) {
                if (output.burnStage != null) {
                    burns.computeIfAbsent(output.burnTrack.id(), _ -> new ArrayList<>()).add(output.burnStage);
                }
            }
            if (merged != null) {
                var selected = new HashSet<>(byTrack.keySet());
                selected.addAll(burns.keySet());
                merged.select(selected);
            }
            if (demuxer instanceof SegmentedDemuxer segmented) {
                segmented.segmentListener((trackId, track, segmentStart) -> {
                    var list = byTrack.get(trackId);
                    if (list != null) {
                        for (var receiver : list) {
                            receiver.startSegment(track, segmentStart);
                        }
                    }
                });
            }
            while (!allDone()) {
                cancellation.check();
                var packet = demuxer.read();
                if (packet == null) {
                    break;
                }
                var burnStages = burns.get(packet.trackId());
                if (burnStages != null) {
                    for (var stage : burnStages) {
                        stage.acceptSubtitle(packet);
                    }
                }
                var list = byTrack.get(packet.trackId());
                if (list != null) {
                    for (var receiver : list) {
                        receiver.accept(packet);
                    }
                    for (var output : outputs) {
                        output.report(false);
                    }
                }
            }
            for (var fanOut : fanOuts) {
                cancellation.check();
                fanOut.flush();
            }
            var results = new ArrayList<TranscodeResult>();
            for (var index = 0; index < outputs.size(); index++) {
                results.add(outputs.get(index).finish(muxers.get(index)));
            }
            return results;
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            var closing = new ArrayList<AutoCloseable>(muxers);
            for (var output : outputs) {
                closing.addAll(output.stages);
            }
            closing.addAll(fanOuts);
            closeQuietly(closing, primary);
        }
    }

    /**
     * Closes resources, attaching their failures to the failure that ended the job, or throwing the first when the
     * job succeeded.
     *
     * @param resources the resources, closed in order
     * @param primary the failure that ended the job, or {@code null}
     */
    private static void closeQuietly(List<AutoCloseable> resources, Throwable primary) {
        RuntimeException failure = null;
        for (var resource : resources) {
            try {
                resource.close();
            } catch (Exception exception) {
                if (primary != null) {
                    primary.addSuppressed(exception);
                } else if (failure == null) {
                    failure = exception instanceof RuntimeException runtime ? runtime : new IllegalStateException(exception);
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Returns what receives the packets of each input track: the stages, except the video encodes whose decoder a
     * {@link VideoFanOut} shares, which replaces them.
     *
     * @param fanOuts receives the fan-outs created
     * @return the receivers
     */
    private List<TrackReceiver> receivers(List<VideoFanOut> fanOuts) {
        var receivers = new ArrayList<TrackReceiver>();
        var sharing = new LinkedHashMap<Integer, List<VideoEncodeStage>>();
        var sources = new HashMap<Integer, VideoTrack>();
        for (var output : outputs) {
            if (output.sharedVideo != null) {
                sharing.computeIfAbsent(output.sharedVideo.sourceTrackId(), _ -> new ArrayList<>()).add(output.sharedVideo);
                sources.put(output.sharedVideo.sourceTrackId(), output.videoPlan.track());
            }
            for (var stage : output.stages) {
                if (stage != output.sharedVideo) {
                    receivers.add(output.retimed(stage));
                }
            }
        }
        for (var entry : sharing.entrySet()) {
            var fanOut = new VideoFanOut(sources.get(entry.getKey()), videoDecoderOptions(), entry.getValue());
            fanOuts.add(fanOut);
            receivers.add(fanOut);
        }
        return receivers;
    }

    /**
     * {@return the options of the video decoders: the limits, at the stream's depth}
     */
    private VideoDecoderOptions videoDecoderOptions() {
        return VideoDecoderOptions.builder().limits(shared.limits()).highBitDepth(true).build();
    }

    /**
     * {@return whether every output's stages reached the end of the kept range}
     */
    private boolean allDone() {
        for (var output : outputs) {
            if (!allDone(output.stages)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Finds the silences of the first selected audio stream and moves the kept range past the leading and trailing
     * ones, recording the pauses to shorten.
     *
     * @param trim the trimming settings
     * @param demuxer the demuxer of the job, not read yet
     * @throws IllegalArgumentException if the job selects no audio stream, or pauses are shortened in an output with
     * video or subtitles
     * @throws MediaException.Unsupported if a file of the job is read once, so it cannot be read twice
     */
    private void trimSilence(SilenceTrim trim, Demuxer demuxer) {
        AudioTrack track = null;
        for (var output : outputs) {
            if (!output.audio.isEmpty()) {
                track = output.audio.getFirst();
                break;
            }
        }
        if (track == null) {
            throw new IllegalArgumentException("trimming silence needs an audio stream in the output");
        }
        if (trim.maxPauseMicros().isPresent()) {
            for (var output : outputs) {
                if (output.video != null || output.options.subtitles().mode() != StreamMode.DROP
                        && output.hasSubtitleCandidates(demuxer.tracks())) {
                    throw new IllegalArgumentException("shortening pauses needs outputs of audio streams alone: drop "
                            + "the video and subtitle streams");
                }
            }
        }
        if (forwardOnly()) {
            throw new MediaException.Unsupported("silence trim", "trimming silence reads the input twice, which a "
                    + "source read once cannot do");
        }
        // the files of a concatenation are measured at the first file's rate and layout, as their encode converts them
        var layout = track.layout().orElse(null);
        var options = (layout != null && inputs.get(inputOf(track.id())).isConcatenation()
                ? AudioStreamDecoderOptions.of(track.sampleRate(), layout).toBuilder()
                : AudioStreamDecoderOptions.builder()).limits(shared.limits()).build();
        long total;
        int rate;
        List<com.github.auties00.smalt.audio.loudness.SilenceRange> ranges;
        try (var scan = openDemuxer();
             var decoder = new SegmentedAudioDecoder(AudioStreamDecoder.create(track, options), options)) {
            var id = track.id();
            if (scan instanceof SegmentedDemuxer segmented) {
                segmented.segmentListener((trackId, segmentTrack, segmentStart) -> {
                    if (trackId == id) {
                        decoder.startSegment((AudioTrack) segmentTrack, segmentStart);
                    }
                });
            }
            if (scan instanceof MergedDemuxer mergedScan) {
                mergedScan.select(Set.of(id));
            }
            rate = decoder.sampleRate();
            var detector = new SilenceDetector(rate, trim.detection());
            var samples = Rational.timeBaseOf(rate);
            var drop = Rational.MICROSECONDS.rescale(start, samples);
            var keep = end < 0 ? Long.MAX_VALUE : Rational.MICROSECONDS.rescale(end - start, samples);
            var planes = new float[decoder.current().layout().channels()][SILENCE_BLOCK_FRAMES];
            var kept = 0L;
            var ended = false;
            while (!ended && kept < keep) {
                cancellation.check();
                var packet = scan.read();
                if (packet == null) {
                    decoder.finish();
                    ended = true;
                } else if (packet.trackId() == id) {
                    decoder.decode(packet);
                } else {
                    continue;
                }
                int frames;
                while (kept < keep && (frames = decoder.read(planes, 0, SILENCE_BLOCK_FRAMES)) > 0) {
                    var offset = (int) Math.min(drop, frames);
                    drop -= offset;
                    var count = (int) Math.min(frames - offset, keep - kept);
                    if (count > 0) {
                        if (offset > 0) {
                            for (var plane : planes) {
                                System.arraycopy(plane, offset, plane, 0, count);
                            }
                        }
                        detector.accept(planes, 0, count);
                        kept += count;
                    }
                }
            }
            total = kept;
            ranges = detector.finish();
        }
        if (total == 0 || ranges.isEmpty()) {
            return;
        }
        var samples = Rational.timeBaseOf(rate);
        var firstKept = 0L;
        var lastKept = total;
        var first = ranges.getFirst();
        var last = ranges.getLast();
        if (first.startFrame() == 0 && first.endFrame() >= total) {
            // the whole range is silent: nothing is left to keep, so it is kept whole
            return;
        }
        if (trim.leading() && first.startFrame() == 0) {
            firstKept = first.endFrame();
        }
        if (trim.trailing() && last.endFrame() >= total) {
            lastKept = last.startFrame();
        }
        var removed = new ArrayList<Long>();
        if (trim.maxPauseMicros().isPresent()) {
            var maxPause = Rational.MICROSECONDS.rescale(trim.maxPauseMicros().getAsLong(), samples);
            for (var range : ranges) {
                if (range.startFrame() < firstKept || range.endFrame() > lastKept) {
                    continue;
                }
                var length = range.endFrame() - range.startFrame();
                if (length > maxPause) {
                    var cutStart = range.startFrame() + maxPause / 2;
                    var cutEnd = range.endFrame() - (maxPause - maxPause / 2);
                    removed.add(samples.toMicros(cutStart - firstKept));
                    removed.add(samples.toMicros(cutEnd - firstKept));
                }
            }
        }
        var originalStart = start;
        start = originalStart + samples.toMicros(firstKept);
        if (lastKept < total) {
            end = originalStart + samples.toMicros(lastKept);
        }
        cuts = removed.stream().mapToLong(Long::longValue).toArray();
    }

    /**
     * {@return the length of the pauses removed, in microseconds}
     */
    private long cutMicros() {
        var total = 0L;
        for (var index = 0; index < cuts.length; index += 2) {
            total += cuts[index + 1] - cuts[index];
        }
        return total;
    }

    /**
     * {@return a span of the input timeline divided by the speed}
     *
     * @param micros the span in microseconds
     */
    private long scaledMicros(long micros) {
        if (speed.numerator() == speed.denominator()) {
            return micros;
        }
        return Math.floorDiv(Math.multiplyExact(micros, speed.denominator()) * 2 + speed.numerator(),
                2 * speed.numerator());
    }

    /**
     * Seeks the demuxer before the start of the kept range, far enough for every stage: copied
     * streams need {@value #SEEK_PRE_ROLL_MICROS} microseconds, and every encoded audio stream the
     * pre-roll and timing its decoder measures, so that it restarts sample-accurately. When the
     * demuxer turns out unable to seek, or an encoded audio stream's timestamps are not
     * sample-accurate ({@link AudioStreamDecoder.Timing#isExact()}), the demuxer is left at the
     * start and the stages decode and drop. After a seek past the start of the input, the subtitle
     * stages are primed with the packets stored before the seek point
     * ({@link #primeSubtitles(List)}); a source that reads its input once cannot be read again for
     * them, so with subtitle stages the demuxer is left at the start.
     *
     * @param demuxer the demuxer, at the start of the input
     * @param stages the stages of every output, none of which received a packet
     */
    private void seek(Demuxer demuxer, List<Stage> stages) {
        if (forwardOnly() && stages.stream().anyMatch(SubtitleStage.class::isInstance)) {
            // a source read once cannot be read again from its start for the cues shown at the start
            return;
        }
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
            demuxer.seek(target);
        } catch (MediaException.Unsupported unsupported) {
            // the first seek failed before any packet was read: decode from the start and drop instead
            return;
        }
        if (target > 0) {
            primeSubtitles(stages);
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
     * Hands every subtitle stage the packets of its track that start before the kept range, read
     * from the start of the input by a second demuxer, so that the cues still shown at the start,
     * which are stored at the time they appear and may precede the seek point by any distance,
     * reach the output.
     *
     * <p>The scan ends at the end of the input, once every subtitle track has a packet at or after
     * the start, or once any packet lies {@value #SUBTITLE_SCAN_SLACK_MICROS} microseconds past the
     * start. Each stage then ignores the packets of the seeked demuxer that it already received.
     *
     * @param stages the stages, none of which received a packet
     */
    private void primeSubtitles(List<Stage> stages) {
        var subtitles = new HashMap<Integer, List<SubtitleStage>>();
        for (var stage : stages) {
            if (stage instanceof SubtitleStage subtitle) {
                subtitles.computeIfAbsent(subtitle.sourceTrackId(), _ -> new ArrayList<>()).add(subtitle);
            }
        }
        if (subtitles.isEmpty()) {
            return;
        }
        try (var scan = openDemuxer()) {
            var tracksById = new HashMap<Integer, Track>();
            for (var track : scan.tracks()) {
                tracksById.put(track.id(), track);
            }
            var pending = new HashSet<>(subtitles.keySet());
            while (!pending.isEmpty()) {
                cancellation.check();
                var packet = scan.read();
                if (packet == null) {
                    break;
                }
                var track = tracksById.get(packet.trackId());
                var time = packet.pts() != Packet.NO_TIMESTAMP ? packet.pts() : packet.dts();
                if (track == null || time == Packet.NO_TIMESTAMP) {
                    continue;
                }
                var micros = track.timeBase().toMicros(time);
                var list = subtitles.get(packet.trackId());
                if (list != null) {
                    if (micros < start) {
                        var retimed = retime(packet, track);
                        for (var stage : list) {
                            stage.prime(retimed);
                        }
                    } else {
                        pending.remove(packet.trackId());
                    }
                }
                if (micros - start >= SUBTITLE_SCAN_SLACK_MICROS) {
                    break;
                }
            }
        }
    }

    /**
     * Runs the loudness pass of every encoded audio stream that normalizes its loudness, each over
     * its own demuxer of the input.
     *
     * @param stages the stages of every output, none of which received a packet
     */
    private void normalizeLoudness(List<Stage> stages) {
        for (var output : outputs) {
            var normalization = output.options.audio().loudnessNormalization();
            if (normalization.isEmpty() || output.options.audio().singlePassNormalization()) {
                continue;
            }
            for (var stage : output.stages) {
                if (stage instanceof AudioEncodeStage audio) {
                    try (var measuring = openDemuxer()) {
                        if (measuring instanceof MergedDemuxer mergedMeasuring) {
                            mergedMeasuring.select(Set.of(audio.sourceTrackId()));
                        }
                        audio.normalize(measuring, normalization.get(), cancellation);
                    }
                }
            }
        }
    }

    /**
     * {@return a packet of a subtitle or caption stream moved to the speed of the output: its times after the start
     * of the kept range divided by the speed}
     *
     * @param packet a packet of the track
     * @param track the packet's track
     */
    private Packet retime(Packet packet, Track track) {
        if (speed.numerator() == speed.denominator()) {
            return packet;
        }
        var timeBase = track.timeBase();
        var origin = Rational.MICROSECONDS.rescale(start, timeBase);
        var pts = packet.pts() == Packet.NO_TIMESTAMP ? Packet.NO_TIMESTAMP : origin + scaled(packet.pts() - origin);
        var dts = packet.dts() == Packet.NO_TIMESTAMP ? Packet.NO_TIMESTAMP : origin + scaled(packet.dts() - origin);
        var duration = packet.duration() <= 0 ? packet.duration() : Math.max(1, scaled(packet.duration()));
        return packet.withTiming(pts, dts, duration);
    }

    /**
     * {@return a span divided by the speed, in the same unit}
     *
     * @param span the span to divide, in any unit
     */
    private long scaled(long span) {
        return Math.floorDiv(Math.multiplyExact(span, speed.denominator()) * 2 + speed.numerator(),
                2 * speed.numerator());
    }

    /**
     * Describes how a text subtitle stream is processed.
     *
     * @param track the input track
     * @param codec the output codec
     * @param convert whether the stream is decoded and encoded rather than copied
     * @param embedded whether the stream is the closed captions carried in the video track {@code track} stands
     * for, extracted from its packets
     */
    private record SubtitlePlan(Track track, Codec codec, boolean convert, boolean embedded) {
    }

    /**
     * Returns whether a track is a subtitle stream a transcode may keep: text, TTML, bitmap or caption.
     *
     * @param track the input track
     * @return {@code true} for a subtitle or caption track
     */
    private static boolean isSubtitleTrack(Track track) {
        var codec = track.codec();
        return codec.isSubtitle() || codec == Codec.TTML || codec.isBitmapSubtitle() || codec.isCaption();
    }

    /**
     * {@return the brand {@link Mp4Remuxer} writes for an output container, or {@code null} when it
     * does not write the container}
     *
     * @param container the output container
     */
    private static Mp4MuxerOptions.Brand remuxBrand(OutputContainer container) {
        return switch (container) {
            case MP4 -> Mp4MuxerOptions.Brand.MP4;
            case M4A -> Mp4MuxerOptions.Brand.M4A;
            case MOV -> Mp4MuxerOptions.Brand.QUICKTIME;
            default -> null;
        };
    }

    /**
     * {@return whether {@link Mp4Remuxer} reads an input container: ISO base media, Matroska, MPEG
     * transport streams, MPEG program streams, MXF, AVI and Ogg}
     *
     * @param format the input container
     */
    private static boolean remuxable(ContainerFormat format) {
        return switch (format) {
            case MP4, MATROSKA, MPEG_TS, MPEG_PS, MXF, AVI, OGG -> true;
            default -> false;
        };
    }

    /**
     * {@return whether every stage reached the end of the kept range, so that no later packet is
     * needed}
     *
     * @param stages the stages of an output
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
     * @param stages the stages of an output
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
     * {@return the input a track of the job's demuxer comes from}
     *
     * @param trackId the track identifier
     */
    private int inputOf(int trackId) {
        return merged == null ? 0 : merged.inputOf(trackId);
    }

    /**
     * {@return the identifier the input itself gives a track of the job's demuxer}
     *
     * @param trackId the track identifier
     */
    private int originalId(int trackId) {
        return merged == null ? trackId : merged.originalId(trackId);
    }

    /**
     * Returns the tracks a stream selection may pick from.
     *
     * @param tracks the tracks of the job's demuxer
     * @param input the input the selection names, or empty for any
     * @return the tracks of that input, or every track
     * @throws IllegalArgumentException if the input index is out of range
     */
    private List<Track> candidates(List<Track> tracks, OptionalInt input) {
        if (input.isEmpty()) {
            return tracks;
        }
        var index = input.getAsInt();
        if (index >= inputs.size()) {
            throw new IllegalArgumentException("the options select input " + index + " of a transcode of "
                    + inputs.size() + (inputs.size() == 1 ? " input" : " inputs"));
        }
        var result = new ArrayList<Track>();
        for (var track : tracks) {
            if (inputOf(track.id()) == index) {
                result.add(track);
            }
        }
        return result;
    }

    /**
     * Returns the track with an identifier of an input.
     *
     * @param tracks the tracks of the job's demuxer
     * @param input the input the selection names, or empty for the first
     * @param id the identifier the input gives the track
     * @return the track
     * @throws IllegalArgumentException if the input has no track of that identifier
     */
    private Track trackById(List<Track> tracks, OptionalInt input, int id) {
        var index = input.orElse(0);
        candidates(tracks, OptionalInt.of(index));
        var wanted = merged == null ? id : merged.mergedId(index, id);
        for (var track : tracks) {
            if (track.id() == wanted) {
                return track;
            }
        }
        throw new IllegalArgumentException((inputs.size() == 1 ? "the input" : "input " + index) + " has no track "
                + id);
    }

    /**
     * Returns the options the demuxers of a transcode's input use.
     *
     * @param options the transcode options
     * @return the limits and the resolver of the files the input names
     */
    private static DemuxerOptions demuxerOptions(TranscodeOptions options) {
        return DemuxerOptions.builder().limits(options.limits()).dataReferences(options.dataReferences()).build();
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
     * Describes how the video stream is processed.
     *
     * @param track the input track
     * @param copy whether its packets are copied
     * @param codec the codec produced when encoding
     * @param applyOrientation whether the orientation is applied to the pixels
     * @param applyAspect whether non-square samples are resampled to square ones
     * @param frameRate the constant output rate, or {@code null} to keep the source timing
     */
    private record VideoPlan(VideoTrack track, boolean copy, Codec codec, boolean applyOrientation, boolean applyAspect,
                             Rational frameRate) {
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
     * One output of the job: its options, plan, muxer and stages.
     */
    private final class Output {
        private final MediaSink sink;
        private final TranscodeOptions options;

        /**
         * The subtitle track whose packets the video stage receives as they arrive, or {@code null}.
         */
        private Track burnTrack;

        /**
         * The video stage that receives {@link #burnTrack}'s packets, or {@code null}.
         */
        private VideoEncodeStage burnStage;

        /**
         * The video stage whose decoder a {@link VideoFanOut} shares, or {@code null}.
         */
        private VideoEncodeStage sharedVideo;

        /**
         * The last position reported to the listener in microseconds, or {@code -1} before the first
         * report.
         */
        private long reported = -1;

        /**
         * The expected output duration in microseconds, or {@code -1} when unknown.
         */
        private long expected = -1;

        private VideoTrack video;
        private List<AudioTrack> audio = List.of();
        private List<SubtitlePlan> subtitles = List.of();
        private VideoPlan videoPlan;
        private final List<AudioPlan> audioPlans = new ArrayList<>();
        private final List<Stage> stages = new ArrayList<>();

        Output(MediaSink sink, TranscodeOptions options) {
            this.sink = sink;
            this.options = options;
        }

        /**
         * Selects the video and audio streams.
         *
         * @param tracks the tracks of the job's demuxer
         */
        void select(List<Track> tracks) {
            video = selectVideo(tracks);
            audio = selectAudio(tracks);
        }

        /**
         * Plans the streams once the kept range is final.
         *
         * @param tracks the tracks of the job's demuxer
         * @param inputMicros the duration of the input in microseconds, or {@code -1} when unknown
         */
        void plan(List<Track> tracks, long inputMicros) {
            var planned = new ArrayList<>(planSubtitles(tracks));
            var captions = planCaptions(video, planned);
            if (captions != null) {
                planned.add(captions);
            }
            subtitles = planned;
            if (video == null && audio.isEmpty() && subtitles.isEmpty()) {
                throw new IllegalArgumentException("the options select no stream of the input");
            }
            videoPlan = video == null ? null : planVideo(video);
            for (var track : audio) {
                audioPlans.add(planAudio(track));
            }
            var span = end >= 0 ? end - start : inputMicros >= 0 ? Math.max(0, inputMicros - start) : -1;
            expected = span < 0 ? -1 : scaledMicros(Math.max(0, span - cutMicros()));
        }

        /**
         * {@return whether the input has a subtitle track this output's options could select}
         *
         * @param tracks the tracks of the job's demuxer
         */
        boolean hasSubtitleCandidates(List<Track> tracks) {
            for (var track : candidates(tracks, options.subtitles().input())) {
                if (isSubtitleTrack(track)) {
                    return true;
                }
            }
            return options.subtitles().closedCaptions() && video != null && ClosedCaptions.carries(video.codec());
        }

        /**
         * {@return whether the job may be handed to {@link Mp4Remuxer}}
         *
         * @param format the input container
         */
        boolean remuxable(ContainerFormat format) {
            var brand = remuxBrand(options.container());
            return start == 0 && end < 0 && cuts.length == 0 && speed.numerator() == speed.denominator()
                    && brand != null && AvTranscode.remuxable(format)
                    && (videoPlan == null || videoPlan.copy() && Mp4Muxer.supports(brand, videoPlan.track().codec()))
                    && audioPlans.stream().allMatch(plan -> plan.copy() && Mp4Muxer.supports(brand, plan.track().codec()))
                    && subtitles.stream().allMatch(plan -> !plan.convert() && !plan.embedded()
                    && plan.codec() == plan.track().codec() && Mp4Muxer.supports(brand, plan.codec()));
        }

        /**
         * {@return the muxer of this output}
         */
        OutputMuxer openMuxer() {
            var capacity = (videoPlan == null ? 0 : 1) + audioPlans.size() + subtitles.size();
            var container = options.container();
            return new OutputMuxer(() -> container.createMuxer(sink), capacity);
        }

        /**
         * Creates the stages, reserving their output tracks in order.
         *
         * @param muxer the output
         * @param tracks the tracks of the job's demuxer
         */
        void createStages(OutputMuxer muxer, List<Track> tracks) {
            if (videoPlan != null) {
                var stage = createVideoStage(videoPlan, muxer.reserve(), muxer, tracks);
                stages.add(stage);
                if (stage instanceof VideoEncodeStage encoding && burnTrack != null) {
                    burnStage = encoding;
                }
            }
            for (var plan : audioPlans) {
                stages.add(createAudioStage(plan, muxer.reserve(), muxer));
            }
            var subtitleEnd = end < 0 ? -1 : start + scaledMicros(end - start);
            for (var plan : subtitles) {
                var service = plan.track().codec().isCaption()
                        ? options.subtitles().captionService().filter(chosen -> chosen.readableFrom(plan.track().codec()))
                        .orElse(CaptionService.defaultFor(plan.track())) : null;
                var stage = new SubtitleStage(plan.track(), plan.codec(), plan.convert(), service, muxer.reserve(),
                        muxer, start, subtitleEnd);
                stages.add(plan.embedded() ? new CaptionStage(new CaptionExtractor(video), stage) : stage);
            }
        }

        /**
         * {@return the receiver of a stage's packets: the stage, or a wrapper moving subtitle and caption packets to
         * the output's speed}
         *
         * @param stage the stage whose packets are received
         */
        TrackReceiver retimed(Stage stage) {
            if (speed.numerator() == speed.denominator()
                    || !(stage instanceof SubtitleStage) && !(stage instanceof CaptionStage)) {
                return stage;
            }
            Track track = null;
            if (stage instanceof CaptionStage) {
                track = video;
            } else {
                for (var plan : subtitles) {
                    if (plan.track().id() == stage.sourceTrackId()) {
                        track = plan.track();
                    }
                }
            }
            var timed = Objects.requireNonNull(track);
            return new TrackReceiver() {
                @Override
                public int sourceTrackId() {
                    return stage.sourceTrackId();
                }

                @Override
                public void accept(Packet packet) {
                    stage.accept(retime(packet, timed));
                }

                @Override
                public boolean done() {
                    return stage.done();
                }
            };
        }

        /**
         * Drains the stages and the muxer.
         *
         * @param muxer the output
         * @return what was written
         */
        TranscodeResult finish(OutputMuxer muxer) {
            for (var stage : stages) {
                cancellation.check();
                stage.finish();
            }
            muxer.finish();
            var results = new ArrayList<TranscodedStream>();
            var remuxed = true;
            for (var stage : stages) {
                var result = stage.result();
                var id = result.sourceTrackId();
                results.add(new TranscodedStream(inputOf(id), originalId(id), result.track(), result.copied()));
                remuxed &= result.copied();
            }
            var duration = report(true);
            return new TranscodeResult(options.container(), duration, results, remuxed);
        }

        /**
         * Describes how the output of a plan relates to the input: the output tracks are numbered from one in the
         * order the stages reserve them (the video stream, the audio streams, the subtitle streams).
         *
         * @return the mapping
         */
        private TagCarry.Mapping mapping() {
            var outputIds = new HashMap<Integer, Integer>();
            var rewritten = new HashSet<Integer>();
            var encoded = false;
            var levelChanged = false;
            if (videoPlan != null) {
                outputIds.put(videoPlan.track().id(), outputIds.size() + 1);
                if (!videoPlan.copy()) {
                    rewritten.add(videoPlan.track().id());
                    encoded = true;
                }
            }
            for (var plan : audioPlans) {
                outputIds.put(plan.track().id(), outputIds.size() + 1);
                if (!plan.copy()) {
                    rewritten.add(plan.track().id());
                    encoded = true;
                    levelChanged |= options.audio().gainDb() != 0 || options.audio().loudnessNormalization().isPresent()
                            || !options.audio().filters().isEmpty() || !options.audio().fades().isEmpty();
                }
            }
            for (var plan : subtitles) {
                outputIds.put(plan.track().id(), outputIds.size() + 1);
                if (plan.convert() || plan.codec() != plan.track().codec()) {
                    rewritten.add(plan.track().id());
                }
            }
            if (start > 0 || end >= 0 || cuts.length > 0 || speed.numerator() != speed.denominator()) {
                rewritten.addAll(outputIds.keySet());
            }
            return new TagCarry.Mapping(outputIds, rewritten, encoded, levelChanged, start, end);
        }

        /**
         * Resolves the tags of the output and hands them to the muxer.
         *
         * @param muxer the output, with no track reserved yet
         * @param demuxer the demuxer of the job, at its start
         */
        void setTags(OutputMuxer muxer, Demuxer demuxer) {
            if (!options.container().supportsTags()) {
                return;
            }
            var mode = options.tagMode();
            var given = options.tags().orElse(null);
            var inputTags = mode == TagMode.COPY || mode == TagMode.MERGE ? readTags(demuxer) : null;
            muxer.tags(TagCarry.resolve(mode, given, inputTags, mapping()), mode, given);
        }

        /**
         * Reads the tags of the job's inputs, on the terms of the job's demuxer: the global tags, pictures and
         * chapters of the first input, and the track tags of every input.
         *
         * @param demuxer the demuxer of the job, at its start
         * @return the tags
         */
        private MediaTags readTags(Demuxer demuxer) {
            var limits = shared.limits();
            if (merged == null) {
                return withChapters(TagCarry.read(firstSource(), demuxer, limits), demuxer, inputs.getFirst());
            }
            var first = withChapters(TagCarry.read(firstSource(), merged.input(0), limits), merged.input(0),
                    inputs.getFirst());
            var tracks = new ArrayList<TrackTags>();
            for (var index = 0; index < merged.inputCount(); index++) {
                var own = index == 0 ? first : TagCarry.read(inputs.get(index).sources().getFirst(), merged.input(index),
                        limits);
                for (var track : own.tracks()) {
                    var id = merged.mergedId(index, track.trackId());
                    if (id >= 0) {
                        tracks.add(new TrackTags(id, track.tags(), track.disposition()));
                    }
                }
            }
            return first.toBuilder().tracks(tracks).build();
        }

        /**
         * {@return the tags of an input with a concatenation's joined chapters}
         *
         * @param tags the tags read from the input's first file
         * @param demuxer the input's demuxer
         * @param input the index of the input
         */
        private MediaTags withChapters(MediaTags tags, Demuxer demuxer, TranscodeInput input) {
            return input.isConcatenation() ? tags.toBuilder().chapters(demuxer.chapters()).build() : tags;
        }

        /**
         * Reports progress to the listener when the output advanced enough, or always at the end.
         *
         * @param last whether the output is complete
         * @return the position reported, in microseconds
         */
        long report(boolean last) {
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
         * @param tracks the tracks of the job's demuxer
         * @return the track, or {@code null} when video is dropped, absent, or (unless requested
         * explicitly) not carried by the container
         * @throws IllegalArgumentException if a requested track is missing or cannot be written
         */
        private VideoTrack selectVideo(List<Track> tracks) {
            var settings = options.video();
            if (settings.mode() == StreamMode.DROP) {
                return null;
            }
            VideoTrack selected = null;
            if (settings.trackId().isPresent()) {
                var id = settings.trackId().getAsInt();
                var track = trackById(tracks, settings.input(), id);
                if (!(track instanceof VideoTrack videoTrack)) {
                    throw new IllegalArgumentException("track " + id + " is not a video track");
                }
                selected = videoTrack;
            } else {
                for (var track : candidates(tracks, settings.input())) {
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
                if (settings.mode() == StreamMode.AUTO && settings.trackId().isEmpty()) {
                    return null;
                }
                throw new IllegalArgumentException(container + " files cannot carry video");
            }
            return selected;
        }

        /**
         * Selects the audio tracks.
         *
         * @param tracks the tracks of the job's demuxer
         * @return the tracks, empty when audio is dropped, absent, or (unless requested explicitly) not
         * carried by the container
         * @throws IllegalArgumentException if a requested track is missing or cannot be written
         */
        private List<AudioTrack> selectAudio(List<Track> tracks) {
            var settings = options.audio();
            if (settings.mode() == StreamMode.DROP) {
                return List.of();
            }
            var selected = new ArrayList<AudioTrack>();
            if (settings.trackId().isPresent()) {
                var id = settings.trackId().getAsInt();
                var track = trackById(tracks, settings.input(), id);
                if (!(track instanceof AudioTrack audioTrack)) {
                    throw new IllegalArgumentException("track " + id + " is not an audio track");
                }
                selected.add(audioTrack);
            } else {
                for (var track : candidates(tracks, settings.input())) {
                    if (track instanceof AudioTrack audioTrack) {
                        selected.add(audioTrack);
                        if (!settings.allTracks()) {
                            break;
                        }
                    }
                }
            }
            var container = options.container();
            if (!selected.isEmpty() && container.audioCodecs().isEmpty() && !container.supports(selected.getFirst().codec())) {
                if (settings.mode() == StreamMode.AUTO && settings.trackId().isEmpty()) {
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
         * Selects and plans the text subtitle streams.
         *
         * @param tracks the tracks of the job's demuxer
         * @return the plans, empty when subtitles are dropped, absent, or (unless requested
         * explicitly) not carried by the container in any form
         * @throws IllegalArgumentException if a requested track is missing, is not a subtitle track,
         * or cannot be written
         * @throws MediaException.Unsupported if a stream must be copied but the container cannot carry
         * its codec
         */
        private List<SubtitlePlan> planSubtitles(List<Track> tracks) {
            var settings = options.subtitles();
            if (settings.mode() == StreamMode.DROP) {
                return List.of();
            }
            var selected = new ArrayList<Track>();
            if (settings.trackId().isPresent()) {
                var id = settings.trackId().getAsInt();
                var track = trackById(tracks, settings.input(), id);
                if (!isSubtitleTrack(track)) {
                    throw new IllegalArgumentException("track " + id + " is not a subtitle or caption track");
                }
                selected.add(track);
            } else {
                for (var track : candidates(tracks, settings.input())) {
                    if (isSubtitleTrack(track)) {
                        selected.add(track);
                        if (!settings.allTracks()) {
                            break;
                        }
                    }
                }
            }
            var container = options.container();
            var explicit = settings.mode() != StreamMode.AUTO || settings.trackId().isPresent();
            var retimes = speed.numerator() != speed.denominator();
            var plans = new ArrayList<SubtitlePlan>();
            for (var track : selected) {
                var codec = track.codec();
                if (codec.isBitmapSubtitle()) {
                    var copyable = container.supports(codec) && settings.mode() != StreamMode.ENCODE
                            && settings.codec().map(codec::equals).orElse(true);
                    if (copyable) {
                        plans.add(new SubtitlePlan(track, codec, false, false));
                        if (!container.carriesSeveralTracks()) {
                            break;
                        }
                    } else if (explicit) {
                        throw new MediaException.Unsupported(codec.displayName(), "the " + codec.displayName()
                                + " subtitles are pictures, which cannot be converted to text, and " + (container.supports(codec)
                                ? "the options ask for " + settings.codec().map(Codec::displayName).orElse("conversion")
                                : container + " cannot carry them"));
                    }
                    continue;
                }
                Codec target;
                if (settings.codec().isPresent()) {
                    target = settings.codec().get();
                    if (!container.supports(target)) {
                        throw new IllegalArgumentException(container + " cannot carry " + target.displayName());
                    }
                } else if (container.supports(codec) && !(codec == Codec.TTML && (start > 0 || end >= 0 || retimes)
                        && container.format() != ContainerFormat.TTML)) {
                    target = codec;
                } else if (!container.subtitleCodecs().isEmpty()) {
                    target = container.subtitleCodecs().getFirst();
                } else if (explicit) {
                    throw new IllegalArgumentException(container + " files cannot carry subtitles");
                } else {
                    continue;
                }
                if (settings.mode() == StreamMode.COPY && target != codec) {
                    throw new MediaException.Unsupported(codec.displayName(), "cannot copy the " + codec.displayName()
                            + " subtitles into " + container + ": " + (container.supports(codec)
                            ? "the options ask for " + target.displayName() : container + " cannot carry " + codec.displayName()));
                }
                plans.add(new SubtitlePlan(track, target, settings.mode() == StreamMode.ENCODE, false));
                if (!container.carriesSeveralTracks()) {
                    break;
                }
            }
            return plans;
        }

        /**
         * Plans the text subtitle stream made of the closed captions the selected video track carries, when the
         * options ask for it.
         *
         * @param video the selected video track, or {@code null}
         * @param planned the subtitle streams already planned
         * @return the plan, or {@code null} when the options do not ask for the captions, the video codec carries none
         * or the container carries no text subtitles
         * @throws IllegalArgumentException if the options ask for the captions and the container carries no text
         * subtitles, or carries one track and a subtitle stream already fills it
         */
        private SubtitlePlan planCaptions(Track video, List<SubtitlePlan> planned) {
            var settings = options.subtitles();
            if (!settings.closedCaptions() || settings.mode() == StreamMode.DROP || video == null
                    || !ClosedCaptions.carries(video.codec())) {
                return null;
            }
            var container = options.container();
            var target = settings.codec().orElse(container.subtitleCodecs().isEmpty() ? null
                    : container.subtitleCodecs().getFirst());
            if (target == null || !container.supports(target)) {
                throw new IllegalArgumentException(container + " files cannot carry the closed captions as text subtitles");
            }
            if (!container.carriesSeveralTracks() && !planned.isEmpty()) {
                throw new IllegalArgumentException(container + " files hold one subtitle stream, already taken");
            }
            return new SubtitlePlan(ClosedCaptions.track(video), target, true, true);
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
            var settings = options.video();
            var container = options.container();
            var apply = switch (settings.orientation()) {
                case PRESERVE -> false;
                case APPLY -> true;
                default -> !container.storesOrientation();
            };
            var applyAspect = switch (settings.sampleAspect()) {
                case PRESERVE -> false;
                case APPLY -> true;
                default -> !container.storesSampleAspectRatio();
            };
            var frameRate = settings.effectiveFrameRate(track.frameRate().orElse(null));
            var reason = copyObstacle(track, apply);
            var copy = switch (settings.mode()) {
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
                return new VideoPlan(track, true, track.codec(), false, false, null);
            }
            for (var file : fileTracks(track)) {
                if (!VideoDecoders.isSupported(file.codec())) {
                    throw Artifacts.unsupported(file.codec().displayName(), "decodable", Artifacts.decoderOf(file.codec()));
                }
            }
            return new VideoPlan(track, false, pickEncoder(settings.codec(), container.videoCodecs(),
                    VideoEncoders::isSupported, "video"), apply, applyAspect, frameRate);
        }

        /**
         * Returns why the video stream cannot be copied.
         *
         * @param track the input track
         * @param apply whether the orientation is applied to the pixels
         * @return the reason, or {@code null} when it can be copied
         */
        private String copyObstacle(VideoTrack track, boolean apply) {
            var settings = options.video();
            var container = options.container();
            var codec = track.codec();
            if (!container.supports(codec)) {
                return container + " cannot carry " + codec.displayName();
            }
            if (container.needsConfig(codec) && track.codecConfig().isEmpty()) {
                return "the track has no codec configuration";
            }
            if (container.format() == ContainerFormat.Y4M && !Y4mMuxer.supports(track)) {
                return "YUV4MPEG2 cannot carry the track's raw video format";
            }
            if (settings.codec().isPresent() && settings.codec().get() != codec) {
                return "the options ask for " + settings.codec().get().displayName();
            }
            if (speed.numerator() != speed.denominator()) {
                return "the speed change needs decoding";
            }
            var mismatch = segmentMismatch(track);
            if (mismatch != null) {
                return mismatch;
            }
            if (apply && track.orientation() != Orientation.NORMAL) {
                return "the orientation must be applied to the pixels";
            }
            var aspect = VideoConfigs.sampleAspect(track);
            if (settings.sampleAspect() == SampleAspectMode.APPLY && aspect != null
                    && aspect.numerator() != aspect.denominator()) {
                return "the sample aspect ratio must be applied to the pixels";
            }
            var swaps = track.orientation().swapsDimensions();
            var width = swaps ? track.height() : track.width();
            var height = swaps ? track.width() : track.height();
            if (width <= 0 || height <= 0) {
                return settings.hasPictureOps() ? "the picture operations need decoding" : null;
            }
            if (settings.changesPicture(width, height, track.frameRate().orElse(null))) {
                return "the picture operations need decoding";
            }
            if (settings.deinterlaces(track.interlacing().orElse(null))) {
                return "deinterlacing needs decoding";
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
            var settings = options.audio();
            var container = options.container();
            var codec = track.codec();
            String reason = null;
            if (!container.supports(codec)) {
                reason = container + " cannot carry " + codec.displayName();
            } else if (container.needsConfig(codec) && track.codecConfig().isEmpty()) {
                reason = "the track has no codec configuration";
            } else if (container == OutputContainer.MPEG_TS && !TsMuxer.supports(track)) {
                reason = container + " carries PCM at 48, 96 or 192 kHz with one to eight channels only";
            } else if (container == OutputContainer.MPEG_PS && !PsMuxer.supports(track, PsMuxerOptions.defaults())) {
                reason = container + " carries PCM at 32, 44.1, 48 or 96 kHz with one to eight channels only";
            } else if ((codec == Codec.AMR_NB || codec == Codec.AMR_WB) && container != OutputContainer.AMR
                    && container != OutputContainer.WAV && track.layout().map(ChannelLayout::channels).orElse(1) > 1) {
                reason = container + " carries single-channel " + codec.displayName() + " only";
            } else if (settings.codec().isPresent() && settings.codec().get() != codec) {
                reason = "the options ask for " + settings.codec().get().displayName();
            } else if (settings.changesSamples(track.sampleRate(), track.layout().orElse(null))) {
                reason = "the sample operations need decoding";
            } else if (speed.numerator() != speed.denominator()) {
                reason = "the speed change needs decoding";
            } else if (cuts.length > 0) {
                reason = "removing pauses needs decoding";
            } else {
                reason = segmentMismatch(track);
            }
            var copy = switch (settings.mode()) {
                case COPY -> {
                    if (reason != null) {
                        throw new MediaException.Unsupported(codec.displayName(), "cannot copy the " + codec.displayName()
                                + " audio into " + container + ": " + reason);
                    }
                    yield true;
                }
                case ENCODE -> false;
                default -> reason == null && (container.copiesByDefault(codec) || settings.codec().isPresent());
            };
            if (copy) {
                return new AudioPlan(track, true, codec);
            }
            for (var file : fileTracks(track)) {
                if (!AudioDecoders.isSupported(file.codec())) {
                    throw Artifacts.unsupported(file.codec().displayName(), "decodable", Artifacts.decoderOf(file.codec()));
                }
            }
            return new AudioPlan(track, false, pickEncoder(settings.codec(), container.audioCodecs(),
                    AudioEncoders::isSupported, "audio"));
        }

        /**
         * Creates the stage of the video stream.
         *
         * <p>Pictures deeper than 8 bits are decoded at their depth, so that the color conversion and the
         * output depth have every bit the stream codes. When another output encodes the same track, the stage is
         * created without a decoder, for a {@link VideoFanOut} to feed.
         *
         * @param plan the plan of the video stream
         * @param outputId the output track identifier
         * @param muxer the output
         * @param tracks the tracks of the job's demuxer
         * @return the stage
         * @throws IllegalArgumentException if the subtitles burned in come from a track the input lacks or
         * that is not a text subtitle track
         */
        private Stage createVideoStage(VideoPlan plan, int outputId, OutputMuxer muxer, List<Track> tracks) {
            if (plan.copy()) {
                return new CopyStage(plan.track(), outputId, muxer, start, end);
            }
            var settings = options.video();
            var burnIn = settings.subtitleBurnIn().orElse(null);
            List<SubtitleCue> cues = null;
            var markup = SubtitleMarkup.SUBRIP;
            burnTrack = null;
            if (burnIn != null) {
                if (burnIn.cues().isPresent()) {
                    cues = new ArrayList<>(burnIn.cues().get());
                    markup = burnIn.markup();
                } else {
                    var track = burnInTrack(tracks, burnIn, plan.track());
                    markup = Subtitles.markupOf(track.codec());
                    if (forwardOnly()) {
                        cues = new ArrayList<>();
                        burnTrack = track;
                    } else {
                        cues = readCues(track);
                    }
                }
            }
            var decoderOptions = videoDecoderOptions();
            var shares = sharesVideoDecode(plan.track());
            var decoder = shares ? null : VideoDecoders.create(plan.track(), decoderOptions);
            var stage = new VideoEncodeStage(plan.track(), decoder, decoderOptions, plan.codec(), settings,
                    plan.applyOrientation(), plan.applyAspect(), plan.frameRate(), keepsAlpha(plan.codec()), outputId,
                    muxer, start, end, cancellation, cues, markup, burnTrack,
                    new VideoEncodeStage.Timing(speed, expected));
            if (shares) {
                sharedVideo = stage;
            }
            return stage;
        }

        /**
         * {@return whether another output also encodes a video track, so that the outputs share its decoder}
         *
         * @param track an audio track of the job's demuxer
         */
        private boolean sharesVideoDecode(VideoTrack track) {
            var count = 0;
            for (var output : outputs) {
                if (output.videoPlan != null && !output.videoPlan.copy() && output.videoPlan.track().id() == track.id()) {
                    count++;
                }
            }
            return count > 1;
        }

        /**
         * Returns the text subtitle track whose cues are burned in.
         *
         * @param tracks the tracks of the job's demuxer
         * @param burnIn the burn-in settings, which read a track
         * @param video the video track, whose input a track identifier names a track of
         * @return the track
         * @throws IllegalArgumentException if the track is missing or is not a text subtitle track
         */
        private Track burnInTrack(List<Track> tracks, SubtitleBurnIn burnIn, VideoTrack video) {
            if (burnIn.trackId().isPresent()) {
                var track = trackById(tracks, OptionalInt.of(inputOf(video.id())), burnIn.trackId().getAsInt());
                if (!track.codec().isSubtitle()) {
                    throw new IllegalArgumentException("track " + burnIn.trackId().getAsInt()
                            + " is not a text subtitle track to burn in");
                }
                return track;
            }
            for (var track : tracks) {
                if (track.codec().isSubtitle()) {
                    return track;
                }
            }
            throw new IllegalArgumentException("the input has no text subtitle track to burn in");
        }

        /**
         * Reads every cue of a subtitle track with a second demuxer of the inputs.
         *
         * @param track the subtitle track to read
         * @return the cues, in file order, in a list the caller may modify
         */
        private List<SubtitleCue> readCues(Track track) {
            var cues = new ArrayList<SubtitleCue>();
            try (var demuxer = openDemuxer()) {
                Track found = null;
                for (var candidate : demuxer.tracks()) {
                    if (candidate.id() == track.id()) {
                        found = candidate;
                    }
                }
                if (found == null) {
                    return cues;
                }
                if (demuxer instanceof MergedDemuxer mergedCues) {
                    mergedCues.select(Set.of(track.id()));
                }
                var decoder = new SubtitleDecoder(found);
                while (true) {
                    cancellation.check();
                    var packet = demuxer.read();
                    if (packet == null) {
                        break;
                    }
                    if (packet.trackId() == track.id()) {
                        cues.add(decoder.decode(packet));
                    }
                }
            }
            return cues;
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
                var format = options.container().format();
                var track = format == ContainerFormat.MATROSKA || format == ContainerFormat.MP4
                        || format == ContainerFormat.OGG ? TagCarry.withoutEmbeddedTags(plan.track()) : plan.track();
                return new CopyStage(track, outputId, muxer, start, end);
            }
            var timing = new AudioEncodeStage.Timing(speed.toDouble(), cuts, expected, !uniformAudio(plan.track()));
            return new AudioEncodeStage(plan.track(), plan.codec(), options.audio(), timing, outputId, muxer,
                    shared.limits(), start, end);
        }

        /**
         * {@return whether every file of a concatenation has an audio track of the first file's codec, rate, layout
         * and sample size at the track's position}
         *
         * @param track a track of the job's demuxer
         */
        private boolean uniformAudio(AudioTrack track) {
            for (var file : fileTracks(track)) {
                if (!(file instanceof AudioTrack audio) || audio.codec() != track.codec()
                        || audio.sampleRate() != track.sampleRate() || !audio.layout().equals(track.layout())
                        || audio.bitsPerSample() != track.bitsPerSample()) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Hands a whole-file copy into MP4, M4A or QuickTime to {@link Mp4Remuxer}.
         *
         * @return what was written
         */
        TranscodeResult remux() {
            var brand = remuxBrand(options.container());
            var builder = RemuxOptions.builder()
                    .limits(shared.limits())
                    .brand(brand);
            switch (options.tagMode()) {
                case COPY -> builder.copyTags();
                case DROP -> builder.noTags();
                case REPLACE -> builder.tags(options.tags().orElseThrow());
                case MERGE -> builder.mergeTags(options.tags().orElseThrow());
            }
            var sources = new ArrayList<Track>();
            if (video == null) {
                builder.noVideo();
            } else {
                builder.videoTrack(video.id());
                sources.add(video);
            }
            if (audio.isEmpty()) {
                builder.noAudio();
            } else {
                builder.audioTracks(audio.stream().mapToInt(Track::id).toArray());
                sources.addAll(audio);
            }
            var subtitleTracks = new ArrayList<Track>();
            for (var plan : subtitles) {
                subtitleTracks.add(plan.track());
            }
            if (subtitleTracks.isEmpty()) {
                builder.noSubtitles();
            } else {
                builder.subtitleTracks(subtitleTracks.stream().mapToInt(Track::id).toArray());
                sources.addAll(subtitleTracks);
            }
            var input = firstSource();
            var size = input.size();
            var watched = new WatchedSink(sink, cancellation, written -> {
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
            for (var index = 0; index < result.tracks().size(); index++) {
                streams.add(new TranscodedStream(sources.get(index).id(), result.tracks().get(index), true));
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
        TranscodeResult runAnimation() {
            var settings = options.video();
            var container = options.container();
            if (settings.mode() == StreamMode.DROP) {
                throw new IllegalArgumentException("the input is an animated image and the options drop video");
            }
            if (settings.mode() == StreamMode.COPY) {
                throw new MediaException.Unsupported("animated image", "an animated image cannot be copied into " + container
                        + ": encode it");
            }
            if (shared.silenceTrim().isPresent()) {
                throw new IllegalArgumentException("an animated image has no audio whose silence could be trimmed");
            }
            var codec = pickEncoder(settings.codec(), container.videoCodecs(), VideoEncoders::isSupported, "video");
            var input = firstSource();
            try (var reader = Images.openAnimation(input, ImageDecoderOptions.builder().limits(shared.limits()).build())) {
                var first = reader.read();
                if (first == null) {
                    throw new MediaException.Malformed("the animation has no frame");
                }
                var firstDuration = displayTime(first.durationMicros());
                var rate = settings.frameRate().orElse(new Rational(Math.max(1, Math.round(1_000_000.0 / firstDuration)), 1));
                var frameRate = settings.effectiveFrameRate(rate);
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
                    expected = scaledMicros(Math.max(0, (end >= 0 ? Math.min(end, length) : length) - start));
                }
                try (var muxer = new OutputMuxer(() -> container.createMuxer(sink), 1)) {
                    var burnIn = settings.subtitleBurnIn().orElse(null);
                    if (burnIn != null && burnIn.cues().isEmpty()) {
                        throw new IllegalArgumentException("an animated image has no subtitle track to burn in");
                    }
                    var stage = new VideoEncodeStage(track, null, null, codec, settings, true, true, frameRate,
                            keepsAlpha(codec), muxer.reserve(),
                            muxer, start, end, cancellation, burnIn == null ? null : new ArrayList<>(burnIn.cues().get()),
                            burnIn == null ? SubtitleMarkup.SUBRIP : burnIn.markup(), null,
                            new VideoEncodeStage.Timing(speed, expected));
                    try (stage) {
                        stages.add(stage);
                        var pts = 0L;
                        for (var frame = first; frame != null && !stage.done(); frame = reader.read()) {
                            cancellation.check();
                            var duration = displayTime(frame.durationMicros());
                            stage.acceptFrame(new VideoFrame(frame.picture(), pts, duration, true));
                            pts += duration;
                            report(false);
                        }
                        stage.finish();
                        muxer.finish();
                        var duration = report(true);
                        return new TranscodeResult(container, duration, List.of(new TranscodedStream(-1, stage.result().track(), false)),
                                false);
                    } finally {
                        stages.clear();
                    }
                }
            }
        }

        /**
         * {@return whether an encoded video stream keeps its transparency: the options do not flatten it, the
         * file stores alpha and the codec codes it}
         *
         * @param codec the codec of the encoded stream
         */
        private boolean keepsAlpha(Codec codec) {
            return !options.video().flatten() && options.container().storesAlpha() && VideoEncoders.encodesAlpha(codec);
        }
    }

    /**
     * {@return the tracks every file of the track's input has at its position: the track alone unless the input is a
     * concatenation}
     *
     * @param track a track of the job's demuxer
     */
    private List<Track> fileTracks(Track track) {
        var input = inputOf(track.id());
        if (!inputs.get(input).isConcatenation()) {
            return List.of(track);
        }
        return concatOf(input).fileTracks(merged == null ? track.id() : merged.originalId(track.id()));
    }

    /**
     * {@return the concatenation demuxer of an input}
     *
     * @param input the input, a concatenation
     */
    private ConcatDemuxer concatOf(int input) {
        return (ConcatDemuxer) (merged == null ? concat : merged.input(input));
    }

    /**
     * The demuxer of the job when it reads one concatenated input, or {@code null}.
     */
    private ConcatDemuxer concat;

    /**
     * Returns why a stream of a concatenation cannot be copied: its files differ in codec, codec configuration,
     * sampling rate and channel layout, or picture dimensions.
     *
     * @param track a track of the job's demuxer
     * @return the reason, or {@code null} when every file has the same codec, configuration and format
     */
    private String segmentMismatch(Track track) {
        var files = fileTracks(track);
        for (var index = 1; index < files.size(); index++) {
            var file = files.get(index);
            if (file.codec() != track.codec()) {
                return "file " + (index + 1) + " of the concatenation is " + file.codec().displayName();
            }
            if (!Arrays.equals(file.codecConfig().orElse(null), track.codecConfig().orElse(null))) {
                return "file " + (index + 1) + " of the concatenation has another codec configuration";
            }
            if (file instanceof AudioTrack audio && track instanceof AudioTrack first
                    && (audio.sampleRate() != first.sampleRate() || !audio.layout().equals(first.layout()))) {
                return "file " + (index + 1) + " of the concatenation has another sampling rate or channel layout";
            }
            if (file instanceof VideoTrack picture && track instanceof VideoTrack first
                    && (picture.width() != first.width() || picture.height() != first.height())) {
                return "file " + (index + 1) + " of the concatenation has other dimensions";
            }
        }
        return null;
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
