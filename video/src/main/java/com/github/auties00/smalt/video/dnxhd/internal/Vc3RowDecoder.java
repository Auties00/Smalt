package com.github.auties00.smalt.video.dnxhd.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.video.intra.internal.BlockKernel;
import com.github.auties00.smalt.video.intra.internal.IntraBitReader;
import com.github.auties00.smalt.video.intra.internal.VlcTable;

import java.util.Arrays;

/**
 * Decodes the macroblock scan lines of VC-3 coding units (SMPTE ST 2019-1 clauses 7.3 and 8): the
 * macroblock headers, the DC and AC entropy codes, inverse zig-zag and inverse quantization, then
 * the inverse transform and sample conversion of {@link BlockKernel}, one macroblock per batch of
 * blocks, and the Y'CbCr to RGB conversion of {@link Vc3Rgb} for the macroblocks of RGB coding
 * units that set the alternate color space flag; then the alpha macroblocks of the scan line,
 * DCT-coded like luma or run-length coded.
 *
 * <p>Instances hold the scratch buffers of one decoding thread and are not thread-safe.
 */
public final class Vc3RowDecoder {
    /**
     * The bit reader.
     */
    private final IntraBitReader reader = new IntraBitReader();

    /**
     * The dequantized coefficients of a macroblock, in batches of {@link BlockKernel#LANES}
     * blocks.
     */
    private final float[][] batches = new float[2][BlockKernel.BATCH_LENGTH];

    /**
     * The integer samples of one batch.
     */
    private final int[] samples = new int[BlockKernel.BATCH_LENGTH];

    /**
     * The index of the alpha channel's DC predictor in {@link #predictors}.
     */
    private static final int ALPHA = 3;

    /**
     * The DC predictor of every component type: the three video components, then alpha
     * (ST 2019-1 clause 8.2.4).
     */
    private final int[] predictors = new int[4];

    /**
     * Creates a row decoder.
     */
    public Vc3RowDecoder() {
    }

    /**
     * Decodes one macroblock scan line: its video macroblocks and, when the coding unit carries an
     * alpha channel, the alpha macroblocks that follow them (ST 2019-1 clause 8, Figure 40).
     *
     * @param data       the array holding the coding unit
     * @param start      the index of the scan line's first byte
     * @param end        the index one past the last byte the scan line may occupy
     * @param header     the coding unit's header
     * @param target     where the samples go
     * @param row        the scan line, counted from the top of the coding unit
     * @param widthInMb  the number of macroblocks of the scan line
     * @throws MediaException.Malformed if the scan line is corrupt, or a macroblock sets the
     *                                  alternate color space flag where ST 2019-1 clause 7.3.1.1
     *                                  forbids it
     */
    public void decode(byte[] data, int start, int end, Vc3Header header, Vc3Target target, int row, int widthInMb) {
        var compression = header.compression();
        var layout = Vc3Layout.of(header.subsampling());
        var blocks = layout.blocks();
        var lumaWeights = Vc3Tables.weights(compression.weights(), false);
        var chromaWeights = compression.chroma444() ? lumaWeights : Vc3Tables.weights(compression.weights(), true);
        var adaptive = compression.cid() == 1260;
        var alternate = compression.cid() == 1256 || compression.cid() == 1270;
        var conversionAllowed = header.rgb() && header.colorVolume() != Vc3Header.OUT_OF_BAND_VOLUME;
        var in = reader;
        in.reset(data, start, end);
        // Ambiguous: clause 8.2.4 keeps "a separate prediction value for each component type (ct = Y,
        // CB, CR, R, G, B or A)", which may mean that an RGB scan line mixing Y'CbCr (ACF) and RGB
        // macroblocks keeps six video predictors, or that Y/R, Cb/G and Cr/B name the same three
        // channels. Smalt keeps one predictor per channel position; streams whose macroblocks all
        // share one color space, as Smalt's and the known encoders' do, decode the same either way.
        Arrays.fill(predictors, 0);
        for (var mb = 0; mb < widthInMb; mb++) {
            var macroblockHeader = in.read(12);
            int scale;
            var fieldMode = false;
            var convert = false;
            if (adaptive) {
                fieldMode = (macroblockHeader >>> 11) != 0;
                scale = (macroblockHeader >>> 1) & 0x3FF;
            } else {
                scale = macroblockHeader >>> 1;
                if (alternate && (macroblockHeader & 1) != 0) {
                    if (!conversionAllowed) {
                        throw new MediaException.Malformed("VC-3 alternate color space flag outside an RGB coding unit"
                                + " with an in-band color volume");
                    }
                    convert = true;
                }
            }
            Arrays.fill(batches[0], 0f);
            if (blocks > BlockKernel.LANES) {
                Arrays.fill(batches[1], 0f);
            }
            for (var block = 0; block < blocks; block++) {
                var component = layout.components[block];
                decodeBlock(header, batches[block >>> 3], block & 7, component == 0 ? lumaWeights : chromaWeights,
                        component, scale);
            }
            if (in.overrun()) {
                throw malformed();
            }
            reconstruct(target, layout, blocks, mb, row, fieldMode, false);
            if (convert) {
                Vc3Rgb.toRgb(target, mb << 4, row << 4, header.depth());
            }
        }
        if (header.alpha()) {
            decodeAlpha(header, target, row, widthInMb);
        }
    }

