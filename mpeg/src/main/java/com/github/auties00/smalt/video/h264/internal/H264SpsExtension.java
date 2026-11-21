package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Holds a parsed H.264 sequence parameter set extension (ITU-T H.264 7.3.2.1.2), which describes
 * the auxiliary coded pictures of the sequences of its sequence parameter set.
 *
 * <p>Instances are not thread-safe: the alpha table is built on first use.
 */
public final class H264SpsExtension {
    /**
     * The {@code seq_parameter_set_id} of the sequence parameter set the extension belongs to.
     */
    private final int id;

    /**
     * The {@code aux_format_idc}: 0 for no auxiliary pictures, 1 for alpha, 2 for alpha by which
     * the color is premultiplied, 3 for an unspecified use.
     */
    private final int auxFormatIdc;

    /**
     * The bit depth of the auxiliary pictures, {@code bit_depth_aux_minus8 + 8}.
     */
    private final int bitDepthAux;

    /**
     * The {@code alpha_incr_flag}.
     */
    private final boolean alphaIncr;

    /**
     * The {@code alpha_opaque_value}.
     */
    private final int alphaOpaque;

    /**
     * The {@code alpha_transparent_value}.
     */
    private final int alphaTransparent;

    /**
     * The alpha of every auxiliary sample value at {@link #tableDepth}, or {@code null}.
     */
    private int[] table;

    /**
     * The alpha bit depth of {@link #table}.
     */
    private int tableDepth;

    /**
     * Constructs an extension.
     *
     * @param id the {@code seq_parameter_set_id}, 0 to 31
     * @param auxFormatIdc the {@code aux_format_idc}
     * @param bitDepthAux the bit depth of the auxiliary picture samples, 8 to 12
     * @param alphaIncr the {@code alpha_incr_flag}
     * @param alphaOpaque the {@code alpha_opaque_value}
     * @param alphaTransparent the {@code alpha_transparent_value}
     */
    H264SpsExtension(int id, int auxFormatIdc, int bitDepthAux, boolean alphaIncr, int alphaOpaque,
                     int alphaTransparent) {
        this.id = id;
        this.auxFormatIdc = auxFormatIdc;
        this.bitDepthAux = bitDepthAux;
        this.alphaIncr = alphaIncr;
        this.alphaOpaque = alphaOpaque;
        this.alphaTransparent = alphaTransparent;
    }

    /**
     * Parses a sequence parameter set extension.
     *
     * @param rbsp the unescaped payload, starting after the NAL header byte and followed by
     * {@link H264BitReader#PADDING} zero bytes
     * @param length the payload length
     * @return the parsed extension
     * @throws MediaException.Malformed if a field is out of its range or the payload is truncated
     */
    public static H264SpsExtension parse(byte[] rbsp, int length) {
        var reader = new H264BitReader();
        reader.init(rbsp, 0, length);
        var id = reader.readUe();
        if (id > 31) {
            throw new MediaException.Malformed("seq_parameter_set_id " + id + " out of range");
        }
        var format = reader.readUe();
        var depth = 8;
        var incr = false;
        var opaque = 0;
        var transparent = 0;
        if (format != 0) {
            depth = reader.readUe() + 8;
            if (depth > 12) {
                throw new MediaException.Malformed("bit_depth_aux_minus8 out of range");
            }
            incr = reader.readFlag();
            opaque = reader.readBits(depth + 1);
            transparent = reader.readBits(depth + 1);
        }
        if (reader.overrun()) {
            throw new MediaException.Malformed("truncated sequence parameter set extension");
        }
        return new H264SpsExtension(id, format, depth, incr, opaque, transparent);
    }

    /**
     * {@return the {@code seq_parameter_set_id}, 0 to 31}
     */
    public int id() {
        return id;
    }

    /**
     * {@return the {@code aux_format_idc}: 0 without auxiliary pictures, 1 for alpha that the
     * primary pictures are to be multiplied by, 2 for alpha the primary pictures are already
     * premultiplied by (pre-multiplied-black video), 3 or more for auxiliary pictures of other
     * uses}
     */
    public int auxFormatIdc() {
        return auxFormatIdc;
    }

    /**
     * {@return the bit depth of the auxiliary picture samples, 8 to 12}
     */
    public int bitDepthAux() {
        return bitDepthAux;
    }

    /**
     * {@return whether the auxiliary pictures are alpha planes meant for blending: an
     * {@code aux_format_idc} of 1 or 2 and distinct opaque and transparent values}
     */
    public boolean hasAlpha() {
        return (auxFormatIdc == 1 || auxFormatIdc == 2) && alphaOpaque != alphaTransparent;
    }

    /**
     * {@return whether the primary pictures are premultiplied by the alpha, {@code aux_format_idc} 2}
     */
    public boolean premultiplied() {
        return auxFormatIdc == 2;
    }

    /**
     * Returns the alpha of every auxiliary sample value: the interpretation value, the sample plus
     * one when {@code alpha_incr_flag} is set and the sample exceeds the smaller of the opaque and
     * transparent values, clipped to the range between them and scaled linearly so that the
     * transparent value maps to 0 and the opaque value to {@code (1 << depth) - 1}, rounded to
     * nearest.
     *
     * @param depth the bit depth of the alpha, 8 to 16
     * @return the alpha of each sample value, indexed by the value; owned by this extension
     */
    public int[] alphaTable(int depth) {
        if (table != null && tableDepth == depth) {
            return table;
        }
        var low = Math.min(alphaOpaque, alphaTransparent);
        var high = Math.max(alphaOpaque, alphaTransparent);
        var range = Math.max(1, high - low);
        var max = (1 << depth) - 1;
        var lut = new int[1 << bitDepthAux];
        for (var value = 0; value < lut.length; value++) {
            var interpreted = alphaIncr && value > low ? value + 1 : value;
            interpreted = Math.clamp(interpreted, low, high);
            var weight = Math.abs(interpreted - alphaTransparent);
            lut[value] = (int) (((long) weight * max + range / 2) / range);
        }
        table = lut;
        tableDepth = depth;
        return lut;
    }
}
