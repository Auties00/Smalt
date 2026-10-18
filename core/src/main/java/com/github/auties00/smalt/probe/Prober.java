package com.github.auties00.smalt.probe;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.internal.TrackStatistics;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.internal.codec.Ac3Configs;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.codec.CodecConfigs;
import com.github.auties00.smalt.internal.codec.VideoConfigs;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Implements {@link com.github.auties00.smalt.Media#probe}: recognizes the file, then describes an
 * image from its headers or a container from its demuxer and the codec configuration records of
 * its tracks.
 *
 * <p>{@link ProbeOptions} registers {@link #probe(MediaSource, ProbeOptions)} with
 * {@link com.github.auties00.smalt.internal.probe.ProbeAccess} when it is initialized, so the
 * facade outside this package reaches it without making it public API.
 */
final class Prober {
    /**
     * The largest number of packets read to find the first key frame of tracks without a
     * configuration record.
     */
    private static final int MAX_PEEKED_PACKETS = 256;

    /**
     * Prevents instantiation.
     */
    private Prober() {
        throw new AssertionError();
    }

    /**
     * Describes a media file.
     *
     * @param source the input
     * @param options the probe options
     * @return the description
     * @throws MediaException.Malformed if the headers are corrupt
     * @throws MediaException.Unsupported if the input is neither a recognized image nor a
     * recognized container
     * @throws MediaException.Io if reading the source fails
     */
    static MediaInfo probe(MediaSource source, ProbeOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        var size = source.size();
        var image = Images.detect(source).orElse(null);
        if (image != null) {
            var header = ImageHeaders.read(source, image);
            var fields = new StreamFields(0, -1, null, header.durationMicros, -1, header.frames, Map.of());
            var stream = new ImageStreamInfo(fields, image, header.width, header.height, header.orientation,
                    header.bitDepth, header.alpha, header.animated, header.loops);
            var bitRate = size >= 0 && header.durationMicros > 0 ? size * 8_000_000L / header.durationMicros : -1;
            return new MediaInfo(null, image, size, header.durationMicros, bitRate, List.of(stream), header.tags, List.of(),
                    List.of());
        }
        var format = Containers.detect(source).orElseThrow(() -> new MediaException.Unsupported("unknown format",
                "the input is neither a recognized image nor a recognized container"));
        try (var demuxer = Containers.openDemuxer(source, DemuxerOptions.builder().limits(options.limits()).build())) {
            return describe(demuxer, size, options);
        }
    }

    /**
     * Describes an open container.
     *
     * @param demuxer the open demuxer of the input
     * @param size the file size, or {@code -1}
     * @param options the probe options
     * @return the description
     */
    private static MediaInfo describe(Demuxer demuxer, long size, ProbeOptions options) {
        var tracks = demuxer.tracks();
        var firstFrames = options.readFirstFrames() ? firstKeyFrames(demuxer, tracks) : Map.<Integer, byte[]>of();
        var statistics = TrackStatistics.of(demuxer);
        var streams = new ArrayList<StreamInfo>(tracks.size());
        var longest = -1L;
        for (var index = 0; index < tracks.size(); index++) {
            var track = tracks.get(index);
            var duration = track.duration() >= 0 ? safeMicros(track.timeBase(), track.duration()) : -1;
            var frames = statistics != null ? statistics.sampleCount().applyAsLong(track.id()) : -1;
            var bytes = statistics != null ? statistics.sampleBytes().applyAsLong(track.id()) : -1;
            var bitRate = bytes >= 0 && duration > 0 ? bytes * 8_000_000L / duration : -1;
            var fields = new StreamFields(index, track.id(), track.language().orElse(null), duration, -1, frames,
                    TagNames.normalize(demuxer.trackMetadata(track.id())));
            streams.add(switch (track) {
                case VideoTrack video -> {
                    longest = Math.max(longest, duration);
                    yield video(withBitRate(fields, bitRate), video, firstFrames.get(track.id()));
                }
                case AudioTrack audio -> {
                    longest = Math.max(longest, duration);
                    yield audio(withBitRate(fields, bitRate >= 0 ? bitRate : pcmBitRate(audio)), audio,
                            firstFrames.get(track.id()));
                }
                case DataTrack data -> new DataStreamInfo(withBitRate(fields, bitRate), data.codec(), data.handler().orElse(null));
            });
        }
        var duration = demuxer.durationMicros() >= 0 ? demuxer.durationMicros() : longest;
        var bitRate = size >= 0 && duration > 0 ? size * 8_000_000L / duration : -1;
        return new MediaInfo(demuxer.format(), null, size, duration, bitRate, streams, TagNames.normalize(demuxer.metadata()),
                demuxer.chapters(), options.attachedPictures() ? demuxer.attachedPictures() : List.of());
    }

    /**
     * Converts a duration to microseconds without overflowing.
     *
     * @param timeBase the unit of {@code value}
     * @param value the duration in that time base
     * @return the duration in microseconds, or {@code -1} when it does not fit
     */
    private static long safeMicros(Rational timeBase, long value) {
        try {
            return timeBase.toMicros(value);
        } catch (ArithmeticException exception) {
            return -1;
        }
    }

    /**
     * {@return a copy of shared fields with another bit rate}
     *
     * @param fields the fields to copy
     * @param bitRate the bit rate in bits per second, or {@code -1} when unknown
     */
    private static StreamFields withBitRate(StreamFields fields, long bitRate) {
        return new StreamFields(fields.index(), fields.trackId(), fields.language(), fields.durationMicros(), bitRate,
                fields.frameCount(), fields.tags());
    }

    /**
     * {@return the bit rate of uncompressed audio in bits per second, or {@code -1} for compressed
     * audio or an incomplete description}
     *
     * @param track an audio track
     */
    private static long pcmBitRate(AudioTrack track) {
        if (!track.codec().isPcm() || track.bitsPerSample() <= 0 || track.sampleRate() <= 0) {
            return -1;
        }
        var channels = track.layout().map(ChannelLayout::channels).orElse(0);
        return channels <= 0 ? -1 : (long) track.sampleRate() * channels * track.bitsPerSample();
    }

    /**
     * Reads the first packets until every video track without a usable configuration record, and
     * every AC-3 and E-AC-3 track, met its first key frame.
     *
     * @param demuxer the demuxer, at its start
     * @param tracks the tracks to look for a key frame of
     * @return the first key frame of each such track, by track identifier
     */
    private static Map<Integer, byte[]> firstKeyFrames(Demuxer demuxer, List<Track> tracks) {
        var wanted = new HashMap<Integer, Codec>();
        for (var track : tracks) {
            if (track instanceof VideoTrack video && needsKeyFrame(video)
                    || track.codec() == Codec.AC3 || track.codec() == Codec.EAC3) {
                wanted.put(track.id(), track.codec());
            }
        }
        var result = new HashMap<Integer, byte[]>();
        for (var count = 0; count < MAX_PEEKED_PACKETS && result.size() < wanted.size(); count++) {
            Packet packet;
            try {
                packet = demuxer.read();
            } catch (MediaException.Malformed exception) {
                break;
            }
            if (packet == null) {
                break;
            }
            if (wanted.containsKey(packet.trackId()) && packet.keyframe() && !result.containsKey(packet.trackId())) {
                result.put(packet.trackId(), packet.data());
            }
        }
        return result;
    }

    /**
     * Returns whether a video track lacks a configuration record that describes it.
     *
     * @param track a video track
     * @return {@code true} for VP8, ProRes and DNxHD tracks, and VP9 and AV1 tracks without one
     */
    private static boolean needsKeyFrame(VideoTrack track) {
        return switch (track.codec()) {
            case VP8, PRORES, DNXHD -> true;
            case VP9, AV1 -> track.codecConfig().isEmpty();
            default -> false;
        };
    }

    /**
     * Describes a video track.
     *
     * @param fields the shared fields
     * @param track the track to describe
     * @param keyFrame the first key frame, or {@code null} when none was read
     * @return the description
     */
    private static VideoStreamInfo video(StreamFields fields, VideoTrack track, byte[] keyFrame) {
        var info = VideoConfigs.describe(track.codec(), track.codecConfig().orElse(null));
        var width = track.width();
        var height = track.height();
        String profile = null;
        var level = -1;
        var bitDepth = 0;
        ChromaFormat chroma = null;
        ColorInfo color = track.color().orElse(null);
        Rational sar = null;
        Rational frameRate = track.frameRate().orElse(null);
        if (info != null) {
            profile = info.profile();
            level = info.level();
            bitDepth = info.bitDepth();
            chroma = info.chroma();
            sar = info.sampleAspect();
            if (color == null) {
                color = info.color();
            }
            if (width <= 0 || height <= 0) {
                width = info.width();
                height = info.height();
            }
            if (frameRate == null) {
                frameRate = info.frameRate();
            }
        }
        if (keyFrame != null) {
            switch (track.codec()) {
                case VP8 -> {
                    if (keyFrame.length >= 10 && (keyFrame[0] & 1) == 0) {
                        profile = Integer.toString((keyFrame[0] >> 1) & 0x07);
                        bitDepth = 8;
                        chroma = ChromaFormat.YUV420;
                        if (width <= 0 || height <= 0) {
                            width = ((keyFrame[6] & 0xFF) | (keyFrame[7] & 0xFF) << 8) & 0x3FFF;
                            height = ((keyFrame[8] & 0xFF) | (keyFrame[9] & 0xFF) << 8) & 0x3FFF;
                        }
                    }
                }
                case VP9 -> {
                    var header = VideoConfigs.vp9KeyFrame(keyFrame);
                    if (header != null) {
                        profile = profile != null ? profile : "Profile " + header.profile();
                        if (color == null) {
                            var matrix = MatrixCoefficients.of(VideoConfigs.vp9Matrix(header.colorSpace()));
                            color = new ColorInfo(matrix, header.fullRange() ? ColorRange.FULL
                                    : ColorRange.LIMITED, ColorPrimaries.UNSPECIFIED, TransferCharacteristics.UNSPECIFIED);
                        }
                        bitDepth = header.bitDepth();
                        chroma = header.subsamplingX() == 1 && header.subsamplingY() == 1 ? ChromaFormat.YUV420
                                : header.subsamplingX() == 1 ? ChromaFormat.YUV422 : ChromaFormat.YUV444;
                        if (width <= 0 || height <= 0) {
                            width = header.width();
                            height = header.height();
                        }
                    }
                }
                case AV1 -> {
                    var header = VideoConfigs.sequenceHeaderOf(keyFrame);
                    if (header != null) {
                        var described = VideoConfigs.describe(Codec.AV1, av1Record(keyFrame));
                        if (described != null) {
                            profile = described.profile();
                            level = described.level();
                            bitDepth = described.bitDepth();
                            chroma = described.chroma();
                            if (color == null) {
                                color = described.color();
                            }
                        }
                    }
                }
                case PRORES, DNXHD -> {
                    var header = track.codec() == Codec.PRORES
                            ? IntraFrameHeaders.prores(keyFrame, track.codecConfig().orElse(null))
                            : IntraFrameHeaders.dnxhd(keyFrame);
                    if (header != null) {
                        profile = header.profile();
                        bitDepth = header.bitDepth();
                        chroma = header.chroma();
                        if (color == null) {
                            color = header.color();
                        } else if (header.color() != null && header.color().matrix() == MatrixCoefficients.IDENTITY) {
                            color = color.withMatrix(MatrixCoefficients.IDENTITY).withRange(header.color().range());
                        }
                        if (width <= 0 || height <= 0) {
                            width = header.width();
                            height = header.height();
                        }
                    }
                }
                default -> {
                }
            }
        }
        return new VideoStreamInfo(fields, track.codec(), profile, level, width, height, track.orientation(), frameRate,
                sar, color, bitDepth, chroma);
    }

    /**
     * {@return the {@code av1C} record of a key frame's temporal unit, or an empty array when it
     * cannot be built}
     *
     * @param keyFrame the temporal unit
     */
    private static byte[] av1Record(byte[] keyFrame) {
        var record = CodecConfigs.av1Config(keyFrame);
        return record != null ? record : new byte[0];
    }

    /**
     * Describes an audio track.
     *
     * <p>The first packet of an AC-3 or E-AC-3 track supplies the layout with its dependent
     * substreams, the bit rate, the dialogue level and the Dolby Atmos signal.
     *
     * @param fields the shared fields
     * @param track the track to describe
     * @param firstFrame the first packet of an AC-3 or E-AC-3 track, or {@code null} when none was read
     * @return the description
     */
    private static AudioStreamInfo audio(StreamFields fields, AudioTrack track, byte[] firstFrame) {
        var config = track.codecConfig().orElse(null);
        var channels = track.layout().map(ChannelLayout::channels).orElse(0);
        var layout = track.layout().orElse(null);
        var rate = track.sampleRate();
        String profile = null;
        var dialogueLevel = 0;
        var dolby = firstFrame == null ? null : Ac3Configs.describe(firstFrame, 0, firstFrame.length);
        if (dolby != null) {
            layout = dolby.layout();
            channels = layout.channels();
            rate = rate == 0 ? dolby.sampleRate() : rate;
            dialogueLevel = dolby.dialogueLevel();
            if (dolby.objectAudio()) {
                profile = "Dolby Digital Plus + Dolby Atmos";
            }
            if (fields.bitRate() < 0) {
                fields = withBitRate(fields, dolby.bitrate());
            }
        }
        if (config != null) {
            try {
                switch (track.codec()) {
                    case AAC -> {
                        profile = aacProfile(config);
                        if (channels == 0 || rate == 0) {
                            var aac = AudioConfigs.parseAac(config);
                            channels = channels == 0 ? aac.channels() : channels;
                            rate = rate == 0 ? aac.outputRate() : rate;
                        }
                    }
                    case OPUS -> {
                        if (channels == 0) {
                            channels = AudioConfigs.parseOpusHead(config).channels();
                        }
                    }
                    default -> {
                    }
                }
            } catch (MediaException.Malformed ignored) {
                // a damaged configuration leaves the container's own description
            }
        }
        var bits = track.bitsPerSample();
        if (track.codec() == Codec.FLAC && bits <= 0 && config != null) {
            try {
                bits = AudioConfigs.parseFlac(config).bitDepth();
            } catch (MediaException.Malformed ignored) {
                // a damaged configuration leaves the bit depth unknown
            }
        }
        if (!lossless(track.codec())) {
            bits = 0;
        }
        return new AudioStreamInfo(fields, track.codec(), profile, rate, channels, layout, bits, dialogueLevel);
    }

    /**
     * {@return whether a codec stores samples at a fixed resolution: PCM, FLAC and ALAC}
     *
     * @param codec an audio codec
     */
    private static boolean lossless(Codec codec) {
        return codec == Codec.FLAC || codec == Codec.ALAC || codec.isPcm();
    }

    /**
     * Names the profile of an {@code AudioSpecificConfig} as ffprobe does.
     *
     * @param config the {@code AudioSpecificConfig}
     * @return the profile name, or {@code null} for an unknown object type
     */
    private static String aacProfile(byte[] config) {
        if (config.length < 2) {
            return null;
        }
        var objectType = (config[0] & 0xFF) >> 3;
        if (objectType == 31) {
            objectType = 32 + (((config[0] & 0x07) << 3) | ((config[1] & 0xFF) >> 5));
        }
        var sbr = false;
        try {
            sbr = AudioConfigs.parseAac(config).sbr();
        } catch (MediaException.Malformed ignored) {
            // the object type alone names the profile
        }
        return switch (objectType) {
            case 1 -> "Main";
            case 2 -> sbr ? "HE-AAC" : "LC";
            case 3 -> "SSR";
            case 4 -> "LTP";
            case 5 -> "HE-AAC";
            case 29 -> "HE-AACv2";
            case 23 -> "LD";
            case 39 -> "ELD";
            default -> null;
        };
    }
}
