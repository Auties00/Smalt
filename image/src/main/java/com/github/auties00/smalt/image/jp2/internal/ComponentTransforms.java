package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Parses and applies the multiple component transformations of ISO/IEC 15444-2 annex J: the transformation arrays of
 * {@code MCT} segments, the component collections of {@code MCC} segments and the stage order of {@code MCO} segments.
 *
 * <p>Irreversible array-based transformations are computed in single precision, each output accumulating its terms
 * in input order; reversible ones, and the reversible wavelet-based transformation, in exact integer arithmetic.
 */
final class ComponentTransforms {
    /**
     * The array type of a dependency transformation array.
     */
    static final int DEPENDENCY = 0;

    /**
     * The array type of a decorrelation transformation array.
     */
    static final int DECORRELATION = 1;

    /**
     * The array type of an offset array.
     */
    static final int OFFSET = 2;

    /**
     * The collection type of a wavelet-based transformation.
     */
    static final int WAVELET = 3;

    /**
     * Prevents instantiation.
     */
    private ComponentTransforms() {
        throw new AssertionError();
    }

    /**
     * Holds one collection of an {@code MCC} segment.
     *
     * @param type the transformation: {@link #DEPENDENCY}, {@link #DECORRELATION} or {@link #WAVELET}
     * @param inputs the input intermediate component of each input position
     * @param outputs the output intermediate component of each output position
     * @param reversible whether an array-based transformation is reversible
     * @param transform the index of the transformation array, 0 for the null array; for a wavelet-based
     * transformation, the kernel: 0 for the 9-7, 1 for the 5-3, or an {@code ATK} index
     * @param offsets the index of the offset array, 0 for none
     * @param levels the number of wavelet decomposition levels
     * @param origin the reference grid offset of a wavelet-based transformation in the component direction
     */
    record Collection(int type, int[] inputs, int[] outputs, boolean reversible, int transform, int offsets, int levels,
                      long origin) {
    }

    /**
     * Collects the segments of the {@code MCT} and {@code MCC} series of one header, concatenating each series in
     * {@code Zmct} or {@code Zmcc} order (J.2).
     */
    static final class Series {
        /**
         * The array data of each {@code Imct} value, by segment index.
         */
        private final Map<Integer, TreeMap<Integer, byte[]>> arrays = new TreeMap<>();

        /**
         * The collection data of each {@code Imcc} value, by segment index.
         */
        private final Map<Integer, TreeMap<Integer, byte[]>> collections = new TreeMap<>();

        /**
         * The parsed arrays, once {@link #arrays()} has run.
         */
        private Map<Integer, double[]> parsedArrays;

        /**
         * The parsed collections, once {@link #collections()} has run.
         */
        private Map<Integer, Collection[]> parsedCollections;

        /**
         * Records an {@code MCT} segment.
         *
         * @param data the codestream
         * @param position the index of its {@code Zmct} field
         * @param end the end of the segment
         * @throws MediaException.Malformed if the segment is truncated or repeats a segment index
         */
        void addArray(byte[] data, int position, int end) {
            if (end - position < 4) {
                throw new MediaException.Malformed("truncated JPEG 2000 MCT segment");
            }
            var z = ByteArrays.u16be(data, position);
            var imct = ByteArrays.u16be(data, position + 2);
            var start = position + 4;
            if (z == 0) {
                if (end - start < 2) {
                    throw new MediaException.Malformed("truncated JPEG 2000 MCT segment");
                }
                start += 2;
            }
            add(arrays, imct, z, data, start, end, "MCT");
        }

        /**
         * Records an {@code MCC} segment.
         *
         * @param data the codestream
         * @param position the index of its {@code Zmcc} field
         * @param end the end of the segment
         * @throws MediaException.Malformed if the segment is truncated or repeats a segment index
         */
        void addCollections(byte[] data, int position, int end) {
            if (end - position < 3) {
                throw new MediaException.Malformed("truncated JPEG 2000 MCC segment");
            }
            var z = ByteArrays.u16be(data, position);
            var imcc = data[position + 2] & 0xFF;
            var start = position + 3;
            if (z == 0) {
                if (end - start < 4) {
                    throw new MediaException.Malformed("truncated JPEG 2000 MCC segment");
                }
                start += 2;
            }
            add(collections, imcc, z, data, start, end, "MCC");
        }

