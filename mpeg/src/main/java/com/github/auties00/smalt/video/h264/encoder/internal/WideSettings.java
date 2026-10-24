package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.h264.encoder.H264FieldCoding;
import com.github.auties00.smalt.video.h264.encoder.H264Profile;
import com.github.auties00.smalt.video.h264.internal.H264Levels;

/**
 * Derives the stream-level parameters of the extended engine, which codes samples of 8 to 14
 * bits in every chroma format, lossless streams and interlaced frames.
 *
 * <p>Pictures are I and P pictures predicted from one reference frame (two reference frames for
 * field pictures, so that a second field can predict from the frame before and from its first
 * field), or from the latest picture of a lower temporal layer.
 */
final class WideSettings implements StreamParameters {
    /**
     * The binary logarithm of {@code MaxPicOrderCntLsb}.
     */
    private static final int LOG2_MAX_POC_LSB = 10;

    private final EncoderConfig config;

    private final int chromaFormatIdc;

    private final int subWidth;

    private final int subHeight;

    private final int mbWidthC;

    private final int mbHeightC;

    private final int bitDepth;

    private final boolean interlaced;

    private final boolean mbaff;

    private final boolean fieldPictures;

    private final int mbWidth;

    private final int mbHeight;

    private final int displayWidth;

    private final int displayHeight;

    private final int profileIdc;

    private final boolean cabac;

    private final boolean transform8x8;

    private final H264Levels.Level level;

    private final int numRefFrames;

    private final int layerRefs;

    private final int sliceRows;

    private final long numUnitsInTick;

    private final long timeScale;

    /**
     * Derives the settings of a configuration.
     *
     * @param config the configuration, which {@link EncoderConfig#extended()} selects for this engine
     * @throws IllegalArgumentException if an explicit level is too small or does not allow the stream's
     * coding, or the interlaced picture exceeds level 4.1
     */
    WideSettings(EncoderConfig config) {
        this.config = config;
        var format = config.chromaFormat();
        this.chromaFormatIdc = switch (format) {
            case MONOCHROME -> 0;
            case YUV420 -> 1;
            case YUV422 -> 2;
            default -> 3;
        };
        this.subWidth = chromaFormatIdc == 1 || chromaFormatIdc == 2 ? 2 : 1;
        this.subHeight = chromaFormatIdc == 1 ? 2 : 1;
        this.mbWidthC = chromaFormatIdc == 0 ? 0 : 16 / subWidth;
        this.mbHeightC = chromaFormatIdc == 0 ? 0 : 16 / subHeight;
        this.bitDepth = config.bitDepth();
        this.interlaced = config.fieldOrder().isInterlaced();
        this.mbaff = interlaced && config.fieldCoding() == H264FieldCoding.MBAFF;
        this.fieldPictures = interlaced && !mbaff;
        this.mbWidth = (config.width() + 15) >> 4;
        var rows = (config.height() + 15) >> 4;
        this.mbHeight = interlaced ? (rows + 1) & ~1 : rows;
        var cropX = chromaFormatIdc == 1 || chromaFormatIdc == 2 ? 2 : 1;
        var cropY = (chromaFormatIdc == 1 ? 2 : 1) * (interlaced ? 2 : 1);
        this.displayWidth = Math.ceilDiv(config.width(), cropX) * cropX;
        this.displayHeight = Math.ceilDiv(config.height(), cropY) * cropY;
        this.profileIdc = config.profile().profileIdc();
        this.cabac = config.profile() != H264Profile.CONSTRAINED_BASELINE && !config.cavlc();
        this.transform8x8 = profileIdc >= 100;
        var layers = config.temporalLayers();
        this.layerRefs = layers > 1 ? 1 << Math.max(0, layers - 2) : 1;
        this.numRefFrames = layerRefs + (fieldPictures ? 1 : 0);
        var factor = Levels.bitrateFactor(config.profile());
        var frameRate = config.frameRate().toDouble();
        var maxBitrate = config.effectiveMaxBitrate();
        var bufferSize = config.effectiveBufferSize();
        if (config.level() == 0) {
            this.level = Levels.select(factor, interlaced, mbWidth, mbHeight, frameRate, maxBitrate, bufferSize,
                    numRefFrames);
        } else {
            this.level = Levels.of(config.level());
            if (!Levels.fits(level, factor, interlaced, mbWidth, mbHeight, frameRate, maxBitrate, bufferSize)) {
                throw new IllegalArgumentException("level " + config.level() + " is too small for "
                        + config.width() + "x" + config.height() + " at " + config.frameRate() + " fps and "
                        + maxBitrate + " bps" + (interlaced ? ", or does not allow interlaced coding" : ""));
            }
        }
        if (interlaced && (long) mbWidth * mbHeight > Levels.of(41).maxFs()) {
            throw new IllegalArgumentException("interlaced " + config.width() + "x" + config.height()
                    + " pictures exceed level 4.1, the highest level of interlaced coding");
        }
        if (level.maxDpbFrames(mbWidth * mbHeight) < numRefFrames) {
            throw new IllegalArgumentException("level " + level.idc() + " holds fewer than the " + numRefFrames
                    + " reference frames of " + config.width() + "x" + config.height() + " the stream needs");
        }
        this.sliceRows = Math.min(config.slices(), mbaff || fieldPictures ? mbHeight / 2 : mbHeight);
        var rate = config.frameRate().reduce();
        this.numUnitsInTick = rate.denominator();
        this.timeScale = 2 * rate.numerator();
    }

    EncoderConfig config() {
        return config;
    }

