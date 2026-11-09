/* Ports psd-tools (main branch, commit 9a2e523: src/psd_tools/composite/blend.py). Copyright (c) 2019
 * Kota Yamaguchi; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.psd;

import com.github.auties00.smalt.MediaException;

/**
 * The blend modes of Photoshop layers: the function {@code B(cb, cs)} of a backdrop color and a source color that the
 * compositor mixes in where both are opaque.
 *
 * <p>Colors are the stored channel values scaled to 0..1: RGB and gray as they are, CMYK as the stored complement of
 * the ink amounts. The separable modes apply to each channel; the non-separable modes (hue, saturation, color,
 * luminosity) use the luminosity, saturation and clipping functions of ISO 32000-1 section 11.3.5.3 on the three
 * color channels (on CMYK, the cyan, magenta and yellow complements, black taken from the backdrop or, for
 * luminosity, from the source); darker and lighter color choose one whole color by luminosity, which on CMYK
 * weighs each complement by the black complement.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results.
 */
final class PsdBlend {
    static final int NORMAL = 0;
    static final int DISSOLVE = 1;
    static final int DARKEN = 2;
    static final int MULTIPLY = 3;
    static final int COLOR_BURN = 4;
    static final int LINEAR_BURN = 5;
    static final int DARKER_COLOR = 6;
    static final int LIGHTEN = 7;
    static final int SCREEN = 8;
    static final int COLOR_DODGE = 9;
    static final int LINEAR_DODGE = 10;
    static final int LIGHTER_COLOR = 11;
    static final int OVERLAY = 12;
    static final int SOFT_LIGHT = 13;
    static final int HARD_LIGHT = 14;
    static final int VIVID_LIGHT = 15;
    static final int LINEAR_LIGHT = 16;
    static final int PIN_LIGHT = 17;
    static final int HARD_MIX = 18;
    static final int DIFFERENCE = 19;
    static final int EXCLUSION = 20;
    static final int SUBTRACT = 21;
    static final int DIVIDE = 22;
    static final int HUE = 23;
    static final int SATURATION = 24;
    static final int COLOR = 25;
    static final int LUMINOSITY = 26;

    /** The color model of a gray document. */
    static final int GRAY = 1;

    /** The color model of an RGB document. */
    static final int RGB = 3;

    /** The color model of a CMYK document. */
    static final int CMYK = 4;

    private PsdBlend() {
        throw new AssertionError();
    }

    /**
     * {@return the mode of a blend mode key}
     *
     * @param key the four-character key of a layer record
     * @throws MediaException.Unsupported if the key names no blend mode
     */
    static int of(String key) {
        return switch (key) {
            case "norm", "pass" -> NORMAL;
            case "diss" -> DISSOLVE;
            case "dark" -> DARKEN;
            case "mul " -> MULTIPLY;
            case "idiv" -> COLOR_BURN;
            case "lbrn" -> LINEAR_BURN;
            case "dkCl" -> DARKER_COLOR;
            case "lite" -> LIGHTEN;
            case "scrn" -> SCREEN;
            case "div " -> COLOR_DODGE;
            case "lddg" -> LINEAR_DODGE;
            case "lgCl" -> LIGHTER_COLOR;
            case "over" -> OVERLAY;
            case "sLit" -> SOFT_LIGHT;
            case "hLit" -> HARD_LIGHT;
            case "vLit" -> VIVID_LIGHT;
            case "lLit" -> LINEAR_LIGHT;
            case "pLit" -> PIN_LIGHT;
            case "hMix" -> HARD_MIX;
            case "diff" -> DIFFERENCE;
            case "smud" -> EXCLUSION;
            case "fsub" -> SUBTRACT;
            case "fdiv" -> DIVIDE;
            case "hue " -> HUE;
            case "sat " -> SATURATION;
            case "colr" -> COLOR;
            case "lum " -> LUMINOSITY;
            default -> throw new MediaException.Unsupported("psd", "Photoshop blend mode '" + key + "'");
        };
    }