        /**
         * Records one segment of a series.
         *
         * @param series the series of the segment type
         * @param key the series index
         * @param z the segment index within the series
         * @param data the codestream
         * @param start the first byte of the series data in this segment
         * @param end the end of the segment
         * @param name the segment name, for failure messages
         */
        private static void add(Map<Integer, TreeMap<Integer, byte[]>> series, int key, int z, byte[] data, int start,
                                int end, String name) {
            var parts = series.computeIfAbsent(key, _ -> new TreeMap<>());
            if (parts.putIfAbsent(z, java.util.Arrays.copyOfRange(data, start, end)) != null) {
                throw new MediaException.Malformed("duplicate JPEG 2000 " + name + " segment " + key + "/" + z);
            }
        }

        /**
         * Returns the arrays of the series, keyed by the ten low bits of {@code Imct}: the array index and type.
         *
         * @return the arrays
         * @throws MediaException.Malformed if an array's data is not a whole number of elements
         */
        Map<Integer, double[]> arrays() {
            if (parsedArrays != null) {
                return parsedArrays;
            }
            var result = new TreeMap<Integer, double[]>();
            for (var entry : arrays.entrySet()) {
                var imct = entry.getKey();
                var bytes = join(entry.getValue());
                var elementType = (imct >> 10) & 3;
                var size = switch (elementType) {
                    case 0 -> 2;
                    case 1, 2 -> 4;
                    default -> 8;
                };
                if (bytes.length % size != 0 || ((imct >> 8) & 3) == 3 || (imct & 0xFF) == 0) {
                    throw new MediaException.Malformed("invalid JPEG 2000 MCT array " + Integer.toHexString(imct));
                }
                var values = new double[bytes.length / size];
                for (var i = 0; i < values.length; i++) {
                    var at = i * size;
                    values[i] = switch (elementType) {
                        case 0 -> (short) ByteArrays.u16be(bytes, at);
                        case 1 -> (int) ByteArrays.u32be(bytes, at);
                        case 2 -> Float.intBitsToFloat((int) ByteArrays.u32be(bytes, at));
                        default -> Double.longBitsToDouble(Part2Values.u64(bytes, at));
                    };
                }
                result.put(imct & 0x3FF, values);
            }
            parsedArrays = result;
            return result;
        }

        /**
         * Returns the collections of each {@code MCC} series.
         *
         * @return the collections, by {@code Imcc}
         * @throws MediaException.Malformed if a series is truncated or invalid
         */
        Map<Integer, Collection[]> collections() {
            if (parsedCollections != null) {
                return parsedCollections;
            }
            var result = new TreeMap<Integer, Collection[]>();
            for (var entry : collections.entrySet()) {
                result.put(entry.getKey(), parseCollections(join(entry.getValue())));
            }
            parsedCollections = result;
            return result;
        }

        /**
         * Concatenates the segments of a series.
         *
         * @param parts the segments by index
         * @return the series data
         * @throws MediaException.Malformed if a segment is missing
         */
        private static byte[] join(TreeMap<Integer, byte[]> parts) {
            if (parts.lastKey() != parts.size() - 1) {
                throw new MediaException.Malformed("incomplete JPEG 2000 MCT or MCC segment series");
            }
            var joined = new ByteArrayOutputStream();
            for (var part : parts.values()) {
                joined.writeBytes(part);
            }
            return joined.toByteArray();
        }
    }

