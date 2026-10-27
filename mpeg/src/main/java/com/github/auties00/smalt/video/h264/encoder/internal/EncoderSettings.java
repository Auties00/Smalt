package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderSpeed;
import com.github.auties00.smalt.video.h264.encoder.H264Profile;
import com.github.auties00.smalt.video.h264.internal.H264Levels;

/**
 * Derives every stream-level and analysis parameter of an encoder from its configuration.
 *
 * <p>The stream-level part (sizes, profile, level, reference count, timing) fixes the parameter
 * sets; the analysis part (search range, partitions, decision method) depends only on the
 * {@link H264EncoderSpeed} and changes no syntax.
 */
public final class EncoderSettings implements StreamParameters {
    /**
     * The configuration the settings derive from.
     */
    private final EncoderConfig config;

    /**
     * The picture width in macroblocks.
     */
    private final int mbWidth;

    /**
     * The picture height in macroblocks.
     */
    private final int mbHeight;

    /**
     * The width of the cropped output, the input width rounded up to even.
     */
    private final int displayWidth;

    /**
     * The height of the cropped output, the input height rounded up to even.
     */
    private final int displayHeight;

    /**
     * The {@code profile_idc}.
     */
    private final int profileIdc;

    /**
     * Whether the entropy coder is CABAC.
     */
    private final boolean cabac;

    /**
     * Whether the 8x8 transform and 8x8 intra prediction are enabled.
     */
    private final boolean transform8x8;

    /**
     * The limits of the signaled level.
     */
    private final H264Levels.Level level;

    /**
     * The number of reference frames the decoded picture buffer holds.
     */
    private final int refs;

    /**
     * Whether B pictures that later B pictures reference are coded.
     */
    private final boolean pyramid;

    /**
     * The number of consecutive B pictures between anchors, {@code 0} for none.
     */
    private final int bframes;

    /**
     * The {@code max_num_ref_frames}: {@link #refs} plus the B pictures a pyramid keeps.
     */
    private final int numRefFrames;

    /**
     * The number of reference pictures a P slice normally lists and the motion search tries.
     */
    private final int activeRefs;

    /**
     * The binary logarithm of {@code MaxFrameNum}.
     */
    private final int log2MaxFrameNum;

    /**
     * The {@code num_units_in_tick} of the timing information.
     */
    private final long numUnitsInTick;

    /**
     * The {@code time_scale} of the timing information.
     */
    private final long timeScale;

    /**
     * The number of workers: the most threads that analyze the rows of a picture at once.
     */
    private final int workers;

    /**
     * The largest vertical motion vector magnitude the level allows, in quarter samples.
     */
    private final int maxMvY;

    /**
     * The analysis parameters of the configured speed, or of {@link H264EncoderSpeed#FASTEST} in the first
     * pass of a two-pass encode, which keeps the stream structure of the configured speed.
     */
    private final Analysis analysis;

    /**
     * The address of the first macroblock of the fixed slice each macroblock belongs to.
     */
    private final int[] sliceStarts;

    /**
     * The number of fixed slices per picture.
     */
    private final int sliceCount;

    /**
     * The number of temporal layers.
     */
    private final int temporalLayers;

    /**
     * The intra refresh period in frames, or 0 for none.
     */
    private final int intraRefresh;

