package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Holds the RFC 6184 parameters of an H.264 payload format and the NAL unit types the payload format adds.
 */
final class H264Format {
    /** The aggregation packet without decoding order number. */
    static final int STAP_A = 24;
    /** The aggregation packet with a decoding order number. */
    static final int STAP_B = 25;
    /** The multi-time aggregation packet with 16-bit timestamp offsets. */
    static final int MTAP16 = 26;
    /** The multi-time aggregation packet with 24-bit timestamp offsets. */
    static final int MTAP24 = 27;
    /** The fragmentation unit without decoding order number. */
    static final int FU_A = 28;
    /** The fragmentation unit with a decoding order number. */
    static final int FU_B = 29;
    /** The NAL unit type of an IDR slice. */
    static final int IDR = 5;
    /** The NAL unit type of a sequence parameter set. */
    static final int SPS = 7;
    /** The NAL unit type of a picture parameter set. */
    static final int PPS = 8;

    /** The {@code packetization-mode}, from 0 to 2. */
    final int mode;
    /** The NAL units of {@code sprop-parameter-sets}, in order. */
    final List<byte[]> parameterSets;
    /** The {@code sprop-interleaving-depth}, 0 when absent. */
    final int interleavingDepth;
    /** The {@code sprop-max-don-diff}, or {@code -1} when absent. */
    final int maxDonDiff;

    private H264Format(int mode, List<byte[]> parameterSets, int interleavingDepth, int maxDonDiff) {
        this.mode = mode;
        this.parameterSets = parameterSets;
        this.interleavingDepth = interleavingDepth;
        this.maxDonDiff = maxDonDiff;
    }

    /**
     * Reads the parameters of a format.
     *
     * @throws MediaException.Malformed if a parameter is not valid
     */
    static H264Format of(RtpPayloadFormat format) {
        var mode = format.intParameter("packetization-mode", 0, 0, 2);
        var sets = new ArrayList<byte[]>();
        var sprop = format.parameter("sprop-parameter-sets").orElse("");
        for (var item : sprop.split(",")) {
            var text = item.strip();
            if (text.isEmpty()) {
                continue;
            }
            try {
                var unit = Base64.getDecoder().decode(text.replaceAll("\\s", ""));
                if (unit.length > 0) {
                    sets.add(unit);
                }
            } catch (IllegalArgumentException exception) {
                throw new MediaException.Malformed("sprop-parameter-sets item \"" + text + "\" is not base64",
                        exception);
            }
        }
        var depth = format.intParameter("sprop-interleaving-depth", 0, 0, 32767);
        var maxDonDiff = format.intParameter("sprop-max-don-diff", -1, 0, 32767);
        return new H264Format(mode, List.copyOf(sets), depth, maxDonDiff);
    }

    static int type(int header) {
        return header & 0x1F;
    }

    static boolean isVcl(int type) {
        return type >= 1 && type <= 5;
    }
}
