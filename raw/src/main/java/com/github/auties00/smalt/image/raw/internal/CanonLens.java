package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;

import java.util.function.DoubleUnaryOperator;

/**
 * Builds the {@link LensCorrection} of a Canon PowerShot file from the lens correction data of its maker note, tag
 * {@code 0x4015}: little-endian tables of the radial maps of the shot's focal length, in one of these layouts, told
 * apart by the first 16-bit word.
 *
 * <ul>
 * <li>{@code 0x8001} and {@code 0x8002} (CR2, 16-bit words): at word 17 a knot count of 7 then, from word 19, the
 * distortion as pairs of a radius in pixels and a factor in units of 2^-13, first the center's, with radii equally
 * spaced in the radius's square. A table of zero radii records no distortion; {@code 0x8001} files with a count of 0
 * record nothing. {@code 0x8002} continues with a red table and a blue one, each the center's pair at
 * words 51 and 52 (or 76 and 77) followed by seven triples of an unused word, a radius in pixels and
 * the plane's factor relative to green, as 1 plus the value's excess over 8192 in units of 2^-16.</li>
 * <li>{@code 0x8003} to {@code 0x8005} (CR2, 32-bit words): four tables, each introduced by the units {@code 16} and
 * {@code 256} or {@code 128}, of seventeen pairs from the center to the half diagonal of the sensor: the radius in
 * units of 1/16 pixel and, in the first table, the distance toward the center of the source in units of 1/256 pixel,
 * and in the third and fourth, the distance outward of the red and of the blue plane from green in units of 1/128
 * pixel; a last value of 0 is not a measurement.</li>
 * <li>Other versions (the CR3 files, 16-bit words): a table of the words {@code n}, 17, 17, then, 233 words after the
 * first of them, a table of the red plane, at 256 of the blue, at 279 of the radii (0 first) and at 302 of the
 * distortion, of seventeen values equally spaced from the center to the half diagonal of the default crop (only the
 * order of the stored radii is read): the distortion factor, as the value in units
 * of 2^-14 with its distance from 1 weighted by {@value #CR3_DISTORTION_WEIGHT}, and the plane's factor relative to
 * green, as 1 plus the value's excess over 16384 in units of 2^-19.</li>
 * </ul>
 *
 * @implNote This implementation derives the layouts and the units from the tag's bytes in CC0 files, against the
 * {@code WarpRectilinear} opcodes Adobe DNG Converter 18.0, run as a black box, writes for the same files, and against
 * the camera's own JPEG image in them. Blanking the tag of copies of the {@code 0x8001} and {@code 0x8002} files makes
 * the converter drop its opcode, so it reads those layouts; for the later ones it gives the same maps from profiles of
 * its own, unchanged by blanking, which the tag's tables match within the tolerances
 * {@code LensCorrectionOracleTest} states. The weight of the CR3 distortion is a fit to those opcodes and to the
 * camera's JPEG images, not a documented rule. The second distortion table of the {@code 0x8002} to {@code 0x8005}
 * layouts is larger than what either applies and is not read. The
 * optical center is taken as the center of the default crop, as the converter does; the offsets the tag records next
 * to the sensor size are not applied, since neither moves the center by them.
 */
final class CanonLens {
    /**
     * The maker note tag.
     */
    static final int TAG = 0x4015;

    /**
     * The weight of the CR3 distortion table's distance from 1.
     */
    private static final double CR3_DISTORTION_WEIGHT = 0.8;

    /**
     * The knots of the tables of 32-bit and CR3 words.
     */
    private static final int KNOTS = 17;

    /**
     * The largest correction, in pixels, that does not differ from the identity.
     */
    private static final double IDENTITY = 0.05;

    /**
     * The largest difference from 1 of a distortion factor taken as a lens's.
     */
    private static final double LARGEST_DISTORTION = 0.5;

    /**
     * The largest difference from 1 of a plane's factor relative to green taken as a lens's.
     */
    private static final double LARGEST_ABERRATION = 0.02;

    /**
     * The radii at which a map is checked, from one pixel to the farthest corner of the active area (a multiple of the
     * knots the correction is sampled at), and its fill scale found, up to the corner of the crop.
     */
    private static final int CHECKS = 128;