    /**
     * Parses the collections of an {@code MCC} series ({@code Qmcc} followed by the collections, A.3.8).
     *
     * @param data the series data, from {@code Qmcc}
     * @return the collections
     * @throws MediaException.Malformed if the data is truncated or a collection is invalid
     */
    static Collection[] parseCollections(byte[] data) {
        if (data.length < 2) {
            throw new MediaException.Malformed("truncated JPEG 2000 MCC segment");
        }
        var count = ByteArrays.u16be(data, 0);
        var position = 2;
        var result = new ArrayList<Collection>();
        for (var i = 0; i < count; i++) {
            require(data, position, 3);
            var type = data[position] & 3;
            if (type == 2) {
                throw new MediaException.Malformed("invalid JPEG 2000 MCC collection type 2");
            }
            var inputCount = ByteArrays.u16be(data, position + 1);
            position += 3;
            var inputs = indices(data, position, inputCount);
            position += (inputCount & 0x7FFF) * ((inputCount >> 15) + 1);
            require(data, position, 2);
            var outputCount = ByteArrays.u16be(data, position);
            position += 2;
            var outputs = indices(data, position, outputCount);
            position += (outputCount & 0x7FFF) * ((outputCount >> 15) + 1);
            require(data, position, 3);
            var tmcc = (int) ((ByteArrays.u16be(data, position) << 8) | (data[position + 2] & 0xFF));
            position += 3;
            long origin = 0;
            if (type == WAVELET) {
                require(data, position, 4);
                origin = ByteArrays.u32be(data, position);
                position += 4;
            }
            if (type != DECORRELATION || (tmcc & 0x10000) != 0) {
                if (inputs.length != outputs.length) {
                    throw new MediaException.Malformed("JPEG 2000 MCC collection with " + inputs.length
                            + " inputs and " + outputs.length + " outputs");
                }
            }
            var levels = (tmcc >> 16) & 0x3F;
            if (type == WAVELET && levels > 32) {
                throw new MediaException.Malformed("invalid JPEG 2000 MCC wavelet level count " + levels);
            }
            result.add(new Collection(type, inputs, outputs, type != WAVELET && (tmcc & 0x10000) != 0, tmcc & 0xFF,
                    (tmcc >> 8) & 0xFF, type == WAVELET ? levels : 0, origin));
        }
        return result.toArray(Collection[]::new);
    }

    /**
     * Reads the component indices of a collection.
     *
     * @param data the series data
     * @param position the index of the first index
     * @param field the {@code Nmcc} or {@code Mmcc} field: the count, and in bit 15 whether indices are 16 bits wide
     * @return the indices
     * @throws MediaException.Malformed if the data is truncated or the count is 0
     */
    private static int[] indices(byte[] data, int position, int field) {
        var count = field & 0x7FFF;
        var wide = (field & 0x8000) != 0;
        if (count == 0 || count > 16384) {
            throw new MediaException.Malformed("invalid JPEG 2000 MCC component count " + count);
        }
        require(data, position, count * (wide ? 2 : 1));
        var result = new int[count];
        for (var j = 0; j < count; j++) {
            result[j] = wide ? ByteArrays.u16be(data, position + 2 * j) : data[position + j] & 0xFF;
            if (result[j] >= 16384) {
                throw new MediaException.Malformed("invalid JPEG 2000 MCC component index " + result[j]);
            }
        }
        return result;
    }

    /**
     * Checks that a series holds a number of bytes from a position.
     *
     * @param data the series data
     * @param position the index of the first byte needed
     * @param bytes the number of bytes needed
     * @throws MediaException.Malformed if it does not
     */
    private static void require(byte[] data, int position, int bytes) {
        if (data.length - position < bytes) {
            throw new MediaException.Malformed("truncated JPEG 2000 MCC segment");
        }
    }

