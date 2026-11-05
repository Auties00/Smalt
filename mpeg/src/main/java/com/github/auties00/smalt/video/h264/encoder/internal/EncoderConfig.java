package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.h264.encoder.H264AdaptiveQuantization;
import com.github.auties00.smalt.video.h264.encoder.H264FieldCoding;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderOptions;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderSpeed;
import com.github.auties00.smalt.video.h264.encoder.H264Level;
import com.github.auties00.smalt.video.h264.encoder.H264PacketFormat;
import com.github.auties00.smalt.video.h264.encoder.H264Profile;
import com.github.auties00.smalt.video.h264.encoder.H264RateControl;
import com.github.auties00.smalt.video.h264.internal.H264Levels;

import java.math.BigInteger;
import java.time.Duration;

/**
 * The resolved settings of one encoder: the picture size together with its
 * {@link H264EncoderOptions}, with the optional settings reduced to the values the engine works
 * with. Instances come only from {@link #of}, which validates them.
 *
 * @param width the picture width in pixels, from 1 to 16384
 * @param height the picture height in pixels, from 1 to 16384
 * @param frameRate the nominal frame rate in frames per second
 * @param timeBase the unit of the timestamps of accepted frames and produced packets
 * @param profile the H.264 profile
 * @param level the {@code level_idc} to signal, or 0 to choose the lowest level that fits
 * @param rateControl the rate control mode
 * @param bitrate the target bitrate in bits per second; 0 for
 * {@link H264RateControl#CONSTANT_QUALITY}
 * @param maxBitrate the rate at which the buffering verifier fills in bits per second, or 0 for
 * the default of the mode
 * @param bufferSize the buffering verifier size in bits, or 0 for the default of the mode
 * @param quality the quantizer of {@link H264RateControl#CONSTANT_QUALITY}, from 0 to 51
 * @param keyframeInterval the maximum number of frames between two IDR pictures, or 0 for no
 * periodic IDR
 * @param speed the speed and compression trade-off
 * @param color the color description
 * @param threads the most threads that analyze the macroblock rows of a picture, from 1 to 64
 * @param packetFormat how the NAL units of a packet are delimited
 * @param minQuantizer the smallest quantizer
 * @param maxQuantizer the largest quantizer
 * @param realtime whether the encoder is tuned for interactive transmission
 * @param lookahead the number of pictures analyzed ahead of the one being coded
 * @param bFrames the number of consecutive B pictures between anchors
 * @param chromaFormat the chroma format of the stream
 * @param bitDepth the sample bit depth, from 8 to 14
 * @param lossless whether the stream is coded losslessly
 * @param fieldOrder the field order of the input, {@link FieldOrder#PROGRESSIVE} for frames
 * @param fieldCoding how interlaced frames are coded
 * @param slices the number of slices per picture, from 1 to 256
 * @param maxSliceBytes the largest slice NAL unit in bytes, or 0 for no limit
 * @param intraRefresh the intra refresh period in frames, or 0 for none
 * @param temporalLayers the number of temporal layers, from 1 to 4
 * @param sampleAspectRatio the sample aspect ratio
 * @param fixedFrameRate whether the stream declares a fixed frame rate
 * @param limits the allocation limits
 * @param cavlc whether a profile above Constrained Baseline codes with CAVLC
 * @param hrd whether the stream signals a hypothetical reference decoder; the maximum bitrate and the buffer
 * size, and for {@link H264RateControl#CONSTANT_BITRATE} the bitrate, are then the explicit values that decoder
 * signals
 * @param adaptiveQuantization how the quantizer varies between macroblocks
 * @param adaptiveQuantizationStrength the strength of adaptive quantization, from 0 to 2
 * @param macroblockTreeStrength the strength of the macroblock tree, from 0 to 2
 * @param psychovisualStrength the weight of texture retention in mode decision, from 0 to 2
 * @param passRecords the first pass records of a second pass, or {@code null} for a single pass
 * @param passRecorder the recorder of a first pass, which codes with the fastest analysis, or {@code null}
 */