    /**
     * Prevents instantiation.
     */
    private CanonLens() {
        throw new AssertionError();
    }

    /**
     * Returns the corrections of a file's maker note. They apply to the fixed lenses of the PowerShot compacts; the
     * tag of a camera of interchangeable lenses describes the lens mounted, which raw converters correct from lens
     * profiles of their own.
     *
     * @param note the maker note directory
     * @param sensor the sensor description, with its active area and default crop set
     * @param model the camera model, or {@code null}
     * @return the corrections, or {@code null} when the file records none
     */
    static LensCorrection of(Ifd note, Sensor sensor, String model) {
        if (!applies(model)) {
            return null;
        }
        byte[] tag;
        try {
            tag = note.bytes(TAG);
        } catch (MediaException.Malformed exception) {
            // a file cut short loses its timed metadata first, at the end of its media data
            return null;
        }
        var area = sensor.active();
        var width = area[3] - area[1];
        var height = area[2] - area[0];
        return of(tag, Renderer.window(sensor, width, height, true, false), width, height);
    }

    /**
     * {@return whether the tag of a camera model's files describes its fixed lens: the PowerShot compacts'}
     *
     * @param model the camera model, or {@code null}
     */
    static boolean applies(String model) {
        return model != null && model.strip().startsWith("Canon PowerShot");
    }

    /**
     * Returns the corrections a maker note's tag records. A pincushion map, which would read past the sensor, is
     * scaled to stay inside it.
     *
     * @param tag the tag's bytes, or {@code null}
     * @param window the left, top, width and height of the default crop in the active area
     * @param width the width of the active area
     * @param height the height of the active area
     * @return the corrections, or {@code null} when the tag is absent, damaged, of an unknown layout or records no
     * correction
     */
    static LensCorrection of(byte[] tag, int[] window, int width, int height) {
        if (tag == null || tag.length < 4) {
            return null;
        }
        var reach = StrictMath.hypot(window[2] / 2.0, window[3] / 2.0);
        var map = switch (u16(tag, 0)) {
            case 0x8001, 0x8002 -> shortTables(tag);
            case 0x8003, 0x8004, 0x8005 -> intTables(tag);
            default -> cr3Tables(tag, reach);
        };
        if (map == null) {
            return null;
        }
        var centerX = window[0] + window[2] / 2.0;
        var centerY = window[1] + window[3] / 2.0;
        var corner = StrictMath.hypot(Math.max(centerX, width - centerX), Math.max(centerY, height - centerY));
        var most = 0.0;
        var moved = 0.0;
        for (var check = 0; check <= CHECKS; check++) {
            var radius = pixels((double) check / CHECKS, corner);
            var green = map.green().applyAsDouble(radius);
            var red = map.red().applyAsDouble(radius);
            var blue = map.blue().applyAsDouble(radius);
            if (!(Math.abs(green / radius - 1) <= LARGEST_DISTORTION) || !(Math.abs(red / green - 1) <= LARGEST_ABERRATION)
                    || !(Math.abs(blue / green - 1) <= LARGEST_ABERRATION)) {
                return null;
            }
            if (radius <= reach) {
                most = Math.max(most, green / radius);
                moved = Math.max(moved, Math.max(Math.abs(green - radius), Math.max(Math.abs(red - radius), Math.abs(blue - radius))));
            }
        }
        if (moved <= IDENTITY) {
            return null;
        }
        return VendorLens.sampled(centerX, centerY, corner, most > 1 ? 1 / most : 1,
                u -> map.green().applyAsDouble(pixels(u, corner)) / pixels(u, corner),
                u -> map.red().applyAsDouble(pixels(u, corner)) / map.green().applyAsDouble(pixels(u, corner)),
                u -> map.blue().applyAsDouble(pixels(u, corner)) / map.green().applyAsDouble(pixels(u, corner)), null);
    }

    /**
     * {@return a radius in pixels, one pixel at the least, at which a map's factor is defined}
     *
     * @param u the radius in units of the corner radius
     * @param corner the corner radius in pixels
     */
    private static double pixels(double u, double corner) {
        return Math.max(1, u * corner);
    }