    /**
     * Decodes the alpha macroblocks of a scan line: DCT-coded ones (clause 8.1.2), each with its
     * 12-bit macroblock header and four blocks coded like luma, or, with {@code LLA} set,
     * run-length coded ones (clause 8.3).
     *
     * @param header    the coding unit's header
     * @param target    where the samples go
     * @param row       the scan line
     * @param widthInMb the number of macroblocks of the scan line
     * @throws MediaException.Malformed if an alpha macroblock is corrupt
     */
    private void decodeAlpha(Vc3Header header, Vc3Target target, int row, int widthInMb) {
        var compression = header.compression();
        var weights = Vc3Tables.weights(compression.alphaWeights(), false);
        var adaptive = compression.cid() == 1260;
        var in = reader;
        var blocks = Vc3Layout.ALPHA.blocks();
        for (var mb = 0; mb < widthInMb; mb++) {
            if (header.losslessAlpha()) {
                decodeLosslessAlpha(header.depth(), target, mb << 4, row << 4);
                continue;
            }
            // Ambiguous: clause 7.3.1.1 starts every DCT-compressed macroblock with a 12-bit header,
            // and inverse quantization (clause 8.2.7) takes the scale factor from it, but the alpha
            // pseudo code of Figure 43 decodes the four blocks without reading one (as Figure 41 does
            // for video), which could mean alpha reuses its video macroblock's scale factor. Smalt
            // reads a header per alpha macroblock and applies its own scale factor and, for ID 1260,
            // its own MFF bit; the ACF bit of IDs 1256 and 1270 has no meaning for alpha and is ignored.
            var macroblockHeader = in.read(12);
            var fieldMode = adaptive && (macroblockHeader >>> 11) != 0;
            var scale = adaptive ? (macroblockHeader >>> 1) & 0x3FF : macroblockHeader >>> 1;
            Arrays.fill(batches[0], 0f);
            for (var block = 0; block < blocks; block++) {
                decodeBlock(header, batches[0], block, weights, ALPHA, scale);
            }
            if (in.overrun()) {
                throw malformed();
            }
            reconstruct(target, Vc3Layout.ALPHA, blocks, mb, row, fieldMode, true);
        }
    }

