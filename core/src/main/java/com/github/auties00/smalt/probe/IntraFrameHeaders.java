package com.github.auties00.smalt.probe;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.internal.codec.Jp2Frames;

import java.nio.charset.StandardCharsets;

/**
 * Reads the stream parameters of the intra codecs from the header of a frame: the ProRes frame header (SMPTE
 * RDD 36 clause 5.1.1), the VC-3 coding unit header (SMPTE ST 2019-1 clause 7.2) and the JPEG 2000 main header
 * (ISO/IEC 15444-1 annex A), for {@link Prober}.
 */
final class IntraFrameHeaders {
    /**
     * Prevents instantiation.
     */
    private IntraFrameHeaders() {
        throw new AssertionError();
    }

    /**
     * Describes a frame header.
     *
     * @param profile the profile name, as ffprobe names it, or {@code null}
     * @param bitDepth the bit depth of the samples
     * @param chroma the chroma layout
     * @param width the frame width
     * @param height the frame height
     * @param color the color description, or {@code null}
     */
    record Header(String profile, int bitDepth, ChromaFormat chroma, int width, int height, ColorInfo color) {
    }

    /**
     * Reads the main header of the first codestream of a Motion JPEG 2000 frame.
     *
     * <p>The bit depth is the largest component precision. The chroma layout is monochrome for one component,
     * the layout of the chroma subsampling for subsampled Y'CbCr, 4:4:4 for three unsubsampled components the
     * JP2 Header box of the frame or the track declares as sYCC, and unknown otherwise (RGB, or an undeclared
     * color space).
     *
     * @param frame the coded frame
     * @param config the track's configuration (its JP2 Header box), or {@code null}
     * @return the description, without a color, with the profile of a digital cinema codestream ({@code Rsiz} 3 or 4)
     * named as ffprobe names it, or {@code null} when the frame holds no valid main header
     */
    static Header jpeg2000(byte[] frame, byte[] config) {
        var siz = Jp2Frames.firstSiz(frame);
        if (siz == null || siz.width() > Integer.MAX_VALUE || siz.height() > Integer.MAX_VALUE) {
            return null;
        }
        ChromaFormat chroma = null;
        if (siz.components() < 3) {
            chroma = ChromaFormat.MONOCHROME;
        } else if (siz.subsampledChroma()) {
            var dx = siz.subsamplingX()[1];
            var dy = siz.subsamplingY()[1];
            chroma = dx == 2 && dy == 2 ? ChromaFormat.YUV420 : dx == 2 && dy == 1 ? ChromaFormat.YUV422 : null;
        } else if (!siz.componentTransform() && Jp2Frames.headerColorSpace(Jp2Frames.isHeader(config) ? config
                : Jp2Frames.header(frame, 0, frame.length)) == 18) {
            chroma = ChromaFormat.YUV444;
        }
        var profile = switch (siz.profile()) {
            case 3 -> "JPEG 2000 digital cinema 2K";
            case 4 -> "JPEG 2000 digital cinema 4K";
            default -> null;
        };
        return new Header(profile, siz.maxDepth(), chroma, (int) siz.width(), (int) siz.height(), null);
    }

    /**
     * Reads a ProRes frame header, from a frame with its size and {@code icpf} identifier
     * (QuickTime) or without them (Matroska).
     *
     * @param frame the coded frame
     * @param config the track's codec configuration (the sample entry type), or {@code null}
     * @return the description, or {@code null} when the frame is not a ProRes frame
     */
    static Header prores(byte[] frame, byte[] config) {
        if (frame.length >= 8 && (frame[4] != 'i' || frame[5] != 'c' || frame[6] != 'p' || frame[7] != 'f')) {
            var framed = new byte[Math.min(frame.length, 64) + 8];
            System.arraycopy(frame, 0, framed, 8, framed.length - 8);
            frame = framed;
        }
        if (frame.length < 28) {
            return null;
        }
        var width = (frame[16] & 0xFF) << 8 | frame[17] & 0xFF;
        var height = (frame[18] & 0xFF) << 8 | frame[19] & 0xFF;
        var chroma444 = (frame[20] & 0xFF) >>> 6 == 3;
        String profile = null;
        if (config != null && config.length == 4) {
            profile = switch (new String(config, StandardCharsets.US_ASCII)) {
                case "apco" -> "Proxy";
                case "apcs" -> "LT";
                case "apcn" -> "Standard";
                case "apch" -> "HQ";
                case "ap4h" -> "4444";
                case "ap4x" -> "XQ";
                default -> null;
            };
        }
        var color = new ColorInfo(MatrixCoefficients.of(code(frame[24])), ColorRange.LIMITED,
                ColorPrimaries.of(code(frame[22])), TransferCharacteristics.of(code(frame[23])));
        return new Header(profile, chroma444 ? 12 : 10, chroma444 ? ChromaFormat.YUV444 : ChromaFormat.YUV422, width,
                height, color);
    }