    /**
     * {@return the map of a tag of 16-bit words, or {@code null} when it records none}
     *
     * @param tag the tag's bytes
     */
    private static RadialMap shortTables(byte[] tag) {
        var words = tag.length / 2;
        if (words < 33 || s16(tag, 17) != 7) {
            return null;
        }
        var radii = new double[7];
        var factors = new double[7];
        factors[0] = 1;
        var recorded = false;
        for (var knot = 1; knot < 7; knot++) {
            var radius = u16(tag, 2 * (19 + 2 * knot));
            radii[knot] = (double) radius * radius;
            factors[knot] = u16(tag, 2 * (20 + 2 * knot)) / 8192.0;
            recorded |= radius != 0;
        }
        if (recorded && !increasing(radii)) {
            return null;
        }
        DoubleUnaryOperator green = recorded ? r -> r * interpolate(radii, factors, r * r) : r -> r;
        if (u16(tag, 0) != 0x8002) {
            return new RadialMap(green, green, green);
        }
        if (words < 101) {
            return null;
        }
        var red = aberration(tag, 49, green);
        var blue = aberration(tag, 74, green);
        return red == null || blue == null ? null : new RadialMap(red, green, blue);
    }

    /**
     * {@return the map of a plane of the {@code 0x8002} layout, or {@code null} when its table is damaged}
     *
     * @param tag the tag's bytes
     * @param start the word two before the table's center pair
     * @param green the map of the green plane
     */
    private static DoubleUnaryOperator aberration(byte[] tag, int start, DoubleUnaryOperator green) {
        var radii = new double[8];
        var factors = new double[8];
        factors[0] = 1;
        for (var knot = 1; knot < 8; knot++) {
            radii[knot] = u16(tag, 2 * (start + 2 + 3 * knot));
            factors[knot] = 1 + (s16(tag, start + 3 + 3 * knot) - 8192) / 65536.0;
        }
        if (!increasing(radii)) {
            return null;
        }
        return r -> green.applyAsDouble(r) * interpolate(radii, factors, r);
    }

    /**
     * {@return the map of a tag of 32-bit words, or {@code null} when its tables are damaged}
     *
     * @param tag the tag's bytes
     */
    private static RadialMap intTables(byte[] tag) {
        var words = tag.length / 4;
        var tables = new int[4];
        var found = 0;
        for (var word = 0; word + 2 + 2 * KNOTS <= words && found < 4; word++) {
            var unit = s32(tag, word + 1);
            if (s32(tag, word) == 16 && unit == (found < 2 ? 256 : 128) && s32(tag, word + 2) == 0 && s32(tag, word + 3) == 0) {
                tables[found++] = word + 2;
                word += 1 + 2 * KNOTS;
            }
        }
        if (found < 4) {
            return null;
        }
        var radii = new double[KNOTS];
        var distances = new double[KNOTS];
        for (var knot = 0; knot < KNOTS; knot++) {
            radii[knot] = s32(tag, tables[0] + 2 * knot) / 16.0;
            distances[knot] = s32(tag, tables[0] + 2 * knot + 1) / 256.0;
        }
        if (!increasing(radii)) {
            return null;
        }
        DoubleUnaryOperator green = r -> r - interpolate(radii, distances, r);
        var red = outward(tag, tables[2], green);
        var blue = outward(tag, tables[3], green);
        return red == null || blue == null ? null : new RadialMap(red, green, blue);
    }

    /**
     * {@return the map of a plane of the layouts of 32-bit words, or {@code null} when its table is damaged}
     *
     * @param tag the tag's bytes
     * @param table the table's first word
     * @param green the map of the green plane
     */
    private static DoubleUnaryOperator outward(byte[] tag, int table, DoubleUnaryOperator green) {
        var count = s32(tag, table + 2 * KNOTS - 1) == 0 ? KNOTS - 1 : KNOTS;
        var radii = new double[count];
        var distances = new double[count];
        for (var knot = 0; knot < count; knot++) {
            radii[knot] = s32(tag, table + 2 * knot) / 16.0;
            distances[knot] = s32(tag, table + 2 * knot + 1) / 128.0;
        }
        if (!increasing(radii)) {
            return null;
        }
        return r -> green.applyAsDouble(r) + interpolate(radii, distances, r);
    }

