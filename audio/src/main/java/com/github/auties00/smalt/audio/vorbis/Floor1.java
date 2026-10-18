package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.MediaException;

import com.github.auties00.smalt.audio.vorbis.internal.Ilog;
import java.util.Arrays;

/**
 * Implements Vorbis floor type 1: a piecewise linear envelope on a dB amplitude scale, coded as
 * iteratively predicted Y values at configured X positions (Vorbis I section 7).
 *
 * @implNote This implementation follows the specification's integer algorithms exactly (render
 * point, the Bresenham-like line renderer and the inverse dB table), applying the table value to
 * the residue as each line is rendered. Final Y values are clamped to {@code [0, range)} after
 * amplitude synthesis, as the specification suggests, so hostile streams cannot index outside the
 * table.
 */
final class Floor1 extends Floor {
    /**
     * The linear amplitude of each floor 1 dB step (Vorbis I section 10.1,
     * {@code floor1_inverse_dB_table}).
     */
    static final float[] INVERSE_DB = {
            1.0649863e-07f, 1.1341951e-07f, 1.2079015e-07f, 1.2863978e-07f,
            1.3699951e-07f, 1.4590251e-07f, 1.5538408e-07f, 1.6548181e-07f,
            1.7623575e-07f, 1.8768855e-07f, 1.9988561e-07f, 2.1287530e-07f,
            2.2670913e-07f, 2.4144197e-07f, 2.5713223e-07f, 2.7384213e-07f,
            2.9163793e-07f, 3.1059021e-07f, 3.3077411e-07f, 3.5226968e-07f,
            3.7516214e-07f, 3.9954229e-07f, 4.2550680e-07f, 4.5315863e-07f,
            4.8260743e-07f, 5.1396998e-07f, 5.4737065e-07f, 5.8294187e-07f,
            6.2082472e-07f, 6.6116941e-07f, 7.0413592e-07f, 7.4989464e-07f,
            7.9862701e-07f, 8.5052630e-07f, 9.0579828e-07f, 9.6466216e-07f,
            1.0273513e-06f, 1.0941144e-06f, 1.1652161e-06f, 1.2409384e-06f,
            1.3215816e-06f, 1.4074654e-06f, 1.4989305e-06f, 1.5963394e-06f,
            1.7000785e-06f, 1.8105592e-06f, 1.9282195e-06f, 2.0535261e-06f,
            2.1869758e-06f, 2.3290978e-06f, 2.4804557e-06f, 2.6416497e-06f,
            2.8133190e-06f, 2.9961443e-06f, 3.1908506e-06f, 3.3982101e-06f,
            3.6190449e-06f, 3.8542308e-06f, 4.1047004e-06f, 4.3714470e-06f,
            4.6555282e-06f, 4.9580707e-06f, 5.2802740e-06f, 5.6234160e-06f,
            5.9888572e-06f, 6.3780469e-06f, 6.7925283e-06f, 7.2339451e-06f,
            7.7040476e-06f, 8.2047000e-06f, 8.7378876e-06f, 9.3057248e-06f,
            9.9104632e-06f, 1.0554501e-05f, 1.1240392e-05f, 1.1970856e-05f,
            1.2748789e-05f, 1.3577278e-05f, 1.4459606e-05f, 1.5399272e-05f,
            1.6400004e-05f, 1.7465768e-05f, 1.8600792e-05f, 1.9809576e-05f,
            2.1096914e-05f, 2.2467911e-05f, 2.3928002e-05f, 2.5482978e-05f,
            2.7139006e-05f, 2.8902651e-05f, 3.0780908e-05f, 3.2781225e-05f,
            3.4911534e-05f, 3.7180282e-05f, 3.9596466e-05f, 4.2169667e-05f,
            4.4910090e-05f, 4.7828601e-05f, 5.0936773e-05f, 5.4246931e-05f,
            5.7772202e-05f, 6.1526565e-05f, 6.5524908e-05f, 6.9783085e-05f,
            7.4317983e-05f, 7.9147585e-05f, 8.4291040e-05f, 8.9768747e-05f,
            9.5602426e-05f, 0.00010181521f, 0.00010843174f, 0.00011547824f,
            0.00012298267f, 0.00013097477f, 0.00013948625f, 0.00014855085f,
            0.00015820453f, 0.00016848555f, 0.00017943469f, 0.00019109536f,
            0.00020351382f, 0.00021673929f, 0.00023082423f, 0.00024582449f,
            0.00026179955f, 0.00027881276f, 0.00029693158f, 0.00031622787f,
            0.00033677814f, 0.00035866388f, 0.00038197188f, 0.00040679456f,
            0.00043323036f, 0.00046138411f, 0.00049136745f, 0.00052329927f,
            0.00055730621f, 0.00059352311f, 0.00063209358f, 0.00067317058f,
            0.00071691700f, 0.00076350630f, 0.00081312324f, 0.00086596457f,
            0.00092223983f, 0.00098217216f, 0.0010459992f, 0.0011139742f,
            0.0011863665f, 0.0012634633f, 0.0013455702f, 0.0014330129f,
            0.0015261382f, 0.0016253153f, 0.0017309374f, 0.0018434235f,
            0.0019632195f, 0.0020908006f, 0.0022266726f, 0.0023713743f,
            0.0025254795f, 0.0026895994f, 0.0028643847f, 0.0030505286f,
            0.0032487691f, 0.0034598925f, 0.0036847358f, 0.0039241906f,
            0.0041792066f, 0.0044507950f, 0.0047400328f, 0.0050480668f,
            0.0053761186f, 0.0057254891f, 0.0060975636f, 0.0064938176f,
            0.0069158225f, 0.0073652516f, 0.0078438871f, 0.0083536271f,
            0.0088964928f, 0.009474637f, 0.010090352f, 0.010746080f,
            0.011444421f, 0.012188144f, 0.012980198f, 0.013823725f,
            0.014722068f, 0.015678791f, 0.016697687f, 0.017782797f,
            0.018938423f, 0.020169149f, 0.021479854f, 0.022875735f,
            0.024362330f, 0.025945531f, 0.027631618f, 0.029427276f,
            0.031339626f, 0.033376252f, 0.035545228f, 0.037855157f,
            0.040315199f, 0.042935108f, 0.045725273f, 0.048696758f,
            0.051861348f, 0.055231591f, 0.058820850f, 0.062643361f,
            0.066714279f, 0.071049749f, 0.075666962f, 0.080584227f,
            0.085821044f, 0.091398179f, 0.097337747f, 0.10366330f,
            0.11039993f, 0.11757434f, 0.12521498f, 0.13335215f,
            0.14201813f, 0.15124727f, 0.16107617f, 0.17154380f,
            0.18269168f, 0.19456402f, 0.20720788f, 0.22067342f,
            0.23501402f, 0.25028656f, 0.26655159f, 0.28387361f,
            0.30232132f, 0.32196786f, 0.34289114f, 0.36517414f,
            0.38890521f, 0.41417847f, 0.44109412f, 0.46975890f,
            0.50028648f, 0.53279791f, 0.56742212f, 0.60429640f,
            0.64356699f, 0.68538959f, 0.72993007f, 0.77736504f,
            0.82788260f, 0.88168307f, 0.9389798f, 1.f,
    };