public record EncoderConfig(int width, int height, Rational frameRate, Rational timeBase, H264Profile profile,
                            int level, H264RateControl rateControl, int bitrate, int maxBitrate, int bufferSize,
                            int quality, int keyframeInterval, H264EncoderSpeed speed, ColorInfo color, int threads,
                            H264PacketFormat packetFormat, int minQuantizer, int maxQuantizer, boolean realtime,
                            int lookahead, int bFrames, ChromaFormat chromaFormat, int bitDepth, boolean lossless,
                            FieldOrder fieldOrder, H264FieldCoding fieldCoding, int slices, int maxSliceBytes,
                            int intraRefresh, int temporalLayers, Rational sampleAspectRatio,
                            boolean fixedFrameRate, MediaLimits limits, boolean cavlc, boolean hrd,
                            H264AdaptiveQuantization adaptiveQuantization, double adaptiveQuantizationStrength,
                            double macroblockTreeStrength, double psychovisualStrength, PassRecords passRecords,
                            PassRecords.Recorder passRecorder) {
    /**
     * The largest accepted picture side in pixels.
     */
    private static final int MAX_DIMENSION = 16_384;

    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);

    /**
     * Resolves the settings of an encoder of a picture size.
     *
     * <p>The buffering verifier size is the playback time of {@link H264EncoderOptions#bufferSize()}
     * at the effective maximum bitrate, rounded down to whole bits and at least one bit. With
     * {@link H264EncoderOptions#hrd()} the effective maximum bitrate is rounded down to a multiple of 64 and
     * the effective buffer size to a multiple of 16, the values the decoder signals, and a constant bitrate is
     * that maximum bitrate.
     *
     * @param width the picture width in pixels
     * @param height the picture height in pixels
     * @param options the encoder settings, validated by their builder
     * @return the settings
     * @throws IllegalArgumentException if the picture size is outside 1 to 16384 or exceeds every
     * H.264 level, or a hypothetical reference decoder is signaled with a maximum bitrate below 64 bits per
     * second or a buffer below 16 bits
     * @throws com.github.auties00.smalt.MediaException.LimitExceeded if the picture exceeds the
     * options' limits
     */
    public static EncoderConfig of(int width, int height, H264EncoderOptions options) {
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
        LimitChecks.checkPicture(options.limits(), width, height);
        if ((long) ((width + 15) >> 4) * ((height + 15) >> 4) > H264Levels.HIGHEST.maxFs()) {
            throw new IllegalArgumentException("picture size " + width + "x" + height + " exceeds every H.264 level");
        }
        var rateControl = options.rateControl();
        var quality = rateControl == H264RateControl.CONSTANT_QUALITY;
        var bitrate = quality ? 0 : (int) options.bitrate();
        var maxBitrate = (int) options.maxBitrate().orElse(0);
        var bufferSize = options.bufferSize()
                .map(size -> bufferBits(size, effectiveMaxBitrate(rateControl, bitrate, maxBitrate)))
                .orElse(0);
        var config = new EncoderConfig(width, height, options.frameRate(), options.timeBase(), options.profile(),
                options.level().map(H264Level::levelIdc).orElse(0), rateControl, bitrate, maxBitrate, bufferSize,
                options.quality(), options.keyframeInterval().orElse(0), options.speed(), options.color(),
                options.threads(), options.packetFormat(), options.minQuantizer(), options.maxQuantizer(),
                options.realtime(), options.lookahead(), options.bFrames(), options.chromaFormat(),
                options.bitDepth(), options.lossless(), options.fieldOrder(), options.fieldCoding(), options.slices(),
                options.maxSliceBytes().orElse(0), options.intraRefresh().orElse(0), options.temporalLayers(),
                options.sampleAspectRatio(), options.fixedFrameRate(), options.limits(), options.cavlc(),
                options.hrd(), options.adaptiveQuantization(), options.adaptiveQuantizationStrength(),
                options.macroblockTreeStrength(), options.psychovisualStrength(), null, null);
        if (!options.hrd()) {
            return config;
        }
        var rate = config.effectiveMaxBitrate() / 64 * 64;
        var buffer = config.effectiveBufferSize() / 16 * 16;
        if (rate < 64 || buffer < 16) {
            throw new IllegalArgumentException("the hypothetical reference decoder needs at least 64 bits per second "
                    + "and a buffer of at least 16 bits, got " + config.effectiveMaxBitrate() + " and "
                    + config.effectiveBufferSize());
        }
        return config.withRates(rateControl == H264RateControl.CONSTANT_BITRATE ? rate : bitrate, rate, buffer);
    }

    /**
     * Returns these settings with other rates.
     *
     * @param newBitrate the target bitrate
     * @param newMaxBitrate the explicit maximum bitrate
     * @param newBufferSize the explicit buffer size in bits
     * @return the settings
     */
    private EncoderConfig withRates(int newBitrate, int newMaxBitrate, int newBufferSize) {
        return new EncoderConfig(width, height, frameRate, timeBase, profile, level, rateControl, newBitrate,
                newMaxBitrate, newBufferSize, quality, keyframeInterval, speed, color, threads, packetFormat,
                minQuantizer, maxQuantizer, realtime, lookahead, bFrames, chromaFormat, bitDepth, lossless,
                fieldOrder, fieldCoding, slices, maxSliceBytes, intraRefresh, temporalLayers, sampleAspectRatio,
                fixedFrameRate, limits, cavlc, hrd, adaptiveQuantization, adaptiveQuantizationStrength,
                macroblockTreeStrength, psychovisualStrength, passRecords, passRecorder);
    }

    /**
     * Returns these settings for one pass of a two-pass encode.
     *
     * @param records the first pass records the second pass plans with, or {@code null}
     * @param recorder the recorder of the first pass, or {@code null}
     * @return the settings
     */
    public EncoderConfig withPass(PassRecords records, PassRecords.Recorder recorder) {
        return new EncoderConfig(width, height, frameRate, timeBase, profile, level, rateControl, bitrate,
                maxBitrate, bufferSize, quality, keyframeInterval, speed, color, threads, packetFormat,
                minQuantizer, maxQuantizer, realtime, lookahead, bFrames, chromaFormat, bitDepth, lossless,
                fieldOrder, fieldCoding, slices, maxSliceBytes, intraRefresh, temporalLayers, sampleAspectRatio,
                fixedFrameRate, limits, cavlc, hrd, adaptiveQuantization, adaptiveQuantizationStrength,
                macroblockTreeStrength, psychovisualStrength, records, recorder);
    }

    /**
     * {@return whether the stream needs the extended engine: samples deeper than 8 bits, a chroma format
     * other than 4:2:0, lossless coding or interlaced coding}
     */
    public boolean extended() {
        return bitDepth > 8 || chromaFormat != ChromaFormat.YUV420 || lossless || fieldOrder.isInterlaced();
    }

    /**
     * Returns the bits a buffer of a playback time holds at a rate.
     *
     * @param size the playback time
     * @param rate the rate in bits per second
     * @return the bits, at least one, or 0 when the rate is 0
     */
    private static int bufferBits(Duration size, int rate) {
        if (rate == 0) {
            return 0;
        }
        var bits = BigInteger.valueOf(rate).multiply(BigInteger.valueOf(size.toNanos())).divide(NANOS_PER_SECOND);
        return (int) Math.clamp(bits.min(BigInteger.valueOf(Integer.MAX_VALUE)).longValue(), 1, Integer.MAX_VALUE);
    }

    /**
     * Returns the rate at which the buffering verifier fills, resolving the 0 default.
     *
     * @param rateControl the rate control mode
     * @param bitrate the target bitrate
     * @param maxBitrate the explicit maximum bitrate, or 0
     * @return the maximum bitrate in bits per second, or 0 for
     * {@link H264RateControl#CONSTANT_QUALITY} without an explicit value
     */
    private static int effectiveMaxBitrate(H264RateControl rateControl, int bitrate, int maxBitrate) {
        if (maxBitrate != 0 || rateControl == H264RateControl.CONSTANT_QUALITY) {
            return maxBitrate;
        }
        return rateControl == H264RateControl.CONSTANT_BITRATE
                ? bitrate
                : (int) Math.min(Integer.MAX_VALUE, 2L * bitrate);
    }

    /**
     * {@return the rate at which the buffering verifier fills, in bits per second, resolving the 0
     * default; 0 for {@link H264RateControl#CONSTANT_QUALITY} without an explicit value}
     */
    public int effectiveMaxBitrate() {
        return effectiveMaxBitrate(rateControl, bitrate, maxBitrate);
    }

    /**
     * {@return the buffering verifier size in bits, resolving the 0 default; 0 for
     * {@link H264RateControl#CONSTANT_QUALITY} without an explicit value}
     */
    public int effectiveBufferSize() {
        if (bufferSize != 0 || rateControl == H264RateControl.CONSTANT_QUALITY) {
            return bufferSize;
        }
        var rate = (long) effectiveMaxBitrate();
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE,
                rateControl == H264RateControl.CONSTANT_BITRATE ? rate / 2 : 2 * rate));
    }
}
