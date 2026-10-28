package com.github.auties00.smalt.video.mpeg2.internal;

import com.github.auties00.smalt.internal.bits.MsbBitReader;

import java.util.HashMap;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * The variable length codes of ISO/IEC 13818-2 Annex B that the syntax streams write, found by running every bit
 * pattern of up to 12 bits through the decoder's tables: a code is a pattern the decoder reads, whole, as the value.
 * The streams are checked against the reference decoder, so a wrong table entry in the decoder shows up as a
 * mismatch rather than going unnoticed.
 */
public final class VlcCodes {
    /** A code: its bits, right-aligned, and its length. */
    public record Code(int bits, int length) {
        void write(BitWriter writer) {
            writer.put(bits, length);
        }
    }

    public static final Map<Integer, Code> ADDRESS_INCREMENT = derive(Vlc::addressIncrement, 11, Vlc.ERROR);
    public static final Map<Integer, Code> INTRA_TYPE = derive(Vlc::intraType, 2, Vlc.ERROR);
    public static final Map<Integer, Code> PREDICTIVE_TYPE = derive(Vlc::predictiveType, 6, Vlc.ERROR);
    public static final Map<Integer, Code> BIDIRECTIONAL_TYPE = derive(Vlc::bidirectionalType, 6, Vlc.ERROR);
    public static final Map<Integer, Code> PATTERN = derive(Vlc::codedBlockPattern, 9, Vlc.ERROR);
    // motion codes and dmvector take the value -1, so their decoders signal errors otherwise (or never)
    public static final Map<Integer, Code> MOTION = derive(Vlc::motionCode, 12, Integer.MIN_VALUE);
    public static final Map<Integer, Code> DUAL_PRIME = derive(Vlc::dualPrimeVector, 2, Integer.MIN_VALUE);
    public static final Map<Integer, Code> LUMA_DC = derive(Vlc::lumaDcSize, 9, Vlc.ERROR);
    public static final Map<Integer, Code> CHROMA_DC = derive(Vlc::chromaDcSize, 10, Vlc.ERROR);

    /** The flags of a macroblock type. */
    public static final int INTRA = Vlc.INTRA;
    public static final int PATTERN_FLAG = Vlc.PATTERN;
    public static final int BACKWARD = Vlc.BACKWARD;
    public static final int FORWARD = Vlc.FORWARD;
    public static final int QUANT = Vlc.QUANT;

    private VlcCodes() {
    }

    private static Map<Integer, Code> derive(ToIntFunction<MsbBitReader> decoder, int maxLength, int error) {
        var codes = new HashMap<Integer, Code>();
        for (var length = 1; length <= maxLength; length++) {
            for (var pattern = 0; pattern < 1 << length; pattern++) {
                var ones = decode(decoder, pattern, length, true);
                var zeros = decode(decoder, pattern, length, false);
                if (ones != null && ones[1] == length && zeros != null && zeros[1] == length && ones[0] == zeros[0]
                        && ones[0] != error) {
                    codes.putIfAbsent(ones[0], new Code(pattern, length));
                }
            }
        }
        return Map.copyOf(codes);
    }

    private static int[] decode(ToIntFunction<MsbBitReader> decoder, int pattern, int length, boolean ones) {
        long word = (long) pattern << (64 - length);
        if (ones) {
            word |= -1L >>> length;
        }
        var bytes = new byte[8];
        for (var i = 0; i < 8; i++) {
            bytes[i] = (byte) (word >>> (56 - 8 * i));
        }
        var reader = new MsbBitReader(bytes);
        var value = decoder.applyAsInt(reader);
        return new int[]{value, (int) reader.bitPosition()};
    }
}