    /**
     * Holds the analysis parameters of a speed.
     *
     * @param refs the number of reference frames searched
     * @param partitions the largest partition set searched: {@code 0} for 16x16, {@code 1}
     * adds 16x8 and 8x16, {@code 2} adds 8x8, {@code 3} adds sub-8x8
     * @param searchRange the integer motion search radius in samples
     * @param searchMethod {@code 0} for a small diamond, {@code 1} for hexagon, {@code 2} for
     * an uneven multi-hexagon search
     * @param subpel16 the fractional refinement level of the 16x16 search on the first
     * reference, from 0 (diamonds) to 3 (iterated squares), see
     * {@link MotionSearch#refineSubpel(int)}
     * @param subpelOther the fractional refinement level of every other search
     * @param intra4x4 whether Intra_4x4 is tried in P slices
     * @param intraModeSearch {@code 0} to try the DC, vertical, horizontal and predicted
     * Intra_4x4 and Intra_8x8 modes, {@code 1} to also follow the best
     * direction to its angular neighbors, {@code 2} to try every mode
     * @param rdMode {@code 0} for SATD decisions, {@code 1} for rate-distortion
     * refinement of the best candidates, {@code 2} for full
     * rate-distortion decision
     * @param trellis whether rate-distortion optimized quantization is used
     * @param refSearchMethod the integer search pattern of the 16x16 search on every reference
     * but the first, numbered like {@code searchMethod}
     * @param lightB whether B pictures search with the small diamond and half- and
     * quarter-sample diamonds, and skip the intra analysis of a macroblock
     * that the look-ahead found much cheaper to predict than to code
     * intra, instead of searching like P pictures
     */
    public record Analysis(int refs, int partitions, int searchRange, int searchMethod, int subpel16,
                           int subpelOther, boolean intra4x4, int intraModeSearch, int rdMode, boolean trellis,
                           int refSearchMethod, boolean lightB) {
        /**
         * Returns these parameters with another number of searched reference frames.
         *
         * @param count the number of reference frames searched
         * @return the parameters
         */
        Analysis withRefs(int count) {
            return new Analysis(count, partitions, searchRange, searchMethod, subpel16, subpelOther, intra4x4,
                    intraModeSearch, rdMode, trellis, refSearchMethod, lightB);
        }

        /**
         * Returns the analysis parameters of a speed.
         *
         * @implNote This implementation searches the second reference of
         * {@link H264EncoderSpeed#BALANCED} with the small diamond: starting from the same
         * predictor candidates it finds nearly the vectors the hexagon finds (0.02% Bjontegaard rate
         * on the Xiph test sequences) with fewer evaluations.
         *
         * @param speed the speed whose analysis parameters to return
         * @return the parameters
         */
        static Analysis of(H264EncoderSpeed speed) {
            return switch (speed) {
                case FASTEST -> new Analysis(1, 0, 16, 0, 0, 0, true, 0, 0, false, 0, false);
                case FAST -> new Analysis(1, 0, 16, 1, 1, 0, true, 1, 1, false, 1, false);
                case BALANCED -> new Analysis(2, 1, 24, 1, 1, 0, true, 1, 1, false, 0, true);
                case SLOW -> new Analysis(3, 3, 32, 2, 3, 2, true, 2, 2, true, 2, false);
            };
        }
    }

