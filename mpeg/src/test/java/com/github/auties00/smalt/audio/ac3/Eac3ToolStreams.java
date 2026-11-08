package com.github.auties00.smalt.audio.ac3;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;

import java.util.List;
import java.util.Random;

/**
 * Builds short E-AC-3 streams with Smalt's encoder that exercise the decoder paths of its rarer
 * tools, for the I/O behavior harness and the fuzz seeds: a 7.1.4 program in three dependent
 * substreams with transient pre-noise processing data, the metadata of every kind and dynamic range
 * words, and a stereo program of three-block frames with enhanced coupling and several pending
 * pre-noise corrections at once.
 */
public final class Eac3ToolStreams {
    private Eac3ToolStreams() {
    }

    /**
     * The names of the streams.
     */
    public static final List<String> NAMES = List.of("surround-7.1.4", "stereo-coupled");

    /**
     * Returns a stream.
     *
     * @param name one of {@link #NAMES}
     * @return the raw E-AC-3 stream
     */
    public static byte[] stream(String name) {
        return switch (name) {
            case "surround-7.1.4" -> surround();
            case "stereo-coupled" -> stereo();
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static AudioBuffer onsets(ChannelLayout layout, int frames) {
        var planes = new float[layout.channels()][frames];
        var random = new Random(11);
        for (var ch = 0; ch < planes.length; ch++) {
            for (var i = 0; i < frames; i++) {
                var t = i / 48000.0;
                var phase = (i + 977 * ch) % 7200;
                var envelope = phase < 1200 ? Math.exp(-phase / 180.0) * Math.min(1, phase / 8.0) : 0;
                var common = 0.1 * Math.sin(2 * Math.PI * 3000 * t) + 0.05 * Math.sin(2 * Math.PI * 9000 * t);
                planes[ch][i] = (float) (0.15 * Math.sin(2 * Math.PI * (200 + 150 * ch) * t) + common * (1 - 0.1 * ch)
                        + 0.5 * envelope * random.nextGaussian());
            }
        }
        return new AudioBuffer(layout, 48000, planes, frames);
    }

    private static byte[] surround() {
        var layout = new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER,
                Speaker.LOW_FREQUENCY, Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT,
                Speaker.TOP_FRONT_LEFT, Speaker.TOP_FRONT_RIGHT, Speaker.TOP_BACK_LEFT, Speaker.TOP_BACK_RIGHT));
        var metadata = Ac3Metadata.builder().serviceType(Ac3ServiceType.MUSIC_AND_EFFECTS)
                .preferredDownmix(Ac3PreferredDownmix.LT_RT).ltrtCenterMixLevel(Ac3MixLevel.MINUS_1_5_DB)
                .loroSurroundMixLevel(Ac3MixLevel.MINUS_4_5_DB).surroundExMode(Ac3SurroundExMode.PRO_LOGIC_IIZ)
                .mixingLevel(98).roomType(Ac3RoomType.LARGE).lfeMixLevel(4).programScale(2).copyright(true)
                .lineModeProfile(Ac3DynamicRangeProfile.FILM_LIGHT).rfModeProfile(Ac3DynamicRangeProfile.FILM_STANDARD)
                .build();
        var options = Eac3EncoderOptions.of(48000, layout).toBuilder().bitrate(1152000).preNoiseProcessing(true)
                .dialogueLevel(-24).metadata(metadata).build();
        return Ac3EncoderTest.concatenate(Eac3EncoderTest.encode(options, onsets(layout, 9600)));
    }

    private static byte[] stereo() {
        var options = Eac3EncoderOptions.defaults().toBuilder().bitrate(96000).blocksPerFrame(3).spectralExtension(false)
                .enhancedCoupling(true).preNoiseProcessing(true).blockSwitching(false)
                .metadata(Ac3Metadata.builder().surroundMode(Ac3Indication.ENCODED).headphoneMode(Ac3Indication.NOT_ENCODED)
                        .lineModeProfile(Ac3DynamicRangeProfile.SPEECH).build())
                .build();
        return Ac3EncoderTest.concatenate(Eac3EncoderTest.encode(options, onsets(ChannelLayout.STEREO, 48000)));
    }
}
