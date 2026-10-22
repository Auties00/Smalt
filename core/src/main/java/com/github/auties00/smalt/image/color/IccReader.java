/* Ports Little CMS 2.17 (cmsio0.c, cmstypes.c). Copyright Marti Maria Saguer; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.MediaException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parses an ICC profile (versions 2 and 4): its header, its tag directory, and on demand the tags
 * a transform needs.
 *
 * <p>The constructor validates the header and the tag directory only; tags are decoded when first
 * read and remembered. A tag whose bounds leave the profile is dropped from the directory, as
 * Little CMS does; a tag that is present but cannot be decoded raises
 * {@link MediaException.Malformed} when it is read.
 *
 * <p>Instances are immutable after construction (the tag cache is concurrent) and thread-safe.
 *
 * @implNote This implementation follows the ICC.1:2022 specification and the tag readers of Little CMS 2.17
 * ({@code cmsio0.c} and {@code cmstypes.c}): a {@code curv} of one entry is an 8.8 fixed-point gamma, {@code para}
 * function types {@code 0..4} map to the Little CMS types {@code 1..5}, the {@code lut8Type} and {@code lut16Type}
 * matrix is applied whenever it is not the identity and the input has three channels, the {@code lutAtoBType} and
 * {@code lutBtoAType} elements are read in their fixed order, a {@code multiProcessElementType} skips its
 * {@code bACS} and {@code eACS} elements and rejects a {@code float32Number} that is not zero or normal or whose
 * magnitude exceeds {@code 1e20}, and a {@code namedColor2Type} name keeps at most 31 characters of its prefix and
 * suffix and 32 of its root.
 */
final class IccReader {
    /**
     * The size of the profile header in bytes.
     */
    static final int HEADER_SIZE = 128;

    /**
     * The largest number of tags accepted in the directory.
     */
    private static final int MAX_TAGS = 1024;

    /**
     * The largest number of channels of a lookup table.
     */
    private static final int MAX_CHANNELS = 15;

    /**
     * The largest number of 16-bit samples of one lookup table grid.
     */
    private static final long MAX_CLUT_SAMPLES = 1L << 22;

    /**
     * The length of a name field of a {@code namedColor2Type}.
     */
    private static final int NAME_LENGTH = 32;

    /**
     * Holds the colors of a {@code namedColor2Type} tag.
     *
     * @param prefix the prefix of every name
     * @param suffix the suffix of every name
     * @param roots the root name of each color
     * @param pcs the three 16-bit connection space values of each color, in the legacy L*a*b* or the XYZ encoding
     * @param devices the number of device coordinates of each color, {@code 0} for none
     * @param device the 16-bit device coordinates of each color
     */
    record NamedColors(String prefix, String suffix, String[] roots, int[] pcs, int devices, int[] device) {
        /**
         * {@return the number of colors}
         */
        int count() {
            return roots.length;
        }
    }

    /**
     * The profile bytes, not copied.
     */
    private final byte[] data;

    /**
     * The profile size the header declares, bounded by the data length.
     */
    private final int size;

    /**
     * The raw version field of the header.
     */
    final int version;

    /**
     * The device class signature.
     */
    final int deviceClass;

    /**
     * The data color space signature.
     */
    final int colorSpace;

    /**
     * The profile connection space signature.
     */
    final int pcs;

    /**
     * The rendering intent of the header.
     */
    final int intent;

    /**
     * The tag directory: signature to {@code {offset, size}}.
     */
    private final Map<Integer, int[]> tags;

    /**
     * The decoded tags, keyed by signature.
     */
    private final Map<Integer, Object> cache = new ConcurrentHashMap<>();

    /**
     * The decoded curves, keyed by the bytes of their tag, so that tags holding the same curve
     * (the three channels of a matrix profile usually do) decode it once.
     */
    private final Map<ByteBuffer, ToneCurve> curves = new ConcurrentHashMap<>();

    /**
     * Parses the header and the tag directory of a profile.
     *
     * @param data the profile bytes, not copied
     * @throws MediaException.Malformed if the header or the tag directory is invalid
     */
    IccReader(byte[] data) {
        this.data = data;
        if (data.length < HEADER_SIZE + 4) {
            throw new MediaException.Malformed("ICC profile of " + data.length + " bytes is shorter than its header");
        }
        if (u32(36) != signature("acsp")) {
            throw new MediaException.Malformed("not an ICC profile: missing 'acsp' signature");
        }
        var declared = u32(0) & 0xFFFFFFFFL;
        this.size = (int) Math.min(declared, data.length);
        if (size < HEADER_SIZE + 4) {
            throw new MediaException.Malformed("ICC profile declares " + declared + " bytes, shorter than its header");
        }
        this.version = u32(8);
        this.deviceClass = u32(12);
        this.colorSpace = u32(16);
        this.pcs = u32(20);
        this.intent = u32(64);
        if (channels(colorSpace) == 0) {
            throw new MediaException.Malformed("ICC profile with unknown color space " + name(colorSpace));
        }
        if (pcs != signature("XYZ ") && pcs != signature("Lab ") && deviceClass != signature("link")) {
            throw new MediaException.Malformed("ICC profile with unknown connection space " + name(pcs));
        }
        var count = u32(HEADER_SIZE) & 0xFFFFFFFFL;
        if (count > MAX_TAGS || HEADER_SIZE + 4 + count * 12 > size) {
            throw new MediaException.Malformed("ICC profile tag table of " + count + " entries does not fit");
        }
        this.tags = new HashMap<>();
        for (var i = 0; i < count; i++) {
            var entry = HEADER_SIZE + 4 + i * 12;
            var tag = u32(entry);
            var offset = u32(entry + 4) & 0xFFFFFFFFL;
            var length = u32(entry + 8) & 0xFFFFFFFFL;
            if (offset + length > size || length < 8) {
                continue;
            }
            tags.putIfAbsent(tag, new int[]{(int) offset, (int) length});
        }
    }