    /**
     * The Y value range selected by each multiplier minus one (Vorbis I section 7.2.3).
     */
    private static final int[] RANGES = {256, 128, 86, 64};

    /**
     * The largest number of X positions a floor 1 may declare, including the two implicit ones.
     */
    static final int MAX_VALUES = 65;

    /**
     * The codebooks of the stream.
     */
    private final Codebook[] books;

    /**
     * The class of each partition.
     */
    private final int[] partitionClasses;

    /**
     * The number of Y values each class codes.
     */
    private final int[] classDimensions;

    /**
     * The base-two logarithm of the number of subclass books of each class.
     */
    private final int[] classSubclasses;

    /**
     * The master book of each class, or {@code -1} for classes without subclasses.
     */
    private final int[] classMasterbooks;

    /**
     * The book of each subclass of each class, or {@code -1} when the value is implicitly zero.
     */
    private final int[][] subclassBooks;

    /**
     * The amplitude multiplier, from 1 to 4.
     */
    private final int multiplier;

    /**
     * The Y value range, {@link #RANGES} indexed by the multiplier minus one.
     */
    private final int range;

    /**
     * The width in bits of the first two Y values.
     */
    private final int yBits;

    /**
     * The X position of each value in list order.
     */
    private final int[] xList;

    /**
     * The number of values, including the two implicit ones.
     */
    private final int valueCount;

