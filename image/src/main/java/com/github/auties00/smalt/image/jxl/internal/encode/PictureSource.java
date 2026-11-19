package com.github.auties00.smalt.image.jxl.internal.encode;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.ColorModel;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ColorTransform;
import com.github.auties00.smalt.image.color.ProfileColorSpace;
import com.github.auties00.smalt.image.jxl.internal.header.BitDepth;
import com.github.auties00.smalt.image.jxl.internal.header.ColorEncoding;
import com.github.auties00.smalt.image.jxl.internal.header.ExtraChannelInfo;
import com.github.auties00.smalt.image.ops.ImageOps;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts pictures to the planes and header fields the encoder codes.
 */
public final class PictureSource {
    private static final BitDepth FLOAT32 = new BitDepth(true, 32, 8);

    private PictureSource() {
        throw new AssertionError();
    }

    /**
     * Converts a picture.
     *
     * <p>An 8-bit RGBA picture whose color samples are all gray is coded as a gray image when it has no color
     * profile or a gray one, a 16-bit one when it has a gray one; 16-bit pictures are coded at 16 bits. CMYK
     * channel pictures store their inks inverted, as JPEG XL does, with the black ink as an extra channel.
     *
     * @param picture the picture to convert
     * @param profile the color profile, or {@code null} for sRGB
     * @param orientation the EXIF orientation code, from 1 to 8
     * @param lossless whether the samples are coded losslessly, which keeps an ICC profile's samples as they are
     * @return the source
     * @throws MediaException.Unsupported if the picture is YUV of more than 16 bits
     * @throws IllegalArgumentException if the profile does not describe the picture's channels: a gray profile
     * for color samples, a CMYK profile for a picture that is not CMYK, or another color space
     */
    public static JxlImageEncoder.Source of(Picture picture, ColorProfile profile, int orientation,
                                            boolean lossless) {
        return of(picture, profile, orientation, lossless, true);
    }

    /**
     * Converts a picture, optionally never coding an RGBA picture as gray.
     *
     * @param picture the picture to convert
     * @param profile the color profile, or {@code null} for sRGB
     * @param orientation the EXIF orientation code, from 1 to 8
     * @param lossless whether the samples are coded losslessly
     * @param allowGray whether an RGBA picture of gray samples may be coded as gray
     * @return the source
     */
    public static JxlImageEncoder.Source of(Picture picture, ColorProfile profile, int orientation,
                                            boolean lossless, boolean allowGray) {
        var p = switch (picture) {
            case RgbaPicture r -> r;
            case Rgba16Picture r -> r;
            case ChannelPicture c -> c;
            default -> ImageOps.toRgba(picture);
        };
        return switch (p) {
            case RgbaPicture r -> fromRgba(r, profile, orientation, allowGray);
            case Rgba16Picture r -> fromRgba16(r, profile, orientation, allowGray);
            case ChannelPicture c -> fromChannels(c, profile, orientation);
            default -> throw new AssertionError();
        };
    }