    /**
     * Computes {@code B(cb, cs)} for one pixel.
     *
     * @param mode the blend mode
     * @param model the color model, {@link #GRAY}, {@link #RGB} or {@link #CMYK} (the channel count)
     * @param backdrop the backdrop colors
     * @param backdropAt the offset of the backdrop pixel
     * @param source the source colors
     * @param sourceAt the offset of the source pixel
     * @param out receives the blended colors from index 0
     */
    static void blend(int mode, int model, float[] backdrop, int backdropAt, float[] source, int sourceAt,
                      float[] out) {
        switch (mode) {
            case DARKER_COLOR, LIGHTER_COLOR -> {
                var darker = mode == DARKER_COLOR;
                var lb = lightness(model, backdrop, backdropAt);
                var ls = lightness(model, source, sourceAt);
                var pick = darker ? ls < lb : ls > lb;
                var from = pick ? source : backdrop;
                var at = pick ? sourceAt : backdropAt;
                System.arraycopy(from, at, out, 0, model);
            }
            case HUE, SATURATION, COLOR, LUMINOSITY -> nonSeparable(mode, model, backdrop, backdropAt, source,
                    sourceAt, out);
            default -> {
                for (var channel = 0; channel < model; channel++) {
                    out[channel] = separable(mode, backdrop[backdropAt + channel], source[sourceAt + channel]);
                }
            }
        }
    }

    /**
     * {@return {@code B(cb, cs)} of a separable mode}
     *
     * @param mode the blend mode, neither non-separable nor darker or lighter color
     * @param cb the backdrop value
     * @param cs the source value
     */
    static float separable(int mode, float cb, float cs) {
        return switch (mode) {
            case DARKEN -> Math.min(cb, cs);
            case MULTIPLY -> cb * cs;
            case COLOR_BURN -> {
                if (cb >= 1) {
                    yield 1;
                }
                if (cs <= 0) {
                    yield 0;
                }
                yield 1 - Math.min(1, (1 - cb) / cs);
            }
            case LINEAR_BURN -> Math.max(0, cb + cs - 1);
            case LIGHTEN -> Math.max(cb, cs);
            case SCREEN -> cb + cs - cb * cs;
            case COLOR_DODGE -> {
                if (cb <= 0) {
                    yield 0;
                }
                if (cs >= 1) {
                    yield 1;
                }
                yield Math.min(1, cb / (1 - cs));
            }
            case LINEAR_DODGE -> Math.min(1, cb + cs);
            case OVERLAY -> hardLight(cs, cb);
            case SOFT_LIGHT -> {
                if (cs <= 0.5f) {
                    yield cb - (1 - 2 * cs) * cb * (1 - cb);
                }
                var d = cb <= 0.25f ? ((16 * cb - 12) * cb + 4) * cb : (float) Math.sqrt(cb);
                yield cb + (2 * cs - 1) * (d - cb);
            }
            case HARD_LIGHT -> hardLight(cb, cs);
            case VIVID_LIGHT -> {
                if (cs <= 0) {
                    yield 0;
                }
                if (cs >= 1) {
                    yield 1;
                }
                if (cs <= 0.5f) {
                    yield 1 - Math.min(1, (1 - cb) / (2 * cs));
                }
                yield Math.min(1, cb / (2 * (1 - cs)));
            }
            case LINEAR_LIGHT -> Math.clamp(cb + 2 * cs - 1, 0f, 1f);
            case PIN_LIGHT -> cs > 0.5f ? Math.max(cb, 2 * cs - 1) : Math.min(cb, 2 * cs);
            case HARD_MIX -> {
                // Complementary 8 and 16-bit values sum to 1 within float rounding; a sum on 1 is 1 above mid-gray.
                var total = cb + cs;
                if (Math.abs(total - 1) <= 1e-6f) {
                    yield cb > 0.5f ? 1 : 0;
                }
                yield total > 1 ? 1 : 0;
            }
            case DIFFERENCE -> Math.abs(cb - cs);
            case EXCLUSION -> cb + cs - 2 * cb * cs;
            case SUBTRACT -> Math.max(0, cb - cs);
            case DIVIDE -> {
                if (cs <= 0) {
                    yield cb <= 0 ? 0 : 1;
                }
                yield Math.min(1, cb / cs);
            }
            default -> cs;
        };
    }

