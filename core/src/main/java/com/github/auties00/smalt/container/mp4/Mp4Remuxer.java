package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.internal.codec.Jp2Frames;
import com.github.auties00.smalt.internal.io.LocalSource;
import com.github.auties00.smalt.internal.tags.TagCarry;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.TagMode;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Copies the tracks of an ISO base media, Matroska, MPEG transport stream, AVI or Ogg file into a
 * fast-start MP4, M4A or QuickTime movie without re-encoding.
 *
 * <p>{@link #remux} detects the input container by its magic, keeps the tracks {@link RemuxOptions}
 * selects (by default the first video track and the first audio track; several audio tracks and
 * subtitle tracks when asked), and copies their packets unchanged into an {@link Mp4Muxer}
 * with fast start, so the output has its {@code moov} before the media data. For an ISO base media
 * input, whose sample index is known up front, the output layout is planned from the index first,
 * so the {@code moov} is written before the media data and the media data is written once,
 * straight to the output; other inputs go through the muxer's spool, except an input larger than
 * the spool's memory that is local (in memory or in a file, see {@link LocalSource}), whose layout
 * is planned by demuxing it once before it is demuxed again to write the media data. An input that
 * is not local, such as one fetched over the network, is always read once, and a layout that
 * depends on the sample bytes (an AC-3, E-AC-3 or AMR configuration, a VP8, VP9, AV1 or MPEG-4
 * Part 2 configuration derived from the first key frame, ProRes and DNxHD, Motion-JPEG in a
 * QuickTime file, the subtitle samples of a non-MP4 input) is spooled. Presentation timing is
 * preserved: edit lists, encoder priming ({@link AudioTrack#skipSamples()}), composition offsets
 * and the display orientation carry over; an ISO base media track whose edit list has several
 * segments keeps its samples and its edit list as they are, and a track with several sample
 * descriptions keeps them too; the samples of an ISO base media subtitle track are copied as they
 * are. Timing is repaired where the input is inconsistent: decoding timestamps that step backwards
 * are held, video stored with presentation timestamps only (Matroska) gets decoding timestamps
 * derived from them, and audio whose stored timestamps use a time base other than its sampling
 * rate (Matroska, a millisecond by default; transport streams, 90 kHz; AVI) is retimed to exact
 * sample counts read from the frames.
 *
 * <p>The tags, cover art, chapters and track tags of the input are carried over as
 * {@link RemuxOptions#tagMode()} selects, in the forms {@link Mp4Muxer#setTags} writes.
 *
 * <p>The output carries the codecs {@link Mp4Muxer#supports} lists for the brand, PCM needing its
 * sampling rate and channel layout; a selected track with any other codec raises
 * {@link MediaException.Unsupported} naming the codec, so the caller can transcode it instead.
 */
public final class Mp4Remuxer {
    private Mp4Remuxer() {
        throw new AssertionError();
    }

    /**
     * Remuxes the first video and audio tracks of an input into a fast-start MP4 under the default
     * limits.
     *
     * @param input the input; not closed
     * @param output the sink to write, from its position; not closed
     * @return what was written
     * @throws MediaException.Unsupported if the input container is not recognized, it has no
     * selectable track, or a selected track's codec cannot be stored in MP4
     * @throws MediaException.Malformed if the input is corrupt
     * @throws MediaException.LimitExceeded if the input exceeds the limits
     * @throws MediaException.Io if reading the input or writing the output fails
     * @see #remux(MediaSource, MediaSink, RemuxOptions)
     */
    public static RemuxResult remux(MediaSource input, MediaSink output) {
        return remux(input, output, RemuxOptions.defaults());
    }

    /**
     * Remuxes an input into a fast-start MP4, M4A or QuickTime movie.
     *
     * <p>The output is written strictly in order, so any sink works, a streaming one included.
     *
     * @param input the input; not closed
     * @param output the sink to write, from its position; not closed
     * @param options the track selection, brand and limits
     * @return what was written
     * @throws IllegalArgumentException if a selected track identifier does not name a track of the
     * right kind (a subtitle track, see {@link RemuxOptions#includesSubtitles()}, for a subtitle
     * selection)
     * @throws MediaException.Unsupported if the input container is not recognized, it has no
     * selectable track, or a selected track's codec cannot be stored in MP4
     * @throws MediaException.Malformed if the input is corrupt
     * @throws MediaException.LimitExceeded if the input exceeds the limits
     * @throws MediaException.Io if reading the input or writing the output fails
     */
    public static RemuxResult remux(MediaSource input, MediaSink output, RemuxOptions options) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        var format = detect(input);
        var start = output.position();
        try (var demuxer = open(input, format, options)) {
            var copies = prepare(demuxer, options, format);
            var tags = tags(input, demuxer, copies, options);
            if (demuxer instanceof Mp4Demuxer indexed) {
                for (var copy : copies) {
                    copy.edits = indexed.useMediaSamples(copy.input.id());
                }
            }
            var muxerOptions = Mp4MuxerOptions.builder()
                    .brand(options.brand())
                    .fastStart(true)
                    .temporaryDirectory(options.temporaryDirectory().orElse(null))
                    .build();
            try (var muxer = new Mp4Muxer(output, muxerOptions)) {
                for (var copy : copies) {
                    muxer.addTrack(copy.output);
                    if (copy.edits != null) {
                        muxer.setEdits(copy.output.id(), copy.edits);
                    }
                }
                TagCarry.apply(muxer, tags, options.tagMode(), options.tags().orElse(null));
                long packets;
                try {
                    if (demuxer instanceof Mp4Demuxer indexed && plannable(copies, format, options.brand())
                            && copies.stream().noneMatch(copy -> indexed.convertsSamples(copy.input.id()))) {
                        muxer.streamAfter(plan(indexed, copies, muxerOptions, tags, options));
                    } else if (format != ContainerFormat.MP4 && input.size() > MdatSpool.MEMORY_LIMIT && LocalSource.isLocal(input)) {
                        var header = planByReading(input, format, options, muxerOptions, tags);
                        if (header != null) {
                            muxer.streamAfter(header);
                        }
                    }
                    packets = copyPackets(demuxer, muxer, copies);
                    muxer.finish();
                } catch (MediaException exception) {
                    throw exception;
                } catch (IllegalArgumentException | IllegalStateException | ArithmeticException exception) {
                    throw new MediaException.Malformed("cannot remux the input: " + exception.getMessage(), exception);
                } catch (RuntimeException exception) {
                    throw new MediaException.Malformed("cannot remux the input: " + exception, exception);
                }
                var tracks = new ArrayList<Track>();
                for (var copy : copies) {
                    tracks.add(copy.output);
                }
                var end = output.position();
                return new RemuxResult(format, tracks, packets, muxer.durationMicros(), end - start);
            }
        }
    }

    /**
     * Resolves the tags of the output.
     *
     * @param input the input; not closed
     * @param demuxer the demuxer of the input, at its start
     * @param copies the track plans
     * @param options the remux options
     * @return the tags on the output's terms
     */
    private static MediaTags tags(MediaSource input, Demuxer demuxer, List<Copy> copies, RemuxOptions options) {
        var mode = options.tagMode();
        var outputIds = new HashMap<Integer, Integer>();
        for (var copy : copies) {
            outputIds.put(copy.input.id(), copy.output.id());
        }
        var inputTags = mode == TagMode.COPY || mode == TagMode.MERGE
                ? TagCarry.read(input, demuxer, options.limits()) : null;
        return TagCarry.resolve(mode, options.tags().orElse(null), inputTags,
                new TagCarry.Mapping(outputIds, Set.of(), false, false, 0, -1));
    }

    /**
     * Opens the demuxer of an input.
     *
     * @param input the input to demux; not closed
     * @param format the detected container
     * @param options the remux options
     * @return the demuxer
     */
    private static Demuxer open(MediaSource input, ContainerFormat format, RemuxOptions options) {
        var demuxerOptions = DemuxerOptions.builder().limits(options.limits())
                .decryptionKeys(options.decryptionKeys()).build();
        return format == ContainerFormat.MP4 ? new Mp4Demuxer(input, demuxerOptions)
                : Containers.openDemuxer(input, demuxerOptions);
    }

    /**
     * Selects the tracks to copy and plans their conversion.
     *
     * @param demuxer the input
     * @param options the remux options
     * @param format the detected container
     * @return the track plans: the video track, then the audio tracks, then the subtitle tracks
     * @throws MediaException.Unsupported if nothing can be copied or a codec cannot be stored
     */
    private static List<Copy> prepare(Demuxer demuxer, RemuxOptions options, ContainerFormat format) {
        var brand = options.brand();
        var tracks = demuxer.tracks();
        var copies = new ArrayList<Copy>();
        if (brand != Mp4MuxerOptions.Brand.M4A) {
            var video = select(tracks, TrackKind.VIDEO, options.videoSelection(), brand);
            if (!video.isEmpty()) {
                copies.add(prepareVideo(video.getFirst(), copies.size() + 1, format, brand));
            }
        }
        for (var audio : select(tracks, TrackKind.AUDIO, options.audioSelection(), brand)) {
            copies.add(prepareAudio(audio, copies.size() + 1, format, brand));
        }
        if (brand != Mp4MuxerOptions.Brand.M4A) {
            for (var text : select(tracks, TrackKind.DATA, options.subtitleSelection(), brand)) {
                copies.add(prepareText(text, copies.size() + 1, brand));
            }
        }
        if (copies.isEmpty()) {
            throw new MediaException.Unsupported("no audio or video", "the input has no track to remux");
        }
        return copies;
    }

    /**
     * Returns whether an input is an ISO base media file whose {@code moov} precedes its first
     * {@code mdat}, so that it can be played while it downloads.
     *
     * @param input the input; not closed
     * @return {@code true} for a fast-start ISO base media file, {@code false} for any other file
     * @throws MediaException.Io if reading the input fails
     */
    public static boolean isFastStart(MediaSource input) {
        Objects.requireNonNull(input, "input");
        try {
            if (detect(input) != ContainerFormat.MP4) {
                return false;
            }
            return TopLevelScan.scan(input, true).isFastStart();
        } catch (MediaException.Malformed | MediaException.Unsupported exception) {
            return false;
        }
    }

    /**
     * Identifies the container of an input by its first bytes.
     *
     * @param input the input, read from its start; not closed
     * @return {@link ContainerFormat#MP4}, {@link ContainerFormat#MATROSKA},
     * {@link ContainerFormat#MPEG_TS}, {@link ContainerFormat#AVI} or {@link ContainerFormat#OGG}
     * @throws MediaException.Unsupported if the input is none of those
     */
    static ContainerFormat detect(MediaSource input) {
        return Containers.detect(input)
                .filter(format -> format == ContainerFormat.MP4 || format == ContainerFormat.MATROSKA
                        || format == ContainerFormat.MPEG_TS || format == ContainerFormat.AVI || format == ContainerFormat.OGG)
                .orElseThrow(() -> new MediaException.Unsupported("unknown container",
                        "the input is not an ISO base media, Matroska, MPEG transport stream, AVI or Ogg file"));
    }

    /**
     * Selects the tracks of a kind.
     *
     * @param tracks the input tracks
     * @param kind the kind wanted; {@link TrackKind#DATA} selects subtitle tracks
     * @param selection the selection of the options
     * @param brand the output brand: the first or every subtitle track is selected among those it can carry
     * @return the tracks, in output order; empty when none is selected or the input has none of
     * the kind
     * @throws IllegalArgumentException if an identifier names no track of the kind
     */
    private static List<Track> select(List<Track> tracks, TrackKind kind, RemuxOptions.Selection selection,
                                      Mp4MuxerOptions.Brand brand) {
        var result = new ArrayList<Track>();
        switch (selection.mode()) {
            case NONE -> {
            }
            case FIRST, ALL -> {
                for (var track : tracks) {
                    if (matches(track, kind) && (kind != TrackKind.DATA || !track.codec().isBitmapSubtitle()
                            && !track.codec().isCaption() || Mp4Muxer.supports(brand, track.codec()))) {
                        result.add(track);
                        if (selection.mode() == RemuxOptions.Mode.FIRST) {
                            break;
                        }
                    }
                }
            }
            case IDS -> {
                for (var id : selection.ids()) {
                    Track found = null;
                    for (var track : tracks) {
                        if (track.id() == id && matches(track, kind)) {
                            found = track;
                        }
                    }
                    if (found == null) {
                        throw new IllegalArgumentException("the input has no "
                                + (kind == TrackKind.DATA ? "subtitle" : kind.name().toLowerCase(Locale.ROOT))
                                + " track " + id);
                    }
                    result.add(found);
                }
            }
        }
        return result;
    }

    /**
     * Returns whether a track is of a selected kind.
     *
     * @param track the input track
     * @param kind the kind; {@link TrackKind#DATA} stands for subtitle tracks, of a text or bitmap subtitle
     * format, TTML or a caption format
     * @return {@code true} when the track has the kind
     */
    private static boolean matches(Track track, TrackKind kind) {
        var codec = track.codec();
        return kind == TrackKind.DATA ? codec.isSubtitle() || codec == Codec.TTML || codec.isBitmapSubtitle()
                || codec.isCaption() : track.kind() == kind;
    }

    /**
     * Holds the plan for copying one track.
     */
    private static final class Copy {
        /**
         * The input track.
         */
        private final Track input;

        /**
         * The output track description.
         */
        private final Track output;

        /**
         * The retimer converting Matroska audio timestamps, or {@code null}.
         */
        private final AudioRetimer retimer;

        /**
         * The last decoding timestamp written, for repairs.
         */
        private long lastDts = Long.MIN_VALUE;

        /**
         * The edit list copied from an ISO base media input whose samples are copied without it,
         * or {@code null} to derive the output's from the timestamps.
         */
        private long[][] edits;

        /**
         * Constructs a plan.
         *
         * @param input the input track
         * @param output the output track
         * @param retimer the retimer, or {@code null}
         */
        private Copy(Track input, Track output, AudioRetimer retimer) {
            this.input = input;
            this.output = output;
            this.retimer = retimer;
        }
    }

    /**
     * Plans the copy of a video track.
     *
     * @param track the input track
     * @param id the output identifier
     * @param format the input container
     * @param brand the output brand
     * @return the plan
     * @throws MediaException.Unsupported if the codec cannot be stored or its configuration is
     * missing
     */
    private static Copy prepareVideo(Track track, int id, ContainerFormat format, Mp4MuxerOptions.Brand brand) {
        var video = (VideoTrack) track;
        checkCodec(video.codec(), brand);
        if (video.codec() == Codec.H264 || video.codec() == Codec.HEVC) {
            var config = video.codecConfig().orElse(null);
            if (config == null || config.length < 7 || config[0] != 1) {
                throw new MediaException.Unsupported(video.codec().displayName() + " without configuration record",
                        "the " + video.codec().displayName() + " track has no decoder configuration record (" + format + ")");
            }
        }
        var output = video.toBuilder().id(id).build();
        return new Copy(track, output, null);
    }

    /**
     * Plans the copy of an audio track.
     *
     * <p>The packets of a Matroska or AVI input, whose timestamps are rounded to their own clock
     * whatever time base they are expressed in, and those of another input other than ISO base
     * media whose time base is not one tick per sample, are retimed to exact sample counts.
     *
     * @param track the input track
     * @param id the output identifier
     * @param format the input container
     * @param brand the output brand
     * @return the plan
     * @throws MediaException.Unsupported if the codec cannot be stored or the configuration, sampling
     * rate or channel layout it needs is missing
     */
    private static Copy prepareAudio(Track track, int id, ContainerFormat format, Mp4MuxerOptions.Brand brand) {
        var audio = (AudioTrack) track;
        var codec = audio.codec();
        checkCodec(codec, brand);
        switch (codec) {
            case AAC, OPUS, ALAC, FLAC -> {
                if (audio.codecConfig().filter(config -> config.length > 0).isEmpty()) {
                    throw new MediaException.Unsupported(codec.displayName() + " without configuration",
                            "the " + codec.displayName() + " track has no codec configuration");
                }
            }
            default -> {
                if (codec.isPcm() && (audio.sampleRate() <= 0 || audio.layout().isEmpty())) {
                    throw new MediaException.Unsupported(codec.displayName() + " without sample format",
                            "the " + codec.displayName() + " track declares no sampling rate or channel layout");
                }
            }
        }
        var rate = codec == Codec.OPUS ? 48000 : audio.sampleRate();
        var coarse = format == ContainerFormat.MATROSKA || format == ContainerFormat.AVI
                || !audio.timeBase().equals(Rational.timeBaseOf(rate));
        if (format != ContainerFormat.MP4 && rate > 0 && coarse) {
            var retimer = new AudioRetimer(audio, rate);
            var duration = audio.duration() > 0 ? audio.timeBase().rescale(audio.duration(), retimer.timeBase()) : -1;
            var output = TagCarry.withoutEmbeddedTags(audio.toBuilder().id(id).timeBase(retimer.timeBase())
                    .duration(duration).build());
            return new Copy(track, output, retimer);
        }
        var output = TagCarry.withoutEmbeddedTags(audio.toBuilder().id(id).build());
        return new Copy(track, output, null);
    }

    /**
     * Plans the copy of a subtitle track.
     *
     * @param track the input track
     * @param id the output identifier
     * @param brand the output brand
     * @return the plan
     * @throws MediaException.Unsupported if the codec cannot be stored
     */
    private static Copy prepareText(Track track, int id, Mp4MuxerOptions.Brand brand) {
        checkCodec(track.codec(), brand);
        if (!(track instanceof DataTrack data)) {
            throw new MediaException.Unsupported(track.codec().displayName(), "the subtitle track is not a data track");
        }
        return new Copy(track, data.toBuilder().id(id).build(), null);
    }

    /**
     * Checks that the output brand carries a codec.
     *
     * @param codec the codec of a selected track
     * @param brand the output brand
     * @throws MediaException.Unsupported if it does not, naming the codec
     */
    private static void checkCodec(Codec codec, Mp4MuxerOptions.Brand brand) {
        if (!Mp4Muxer.supports(brand, codec)) {
            var hint = Mp4Muxer.supports(Mp4MuxerOptions.Brand.MP4, codec)
                    ? ", which the MP4 and QuickTime brands carry"
                    : Mp4Muxer.supports(Mp4MuxerOptions.Brand.QUICKTIME, codec)
                    ? ", which the QuickTime brand carries" : "";
            throw new MediaException.Unsupported(codec.displayName(),
                    "MP4 remuxing with the " + brand + " brand does not carry " + codec.displayName() + hint);
        }
    }

    /**
     * Returns whether the output layout can be planned from sample sizes and timestamps alone,
     * without the sample bytes the muxer reads for some sample descriptions.
     *
     * <p>The layout cannot be planned for an MP3 or MP2 track that declares no sampling rate or
     * channel layout, an AC-3, E-AC-3 or AMR track (whose configuration boxes come from the
     * frames), a VP8, VP9, AV1, MPEG-4 Part 2, MPEG-1 video or MPEG-2 video track without a configuration
     * (derived from the first key frame), a ProRes or DNxHD track (whose sample entry reads the first frame),
     * Motion-JPEG in a QuickTime file (whose sample entry type depends on the frames), Motion JPEG 2000 in a Motion
     * JPEG 2000 file (whose samples are rewritten) or without a JP2 Header box configuration, and the
     * subtitle tracks of an input other than ISO base media (whose cues are cut into new samples).
     *
     * @param copies the track plans
     * @param format the input container
     * @param brand the output brand
     * @return {@code true} when planning is possible
     */
    private static boolean plannable(List<Copy> copies, ContainerFormat format, Mp4MuxerOptions.Brand brand) {
        for (var copy : copies) {
            var output = copy.output;
            var plannable = switch (output.codec()) {
                case MP3, MP2 -> output instanceof AudioTrack audio && audio.sampleRate() > 0 && audio.layout().isPresent();
                case AC3, EAC3, AMR_NB, AMR_WB, PRORES, DNXHD -> false;
                case VP8, VP9, AV1, MPEG4_PART2, MPEG1_VIDEO, MPEG2_VIDEO ->
                        output.codecConfig().filter(config -> config.length > 0).isPresent();
                case MJPEG -> brand != Mp4MuxerOptions.Brand.QUICKTIME;
                case JPEG2000 -> brand != Mp4MuxerOptions.Brand.MJ2
                        && Jp2Frames.isHeader(output.codecConfig().orElse(null));
                case MOV_TEXT, WEBVTT, TTML -> format == ContainerFormat.MP4;
                case CEA_608, CEA_708 -> false;
                default -> true;
            };
            if (!plannable) {
                return false;
            }
        }
        return true;
    }

    /**
     * Plans the fast-start layout of the output from the input's sample index, then rewinds the
     * input.
     *
     * <p>The plan lets the muxer write the {@code moov} first and every chunk straight after it,
     * so the media data is written once instead of being spooled and copied.
     *
     * @param demuxer the input
     * @param copies the track plans
     * @param options the options of the output muxer
     * @param tags the tags of the output
     * @param remuxOptions the remux options
     * @return the header the output muxer writes before the media data
     */
    private static byte[] plan(Mp4Demuxer demuxer, List<Copy> copies, Mp4MuxerOptions options, MediaTags tags,
                               RemuxOptions remuxOptions) {
        var planner = Mp4Muxer.planner(options);
        for (var copy : copies) {
            planner.addTrack(copy.output);
            if (copy.edits != null) {
                planner.setEdits(copy.output.id(), copy.edits);
            }
        }
        TagCarry.apply(planner, tags, remuxOptions.tagMode(), remuxOptions.tags().orElse(null));
        var sample = new Mp4Demuxer.SampleInfo();
        while (demuxer.nextSample(sample)) {
            var copy = find(copies, sample.trackId);
            if (copy != null) {
                if (sample.newConfig != null) {
                    planner.changeCodecConfig(copy.output.id(), sample.newConfig);
                }
                var dts = repairDts(copy, sample.dts);
                planner.planSample(copy.output.id(), sample.size, repairPts(copy, sample.pts, dts), dts, sample.duration,
                        sample.keyframe);
            }
        }
        var header = planner.finishPlan();
        demuxer.rewind();
        for (var copy : copies) {
            copy.lastDts = Long.MIN_VALUE;
        }
        return header;
    }

    /**
     * Plans the fast-start layout of the output by demuxing the whole input once, for inputs
     * without a sample index; the input is then demuxed again to write the media data straight
     * to the output.
     *
     * <p>Reading a large local input twice costs less than spooling its media data to a temporary
     * file and copying it back; an input that is not local is spooled instead, since reading it
     * twice could transfer it twice.
     *
     * @param input the input, demuxed once to plan the layout; not closed
     * @param format the detected container
     * @param options the remux options
     * @param muxerOptions the options of the output muxer
     * @param tags the tags of the output
     * @return the header the output muxer writes before the media data, or {@code null} when a
     * sample description depends on sample bytes and the layout cannot be planned
     */
    private static byte[] planByReading(MediaSource input, ContainerFormat format, RemuxOptions options,
                                        Mp4MuxerOptions muxerOptions, MediaTags tags) {
        try (var demuxer = open(input, format, options)) {
            var copies = prepare(demuxer, options, format);
            if (!plannable(copies, format, options.brand())) {
                return null;
            }
            var planner = Mp4Muxer.planner(muxerOptions);
            for (var copy : copies) {
                planner.addTrack(copy.output);
            }
            TagCarry.apply(planner, tags, options.tagMode(), options.tags().orElse(null));
            transfer(demuxer, copies, packet -> {
                packet.sideData().newCodecConfig().ifPresent(config -> planner.changeCodecConfig(packet.trackId(), config));
                planner.planSample(packet.trackId(), packet.size(), packet.pts(), packet.dts(), packet.duration(),
                        packet.keyframe());
            });
            return planner.finishPlan();
        }
    }

    /**
     * Returns the plan of the track a packet belongs to.
     *
     * @param copies the track plans
     * @param trackId the input track identifier
     * @return the plan, or {@code null} when the track is not copied
     */
    private static Copy find(List<Copy> copies, int trackId) {
        for (var copy : copies) {
            if (copy.input.id() == trackId) {
                return copy;
            }
        }
        return null;
    }

    /**
     * Holds a decoding timestamp that would step backwards at the previous one.
     *
     * @param copy the track plan, whose last decoding timestamp is updated
     * @param dts the decoding timestamp, or {@link Packet#NO_TIMESTAMP}
     * @return the repaired decoding timestamp
     */
    private static long repairDts(Copy copy, long dts) {
        if (dts == Packet.NO_TIMESTAMP) {
            return dts;
        }
        if (copy.lastDts != Long.MIN_VALUE && dts < copy.lastDts) {
            dts = copy.lastDts;
        }
        copy.lastDts = dts;
        return dts;
    }

    /**
     * Keeps an audio presentation timestamp from preceding its decoding timestamp.
     *
     * @param copy the track plan
     * @param pts the presentation timestamp, or {@link Packet#NO_TIMESTAMP}
     * @param dts the repaired decoding timestamp, or {@link Packet#NO_TIMESTAMP}
     * @return the repaired presentation timestamp
     */
    private static long repairPts(Copy copy, long pts, long dts) {
        if (pts != Packet.NO_TIMESTAMP && dts != Packet.NO_TIMESTAMP && pts < dts && copy.input.kind() == TrackKind.AUDIO) {
            return dts;
        }
        return pts;
    }

    /**
     * Copies the packets of the selected tracks, repairing their timing.
     *
     * @param demuxer the input
     * @param muxer the output
     * @param copies the track plans
     * @return the number of packets copied
     */
    private static long copyPackets(Demuxer demuxer, Mp4Muxer muxer, List<Copy> copies) {
        if (demuxer instanceof Mp4Demuxer indexed) {
            return copySamples(indexed, muxer, copies);
        }
        return transfer(demuxer, copies, muxer::writePacket);
    }

    /**
     * Copies the samples of an MP4 input straight from the demuxer's buffers into the muxer's
     * chunks, with the timestamp repairs of {@link #transfer}.
     *
     * <p>No packet or payload array is allocated per sample; the samples, their order, their
     * timestamps and the exceptions are those of the packet path.
     *
     * @param demuxer the demuxer of the MP4 input
     * @param muxer the muxer, with every output track declared
     * @param copies the selected tracks
     * @return the number of samples written
     */
    private static long copySamples(Mp4Demuxer demuxer, Mp4Muxer muxer, List<Copy> copies) {
        var count = 0L;
        var sample = new Mp4Demuxer.SampleInfo();
        while (demuxer.readSample(sample)) {
            var copy = find(copies, sample.trackId);
            if (copy == null) {
                continue;
            }
            if (sample.newConfig != null) {
                muxer.changeCodecConfig(copy.output.id(), sample.newConfig);
            }
            var dts = repairDts(copy, sample.dts);
            var pts = repairPts(copy, sample.pts, dts);
            muxer.writeSample(copy.output.id(), sample.data, sample.dataOffset, sample.size, pts, dts, sample.duration,
                    sample.keyframe);
            count++;
        }
        return count;
    }

    /**
     * Reads the packets of the selected tracks, repairs their timing, and hands them to a sink
     * with their output track identifiers.
     *
     * @param demuxer the input
     * @param copies the track plans
     * @param sink receives the repaired packets
     * @return the number of packets handed over
     */
    private static long transfer(Demuxer demuxer, List<Copy> copies, Consumer<Packet> sink) {
        var count = 0L;
        Packet packet;
        while ((packet = demuxer.read()) != null) {
            var copy = find(copies, packet.trackId());
            if (copy == null) {
                continue;
            }
            Packet repaired;
            if (copy.retimer != null) {
                repaired = copy.retimer.retime(packet, copy.output.id());
            } else {
                var dts = repairDts(copy, packet.dts());
                var pts = repairPts(copy, packet.pts(), dts);
                repaired = new Packet(copy.output.id(), packet.data(), pts, dts, packet.duration(), packet.flags(),
                        packet.sideData());
            }
            sink.accept(repaired);
            count++;
        }
        return count;
    }
}