    /**
     * Holds the samples of one intermediate component of a tile: integers after reversible processing, floats after
     * irreversible processing.
     *
     * @param x0 the left edge on the component grid
     * @param y0 the top edge on the component grid
     * @param width the width in samples
     * @param height the height in samples
     * @param subsamplingX the horizontal subsampling of the component
     * @param subsamplingY the vertical subsampling of the component
     * @param integers the integer samples, or {@code null}
     * @param floats the floating-point samples, or {@code null}
     * @param addend an integer offset still to add to the floating-point samples once they are rounded, 0 for
     * integer samples
     */
    record Plane(long x0, long y0, int width, int height, int subsamplingX, int subsamplingY, long[] integers,
                 float[] floats, long addend) {
        /**
         * Constructs a plane without a pending offset.
         *
         * @param x0 the left edge on the component grid
         * @param y0 the top edge on the component grid
         * @param width the width in samples
         * @param height the height in samples
         * @param subsamplingX the horizontal subsampling of the component
         * @param subsamplingY the vertical subsampling of the component
         * @param integers the integer samples, or {@code null}
         * @param floats the floating-point samples, or {@code null}
         */
        Plane(long x0, long y0, int width, int height, int subsamplingX, int subsamplingY, long[] integers,
              float[] floats) {
            this(x0, y0, width, height, subsamplingX, subsamplingY, integers, floats, 0);
        }

        /**
         * Returns the plane as integers, rounding floating-point samples to the nearest integer, ties to even, before
         * adding the pending offset.
         *
         * @return the integer samples, the plane's own array when it holds integers
         */
        long[] asIntegers() {
            if (integers != null) {
                return integers;
            }
            var result = new long[floats.length];
            for (var i = 0; i < result.length; i++) {
                result[i] = (long) Math.rint(floats[i]) + addend;
            }
            return result;
        }

        /**
         * Returns the plane as single-precision values, the pending offset included.
         *
         * @return the floating-point samples, the plane's own array when it holds floats without a pending offset
         */
        float[] asFloats() {
            if (floats != null && addend == 0) {
                return floats;
            }
            var result = new float[floats != null ? floats.length : integers.length];
            for (var i = 0; i < result.length; i++) {
                result[i] = floats != null ? floats[i] + addend : integers[i];
            }
            return result;
        }

        /**
         * Returns whether another plane covers the same samples.
         *
         * @param other the other plane
         * @return whether the planes have the same area and subsampling
         */
        boolean sameGrid(Plane other) {
            return x0 == other.x0 && y0 == other.y0 && width == other.width && height == other.height
                    && subsamplingX == other.subsamplingX && subsamplingY == other.subsamplingY;
        }

        /**
         * Returns a plane of the same area with new samples.
         *
         * @param newIntegers the integer samples, or {@code null}
         * @param newFloats the floating-point samples, or {@code null}
         * @return the plane
         */
        Plane with(long[] newIntegers, float[] newFloats) {
            return new Plane(x0, y0, width, height, subsamplingX, subsamplingY, newIntegers, newFloats);
        }
    }

    /**
     * Applies the inverse multiple component transformation of a tile (MCO_TRANSFORM, J.2.1).
     *
     * @param planes the spatially reconstructed components, {@code I_0}
     * @param stages the collections of each stage, in inverse processing order
     * @param arrays the transformation and offset arrays in force, by the ten low bits of {@code Imct}
     * @param kernels the {@code ATK} kernels in force, by index
     * @param outputs the number of reconstructed image components, {@code Ncbd}
     * @return the reconstructed image components; an entry is {@code null} for a component no stage produced
     * @throws MediaException.Malformed if a collection refers to a missing component, array or kernel, or its inputs
     * cover different areas
     */
    static Plane[] apply(Plane[] planes, List<Collection[]> stages, Map<Integer, double[]> arrays,
                         Map<Integer, LiftingKernel> kernels, int outputs) {
        var current = planes;
        for (var k = 0; k < stages.size(); k++) {
            var collections = stages.get(k);
            var size = k + 1 < stages.size() ? inputCount(stages.get(k + 1)) : outputs;
            var next = new Plane[size];
            for (var collection : collections) {
                var inputs = new Plane[collection.inputs().length];
                Plane grid = null;
                for (var j = 0; j < inputs.length; j++) {
                    var index = collection.inputs()[j];
                    inputs[j] = index < current.length ? current[index] : null;
                    if (inputs[j] != null) {
                        if (grid == null) {
                            grid = inputs[j];
                        } else if (!grid.sameGrid(inputs[j])) {
                            throw new MediaException.Malformed("JPEG 2000 component collection over components of "
                                    + "different sizes");
                        }
                    }
                }
                if (grid == null) {
                    grid = firstPlane(current);
                }
                for (var j = 0; j < inputs.length; j++) {
                    if (inputs[j] == null) {
                        inputs[j] = grid.with(new long[grid.width() * grid.height()], null);
                    }
                }
                var results = switch (collection.type()) {
                    case DECORRELATION -> collection.reversible() ? reversibleDecorrelation(inputs, collection, arrays)
                            : irreversibleDecorrelation(inputs, collection, arrays);
                    case DEPENDENCY -> collection.reversible() ? reversibleDependency(inputs, collection, arrays)
                            : irreversibleDependency(inputs, collection, arrays);
                    default -> wavelet(inputs, collection, arrays, kernels);
                };
                for (var j = 0; j < results.length; j++) {
                    var index = collection.outputs()[j];
                    if (index >= next.length) {
                        throw new MediaException.Malformed("JPEG 2000 component collection output " + index
                                + " beyond the " + next.length + " components of its stage");
                    }
                    if (next[index] != null) {
                        throw new MediaException.Malformed("JPEG 2000 component " + index + " produced twice");
                    }
                    next[index] = results[j];
                }
            }
            current = next;
        }
        return current;
    }