    private static float hardLight(float cb, float cs) {
        if (cs <= 0.5f) {
            return cb * 2 * cs;
        }
        var s = 2 * cs - 1;
        return cb + s - cb * s;
    }

    /**
     * The luminosity by which darker and lighter color choose.
     */
    private static float lightness(int model, float[] color, int at) {
        return switch (model) {
            case GRAY -> color[at];
            case CMYK -> lum(color[at] * color[at + 3], color[at + 1] * color[at + 3], color[at + 2] * color[at + 3]);
            default -> lum(color[at], color[at + 1], color[at + 2]);
        };
    }

    private static float lum(float r, float g, float b) {
        return 0.3f * r + 0.59f * g + 0.11f * b;
    }

    private static void nonSeparable(int mode, int model, float[] backdrop, int backdropAt, float[] source,
                                     int sourceAt, float[] out) {
        if (model == GRAY) {
            out[0] = mode == LUMINOSITY ? source[sourceAt] : backdrop[backdropAt];
            return;
        }
        var br = backdrop[backdropAt];
        var bg = backdrop[backdropAt + 1];
        var bb = backdrop[backdropAt + 2];
        var sr = source[sourceAt];
        var sg = source[sourceAt + 1];
        var sb = source[sourceAt + 2];
        switch (mode) {
            case HUE -> {
                setSaturation(sr, sg, sb, saturation(br, bg, bb), out);
                setLuminosity(out[0], out[1], out[2], lum(br, bg, bb), out);
            }
            case SATURATION -> {
                setSaturation(br, bg, bb, saturation(sr, sg, sb), out);
                setLuminosity(out[0], out[1], out[2], lum(br, bg, bb), out);
            }
            case COLOR -> setLuminosity(sr, sg, sb, lum(br, bg, bb), out);
            default -> setLuminosity(br, bg, bb, lum(sr, sg, sb), out);
        }
        if (model == CMYK) {
            out[3] = mode == LUMINOSITY ? source[sourceAt + 3] : backdrop[backdropAt + 3];
        }
    }

    private static float saturation(float r, float g, float b) {
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
    }

    /**
     * Writes {@code SetLum(c, l)}: the color shifted to luminosity {@code l}, then clipped into range keeping its
     * luminosity.
     */
    private static void setLuminosity(float r, float g, float b, float l, float[] out) {
        var d = l - lum(r, g, b);
        r += d;
        g += d;
        b += d;
        var lum = lum(r, g, b);
        var min = Math.min(r, Math.min(g, b));
        var max = Math.max(r, Math.max(g, b));
        if (min < 0) {
            var scale = lum / (lum - min);
            r = lum + (r - lum) * scale;
            g = lum + (g - lum) * scale;
            b = lum + (b - lum) * scale;
        }
        if (max > 1) {
            var scale = (1 - lum) / (max - lum);
            r = lum + (r - lum) * scale;
            g = lum + (g - lum) * scale;
            b = lum + (b - lum) * scale;
        }
        out[0] = Math.clamp(r, 0f, 1f);
        out[1] = Math.clamp(g, 0f, 1f);
        out[2] = Math.clamp(b, 0f, 1f);
    }

    /**
     * Writes {@code SetSat(c, s)}: the color with its largest component {@code s}, its smallest 0 and its middle one
     * scaled between them.
     */
    private static void setSaturation(float r, float g, float b, float s, float[] out) {
        var max = Math.max(r, Math.max(g, b));
        var min = Math.min(r, Math.min(g, b));
        if (max <= min) {
            out[0] = 0;
            out[1] = 0;
            out[2] = 0;
            return;
        }
        var range = max - min;
        // The first maximum and the last minimum get the extremes, so ties keep one middle component.
        var maxIndex = r == max ? 0 : g == max ? 1 : 2;
        var minIndex = b == min && maxIndex != 2 ? 2 : g == min && maxIndex != 1 ? 1 : 0;
        var midIndex = 3 - maxIndex - minIndex;
        var mid = midIndex == 0 ? r : midIndex == 1 ? g : b;
        out[maxIndex] = s;
        out[minIndex] = 0;
        out[midIndex] = (mid - min) * s / range;
    }
}
