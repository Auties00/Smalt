package com.github.auties00.smalt.property;

import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleEncoder;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoEncoders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The streams of every codec the property tests can produce, made once per JVM: Smalt's encoders for the codecs
 * they cover, ffmpeg for the others (the tests that need one skip without it), and Smalt's subtitle encoder for the
 * text codecs.
 */
public final class Catalog {
    /** The video codecs Smalt does not encode, with the ffmpeg encoder and container that make them. */
    private static final Map<Codec, String[]> FFMPEG_VIDEO = Map.of(
            Codec.HEVC, new String[]{"libx265", "mp4", "-x265-params", "log-level=error:keyint=4"},
            Codec.MPEG4_PART2, new String[]{"mpeg4", "mp4", "-g", "4"},
            Codec.H263, new String[]{"h263", "3gp", "-g", "4"},
            Codec.THEORA, new String[]{"libtheora", "ogg", "-g", "4"});

    /** The audio codecs Smalt does not encode, with the ffmpeg encoder, container and sample rate. */
    private static final Map<Codec, String[]> FFMPEG_AUDIO = Map.of(
            Codec.MP2, new String[]{"mp2", "mp2", "44100"},
            Codec.SPEEX, new String[]{"libspeex", "ogg", "16000"});

    private static final Map<Codec, Streams.Encoded> CACHE = new ConcurrentHashMap<>();

    private Catalog() {
        throw new AssertionError();
    }

    /**
     * Returns the codecs a stream can be made of on this machine without ffmpeg.
     *
     * @return the codecs
     */
    public static List<Codec> smaltCodecs() {
        var codecs = new ArrayList<Codec>();
        for (var codec : Codec.values()) {
            if (VideoEncoders.isSupported(codec) || AudioEncoders.isSupported(codec)
                    || codec == Codec.SUBRIP || codec == Codec.WEBVTT || codec == Codec.ASS) {
                codecs.add(codec);
            }
        }
        return codecs;
    }

    /**
     * Returns every codec the catalog knows, those that need ffmpeg included.
     *
     * @return the codecs
     */
    public static List<Codec> allCodecs() {
        var codecs = new ArrayList<>(smaltCodecs());
        codecs.addAll(FFMPEG_VIDEO.keySet());
        codecs.addAll(FFMPEG_AUDIO.keySet());
        return codecs;
    }

    /**
     * Returns the stream of a codec, skipping the calling test when it needs an ffmpeg encoder this machine lacks.
     * Video tracks have id 1 and audio and text tracks id 2.
     *
     * @param codec the codec
     * @return the track and its packets
     */
    public static Streams.Encoded stream(Codec codec) {
        var ffmpeg = FFMPEG_VIDEO.containsKey(codec) ? FFMPEG_VIDEO.get(codec)[0]
                : FFMPEG_AUDIO.containsKey(codec) ? FFMPEG_AUDIO.get(codec)[0] : null;
        if (ffmpeg != null) {
            OracleEncoders.assumeFfmpegEncoder(ffmpeg);
        }
        return CACHE.computeIfAbsent(codec, Catalog::make);
    }

    private static Streams.Encoded make(Codec codec) {
        if (VideoEncoders.isSupported(codec)) {
            // H.264 in file mode: length-prefixed packets with an avcC record, and B-frames.
            var realtime = codec != Codec.H264;
            return Streams.video(codec, 64, 64, 12, builder -> builder.realtime(realtime).threads(1).keyframeInterval(4));
        }
        if (AudioEncoders.isSupported(codec)) {
            var rate = switch (codec) {
                case AMR_NB -> 8000;
                case AMR_WB -> 16_000;
                case OPUS, AC3, EAC3 -> 48_000;
                default -> 44_100;
            };
            var layout = codec == Codec.AMR_NB || codec == Codec.AMR_WB ? ChannelLayout.MONO : ChannelLayout.STEREO;
            return Streams.audio(codec, layout, rate, rate / 2, builder -> builder);
        }
        if (codec == Codec.SUBRIP || codec == Codec.WEBVTT || codec == Codec.ASS) {
            var encoder = new SubtitleEncoder(SubtitleEncoder.track(codec, 2));
            var packets = new ArrayList<Packet>();
            for (var index = 0; index < 6; index++) {
                var cue = SubtitleCue.of(index * 400_000L, index * 400_000L + 300_000, "cue " + index + "\nline two");
                packets.add(encoder.encode(cue, SubtitleMarkup.SUBRIP));
            }
            return new Streams.Encoded(encoder.track(), packets);
        }
        var video = FFMPEG_VIDEO.get(codec);
        if (video != null) {
            var output = OracleEncoders.path("catalog-" + codec.name().toLowerCase() + "." + video[1]);
            var arguments = new ArrayList<>(List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25:duration=0.48",
                    "-c:v", video[0], "-pix_fmt", "yuv420p"));
            arguments.addAll(List.of(video).subList(2, video.length));
            OracleEncoders.ffmpeg(arguments, output);
            return Streams.demuxed(output, codec, 1);
        }
        var audio = FFMPEG_AUDIO.get(codec);
        if (audio != null) {
            var output = OracleEncoders.path("catalog-" + codec.name().toLowerCase() + "." + audio[1]);
            OracleEncoders.ffmpeg(List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=" + audio[2]
                    + ":duration=0.5", "-c:a", audio[0]), output);
            return Streams.demuxed(output, codec, 2);
        }
        throw Skip.now("input:no-stream", "no stream of " + codec);
    }
}