    private static JxlImageEncoder.Source fromRgba(RgbaPicture r, ColorProfile profile, int orientation,
                                                   boolean allowGray) {
        var w = r.width();
        var h = r.height();
        var px = r.pixels();
        var stride = r.stride();
        var gray = allowGray && (profile == null || profile.colorSpace() == ProfileColorSpace.GRAY);
        for (var y = 0; y < h && gray; y++) {
            for (var x = 0; x < w; x++) {
                var o = y * stride + x * 4;
                if (px[o] != px[o + 1] || px[o] != px[o + 2]) {
                    gray = false;
                    break;
                }
            }
        }
        var planes = new ArrayList<int[]>();
        var colors = gray ? 1 : 3;
        for (var c = 0; c < colors; c++) {
            var plane = new int[w * h];
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    plane[y * w + x] = px[y * stride + x * 4 + c] & 0xFF;
                }
            }
            planes.add(plane);
        }
        var extra = new ArrayList<int[]>();
        var info = new ArrayList<ExtraChannelInfo>();
        if (r.hasAlpha()) {
            var plane = new int[w * h];
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    plane[y * w + x] = px[y * stride + x * 4 + 3] & 0xFF;
                }
            }
            extra.add(plane);
            info.add(alpha(BitDepth.DEFAULT, false));
        }
        return source(w, h, planes, BitDepth.DEFAULT, extra, info, profile, gray, orientation);
    }

    private static JxlImageEncoder.Source fromRgba16(Rgba16Picture r, ColorProfile profile, int orientation,
                                                     boolean allowGray) {
        var w = r.width();
        var h = r.height();
        var px = r.pixels();
        var stride = r.stride();
        var bits = 16;
        var gray = allowGray && profile != null && profile.colorSpace() == ProfileColorSpace.GRAY;
        for (var y = 0; y < h && gray; y++) {
            for (var x = 0; x < w; x++) {
                var o = y * stride + x * 4;
                if (px[o] != px[o + 1] || px[o] != px[o + 2]) {
                    gray = false;
                    break;
                }
            }
        }
        var planes = new ArrayList<int[]>();
        for (var c = 0; c < (gray ? 1 : 3); c++) {
            planes.add(plane16(px, stride, w, h, 4, c, bits));
        }
        var extra = new ArrayList<int[]>();
        var info = new ArrayList<ExtraChannelInfo>();
        var depth = new BitDepth(false, bits, 0);
        if (r.hasAlpha()) {
            extra.add(plane16(px, stride, w, h, 4, 3, bits));
            info.add(alpha(depth, false));
        }
        return source(w, h, planes, depth, extra, info, profile, gray, orientation);
    }

    private static int[] plane16(short[] px, int stride, int w, int h, int channels, int c, int bits) {
        var plane = new int[w * h];
        var max = (1 << bits) - 1;
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                var v = px[y * stride + x * channels + c] & 0xFFFF;
                plane[y * w + x] = bits == 16 ? v : (int) Math.round(v * (double) max / 65535);
            }
        }
        return plane;
    }

    private static JxlImageEncoder.Source fromChannels(ChannelPicture p, ColorProfile profile, int orientation) {
        var layout = p.layout();
        var w = p.width();
        var h = p.height();
        var n = p.channelCount();
        var model = layout.colorModel();
        var colorCount = layout.colorChannels();
        var cmyk = model == ColorModel.CMYK;
        if (cmyk && (profile == null || profile.colorSpace() != ProfileColorSpace.CMYK)) {
            throw new IllegalArgumentException("a CMYK picture needs a CMYK color profile");
        }
        BitDepth depth;
        int[][] all = new int[n][];
        switch (p) {
            case Channel8Picture c -> {
                depth = BitDepth.DEFAULT;
                for (var k = 0; k < n; k++) {
                    all[k] = new int[w * h];
                    for (var y = 0; y < h; y++) {
                        for (var x = 0; x < w; x++) {
                            all[k][y * w + x] = c.pixels()[y * c.stride() + x * n + k] & 0xFF;
                        }
                    }
                }
            }
            case Channel16Picture c -> {
                var bits = 16;
                depth = new BitDepth(false, bits, 0);
                for (var k = 0; k < n; k++) {
                    all[k] = plane16(c.pixels(), c.stride(), w, h, n, k, bits);
                }
            }
            case ChannelFloatPicture c -> {
                depth = FLOAT32;
                for (var k = 0; k < n; k++) {
                    all[k] = new int[w * h];
                    for (var y = 0; y < h; y++) {
                        for (var x = 0; x < w; x++) {
                            all[k][y * w + x] = Float.floatToRawIntBits(c.pixels()[y * c.stride() + x * n + k]);
                        }
                    }
                }
            }
        }
        var max = depth.floatSample() ? 0 : (1 << depth.bitsPerSample()) - 1;
        if (cmyk) {
            for (var k = 0; k < 4; k++) {
                invert(all[k], depth, max);
            }
        }
        var planes = new ArrayList<int[]>();
        var extra = new ArrayList<int[]>();
        var info = new ArrayList<ExtraChannelInfo>();
        var colorPlanes = cmyk ? 3 : colorCount;
        for (var k = 0; k < colorPlanes; k++) {
            planes.add(all[k]);
        }
        if (cmyk) {
            extra.add(all[3]);
            info.add(new ExtraChannelInfo(ExtraChannelInfo.TYPE_BLACK, depth, 0, "", false, null, 0));
        }
        for (var k = colorCount; k < n; k++) {
            var channel = layout.channel(k);
            extra.add(all[k]);
            info.add(extraInfo(channel.type(), channel.name(), depth));
        }
        return source(w, h, planes, depth, extra, info, profile, colorPlanes == 1, orientation);
    }

    private static void invert(int[] plane, BitDepth depth, int max) {
        for (var i = 0; i < plane.length; i++) {
            if (depth.floatSample()) {
                plane[i] = Float.floatToRawIntBits(1.0f - Float.intBitsToFloat(plane[i]));
            } else {
                plane[i] = max - plane[i];
            }
        }
    }

    private static ExtraChannelInfo extraInfo(ChannelType type, String name, BitDepth depth) {
        var label = name == null ? "" : name;
        return switch (type) {
            case ALPHA -> alpha(depth, false);
            case PREMULTIPLIED_ALPHA -> alpha(depth, true);
            case DEPTH -> new ExtraChannelInfo(ExtraChannelInfo.TYPE_DEPTH, depth, 0, label, false, null, 0);
            case SELECTION_MASK -> new ExtraChannelInfo(ExtraChannelInfo.TYPE_SELECTION_MASK, depth, 0, label,
                    false, null, 0);
            case THERMAL -> new ExtraChannelInfo(ExtraChannelInfo.TYPE_THERMAL, depth, 0, label, false, null, 0);
            case SPOT -> new ExtraChannelInfo(ExtraChannelInfo.TYPE_SPOT_COLOR, depth, 0, label, false,
                    new float[]{0, 0, 0, 0}, 0);
            case BLACK -> new ExtraChannelInfo(ExtraChannelInfo.TYPE_BLACK, depth, 0, label, false, null, 0);
            default -> new ExtraChannelInfo(ExtraChannelInfo.TYPE_OPTIONAL, depth, 0, label, false, null, 0);
        };
    }

    private static ExtraChannelInfo alpha(BitDepth depth, boolean premultiplied) {
        return new ExtraChannelInfo(ExtraChannelInfo.TYPE_ALPHA, depth, 0, "", premultiplied, null, 0);
    }

    private static JxlImageEncoder.Source source(int w, int h, List<int[]> planes, BitDepth depth,
                                                 List<int[]> extra, List<ExtraChannelInfo> info,
                                                 ColorProfile profile, boolean gray, int orientation) {
        ColorEncoding encoding;
        byte[] icc = null;
        if (profile == null || (!gray && profile.isSrgb())) {
            encoding = gray ? new ColorEncoding(false, ColorEncoding.GRAY, ColorEncoding.WHITE_D65, null,
                    ColorEncoding.PRIMARIES_SRGB, null, ColorEncoding.TF_SRGB, 0, 1) : ColorEncoding.SRGB;
        } else {
            var space = switch (profile.colorSpace()) {
                case GRAY -> ColorEncoding.GRAY;
                case RGB -> ColorEncoding.RGB;
                default -> ColorEncoding.UNKNOWN;
            };
            if (gray && space == ColorEncoding.RGB) {
                return source(w, h, expand(planes), depth, extra, info, profile, false, orientation);
            }
            var black = info.stream().anyMatch(e -> e.type() == ExtraChannelInfo.TYPE_BLACK);
            if ((space == ColorEncoding.GRAY && !gray) || (space == ColorEncoding.UNKNOWN
                    && (profile.colorSpace() != ProfileColorSpace.CMYK || !black))) {
                throw new IllegalArgumentException("a " + profile.colorSpace()
                        + " color profile does not describe the picture's channels");
            }
            encoding = new ColorEncoding(true, space, ColorEncoding.WHITE_D65, null, ColorEncoding.PRIMARIES_SRGB,
                    null, ColorEncoding.TF_SRGB, 0, 1);
            icc = profile.toByteArray();
        }
        return new JxlImageEncoder.Source(w, h, planes, depth, extra, info, encoding, icc, orientation);
    }

    private static List<int[]> expand(List<int[]> gray) {
        return List.of(gray.getFirst(), gray.getFirst().clone(), gray.getFirst().clone());
    }

    /**
     * Converts the color planes of a source with an embedded RGB or gray profile to linear BT.2020 with the D65
     * white, the wide space the lossy path converts to XYB from.
     *
     * @param s the source, with an ICC profile
     * @param profile the embedded color profile of the source
     * @return a source of 16-bit linear BT.2020 planes described by an enumerated encoding
     */
    public static JxlImageEncoder.Source linearized(JxlImageEncoder.Source s, ColorProfile profile) {
        var target = ColorProfile.rgb(new com.github.auties00.smalt.image.Chromaticity(0.708, 0.292),
                new com.github.auties00.smalt.image.Chromaticity(0.170, 0.797),
                new com.github.auties00.smalt.image.Chromaticity(0.131, 0.046),
                new com.github.auties00.smalt.image.Chromaticity(0.3127, 0.329), 1.0);
        var transform = ColorTransform.create(profile, target);
        var n = s.width() * s.height();
        var in = profile.channels();
        var src = new short[n * in];
        var bits = s.depth().bitsPerSample();
        for (var i = 0; i < n; i++) {
            for (var c = 0; c < in; c++) {
                int v;
                if (s.depth().floatSample()) {
                    v = (int) Math.round(Math.clamp(Float.intBitsToFloat(s.color().get(c)[i]), 0, 1) * 65535);
                } else {
                    v = (int) Math.round(s.color().get(c)[i] * 65535.0 / ((1L << bits) - 1));
                }
                src[i * in + c] = (short) v;
            }
        }
        var dst = new short[n * 3];
        transform.transform(src, 0, in, dst, 0, 3, n);
        var planes = new ArrayList<int[]>();
        for (var c = 0; c < 3; c++) {
            var plane = new int[n];
            for (var i = 0; i < n; i++) {
                plane[i] = dst[i * 3 + c] & 0xFFFF;
            }
            planes.add(plane);
        }
        var encoding = new ColorEncoding(false, ColorEncoding.RGB, ColorEncoding.WHITE_D65, null,
                ColorEncoding.PRIMARIES_BT2100, null, ColorEncoding.TF_LINEAR, 0, 1);
        return new JxlImageEncoder.Source(s.width(), s.height(), planes, new BitDepth(false, 16, 0), s.extra(),
                s.extraInfo(), encoding, null, s.orientation());
    }
}