    /**
     * Derives the settings of a configuration.
     *
     * @param config the configuration
     * @throws IllegalArgumentException if an explicit level is too small for the stream
     */
    public EncoderSettings(EncoderConfig config) {
        this.config = config;
        this.mbWidth = (config.width() + 15) >> 4;
        this.mbHeight = (config.height() + 15) >> 4;
        this.displayWidth = (config.width() + 1) & ~1;
        this.displayHeight = (config.height() + 1) & ~1;
        this.profileIdc = config.profile().profileIdc();
        this.cabac = config.profile() != H264Profile.CONSTRAINED_BASELINE && !config.cavlc();
        this.transform8x8 = profileIdc >= 100 && cabac;
        var baseAnalysis = Analysis.of(config.speed());
        this.temporalLayers = config.temporalLayers();
        this.intraRefresh = config.intraRefresh();
        if (temporalLayers > 1 || intraRefresh > 0) {
            baseAnalysis = baseAnalysis.withRefs(1);
        }
        var wantB = cabac && config.bFrames() > 0 && lookahead() > 0 && temporalLayers == 1 && intraRefresh == 0;
        var bCount = wantB ? Math.min(config.bFrames(), lookahead() - 1) : 0;
        var factor = Levels.bitrateFactor(config.profile());
        var frameRate = config.frameRate().toDouble();
        var maxBitrate = config.effectiveMaxBitrate();
        var bufferSize = config.effectiveBufferSize();
        var layerRefs = temporalLayers > 1 ? 1 << Math.max(0, temporalLayers - 2) : 1;
        if (config.level() == 0) {
            this.level = Levels.select(factor, false, mbWidth, mbHeight, frameRate, maxBitrate, bufferSize,
                    Math.max(layerRefs, wantB ? Math.max(2, baseAnalysis.refs()) : baseAnalysis.refs()));
        } else {
            this.level = Levels.of(config.level());
            if (!Levels.fits(level, factor, false, mbWidth, mbHeight, frameRate, maxBitrate, bufferSize)) {
                throw new IllegalArgumentException("level " + config.level() + " is too small for "
                        + config.width() + "x" + config.height() + " at " + config.frameRate() + " fps and "
                        + maxBitrate + " bps");
            }
        }
        var dpb = level.maxDpbFrames(mbWidth * mbHeight);
        if (layerRefs > dpb) {
            throw new IllegalArgumentException("level " + level.idc() + " holds " + dpb + " reference frames of "
                    + config.width() + "x" + config.height() + ", " + temporalLayers + " temporal layers need "
                    + layerRefs);
        }
        this.activeRefs = Math.max(1, Math.min(baseAnalysis.refs(), dpb));
        this.refs = temporalLayers > 1 ? layerRefs : wantB ? Math.max(activeRefs, Math.min(2, dpb)) : activeRefs;
        var fitted = bCount;
        var shaped = false;
        if (refs >= 2 && bCount >= 2) {
            while (fitted >= 2 && refs + new BStructure(fitted, true).referenced > dpb) {
                fitted--;
            }
            shaped = fitted >= 2;
        }
        this.pyramid = shaped;
        this.bframes = shaped ? fitted : bCount;
        this.numRefFrames = refs + (shaped ? new BStructure(fitted, true).referenced : 0);
        this.analysis = config.passRecorder() != null ? Analysis.of(H264EncoderSpeed.FASTEST)
                : activeRefs == baseAnalysis.refs() ? baseAnalysis : new Analysis(activeRefs, baseAnalysis.partitions(),
                baseAnalysis.searchRange(), baseAnalysis.searchMethod(), baseAnalysis.subpel16(),
                baseAnalysis.subpelOther(), baseAnalysis.intra4x4(), baseAnalysis.intraModeSearch(),
                baseAnalysis.rdMode(), baseAnalysis.trellis(), baseAnalysis.refSearchMethod(), baseAnalysis.lightB());
        this.log2MaxFrameNum = 8;
        var rate = config.frameRate().reduce();
        this.numUnitsInTick = rate.denominator();
        this.timeScale = 2 * rate.numerator();
        this.workers = Math.min(config.threads(), mbHeight);
        this.maxMvY = level.maxVmv() * 4 - 1;
        var rows = Math.min(config.slices(), mbHeight);
        this.sliceCount = rows;
        this.sliceStarts = new int[mbWidth * mbHeight];
        for (var slice = 0; slice < rows; slice++) {
            var firstRow = (int) ((long) slice * mbHeight / rows);
            var endRow = (int) ((long) (slice + 1) * mbHeight / rows);
            java.util.Arrays.fill(sliceStarts, firstRow * mbWidth, endRow * mbWidth, firstRow * mbWidth);
        }
    }

    /**
     * Returns the configuration the settings derive from.
     *
     * @return the configuration
     */
    public EncoderConfig config() {
        return config;
    }

    /**
     * Returns the input width.
     *
     * @return the width in pixels
     */
    public int width() {
        return config.width();
    }

    /**
     * Returns the input height.
     *
     * @return the height in pixels
     */
    public int height() {
        return config.height();
    }

    /**
     * Returns the picture width in macroblocks.
     *
     * @return {@code PicWidthInMbs}
     */
    public int mbWidth() {
        return mbWidth;
    }

    /**
     * Returns the picture height in macroblocks.
     *
     * @return {@code FrameHeightInMbs}
     */
    public int mbHeight() {
        return mbHeight;
    }

    /**
     * Returns the number of macroblocks per picture.
     *
     * @return {@code PicSizeInMbs}
     */
    public int mbCount() {
        return mbWidth * mbHeight;
    }