    /**
     * The list index of the low neighbor of each value from index 2.
     */
    private final int[] lowNeighbors;

    /**
     * The list index of the high neighbor of each value from index 2.
     */
    private final int[] highNeighbors;

    /**
     * The list indices of the values in ascending X order.
     */
    private final int[] sortedIndices;

    /**
     * Constructs a floor from its parsed fields.
     *
     * @param books the codebooks of the stream
     * @param partitionClasses the class of each partition
     * @param classDimensions the dimensions of each class
     * @param classSubclasses the subclass exponent of each class
     * @param classMasterbooks the master book of each class
     * @param subclassBooks the subclass books of each class
     * @param multiplier the amplitude multiplier
     * @param xList the X positions in list order
     */
    private Floor1(Codebook[] books, int[] partitionClasses, int[] classDimensions, int[] classSubclasses,
                   int[] classMasterbooks, int[][] subclassBooks, int multiplier, int[] xList) {
        this.books = books;
        this.partitionClasses = partitionClasses;
        this.classDimensions = classDimensions;
        this.classSubclasses = classSubclasses;
        this.classMasterbooks = classMasterbooks;
        this.subclassBooks = subclassBooks;
        this.multiplier = multiplier;
        this.range = RANGES[multiplier - 1];
        this.yBits = Ilog.ilog(range - 1);
        this.xList = xList;
        this.valueCount = xList.length;
        this.lowNeighbors = new int[valueCount];
        this.highNeighbors = new int[valueCount];
        for (var i = 2; i < valueCount; i++) {
            var low = 0;
            var high = 1;
            for (var j = 0; j < i; j++) {
                var x = xList[j];
                if (x < xList[i] && x > xList[low]) {
                    low = j;
                }
                if (x > xList[i] && x < xList[high]) {
                    high = j;
                }
            }
            lowNeighbors[i] = low;
            highNeighbors[i] = high;
        }
        var keys = new long[valueCount];
        for (var i = 0; i < valueCount; i++) {
            keys[i] = (long) xList[i] << 32 | i;
        }
        Arrays.sort(keys);
        this.sortedIndices = new int[valueCount];
        for (var i = 0; i < valueCount; i++) {
            sortedIndices[i] = (int) keys[i];
        }
    }

    /**
     * Reads a floor 1 configuration from the setup header (Vorbis I section 7.2.2).
     *
     * @param reader the setup header reader, positioned after the floor type
     * @param books the codebooks of the stream
     * @return the floor
     * @throws MediaException.Malformed if the configuration is truncated, references a missing
     * codebook, declares more than 65 values or repeats an X
     */
    static Floor1 read(BitReader reader, Codebook[] books) {
        var partitions = reader.read(5);
        var partitionClasses = new int[partitions];
        var maxClass = -1;
        for (var i = 0; i < partitions; i++) {
            partitionClasses[i] = reader.read(4);
            maxClass = Math.max(maxClass, partitionClasses[i]);
        }
        var classes = maxClass + 1;
        var classDimensions = new int[classes];
        var classSubclasses = new int[classes];
        var classMasterbooks = new int[classes];
        var subclassBooks = new int[classes][];
        for (var c = 0; c < classes; c++) {
            classDimensions[c] = reader.read(3) + 1;
            classSubclasses[c] = reader.read(2);
            classMasterbooks[c] = -1;
            if (classSubclasses[c] != 0) {
                classMasterbooks[c] = checkBook(reader.read(8), books);
            }
            var subclasses = new int[1 << classSubclasses[c]];
            for (var j = 0; j < subclasses.length; j++) {
                var book = reader.read(8) - 1;
                subclasses[j] = book < 0 ? -1 : checkBook(book, books);
            }
            subclassBooks[c] = subclasses;
        }
        var multiplier = reader.read(2) + 1;
        var rangeBits = reader.read(4);
        var count = 2;
        for (var i = 0; i < partitions; i++) {
            count += classDimensions[partitionClasses[i]];
        }
        if (count > MAX_VALUES) {
            throw new MediaException.Malformed("Vorbis floor 1 declares " + count + " values, more than " + MAX_VALUES);
        }
        var xList = new int[count];
        xList[1] = 1 << rangeBits;
        var index = 2;
        for (var i = 0; i < partitions; i++) {
            var dimensions = classDimensions[partitionClasses[i]];
            for (var j = 0; j < dimensions; j++) {
                xList[index++] = reader.read(rangeBits);
            }
        }
        VorbisSetup.checkNotTruncated(reader);
        var sorted = xList.clone();
        Arrays.sort(sorted);
        for (var i = 1; i < sorted.length; i++) {
            if (sorted[i] == sorted[i - 1]) {
                throw new MediaException.Malformed("Vorbis floor 1 repeats X position " + sorted[i]);
            }
        }
        return new Floor1(books, partitionClasses, classDimensions, classSubclasses, classMasterbooks,
                subclassBooks, multiplier, xList);
    }