    /**
     * Decodes the entropy codes of one DCT block, inverse zig-zags and inverse quantizes them into
     * one lane of a batch (clauses 8.2.4 to 8.2.7).
     *
     * <p>The bits are consumed from a 64-bit cache that is topped up to at least 56 valid bits
     * before every coefficient, which fit the longest amplitude codeword, sign, amplitude index and
     * run codeword of one coefficient; the reader moves to the end of the block's codes once.
     *
     * @param header    the coding unit's header
     * @param batch     the batch, zeroed
     * @param lane      the block's lane
     * @param weights   the quantization weights of the block's component
     * @param component the DC predictor of the block: 0 to 2 for the video components,
     *                  {@link #ALPHA} for alpha
     * @param scale     the quantization scale factor of the macroblock
     * @throws MediaException.Malformed if the codes are invalid
     */
    private void decodeBlock(Vc3Header header, float[] batch, int lane, int[] weights, int component, int scale) {
        var compression = header.compression();
        var set = compression.codeSet();
        var amplitudes = Vc3Tables.amplitude(set);
        var runs = Vc3Tables.run(set);
        var p = compression.dequantization();
        var pShift = Integer.numberOfTrailingZeros(p);
        var pHalf = p >> 1;
        var indexBits = header.depth() == 8 ? 4 : 6;
        var maxDcBits = header.depth() == 8 ? 11 : 13;
        var in = reader;
        var data = in.array();
        var start = in.position();
        var nextByte = (start >>> 3) + 8;
        var cacheBits = 64 - (start & 7);
        var cache = IntraBitReader.word(data, start >>> 3) << (start & 7);
        var dcEntry = Vc3Tables.dc(set).lookup(cache);
        var size = VlcTable.length(dcEntry);
        if (size == 0) {
            throw malformed();
        }
        var bits = VlcTable.symbol(dcEntry);
        if (bits > maxDcBits) {
            throw malformed();
        }
        cache <<= size;
        cacheBits -= size;
        var difference = 0;
        if (bits > 0) {
            var value = (int) (cache >>> (64 - bits));
            cache <<= bits;
            cacheBits -= bits;
            difference = value >= 1 << (bits - 1) ? value : value + 1 - (1 << bits);
        }
        var dc = predictors[component] + difference;
        predictors[component] = dc;
        batch[lane] = dc;
        var index = 1;
        while (true) {
            if (cacheBits < 56) {
                cache |= IntraBitReader.word(data, nextByte) >>> cacheBits;
                var take = (63 - cacheBits) >>> 3;
                nextByte += take;
                cacheBits += take << 3;
            }
            var entry = amplitudes.lookup(cache);
            var length = VlcTable.length(entry);
            if (length == 0) {
                throw malformed();
            }
            var symbol = VlcTable.symbol(entry);
            if ((symbol & Vc3Tables.END_OF_BLOCK) != 0) {
                cacheBits -= length;
                break;
            }
            var window = cache << length;
            var sign = window >> 63;
            window <<= 1;
            var used = length + 1;
            var amplitude = symbol & Vc3Tables.AMPLITUDE_MASK;
            if ((symbol & Vc3Tables.INDEX_FLAG) != 0) {
                amplitude += (int) (window >>> (64 - indexBits)) << 6;
                window <<= indexBits;
                used += indexBits;
            }
            if ((symbol & Vc3Tables.RUN_FLAG) != 0) {
                var runEntry = runs.lookup(window);
                var runLength = VlcTable.length(runEntry);
                if (runLength == 0) {
                    throw malformed();
                }
                window <<= runLength;
                used += runLength;
                index += VlcTable.symbol(runEntry);
            }
            cache = window;
            cacheBits -= used;
            if (index > 63) {
                throw malformed();
            }
            var position = Vc3Tables.ZIGZAG[index];
            var weight = weights[position];
            var step = (long) weight * scale;
            var magnitude = ((long) amplitude * step + (step >> 1) + (weight != p ? pHalf : 0)) >> pShift;
            batch[position * BlockKernel.LANES + lane] = (float) ((magnitude ^ sign) - sign);
            index++;
        }
        in.seek((int) (nextByte * 8L - cacheBits));
    }