    /**
     * Returns the number of input intermediate components of a stage ({@code NI_k} of equation J-1).
     *
     * @param collections the stage's collections
     * @return one more than the largest input index
     */
    static int inputCount(Collection[] collections) {
        var max = -1;
        for (var collection : collections) {
            for (var index : collection.inputs()) {
                max = Math.max(max, index);
            }
        }
        return max + 1;
    }

    /**
     * Returns the first plane of a set.
     *
     * @param planes the planes, some {@code null}
     * @return the first non-null plane
     * @throws MediaException.Malformed if every plane is {@code null}
     */
    private static Plane firstPlane(Plane[] planes) {
        for (var plane : planes) {
            if (plane != null) {
                return plane;
            }
        }
        throw new MediaException.Malformed("JPEG 2000 component transformation stage without input components");
    }

    /**
     * Returns an array a collection refers to.
     *
     * @param arrays the arrays in force
     * @param index the array index, 0 for the null array
     * @param type the array type
     * @param length the number of elements the transformation needs
     * @return the elements, or {@code null} for the null array
     * @throws MediaException.Malformed if the array is missing or too short
     */
    private static double[] array(Map<Integer, double[]> arrays, int index, int type, int length) {
        if (index == 0) {
            return null;
        }
        var values = arrays.get(index | (type << 8));
        if (values == null) {
            throw new MediaException.Malformed("missing JPEG 2000 MCT array " + index + " of type " + type);
        }
        if (values.length < length) {
            throw new MediaException.Malformed("JPEG 2000 MCT array " + index + " has " + values.length
                    + " elements, not " + length);
        }
        return values;
    }

    /**
     * Applies an irreversible decorrelation transformation (equation J-2).
     *
     * @param inputs the input components
     * @param collection the {@code MCC} collection, for its arrays and outputs
     * @param arrays the arrays in force
     * @return the output components
     */
    private static Plane[] irreversibleDecorrelation(Plane[] inputs, Collection collection,
                                                     Map<Integer, double[]> arrays) {
        var n = inputs.length;
        var m = collection.outputs().length;
        var matrix = array(arrays, collection.transform(), DECORRELATION, m * n);
        var offsets = array(arrays, collection.offsets(), OFFSET, m);
        var coefficients = new float[m * n];
        for (var i = 0; i < m; i++) {
            for (var j = 0; j < n; j++) {
                coefficients[i * n + j] = matrix == null ? (i == j ? 1 : 0) : (float) matrix[i * n + j];
            }
        }
        var sources = new float[n][];
        for (var j = 0; j < n; j++) {
            sources[j] = inputs[j].asFloats();
        }
        var length = sources[0].length;
        var results = new Plane[m];
        for (var i = 0; i < m; i++) {
            var values = new float[length];
            var offset = offsets == null ? 0 : offsets[i];
            // an integer offset joins the rounded result, a fractional one the sum
            var integral = offset == Math.rint(offset) && Math.abs(offset) < 1L << 52;
            var added = integral ? 0f : (float) offset;
            for (var x = 0; x < length; x++) {
                var sum = 0f;
                for (var j = 0; j < n; j++) {
                    sum += coefficients[i * n + j] * sources[j][x];
                }
                values[x] = sum + added;
            }
            var plane = inputs[0];
            results[i] = new Plane(plane.x0(), plane.y0(), plane.width(), plane.height(), plane.subsamplingX(),
                    plane.subsamplingY(), null, values, integral ? (long) offset : 0);
        }
        return results;
    }