    /**
     * Validates a codebook number.
     *
     * @param book the codebook number
     * @param books the codebooks of the stream
     * @return {@code book}
     * @throws MediaException.Malformed if the codebook does not exist
     */
    private static int checkBook(int book, Codebook[] books) {
        if (book >= books.length) {
            throw new MediaException.Malformed("Vorbis floor 1 references missing codebook " + book);
        }
        return book;
    }

    @Override
    boolean decode(BitReader reader, FloorData data) {
        if (reader.readBit() == 0) {
            return false;
        }
        var y = data.values;
        y[0] = reader.read(yBits);
        y[1] = reader.read(yBits);
        var offset = 2;
        var classes = partitionClasses;
        for (var i = 0; i < classes.length; i++) {
            var cls = classes[i];
            var dimensions = classDimensions[cls];
            var bits = classSubclasses[cls];
            var mask = (1 << bits) - 1;
            var value = 0;
            if (bits > 0) {
                value = books[classMasterbooks[cls]].decode(reader);
                if (value < 0) {
                    return false;
                }
            }
            var subclasses = subclassBooks[cls];
            for (var j = 0; j < dimensions; j++) {
                var book = subclasses[value & mask];
                value >>>= bits;
                if (book >= 0) {
                    var decoded = books[book].decode(reader);
                    if (decoded < 0) {
                        return false;
                    }
                    y[offset + j] = decoded;
                } else {
                    y[offset + j] = 0;
                }
            }
            offset += dimensions;
        }
        return !reader.overrun();
    }

    @Override
    void apply(FloorData data, float[] spectrum, int n, boolean longBlock) {
        var y = data.values;
        var finalY = data.finalValues;
        var step2 = data.step2;
        var xs = xList;
        var range = this.range;
        finalY[0] = y[0];
        finalY[1] = y[1];
        step2[0] = true;
        step2[1] = true;
        for (var i = 2; i < valueCount; i++) {
            var low = lowNeighbors[i];
            var high = highNeighbors[i];
            var predicted = renderPoint(xs[low], finalY[low], xs[high], finalY[high], xs[i]);
            var value = y[i];
            var highRoom = range - predicted;
            var lowRoom = predicted;
            var room = (highRoom < lowRoom ? highRoom : lowRoom) * 2;
            if (value != 0) {
                step2[low] = true;
                step2[high] = true;
                step2[i] = true;
                if (value >= room) {
                    finalY[i] = highRoom > lowRoom ? value - lowRoom + predicted : predicted - value + highRoom - 1;
                } else {
                    finalY[i] = (value & 1) != 0 ? predicted - ((value + 1) >> 1) : predicted + (value >> 1);
                }
            } else {
                step2[i] = false;
                finalY[i] = predicted;
            }
        }
        for (var i = 0; i < valueCount; i++) {
            finalY[i] = Math.clamp(finalY[i], 0, range - 1);
        }
        var multiplier = this.multiplier;
        var lx = 0;
        var ly = finalY[sortedIndices[0]] * multiplier;
        var hx = 0;
        var hy = ly;
        for (var k = 1; k < valueCount; k++) {
            var i = sortedIndices[k];
            if (step2[i]) {
                hy = finalY[i] * multiplier;
                hx = xs[i];
                renderLine(lx, ly, hx, hy, spectrum, n);
                lx = hx;
                ly = hy;
            }
        }
        if (hx < n) {
            renderLine(hx, hy, n, hy, spectrum, n);
        }
    }