    /**
     * Reads a VC-3 coding unit header.
     *
     * <p>An RGB coding unit (color format flag {@code CLF} set on IDs 1256 and 1270, ST 2019-1
     * clause 7.2.5) gets a color description with the identity matrix of Recommendation ITU-T
     * H.273, which marks its 4:4:4 samples as R, G and B, limited range unless the color volume
     * {@code CLV} is out of band, with BT.709 or BT.2020 primaries by the color volume; Y'CbCr
     * coding units get none. HD-profile IDs report the samples per line of Table C.1, which
     * thin-raster files of some encoders replace with the display width.
     *
     * @param frame the coded frame
     * @return the description, or {@code null} when the frame is not a VC-3 frame
     */
    static Header dnxhd(byte[] frame) {
        if (frame.length < 0x30 || frame[0] != 0 || frame[1] != 0 || frame[4] < 1 || frame[4] > 4) {
            return null;
        }
        var cid = (frame[0x28] & 0xFF) << 24 | (frame[0x29] & 0xFF) << 16 | (frame[0x2A] & 0xFF) << 8 | frame[0x2B] & 0xFF;
        var lines = (frame[0x18] & 0xFF) << 8 | frame[0x19] & 0xFF;
        var width = (frame[0x1A] & 0xFF) << 8 | frame[0x1B] & 0xFF;
        var fields = (frame[5] & 3) == 2 ? 2 : 1;
        var depth = switch ((frame[0x21] & 0xFF) >>> 5) {
            case 2 -> 10;
            case 3 -> 12;
            default -> 8;
        };
        var chroma = switch ((frame[0x2C] >>> 5) & 3) {
            case 1 -> ChromaFormat.YUV420;
            case 2 -> ChromaFormat.YUV444;
            default -> ChromaFormat.YUV422;
        };
        var clv = (frame[0x2C] >>> 1) & 3;
        var rgb = chroma == ChromaFormat.YUV444 && (frame[0x2C] & 1) != 0 && (cid == 1256 || cid == 1270);
        var primaries = clv == 1 ? ColorPrimaries.BT2020 : clv == 3 ? ColorPrimaries.UNSPECIFIED : ColorPrimaries.BT709;
        var color = rgb ? new ColorInfo(MatrixCoefficients.IDENTITY, clv == 3 ? ColorRange.FULL : ColorRange.LIMITED,
                primaries, TransferCharacteristics.UNSPECIFIED) : null;
        var profile = switch (cid) {
            case 1270 -> "DNXHR 444";
            case 1271 -> "DNXHR HQX";
            case 1272 -> "DNXHR HQ";
            case 1273 -> "DNXHR SQ";
            case 1274 -> "DNXHR LB";
            default -> "DNXHD";
        };
        if (cid >= 1235 && cid <= 1260) {
            width = switch (cid) {
                case 1244, 1259, 1260 -> 1440;
                case 1250, 1251, 1252 -> 1280;
                case 1258 -> 960;
                default -> 1920;
            };
        }
        return new Header(profile, depth, chroma, width, lines * fields, color);
    }

    /**
     * Maps a ProRes color code to an H.273 code point, treating 0 as unspecified.
     *
     * @param code the frame header byte
     * @return the code point
     */
    private static int code(byte code) {
        var value = code & 0xFF;
        return value == 0 ? 2 : value;
    }
}