    /**
     * Returns the coded luma width.
     *
     * @return the width in samples, a multiple of 16
     */
    public int codedWidth() {
        return mbWidth * 16;
    }

    /**
     * Returns the coded luma height.
     *
     * @return the height in samples, a multiple of 16
     */
    public int codedHeight() {
        return mbHeight * 16;
    }

    /**
     * Returns the width of the cropped output.
     *
     * @return the width in pixels, the input width rounded up to even
     */
    public int displayWidth() {
        return displayWidth;
    }

    /**
     * Returns the height of the cropped output.
     *
     * @return the height in pixels, the input height rounded up to even
     */
    public int displayHeight() {
        return displayHeight;
    }

    /**
     * Returns the {@code profile_idc}.
     *
     * @return the profile indicator
     */
    public int profileIdc() {
        return profileIdc;
    }

    /**
     * Returns whether the entropy coder is CABAC.
     *
     * @return {@code true} for CABAC, {@code false} for CAVLC
     */
    public boolean cabac() {
        return cabac;
    }

    /**
     * Returns whether the 8x8 transform and 8x8 intra prediction are enabled.
     *
     * @return whether {@code transform_8x8_mode_flag} is set
     */
    public boolean transform8x8() {
        return transform8x8;
    }

    /**
     * Returns the signaled level indicator.
     *
     * @return the {@code level_idc}, {@code 9} for level 1b
     */
    public int levelIdc() {
        return level.idc();
    }

    /**
     * Returns the limits of the signaled level.
     *
     * @return the level
     */
    public H264Levels.Level level() {
        return level;
    }

    /**
     * Returns the number of reference frames.
     *
     * @return {@code max_num_ref_frames}, at least 1
     */
    public int refs() {
        return refs;
    }

    /**
     * Returns whether B pictures are referenced by other B pictures.
     *
     * @return whether the B pictures of a group form a pyramid
     */
    public boolean pyramid() {
        return pyramid;
    }

    /**
     * Returns the number of reference frames the decoded picture buffer holds.
     *
     * @return {@code max_num_ref_frames}, {@link #refs()} plus the referenced B pictures of a pyramid
     */
    public int numRefFrames() {
        return numRefFrames;
    }

    /**
     * Returns the {@code max_dec_frame_buffering} of the stream: the frames a decoder holds for
     * reference and for output, one more than the reference frames of a pyramid so that a
     * non-referenced picture can wait for its output turn.
     *
     * @return the decoded picture buffer size in frames
     */
    public int maxDecFrameBuffering() {
        return pyramid ? Math.min(level.maxDpbFrames(mbWidth * mbHeight), numRefFrames + 1)
                : Math.max(numRefFrames, reorderFrames() + 1);
    }

    /**
     * Returns the largest number of pictures that precede a picture in coding order and follow it
     * in display order.
     *
     * @return {@code max_num_reorder_frames}
     */
    public int reorderFrames() {
        var count = bframes();
        return count == 0 ? 0 : new BStructure(count, pyramid).reorder;
    }

    /**
     * Returns the number of reference pictures a P slice lists and the motion search tries when
     * that many are available.
     *
     * @return the number of active references, at least 1 and at most {@link #refs()}
     */
    public int activeRefs() {
        return activeRefs;
    }

    /**
     * Returns the binary logarithm of {@code MaxFrameNum}.
     *
     * @return the number of {@code frame_num} bits
     */
    public int log2MaxFrameNum() {
        return log2MaxFrameNum;
    }

    /**
     * Returns the color description.
     *
     * @return the color written to the stream
     */
    public ColorInfo color() {
        return config.color();
    }

    /**
     * Returns the {@code num_units_in_tick} of the timing information.
     *
     * @return the tick length in {@code time_scale} units
     */
    public long numUnitsInTick() {
        return numUnitsInTick;
    }

    /**
     * Returns the {@code time_scale} of the timing information.
     *
     * @return the number of time units per second
     */
    public long timeScale() {
        return timeScale;
    }