    /**
     * {@return the input width in pixels}
     */
    int width() {
        return config.width();
    }

    /**
     * {@return the input height in pixels}
     */
    int height() {
        return config.height();
    }

    /**
     * {@return the horizontal chroma subsampling factor, {@code SubWidthC}, 1 for monochrome}
     */
    int subWidth() {
        return subWidth;
    }

    /**
     * {@return the vertical chroma subsampling factor, {@code SubHeightC}, 1 for monochrome}
     */
    int subHeight() {
        return subHeight;
    }

    /**
     * {@return the chroma macroblock width {@code MbWidthC}, 0 for monochrome}
     */
    int mbWidthC() {
        return mbWidthC;
    }

    /**
     * {@return the chroma macroblock height {@code MbHeightC}, 0 for monochrome}
     */
    int mbHeightC() {
        return mbHeightC;
    }

    /**
     * {@return whether the stream has chroma planes}
     */
    boolean hasChroma() {
        return chromaFormatIdc != 0;
    }

    /**
     * {@return whether chroma is coded like luma ({@code ChromaArrayType} 3)}
     */
    boolean chroma444() {
        return chromaFormatIdc == 3;
    }

    /**
     * {@return {@code QpBdOffsetY}, which is also {@code QpBdOffsetC}}
     */
    int qpBdOffset() {
        return 6 * (bitDepth - 8);
    }

    /**
     * {@return the largest sample value}
     */
    int maxSample() {
        return (1 << bitDepth) - 1;
    }

    /**
     * {@return whether the input is interlaced}
     */
    boolean interlaced() {
        return interlaced;
    }

    /**
     * {@return whether frames are coded as two field pictures}
     */
    boolean fieldPictures() {
        return fieldPictures;
    }

    /**
     * {@return whether the bottom field of a frame was sampled first}
     */
    boolean bottomFirst() {
        return config.fieldOrder() == FieldOrder.BOTTOM_FIRST;
    }

    /**
     * {@return the number of macroblocks per frame}
     */
    int mbCount() {
        return mbWidth * mbHeight;
    }

    /**
     * {@return the coded luma width}
     */
    int codedWidth() {
        return mbWidth * 16;
    }

    /**
     * {@return the coded luma frame height}
     */
    int codedHeight() {
        return mbHeight * 16;
    }

    /**
     * {@return the width of the cropped output}
     */
    int displayWidth() {
        return displayWidth;
    }

    /**
     * {@return the height of the cropped output}
     */
    int displayHeight() {
        return displayHeight;
    }

    /**
     * {@return the limits of the signaled level}
     */
    H264Levels.Level level() {
        return level;
    }

    /**
     * {@return the number of reference frames P pictures of the temporal layers keep}
     */
    int layerRefs() {
        return layerRefs;
    }

    /**
     * {@return the number of slices each picture is divided into, at most its rows of macroblocks or
     * macroblock pairs}
     */
    int sliceRows() {
        return sliceRows;
    }

    /**
     * {@return whether the stream codes lossless macroblocks}
     */
    boolean lossless() {
        return config.lossless();
    }

    /**
     * {@return the largest vertical vector of a frame macroblock, in quarter samples}
     */
    int maxMvY() {
        return level.maxVmv() * 4 - 1;
    }

    @Override
    public int profileIdc() {
        return profileIdc;
    }

    @Override
    public int levelIdc() {
        return level.idc();
    }

    @Override
    public int chromaFormatIdc() {
        return chromaFormatIdc;
    }

    @Override
    public int bitDepth() {
        return bitDepth;
    }

    @Override
    public boolean transformBypass() {
        return config.lossless();
    }

    @Override
    public int log2MaxFrameNum() {
        return 8;
    }

    @Override
    public boolean pictureOrderType0() {
        return interlaced;
    }

    @Override
    public int log2MaxPocLsb() {
        return LOG2_MAX_POC_LSB;
    }

    @Override
    public int numRefFrames() {
        return numRefFrames;
    }

    @Override
    public boolean frameNumGapsAllowed() {
        return config.temporalLayers() > 2;
    }

    @Override
    public int mbWidth() {
        return mbWidth;
    }

    @Override
    public int mbHeight() {
        return mbHeight;
    }

    @Override
    public boolean frameMbsOnly() {
        return !interlaced;
    }

    @Override
    public boolean mbaff() {
        return mbaff;
    }

    @Override
    public int cropRight() {
        return codedWidth() - displayWidth;
    }

    @Override
    public int cropBottom() {
        return codedHeight() - displayHeight;
    }

    @Override
    public ColorInfo color() {
        return config.color();
    }

    @Override
    public Rational sampleAspectRatio() {
        return config.sampleAspectRatio();
    }

    @Override
    public long numUnitsInTick() {
        return numUnitsInTick;
    }

    @Override
    public long timeScale() {
        return timeScale;
    }

    @Override
    public boolean fixedFrameRate() {
        return config.fixedFrameRate();
    }

    @Override
    public int reorderFrames() {
        return 0;
    }

    @Override
    public int maxDecFrameBuffering() {
        return numRefFrames;
    }

    @Override
    public boolean cabac() {
        return cabac;
    }

    @Override
    public int activeRefs() {
        return 1;
    }

    @Override
    public int chromaQpOffset() {
        return 0;
    }

    @Override
    public boolean transform8x8() {
        return transform8x8;
    }

    @Override
    public boolean bottomFieldPicOrder() {
        return mbaff;
    }

    /**
     * {@return the chroma format of the stream}
     */
    ChromaFormat chromaFormat() {
        return config.chromaFormat();
    }
}