    /**
     * Inverse transforms the dequantized blocks of a macroblock and stores their samples.
     *
     * @param target    the output description
     * @param layout    the macroblock layout
     * @param blocks    the number of blocks
     * @param mb        the macroblock's column
     * @param row       the scan line
     * @param fieldMode whether the macroblock is in field macroblock mode (ID 1260, Figure 14): the
     *                  upper blocks of a 16-line component hold the top field's lines, the lower
     *                  blocks the bottom field's
     * @param alpha     whether the blocks are alpha blocks
     */
    private void reconstruct(Vc3Target target, Vc3Layout layout, int blocks, int mb, int row, boolean fieldMode,
                             boolean alpha) {
        var kernel = BlockKernel.INSTANCE;
        for (var batch = 0; batch * BlockKernel.LANES < blocks; batch++) {
            var values = batches[batch];
            kernel.inverse(values);
            kernel.toSamples(values, target.scale(), target.offset(), target.limit(), samples);
            var lanes = Math.min(BlockKernel.LANES, blocks - batch * BlockKernel.LANES);
            for (var lane = 0; lane < lanes; lane++) {
                var block = batch * BlockKernel.LANES + lane;
                var component = layout.components[block];
                var shiftX = component == 0 ? 0 : layout.chromaShiftX;
                var shiftY = component == 0 ? 0 : layout.chromaShiftY;
                var x = (mb << (4 - shiftX)) + layout.xs[block];
                var top = row << (4 - shiftY);
                var y = layout.ys[block];
                int first;
                int step;
                if (fieldMode && (16 >> shiftY) == 16) {
                    first = top + (y == 0 ? 0 : 1);
                    step = 2;
                } else {
                    first = top + y;
                    step = 1;
                }
                if (alpha) {
                    store(target, target.alpha16(), target.alpha8(), target.strides()[0], lane, x, first, step);
                } else if (target.wide()) {
                    store(target, target.planes16()[component], null, target.strides()[component], lane, x, first, step);
                } else {
                    store(target, null, target.planes8()[component], target.strides()[component], lane, x, first, step);
                }
            }
        }
    }

