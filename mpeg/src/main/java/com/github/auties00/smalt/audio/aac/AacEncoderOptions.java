package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.aac.internal.EncoderLayout;
import com.github.auties00.smalt.audio.aac.internal.sbr.SbrEncoder;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.internal.header.AacRates;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the settings of an {@link AacEncoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class AacEncoderOptions {
    /**
     * The smallest accepted bitrate per coded channel.
     */
    private static final int MIN_CHANNEL_BITRATE = 6000;

    /**
     * The bitrates per full-band channel, in bits per second, at which the automatic bandwidth takes
     * the values of {@link #BANDWIDTHS}; it is interpolated linearly between them.
     */
    private static final int[] BANDWIDTH_RATES = {8000, 12000, 16000, 24000, 32000, 48000, 64000, 80000, 96000,
            112000, 128000};

    /**
     * The automatic bandwidth in hertz at each rate of {@link #BANDWIDTH_RATES}.
     */
    private static final int[] BANDWIDTHS = {3000, 3800, 4500, 8500, 13000, 17500, 18000, 19000, 21500, 23000,
            24000};

    /**
     * The options of {@link #defaults()}.
     */
    private static final AacEncoderOptions DEFAULTS = new Builder().build();

    private final int sampleRate;

    private final ChannelLayout layout;

    private final int bitrate;

    private final AacBitrateMode bitrateMode;

    private final int bandwidth;

    private final int frameLength;

    private final AacProfile profile;

    private final AacSignaling signaling;

    private final EncoderLayout coding;

    private AacEncoderOptions(Builder builder, EncoderLayout coding) {
        this.sampleRate = builder.sampleRate;
        this.layout = builder.layout;
        this.bitrate = builder.bitrate;
        this.bitrateMode = builder.bitrateMode;
        this.bandwidth = builder.bandwidth;
        this.frameLength = builder.frameLength;
        this.profile = builder.profile;
        this.signaling = builder.signaling;
        this.coding = coding;
    }

    /**
     * Returns the default options: 48 kHz stereo at 128 kb/s, constant bitrate, with the automatic
     * bandwidth.
     *
     * @return the default options
     */
    public static AacEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns constant bitrate options for a format and a bitrate, with the automatic bandwidth.
     *
     * @param sampleRate the sampling rate in hertz; see {@link #sampleRate()}
     * @param layout the channel layout of the input; see {@link #layout()}
     * @param bitrate the bitrate in bits per second; see {@link #bitrate()}
     * @return the options
     * @throws IllegalArgumentException if the format is not supported or the bitrate does not fit it
     */
    public static AacEncoderOptions of(int sampleRate, ChannelLayout layout, int bitrate) {
        return new Builder().format(sampleRate, layout).bitrate(bitrate).build();
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder initialized with these options.
     *
     * @return a new builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return the sampling rate in hertz of the input and of the decoded output; 48000 by default}
     *
     * <p>With {@link AacProfile#LC}, {@link AacProfile#LD} and {@link AacProfile#ELD} it is a standard AAC rate
     * from 7350 to 96000. With {@link AacProfile#HE} and
     * {@link AacProfile#HE_V2} the core runs at half this rate, which must be 8000, 11025, 12000, 16000, 22050,
     * 24000, 32000, 44100 or 48000 Hz.
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * {@return the audio object type produced; {@link AacProfile#LC} by default}
     */
    public AacProfile profile() {
        return profile;
    }

    /**
     * {@return how the {@code AudioSpecificConfig} of {@link AacProfile#HE} and {@link AacProfile#HE_V2} signals
     * spectral band replication and parametric stereo; {@link AacSignaling#HIERARCHICAL} by default}
     *
     * <p>It applies to these two profiles only. ADTS headers always carry the implicit form.
     */
    public AacSignaling signaling() {
        return signaling;
    }

    /**
     * {@return the channel layout of the input; stereo by default}
     *
     * <p>The channels may come in any order. The layout's loudspeakers must be either those of an
     * ISO/IEC 14496-3 channel configuration (see {@link #channelConfiguration()}), or a set that a
     * program config element positions: at most one each of front center, back center and LFE, and
     * the pairs front left and right, front left and right of center (with front left and right),
     * side left and right, and back left and right, each pair complete. {@link AacProfile#HE_V2} takes stereo
     * only.
     */
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * {@return the ISO/IEC 14496-3 channel configuration the layout is coded with, or 0 when a
     * program config element in the {@code AudioSpecificConfig} describes it}
     *
     * <p>With {@link AacProfile#HE_V2} it is 1, the mono core of the stereo output. {@link AacProfile#LD} and
     * {@link AacProfile#ELD} code configurations 1 to 7 only.
     *
     * <table class="striped">
     * <caption>Channel configurations and their loudspeakers</caption>
     * <thead>
     * <tr><th scope="col">Configuration</th><th scope="col">Loudspeakers</th></tr>
     * </thead>
     * <tbody>
     * <tr><td>1</td><td>front center (mono)</td></tr>
     * <tr><td>2</td><td>front left and right (stereo)</td></tr>
     * <tr><td>3</td><td>3.0: front center, left and right</td></tr>
     * <tr><td>4</td><td>4.0: front center, left and right, back center</td></tr>
     * <tr><td>5</td><td>5.0: front center, left and right, back left and right</td></tr>
     * <tr><td>6</td><td>5.1: configuration 5 and LFE</td></tr>
     * <tr><td>7</td><td>7.1 wide: configuration 6 and front left and right of center</td></tr>
     * <tr><td>11</td><td>6.1: configuration 6 and back center</td></tr>
     * <tr><td>12</td><td>7.1: configuration 6 and side left and right</td></tr>
     * <tr><td>13</td><td>22.2 (ITU-R BS.2051 sound system H), with the second LFE, the top and the
     * bottom loudspeakers</td></tr>
     * <tr><td>14</td><td>7.1 top front: configuration 6 and top front left and right</td></tr>
     * </tbody>
     * </table>
     */
    public int channelConfiguration() {
        return profile == AacProfile.HE_V2 ? 1 : coding.channelConfiguration();
    }

    /**
     * Returns how the layout is coded: its channel elements and the input channel of each coded channel.
     *
     * @return the coding of {@link #layout()}
     */
    EncoderLayout coding() {
        return coding;
    }

    /**
     * {@return the number of core samples per channel and frame, 1024 or 960; 1024 by default}
     *
     * <p>960 sets the {@code frameLengthFlag} of the {@code AudioSpecificConfig}, the frame length
     * of DAB+ and DRM broadcasts. ADTS cannot signal it, so such a stream needs a container that
     * stores the configuration. With {@link AacProfile#HE} and {@link AacProfile#HE_V2} a frame
     * covers twice as many input samples; with {@link AacProfile#LD} and {@link AacProfile#ELD} half as
     * many, 512 or 480.
     */
    public int frameLength() {
        return frameLength;
    }

    /**
     * {@return the bitrate in bits per second, from 6000 per channel to 6144 bits per channel and
     * frame (6 times the sampling rate per channel with 1024-sample frames, 6.4 times with 960);
     * 128000 by default}
     *
     * <p>With {@link AacBitrateMode#CBR} this is the exact long-term average; with
     * {@link AacBitrateMode#VBR} the nominal long-term average. With {@link AacProfile#LD} and
     * {@link AacProfile#ELD} the upper limit counts the shorter frames, 12 times the sampling rate per channel
     * with 512-sample frames. With {@link AacProfile#HE} and
     * {@link AacProfile#HE_V2} it includes the spectral band replication and parametric stereo data, and
     * each element's share (a channel pair counting as two channels, an LFE element as a fifth of one)
     * must lie in the range the encoder tunes for its core rate: for a 22050 or 24000 Hz core, 8000 to
     * about 132000 bits per second for a single channel element (and for HE-AAC v2) and 16000 to about
     * 264000 for a channel pair; for a 32000 to 48000 Hz core, from 24000 and 32000; for an 8000 to
     * 12000 Hz core, single channel elements from 8000 only.
     */
    public int bitrate() {
        return bitrate;
    }

    /**
     * {@return the bitrate control; {@link AacBitrateMode#CBR} by default}
     */
    public AacBitrateMode bitrateMode() {
        return bitrateMode;
    }

    /**
     * {@return the explicit audio bandwidth in hertz, from 1 to half the sampling rate, or empty to
     * derive it from the bitrate (see {@link #effectiveBandwidth()}); empty by default}
     *
     * <p>{@link AacProfile#HE} and {@link AacProfile#HE_V2} take no explicit bandwidth.
     */
    public OptionalInt bandwidth() {
        return bandwidth == 0 ? OptionalInt.empty() : OptionalInt.of(bandwidth);
    }

    /**
     * Returns the bandwidth the encoder uses: the configured one, or the automatic choice.
     *
     * <p>The automatic bandwidth of {@link AacProfile#LC}, {@link AacProfile#LD} and {@link AacProfile#ELD}
     * grows with the bitrate per full-band channel
     * from 3 kHz at 8 kbps through 13 kHz at 32 kbps and 18 kHz at 64 kbps to 24 kHz from 128 kbps,
     * and never exceeds half the sampling rate. With {@link AacProfile#HE} and {@link AacProfile#HE_V2}
     * it is the highest frequency spectral band replication reconstructs, which also grows with the
     * bitrate.
     *
     * @return the bandwidth in hertz
     */
    public int effectiveBandwidth() {
        if (bandwidth != 0) {
            return bandwidth;
        }
        if (profile == AacProfile.HE || profile == AacProfile.HE_V2) {
            return SbrEncoder.bandwidth(sampleRate, coding, bitrate, profile == AacProfile.HE_V2);
        }
        var fullBand = Math.max(1, layout.channels() - coding.lfeChannels());
        var perChannel = bitrate / (double) fullBand;
        var chosen = BANDWIDTHS[BANDWIDTHS.length - 1];
        if (perChannel <= BANDWIDTH_RATES[0]) {
            chosen = BANDWIDTHS[0];
        } else {
            for (var i = 1; i < BANDWIDTH_RATES.length; i++) {
                if (perChannel <= BANDWIDTH_RATES[i]) {
                    var t = (perChannel - BANDWIDTH_RATES[i - 1]) / (BANDWIDTH_RATES[i] - BANDWIDTH_RATES[i - 1]);
                    chosen = (int) (BANDWIDTHS[i - 1] + t * (BANDWIDTHS[i] - BANDWIDTHS[i - 1]));
                    break;
                }
            }
        }
        return Math.min(chosen, sampleRate / 2);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof AacEncoderOptions that
                && sampleRate == that.sampleRate
                && layout.equals(that.layout)
                && bitrate == that.bitrate
                && bitrateMode == that.bitrateMode
                && bandwidth == that.bandwidth
                && frameLength == that.frameLength
                && profile == that.profile
                && signaling == that.signaling;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sampleRate, layout, bitrate, bitrateMode, bandwidth, frameLength, profile, signaling);
    }

    @Override
    public String toString() {
        return "AacEncoderOptions[sampleRate=" + sampleRate
                + ", layout=" + layout.speakers()
                + ", bitrate=" + bitrate
                + ", bitrateMode=" + bitrateMode
                + ", bandwidth=" + (bandwidth == 0 ? "auto" : String.valueOf(bandwidth))
                + ", frameLength=" + frameLength
                + ", profile=" + profile
                + ", signaling=" + signaling + "]";
    }

    /**
     * A builder of {@link AacEncoderOptions}, starting from {@link AacEncoderOptions#defaults()}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the values. A builder may build
     * repeatedly. Instances are not thread-safe.
     */
    public static final class Builder {
        private int sampleRate;

        private ChannelLayout layout;

        private int bitrate;

        private AacBitrateMode bitrateMode;

        private int bandwidth;

        private boolean explicitBandwidth;

        private int frameLength;

        private AacProfile profile;

        private AacSignaling signaling;

        private Builder() {
            this.sampleRate = 48_000;
            this.layout = ChannelLayout.STEREO;
            this.bitrate = 128_000;
            this.bitrateMode = AacBitrateMode.CBR;
            this.bandwidth = 0;
            this.frameLength = 1024;
            this.profile = AacProfile.LC;
            this.signaling = AacSignaling.HIERARCHICAL;
        }

        private Builder(AacEncoderOptions options) {
            this.sampleRate = options.sampleRate;
            this.layout = options.layout;
            this.bitrate = options.bitrate;
            this.bitrateMode = options.bitrateMode;
            this.bandwidth = options.bandwidth;
            this.explicitBandwidth = options.bandwidth != 0;
            this.frameLength = options.frameLength;
            this.profile = options.profile;
            this.signaling = options.signaling;
        }

        /**
         * Sets the input format; see {@link AacEncoderOptions#sampleRate()} and
         * {@link AacEncoderOptions#layout()}.
         *
         * @param sampleRate the value of {@code sampleRate()}
         * @param layout the value of {@code layout()}
         * @return this builder
         */
        public Builder format(int sampleRate, ChannelLayout layout) {
            this.layout = Objects.requireNonNull(layout, "layout");
            this.sampleRate = sampleRate;
            return this;
        }

        /**
         * Sets the bitrate; see {@link AacEncoderOptions#bitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder bitrate(int bitrate) {
            this.bitrate = bitrate;
            return this;
        }

        /**
         * Sets the bitrate control; see {@link AacEncoderOptions#bitrateMode()}.
         *
         * @param bitrateMode the value
         * @return this builder
         */
        public Builder bitrateMode(AacBitrateMode bitrateMode) {
            this.bitrateMode = Objects.requireNonNull(bitrateMode, "bitrateMode");
            return this;
        }

        /**
         * Sets an explicit audio bandwidth; see {@link AacEncoderOptions#bandwidth()}.
         *
         * @param bandwidth the value
         * @return this builder
         */
        public Builder bandwidth(int bandwidth) {
            this.bandwidth = bandwidth;
            this.explicitBandwidth = true;
            return this;
        }

        /**
         * Selects the bandwidth derived from the bitrate; see
         * {@link AacEncoderOptions#effectiveBandwidth()}.
         *
         * @return this builder
         */
        public Builder automaticBandwidth() {
            this.bandwidth = 0;
            this.explicitBandwidth = false;
            return this;
        }

        /**
         * Sets the frame length; see {@link AacEncoderOptions#frameLength()}.
         *
         * @param frameLength the value
         * @return this builder
         */
        public Builder frameLength(int frameLength) {
            this.frameLength = frameLength;
            return this;
        }

        /**
         * Sets the audio object type; see {@link AacEncoderOptions#profile()}.
         *
         * @param profile the value
         * @return this builder
         */
        public Builder profile(AacProfile profile) {
            this.profile = Objects.requireNonNull(profile, "profile");
            return this;
        }

        /**
         * Sets the signaling of HE-AAC; see {@link AacEncoderOptions#signaling()}.
         *
         * @param signaling the value
         * @return this builder
         */
        public Builder signaling(AacSignaling signaling) {
            this.signaling = Objects.requireNonNull(signaling, "signaling");
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the sampling rate, the layout or the frame length is not
         * supported by the profile, or the bitrate or the bandwidth does not fit the format
         */
        public AacEncoderOptions build() {
            if (profile == AacProfile.HE || profile == AacProfile.HE_V2) {
                return buildHighEfficiency();
            }
            if (AacRates.index(sampleRate) < 0) {
                throw new IllegalArgumentException("sampling rate " + sampleRate + " is not an AAC rate");
            }
            if (frameLength != 1024 && frameLength != 960) {
                throw new IllegalArgumentException("frame length " + frameLength + " is not 1024 or 960");
            }
            var coding = EncoderLayout.of(layout, sampleRate).orElseThrow(() -> new IllegalArgumentException(
                    "channel layout " + layout.speakers() + " is neither an AAC channel configuration nor"
                            + " expressible by a program config element"));
            if (profile != AacProfile.LC && (coding.channelConfiguration() < 1 || coding.channelConfiguration() > 7)) {
                throw new IllegalArgumentException("AAC-LD and AAC-ELD code channel configurations 1 to 7, not "
                        + layout.speakers());
            }
            var channels = layout.channels();
            var samples = profile == AacProfile.LC ? frameLength : frameLength / 2;
            var frameBits = 6144L * channels * sampleRate / samples;
            Checks.inRange("bitrate", (long) bitrate, (long) MIN_CHANNEL_BITRATE * channels, frameBits);
            if (explicitBandwidth) {
                Checks.inRange("bandwidth", bandwidth, 1, sampleRate / 2);
            }
            return new AacEncoderOptions(this, coding);
        }

        /**
         * Checks and returns HE-AAC options.
         *
         * @return the options
         * @throws IllegalArgumentException if the settings do not fit HE-AAC
         */
        private AacEncoderOptions buildHighEfficiency() {
            var coreRate = sampleRate / 2;
            if (sampleRate % 2 != 0 || AacRates.index(sampleRate) < 0 || AacRates.index(coreRate) < 0) {
                throw new IllegalArgumentException("sampling rate " + sampleRate + " is not twice an AAC rate");
            }
            if (frameLength != 1024 && frameLength != 960) {
                throw new IllegalArgumentException("frame length " + frameLength + " is not 1024 or 960");
            }
            if (explicitBandwidth) {
                throw new IllegalArgumentException(
                        "HE-AAC takes no explicit bandwidth: the spectral band replication tuning sets it");
            }
            var coding = EncoderLayout.of(layout, coreRate).orElseThrow(() -> new IllegalArgumentException(
                    "channel layout " + layout.speakers() + " is neither an AAC channel configuration nor"
                            + " expressible by a program config element"));
            if (profile == AacProfile.HE_V2 && coding.channelConfiguration() != 2) {
                throw new IllegalArgumentException("HE-AAC v2 codes stereo only, not " + layout.speakers());
            }
            var coreChannels = profile == AacProfile.HE_V2 ? 1 : layout.channels();
            var frameBits = 6144L * coreChannels * coreRate / frameLength;
            Checks.inRange("bitrate", (long) bitrate, 1, frameBits);
            var reason = SbrEncoder.unsupported(sampleRate, coding, bitrate, profile == AacProfile.HE_V2);
            if (reason.isPresent()) {
                throw new IllegalArgumentException(reason.get());
            }
            return new AacEncoderOptions(this, coding);
        }
    }
}
