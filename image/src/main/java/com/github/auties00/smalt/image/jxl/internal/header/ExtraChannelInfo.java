/* Ports jxl-oxide 0.12.6 (crates/jxl-image: ExtraChannelInfo). Copyright Wonwoo Choi and the jxl-oxide contributors;
   MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.header;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;

import java.nio.charset.StandardCharsets;

/**
 * Describes one extra channel of a JPEG XL image (ISO/IEC 18181-1 table A.17,
 * {@code ExtraChannelInfo}).
 *
 * @param type the channel type, one of the {@code TYPE_} constants
 * @param bitDepth the sample format
 * @param dimShift the base-2 logarithm of the channel's subsampling, from 0 to 10
 * @param name the channel name, possibly empty
 * @param alphaAssociated whether an alpha channel is premultiplied into the color
 * @param spotColor the spot color as {@code red, green, blue, solidity}, or {@code null}
 * when the channel is not a spot color
 * @param cfaChannel the color filter array channel index, meaningful for
 * {@link #TYPE_CFA}
 */
public record ExtraChannelInfo(int type, BitDepth bitDepth, int dimShift, String name, boolean alphaAssociated,
                               float[] spotColor, int cfaChannel) {
    /**
     * An alpha channel.
     */
    public static final int TYPE_ALPHA = 0;

    /**
     * A depth map.
     */
    public static final int TYPE_DEPTH = 1;

    /**
     * A spot color.
     */
    public static final int TYPE_SPOT_COLOR = 2;

    /**
     * A selection mask.
     */
    public static final int TYPE_SELECTION_MASK = 3;

    /**
     * The black (K) channel of a CMYK image.
     */
    public static final int TYPE_BLACK = 4;

    /**
     * A color filter array channel.
     */
    public static final int TYPE_CFA = 5;

    /**
     * A thermal channel.
     */
    public static final int TYPE_THERMAL = 6;

    /**
     * A non-optional channel of unspecified meaning.
     */
    public static final int TYPE_NON_OPTIONAL = 15;

    /**
     * An optional channel of unspecified meaning.
     */
    public static final int TYPE_OPTIONAL = 16;

    /**
     * Reads an extra channel description.
     *
     * @param br the bitstream
     * @return the description
     * @throws MediaException.Malformed if the type is unknown or the name is not UTF-8
     */
    public static ExtraChannelInfo read(JxlBitReader br) {
        if (br.readBool()) {
            return new ExtraChannelInfo(TYPE_ALPHA, BitDepth.DEFAULT, 0, "", false, null, 0);
        }
        var type = br.readEnum();
        switch (type) {
            case TYPE_ALPHA, TYPE_DEPTH, TYPE_SPOT_COLOR, TYPE_SELECTION_MASK, TYPE_BLACK, TYPE_CFA, TYPE_THERMAL,
                 TYPE_NON_OPTIONAL, TYPE_OPTIONAL -> {
            }
            default -> throw new MediaException.Malformed("invalid extra channel type " + type);
        }
        var depth = BitDepth.read(br);
        var dimShift = br.readU32(0, 0, 3, 0, 4, 0, 1, 3);
        var name = readName(br);
        var associated = false;
        float[] spot = null;
        var cfa = 0;
        switch (type) {
            case TYPE_ALPHA -> associated = br.readBool();
            case TYPE_SPOT_COLOR -> spot = new float[]{br.readF16(), br.readF16(), br.readF16(), br.readF16()};
            case TYPE_CFA -> cfa = br.readU32(1, 0, 0, 2, 3, 4, 19, 8);
            default -> {
            }
        }
        return new ExtraChannelInfo(type, depth, dimShift, name, associated, spot, cfa);
    }

    /**
     * Reads a {@code Name}: a length and that many UTF-8 bytes.
     *
     * @param br the bitstream
     * @return the name
     * @throws MediaException.Malformed if the bytes are not valid UTF-8
     */
    public static String readName(JxlBitReader br) {
        var length = br.readU32(0, 0, 0, 4, 16, 5, 48, 10);
        var bytes = new byte[length];
        for (var i = 0; i < length; i++) {
            bytes[i] = (byte) br.readBits(8);
        }
        var decoder = StandardCharsets.UTF_8.newDecoder();
        try {
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new MediaException.Malformed("JPEG XL name is not UTF-8", exception);
        }
    }

    /**
     * Returns whether the channel is an alpha channel.
     *
     * @return whether the type is {@link #TYPE_ALPHA}
     */
    public boolean isAlpha() {
        return type == TYPE_ALPHA;
    }

    /**
     * Returns whether the channel is the black channel of a CMYK image.
     *
     * @return whether the type is {@link #TYPE_BLACK}
     */
    public boolean isBlack() {
        return type == TYPE_BLACK;
    }
}
