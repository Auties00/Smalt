package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.internal.subtitle.bitmap.Palettes;
import com.github.auties00.smalt.internal.subtitle.bitmap.VobSubHeader;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * Converts the configurations of bitmap subtitle tracks between the forms the codecs define and the forms the
 * containers store.
 */
public final class BitmapTrackConfigs {
    /**
     * The first line of a VobSub index file.
     */
    public static final String VOBSUB_SIGNATURE = "# VobSub index file, v7 (do not modify this line!)";

    private BitmapTrackConfigs() {
        throw new AssertionError();
    }

    /**
     * Converts the Matroska {@code S_DVBSUB} codec private data to a subtitling descriptor entry.
     *
     * @param codecPrivate the composition page, ancillary page and subtitling type, five bytes, or {@code null}
     * @param language the ISO 639-2 language of the track, or {@code null}
     * @return the eight-byte descriptor entry, or {@code null} when the codec private data is too short
     */
    public static byte[] dvbFromMatroska(byte[] codecPrivate, String language) {
        if (codecPrivate == null || codecPrivate.length < 4) {
            return null;
        }
        var entry = new byte[8];
        var code = (language == null || language.length() != 3 ? "und" : language).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(code, 0, entry, 0, 3);
        entry[3] = codecPrivate.length >= 5 ? codecPrivate[4] : 0x10;
        System.arraycopy(codecPrivate, 0, entry, 4, 4);
        return entry;
    }

    /**
     * Converts a subtitling descriptor body to the Matroska {@code S_DVBSUB} codec private data.
     *
     * @param config the descriptor body, eight bytes per service, or {@code null}
     * @return the composition page, ancillary page and subtitling type of the first service; page 1 and type
     * {@code 0x10} (normal subtitles for a 4:3 display) without a configuration
     */
    public static byte[] matroskaDvb(byte[] config) {
        if (config == null || config.length < 8) {
            return new byte[]{0, 1, 0, 1, 0x10};
        }
        return new byte[]{config[4], config[5], config[6], config[7], config[3]};
    }

    /**
     * Returns a subtitling descriptor body for a track, for a transport stream.
     *
     * @param config the track configuration, or {@code null}
     * @param language the ISO 639-2 language of the track, or {@code null}
     * @return the configuration when it holds at least one entry, otherwise an entry for page 1
     */
    public static byte[] dvbDescriptor(byte[] config, String language) {
        if (config != null && config.length >= 8) {
            return config.length % 8 == 0 ? config : Arrays.copyOf(config, config.length - config.length % 8);
        }
        return dvbFromMatroska(new byte[]{0, 1, 0, 1, 0x10}, language);
    }

    /**
     * Returns the VobSub index header of a DVD subtitle track, writing a default one for a track without.
     *
     * @param config the track configuration, or {@code null}
     * @return the header in UTF-8
     */
    public static byte[] vobSubHeader(byte[] config) {
        if (config != null && config.length > 0) {
            return config;
        }
        return header(720, 480, grayPalette());
    }

    /**
     * Writes a VobSub index header.
     *
     * @param width the frame width
     * @param height the frame height
     * @param palette the 16 palette colors as {@code 0xRRGGBB}
     * @return the header in UTF-8, ending with a line feed
     */
    public static byte[] header(int width, int height, int[] palette) {
        var text = new StringBuilder(VOBSUB_SIGNATURE).append('\n')
                .append("size: ").append(width).append('x').append(height).append('\n')
                .append("palette: ");
        for (var index = 0; index < 16; index++) {
            if (index > 0) {
                text.append(", ");
            }
            text.append(String.format(Locale.ROOT, "%06x", palette[index] & 0xFFFFFF));
        }
        text.append('\n');
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Converts the decoder specific information of an MP4 {@code mp4s} sample entry with object type {@code 0xE0}
     * to a VobSub index header.
     *
     * @param dsi the 16 palette entries of four bytes each (zero, Y, Cr, Cb), or {@code null}
     * @param width the frame width, or {@code 0} when unknown
     * @param height the frame height, or {@code 0} when unknown
     * @return the header in UTF-8
     */
    public static byte[] vobSubFromMp4(byte[] dsi, int width, int height) {
        var palette = grayPalette();
        if (dsi != null && dsi.length >= 64) {
            for (var index = 0; index < 16; index++) {
                var y = dsi[index * 4 + 1] & 0xFF;
                var cr = dsi[index * 4 + 2] & 0xFF;
                var cb = dsi[index * 4 + 3] & 0xFF;
                palette[index] = Palettes.ycbcr(y, cb, cr, 255, false) >>> 8;
            }
        }
        return header(width > 0 ? width : 720, height > 0 ? height : 480, palette);
    }

    /**
     * Converts the palette of a VobSub index header to the decoder specific information of an MP4 {@code mp4s}
     * sample entry.
     *
     * @param config the VobSub index header, or {@code null}
     * @return the 16 palette entries of four bytes each (zero, Y, Cr, Cb)
     */
    public static byte[] mp4FromVobSub(byte[] config) {
        var header = VobSubHeader.parse(config);
        var palette = header.palette() != null ? header.palette() : grayPalette();
        var dsi = new byte[64];
        for (var index = 0; index < 16; index++) {
            var r = palette[index] >> 16 & 0xFF;
            var g = palette[index] >> 8 & 0xFF;
            var b = palette[index] & 0xFF;
            var y = Math.round(16 + 0.256788 * r + 0.504129 * g + 0.097906 * b);
            var cb = Math.round(128 - 0.148223 * r - 0.290993 * g + 0.439216 * b);
            var cr = Math.round(128 + 0.439216 * r - 0.367788 * g - 0.071427 * b);
            dsi[index * 4 + 1] = (byte) clamp(y);
            dsi[index * 4 + 2] = (byte) clamp(cr);
            dsi[index * 4 + 3] = (byte) clamp(cb);
        }
        return dsi;
    }

    private static int clamp(long value) {
        return (int) Math.max(0, Math.min(255, value));
    }

    private static int[] grayPalette() {
        var palette = new int[16];
        for (var index = 0; index < 16; index++) {
            palette[index] = index * 0x111111;
        }
        return palette;
    }
}
