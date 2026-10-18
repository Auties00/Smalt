package com.github.auties00.smalt.probe;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.track.Codec;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Describes an audio stream of a probed file.
 *
 * <p>The values come from the container and from the codec configuration (the AAC
 * {@code AudioSpecificConfig}, the Opus, Vorbis and FLAC identification headers, the MPEG audio
 * and AC-3 frame headers). Instances are immutable and thread-safe.
 */
public final class AudioStreamInfo implements StreamInfo {
    /**
     * The shared fields.
     */
    private final StreamFields fields;

    /**
     * The codec.
     */
    private final Codec codec;

    /**
     * The profile name, or {@code null}.
     */
    private final String profile;

    /**
     * The sampling rate, or {@code 0}.
     */
    private final int sampleRate;

    /**
     * The channel count, or {@code 0}.
     */
    private final int channels;

    /**
     * The channel layout, or {@code null}.
     */
    private final ChannelLayout layout;

    /**
     * The bits per sample, or {@code 0}.
     */
    private final int bitsPerSample;

    /**
     * The dialogue level in dBFS, or {@code 0} when undeclared.
     */
    private final int dialogueLevel;

    /**
     * Constructs a description.
     *
     * @param fields        the shared fields
     * @param codec         the codec of the stream
     * @param profile       the profile name, or {@code null}
     * @param sampleRate    the sampling rate, or {@code 0}
     * @param channels      the channel count, or {@code 0}
     * @param layout        the channel layout, or {@code null}
     * @param bitsPerSample the bits per sample, or {@code 0}
     * @param dialogueLevel the dialogue level in dBFS, or {@code 0} when undeclared
     */
    AudioStreamInfo(StreamFields fields, Codec codec, String profile, int sampleRate, int channels, ChannelLayout layout,
                    int bitsPerSample, int dialogueLevel) {
        this.dialogueLevel = dialogueLevel;
        this.fields = fields;
        this.codec = codec;
        this.profile = profile;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.layout = layout;
        this.bitsPerSample = bitsPerSample;
    }

    @Override
    public int index() {
        return fields.index();
    }

    @Override
    public OptionalInt trackId() {
        return fields.optionalTrackId();
    }

    @Override
    public Optional<String> language() {
        return fields.optionalLanguage();
    }

    @Override
    public OptionalLong durationMicros() {
        return fields.optionalDuration();
    }

    @Override
    public OptionalLong bitrate() {
        return fields.optionalBitRate();
    }

    @Override
    public OptionalLong frameCount() {
        return fields.optionalFrameCount();
    }

    @Override
    public Map<String, String> tags() {
        return fields.tags();
    }

    /**
     * Returns the codec of the stream.
     *
     * @return the codec
     */
    public Codec codec() {
        return codec;
    }

    /**
     * Returns the profile the configuration declares, spelled as ffprobe spells it: for AAC
     * {@code "LC"}, {@code "HE-AAC"}, {@code "HE-AACv2"}, {@code "Main"}, {@code "LTP"},
     * {@code "LD"} or {@code "ELD"}; for MPEG audio {@code "Layer 2"} or {@code "Layer 3"}; for
     * E-AC-3 carrying the joint object coding extension {@code "Dolby Digital Plus + Dolby Atmos"}.
     *
     * @return the profile, or empty when the codec has none
     */
    public Optional<String> profile() {
        return Optional.ofNullable(profile);
    }

    /**
     * Returns the sampling rate.
     *
     * @return the rate in hertz, or {@code 0} when unknown
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * Returns the number of channels.
     *
     * @return the channel count, or {@code 0} when unknown
     */
    public int channels() {
        return channels;
    }

    /**
     * Returns the speaker positions of the channels.
     *
     * @return the layout, or empty when undeclared
     */
    public Optional<ChannelLayout> layout() {
        return Optional.ofNullable(layout);
    }

    /**
     * Returns the number of bits of every sample: the sample size of uncompressed audio, or the
     * coded resolution of lossless audio (FLAC, ALAC).
     *
     * @return the bits per sample, or empty for lossy codecs and when unknown
     */
    public OptionalInt bitsPerSample() {
        return bitsPerSample <= 0 ? OptionalInt.empty() : OptionalInt.of(bitsPerSample);
    }

    /**
     * Returns the dialogue level the stream declares, the average level of its dialogue below
     * full scale that a decoder normalizes playback against (the AC-3 and E-AC-3
     * {@code dialnorm}).
     *
     * @return the level in dBFS, from -31 to -1, or empty when the codec declares none or the
     *         first frame was not read
     */
    public OptionalInt dialogueLevel() {
        return dialogueLevel == 0 ? OptionalInt.empty() : OptionalInt.of(dialogueLevel);
    }

    @Override
    public String toString() {
        return "AudioStreamInfo[" + fields.describe() + ", codec=" + codec + (profile != null ? ", profile=" + profile : "")
                + ", sampleRate=" + sampleRate + ", channels=" + channels + (layout != null ? ", layout=" + layout : "")
                + (bitsPerSample > 0 ? ", bitsPerSample=" + bitsPerSample : "")
                + (dialogueLevel != 0 ? ", dialogueLevel=" + dialogueLevel : "") + "]";
    }
}
