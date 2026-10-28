package com.github.auties00.smalt.video.mpeg4.internal.encoder;

import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.mpeg4.internal.Frame;
import com.github.auties00.smalt.video.mpeg4.internal.Tables;
import com.github.auties00.smalt.video.mpeg4.internal.Vop;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Codes a sequence of pictures as an ITU-T H.263 stream of I- and P-pictures, one picture per packet: the baseline
 * picture header for the standard source formats without the optional modes of the second version, or the extended
 * picture type (PLUSPTYPE) with a custom picture format, a custom picture clock frequency, slices and the modes of
 * {@link Settings}.
 *
 * <p>The temporal reference counts the periods of the picture clock from the first picture. Instances are not
 * thread-safe.
 */
public final class H263EncoderCore {
    /**
     * The settings of a stream.
     *
     * @param width the picture width
     * @param height the picture height
     * @param extendedType whether the pictures carry the extended picture type
     * @param unrestrictedVectors whether the Unrestricted Motion Vector mode (Annex D) is on
     * @param advancedPrediction whether the Advanced Prediction mode (Annex F) is on
     * @param advancedIntra whether the Advanced INTRA Coding mode (Annex I) is on
     * @param deblocking whether the Deblocking Filter mode (Annex J) is on
     * @param modifiedQuantization whether the Modified Quantization mode (Annex T) is on
     * @param groupHeaders whether every group of blocks after the first has a header
     * @param sliceMacroblocks the macroblocks of each slice of the Slice Structured mode (Annex K), or 0 for groups
     * of blocks
     * @param clockConversion the clock conversion code of a custom picture clock frequency, 1000 or 1001, or 0 for
     * the standard frequency of 30000/1001 Hz
     * @param clockDivisor the clock divisor of a custom picture clock frequency, from 1 to 127
     * @param searchRange the largest whole-sample vector component searched
     * @param threads the most threads of the analysis
     * @param bitrate the target bitrate in bits per second
     * @param frameRate the nominal frame rate
     * @param fixedQuant the fixed quantizer, or 0 for rate control
     * @param minQuant the smallest quantizer
     * @param maxQuant the largest quantizer
     * @param keyframeInterval the most pictures from one I-picture to the next, or 0 for none but the first
     * @param timeBase the unit of the timestamps
     */
    public record Settings(int width, int height, boolean extendedType, boolean unrestrictedVectors,
                           boolean advancedPrediction, boolean advancedIntra, boolean deblocking,
                           boolean modifiedQuantization, boolean groupHeaders, int sliceMacroblocks,
                           int clockConversion, int clockDivisor,
                           int searchRange, int threads, long bitrate, Rational frameRate, int fixedQuant,
                           int minQuant, int maxQuant, int keyframeInterval, Rational timeBase) {
    }

    /**
     * The source format codes of the standard picture sizes, indexed like {@link #WIDTHS}.
     */
    private static final int[] WIDTHS = {128, 176, 352, 704, 1408};
    private static final int[] HEIGHTS = {96, 144, 288, 576, 1152};

    /**
     * The flags of a key frame packet.
     */
    private static final Set<PacketFlag> KEY = Set.of(PacketFlag.KEY);

    private final Settings settings;
    private final PictureCoder coder;
    private final SourceFrames sources;
    private final RateControl rateControl;
    private final BitWriter writer = new BitWriter();

    /**
     * The source format code: 1 to 5 for the standard sizes, 6 for a custom format.
     */
    private final int format;

    /**
     * The picture clock in periods per second.
     */
    private final Rational clock;

    private Frame reference;
    private Frame spare;
    private long firstPts = Long.MIN_VALUE;
    private long lastPeriods = -1;
    private int framesSinceKey;
    private int rounding;
    private int lastQuant;

    /**
     * Constructs the core of a stream.
     *
     * @param settings the picture size, modes, clock and rate control of the stream
     */
    public H263EncoderCore(Settings settings) {
        this.settings = settings;
        this.format = formatOf(settings.width(), settings.height());
        var fourVectors = settings.advancedPrediction() || settings.deblocking();
        var tools = new PictureCoder.Tools(true, settings.extendedType(), settings.unrestrictedVectors(),
                settings.advancedPrediction(), fourVectors, settings.advancedIntra(), settings.deblocking(),
                settings.modifiedQuantization(), settings.groupHeaders(), settings.sliceMacroblocks(), false, false,
                Tables.DEFAULT_INTRA_MATRIX,
                Tables.DEFAULT_INTER_MATRIX, false, 0, false, false, settings.searchRange(), settings.threads());
        this.coder = new PictureCoder(settings.width(), settings.height(), tools);
        this.sources = new SourceFrames(settings.width(), settings.height());
        this.rateControl = new RateControl(settings.bitrate(), settings.frameRate().toDouble(), settings.fixedQuant(),
                settings.minQuant(), settings.maxQuant(), settings.keyframeInterval(), 0);
        var periodTicks = settings.clockConversion() == 0 ? 60060
                : settings.clockConversion() * settings.clockDivisor();
        this.clock = new Rational(periodTicks, 1_800_000);
    }