    /**
     * Decodes one run-length coded alpha macroblock (ST 2019-1 clauses 7.3.1.2 and 8.3) into the
     * alpha plane.
     *
     * <p>The 4-bit alpha macroblock header carries the RLE indicator {@code RL} in its least
     * significant bit: set for differential run-length codewords, clear for 256 packed samples. The
     * samples follow the alpha zig-zag of Figure 16, the rows of the macroblock alternately left to
     * right and right to left. Each codeword holds a predictor flag {@code P}, a run length
     * {@code NRL} as an Elias gamma code whose remainder is stored most significant bit first
     * (Figures 36 and 28), a Rice flag {@code RI} and a difference codeword {@code DCW}, plain
     * or Golomb-Rice of order {@code n / 2 - 2}. The difference applies to every sample of the run,
     * added to the previous sample ({@code P = 0}) or to the linear extrapolation of the previous
     * two ({@code P = 1}) modulo {@code 2^n}, both predictors starting at {@code 2^(n-1) - 1}.
     *
     * @param depth  the alpha sample bit depth {@code n}, the stream's
     * @param target the output description
     * @param x      the first column of the macroblock
     * @param top    the first coding unit row of the macroblock
     * @throws MediaException.Malformed if the codewords are invalid or overrun the macroblock
     */
    private void decodeLosslessAlpha(int depth, Vc3Target target, int x, int top) {
        var in = reader;
        var mask = (1 << depth) - 1;
        var values = samples;
        // Ambiguous: the prose of clause 7.3.1.2 defines RL = 1b as run-length codewords and 0b as 256
        // packed samples; the pseudo code of Figure 50 reads the packed samples when the bit is 1.
        // Smalt follows the prose, which Amendment 1:2023 clause 2 ranks above formal languages and
        // figures.
        if ((in.read(4) & 1) == 0) {
            for (var count = 0; count < 256; count++) {
                values[count] = in.read(depth);
            }
        } else {
            var riceOrder = depth / 2 - 2;
            var last = mask >> 1;
            var beforeLast = last;
            var count = 0;
            while (count < 256) {
                var extrapolate = in.readBit() != 0;
                var zeros = 0;
                while (in.readBit() == 0) {
                    if (++zeros > 8 || in.overrun()) {
                        throw malformed();
                    }
                }
                // Ambiguous: Figure 36 draws the remainder R as an N-bit field of a codeword whose
                // msb comes first, as Figure 28 maps every binary value, but its note puts "the lsb of
                // R next to the 1 bit separator". Smalt follows the normative figures, R most
                // significant bit first, as the precedence rule of Amendment 1:2023 clause 2 selects:
                // a paragraph starting with "Note:" is informative.
                var remainder = 0;
                for (var bit = 0; bit < zeros; bit++) {
                    remainder = remainder << 1 | in.readBit();
                }
                var run = (1 << zeros) + remainder;
                if (run > 256 - count) {
                    throw malformed();
                }
                int codeword;
                if (in.readBit() != 0) {
                    var quotient = 0;
                    while (in.readBit() != 0) {
                        if (++quotient > mask >> riceOrder || in.overrun()) {
                            throw malformed();
                        }
                    }
                    codeword = quotient << riceOrder | in.read(riceOrder);
                } else {
                    codeword = in.read(depth);
                }
                var difference = (codeword & 1) != 0 ? mask - (codeword >>> 1) : codeword >>> 1;
                for (var k = 0; k < run; k++, count++) {
                    var value = (extrapolate ? 2 * last - beforeLast + difference : last + difference) & mask;
                    values[count] = value;
                    beforeLast = last;
                    last = value;
                }
            }
        }
        if (in.overrun()) {
            throw malformed();
        }
        var shift = target.alphaShift();
        var max = mask >> shift;
        var half = shift == 0 ? 0 : 1 << (shift - 1);
        var stride = target.strides()[0];
        for (var count = 0; count < 256; count++) {
            var line = count >>> 4;
            var column = (line & 1) == 0 ? count & 15 : 15 - (count & 15);
            var at = ((top + line) * target.rowStep() + target.rowOffset()) * stride + x + column;
            var value = Math.min(max, (values[count] + half) >> shift);
            if (target.alpha16() != null) {
                target.alpha16()[at] = (short) value;
            } else {
                target.alpha8()[at] = (byte) value;
            }
        }
    }

    /**
     * Writes one block of {@link #samples} into a plane.
     *
     * @param target the output description
     * @param wide   the 16-bit plane, or {@code null}
     * @param narrow the 8-bit plane, used when {@code wide} is {@code null}
     * @param stride the plane's row stride
     * @param lane   the block's lane
     * @param x      the block's first column
     * @param first  the coding unit row of the block's first row
     * @param step   the distance in coding unit rows between the block's rows
     */
    private void store(Vc3Target target, short[] wide, byte[] narrow, int stride, int lane, int x, int first, int step) {
        var rowStep = target.rowStep();
        var s = samples;
        if (wide != null) {
            for (var row = 0; row < 8; row++) {
                var at = ((first + row * step) * rowStep + target.rowOffset()) * stride + x;
                var from = row * 64 + lane;
                for (var column = 0; column < 8; column++) {
                    wide[at + column] = (short) s[from + column * 8];
                }
            }
        } else {
            for (var row = 0; row < 8; row++) {
                var at = ((first + row * step) * rowStep + target.rowOffset()) * stride + x;
                var from = row * 64 + lane;
                for (var column = 0; column < 8; column++) {
                    narrow[at + column] = (byte) s[from + column * 8];
                }
            }
        }
    }

    /**
     * Creates the exception of a corrupt scan line.
     *
     * @return the exception
     */
    private static MediaException.Malformed malformed() {
        return new MediaException.Malformed("corrupt VC-3 macroblock data");
    }
}