    /**
     * {@return the map of a CR3 tag, or {@code null} when it holds none}
     *
     * @param tag the tag's bytes
     * @param reach the half diagonal of the default crop in pixels, the last knot's radius
     */
    private static RadialMap cr3Tables(byte[] tag, double reach) {
        var words = tag.length / 2;
        var first = -1;
        for (var word = 0; word + 302 + KNOTS <= words; word++) {
            if (u16(tag, 2 * (word + 1)) == KNOTS && u16(tag, 2 * (word + 2)) == KNOTS) {
                first = word;
                break;
            }
        }
        if (first < 0 || u16(tag, 2 * (first + 279)) != 0) {
            return null;
        }
        var radii = new double[KNOTS];
        var factors = new double[KNOTS];
        var stored = new double[KNOTS];
        for (var knot = 0; knot < KNOTS; knot++) {
            radii[knot] = reach * knot / (KNOTS - 1);
            stored[knot] = u16(tag, 2 * (first + 279 + knot));
            factors[knot] = 1 - CR3_DISTORTION_WEIGHT * (1 - u16(tag, 2 * (first + 302 + knot)) / 16384.0);
        }
        if (!increasing(stored)) {
            return null;
        }
        DoubleUnaryOperator green = r -> r * interpolate(radii, factors, r);
        return new RadialMap(cr3Plane(tag, first + 233, radii, green), green, cr3Plane(tag, first + 256, radii, green));
    }

    /**
     * {@return the map of a plane of a CR3 tag; a last value of 16384 after others is not a measurement}
     *
     * @param tag the tag's bytes
     * @param table the table's first word
     * @param radii the radii of the knots
     * @param green the map of the green plane
     */
    private static DoubleUnaryOperator cr3Plane(byte[] tag, int table, double[] radii, DoubleUnaryOperator green) {
        var count = u16(tag, 2 * (table + KNOTS - 1)) == 16384 ? KNOTS - 1 : KNOTS;
        var knots = new double[count];
        var factors = new double[count];
        for (var knot = 0; knot < count; knot++) {
            knots[knot] = radii[knot];
            factors[knot] = 1 + (u16(tag, 2 * (table + knot)) - 16384) / 524288.0;
        }
        return r -> green.applyAsDouble(r) * interpolate(knots, factors, r);
    }

    /**
     * {@return a table's value at a position by linear interpolation, the last segment continuing past the last knot}
     *
     * @param knots the positions of the knots, increasing
     * @param values the value at each knot
     * @param position where to read the table
     */
    private static double interpolate(double[] knots, double[] values, double position) {
        var segment = 0;
        while (segment + 2 < knots.length && position >= knots[segment + 1]) {
            segment++;
        }
        return values[segment] + (values[segment + 1] - values[segment]) * (position - knots[segment])
                / (knots[segment + 1] - knots[segment]);
    }

    /**
     * {@return whether knots strictly increase}
     *
     * @param knots the values, in order
     */
    private static boolean increasing(double[] knots) {
        for (var index = 1; index < knots.length; index++) {
            if (!(knots[index] > knots[index - 1])) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@return an unsigned little-endian 16-bit value}
     *
     * @param tag the bytes
     * @param at the byte offset
     */
    private static int u16(byte[] tag, int at) {
        return (tag[at] & 0xFF) | (tag[at + 1] & 0xFF) << 8;
    }

    /**
     * {@return the signed little-endian 16-bit word at a word index}
     *
     * @param tag the bytes
     * @param word the word index
     */
    private static int s16(byte[] tag, int word) {
        return (short) u16(tag, 2 * word);
    }

    /**
     * {@return the signed little-endian 32-bit word at a word index}
     *
     * @param tag the bytes
     * @param word the word index
     */
    private static int s32(byte[] tag, int word) {
        return u16(tag, 4 * word) | u16(tag, 4 * word + 2) << 16;
    }

    /**
     * The map from an output radius to a source radius, in pixels, of each color plane.
     *
     * @param red the red plane's
     * @param green the green plane's
     * @param blue the blue plane's
     */
    private record RadialMap(DoubleUnaryOperator red, DoubleUnaryOperator green, DoubleUnaryOperator blue) {
    }
}