    /**
     * Returns whether the profile is version 4 or later.
     *
     * @return {@code true} for a version 4 profile
     */
    boolean isVersion4() {
        return (version & 0xFFFFFFFFL) >= 0x4000000L;
    }

    /**
     * Returns whether the profile has a tag.
     *
     * @param tag the tag signature
     * @return {@code true} if the directory lists it
     */
    boolean has(int tag) {
        return tags.containsKey(tag);
    }

    /**
     * Returns whether the profile has every tag of a list.
     *
     * @param tags the tag signatures as four-character strings
     * @return {@code true} if the directory lists all of them
     */
    boolean hasAll(String... tags) {
        for (var tag : tags) {
            if (!has(signature(tag))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the type signature of a tag.
     *
     * @param tag the tag signature
     * @return the type signature, or {@code 0} when the tag is absent
     */
    int typeOf(int tag) {
        var entry = tags.get(tag);
        return entry == null ? 0 : u32(entry[0]);
    }

    /**
     * Reads an {@code XYZType} tag.
     *
     * @param tag the tag signature
     * @return the first XYZ value, or {@code null} when the tag is absent
     * @throws MediaException.Malformed if the tag is not a valid XYZ tag
     */
    double[] xyz(int tag) {
        var entry = tags.get(tag);
        if (entry == null) {
            return null;
        }
        return ((double[]) cache.computeIfAbsent(tag, _ -> {
            var at = entry[0];
            expectType(at, "XYZ ", tag);
            require(entry[1] >= 20, tag);
            return new double[]{s15Fixed16(at + 8), s15Fixed16(at + 12), s15Fixed16(at + 16)};
        })).clone();
    }

    /**
     * Reads a {@code curveType} or {@code parametricCurveType} tag.
     *
     * @param tag the tag signature
     * @return the curve, or {@code null} when the tag is absent
     * @throws MediaException.Malformed if the tag is not a valid curve
     */
    ToneCurve curve(int tag) {
        var entry = tags.get(tag);
        if (entry == null) {
            return null;
        }
        if (entry[0] < 0 || entry[1] < 0 || (long) entry[0] + entry[1] > data.length) {
            return (ToneCurve) cache.computeIfAbsent(tag, _ -> decodeCurve(tag, entry));
        }
        return curves.computeIfAbsent(ByteBuffer.wrap(data, entry[0], entry[1]).asReadOnlyBuffer(),
                _ -> decodeCurve(tag, entry));
    }

    /**
     * Decodes the curve of a tag.
     *
     * @param tag the tag signature
     * @param entry the offset and size of the tag data
     * @return the curve
     * @throws MediaException.Malformed if the tag is not a valid curve
     */
    private ToneCurve decodeCurve(int tag, int[] entry) {
        var curve = readCurve(entry[0], entry[0] + entry[1]);
        if (curve == null) {
            throw new MediaException.Malformed("ICC tag " + name(tag) + " is not a curve");
        }
        return curve.curve;
    }

    /**
     * Reads the {@code cicp} tag of ICC.1:2022 (version 4.4, section 9.2.17).
     *
     * <p>The code points follow the reserved field of the {@code cicpType}; in a tag of at least 16 bytes whose
     * first four bytes after the reserved field are zero (defined color primaries and transfer characteristics
     * are never zero), they follow those four bytes instead, the layout Little CMS 2.16 and 2.17 write.
     *
     * @return the color primaries, transfer characteristics, matrix coefficients and video full range flag codes,
     * or {@code null} when the tag is absent or is not a {@code cicpType} of at least 12 bytes
     */
    int[] cicp() {
        var entry = tags.get(signature("cicp"));
        if (entry == null || entry[1] < 12 || u32(entry[0]) != signature("cicp")) {
            return null;
        }
        var at = entry[0] + 8;
        if (entry[1] >= 16 && u32(at) == 0) {
            at += 4;
        }
        return new int[]{u8(at), u8(at + 1), u8(at + 2), u8(at + 3)};
    }

    /**
     * Reads a text tag: {@code multiLocalizedUnicodeType}, {@code textDescriptionType} or
     * {@code textType}.
     *
     * @param tag the tag signature
     * @return the text, preferring English, or empty when the tag is absent or unreadable
     */
    Optional<String> text(int tag) {
        var entry = tags.get(tag);
        if (entry == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(readText(entry[0], entry[1]));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /**
     * Reads a lookup table tag: {@code lut8Type}, {@code lut16Type}, {@code lutAtoBType},
     * {@code lutBtoAType} or {@code multiProcessElementType}.
     *
     * @param tag the tag signature
     * @return the stages of the table in evaluation order, or {@code null} when the tag is absent
     * @throws MediaException.Malformed if the tag is not a valid lookup table
     */
    Pipeline lut(int tag) {
        var entry = tags.get(tag);
        if (entry == null) {
            return null;
        }
        var cached = (Pipeline) cache.computeIfAbsent(tag, _ -> readLut(tag, entry[0], entry[1]));
        return cached.copy(cached.inputs(), cached.outputs());
    }

    /**
     * Decodes a lookup table tag.
     *
     * @param tag the tag signature, for error messages
     * @param at the offset of the tag
     * @param length the length of the tag
     * @return the pipeline
     * @throws MediaException.Malformed if the tag is invalid
     */
    private Pipeline readLut(int tag, int at, int length) {
        var end = at + length;
        var type = u32(at);
        try {
            if (type == signature("mft1")) {
                return readLut8(at, end);
            }
            if (type == signature("mft2")) {
                return readLut16(at, end);
            }
            if (type == signature("mAB ")) {
                return readLutAtoB(at, end, true);
            }
            if (type == signature("mBA ")) {
                return readLutAtoB(at, end, false);
            }
            if (type == signature("mpet")) {
                return readMultiProcess(at, end);
            }
        } catch (IndexOutOfBoundsException | IllegalArgumentException | ArithmeticException exception) {
            throw new MediaException.Malformed("ICC tag " + name(tag) + " is truncated or invalid", exception);
        }
        throw new MediaException.Unsupported("icc", "ICC lookup table type " + name(type) + " in tag " + name(tag));
    }

    /**
     * Decodes a {@code multiProcessElementType} (ICC.1:2022 section 10.15): curve set, matrix and table elements in
     * file order, the stages working on natural XYZ and L*a*b* values.
     *
     * @param at the offset of the tag
     * @param end the end of the tag
     * @return the pipeline
     * @throws MediaException.Unsupported if an element type is unknown
     */
    private Pipeline readMultiProcess(int at, int end) {
        checkRange(at, 16, end);
        var inputs = u16(at + 8);
        var outputs = u16(at + 10);
        checkElementChannels(inputs, outputs);
        var count = u32(at + 12) & 0xFFFFFFFFL;
        if (count > (end - (at + 16)) / 8) {
            throw new IllegalArgumentException("element table does not fit");
        }
        var pipeline = new Pipeline(inputs, outputs);
        for (var i = 0; i < count; i++) {
            var element = position(at, at + 16 + i * 8, end);
            var type = u32(element);
            if (type == signature("cvst")) {
                pipeline.append(readCurveSetElement(element, end));
            } else if (type == signature("matf")) {
                pipeline.append(readMatrixElement(element, end));
            } else if (type == signature("clut")) {
                pipeline.append(readClutElement(element, end));
            } else if (type != signature("bACS") && type != signature("eACS")) {
                throw new MediaException.Unsupported("icc", "ICC multiProcessElement type " + name(type));
            }
        }
        var stages = pipeline.stages();
        if (!stages.isEmpty()) {
            var channels = stages.getFirst().inputs;
            for (var stage : stages) {
                if (stage.inputs != channels) {
                    throw new IllegalArgumentException("elements with inconsistent channels");
                }
                channels = stage.outputs;
            }
            if (stages.getFirst().inputs != inputs || channels != outputs) {
                throw new IllegalArgumentException("elements do not match the tag channels");
            }
        }
        return pipeline;
    }

    /**
     * Reads one entry of a position table and checks that the element it points to starts within the tag.
     *
     * @param base the offset the positions are relative to
     * @param entry the offset of the entry
     * @param end the end of the tag
     * @return the offset of the element
     */
    private int position(int base, int entry, int end) {
        checkRange(entry, 8, end);
        var offset = u32(entry) & 0xFFFFFFFFL;
        if (base + offset + 8 > end) {
            throw new IllegalArgumentException("element outside the tag");
        }
        return (int) (base + offset);
    }

    /**
     * Checks the channel counts of a processing element.
     *
     * @param inputs the number of inputs
     * @param outputs the number of outputs
     */
    private static void checkElementChannels(int inputs, int outputs) {
        if (inputs == 0 || inputs > MAX_CHANNELS || outputs == 0 || outputs > MAX_CHANNELS) {
            throw new IllegalArgumentException("invalid channel count");
        }
    }

    /**
     * Reads a {@code curveSetElement}: one {@code segmentedCurveType} per channel.
     *
     * @param at the offset of the element
     * @param end the end of the tag
     * @return the stage
     */
    private Stage readCurveSetElement(int at, int end) {
        checkRange(at, 12, end);
        var inputs = u16(at + 8);
        var outputs = u16(at + 10);
        checkElementChannels(inputs, outputs);
        if (inputs != outputs) {
            throw new IllegalArgumentException("curve set with different input and output channels");
        }
        var curves = new ToneCurve[inputs];
        for (var c = 0; c < inputs; c++) {
            curves[c] = readSegmentedCurve(position(at, at + 12 + c * 8, end), end);
        }
        return new Stage.Curves(curves);
    }

    /**
     * Reads a {@code segmentedCurveType}: breakpoints, then formula ({@code parf}) and sampled ({@code samf})
     * segments.
     *
     * @param at the offset of the curve
     * @param end the end of the tag
     * @return the curve
     */
    private ToneCurve readSegmentedCurve(int at, int end) {
        checkRange(at, 12, end);
        if (u32(at) != signature("curf")) {
            throw new IllegalArgumentException("curve set element is not a segmented curve");
        }
        var count = u16(at + 8);
        if (count == 0) {
            throw new IllegalArgumentException("segmented curve without segments");
        }
        checkRange(at + 12, (count - 1) * 4, end);
        var segments = new ToneCurve.Segment[count];
        var breaks = new float[count + 1];
        breaks[0] = ToneCurve.MINUS_INFINITY;
        breaks[count] = ToneCurve.PLUS_INFINITY;
        for (var i = 1; i < count; i++) {
            breaks[i] = float32(at + 12 + (i - 1) * 4);
        }
        var cursor = at + 12 + (count - 1) * 4;
        for (var i = 0; i < count; i++) {
            checkRange(cursor, 12, end);
            var type = u32(cursor);
            if (type == signature("parf")) {
                var function = u16(cursor + 8);
                if (function > 2) {
                    throw new IllegalArgumentException("unknown segment formula " + function);
                }
                var parameters = new double[ToneCurve.SEGMENT_PARAMETERS[function]];
                checkRange(cursor + 12, parameters.length * 4, end);
                for (var j = 0; j < parameters.length; j++) {
                    parameters[j] = float32(cursor + 12 + j * 4);
                }
                segments[i] = new ToneCurve.Segment(breaks[i], breaks[i + 1], function + 6, parameters, null);
                cursor += 12 + parameters.length * 4;
            } else if (type == signature("samf")) {
                var samples = u32(cursor + 8) & 0xFFFFFFFFL;
                if (samples > (end - (cursor + 12)) / 4) {
                    throw new IllegalArgumentException("sampled segment does not fit");
                }
                var points = new float[(int) samples + 1];
                for (var j = 1; j < points.length; j++) {
                    points[j] = float32(cursor + 12 + (j - 1) * 4);
                }
                segments[i] = new ToneCurve.Segment(breaks[i], breaks[i + 1], 0, null, points);
                cursor += 12 + (int) samples * 4;
            } else {
                throw new IllegalArgumentException("unknown curve segment " + name(type));
            }
        }
        return ToneCurve.segmented(segments);
    }

    /**
     * Reads a {@code matrixElement}: the coefficients row by row, one row per output, then one offset per output.
     *
     * @param at the offset of the element
     * @param end the end of the tag
     * @return the stage
     */
    private Stage readMatrixElement(int at, int end) {
        checkRange(at, 12, end);
        var inputs = u16(at + 8);
        var outputs = u16(at + 10);
        checkElementChannels(inputs, outputs);
        var size = inputs * outputs;
        checkRange(at + 12, (size + outputs) * 4, end);
        var coefficients = new double[size];
        for (var i = 0; i < size; i++) {
            coefficients[i] = float32(at + 12 + i * 4);
        }
        var offset = new double[outputs];
        for (var i = 0; i < outputs; i++) {
            offset[i] = float32(at + 12 + (size + i) * 4);
        }
        return new Stage.Matrix(inputs, outputs, coefficients, offset, Stage.Role.PLAIN);
    }

    /**
     * Reads a {@code CLUTElement}: the grid points per input, then {@code float32Number} samples.
     *
     * @param at the offset of the element
     * @param end the end of the tag
     * @return the stage
     */
    private Stage readClutElement(int at, int end) {
        checkRange(at, 28, end);
        var inputs = u16(at + 8);
        var outputs = u16(at + 10);
        checkElementChannels(inputs, outputs);
        var grid = new int[inputs];
        var samples = (long) outputs;
        for (var i = 0; i < inputs; i++) {
            var points = u8(at + 12 + i);
            if (points < 2) {
                throw new IllegalArgumentException("invalid grid points " + points);
            }
            grid[i] = points;
            samples *= points;
            if (samples > MAX_CLUT_SAMPLES) {
                throw new IllegalArgumentException("lookup table too large");
            }
        }
        checkRange(at + 28, (int) samples * 4, end);
        var table = new float[(int) samples];
        for (var i = 0; i < table.length; i++) {
            table[i] = float32(at + 28 + i * 4);
        }
        return new Stage.FloatClut(grid, outputs, table);
    }

    /**
     * Reads the {@code namedColor2Type} tag ({@code ncl2}).
     *
     * @return the colors, or {@code null} when the tag is absent
     * @throws MediaException.Malformed if the tag is not a valid {@code namedColor2Type}
     */
    NamedColors namedColors() {
        var tag = signature("ncl2");
        var entry = tags.get(tag);
        if (entry == null) {
            return null;
        }
        return (NamedColors) cache.computeIfAbsent(tag, _ -> {
            var at = entry[0];
            var end = at + entry[1];
            expectType(at, "ncl2", tag);
            try {
                checkRange(at, 84, end);
                var count = u32(at + 12) & 0xFFFFFFFFL;
                var devices = u32(at + 16) & 0xFFFFFFFFL;
                if (devices > MAX_CHANNELS) {
                    throw new MediaException.Malformed("ICC named colors with " + devices + " device coordinates");
                }
                var record = NAME_LENGTH + 6 + 2 * (int) devices;
                if (count > (end - (at + 84)) / record) {
                    throw new MediaException.Malformed("ICC tag ncl2 of " + count + " colors is truncated");
                }
                var prefix = string(at + 20, NAME_LENGTH - 1);
                var suffix = string(at + 52, NAME_LENGTH - 1);
                var roots = new String[(int) count];
                var pcs = new int[(int) count * 3];
                var device = new int[(int) (count * devices)];
                var cursor = at + 84;
                for (var i = 0; i < count; i++) {
                    roots[i] = string(cursor, NAME_LENGTH);
                    for (var c = 0; c < 3; c++) {
                        pcs[i * 3 + c] = u16(cursor + NAME_LENGTH + c * 2);
                    }
                    for (var c = 0; c < devices; c++) {
                        device[(int) (i * devices + c)] = u16(cursor + NAME_LENGTH + 6 + c * 2);
                    }
                    cursor += record;
                }
                return new NamedColors(prefix, suffix, roots, pcs, (int) devices, device);
            } catch (IndexOutOfBoundsException | IllegalArgumentException exception) {
                throw new MediaException.Malformed("ICC tag ncl2 is truncated or invalid", exception);
            }
        });
    }

    /**
     * Reads a NUL-terminated string of a fixed-length field.
     *
     * @param at the offset of the field
     * @param length the largest number of characters
     * @return the characters before the first NUL, one per byte
     */
    private String string(int at, int length) {
        var count = 0;
        while (count < length && data[at + count] != 0) {
            count++;
        }
        return new String(data, at, count, StandardCharsets.ISO_8859_1);
    }

    /**
     * Reads a big-endian {@code float32Number} that is zero or normal and within {@code -1e20..1e20}.
     *
     * @param at the offset
     * @return the value
     * @throws IllegalArgumentException if the value is out of range, subnormal, infinite or NaN
     */
    private float float32(int at) {
        var value = Float.intBitsToFloat(u32(at));
        if (value > 1e20 || value < -1e20 || !(value == 0 || Math.getExponent(value) >= Float.MIN_EXPONENT)
                || Float.isNaN(value)) {
            throw new IllegalArgumentException("invalid float32Number " + value);
        }
        return value;
    }

    /**
     * Decodes a {@code lut8Type}.
     *
     * @param at the offset of the tag
     * @param end the end of the tag
     * @return the pipeline
     */
    private Pipeline readLut8(int at, int end) {
        var inputs = u8(at + 8);
        var outputs = u8(at + 9);
        var points = u8(at + 10);
        checkLutShape(inputs, outputs, points);
        var pipeline = new Pipeline(inputs, outputs);
        var matrix = readMatrix(at + 12);
        if (inputs == 3 && !ColorMath.isIdentity(matrix)) {
            pipeline.append(new Stage.Matrix(matrix, null));
        }
        var cursor = at + 48;
        cursor = readTables8(pipeline, cursor, inputs, end);
        var samples = clutSamples(points, inputs, outputs);
        if (samples > 0) {
            checkRange(cursor, (int) samples, end);
            var table = new int[(int) samples];
            for (var i = 0; i < samples; i++) {
                var v = data[cursor + i] & 0xFF;
                table[i] = (v << 8) | v;
            }
            cursor += (int) samples;
            pipeline.append(new Stage.Clut(uniformGrid(inputs, points), outputs, table, false, Stage.Role.PLAIN));
        }
        readTables8(pipeline, cursor, outputs, end);
        return pipeline;
    }

    /**
     * Decodes a {@code lut16Type}.
     *
     * @param at the offset of the tag
     * @param end the end of the tag
     * @return the pipeline
     */
    private Pipeline readLut16(int at, int end) {
        var inputs = u8(at + 8);
        var outputs = u8(at + 9);
        var points = u8(at + 10);
        checkLutShape(inputs, outputs, points);
        var pipeline = new Pipeline(inputs, outputs);
        var matrix = readMatrix(at + 12);
        if (inputs == 3 && !ColorMath.isIdentity(matrix)) {
            pipeline.append(new Stage.Matrix(matrix, null));
        }
        var inputEntries = u16(at + 48);
        var outputEntries = u16(at + 50);
        if (inputEntries > 0x7FFF || outputEntries > 0x7FFF) {
            throw new IllegalArgumentException("table too large");
        }
        var cursor = at + 52;
        cursor = readTables16(pipeline, cursor, inputs, inputEntries, end);
        var samples = clutSamples(points, inputs, outputs);
        if (samples > 0) {
            checkRange(cursor, (int) samples * 2, end);
            var table = new int[(int) samples];
            for (var i = 0; i < samples; i++) {
                table[i] = u16(cursor + i * 2);
            }
            cursor += (int) samples * 2;
            pipeline.append(new Stage.Clut(uniformGrid(inputs, points), outputs, table, false, Stage.Role.PLAIN));
        }
        readTables16(pipeline, cursor, outputs, outputEntries, end);
        return pipeline;
    }

    /**
     * Decodes a {@code lutAtoBType} or {@code lutBtoAType}.
     *
     * @param at the offset of the tag
     * @param end the end of the tag
     * @param forward {@code true} for A to B (A curves, table, M curves, matrix, B curves), {@code false} for B to A
     * (the reverse order)
     * @return the pipeline
     */
    private Pipeline readLutAtoB(int at, int end, boolean forward) {
        var inputs = u8(at + 8);
        var outputs = u8(at + 9);
        if (inputs == 0 || inputs > MAX_CHANNELS || outputs == 0 || outputs > MAX_CHANNELS) {
            throw new IllegalArgumentException("invalid channel count");
        }
        var offsetB = offset(at, 12, end);
        var offsetMatrix = offset(at, 16, end);
        var offsetM = offset(at, 20, end);
        var offsetClut = offset(at, 24, end);
        var offsetA = offset(at, 28, end);
        var pipeline = new Pipeline(inputs, outputs);
        if (forward) {
            if (offsetA != 0) {
                pipeline.append(readCurveSet(at + offsetA, inputs, end));
            }
            if (offsetClut != 0) {
                pipeline.append(readClut(at + offsetClut, inputs, outputs, end));
            }
            if (offsetM != 0) {
                pipeline.append(readCurveSet(at + offsetM, outputs, end));
            }
            if (offsetMatrix != 0) {
                pipeline.append(readOffsetMatrix(at + offsetMatrix, end));
            }
            if (offsetB != 0) {
                pipeline.append(readCurveSet(at + offsetB, outputs, end));
            }
        } else {
            if (offsetB != 0) {
                pipeline.append(readCurveSet(at + offsetB, inputs, end));
            }
            if (offsetMatrix != 0) {
                pipeline.append(readOffsetMatrix(at + offsetMatrix, end));
            }
            if (offsetM != 0) {
                pipeline.append(readCurveSet(at + offsetM, inputs, end));
            }
            if (offsetClut != 0) {
                pipeline.append(readClut(at + offsetClut, inputs, outputs, end));
            }
            if (offsetA != 0) {
                pipeline.append(readCurveSet(at + offsetA, outputs, end));
            }
        }
        if (pipeline.stages().isEmpty()) {
            throw new IllegalArgumentException("lookup table without elements");
        }
        return pipeline;
    }

    /**
     * Reads an element offset of a {@code lutAtoBType} or {@code lutBtoAType} header.
     *
     * @param at the offset of the tag
     * @param field the offset of the field within the tag
     * @param end the end of the tag
     * @return the element offset relative to the tag, or {@code 0} when absent
     */
    private int offset(int at, int field, int end) {
        var value = u32(at + field) & 0xFFFFFFFFL;
        if (value != 0 && at + value >= end) {
            throw new IllegalArgumentException("element offset outside the tag");
        }
        return (int) value;
    }

    /**
     * Reads the matrix element of a {@code lutAtoBType} or {@code lutBtoAType}: nine coefficients
     * followed by three offsets.
     *
     * @param at the offset of the element
     * @param end the end of the tag
     * @return the stage
     */
    private Stage readOffsetMatrix(int at, int end) {
        checkRange(at, 48, end);
        var matrix = readMatrix(at);
        var offset = new double[]{s15Fixed16(at + 36), s15Fixed16(at + 40), s15Fixed16(at + 44)};
        return new Stage.Matrix(matrix, offset);
    }

    /**
     * Reads a 3x3 matrix of {@code s15Fixed16Number}s.
     *
     * @param at the offset of the first coefficient
     * @return the matrix in row-major order
     */
    private double[] readMatrix(int at) {
        var m = new double[9];
        for (var i = 0; i < 9; i++) {
            m[i] = s15Fixed16(at + i * 4);
        }
        return m;
    }

    /**
     * Reads the multidimensional table element of a {@code lutAtoBType} or {@code lutBtoAType}.
     *
     * @param at the offset of the element
     * @param inputs the number of inputs
     * @param outputs the number of outputs
     * @param end the end of the tag
     * @return the stage
     */
    private Stage readClut(int at, int inputs, int outputs, int end) {
        checkRange(at, 20, end);
        var grid = new int[inputs];
        var samples = (long) outputs;
        for (var i = 0; i < 16; i++) {
            var points = u8(at + i);
            if (points == 1) {
                throw new IllegalArgumentException("one grid point");
            }
            if (i < inputs) {
                if (points < 2) {
                    throw new IllegalArgumentException("missing grid points");
                }
                grid[i] = points;
                samples *= points;
                if (samples > MAX_CLUT_SAMPLES) {
                    throw new IllegalArgumentException("lookup table too large");
                }
            }
        }
        var precision = u8(at + 16);
        var cursor = at + 20;
        var table = new int[(int) samples];
        if (precision == 1) {
            checkRange(cursor, (int) samples, end);
            for (var i = 0; i < samples; i++) {
                var v = data[cursor + i] & 0xFF;
                table[i] = (v << 8) | v;
            }
        } else if (precision == 2) {
            checkRange(cursor, (int) samples * 2, end);
            for (var i = 0; i < samples; i++) {
                table[i] = u16(cursor + i * 2);
            }
        } else {
            throw new IllegalArgumentException("unknown table precision " + precision);
        }
        return new Stage.Clut(grid, outputs, table, false, Stage.Role.PLAIN);
    }

    /**
     * Reads a set of embedded curves, each padded to a multiple of four bytes.
     *
     * @param at the offset of the first curve
     * @param channels the number of curves
     * @param end the end of the tag
     * @return the stage
     */
    private Stage readCurveSet(int at, int channels, int end) {
        var curves = new ToneCurve[channels];
        var cursor = at;
        for (var i = 0; i < channels; i++) {
            var read = readCurve(cursor, end);
            if (read == null) {
                throw new IllegalArgumentException("embedded element is not a curve");
            }
            curves[i] = read.curve;
            cursor = (read.end + 3) & ~3;
        }
        return new Stage.Curves(curves);
    }

    /**
     * Reads the 8-bit tables of a {@code lut8Type}.
     *
     * @param pipeline the pipeline to append the curves to
     * @param at the offset of the first table
     * @param channels the number of tables
     * @param end the end of the tag
     * @return the offset after the tables
     */
    private int readTables8(Pipeline pipeline, int at, int channels, int end) {
        checkRange(at, channels * 256, end);
        var curves = new ToneCurve[channels];
        for (var c = 0; c < channels; c++) {
            var samples = new int[256];
            for (var i = 0; i < 256; i++) {
                var v = data[at + c * 256 + i] & 0xFF;
                samples[i] = (v << 8) | v;
            }
            curves[c] = ToneCurve.tabulated(samples);
        }
        pipeline.append(new Stage.Curves(curves));
        return at + channels * 256;
    }

    /**
     * Reads the 16-bit tables of a {@code lut16Type}; zero entries means no tables.
     *
     * @param pipeline the pipeline to append the curves to
     * @param at the offset of the first table
     * @param channels the number of tables
     * @param entries the number of entries per table
     * @param end the end of the tag
     * @return the offset after the tables
     */
    private int readTables16(Pipeline pipeline, int at, int channels, int entries, int end) {
        if (entries == 0) {
            return at;
        }
        if (entries < 2) {
            throw new IllegalArgumentException("table of one entry");
        }
        checkRange(at, channels * entries * 2, end);
        var curves = new ToneCurve[channels];
        for (var c = 0; c < channels; c++) {
            var samples = new int[entries];
            for (var i = 0; i < entries; i++) {
                samples[i] = u16(at + (c * entries + i) * 2);
            }
            curves[c] = ToneCurve.tabulated(samples);
        }
        pipeline.append(new Stage.Curves(curves));
        return at + channels * entries * 2;
    }

    /**
     * Checks the shape of a {@code lut8Type} or {@code lut16Type}.
     *
     * @param inputs the number of inputs
     * @param outputs the number of outputs
     * @param points the number of grid points
     */
    private static void checkLutShape(int inputs, int outputs, int points) {
        if (inputs == 0 || inputs > MAX_CHANNELS || outputs == 0 || outputs > MAX_CHANNELS || points == 1) {
            throw new IllegalArgumentException("invalid lookup table shape");
        }
    }

    /**
     * Returns the number of samples of a uniform grid.
     *
     * @param points the grid points per input, or {@code 0} for no table
     * @param inputs the number of inputs
     * @param outputs the number of outputs
     * @return the sample count, {@code 0} without a table
     */
    private static long clutSamples(int points, int inputs, int outputs) {
        if (points == 0) {
            return 0;
        }
        var samples = (long) outputs;
        for (var i = 0; i < inputs; i++) {
            samples *= points;
            if (samples > MAX_CLUT_SAMPLES) {
                throw new IllegalArgumentException("lookup table too large");
            }
        }
        return samples;
    }

    /**
     * Returns a grid with the same number of points on every input.
     *
     * @param inputs the number of inputs
     * @param points the grid points per input
     * @return the grid
     */
    private static int[] uniformGrid(int inputs, int points) {
        var grid = new int[inputs];
        Arrays.fill(grid, points);
        return grid;
    }

    /**
     * Holds a curve read from the profile and the offset just after it.
     *
     * @param curve the tone curve read
     * @param end the offset after the curve data
     */
    private record ReadCurve(ToneCurve curve, int end) {
    }

    /**
     * Reads a curve element at an offset.
     *
     * @param at the offset of the element
     * @param end the end of the enclosing tag
     * @return the curve, or {@code null} when the element is not a curve type
     */
    private ReadCurve readCurve(int at, int end) {
        try {
            checkRange(at, 12, end);
            var type = u32(at);
            if (type == signature("curv")) {
                var count = u32(at + 8) & 0xFFFFFFFFL;
                if (count == 0) {
                    return new ReadCurve(ToneCurve.gamma(1.0), at + 12);
                }
                if (count == 1) {
                    checkRange(at + 12, 2, end);
                    return new ReadCurve(ToneCurve.gamma(u16(at + 12) / 256.0), at + 14);
                }
                if (count > 0x7FFF) {
                    throw new MediaException.Malformed("ICC curve of " + count + " entries");
                }
                checkRange(at + 12, (int) count * 2, end);
                var samples = new int[(int) count];
                for (var i = 0; i < count; i++) {
                    samples[i] = u16(at + 12 + i * 2);
                }
                return new ReadCurve(ToneCurve.tabulated(samples), at + 12 + (int) count * 2);
            }
            if (type == signature("para")) {
                var function = u16(at + 8);
                if (function > 4) {
                    throw new MediaException.Malformed("ICC parametric curve of unknown function " + function);
                }
                var count = new int[]{1, 3, 4, 5, 7}[function];
                checkRange(at + 12, count * 4, end);
                var parameters = new double[count];
                for (var i = 0; i < count; i++) {
                    parameters[i] = s15Fixed16(at + 12 + i * 4);
                }
                return new ReadCurve(ToneCurve.parametric(function + 1, parameters), at + 12 + count * 4);
            }
            return null;
        } catch (IndexOutOfBoundsException | IllegalArgumentException exception) {
            throw new MediaException.Malformed("ICC curve is truncated or invalid", exception);
        }
    }

    /**
     * Decodes a text tag.
     *
     * @param at the offset of the tag
     * @param length the length of the tag
     * @return the text, or {@code null} when the type is not a text type
     */
    private String readText(int at, int length) {
        var end = at + length;
        var type = u32(at);
        if (type == signature("mluc")) {
            var records = u32(at + 8) & 0xFFFFFFFFL;
            var recordSize = u32(at + 12) & 0xFFFFFFFFL;
            if (records == 0 || recordSize < 12 || records > 4096) {
                return null;
            }
            var chosen = -1L;
            for (var i = 0; i < records; i++) {
                var record = at + 16 + i * recordSize;
                checkRange((int) record, 12, end);
                var language = u16((int) record);
                if (chosen < 0 || language == ('e' << 8 | 'n')) {
                    chosen = record;
                    if (language == ('e' << 8 | 'n')) {
                        break;
                    }
                }
            }
            var stringLength = u32((int) chosen + 4) & 0xFFFFFFFFL;
            var stringOffset = u32((int) chosen + 8) & 0xFFFFFFFFL;
            checkRange((int) (at + stringOffset), (int) stringLength, end);
            return trim(new String(data, (int) (at + stringOffset), (int) stringLength, StandardCharsets.UTF_16BE));
        }
        if (type == signature("desc")) {
            var count = u32(at + 8) & 0xFFFFFFFFL;
            if (count > length) {
                return null;
            }
            checkRange(at + 12, (int) count, end);
            return trim(new String(data, at + 12, (int) count, StandardCharsets.ISO_8859_1));
        }
        if (type == signature("text")) {
            return trim(new String(data, at + 8, length - 8, StandardCharsets.ISO_8859_1));
        }
        return null;
    }

    /**
     * Removes trailing NUL characters and surrounding blanks.
     *
     * @param text the raw tag text, possibly padded with NUL characters
     * @return the trimmed text
     */
    private static String trim(String text) {
        var end = text.indexOf('\0');
        return (end >= 0 ? text.substring(0, end) : text).strip();
    }

    /**
     * Checks that a range lies within a tag.
     *
     * @param at the start of the range
     * @param length the length of the range
     * @param end the end of the tag
     * @throws IllegalArgumentException if the range leaves the tag
     */
    private void checkRange(int at, int length, int end) {
        if (at < 0 || length < 0 || (long) at + length > end || end > size) {
            throw new IllegalArgumentException("range outside the tag");
        }
    }

    /**
     * Checks a tag's type signature.
     *
     * @param at the offset of the tag
     * @param expected the expected type as a four-character string
     * @param tag the tag signature, for the message
     * @throws MediaException.Malformed if the type differs
     */
    private void expectType(int at, String expected, int tag) {
        if (u32(at) != signature(expected)) {
            throw new MediaException.Malformed("ICC tag " + name(tag) + " has type " + name(u32(at))
                    + ", expected " + expected);
        }
    }

    /**
     * Checks a tag invariant.
     *
     * @param condition the invariant
     * @param tag the tag signature, for the message
     * @throws MediaException.Malformed if the invariant does not hold
     */
    private static void require(boolean condition, int tag) {
        if (!condition) {
            throw new MediaException.Malformed("ICC tag " + name(tag) + " is truncated");
        }
    }

    /**
     * Reads an unsigned byte.
     *
     * @param at the offset
     * @return the value
     */
    private int u8(int at) {
        return data[at] & 0xFF;
    }

    /**
     * Reads a big-endian unsigned 16-bit value.
     *
     * @param at the offset
     * @return the value
     */
    private int u16(int at) {
        return ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
    }

    /**
     * Reads a big-endian 32-bit value.
     *
     * @param at the offset
     * @return the value
     */
    private int u32(int at) {
        return ((data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16) | ((data[at + 2] & 0xFF) << 8)
                | (data[at + 3] & 0xFF);
    }

    /**
     * Reads a big-endian {@code s15Fixed16Number}.
     *
     * @param at the offset
     * @return the value
     */
    private double s15Fixed16(int at) {
        return u32(at) / 65536.0;
    }

    /**
     * Returns the number of channels of a color space signature.
     *
     * @param space the signature
     * @return the channel count, or {@code 0} for an unknown space
     */
    static int channels(int space) {
        var name = name(space);
        return switch (name) {
            case "XYZ ", "Lab ", "Luv ", "YCbr", "Yxy ", "RGB ", "HSV ", "HLS ", "CMY " -> 3;
            case "GRAY" -> 1;
            case "CMYK" -> 4;
            default -> {
                if (name.length() == 4 && name.endsWith("CLR")) {
                    var count = Character.digit(name.charAt(0), 16);
                    yield count >= 2 ? count : 0;
                }
                yield 0;
            }
        };
    }

    /**
     * Encodes a four-character signature.
     *
     * @param text the four characters
     * @return the signature
     */
    static int signature(String text) {
        return (text.charAt(0) << 24) | (text.charAt(1) << 16) | (text.charAt(2) << 8) | text.charAt(3);
    }

    /**
     * Decodes a four-character signature.
     *
     * @param signature the signature as a big-endian 32-bit integer
     * @return the four characters
     */
    static String name(int signature) {
        return new String(new char[]{
                (char) ((signature >>> 24) & 0xFF), (char) ((signature >>> 16) & 0xFF),
                (char) ((signature >>> 8) & 0xFF), (char) (signature & 0xFF)
        });
    }
}