    /**
     * Applies a reversible decorrelation transformation (equations J-4 to J-6).
     *
     * @param inputs the input components
     * @param collection the {@code MCC} collection, for its arrays
     * @param arrays the arrays in force
     * @return the output components
     * @throws MediaException.Malformed if a scaling coefficient is not a power of two
     */
    private static Plane[] reversibleDecorrelation(Plane[] inputs, Collection collection,
                                                   Map<Integer, double[]> arrays) {
        var n = inputs.length;
        var matrix = array(arrays, collection.transform(), DECORRELATION, (n + 1) * n);
        var offsets = array(arrays, collection.offsets(), OFFSET, n);
        var t = new long[(n + 1) * n];
        for (var i = 0; i <= n; i++) {
            for (var j = 0; j < n; j++) {
                if (matrix == null) {
                    t[i * n + j] = (i == n && j == n - 1) || (i < n && j == n - 1 - i) ? 1 : 0;
                } else {
                    t[i * n + j] = integral(matrix[i * n + j]);
                }
            }
        }
        for (var l = 0; l < n; l++) {
            checkScale(t[l * n + n - 1 - l], false);
        }
        var last = t[n * n + n - 1];
        checkScale(last, true);
        var values = new long[n][];
        for (var j = 0; j < n; j++) {
            values[j] = inputs[j].asIntegers().clone();
        }
        var length = values[0].length;
        for (var x = 0; x < length; x++) {
            for (var l = 0; l < n; l++) {
                var target = n - 1 - l;
                var scale = t[l * n + target];
                var sum = 0L;
                for (var i = 0; i < n; i++) {
                    if (i != target) {
                        sum += t[l * n + i] * values[i][x];
                    }
                }
                values[target][x] -= Math.floorDiv(2 * sum + scale, 2 * scale);
            }
            var magnitude = Math.abs(last);
            var sum = 0L;
            for (var i = 0; i < n - 1; i++) {
                sum += t[n * n + i] * values[i][x];
            }
            var adjusted = values[n - 1][x] - Math.floorDiv(2 * sum + magnitude, 2 * magnitude);
            values[n - 1][x] = last < 0 ? -adjusted : adjusted;
        }
        return withOffsets(inputs, values, offsets);
    }

    /**
     * Applies an irreversible dependency transformation (equations J-11 and J-12).
     *
     * @param inputs the input components
     * @param collection the {@code MCC} collection, for its arrays
     * @param arrays the arrays in force
     * @return the output components
     */
    private static Plane[] irreversibleDependency(Plane[] inputs, Collection collection,
                                                  Map<Integer, double[]> arrays) {
        var m = inputs.length;
        var matrix = array(arrays, collection.transform(), DEPENDENCY, m * (m - 1) / 2);
        var offsets = array(arrays, collection.offsets(), OFFSET, m);
        var results = new float[m][];
        for (var i = 0; i < m; i++) {
            var source = inputs[i].asFloats();
            var values = new float[source.length];
            var offset = offsets == null ? 0f : (float) offsets[i];
            var row = i * (i - 1) / 2;
            var weights = new float[i];
            for (var j = 0; j < i && matrix != null; j++) {
                weights[j] = (float) matrix[row + j];
            }
            for (var x = 0; x < values.length; x++) {
                var sum = 0f;
                for (var j = 0; j < i; j++) {
                    sum += weights[j] * results[j][x];
                }
                values[x] = sum + (source[x] + offset);
            }
            results[i] = values;
        }
        var planes = new Plane[m];
        for (var i = 0; i < m; i++) {
            planes[i] = inputs[0].with(null, results[i]);
        }
        return planes;
    }

