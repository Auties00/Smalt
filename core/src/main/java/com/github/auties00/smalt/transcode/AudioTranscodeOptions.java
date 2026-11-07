package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.loudness.LoudnessNormalization;
import com.github.auties00.smalt.audio.process.Biquad;
import com.github.auties00.smalt.audio.process.ChannelMixerOptions;
import com.github.auties00.smalt.audio.process.TimeStretcher;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.TrackKind;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds the settings of what a transcode does with the audio streams. Instances are immutable and
 * thread-safe; create them with {@link #builder()} or a preset such as {@link #defaults()}.
 *
 * <p>An encoded stream is decoded to exactly the samples a player outputs (encoder priming and end
 * padding removed), mixed to {@link #layout()} or down to {@link #maxChannels()} as {@link #mixing()}
 * says, resampled to
 * {@link #sampleRate()}, scaled by {@link #gainDb()}, and encoded by the encoder
 * {@link com.github.auties00.smalt.audio.AudioEncoders} finds for {@link #codec()}, configured through
 * {@link com.github.auties00.smalt.audio.AudioEncoderOptions} with {@link #bitrate()}. The output
 * sample format is the codec's: the samples are processed as 32-bit floats and a PCM codec
 * ({@link Codec#PCM_S16LE}, {@link Codec#PCM_F32LE} and the others) picks the stored format. A stream
 * whose samples the options leave unchanged (same rate and layout, no gain, no loudness
 * normalization), decoded from a codec of integer samples (FLAC, ALAC, integer PCM) and encoded to
 * one (FLAC, PCM), skips the floats: its integers reach the encoder bit for bit, so a FLAC or ALAC
 * source of any sample size up to 32 bits re-encodes to FLAC losslessly. Such a stream encoded to
 * PCM from another source travels as 64-bit floats, so 64-bit float PCM re-encodes exactly.
 *
 * <p>The samples of an encoded stream go through, in this order: the removal of the pauses
 * {@link TranscodeOptions#silenceTrim()} shortens, the tempo change of {@link TranscodeOptions#speed()} and the
 * pitch change of {@link #pitch()} ({@link TimeStretcher}), the {@link #filters()}, the {@link #fades()}, the
 * gain and the loudness normalization, which measures the samples after the other steps.
 */
public final class AudioTranscodeOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final AudioTranscodeOptions DEFAULTS = new Builder().build();

    private final StreamMode mode;

    /**
     * The identifier of the selected track, or {@code -1} for the first audio track.
     */
    private final int trackId;

    private final boolean allTracks;

    /**
     * The codec to produce, or {@code null} to let the container choose.
     */
    private final Codec codec;

    /**
     * The target bitrate, or {@code 0} for the codec's default.
     */
    private final int bitrate;

    /**
     * The output sampling rate, or {@code 0} to keep the source rate.
     */
    private final int sampleRate;

    /**
     * The output channel layout, or {@code null} to keep the source layout.
     */
    private final ChannelLayout layout;

    /**
     * The largest output channel count, or {@code 0} for no limit.
     */
    private final int maxChannels;

    private final double gainDb;

    /**
     * The loudness normalization, or {@code null}.
     */
    private final LoudnessNormalization loudnessNormalization;

    private final boolean singlePassNormalization;
    private final ChannelMixerOptions mixing;
    private final boolean verifyIntegrity;

    /**
     * The index of the input the tracks come from, or {@code -1} for any input.
     */
    private final int input;

    private final List<Biquad> filters;
    private final List<Fade> fades;
    private final double pitch;
    private final boolean preservePitch;

    private AudioTranscodeOptions(Builder builder, int trackId, int bitrate, int sampleRate, int maxChannels) {
        this.mode = builder.mode;
        this.trackId = trackId;
        this.allTracks = builder.allTracks;
        this.codec = builder.codec;
        this.bitrate = bitrate;
        this.sampleRate = sampleRate;
        this.layout = builder.layout;
        this.maxChannels = maxChannels;
        this.gainDb = builder.gainDb;
        this.loudnessNormalization = builder.loudnessNormalization;
        this.singlePassNormalization = builder.singlePassNormalization;
        this.mixing = builder.mixing;
        this.verifyIntegrity = builder.verifyIntegrity;
        this.input = builder.input;
        this.filters = builder.filters;
        this.fades = builder.fades;
        this.pitch = builder.pitch;
        this.preservePitch = builder.preservePitch;
    }

    /**
     * Returns the default options: the first audio track, copied when possible and encoded
     * otherwise, at its rate and layout.
     *
     * @return the options
     */
    public static AudioTranscodeOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns options that copy the first audio track unchanged.
     *
     * @return the options
     */
    public static AudioTranscodeOptions copy() {
        return DEFAULTS.toBuilder().mode(StreamMode.COPY).build();
    }

    /**
     * Returns options that encode the first audio track with a codec.
     *
     * @param codec the codec to produce
     * @return the options
     * @throws IllegalArgumentException if {@code codec} is not an audio codec
     */
    public static AudioTranscodeOptions encode(Codec codec) {
        return new Builder().mode(StreamMode.ENCODE).codec(codec).build();
    }

    /**
     * Returns options that leave audio out of the output.
     *
     * @return the options
     */
    public static AudioTranscodeOptions drop() {
        return DEFAULTS.toBuilder().mode(StreamMode.DROP).build();
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder initialized with these options.
     *
     * @return the builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return whether the selected streams are copied, encoded or dropped; {@link StreamMode#AUTO} by
     * default}
     */
    public StreamMode mode() {
        return mode;
    }

    /**
     * {@return the identifier of the selected track, zero or more, or empty to select the first audio
     * track (or every audio track when {@link #allTracks()} is set); empty by default}
     */
    public OptionalInt trackId() {
        return trackId < 0 ? OptionalInt.empty() : OptionalInt.of(trackId);
    }

    /**
     * {@return the index of the input the audio tracks are selected from, in the list the transcode was given, or
     * empty for any input; empty by default}
     *
     * <p>With any input, the first audio track is the first one of the first input that has audio, and
     * {@link #allTracks()} selects the audio tracks of every input, in input order. {@link #trackId()} names a track
     * of this input, or of the first input when this is empty.
     */
    public OptionalInt input() {
        return input < 0 ? OptionalInt.empty() : OptionalInt.of(input);
    }

    /**
     * {@return the filters an encoded stream goes through, in order, each channel filtered alike; empty by default}
     *
     * <p>A {@link Biquad#peaking(double, double, double) peaking} section is one band of an equalizer; the
     * {@link Biquad#lowPass(double) low-pass} and {@link Biquad#highPass(double) high-pass} sections cut what lies
     * outside a band. The filters run at the output sampling rate, so each section's frequency must be below half of
     * it. Filters encode the stream.
     */
    public List<Biquad> filters() {
        return filters;
    }

    /**
     * {@return the fades of an encoded stream; empty by default}
     *
     * <p>Each sample is multiplied by the gain of every fade at its output time. Fades encode the stream.
     */
    public List<Fade> fades() {
        return fades;
    }

    /**
     * {@return the factor every frequency of an encoded stream is multiplied by, keeping its duration, from
     * {@value TimeStretcher#MIN_FACTOR} to {@value TimeStretcher#MAX_FACTOR}; 1 by default}
     *
     * <p>A factor of 2 raises the pitch by an octave, and {@code 2^(n / 12)} by {@code n} semitones. A factor other
     * than 1 encodes the stream.
     */
    public double pitch() {
        return pitch;
    }

    /**
     * {@return whether a speed change keeps the pitch of the audio; {@code true} by default}
     *
     * <p>When {@code false}, a {@link TranscodeOptions#speed()} of {@code s} resamples the audio instead, which
     * multiplies its pitch by {@code s} as playing a tape faster does; {@link #pitch()} still applies on top, and a
     * transcode whose product of the two falls outside {@value TimeStretcher#MIN_FACTOR} to
     * {@value TimeStretcher#MAX_FACTOR} is rejected with an {@link IllegalArgumentException}.
     */
    public boolean preservePitch() {
        return preservePitch;
    }

    /**
     * {@return whether every audio track of the input is selected, each becoming one output stream;
     * {@code false} by default}
     */
    public boolean allTracks() {
        return allTracks;
    }

    /**
     * {@return the audio codec to produce, or empty to keep the source codec when it can be copied and
     * otherwise use the first encodable codec of {@link OutputContainer#audioCodecs()}; empty by
     * default}
     */
    public Optional<Codec> codec() {
        return Optional.ofNullable(codec);
    }

    /**
     * {@return the target bitrate of an encoded stream, in bits per second, positive, or empty for the
     * codec's default; empty by default}
     */
    public OptionalInt bitrate() {
        return bitrate == 0 ? OptionalInt.empty() : OptionalInt.of(bitrate);
    }

    /**
     * {@return the output sampling rate, in hertz, positive, or empty to keep the source rate; empty by
     * default}
     *
     * <p>When the source rate is kept, a codec that cannot code it (Opus codes 8, 12, 16, 24 and
     * 48 kHz only) gets 48 kHz instead.
     */
    public OptionalInt sampleRate() {
        return sampleRate == 0 ? OptionalInt.empty() : OptionalInt.of(sampleRate);
    }

    /**
     * {@return the output channel layout, or empty to keep the source layout; empty by default}
     *
     * <p>When the source layout is kept, a codec that cannot code it gets an ambisonic stream converted
     * to ACN order with SN3D normalization when the codec codes that, and otherwise the stream mixed
     * down to stereo.
     */
    public Optional<ChannelLayout> layout() {
        return Optional.ofNullable(layout);
    }

    /**
     * {@return the largest output channel count, positive, or empty for no limit; empty by default}
     *
     * <p>A source with more channels is mixed down to the default layout of this count
     * ({@link ChannelLayout#defaultFor(int)}).
     */
    public OptionalInt maxChannels() {
        return maxChannels == 0 ? OptionalInt.empty() : OptionalInt.of(maxChannels);
    }

    /**
     * {@return the gain applied to the samples, in decibels, finite; 0 (none) by default}
     *
     * <p>Samples that exceed full scale are clipped by the encoder.
     */
    public double gainDb() {
        return gainDb;
    }

    /**
     * {@return the loudness normalization applied to encoded streams, or empty for none; empty by
     * default}
     *
     * <p>When present, every selected stream is encoded: the transcode first decodes the kept
     * range once to measure its integrated loudness and true peak
     * ({@link com.github.auties00.smalt.audio.loudness.LoudnessMeter}, on the samples mixed and
     * resampled for the encoder), then encodes it with the gain
     * {@link LoudnessNormalization#gainDb(com.github.auties00.smalt.audio.loudness.LoudnessStats)}
     * computes, which replaces {@link #gainDb()}.
     */
    public Optional<LoudnessNormalization> loudnessNormalization() {
        return Optional.ofNullable(loudnessNormalization);
    }

    /**
     * {@return whether {@link #loudnessNormalization()} runs in a single pass, without measuring the stream
     * first; {@code false} by default}
     *
     * <p>When set, the samples go through a {@link com.github.auties00.smalt.audio.loudness.LoudnessNormalizer}
     * on their way to the encoder instead: the gain follows the loudness of the stream as it is encoded,
     * the true peak is limited, and the input is read once. Without a loudness normalization the setting
     * has no effect.
     *
     * <p>In the two-pass mode a normalization with
     * {@link LoudnessNormalization#peakLimiting()} sends the samples through a
     * {@link com.github.auties00.smalt.audio.loudness.TruePeakLimiter} after the gain.
     */
    public boolean singlePassNormalization() {
        return singlePassNormalization;
    }

    /**
     * {@return how the channels are mixed to the output layout; {@link ChannelMixerOptions#defaults()} by
     * default}
     *
     * <p>A caller's matrix ({@link ChannelMixerOptions#matrix()}) is applied even when the layouts are
     * equal, so it makes every selected stream encoded; its dimensions must match the output layout
     * ({@link #layout()}, which should then be set) and each source layout, or the transcode fails with
     * {@link IllegalArgumentException}.
     */
    public ChannelMixerOptions mixing() {
        return mixing;
    }

    /**
     * {@return whether an encoded stream's decoder checks the whole-stream checksum the source
     * carries; {@code false} by default}
     *
     * <p>The check is that of
     * {@link com.github.auties00.smalt.audio.AudioDecoderOptions#verifyIntegrity()}, the MD5 signature
     * of a FLAC source: a stream decoded from its first sample to its end that fails it fails the
     * transcode with {@link com.github.auties00.smalt.MediaException.Malformed}. A stream cut at its
     * end, or started by seeking past its first frame, is not checked; copied streams are not
     * decoded, so not checked.
     */
    public boolean verifyIntegrity() {
        return verifyIntegrity;
    }

    /**
     * Returns the output layout for a source layout under these options.
     *
     * @param source the source layout
     * @return the output layout
     */
    ChannelLayout outputLayout(ChannelLayout source) {
        if (layout != null) {
            return layout;
        }
        if (maxChannels > 0 && source.channels() > maxChannels) {
            return ChannelLayout.defaultFor(maxChannels);
        }
        return source;
    }

    /**
     * Returns whether the options change the samples of a source, which a copy cannot do.
     *
     * @param sourceRate   the source sampling rate
     * @param sourceLayout the source channel layout, or {@code null} when unknown
     * @return {@code true} when the stream has to be decoded
     */
    boolean changesSamples(int sourceRate, ChannelLayout sourceLayout) {
        if (gainDb != 0 || loudnessNormalization != null || (sampleRate != 0 && sampleRate != sourceRate)
                || mixing.matrix().isPresent() || !filters.isEmpty() || !fades.isEmpty() || pitch != 1) {
            return true;
        }
        if (sourceLayout == null) {
            return layout != null || maxChannels > 0;
        }
        return !outputLayout(sourceLayout).equals(sourceLayout);
    }

    /**
     * Returns the state compared by {@link #equals(Object)}.
     *
     * @return the field values
     */
    private Object[] state() {
        return new Object[]{mode, trackId, allTracks, codec, bitrate, sampleRate, layout, maxChannels, gainDb,
                loudnessNormalization, singlePassNormalization, mixing, verifyIntegrity, input, filters, fades, pitch,
                preservePitch};
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AudioTranscodeOptions that && Arrays.equals(state(), that.state());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(state());
    }

    @Override
    public String toString() {
        return "AudioTranscodeOptions[mode=" + mode + ", trackId=" + trackId() + ", allTracks=" + allTracks + ", codec="
                + codec() + ", bitrate=" + bitrate() + ", sampleRate=" + sampleRate() + ", layout=" + layout()
                + ", maxChannels=" + maxChannels() + ", gainDb=" + gainDb + ", loudnessNormalization="
                + loudnessNormalization() + ", singlePassNormalization=" + singlePassNormalization + ", mixing="
                + mixing + ", verifyIntegrity=" + verifyIntegrity + ", input=" + input() + ", filters=" + filters
                + ", fades=" + fades + ", pitch=" + pitch + ", preservePitch=" + preservePitch + "]";
    }

    /**
     * Assembles {@link AudioTranscodeOptions} setting by setting.
     *
     * <p>The track selections {@link #trackId(int)}, {@link #firstTrack()} and {@link #allTracks()}
     * exclude each other: the last one called wins. Setters reject {@code null} unless documented
     * otherwise and store their value unchecked; {@link #build()} checks the ranges and the codec.
     * Instances are not thread-safe.
     */
    public static final class Builder {
        private StreamMode mode = StreamMode.AUTO;
        private int trackId;

        /**
         * Whether {@link #trackId} selects the track.
         */
        private boolean byId;

        private boolean allTracks;
        private Codec codec;
        private int bitrate;

        /**
         * Whether {@link #bitrate} is set; {@code false} for the codec's default.
         */
        private boolean bitrateSet;

        private int sampleRate;

        /**
         * Whether {@link #sampleRate} is set; {@code false} to keep the source rate.
         */
        private boolean sampleRateSet;

        private ChannelLayout layout;
        private int maxChannels;

        /**
         * Whether {@link #maxChannels} limits the output.
         */
        private boolean channelsLimited;

        private double gainDb;
        private LoudnessNormalization loudnessNormalization;
        private boolean singlePassNormalization;
        private ChannelMixerOptions mixing = ChannelMixerOptions.defaults();
        private boolean verifyIntegrity;
        private int input = -1;
        private List<Biquad> filters = List.of();
        private List<Fade> fades = List.of();
        private double pitch = 1;
        private boolean preservePitch = true;

        private Builder() {
        }

        private Builder(AudioTranscodeOptions options) {
            this.mode = options.mode;
            this.trackId = options.trackId;
            this.byId = options.trackId >= 0;
            this.allTracks = options.allTracks;
            this.codec = options.codec;
            this.bitrate = options.bitrate;
            this.bitrateSet = options.bitrate != 0;
            this.sampleRate = options.sampleRate;
            this.sampleRateSet = options.sampleRate != 0;
            this.layout = options.layout;
            this.maxChannels = options.maxChannels;
            this.channelsLimited = options.maxChannels != 0;
            this.gainDb = options.gainDb;
            this.loudnessNormalization = options.loudnessNormalization;
            this.singlePassNormalization = options.singlePassNormalization;
            this.mixing = options.mixing;
            this.verifyIntegrity = options.verifyIntegrity;
            this.input = options.input;
            this.filters = options.filters;
            this.fades = options.fades;
            this.pitch = options.pitch;
            this.preservePitch = options.preservePitch;
        }

        /**
         * Selects the tracks from one input; see {@link AudioTranscodeOptions#input()}.
         *
         * @param input the value, zero or more
         * @return this builder
         * @throws IllegalArgumentException if the index is negative
         */
        public Builder input(int input) {
            this.input = Checks.nonNegative("input index", input);
            return this;
        }

        /**
         * Selects the tracks from any input; see {@link AudioTranscodeOptions#input()}.
         *
         * @return this builder
         */
        public Builder anyInput() {
            this.input = -1;
            return this;
        }

        /**
         * Sets the filters; see {@link AudioTranscodeOptions#filters()}.
         *
         * @param filters the value
         * @return this builder
         * @throws NullPointerException if a filter is {@code null}
         */
        public Builder filters(List<Biquad> filters) {
            this.filters = List.copyOf(filters);
            return this;
        }

        /**
         * Removes the filters; see {@link AudioTranscodeOptions#filters()}.
         *
         * @return this builder
         */
        public Builder noFilters() {
            this.filters = List.of();
            return this;
        }

        /**
         * Sets the fades; see {@link AudioTranscodeOptions#fades()}.
         *
         * @param fades the value
         * @return this builder
         * @throws NullPointerException if a fade is {@code null}
         */
        public Builder fades(List<Fade> fades) {
            this.fades = List.copyOf(fades);
            return this;
        }

        /**
         * Removes the fades; see {@link AudioTranscodeOptions#fades()}.
         *
         * @return this builder
         */
        public Builder noFades() {
            this.fades = List.of();
            return this;
        }

        /**
         * Sets the pitch factor; see {@link AudioTranscodeOptions#pitch()}.
         *
         * @param pitch the value
         * @return this builder
         */
        public Builder pitch(double pitch) {
            this.pitch = pitch;
            return this;
        }

        /**
         * Sets whether a speed change keeps the pitch; see {@link AudioTranscodeOptions#preservePitch()}.
         *
         * @param preservePitch the value
         * @return this builder
         */
        public Builder preservePitch(boolean preservePitch) {
            this.preservePitch = preservePitch;
            return this;
        }

        /**
         * Sets the mode; see {@link AudioTranscodeOptions#mode()}.
         *
         * @param mode the value
         * @return this builder
         */
        public Builder mode(StreamMode mode) {
            this.mode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        /**
         * Selects one track by identifier; see {@link AudioTranscodeOptions#trackId()}.
         *
         * @param trackId the value
         * @return this builder
         */
        public Builder trackId(int trackId) {
            this.trackId = trackId;
            this.byId = true;
            this.allTracks = false;
            return this;
        }

        /**
         * Selects the first audio track; see {@link AudioTranscodeOptions#trackId()}.
         *
         * @return this builder
         */
        public Builder firstTrack() {
            this.byId = false;
            this.allTracks = false;
            return this;
        }

        /**
         * Selects every audio track; see {@link AudioTranscodeOptions#allTracks()}.
         *
         * @return this builder
         */
        public Builder allTracks() {
            this.byId = false;
            this.allTracks = true;
            return this;
        }

        /**
         * Sets the codec to produce; see {@link AudioTranscodeOptions#codec()}.
         *
         * @param codec the value
         * @return this builder
         */
        public Builder codec(Codec codec) {
            this.codec = Objects.requireNonNull(codec, "codec");
            return this;
        }

        /**
         * Lets the output container choose the codec; see {@link AudioTranscodeOptions#codec()}.
         *
         * @return this builder
         */
        public Builder automaticCodec() {
            this.codec = null;
            return this;
        }

        /**
         * Sets the target bitrate; see {@link AudioTranscodeOptions#bitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder bitrate(int bitrate) {
            this.bitrate = bitrate;
            this.bitrateSet = true;
            return this;
        }

        /**
         * Lets the encoder choose the bitrate; see {@link AudioTranscodeOptions#bitrate()}.
         *
         * @return this builder
         */
        public Builder automaticBitrate() {
            this.bitrateSet = false;
            return this;
        }

        /**
         * Sets the output sampling rate; see {@link AudioTranscodeOptions#sampleRate()}.
         *
         * @param sampleRate the value
         * @return this builder
         */
        public Builder sampleRate(int sampleRate) {
            this.sampleRate = sampleRate;
            this.sampleRateSet = true;
            return this;
        }

        /**
         * Keeps the sampling rate of the source; see {@link AudioTranscodeOptions#sampleRate()}.
         *
         * @return this builder
         */
        public Builder sourceSampleRate() {
            this.sampleRateSet = false;
            return this;
        }

        /**
         * Sets the output channel layout; see {@link AudioTranscodeOptions#layout()}.
         *
         * @param layout the value
         * @return this builder
         */
        public Builder layout(ChannelLayout layout) {
            this.layout = Objects.requireNonNull(layout, "layout");
            return this;
        }

        /**
         * Keeps the channel layout of the source; see {@link AudioTranscodeOptions#layout()}.
         *
         * @return this builder
         */
        public Builder sourceLayout() {
            this.layout = null;
            return this;
        }

        /**
         * Sets the largest output channel count; see {@link AudioTranscodeOptions#maxChannels()}.
         *
         * @param maxChannels the value
         * @return this builder
         */
        public Builder maxChannels(int maxChannels) {
            this.maxChannels = maxChannels;
            this.channelsLimited = true;
            return this;
        }

        /**
         * Removes the limit on the output channel count; see {@link AudioTranscodeOptions#maxChannels()}.
         *
         * @return this builder
         */
        public Builder noMaxChannels() {
            this.channelsLimited = false;
            return this;
        }

        /**
         * Sets the gain; see {@link AudioTranscodeOptions#gainDb()}.
         *
         * @param gainDb the value
         * @return this builder
         */
        public Builder gainDb(double gainDb) {
            this.gainDb = gainDb;
            return this;
        }

        /**
         * Sets the loudness normalization; see {@link AudioTranscodeOptions#loudnessNormalization()}.
         *
         * @param normalization the value
         * @return this builder
         */
        public Builder loudnessNormalization(LoudnessNormalization normalization) {
            this.loudnessNormalization = Objects.requireNonNull(normalization, "normalization");
            return this;
        }

        /**
         * Removes the loudness normalization; see {@link AudioTranscodeOptions#loudnessNormalization()}.
         *
         * @return this builder
         */
        public Builder noLoudnessNormalization() {
            this.loudnessNormalization = null;
            return this;
        }

        /**
         * Sets whether the loudness normalization runs in a single pass; see
         * {@link AudioTranscodeOptions#singlePassNormalization()}.
         *
         * @param singlePass the value
         * @return this builder
         */
        public Builder singlePassNormalization(boolean singlePass) {
            this.singlePassNormalization = singlePass;
            return this;
        }

        /**
         * Sets how the channels are mixed; see {@link AudioTranscodeOptions#mixing()}.
         *
         * @param mixing the value
         * @return this builder
         */
        public Builder mixing(ChannelMixerOptions mixing) {
            this.mixing = Objects.requireNonNull(mixing, "mixing");
            return this;
        }

        /**
         * Sets whether the source's whole-stream checksum is checked; see
         * {@link AudioTranscodeOptions#verifyIntegrity()}.
         *
         * @param verify the value
         * @return this builder
         */
        public Builder verifyIntegrity(boolean verify) {
            this.verifyIntegrity = verify;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if the track identifier is negative, the codec is not an
         * audio codec, the bitrate, sampling rate or channel limit is not positive, the gain is not
         * finite, or the pitch factor is out of range
         */
        public AudioTranscodeOptions build() {
            if (!(pitch >= TimeStretcher.MIN_FACTOR && pitch <= TimeStretcher.MAX_FACTOR)) {
                throw new IllegalArgumentException("pitch " + pitch + " is outside " + TimeStretcher.MIN_FACTOR + " to "
                        + TimeStretcher.MAX_FACTOR);
            }
            if (codec != null && codec.kind() != TrackKind.AUDIO) {
                throw new IllegalArgumentException("codec " + codec.displayName() + " is not an audio codec");
            }
            if (!Double.isFinite(gainDb)) {
                throw new IllegalArgumentException("gain " + gainDb + " is not finite");
            }
            return new AudioTranscodeOptions(this,
                    byId ? Checks.nonNegative("track identifier", trackId) : -1,
                    bitrateSet ? Checks.positive("bitrate", bitrate) : 0,
                    sampleRateSet ? Checks.positive("sample rate", sampleRate) : 0,
                    channelsLimited ? Checks.positive("maximum channel count", maxChannels) : 0);
        }
    }
}