    /**
     * Returns the Y value at {@code x} on the line through two points (Vorbis I section 9.2.6).
     *
     * @param x0 the first X, below {@code x1}
     * @param y0 the first Y
     * @param x1 the second X
     * @param y1 the second Y
     * @param x the X to evaluate, between {@code x0} and {@code x1}
     * @return the Y value, truncated toward {@code y0}
     */
    static int renderPoint(int x0, int y0, int x1, int y1, int x) {
        var dy = y1 - y0;
        var adx = x1 - x0;
        var err = Math.abs((long) dy) * (x - x0);
        // Valid streams keep the product far below 2^31, where a 32-bit division is much cheaper.
        var off = err <= Integer.MAX_VALUE ? (int) err / adx : (int) (err / adx);
        return dy < 0 ? y0 - off : y0 + off;
    }

    /**
     * Renders one line segment of the floor curve and multiplies the covered spectral lines by
     * the linear amplitude of each rendered Y (Vorbis I section 9.2.7).
     *
     * <p>Lines at and beyond {@code n} are not rendered, which implements the truncation of the
     * curve to the block's spectral length.
     *
     * @param x0 the start X, inclusive
     * @param y0 the start Y, within the inverse dB table
     * @param x1 the end X, exclusive, above {@code x0}
     * @param y1 the end Y, within the inverse dB table
     * @param spectrum the residue, multiplied in place
     * @param n the number of spectral lines
     */
    static void renderLine(int x0, int y0, int x1, int y1, float[] spectrum, int n) {
        var dy = y1 - y0;
        var adx = x1 - x0;
        var base = dy / adx;
        var ady = Math.abs(dy) - Math.abs(base) * adx;
        var sy = dy < 0 ? base - 1 : base + 1;
        var end = Math.min(x1, n);
        var x = x0;
        var y = y0;
        var err = 0;
        var table = INVERSE_DB;
        if (x >= end) {
            return;
        }
        spectrum[x] *= table[y];
        if (base == 0) {
            renderShallow(x + 1, y, ady, adx, sy, spectrum, end);
            return;
        }
        while (++x < end) {
            err += ady;
            if (err >= adx) {
                err -= adx;
                y += sy;
            } else {
                y += base;
            }
            spectrum[x] *= table[y];
        }
    }

    /**
     * Renders the rest of a line whose Y changes by at most one step per X, as runs of equal Y.
     *
     * <p>This computes exactly the values of the specification's per-X recurrence: after {@code k}
     * further steps the error term crosses {@code adx} for the first time when
     * {@code k = ceil((adx - err) / ady)}, so the {@code k - 1} lines before that keep the
     * current Y and the {@code k}-th moves by {@code sy}.
     *
     * @param x the next X to render
     * @param y the Y of the previous X
     * @param ady the error increment per X, below {@code adx}
     * @param adx the line's X extent
     * @param sy the Y step when the error term crosses {@code adx}
     * @param spectrum the residue, multiplied in place
     * @param end the X at which rendering stops, exclusive
     */
    private static void renderShallow(int x, int y, int ady, int adx, int sy, float[] spectrum, int end) {
        var table = INVERSE_DB;
        if (ady == 0) {
            var amplitude = table[y];
            for (; x < end; x++) {
                spectrum[x] *= amplitude;
            }
            return;
        }
        var err = 0;
        while (x < end) {
            var steps = (adx - err + ady - 1) / ady;
            var runEnd = Math.min(x + steps - 1, end);
            var amplitude = table[y];
            for (; x < runEnd; x++) {
                spectrum[x] *= amplitude;
            }
            if (x >= end) {
                return;
            }
            err += steps * ady - adx;
            y += sy;
            spectrum[x++] *= table[y];
        }
    }

    @Override
    int intCapacity() {
        return valueCount;
    }

    @Override
    int floatCapacity() {
        return 0;
    }
}
