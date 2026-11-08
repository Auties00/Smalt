package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the settings of an {@link Mp2Encoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class Mp2EncoderOptions {
    /**
     * The MPEG-1 Layer I bitrates in kilobits per second, by bitrate index.
     */
    private static final int[] MPEG1_LAYER1 = {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448};

    /**
     * The MPEG-1 Layer II bitrates in kilobits per second, by bitrate index.
     */
    private static final int[] MPEG1_LAYER2 = {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384};

    /**
     * The MPEG-2 Layer I bitrates in kilobits per second, by bitrate index.
     */
    private static final int[] MPEG2_LAYER1 = {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256};

    /**
     * The MPEG-2 Layer II bitrates in kilobits per second, by bitrate index.
     */
    private static final int[] MPEG2_LAYER2 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};

    /**
     * The options of {@link #defaults()}.
     */
    private static final Mp2EncoderOptions DEFAULTS = new Builder().build();

    /**
     * The options of {@link #forVideoCd()}.
     */
    private static final Mp2EncoderOptions VIDEO_CD = new Builder().format(44_100, ChannelLayout.STEREO)
            .bitrate(224_000).stereoMode(Mp3StereoMode.STEREO).build();

    private final int sampleRate;

    private final ChannelLayout layout;

    private final int layer;

    /**
     * The bitrate in bits per second, or {@code 0} for the automatic choice.
     */
    private final int bitrate;

    private final Mp3StereoMode stereoMode;

    private final boolean crc;

    private Mp2EncoderOptions(Builder builder) {
        this.sampleRate = builder.sampleRate;
        this.layout = builder.layout;
        this.layer = builder.layer;
        this.bitrate = builder.bitrate;
        this.stereoMode = builder.stereoMode;
        this.crc = builder.crc;
    }

    /**
     * Returns Layer II stereo at 192 kbit/s for 48 kHz stereo input.
     *
     * @return the default options
     */
    public static Mp2EncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the defaults for an input format: Layer II, stereo and the automatic bitrate.
     *
     * @param sampleRate the sampling rate in hertz, one of the six Layer I and II rates
     * @param layout the channel layout, mono or stereo
     * @return the options
     * @throws IllegalArgumentException if the rate or layout is not codable
     */
    public static Mp2EncoderOptions of(int sampleRate, ChannelLayout layout) {
        return new Builder().format(sampleRate, layout).build();
    }

    /**
     * Returns the audio format of the Video CD: Layer II stereo at 224 kbit/s for 44.1 kHz input.
     *
     * @return the options
     */
    public static Mp2EncoderOptions forVideoCd() {
        return VIDEO_CD;
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
     * {@return the input sampling rate in hertz; 48000 by default}
     *
     * <p>The rate selects the MPEG version: 32, 44.1 and 48 kHz are MPEG-1, 16, 22.05 and 24 kHz
     * MPEG-2 (the lower sampling frequencies of ISO/IEC 13818-3).
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * {@return the input channel layout, mono or stereo; {@link ChannelLayout#STEREO} by default}
     */
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * {@return the layer, 1 or 2; 2 by default}
     *
     * <p>A Layer II frame holds 1152 samples per channel, a Layer I frame 384. Layer I needs about
     * twice the bitrate of Layer II for the same quality.
     */
    public int layer() {
        return layer;
    }

    /**
     * {@return the constant bitrate in bits per second, or empty (the default) for the automatic
     * choice}
     *
     * <p>The bitrate must be one of the bitrates of the layer and MPEG version:
     * <table class="striped">
     * <caption>Bitrates in kilobits per second</caption>
     * <thead><tr><th scope="col">Version and layer</th><th scope="col">Bitrates</th></tr></thead>
     * <tbody>
     * <tr><th scope="row">MPEG-1 Layer I</th><td>32 to 448 in steps of 32</td></tr>
     * <tr><th scope="row">MPEG-1 Layer II</th><td>32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320,
     * 384; 32, 48, 56 and 80 for mono only, 224 and above for stereo only</td></tr>
     * <tr><th scope="row">MPEG-2 Layer I</th><td>32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224,
     * 256</td></tr>
     * <tr><th scope="row">MPEG-2 Layer II</th><td>8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160</td>
     * </tr>
     * </tbody>
     * </table>
     * {@link #effectiveBitrate()} returns the bitrate applied.
     */
    public OptionalInt bitrate() {
        return bitrate == 0 ? OptionalInt.empty() : OptionalInt.of(bitrate);
    }

    /**
     * Returns the bitrate the encoder codes at: {@link #bitrate()} when set, otherwise 192 kbit/s for
     * stereo and 96 kbit/s for mono at MPEG-1 Layer II, 128 and 64 kbit/s at MPEG-2 Layer II, and twice
     * those Layer II bitrates for Layer I.
     *
     * @return the bitrate in bits per second
     */
    public int effectiveBitrate() {
        if (bitrate != 0) {
            return bitrate;
        }
        var stereo = layout.channels() == 2;
        var kbps = isMpeg1(sampleRate) ? (stereo ? 192 : 96) : (stereo ? 128 : 64);
        return (layer == 1 ? 2 * kbps : kbps) * 1000;
    }

    /**
     * {@return how a stereo stream codes its channels; {@link Mp3StereoMode#STEREO} by default}
     *
     * <p>In joint stereo every frame is signaled as joint stereo and codes the subbands from a bound of
     * 4, 8, 12 or 16 on as intensity stereo (one signal, a scalefactor per channel), the bound chosen per
     * frame as the highest at which the bitrate holds the quantization noise of every subband below its
     * masking threshold, or 4 when none does. Joint stereo suits bitrates of 128 kbit/s and less for two
     * channels, where plain stereo leaves noise unmasked. Mono streams ignore the setting.
     */
    public Mp3StereoMode stereoMode() {
        return stereoMode;
    }

    /**
     * {@return whether every frame carries a CRC-16 over its header, bit allocation and, for Layer II,
     * scalefactor selection; {@code false} by default}
     */
    public boolean crc() {
        return crc;
    }

    /**
     * Returns whether a sampling rate is an MPEG-1 rate.
     *
     * @param sampleRate the rate in hertz
     * @return {@code true} for 32, 44.1 and 48 kHz
     */
    static boolean isMpeg1(int sampleRate) {
        return sampleRate == 32_000 || sampleRate == 44_100 || sampleRate == 48_000;
    }

    /**
     * Returns the bitrate table of a version and layer.
     *
     * @param mpeg1 whether the version is MPEG-1
     * @param layer the layer, 1 or 2
     * @return the bitrates in kilobits per second by bitrate index, index 0 (free format) holding 0
     */
    static int[] bitrates(boolean mpeg1, int layer) {
        return mpeg1 ? (layer == 1 ? MPEG1_LAYER1 : MPEG1_LAYER2) : (layer == 1 ? MPEG2_LAYER1 : MPEG2_LAYER2);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof Mp2EncoderOptions that && sampleRate == that.sampleRate && layout.equals(that.layout)
                && layer == that.layer && bitrate == that.bitrate && stereoMode == that.stereoMode && crc == that.crc;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sampleRate, layout, layer, bitrate, stereoMode, crc);
    }

    @Override
    public String toString() {
        return "Mp2EncoderOptions[sampleRate=" + sampleRate + ", layout=" + layout + ", layer=" + layer
                + ", bitrate=" + (bitrate == 0 ? "auto" : bitrate) + ", stereoMode=" + stereoMode + ", crc=" + crc + "]";
    }

    /**
     * A builder of {@link Mp2EncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges and that the bitrate is a
     * bitrate of the layer and MPEG version allowed for the channel count. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private int sampleRate = 48_000;

        private ChannelLayout layout = ChannelLayout.STEREO;

        private int layer = 2;

        /**
         * The bitrate, or {@code 0} for the automatic choice.
         */
        private int bitrate;

        private Mp3StereoMode stereoMode = Mp3StereoMode.STEREO;

        private boolean crc;

        private Builder() {
        }

        private Builder(Mp2EncoderOptions options) {
            this.sampleRate = options.sampleRate;
            this.layout = options.layout;
            this.layer = options.layer;
            this.bitrate = options.bitrate;
            this.stereoMode = options.stereoMode;
            this.crc = options.crc;
        }

        /**
         * Sets the input format; see {@link Mp2EncoderOptions#sampleRate()} and
         * {@link Mp2EncoderOptions#layout()}.
         *
         * @param sampleRate the sampling rate
         * @param layout the channel layout
         * @return this builder
         */
        public Builder format(int sampleRate, ChannelLayout layout) {
            this.layout = Objects.requireNonNull(layout, "layout");
            this.sampleRate = sampleRate;
            return this;
        }

        /**
         * Sets the layer; see {@link Mp2EncoderOptions#layer()}.
         *
         * @param layer the value
         * @return this builder
         */
        public Builder layer(int layer) {
            this.layer = layer;
            return this;
        }

        /**
         * Sets an explicit bitrate; see {@link Mp2EncoderOptions#bitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder bitrate(int bitrate) {
            this.bitrate = bitrate;
            return this;
        }

        /**
         * Selects the automatic bitrate; see {@link Mp2EncoderOptions#bitrate()}.
         *
         * @return this builder
         */
        public Builder automaticBitrate() {
            this.bitrate = 0;
            return this;
        }

        /**
         * Sets the stereo coding; see {@link Mp2EncoderOptions#stereoMode()}.
         *
         * @param stereoMode the value
         * @return this builder
         */
        public Builder stereoMode(Mp3StereoMode stereoMode) {
            this.stereoMode = Objects.requireNonNull(stereoMode, "stereoMode");
            return this;
        }

        /**
         * Sets whether frames carry a CRC; see {@link Mp2EncoderOptions#crc()}.
         *
         * @param crc the value
         * @return this builder
         */
        public Builder crc(boolean crc) {
            this.crc = crc;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the sampling rate is not a Layer I and II rate, the layout
         * is not mono or stereo, the layer is not 1 or 2, or an explicit bitrate is not a bitrate of the
         * layer and MPEG version allowed for the channel count
         */
        public Mp2EncoderOptions build() {
            if (Mp2FrameEncoder.rateIndex(sampleRate) < 0) {
                throw new IllegalArgumentException("sample rate " + sampleRate
                        + " is not one of 16000, 22050, 24000, 32000, 44100, 48000");
            }
            Checks.inRange("channel count", layout.channels(), 1, 2);
            Checks.inRange("layer", layer, 1, 2);
            if (bitrate != 0) {
                var mpeg1 = isMpeg1(sampleRate);
                var table = bitrates(mpeg1, layer);
                var index = -1;
                for (var i = 1; i < table.length; i++) {
                    if (table[i] * 1000 == bitrate) {
                        index = i;
                    }
                }
                if (index < 0) {
                    throw new IllegalArgumentException(bitrate + " bit/s is not an MPEG-" + (mpeg1 ? "1" : "2")
                            + " Layer " + (layer == 1 ? "I" : "II") + " bitrate");
                }
                if (mpeg1 && layer == 2) {
                    var kbps = table[index];
                    var monoOnly = kbps == 32 || kbps == 48 || kbps == 56 || kbps == 80;
                    if (monoOnly && layout.channels() == 2 || kbps >= 224 && layout.channels() == 1) {
                        throw new IllegalArgumentException(kbps + " kbit/s MPEG-1 Layer II is not allowed for "
                                + (layout.channels() == 1 ? "mono" : "stereo"));
                    }
                }
            }
            return new Mp2EncoderOptions(this);
        }
    }
}
