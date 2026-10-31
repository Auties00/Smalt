package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.codec.AudioConfigs;

import java.util.HexFormat;

/**
 * Holds the RFC 3640 parameters of an {@code mpeg4-generic} payload format that configure the AU header section,
 * the auxiliary section and the access unit timing.
 */
final class Mpeg4GenericFormat {
    /** The {@code mode} parameter. */
    final String mode;
    /** The {@code config} parameter, empty when absent. */
    final byte[] config;
    /** The field lengths in bits. */
    final int sizeLength;
    final int indexLength;
    final int indexDeltaLength;
    final int ctsDeltaLength;
    final int dtsDeltaLength;
    final int streamStateIndication;
    final int auxiliaryDataSizeLength;
    /** Whether each AU header carries a random access flag. */
    final boolean randomAccessIndication;
    /** The constant AU size in bytes, 0 when absent. */
    final int constantSize;
    /** The duration of one AU in clock units, from {@code constantDuration} or the configuration. */
    final int duration;
    /** The {@code maxDisplacement} in clock units, 0 when absent. */
    final long maxDisplacement;

    private Mpeg4GenericFormat(RtpPayloadFormat format) {
        this.mode = format.parameter("mode").orElse("generic");
        var hex = format.parameter("config").orElse("").strip();
        try {
            this.config = HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException exception) {
            throw new MediaException.Malformed("config=" + hex + " is not hexadecimal", exception);
        }
        this.sizeLength = format.intParameter("sizelength", 0, 0, 32);
        this.indexLength = format.intParameter("indexlength", 0, 0, 32);
        this.indexDeltaLength = format.intParameter("indexdeltalength", 0, 0, 32);
        this.ctsDeltaLength = format.intParameter("ctsdeltalength", 0, 0, 32);
        this.dtsDeltaLength = format.intParameter("dtsdeltalength", 0, 0, 32);
        this.streamStateIndication = format.intParameter("streamstateindication", 0, 0, 32);
        this.auxiliaryDataSizeLength = format.intParameter("auxiliarydatasizelength", 0, 0, 32);
        this.randomAccessIndication = format.flagParameter("randomaccessindication");
        this.constantSize = format.intParameter("constantsize", 0, 0, Integer.MAX_VALUE);
        this.maxDisplacement = format.intParameter("maxdisplacement", 0, 0, Integer.MAX_VALUE);
        var constant = format.intParameter("constantduration", 0, 0, Integer.MAX_VALUE);
        this.duration = constant > 0 ? constant : configDuration(config, format.clockRate());
    }

    /**
     * Reads the parameters of a format.
     *
     * @throws MediaException.Malformed if a parameter is not valid
     */
    static Mpeg4GenericFormat of(RtpPayloadFormat format) {
        return new Mpeg4GenericFormat(format);
    }

    private static int configDuration(byte[] config, int clockRate) {
        if (config.length < 2) {
            return 1024;
        }
        try {
            var parsed = AudioConfigs.parseAac(config);
            if (parsed.sampleRate() <= 0) {
                return parsed.frameLength();
            }
            return (int) ((long) parsed.frameLength() * clockRate / parsed.sampleRate());
        } catch (MediaException exception) {
            return 1024;
        }
    }

    /** Returns whether AU headers are present at all. */
    boolean hasHeaders() {
        return sizeLength + indexLength + indexDeltaLength + ctsDeltaLength + dtsDeltaLength
                + streamStateIndication > 0 || randomAccessIndication;
    }

    /** Returns whether access units may be fragmented across packets. */
    boolean fragmentable() {
        return !mode.equalsIgnoreCase("AAC-lbr") && !mode.equalsIgnoreCase("CELP-vbr");
    }
}