    /**
     * Returns the source format code of a picture size.
     *
     * @param width the picture width
     * @param height the picture height
     * @return 1 to 5 for the standard sizes, 6 otherwise
     */
    public static int formatOf(int width, int height) {
        for (var i = 0; i < WIDTHS.length; i++) {
            if (WIDTHS[i] == width && HEIGHTS[i] == height) {
                return i + 1;
            }
        }
        return 6;
    }

    /**
     * {@return the reconstruction of the picture coded last, or {@code null} before the first}
     */
    public Frame lastReconstruction() {
        return reference;
    }

    /**
     * {@return the quantizer of the picture coded last}
     */
    public int lastQuantizer() {
        return lastQuant;
    }

    /**
     * Changes the target bitrate from the next picture on.
     *
     * @param bitrate the new target in bits per second
     */
    public void retarget(long bitrate) {
        rateControl.retarget(bitrate);
    }

    /**
     * Codes one picture and emits its packet.
     *
     * @param picture the picture, 8-bit 4:2:0 of the stream's size
     * @param pts the presentation timestamp, larger than the previous one
     * @param duration the duration, positive
     * @param key whether the picture must be an I-picture
     * @param output receives the packet
     */
    public void encode(YuvPicture picture, long pts, long duration, boolean key, Consumer<Packet> output) {
        if (firstPts == Long.MIN_VALUE) {
            firstPts = pts;
        }
        var periods = Math.max(lastPeriods + 1, settings.timeBase().rescale(pts - firstPts, clock));
        lastPeriods = periods;
        var interval = settings.keyframeInterval();
        var intra = framesSinceKey == 0 || key || interval > 0 && framesSinceKey >= interval;
        framesSinceKey = intra ? 1 : framesSinceKey + 1;
        var type = intra ? Vop.I : Vop.P;
        var rcType = intra ? RateControl.INTRA : RateControl.PREDICTED;
        var quant = rateControl.quantizer(rcType, coder.mbWidth() * coder.mbHeight());
        lastQuant = quant;
        var source = sources.copy(picture);
        var target = spare != null ? spare : sources.acquire();
        if (type == Vop.P && settings.extendedType()) {
            rounding ^= 1;
        }
        coder.beginPicture(type, source, target, reference, null, quant, type == Vop.P ? rounding : 0, 0, 0);
        coder.analyze();
        writer.reset();
        writePictureHeader(writer, type, periods, quant);
        coder.code(writer);
        coder.finish();
        writer.alignWithZeros();
        rateControl.update(rcType, quant, writer.position());
        sources.release(source);
        spare = reference;
        reference = target;
        output.accept(new Packet(0, writer.toByteArray(), pts, pts, duration,
                intra ? KEY : EnumSet.noneOf(PacketFlag.class), PacketSideData.NONE));
    }

    /**
     * Writes a picture header (5.1): PSC, TR and PTYPE, then for the extended picture type PLUSPTYPE with the full
     * optional part, CPFMT, CPCFC, ETR and UUI, then PQUANT, CPM and PEI.
     *
     * @param out the stream
     * @param type the picture type
     * @param periods the picture time in periods of the picture clock
     * @param quant the quantizer
     */
    private void writePictureHeader(BitWriter out, int type, long periods, int quant) {
        out.write(0x20, 22);
        out.write((int) (periods & 0xFF), 8);
        out.bit(1);
        out.bit(0);
        out.write(0, 3);
        if (!settings.extendedType()) {
            out.write(format, 3);
            out.bit(type == Vop.I ? 0 : 1);
            out.bit(0);
            out.bit(0);
            out.flag(settings.advancedPrediction());
            out.bit(0);
            out.write(quant, 5);
            out.bit(0);
            out.bit(0);
            return;
        }
        out.write(7, 3);
        out.write(1, 3);
        out.write(format, 3);
        var customClock = settings.clockConversion() != 0;
        out.flag(customClock);
        out.flag(settings.unrestrictedVectors());
        out.bit(0);
        out.flag(settings.advancedPrediction());
        out.flag(settings.advancedIntra());
        out.flag(settings.deblocking());
        out.flag(settings.sliceMacroblocks() > 0);
        out.bit(0);
        out.bit(0);
        out.bit(0);
        out.flag(settings.modifiedQuantization());
        out.write(0b1000, 4);
        out.write(type == Vop.I ? 0 : 1, 3);
        out.bit(0);
        out.bit(0);
        out.bit(type == Vop.P ? rounding : 0);
        out.write(0b001, 3);
        out.bit(0);
        if (format == 6) {
            out.write(1, 4);
            out.write(settings.width() / 4 - 1, 9);
            out.bit(1);
            out.write(settings.height() / 4, 9);
        }
        if (customClock) {
            out.bit(settings.clockConversion() == 1001 ? 1 : 0);
            out.write(settings.clockDivisor(), 7);
            out.write((int) (periods >> 8 & 3), 2);
        }
        if (settings.unrestrictedVectors()) {
            out.bit(1);
        }
        if (settings.sliceMacroblocks() > 0) {
            out.bit(0);
            out.bit(0);
        }
        out.write(quant, 5);
        out.bit(0);
    }
}