    /**
     * Returns the {@code chroma_qp_index_offset}.
     *
     * @return the chroma quantizer offset
     */
    public int chromaQpOffset() {
        return 0;
    }

    /**
     * Returns the number of workers, the most threads that analyze the rows of a picture at once,
     * which is at most the number of macroblock rows.
     *
     * @return the worker count, at least 1
     */
    public int workers() {
        return workers;
    }

    /**
     * Returns the largest vertical motion vector magnitude the level allows.
     *
     * @return the limit in quarter samples
     */
    public int maxMvY() {
        return maxMvY;
    }

    /**
     * Returns the number of consecutive B pictures between anchor pictures.
     *
     * @return the B picture count, {@code 0} unless look-ahead, CABAC and two references are
     * available and the encoder is not real-time
     */
    public int bframes() {
        return refs < 2 ? 0 : bframes;
    }

    /**
     * Returns whether the stream signals {@code pic_order_cnt_type} 0, which it must to code
     * pictures out of display order.
     *
     * @return whether pictures carry an explicit order count
     */
    public boolean pictureOrderType0() {
        return bframes() > 0;
    }

    /**
     * Returns the number of bits of {@code pic_order_cnt_lsb}.
     *
     * @return the bit count
     */
    public int log2MaxPocLsb() {
        return 8;
    }

    /**
     * Returns the decimation score below which an 8x8 luma block of an inter macroblock is zeroed.
     *
     * @return 3 for {@link H264EncoderSpeed#SLOW} and 4 for every other speed
     */
    public int decimationBlockThreshold() {
        return config.speed() == H264EncoderSpeed.SLOW ? 3 : 4;
    }

    /**
     * Returns the decimation score below which all luma of an inter macroblock is zeroed.
     *
     * @return 2 for {@link H264EncoderSpeed#SLOW} and 5 for every other speed
     */
    public int decimationMacroblockThreshold() {
        return config.speed() == H264EncoderSpeed.SLOW ? 2 : 5;
    }

    /**
     * Returns the number of pictures analyzed ahead of the one being encoded.
     *
     * @return the look-ahead depth in pictures, {@code 0} in real-time mode
     */
    public int lookahead() {
        if (config.realtime() || config.temporalLayers() > 1) {
            return 0;
        }
        return config.lookahead();
    }

    /**
     * Returns the analysis parameters.
     *
     * @return the parameters of the configured speed, or of the fastest in a first pass
     */
    public Analysis analysis() {
        return analysis;
    }

    /**
     * Returns the address of the first macroblock of the fixed slice a macroblock belongs to.
     *
     * @param address the macroblock address
     * @return the first macroblock address of its slice
     */
    public int sliceStart(int address) {
        return sliceStarts[address];
    }

    /**
     * {@return the number of fixed slices per picture, at most the number of macroblock rows}
     */
    public int sliceCount() {
        return sliceCount;
    }

    /**
     * {@return the largest slice NAL unit in bytes, or 0 for no limit}
     */
    public int maxSliceBytes() {
        return config.maxSliceBytes();
    }

    /**
     * {@return the number of temporal layers}
     */
    public int temporalLayers() {
        return temporalLayers;
    }

    /**
     * {@return the intra refresh period in frames, or 0 for none}
     */
    public int intraRefresh() {
        return intraRefresh;
    }

    @Override
    public int chromaFormatIdc() {
        return 1;
    }

    @Override
    public int bitDepth() {
        return 8;
    }

    @Override
    public boolean transformBypass() {
        return false;
    }

    @Override
    public boolean frameNumGapsAllowed() {
        return temporalLayers > 2;
    }

    @Override
    public boolean frameMbsOnly() {
        return true;
    }

    @Override
    public boolean mbaff() {
        return false;
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
    public Rational sampleAspectRatio() {
        return config.sampleAspectRatio();
    }

    @Override
    public boolean fixedFrameRate() {
        return config.fixedFrameRate();
    }

    @Override
    public boolean bottomFieldPicOrder() {
        return false;
    }
}