    /**
     * Applies a reversible dependency transformation (equations J-14 and J-15).
     *
     * @param inputs the input components
     * @param collection the {@code MCC} collection, for its arrays
     * @param arrays the arrays in force
     * @return the output components
     * @throws MediaException.Malformed if a scaling coefficient is not a power of two
     */
    private static Plane[] reversibleDependency(Plane[] inputs, Collection collection,
                                                Map<Integer, double[]> arrays) {
        var m = inputs.length;
        var matrix = array(arrays, collection.transform(), DEPENDENCY, m * (m + 1) / 2 - 1);
        var offsets = array(arrays, collection.offsets(), OFFSET, m);
        var values = new long[m][];
        for (var i = 0; i < m; i++) {
            var source = inputs[i].asIntegers();
            var offset = offsets == null ? 0 : integral(offsets[i]);
            var target = new long[source.length];
            // row i holds t_i0 .. t_ii, after the m(m+1)/2 - 1 elements of the rows above minus the absent t_00
            var row = i * (i + 1) / 2 - 1;
            var scale = i == 0 ? 1 : matrix == null ? 1 : integral(matrix[row + i]);
            checkScale(scale, false);
            var weights = new long[i];
            for (var j = 0; j < i && matrix != null; j++) {
                weights[j] = integral(matrix[row + j]);
            }
            for (var x = 0; x < target.length; x++) {
                var y = source[x] + offset;
                if (i == 0) {
                    target[x] = y;
                    continue;
                }
                var sum = scale >> 1;
                for (var j = 0; j < i; j++) {
                    sum += weights[j] * values[j][x];
                }
                target[x] = Math.floorDiv(sum, scale) + y;
            }
            values[i] = target;
        }
        var planes = new Plane[m];
        for (var i = 0; i < m; i++) {
            planes[i] = inputs[0].with(values[i], null);
        }
        return planes;
    }

    /**
     * Applies a wavelet-based transformation (J.3.2): for each sample position, the inverse one-dimensional wavelet
     * transformation of the collection's components, followed by the additive offsets.
     *
     * @param inputs the input components, the sub-band coefficients in sub-band order
     * @param collection the {@code MCC} collection, for its kernel, levels, offsets and origin
     * @param arrays the arrays in force
     * @param kernels the {@code ATK} kernels in force
     * @return the output components
     * @throws MediaException.Malformed if the kernel is missing
     */
    private static Plane[] wavelet(Plane[] inputs, Collection collection, Map<Integer, double[]> arrays,
                                   Map<Integer, LiftingKernel> kernels) {
        var m = inputs.length;
        var kernel = switch (collection.transform()) {
            case 0 -> LiftingKernel.IRREVERSIBLE_97;
            case 1 -> LiftingKernel.REVERSIBLE_53;
            default -> kernels.get(collection.transform());
        };
        if (kernel == null) {
            throw new MediaException.Malformed("missing JPEG 2000 ATK kernel " + collection.transform());
        }
        var offsets = array(arrays, collection.offsets(), OFFSET, m);
        var origin = collection.origin();
        var levels = collection.levels();
        var length = inputs[0].width() * inputs[0].height();
        var lanes = WaveletKernel.LANES;
        var planes = new Plane[m];
        if (kernel.reversible) {
            var sources = new long[m][];
            var outputs = new long[m][];
            for (var j = 0; j < m; j++) {
                sources[j] = inputs[j].asIntegers();
                outputs[j] = new long[length];
            }
            var buffer = new long[m * lanes];
            var scratch = new long[m * lanes];
            for (var x = 0; x < length; x += lanes) {
                var count = Math.min(lanes, length - x);
                for (var j = 0; j < m; j++) {
                    for (var lane = 0; lane < lanes; lane++) {
                        buffer[j * lanes + lane] = lane < count ? sources[j][x + lane] : 0;
                    }
                }
                inverseLevels(buffer, scratch, null, null, m, origin, levels, kernel, lanes);
                for (var j = 0; j < m; j++) {
                    var offset = offsets == null ? 0 : integral(offsets[j]);
                    for (var lane = 0; lane < count; lane++) {
                        outputs[j][x + lane] = buffer[j * lanes + lane] + offset;
                    }
                }
            }
            for (var j = 0; j < m; j++) {
                planes[j] = inputs[0].with(outputs[j], null);
            }
        } else {
            var sources = new float[m][];
            var outputs = new float[m][];
            for (var j = 0; j < m; j++) {
                sources[j] = inputs[j].asFloats();
                outputs[j] = new float[length];
            }
            var buffer = new float[m * lanes];
            var scratch = new float[m * lanes];
            for (var x = 0; x < length; x += lanes) {
                var count = Math.min(lanes, length - x);
                for (var j = 0; j < m; j++) {
                    for (var lane = 0; lane < lanes; lane++) {
                        buffer[j * lanes + lane] = lane < count ? sources[j][x + lane] : 0;
                    }
                }
                inverseLevels(null, null, buffer, scratch, m, origin, levels, kernel, lanes);
                for (var j = 0; j < m; j++) {
                    var offset = offsets == null ? 0f : (float) offsets[j];
                    for (var lane = 0; lane < count; lane++) {
                        outputs[j][x + lane] = buffer[j * lanes + lane] + offset;
                    }
                }
            }
            for (var j = 0; j < m; j++) {
                planes[j] = inputs[0].with(null, outputs[j]);
            }
        }
        return planes;
    }

