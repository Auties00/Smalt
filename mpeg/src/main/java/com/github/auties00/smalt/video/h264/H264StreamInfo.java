package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.internal.nal.NalRecords;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.h264.encoder.H264Level;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;
import com.github.auties00.smalt.video.h264.internal.H264Levels;
import com.github.auties00.smalt.video.h264.internal.H264Nal;
import com.github.auties00.smalt.video.h264.internal.H264Sps;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Describes an H.264 stream from its first sequence parameter set, without decoding pictures.
 *
 * <p>Pipelines use it to decide whether a stream can be remuxed as is or must be transcoded, and
 * whether {@link H264Decoder} supports it, before committing to either. Descriptions come only
 * from {@link #probe(byte[])}.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class H264StreamInfo {
    /**
     * The bit of {@code constraint_set0_flag} in {@link #constraintFlags()}; the other constants
     * name the bits of the other flags.
     */
    private static final int CONSTRAINT_SET0 = 0x80;

    private static final int CONSTRAINT_SET1 = 0x40;

    private static final int CONSTRAINT_SET3 = 0x10;

    private static final int CONSTRAINT_SET4 = 0x08;

    private static final int CONSTRAINT_SET5 = 0x04;

    private final int profileIdc;

    private final int constraintFlags;

    private final int levelIdc;

    private final int codedWidth;

    private final int codedHeight;

    private final int width;

    private final int height;

    private final ChromaFormat chromaFormat;

    private final int bitDepthLuma;

    private final int bitDepthChroma;

    private final boolean transformBypass;

    private final boolean frameMbsOnly;

    private final boolean mbAdaptiveFrameField;

    private final int maxNumRefFrames;

    private final int nalLengthSize;

    private final Rational frameRate;

    private final ColorInfo color;

    /**
     * Constructs the description of a parsed sequence parameter set.
     *
     * @param sps the set
     * @param nalLengthSize the packet NAL length size, or 0 for Annex B
     */
    private H264StreamInfo(H264Sps sps, int nalLengthSize) {
        this.profileIdc = sps.profileIdc;
        this.constraintFlags = sps.constraintFlags;
        this.levelIdc = sps.levelIdc;
        this.codedWidth = sps.codedWidth();
        this.codedHeight = sps.codedHeight();
        this.width = sps.croppedWidth();
        this.height = sps.croppedHeight();
        this.chromaFormat = switch (sps.chromaArrayType()) {
            case 0 -> ChromaFormat.MONOCHROME;
            case 2 -> ChromaFormat.YUV422;
            case 3 -> ChromaFormat.YUV444;
            default -> ChromaFormat.YUV420;
        };
        this.bitDepthLuma = sps.bitDepthLuma;
        this.bitDepthChroma = sps.bitDepthChroma;
        this.transformBypass = sps.transformBypass;
        this.frameMbsOnly = sps.frameMbsOnly;
        this.mbAdaptiveFrameField = sps.mbAdaptiveFrameField;
        this.maxNumRefFrames = sps.maxNumRefFrames;
        this.nalLengthSize = nalLengthSize;
        this.frameRate = sps.timingInfoPresent && sps.timeScale > 0 && sps.numUnitsInTick > 0
                ? new Rational(sps.timeScale, 2 * sps.numUnitsInTick).reduce()
                : null;
        this.color = Objects.requireNonNull(sps.colorInfo(), "color");
    }

    /**
     * Describes a stream from its decoder configuration or the start of its byte stream.
     *
     * <p>The input is either an {@code AVCDecoderConfigurationRecord} ({@code avcC}), an Annex B
     * byte stream that contains a sequence parameter set, or a single sequence parameter set NAL
     * unit (starting with its NAL header byte).
     *
     * @param codecConfigOrAnnexB the configuration record or byte stream
     * @return the description of the first sequence parameter set
     * @throws MediaException.Malformed if no valid sequence parameter set is found
     */
    public static H264StreamInfo probe(byte[] codecConfigOrAnnexB) {
        Objects.requireNonNull(codecConfigOrAnnexB, "codecConfigOrAnnexB");
        try {
            var data = codecConfigOrAnnexB;
            if (NalUnits.looksLikeAnnexB(data)) {
                var found = new H264Sps[1];
                NalUnits.forEachAnnexB(data, (unit, offset, length) -> {
                    if (found[0] == null && (unit[offset] & 0x1F) == H264Nal.SPS) {
                        found[0] = parseSps(unit, offset, length);
                    }
                });
                if (found[0] == null) {
                    throw new MediaException.Malformed("no sequence parameter set in the byte stream");
                }
                return new H264StreamInfo(found[0], 0);
            }
            if (NalRecords.looksLikeAvc(data)) {
                var config = NalRecords.parseAvcStrict(data);
                for (var set : config.parameterSets()) {
                    if ((set[0] & 0x1F) == H264Nal.SPS) {
                        return new H264StreamInfo(parseSps(set, 0, set.length), config.lengthSize());
                    }
                }
                throw new MediaException.Malformed("no sequence parameter set in the configuration record");
            }
            if (data.length > 1 && (data[0] & 0x1F) == H264Nal.SPS) {
                return new H264StreamInfo(parseSps(data, 0, data.length), 0);
            }
            throw new MediaException.Malformed("not an H.264 configuration or byte stream");
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt H.264 configuration", exception);
        }
    }

    /**
     * Parses a sequence parameter set NAL unit.
     *
     * @param data the buffer
     * @param offset the index of the NAL header byte
     * @param length the NAL unit length
     * @return the parsed set
     */
    private static H264Sps parseSps(byte[] data, int offset, int length) {
        var rbsp = new byte[length + H264BitReader.PADDING];
        var size = NalUnits.unescape(data, offset + 1, length - 1, rbsp);
        return H264Sps.parse(Arrays.copyOf(rbsp, size + H264BitReader.PADDING), 0, size);
    }

    /**
     * {@return the {@code profile_idc}, for example 66 (Baseline), 77 (Main) or 100 (High)}
     */
    public int profileIdc() {
        return profileIdc;
    }

    /**
     * {@return the byte holding the constraint flags: {@code constraint_set0_flag} in bit 7 to
     * {@code constraint_set5_flag} in bit 2}
     */
    public int constraintFlags() {
        return constraintFlags;
    }

    /**
     * {@return the {@code level_idc}: ten times the level number}
     *
     * <p>Level 1b has two encodings: 9 in the High profiles, and 11 with
     * {@code constraint_set3_flag} in the Baseline, Main and Extended profiles (66, 77 and 88), where
     * 11 without the flag is level 1.1. {@link #level()} resolves them.
     */
    public int levelIdc() {
        return levelIdc;
    }

    /**
     * Returns the level the stream conforms to.
     *
     * <p>Level 1b is recognized in both of its encodings: {@code level_idc} 9, and {@code level_idc}
     * 11 with {@code constraint_set3_flag} in the Baseline, Main and Extended profiles.
     *
     * @return the level, or empty when {@code level_idc} names no level of ITU-T H.264 Annex A
     */
    public Optional<H264Level> level() {
        var idc = H264Levels.effectiveIdc(profileIdc, constraintFlags, levelIdc);
        for (var level : H264Level.values()) {
            if (level.levelIdc() == idc) {
                return Optional.of(level);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns whether the stream conforms to the Constrained Baseline profile, the profile every
     * H.264 decoder supports.
     *
     * <p>That is the case for {@code profile_idc} 66 with {@code constraint_set1_flag}, 77 with
     * {@code constraint_set0_flag}, and 88 with both flags, as RFC 6184 tabulates.
     *
     * @return {@code true} for a Constrained Baseline stream
     */
    public boolean isConstrainedBaseline() {
        return switch (profileIdc) {
            case 66 -> (constraintFlags & CONSTRAINT_SET1) != 0;
            case 77 -> (constraintFlags & CONSTRAINT_SET0) != 0;
            case 88 -> (constraintFlags & (CONSTRAINT_SET0 | CONSTRAINT_SET1)) == (CONSTRAINT_SET0 | CONSTRAINT_SET1);
            default -> false;
        };
    }

    /**
     * Returns the name ITU-T H.264 gives the profile the stream conforms to, from
     * {@code profile_idc} and the constraint flags.
     *
     * <p>For example "Constrained Baseline", "Main", "High", "Progressive High", "High 10 Intra" or
     * "High 4:4:4 Predictive". A Main or Extended stream that also meets the Constrained Baseline
     * constraints keeps its own name; {@link #isConstrainedBaseline()} tells it apart.
     *
     * @return the profile name, or empty for a {@code profile_idc} the standard does not define
     */
    public Optional<String> profileName() {
        var set3 = (constraintFlags & CONSTRAINT_SET3) != 0;
        var set4 = (constraintFlags & CONSTRAINT_SET4) != 0;
        var set5 = (constraintFlags & CONSTRAINT_SET5) != 0;
        var name = switch (profileIdc) {
            case 44 -> "CAVLC 4:4:4 Intra";
            case 66 -> (constraintFlags & CONSTRAINT_SET1) != 0 ? "Constrained Baseline" : "Baseline";
            case 77 -> "Main";
            case 83 -> set5 ? "Scalable Constrained Baseline" : "Scalable Baseline";
            case 86 -> set3 ? "Scalable High Intra" : set5 ? "Scalable Constrained High" : "Scalable High";
            case 88 -> "Extended";
            case 100 -> set4 && set5 ? "Constrained High" : set4 ? "Progressive High" : "High";
            case 110 -> set3 ? "High 10 Intra" : set4 ? "Progressive High 10" : "High 10";
            case 118 -> "Multiview High";
            case 122 -> set3 ? "High 4:2:2 Intra" : "High 4:2:2";
            case 128 -> "Stereo High";
            case 134 -> "MFC High";
            case 135 -> "MFC Depth High";
            case 138 -> "Multiview Depth High";
            case 139 -> "Enhanced Multiview Depth High";
            case 244 -> set3 ? "High 4:4:4 Intra" : "High 4:4:4 Predictive";
            default -> null;
        };
        return Optional.ofNullable(name);
    }

    /**
     * {@return the coded frame width in luma samples, a multiple of 16}
     */
    public int codedWidth() {
        return codedWidth;
    }

    /**
     * {@return the coded frame height in luma samples, a multiple of 16}
     */
    public int codedHeight() {
        return codedHeight;
    }

    /**
     * {@return the display width after frame cropping, in luma samples}
     */
    public int width() {
        return width;
    }

    /**
     * {@return the display height after frame cropping, in luma samples}
     */
    public int height() {
        return height;
    }

    /**
     * {@return the chroma subsampling of the decoded pictures}
     */
    public ChromaFormat chromaFormat() {
        return chromaFormat;
    }

    /**
     * {@return the luma sample bit depth}
     */
    public int bitDepthLuma() {
        return bitDepthLuma;
    }

    /**
     * {@return the chroma sample bit depth}
     */
    public int bitDepthChroma() {
        return bitDepthChroma;
    }

    /**
     * {@return the {@code qpprime_y_zero_transform_bypass_flag}: {@code true} when lossless macroblocks are
     * allowed}
     */
    public boolean transformBypass() {
        return transformBypass;
    }

    /**
     * {@return the {@code frame_mbs_only_flag}: {@code false} when the stream may contain field pictures or MBAFF
     * frames}
     */
    public boolean frameMbsOnly() {
        return frameMbsOnly;
    }

    /**
     * {@return the {@code mb_adaptive_frame_field_flag}}
     */
    public boolean mbAdaptiveFrameField() {
        return mbAdaptiveFrameField;
    }

    /**
     * {@return the {@code max_num_ref_frames}, the number of reference frames}
     */
    public int maxNumRefFrames() {
        return maxNumRefFrames;
    }

    /**
     * {@return the size in bytes of the length prefix of the NAL units of packets when an {@code avcC} record was
     * probed, or empty for an Annex B byte stream or a bare sequence parameter set}
     */
    public OptionalInt nalLengthSize() {
        return nalLengthSize == 0 ? OptionalInt.empty() : OptionalInt.of(nalLengthSize);
    }

    /**
     * {@return the frame rate signaled by the VUI timing information, or empty when the VUI carries none}
     */
    public Optional<Rational> frameRate() {
        return Optional.ofNullable(frameRate);
    }

    /**
     * {@return the color description, with the defaults documented on {@link H264Decoder}}
     */
    public ColorInfo color() {
        return color;
    }

    /**
     * Returns whether {@link H264Decoder} decodes streams with this configuration.
     *
     * <p>The decoder supports 8-bit 4:2:0 and 4:0:0 progressive streams without the lossless
     * transform bypass. Streams whose {@code frame_mbs_only_flag} is 0 are reported as unsupported
     * because they may contain field pictures or MBAFF frames, which the decoder rejects; such
     * streams made of frame pictures only still decode. Extended profile streams (88) are reported
     * as supported only when {@code constraint_set0_flag} or {@code constraint_set1_flag} says they
     * also conform to the Baseline or Main profile, since otherwise they may use slice data
     * partitioning or SP and SI slices.
     *
     * @return {@code true} when the decoder supports every picture this configuration allows
     */
    public boolean decoderSupported() {
        return bitDepthLuma == 8 && bitDepthChroma == 8 && frameMbsOnly && !transformBypass
                && (chromaFormat == ChromaFormat.YUV420 || chromaFormat == ChromaFormat.MONOCHROME)
                && (profileIdc != 88 || (constraintFlags & (CONSTRAINT_SET0 | CONSTRAINT_SET1)) != 0);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof H264StreamInfo that && profileIdc == that.profileIdc
                && constraintFlags == that.constraintFlags && levelIdc == that.levelIdc
                && codedWidth == that.codedWidth && codedHeight == that.codedHeight && width == that.width
                && height == that.height && chromaFormat == that.chromaFormat && bitDepthLuma == that.bitDepthLuma
                && bitDepthChroma == that.bitDepthChroma && transformBypass == that.transformBypass
                && frameMbsOnly == that.frameMbsOnly && mbAdaptiveFrameField == that.mbAdaptiveFrameField
                && maxNumRefFrames == that.maxNumRefFrames && nalLengthSize == that.nalLengthSize
                && Objects.equals(frameRate, that.frameRate) && color.equals(that.color);
    }

    @Override
    public int hashCode() {
        return Objects.hash(profileIdc, constraintFlags, levelIdc, codedWidth, codedHeight, width, height, chromaFormat,
                bitDepthLuma, bitDepthChroma, transformBypass, frameMbsOnly, mbAdaptiveFrameField, maxNumRefFrames,
                nalLengthSize, frameRate, color);
    }

    @Override
    public String toString() {
        return "H264StreamInfo[profileIdc=" + profileIdc + ", constraintFlags=" + constraintFlags + ", levelIdc="
                + levelIdc + ", coded=" + codedWidth + "x" + codedHeight + ", size=" + width + "x" + height
                + ", chromaFormat=" + chromaFormat + ", bitDepth=" + bitDepthLuma + "/" + bitDepthChroma
                + ", transformBypass=" + transformBypass + ", frameMbsOnly=" + frameMbsOnly
                + ", mbAdaptiveFrameField=" + mbAdaptiveFrameField + ", maxNumRefFrames=" + maxNumRefFrames
                + ", nalLengthSize=" + nalLengthSize + ", frameRate=" + frameRate + ", color=" + color + "]";
    }
}