    /**
     * Applies the inverse one-dimensional dyadic wavelet transformation to signals stored low-pass half first at
     * every level, as the sub-band order of a wavelet-based collection places them.
     *
     * @param integers the reversible signals, or {@code null}
     * @param integerScratch a buffer as large as {@code integers}, or {@code null}
     * @param floats the irreversible signals, or {@code null}
     * @param floatScratch a buffer as large as {@code floats}, or {@code null}
     * @param m the signal length
     * @param origin the coordinate of the first sample
     * @param levels the number of decomposition levels
     * @param kernel the lifting kernel of every level
     * @param lanes the number of signals
     */
    static void inverseLevels(long[] integers, long[] integerScratch, float[] floats, float[] floatScratch, int m,
                              long origin, int levels, LiftingKernel kernel, int lanes) {
        for (var level = levels; level >= 1; level--) {
            var i0 = ceilDivPow2(origin, level - 1);
            var i1 = ceilDivPow2(origin + m, level - 1);
            var n = (int) (i1 - i0);
            if (n <= 0) {
                continue;
            }
            var sn = (int) (ceilDivPow2(origin + m, level) - ceilDivPow2(origin, level));
            var cas = (int) (i0 & 1);
            for (var p = 0; p < n; p++) {
                var source = p < sn ? 2 * p + cas : 2 * (p - sn) + 1 - cas;
                for (var lane = 0; lane < lanes; lane++) {
                    if (integers != null) {
                        integerScratch[source * lanes + lane] = integers[p * lanes + lane];
                    } else {
                        floatScratch[source * lanes + lane] = floats[p * lanes + lane];
                    }
                }
            }
            if (integers != null) {
                System.arraycopy(integerScratch, 0, integers, 0, n * lanes);
                kernel.inverse(integers, n, i0, lanes);
            } else {
                System.arraycopy(floatScratch, 0, floats, 0, n * lanes);
                kernel.inverse(floats, n, i0, lanes);
            }
        }
    }

    /**
     * Divides by a power of two, rounding up.
     *
     * @param value the dividend
     * @param shift the base-2 logarithm of the divisor
     * @return {@code ceil(value / 2^shift)}
     */
    private static long ceilDivPow2(long value, int shift) {
        return -Math.floorDiv(-value, 1L << shift);
    }

    /**
     * Adds integer offsets to integer results.
     *
     * @param inputs the input components, for the area
     * @param values the results
     * @param offsets the offsets, or {@code null}
     * @return the output components
     */
    private static Plane[] withOffsets(Plane[] inputs, long[][] values, double[] offsets) {
        var planes = new Plane[values.length];
        for (var i = 0; i < values.length; i++) {
            if (offsets != null) {
                var offset = integral(offsets[i]);
                for (var x = 0; x < values[i].length; x++) {
                    values[i][x] += offset;
                }
            }
            planes[i] = inputs[0].with(values[i], null);
        }
        return planes;
    }

    /**
     * Returns a coefficient of a reversible transformation as an integer.
     *
     * @param value the coefficient
     * @return the integer
     * @throws MediaException.Malformed if the coefficient is not an integer
     */
    private static long integral(double value) {
        if (value != Math.rint(value) || Math.abs(value) > 1L << 40) {
            throw new MediaException.Malformed("non-integer JPEG 2000 reversible MCT coefficient " + value);
        }
        return (long) value;
    }

    /**
     * Checks a scaling coefficient of a reversible transformation.
     *
     * @param value the coefficient
     * @param signed whether a negative power of two is allowed
     * @throws MediaException.Malformed if the coefficient is not a power of two
     */
    private static void checkScale(long value, boolean signed) {
        var magnitude = signed ? Math.abs(value) : value;
        if (magnitude <= 0 || Long.bitCount(magnitude) != 1) {
            throw new MediaException.Malformed("invalid JPEG 2000 reversible MCT scaling coefficient " + value);
        }
    }
}
