/* Ports minimp3 (minimp3.h). By lieff; CC0 1.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.internal.simd.Simd;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link SynthesisKernel} with the Vector API; the output equals
 * {@link ScalarSynthesisKernel} sample for sample.
 *
 * @implNote This implementation follows the kernel rules of
 * {@link com.github.auties00.smalt.internal.simd.Simd} and the SSE paths of minimp3's
 * {@code mp3d_DCT_II} and {@code mp3d_synth}: the DCT runs four time slots per pass, one per 128-bit
 * lane, and the window runs two output positions per pass on 256-bit hardware, with the
 * coefficients pre-arranged in {@link #PAIR_WEIGHTS}. The window accumulations are straight-line
 * code ({@link #accumulateNarrow(float[], int, float[], int, float[])}), because a loop whose
 * conditional accumulation merged vectors left the kernel on the allocating fallback.
 */
final class VectorSynthesisKernel implements SynthesisKernel {
    /**
     * The four-lane float species.
     */
    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_128;

    /**
     * The eight-lane float species of the wide window path.
     */
    private static final VectorSpecies<Float> WIDE = FloatVector.SPECIES_256;

    /**
     * The exact class of the vectors of {@link #SPECIES}.
     */
    private static final Class<FloatVector> FLOAT_VECTOR = vectorClass(SPECIES);

    /**
     * The exact class of the vectors of {@link #WIDE}.
     */
    private static final Class<FloatVector> WIDE_VECTOR = vectorClass(WIDE);


    /**
     * Holds, for each of the seven output-position pairs {@code (13 - 2p, 14 - 2p)} and each of
     * the eight window steps, the coefficient {@code w0} broadcast over the four lanes of each
     * position followed by {@code w1} likewise: 16 floats per step.
     */
    private static final float[] PAIR_WEIGHTS = pairWeights();

    /**
     * Whether this instance runs the eight-lane window path, which it does only where
     * {@link Simd#WIDE} holds.
     */
    private final boolean wide;

    /**
     * The scalar kernel that handles the two leftover time slots of each granule.
     */
    private final ScalarSynthesisKernel tail = new ScalarSynthesisKernel();

    /**
     * The 32 four-lane rows of one DCT pass.
     */
    private final float[] rows = new float[32 * 4];

    /**
     * The window results of one or two output positions: the {@code a} lanes then the {@code b}
     * lanes.
     */
    private final float[] results = new float[16];

    /**
     * The transposed rows of the long IMDCT, each of eight subband lanes: 18 rows for the spectrum,
     * nine for the overlap, then 18 for the outputs of the two 9-point DCTs.
     */
    private final float[] laneRows = new float[45 * 8];

    /**
     * Creates a kernel.
     *
     * @param wide whether to run the eight-lane window path
     */
    private VectorSynthesisKernel(boolean wide) {
        this.wide = wide;
    }

    /**
     * Returns a new kernel whose window path suits the platform's preferred vector width.
     *
     * @return the kernel
     */
    static SynthesisKernel create() {
        return new VectorSynthesisKernel(true);
    }

    /**
     * Returns a new kernel with a chosen window path.
     *
     * @param wide whether to run the eight-lane window path
     * @return the kernel
     */
    static SynthesisKernel create(boolean wide) {
        return new VectorSynthesisKernel(wide);
    }

    /**
     * Returns the exact class of the vectors of a species.
     *
     * @param species the vector shape
     * @param <E> the element type
     * @param <V> the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    /**
     * Builds {@link #PAIR_WEIGHTS} from {@link Mp3Tables#SYNTH_WINDOW}.
     *
     * @return the table
     */
    private static float[] pairWeights() {
        var w = Mp3Tables.SYNTH_WINDOW;
        var table = new float[7 * 8 * 16];
        for (var p = 0; p < 7; p++) {
            var high = 14 - 2 * p;
            var low = high - 1;
            for (var k = 0; k < 8; k++) {
                var base = (p * 8 + k) * 16;
                for (var lane = 0; lane < 4; lane++) {
                    table[base + lane] = w[(14 - low) * 16 + 2 * k];
                    table[base + 4 + lane] = w[(14 - high) * 16 + 2 * k];
                    table[base + 8 + lane] = w[(14 - low) * 16 + 2 * k + 1];
                    table[base + 12 + lane] = w[(14 - high) * 16 + 2 * k + 1];
                }
            }
        }
        return table;
    }

    @Override
    public void dct(float[] grbuf, int base, int slots) {
        var sec = Mp3Tables.DCT_SECANTS;
        var t = rows;
        var k = 0;
        for (; k + 4 <= slots; k += 4) {
            var y = base + k;
            for (var i = 0; i < 8; i++) {
                var x0 = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, grbuf, y + i * 18));
                var x1 = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, grbuf, y + (15 - i) * 18));
                var x2 = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, grbuf, y + (16 + i) * 18));
                var x3 = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, grbuf, y + (31 - i) * 18));
                var t0 = x0.lanewise(VectorOperators.ADD, x3);
                var t1 = x1.lanewise(VectorOperators.ADD, x2);
                var t2 = x1.lanewise(VectorOperators.SUB, x2).lanewise(VectorOperators.MUL, FloatVector.broadcast(SPECIES, sec[3 * i]));
                var t3 = x0.lanewise(VectorOperators.SUB, x3).lanewise(VectorOperators.MUL, FloatVector.broadcast(SPECIES, sec[3 * i + 1]));
                t0.lanewise(VectorOperators.ADD, t1).intoArray(t, i * 4);
                t0.lanewise(VectorOperators.SUB, t1).lanewise(VectorOperators.MUL, FloatVector.broadcast(SPECIES, sec[3 * i + 2])).intoArray(t, (8 + i) * 4);
                t3.lanewise(VectorOperators.ADD, t2).intoArray(t, (16 + i) * 4);
                t3.lanewise(VectorOperators.SUB, t2).lanewise(VectorOperators.MUL, FloatVector.broadcast(SPECIES, sec[3 * i + 2])).intoArray(t, (24 + i) * 4);
            }
            for (var r = 0; r < 32; r += 8) {
                var x0 = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, t, r * 4));
                var x1 = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, t, (r + 1) * 4));
                var x2 = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, t, (r + 2) * 4));
                var x3 = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, t, (r + 3) * 4));
                var x4 = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, t, (r + 4) * 4));
                var x5 = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, t, (r + 5) * 4));
                var x6 = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, t, (r + 6) * 4));
                var x7 = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, t, (r + 7) * 4));
                var xt = x0.lanewise(VectorOperators.SUB, x7);
                x0 = x0.lanewise(VectorOperators.ADD, x7);
                x7 = x1.lanewise(VectorOperators.SUB, x6);
                x1 = x1.lanewise(VectorOperators.ADD, x6);
                x6 = x2.lanewise(VectorOperators.SUB, x5);
                x2 = x2.lanewise(VectorOperators.ADD, x5);
                x5 = x3.lanewise(VectorOperators.SUB, x4);
                x3 = x3.lanewise(VectorOperators.ADD, x4);
                x4 = x0.lanewise(VectorOperators.SUB, x3);
                x0 = x0.lanewise(VectorOperators.ADD, x3);
                x3 = x1.lanewise(VectorOperators.SUB, x2);
                x1 = x1.lanewise(VectorOperators.ADD, x2);
                x0.lanewise(VectorOperators.ADD, x1).intoArray(t, r * 4);
                x0.lanewise(VectorOperators.SUB, x1).lanewise(VectorOperators.MUL, FloatVector.broadcast(SPECIES, 0.70710677f)).intoArray(t, (r + 4) * 4);
                x5 = x5.lanewise(VectorOperators.ADD, x6);
                x6 = x6.lanewise(VectorOperators.ADD, x7).lanewise(VectorOperators.MUL, FloatVector.broadcast(SPECIES, 0.70710677f));
                x7 = x7.lanewise(VectorOperators.ADD, xt);
                x3 = x3.lanewise(VectorOperators.ADD, x4).lanewise(VectorOperators.MUL, FloatVector.broadcast(SPECIES, 0.70710677f));
                x5 = x5.lanewise(VectorOperators.SUB, x7.lanewise(VectorOperators.MUL, FloatVector.broadcast(SPECIES, 0.198912367f)));
                x7 = x7.lanewise(VectorOperators.ADD, x5.lanewise(VectorOperators.MUL, FloatVector.broadcast(SPECIES, 0.382683432f)));
                x5 = x5.lanewise(VectorOperators.SUB, x7.lanewise(VectorOperators.MUL, FloatVector.broadcast(SPECIES, 0.198912367f)));
                x0 = xt.lanewise(VectorOperators.SUB, x6);
                xt = xt.lanewise(VectorOperators.ADD, x6);
                xt.lanewise(VectorOperators.ADD, x7).lanewise(VectorOperators.MUL, FloatVector.broadcast(SPECIES, 0.50979561f)).intoArray(t, (r + 1) * 4);
                x4.lanewise(VectorOperators.ADD, x3).lanewise(VectorOperators.MUL, FloatVector.broadcast(SPECIES, 0.54119611f)).intoArray(t, (r + 2) * 4);
                x0.lanewise(VectorOperators.SUB, x5).lanewise(VectorOperators.MUL, FloatVector.broadcast(SPECIES, 0.60134488f)).intoArray(t, (r + 3) * 4);
                x0.lanewise(VectorOperators.ADD, x5).lanewise(VectorOperators.MUL, FloatVector.broadcast(SPECIES, 0.89997619f)).intoArray(t, (r + 5) * 4);
                x4.lanewise(VectorOperators.SUB, x3).lanewise(VectorOperators.MUL, FloatVector.broadcast(SPECIES, 1.30656302f)).intoArray(t, (r + 6) * 4);
                xt.lanewise(VectorOperators.SUB, x7).lanewise(VectorOperators.MUL, FloatVector.broadcast(SPECIES, 2.56291556f)).intoArray(t, (r + 7) * 4);
            }
            for (var i = 0; i < 7; i++, y += 4 * 18) {
                var t3a = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, t, (24 + i) * 4));
                var t3b = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, t, (24 + i + 1) * 4));
                var s = t3a.lanewise(VectorOperators.ADD, t3b);
                FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, t, i * 4)).intoArray(grbuf, y);
                FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, t, (16 + i) * 4)).lanewise(VectorOperators.ADD, s).intoArray(grbuf, y + 18);
                FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, t, (8 + i) * 4)).lanewise(VectorOperators.ADD, FloatVector.fromArray(SPECIES, t, (8 + i + 1) * 4))
                        .intoArray(grbuf, y + 36);
                FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, t, (16 + i + 1) * 4)).lanewise(VectorOperators.ADD, s).intoArray(grbuf, y + 54);
            }
            FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, t, 7 * 4)).intoArray(grbuf, y);
            FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, t, (16 + 7) * 4)).lanewise(VectorOperators.ADD, FloatVector.fromArray(SPECIES, t, (24 + 7) * 4))
                    .intoArray(grbuf, y + 18);
            FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, t, (8 + 7) * 4)).intoArray(grbuf, y + 36);
            FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, t, (24 + 7) * 4)).intoArray(grbuf, y + 54);
        }
        if (k < slots) {
            tail.dct(grbuf, base + k, slots - k);
        }
    }

    @Override
    public void imdct36(float[] grbuf, int base, float[] overlap, int ovl, float[] window, int bands) {
        var tw = Mp3Tables.TWIDDLE_36;
        var done = 0;
        if (Simd.WIDE && wide) {
            var lanes = laneRows;
            for (; done + 8 <= bands; done += 8, base += 8 * 18, ovl += 8 * 9) {
                for (var l = 0; l < 8; l++) {
                    var src = base + 18 * l;
                    for (var k = 0; k < 18; k++) {
                        lanes[k * 8 + l] = grbuf[src + k];
                    }
                    var osrc = ovl + 9 * l;
                    for (var k = 0; k < 9; k++) {
                        lanes[(18 + k) * 8 + l] = overlap[osrc + k];
                    }
                }
                imdctRowsLow(lanes);
                imdctRowsHigh(lanes);
                imdctOutputLow(lanes, tw, window);
                imdctOutputHigh(lanes, tw, window);
                for (var l = 0; l < 8; l++) {
                    var dst = base + 18 * l;
                    for (var k = 0; k < 18; k++) {
                        grbuf[dst + k] = lanes[k * 8 + l];
                    }
                    var odst = ovl + 9 * l;
                    for (var k = 0; k < 9; k++) {
                        overlap[odst + k] = lanes[(18 + k) * 8 + l];
                    }
                }
            }
        }
        if (done < bands) {
            tail.imdct36(grbuf, base, overlap, ovl, window, bands - done);
        }
    }

    /**
     * Computes the first nine 9-point DCT outputs of eight subbands from their spectra.
     *
     * @param lanes the transposed rows
     */
    private static void imdctRowsLow(float[] lanes) {
        var c0 = FloatVector.broadcast(WIDE, 0.5f);
        var c1 = FloatVector.broadcast(WIDE, 0.93969262f);
        var c2 = FloatVector.broadcast(WIDE, 0.76604444f);
        var c3 = FloatVector.broadcast(WIDE, 0.17364818f);
        var c4 = FloatVector.broadcast(WIDE, 0.86602540f);
        var c5 = FloatVector.broadcast(WIDE, 0.98480775f);
        var c6 = FloatVector.broadcast(WIDE, 0.34202014f);
        var c7 = FloatVector.broadcast(WIDE, 0.64278761f);
        var g0 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 0));
        var g1 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 8));
        var g2 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 16));
        var g3 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 24));
        var g4 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 32));
        var g5 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 40));
        var g6 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 48));
        var g7 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 56));
        var g8 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 64));
        var g9 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 72));
        var g10 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 80));
        var g11 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 88));
        var g12 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 96));
        var g13 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 104));
        var g14 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 112));
        var g15 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 120));
        var g16 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 128));
        var v1 = g0.lanewise(VectorOperators.NEG);
        var v3 = g1.lanewise(VectorOperators.ADD, g2);
        var v5 = g3.lanewise(VectorOperators.ADD, g4);
        var v6 = v5.lanewise(VectorOperators.NEG);
        var v8 = g5.lanewise(VectorOperators.ADD, g6);
        var v10 = g7.lanewise(VectorOperators.ADD, g8);
        var v11 = v10.lanewise(VectorOperators.NEG);
        var v13 = g9.lanewise(VectorOperators.ADD, g10);
        var v15 = g11.lanewise(VectorOperators.ADD, g12);
        var v16 = v15.lanewise(VectorOperators.NEG);
        var v18 = g13.lanewise(VectorOperators.ADD, g14);
        var v20 = g15.lanewise(VectorOperators.ADD, g16);
        var v21 = v20.lanewise(VectorOperators.NEG);
        var v22 = v16.lanewise(VectorOperators.MUL, c0);
        var v23 = v1.lanewise(VectorOperators.ADD, v22);
        var v24 = v1.lanewise(VectorOperators.SUB, v16);
        var v25 = v11.lanewise(VectorOperators.ADD, v6);
        var v26 = v25.lanewise(VectorOperators.MUL, c1);
        var v27 = v21.lanewise(VectorOperators.ADD, v6);
        var v28 = v27.lanewise(VectorOperators.MUL, c2);
        var v29 = v11.lanewise(VectorOperators.SUB, v21);
        var v30 = v29.lanewise(VectorOperators.MUL, c3);
        var v31 = v21.lanewise(VectorOperators.SUB, v6);
        var v32 = v11.lanewise(VectorOperators.ADD, v31);
        var v33 = v32.lanewise(VectorOperators.MUL, c0);
        var v34 = v24.lanewise(VectorOperators.SUB, v33);
        var v35 = v32.lanewise(VectorOperators.ADD, v24);
        var v36 = v23.lanewise(VectorOperators.SUB, v28);
        var v37 = v36.lanewise(VectorOperators.ADD, v30);
        var v38 = v23.lanewise(VectorOperators.SUB, v26);
        var v39 = v38.lanewise(VectorOperators.ADD, v28);
        var v40 = v23.lanewise(VectorOperators.ADD, v26);
        var v41 = v40.lanewise(VectorOperators.SUB, v30);
        var v42 = v8.lanewise(VectorOperators.MUL, c4);
        var v43 = v13.lanewise(VectorOperators.ADD, v3);
        var v44 = v43.lanewise(VectorOperators.MUL, c5);
        var v45 = v13.lanewise(VectorOperators.SUB, v18);
        var v46 = v45.lanewise(VectorOperators.MUL, c6);
        var v47 = v3.lanewise(VectorOperators.ADD, v18);
        var v48 = v47.lanewise(VectorOperators.MUL, c7);
        var v49 = v3.lanewise(VectorOperators.SUB, v13);
        var v50 = v49.lanewise(VectorOperators.SUB, v18);
        var v51 = v50.lanewise(VectorOperators.MUL, c4);
        var v52 = v44.lanewise(VectorOperators.SUB, v42);
        var v53 = v52.lanewise(VectorOperators.SUB, v48);
        var v54 = v46.lanewise(VectorOperators.SUB, v42);
        var v55 = v54.lanewise(VectorOperators.SUB, v44);
        var v56 = v46.lanewise(VectorOperators.ADD, v42);
        var v57 = v56.lanewise(VectorOperators.SUB, v48);
        var v58 = v41.lanewise(VectorOperators.SUB, v55);
        var v59 = v34.lanewise(VectorOperators.ADD, v51);
        var v60 = v39.lanewise(VectorOperators.SUB, v57);
        var v61 = v37.lanewise(VectorOperators.ADD, v53);
        var v62 = v37.lanewise(VectorOperators.SUB, v53);
        var v63 = v39.lanewise(VectorOperators.ADD, v57);
        var v64 = v34.lanewise(VectorOperators.SUB, v51);
        var v65 = v41.lanewise(VectorOperators.ADD, v55);
        v58.intoArray(lanes, 216);
        v59.intoArray(lanes, 224);
        v60.intoArray(lanes, 232);
        v61.intoArray(lanes, 240);
        v35.intoArray(lanes, 248);
        v62.intoArray(lanes, 256);
        v63.intoArray(lanes, 264);
        v64.intoArray(lanes, 272);
        v65.intoArray(lanes, 280);
    }

    /**
     * Computes the last nine 9-point DCT outputs of eight subbands from their spectra, with the odd outputs negated.
     *
     * @param lanes the transposed rows
     */
    private static void imdctRowsHigh(float[] lanes) {
        var c0 = FloatVector.broadcast(WIDE, 0.5f);
        var c1 = FloatVector.broadcast(WIDE, 0.93969262f);
        var c2 = FloatVector.broadcast(WIDE, 0.76604444f);
        var c3 = FloatVector.broadcast(WIDE, 0.17364818f);
        var c4 = FloatVector.broadcast(WIDE, 0.86602540f);
        var c5 = FloatVector.broadcast(WIDE, 0.98480775f);
        var c6 = FloatVector.broadcast(WIDE, 0.34202014f);
        var c7 = FloatVector.broadcast(WIDE, 0.64278761f);
        var g1 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 8));
        var g2 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 16));
        var g3 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 24));
        var g4 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 32));
        var g5 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 40));
        var g6 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 48));
        var g7 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 56));
        var g8 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 64));
        var g9 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 72));
        var g10 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 80));
        var g11 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 88));
        var g12 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 96));
        var g13 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 104));
        var g14 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 112));
        var g15 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 120));
        var g16 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 128));
        var g17 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 136));
        var v2 = g1.lanewise(VectorOperators.SUB, g2);
        var v4 = g4.lanewise(VectorOperators.SUB, g3);
        var v7 = g5.lanewise(VectorOperators.SUB, g6);
        var v9 = g8.lanewise(VectorOperators.SUB, g7);
        var v12 = g9.lanewise(VectorOperators.SUB, g10);
        var v14 = g12.lanewise(VectorOperators.SUB, g11);
        var v17 = g13.lanewise(VectorOperators.SUB, g14);
        var v19 = g16.lanewise(VectorOperators.SUB, g15);
        var v22 = v7.lanewise(VectorOperators.MUL, c0);
        var v23 = g17.lanewise(VectorOperators.ADD, v22);
        var v24 = g17.lanewise(VectorOperators.SUB, v7);
        var v25 = v12.lanewise(VectorOperators.ADD, v17);
        var v26 = v25.lanewise(VectorOperators.MUL, c1);
        var v27 = v2.lanewise(VectorOperators.ADD, v17);
        var v28 = v27.lanewise(VectorOperators.MUL, c2);
        var v29 = v12.lanewise(VectorOperators.SUB, v2);
        var v30 = v29.lanewise(VectorOperators.MUL, c3);
        var v31 = v2.lanewise(VectorOperators.SUB, v17);
        var v32 = v12.lanewise(VectorOperators.ADD, v31);
        var v33 = v32.lanewise(VectorOperators.MUL, c0);
        var v34 = v24.lanewise(VectorOperators.SUB, v33);
        var v35 = v32.lanewise(VectorOperators.ADD, v24);
        var v36 = v23.lanewise(VectorOperators.SUB, v28);
        var v37 = v36.lanewise(VectorOperators.ADD, v30);
        var v38 = v23.lanewise(VectorOperators.SUB, v26);
        var v39 = v38.lanewise(VectorOperators.ADD, v28);
        var v40 = v23.lanewise(VectorOperators.ADD, v26);
        var v41 = v40.lanewise(VectorOperators.SUB, v30);
        var v42 = v14.lanewise(VectorOperators.MUL, c4);
        var v43 = v9.lanewise(VectorOperators.ADD, v19);
        var v44 = v43.lanewise(VectorOperators.MUL, c5);
        var v45 = v9.lanewise(VectorOperators.SUB, v4);
        var v46 = v45.lanewise(VectorOperators.MUL, c6);
        var v47 = v19.lanewise(VectorOperators.ADD, v4);
        var v48 = v47.lanewise(VectorOperators.MUL, c7);
        var v49 = v19.lanewise(VectorOperators.SUB, v9);
        var v50 = v49.lanewise(VectorOperators.SUB, v4);
        var v51 = v50.lanewise(VectorOperators.MUL, c4);
        var v52 = v44.lanewise(VectorOperators.SUB, v42);
        var v53 = v52.lanewise(VectorOperators.SUB, v48);
        var v54 = v46.lanewise(VectorOperators.SUB, v42);
        var v55 = v54.lanewise(VectorOperators.SUB, v44);
        var v56 = v46.lanewise(VectorOperators.ADD, v42);
        var v57 = v56.lanewise(VectorOperators.SUB, v48);
        var v58 = v41.lanewise(VectorOperators.SUB, v55);
        var v59 = v34.lanewise(VectorOperators.ADD, v51);
        var v60 = v39.lanewise(VectorOperators.SUB, v57);
        var v61 = v37.lanewise(VectorOperators.ADD, v53);
        var v62 = v37.lanewise(VectorOperators.SUB, v53);
        var v63 = v39.lanewise(VectorOperators.ADD, v57);
        var v64 = v34.lanewise(VectorOperators.SUB, v51);
        var v65 = v41.lanewise(VectorOperators.ADD, v55);
        var v66 = v59.lanewise(VectorOperators.NEG);
        var v67 = v61.lanewise(VectorOperators.NEG);
        var v68 = v62.lanewise(VectorOperators.NEG);
        var v69 = v64.lanewise(VectorOperators.NEG);
        v58.intoArray(lanes, 288);
        v66.intoArray(lanes, 296);
        v60.intoArray(lanes, 304);
        v67.intoArray(lanes, 312);
        v35.intoArray(lanes, 320);
        v68.intoArray(lanes, 328);
        v63.intoArray(lanes, 336);
        v69.intoArray(lanes, 344);
        v65.intoArray(lanes, 352);
    }

    /**
     * Windows the first five DCT outputs of eight subbands and overlaps them with the previous granule.
     *
     * @param lanes the transposed rows
     * @param tw the twiddle factors
     * @param window the IMDCT window, split as in {@link Mp3Tables#MDCT_WINDOW}
     */
    private static void imdctOutputLow(float[] lanes, float[] tw, float[] window) {
        var co0 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 216));
        var si0 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 288));
        var p0 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 144));
        var a0 = FloatVector.broadcast(WIDE, tw[0]);
        var b0 = FloatVector.broadcast(WIDE, tw[9]);
        var wa0 = FloatVector.broadcast(WIDE, window[0]);
        var wb0 = FloatVector.broadcast(WIDE, window[9]);
        var m0 = co0.lanewise(VectorOperators.MUL, b0).lanewise(VectorOperators.ADD, si0.lanewise(VectorOperators.MUL, a0));
        co0.lanewise(VectorOperators.MUL, a0).lanewise(VectorOperators.SUB, si0.lanewise(VectorOperators.MUL, b0)).intoArray(lanes, 144);
        p0.lanewise(VectorOperators.MUL, wa0).lanewise(VectorOperators.SUB, m0.lanewise(VectorOperators.MUL, wb0)).intoArray(lanes, 0);
        p0.lanewise(VectorOperators.MUL, wb0).lanewise(VectorOperators.ADD, m0.lanewise(VectorOperators.MUL, wa0)).intoArray(lanes, 136);
        var co1 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 224));
        var si1 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 296));
        var p1 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 152));
        var a1 = FloatVector.broadcast(WIDE, tw[1]);
        var b1 = FloatVector.broadcast(WIDE, tw[10]);
        var wa1 = FloatVector.broadcast(WIDE, window[1]);
        var wb1 = FloatVector.broadcast(WIDE, window[10]);
        var m1 = co1.lanewise(VectorOperators.MUL, b1).lanewise(VectorOperators.ADD, si1.lanewise(VectorOperators.MUL, a1));
        co1.lanewise(VectorOperators.MUL, a1).lanewise(VectorOperators.SUB, si1.lanewise(VectorOperators.MUL, b1)).intoArray(lanes, 152);
        p1.lanewise(VectorOperators.MUL, wa1).lanewise(VectorOperators.SUB, m1.lanewise(VectorOperators.MUL, wb1)).intoArray(lanes, 8);
        p1.lanewise(VectorOperators.MUL, wb1).lanewise(VectorOperators.ADD, m1.lanewise(VectorOperators.MUL, wa1)).intoArray(lanes, 128);
        var co2 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 232));
        var si2 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 304));
        var p2 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 160));
        var a2 = FloatVector.broadcast(WIDE, tw[2]);
        var b2 = FloatVector.broadcast(WIDE, tw[11]);
        var wa2 = FloatVector.broadcast(WIDE, window[2]);
        var wb2 = FloatVector.broadcast(WIDE, window[11]);
        var m2 = co2.lanewise(VectorOperators.MUL, b2).lanewise(VectorOperators.ADD, si2.lanewise(VectorOperators.MUL, a2));
        co2.lanewise(VectorOperators.MUL, a2).lanewise(VectorOperators.SUB, si2.lanewise(VectorOperators.MUL, b2)).intoArray(lanes, 160);
        p2.lanewise(VectorOperators.MUL, wa2).lanewise(VectorOperators.SUB, m2.lanewise(VectorOperators.MUL, wb2)).intoArray(lanes, 16);
        p2.lanewise(VectorOperators.MUL, wb2).lanewise(VectorOperators.ADD, m2.lanewise(VectorOperators.MUL, wa2)).intoArray(lanes, 120);
        var co3 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 240));
        var si3 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 312));
        var p3 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 168));
        var a3 = FloatVector.broadcast(WIDE, tw[3]);
        var b3 = FloatVector.broadcast(WIDE, tw[12]);
        var wa3 = FloatVector.broadcast(WIDE, window[3]);
        var wb3 = FloatVector.broadcast(WIDE, window[12]);
        var m3 = co3.lanewise(VectorOperators.MUL, b3).lanewise(VectorOperators.ADD, si3.lanewise(VectorOperators.MUL, a3));
        co3.lanewise(VectorOperators.MUL, a3).lanewise(VectorOperators.SUB, si3.lanewise(VectorOperators.MUL, b3)).intoArray(lanes, 168);
        p3.lanewise(VectorOperators.MUL, wa3).lanewise(VectorOperators.SUB, m3.lanewise(VectorOperators.MUL, wb3)).intoArray(lanes, 24);
        p3.lanewise(VectorOperators.MUL, wb3).lanewise(VectorOperators.ADD, m3.lanewise(VectorOperators.MUL, wa3)).intoArray(lanes, 112);
        var co4 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 248));
        var si4 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 320));
        var p4 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 176));
        var a4 = FloatVector.broadcast(WIDE, tw[4]);
        var b4 = FloatVector.broadcast(WIDE, tw[13]);
        var wa4 = FloatVector.broadcast(WIDE, window[4]);
        var wb4 = FloatVector.broadcast(WIDE, window[13]);
        var m4 = co4.lanewise(VectorOperators.MUL, b4).lanewise(VectorOperators.ADD, si4.lanewise(VectorOperators.MUL, a4));
        co4.lanewise(VectorOperators.MUL, a4).lanewise(VectorOperators.SUB, si4.lanewise(VectorOperators.MUL, b4)).intoArray(lanes, 176);
        p4.lanewise(VectorOperators.MUL, wa4).lanewise(VectorOperators.SUB, m4.lanewise(VectorOperators.MUL, wb4)).intoArray(lanes, 32);
        p4.lanewise(VectorOperators.MUL, wb4).lanewise(VectorOperators.ADD, m4.lanewise(VectorOperators.MUL, wa4)).intoArray(lanes, 104);
    }

    /**
     * Windows the last four DCT outputs of eight subbands and overlaps them with the previous granule.
     *
     * @param lanes the transposed rows
     * @param tw the twiddle factors
     * @param window the IMDCT window, split as in {@link Mp3Tables#MDCT_WINDOW}
     */
    private static void imdctOutputHigh(float[] lanes, float[] tw, float[] window) {
        var co5 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 256));
        var si5 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 328));
        var p5 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 184));
        var a5 = FloatVector.broadcast(WIDE, tw[5]);
        var b5 = FloatVector.broadcast(WIDE, tw[14]);
        var wa5 = FloatVector.broadcast(WIDE, window[5]);
        var wb5 = FloatVector.broadcast(WIDE, window[14]);
        var m5 = co5.lanewise(VectorOperators.MUL, b5).lanewise(VectorOperators.ADD, si5.lanewise(VectorOperators.MUL, a5));
        co5.lanewise(VectorOperators.MUL, a5).lanewise(VectorOperators.SUB, si5.lanewise(VectorOperators.MUL, b5)).intoArray(lanes, 184);
        p5.lanewise(VectorOperators.MUL, wa5).lanewise(VectorOperators.SUB, m5.lanewise(VectorOperators.MUL, wb5)).intoArray(lanes, 40);
        p5.lanewise(VectorOperators.MUL, wb5).lanewise(VectorOperators.ADD, m5.lanewise(VectorOperators.MUL, wa5)).intoArray(lanes, 96);
        var co6 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 264));
        var si6 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 336));
        var p6 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 192));
        var a6 = FloatVector.broadcast(WIDE, tw[6]);
        var b6 = FloatVector.broadcast(WIDE, tw[15]);
        var wa6 = FloatVector.broadcast(WIDE, window[6]);
        var wb6 = FloatVector.broadcast(WIDE, window[15]);
        var m6 = co6.lanewise(VectorOperators.MUL, b6).lanewise(VectorOperators.ADD, si6.lanewise(VectorOperators.MUL, a6));
        co6.lanewise(VectorOperators.MUL, a6).lanewise(VectorOperators.SUB, si6.lanewise(VectorOperators.MUL, b6)).intoArray(lanes, 192);
        p6.lanewise(VectorOperators.MUL, wa6).lanewise(VectorOperators.SUB, m6.lanewise(VectorOperators.MUL, wb6)).intoArray(lanes, 48);
        p6.lanewise(VectorOperators.MUL, wb6).lanewise(VectorOperators.ADD, m6.lanewise(VectorOperators.MUL, wa6)).intoArray(lanes, 88);
        var co7 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 272));
        var si7 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 344));
        var p7 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 200));
        var a7 = FloatVector.broadcast(WIDE, tw[7]);
        var b7 = FloatVector.broadcast(WIDE, tw[16]);
        var wa7 = FloatVector.broadcast(WIDE, window[7]);
        var wb7 = FloatVector.broadcast(WIDE, window[16]);
        var m7 = co7.lanewise(VectorOperators.MUL, b7).lanewise(VectorOperators.ADD, si7.lanewise(VectorOperators.MUL, a7));
        co7.lanewise(VectorOperators.MUL, a7).lanewise(VectorOperators.SUB, si7.lanewise(VectorOperators.MUL, b7)).intoArray(lanes, 200);
        p7.lanewise(VectorOperators.MUL, wa7).lanewise(VectorOperators.SUB, m7.lanewise(VectorOperators.MUL, wb7)).intoArray(lanes, 56);
        p7.lanewise(VectorOperators.MUL, wb7).lanewise(VectorOperators.ADD, m7.lanewise(VectorOperators.MUL, wa7)).intoArray(lanes, 80);
        var co8 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 280));
        var si8 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 352));
        var p8 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lanes, 208));
        var a8 = FloatVector.broadcast(WIDE, tw[8]);
        var b8 = FloatVector.broadcast(WIDE, tw[17]);
        var wa8 = FloatVector.broadcast(WIDE, window[8]);
        var wb8 = FloatVector.broadcast(WIDE, window[17]);
        var m8 = co8.lanewise(VectorOperators.MUL, b8).lanewise(VectorOperators.ADD, si8.lanewise(VectorOperators.MUL, a8));
        co8.lanewise(VectorOperators.MUL, a8).lanewise(VectorOperators.SUB, si8.lanewise(VectorOperators.MUL, b8)).intoArray(lanes, 208);
        p8.lanewise(VectorOperators.MUL, wa8).lanewise(VectorOperators.SUB, m8.lanewise(VectorOperators.MUL, wb8)).intoArray(lanes, 64);
        p8.lanewise(VectorOperators.MUL, wb8).lanewise(VectorOperators.ADD, m8.lanewise(VectorOperators.MUL, wa8)).intoArray(lanes, 72);
    }

    @Override
    public void window(float[] grbuf, int left, int right, float[] outLeft, float[] outRight, int outOffset,
                       float[] lins, int linsOffset) {
        var z = linsOffset + 15 * 64;
        lins[z + 4 * 15] = grbuf[left + 18 * 16];
        lins[z + 4 * 15 + 1] = grbuf[right + 18 * 16];
        lins[z + 4 * 15 + 2] = grbuf[left];
        lins[z + 4 * 15 + 3] = grbuf[right];
        lins[z + 4 * 31] = grbuf[left + 1 + 18 * 16];
        lins[z + 4 * 31 + 1] = grbuf[right + 1 + 18 * 16];
        lins[z + 4 * 31 + 2] = grbuf[left + 1];
        lins[z + 4 * 31 + 3] = grbuf[right + 1];
        ScalarSynthesisKernel.pair(outRight, outOffset, lins, linsOffset + 4 * 15 + 1);
        ScalarSynthesisKernel.pair(outRight, outOffset + 32, lins, linsOffset + 4 * 15 + 64 + 1);
        ScalarSynthesisKernel.pair(outLeft, outOffset, lins, linsOffset + 4 * 15);
        ScalarSynthesisKernel.pair(outLeft, outOffset + 32, lins, linsOffset + 4 * 15 + 64);
        if (Simd.WIDE && wide) {
            windowWide(grbuf, left, right, outLeft, outRight, outOffset, lins, z);
            return;
        }
        var w = Mp3Tables.SYNTH_WINDOW;
        var r = results;
        var wi = 0;
        for (var i = 14; i >= 0; i--) {
            store(grbuf, left, right, lins, z, i);
            var zi = z + 4 * i;
            accumulateNarrow(lins, zi, w, wi, r);
            wi += 16;
            scatter(r, 0, 4, i, outLeft, outRight, outOffset);
        }
    }

    /**
     * Runs the window loop two output positions per pass with eight-lane vectors.
     *
     * <p>The history writes of every position are hoisted before the arithmetic: no position reads
     * a history value written for another position, so the results equal the interleaved order of
     * the scalar kernel.
     *
     * @param grbuf the granule buffer
     * @param left the index of the left channel's first time slot
     * @param right the index of the right channel's first time slot
     * @param outLeft the left output plane
     * @param outRight the right output plane
     * @param outOffset the index of the first output sample
     * @param lins the filterbank history
     * @param z the index in {@code lins} of the newest history slot
     */
    private void windowWide(float[] grbuf, int left, int right, float[] outLeft, float[] outRight, int outOffset,
                            float[] lins, int z) {
        for (var i = 14; i >= 0; i--) {
            store(grbuf, left, right, lins, z, i);
        }
        var r = results;
        var pw = PAIR_WEIGHTS;
        for (var p = 0; p < 7; p++) {
            var low = 13 - 2 * p;
            var zi = z + 4 * low;
            var wb = p * 8 * 16;
            accumulateWide(lins, zi, pw, wb, r);
            scatter(r, 0, 8, low, outLeft, outRight, outOffset);
            scatter(r, 4, 12, low + 1, outLeft, outRight, outOffset);
        }
        var w = Mp3Tables.SYNTH_WINDOW;
        var wi = 14 * 16;
        accumulateNarrow(lins, z, w, wi, r);
        scatter(r, 0, 4, 0, outLeft, outRight, outOffset);
    }

    /**
     * Computes the 32 windowed sums of one 128-bit column group of time slot position {@code zi}:
     * the four even-indexed outputs into {@code r[0..3]} and the four odd-indexed ones into
     * {@code r[4..7]}, scaled to the output range.
     *
     * @implNote This implementation unrolls the eight window taps into straight-line code, so no
     * vector crosses a loop back edge or a control-flow merge (the alternating sign of the odd taps
     * is fixed per tap), and it stays above the JIT compiler's inlining size limit so it is compiled
     * on its own; written as a loop with a conditional accumulation, it was left on the allocating
     * Java fallback in most multi-threaded JVM runs.
     *
     * @param lins the filterbank history
     * @param zi the index in {@code lins} of the first sample of the position
     * @param w the synthesis window
     * @param wi the index in {@code w} of the position's first weight
     * @param r receives the eight results
     */
    private static void accumulateNarrow(float[] lins, int zi, float[] w, int wi, float[] r) {
        var w0 = FloatVector.broadcast(SPECIES, w[wi]);
        var w1 = FloatVector.broadcast(SPECIES, w[wi + 1]);
        var vz = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, lins, zi));
        var vy = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, lins, zi - 15 * 64));
        var b = vz.lanewise(VectorOperators.MUL, w1).lanewise(VectorOperators.ADD, vy.lanewise(VectorOperators.MUL, w0));
        var a = vz.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.SUB, vy.lanewise(VectorOperators.MUL, w1));
        w0 = FloatVector.broadcast(SPECIES, w[wi + 2]);
        w1 = FloatVector.broadcast(SPECIES, w[wi + 3]);
        vz = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, lins, zi - 64));
        vy = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, lins, zi - 896));
        b = b.lanewise(VectorOperators.ADD, vz.lanewise(VectorOperators.MUL, w1).lanewise(VectorOperators.ADD, vy.lanewise(VectorOperators.MUL, w0)));
        a = a.lanewise(VectorOperators.ADD, vy.lanewise(VectorOperators.MUL, w1).lanewise(VectorOperators.SUB, vz.lanewise(VectorOperators.MUL, w0)));
        w0 = FloatVector.broadcast(SPECIES, w[wi + 4]);
        w1 = FloatVector.broadcast(SPECIES, w[wi + 5]);
        vz = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, lins, zi - 128));
        vy = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, lins, zi - 832));
        b = b.lanewise(VectorOperators.ADD, vz.lanewise(VectorOperators.MUL, w1).lanewise(VectorOperators.ADD, vy.lanewise(VectorOperators.MUL, w0)));
        a = a.lanewise(VectorOperators.ADD, vz.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.SUB, vy.lanewise(VectorOperators.MUL, w1)));
        w0 = FloatVector.broadcast(SPECIES, w[wi + 6]);
        w1 = FloatVector.broadcast(SPECIES, w[wi + 7]);
        vz = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, lins, zi - 192));
        vy = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, lins, zi - 768));
        b = b.lanewise(VectorOperators.ADD, vz.lanewise(VectorOperators.MUL, w1).lanewise(VectorOperators.ADD, vy.lanewise(VectorOperators.MUL, w0)));
        a = a.lanewise(VectorOperators.ADD, vy.lanewise(VectorOperators.MUL, w1).lanewise(VectorOperators.SUB, vz.lanewise(VectorOperators.MUL, w0)));
        w0 = FloatVector.broadcast(SPECIES, w[wi + 8]);
        w1 = FloatVector.broadcast(SPECIES, w[wi + 9]);
        vz = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, lins, zi - 256));
        vy = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, lins, zi - 704));
        b = b.lanewise(VectorOperators.ADD, vz.lanewise(VectorOperators.MUL, w1).lanewise(VectorOperators.ADD, vy.lanewise(VectorOperators.MUL, w0)));
        a = a.lanewise(VectorOperators.ADD, vz.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.SUB, vy.lanewise(VectorOperators.MUL, w1)));
        w0 = FloatVector.broadcast(SPECIES, w[wi + 10]);
        w1 = FloatVector.broadcast(SPECIES, w[wi + 11]);
        vz = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, lins, zi - 320));
        vy = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, lins, zi - 640));
        b = b.lanewise(VectorOperators.ADD, vz.lanewise(VectorOperators.MUL, w1).lanewise(VectorOperators.ADD, vy.lanewise(VectorOperators.MUL, w0)));
        a = a.lanewise(VectorOperators.ADD, vy.lanewise(VectorOperators.MUL, w1).lanewise(VectorOperators.SUB, vz.lanewise(VectorOperators.MUL, w0)));
        w0 = FloatVector.broadcast(SPECIES, w[wi + 12]);
        w1 = FloatVector.broadcast(SPECIES, w[wi + 13]);
        vz = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, lins, zi - 384));
        vy = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, lins, zi - 576));
        b = b.lanewise(VectorOperators.ADD, vz.lanewise(VectorOperators.MUL, w1).lanewise(VectorOperators.ADD, vy.lanewise(VectorOperators.MUL, w0)));
        a = a.lanewise(VectorOperators.ADD, vz.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.SUB, vy.lanewise(VectorOperators.MUL, w1)));
        w0 = FloatVector.broadcast(SPECIES, w[wi + 14]);
        w1 = FloatVector.broadcast(SPECIES, w[wi + 15]);
        vz = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, lins, zi - 448));
        vy = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, lins, zi - 512));
        b = b.lanewise(VectorOperators.ADD, vz.lanewise(VectorOperators.MUL, w1).lanewise(VectorOperators.ADD, vy.lanewise(VectorOperators.MUL, w0)));
        a = a.lanewise(VectorOperators.ADD, vy.lanewise(VectorOperators.MUL, w1).lanewise(VectorOperators.SUB, vz.lanewise(VectorOperators.MUL, w0)));
        var scale = FloatVector.broadcast(SPECIES, ScalarSynthesisKernel.OUTPUT_SCALE);
        a.lanewise(VectorOperators.MUL, scale).intoArray(r, 0);
        b.lanewise(VectorOperators.MUL, scale).intoArray(r, 4);
    }

    /**
     * Computes the windowed sums of two positions at once with 256-bit vectors, the pair weights
     * starting at {@code wb}: the even-indexed outputs into {@code r[0..7]} and the odd-indexed ones
     * into {@code r[8..15]}, scaled to the output range.
     *
     * @implNote This implementation unrolls the taps for the reasons given on
     * {@link #accumulateNarrow(float[], int, float[], int, float[])}.
     *
     * @param lins the filterbank history
     * @param zi the index in {@code lins} of the first sample of the lower position
     * @param pw the pair weights
     * @param wb the index in {@code pw} of the pair's first weight
     * @param r receives the sixteen results
     */
    private static void accumulateWide(float[] lins, int zi, float[] pw, int wb, float[] r) {
        var w0 = FloatVector.fromArray(WIDE, pw, wb);
        var w1 = FloatVector.fromArray(WIDE, pw, wb + 8);
        var vz = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lins, zi));
        var vy = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lins, zi - 15 * 64));
        var b = vz.lanewise(VectorOperators.MUL, w1).lanewise(VectorOperators.ADD, vy.lanewise(VectorOperators.MUL, w0));
        var a = vz.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.SUB, vy.lanewise(VectorOperators.MUL, w1));
        w0 = FloatVector.fromArray(WIDE, pw, wb + 16);
        w1 = FloatVector.fromArray(WIDE, pw, wb + 24);
        vz = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lins, zi - 64));
        vy = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lins, zi - 896));
        b = b.lanewise(VectorOperators.ADD, vz.lanewise(VectorOperators.MUL, w1).lanewise(VectorOperators.ADD, vy.lanewise(VectorOperators.MUL, w0)));
        a = a.lanewise(VectorOperators.ADD, vy.lanewise(VectorOperators.MUL, w1).lanewise(VectorOperators.SUB, vz.lanewise(VectorOperators.MUL, w0)));
        w0 = FloatVector.fromArray(WIDE, pw, wb + 32);
        w1 = FloatVector.fromArray(WIDE, pw, wb + 40);
        vz = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lins, zi - 128));
        vy = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lins, zi - 832));
        b = b.lanewise(VectorOperators.ADD, vz.lanewise(VectorOperators.MUL, w1).lanewise(VectorOperators.ADD, vy.lanewise(VectorOperators.MUL, w0)));
        a = a.lanewise(VectorOperators.ADD, vz.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.SUB, vy.lanewise(VectorOperators.MUL, w1)));
        w0 = FloatVector.fromArray(WIDE, pw, wb + 48);
        w1 = FloatVector.fromArray(WIDE, pw, wb + 56);
        vz = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lins, zi - 192));
        vy = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lins, zi - 768));
        b = b.lanewise(VectorOperators.ADD, vz.lanewise(VectorOperators.MUL, w1).lanewise(VectorOperators.ADD, vy.lanewise(VectorOperators.MUL, w0)));
        a = a.lanewise(VectorOperators.ADD, vy.lanewise(VectorOperators.MUL, w1).lanewise(VectorOperators.SUB, vz.lanewise(VectorOperators.MUL, w0)));
        w0 = FloatVector.fromArray(WIDE, pw, wb + 64);
        w1 = FloatVector.fromArray(WIDE, pw, wb + 72);
        vz = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lins, zi - 256));
        vy = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lins, zi - 704));
        b = b.lanewise(VectorOperators.ADD, vz.lanewise(VectorOperators.MUL, w1).lanewise(VectorOperators.ADD, vy.lanewise(VectorOperators.MUL, w0)));
        a = a.lanewise(VectorOperators.ADD, vz.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.SUB, vy.lanewise(VectorOperators.MUL, w1)));
        w0 = FloatVector.fromArray(WIDE, pw, wb + 80);
        w1 = FloatVector.fromArray(WIDE, pw, wb + 88);
        vz = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lins, zi - 320));
        vy = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lins, zi - 640));
        b = b.lanewise(VectorOperators.ADD, vz.lanewise(VectorOperators.MUL, w1).lanewise(VectorOperators.ADD, vy.lanewise(VectorOperators.MUL, w0)));
        a = a.lanewise(VectorOperators.ADD, vy.lanewise(VectorOperators.MUL, w1).lanewise(VectorOperators.SUB, vz.lanewise(VectorOperators.MUL, w0)));
        w0 = FloatVector.fromArray(WIDE, pw, wb + 96);
        w1 = FloatVector.fromArray(WIDE, pw, wb + 104);
        vz = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lins, zi - 384));
        vy = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lins, zi - 576));
        b = b.lanewise(VectorOperators.ADD, vz.lanewise(VectorOperators.MUL, w1).lanewise(VectorOperators.ADD, vy.lanewise(VectorOperators.MUL, w0)));
        a = a.lanewise(VectorOperators.ADD, vz.lanewise(VectorOperators.MUL, w0).lanewise(VectorOperators.SUB, vy.lanewise(VectorOperators.MUL, w1)));
        w0 = FloatVector.fromArray(WIDE, pw, wb + 112);
        w1 = FloatVector.fromArray(WIDE, pw, wb + 120);
        vz = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lins, zi - 448));
        vy = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, lins, zi - 512));
        b = b.lanewise(VectorOperators.ADD, vz.lanewise(VectorOperators.MUL, w1).lanewise(VectorOperators.ADD, vy.lanewise(VectorOperators.MUL, w0)));
        a = a.lanewise(VectorOperators.ADD, vy.lanewise(VectorOperators.MUL, w1).lanewise(VectorOperators.SUB, vz.lanewise(VectorOperators.MUL, w0)));
        var scale = FloatVector.broadcast(WIDE, ScalarSynthesisKernel.OUTPUT_SCALE);
        a.lanewise(VectorOperators.MUL, scale).intoArray(r, 0);
        b.lanewise(VectorOperators.MUL, scale).intoArray(r, 8);
    }

    /**
     * Writes the history values of one output position.
     *
     * @param grbuf the granule buffer
     * @param left the index of the left channel's first time slot
     * @param right the index of the right channel's first time slot
     * @param lins the filterbank history
     * @param z the index in {@code lins} of the newest history slot
     * @param i the output position, 0 to 14
     */
    private static void store(float[] grbuf, int left, int right, float[] lins, int z, int i) {
        var zi = z + 4 * i;
        lins[zi] = grbuf[left + 18 * (31 - i)];
        lins[zi + 1] = grbuf[right + 18 * (31 - i)];
        lins[zi + 2] = grbuf[left + 1 + 18 * (31 - i)];
        lins[zi + 3] = grbuf[right + 1 + 18 * (31 - i)];
        lins[zi + 64] = grbuf[left + 1 + 18 * (1 + i)];
        lins[zi + 64 + 1] = grbuf[right + 1 + 18 * (1 + i)];
        lins[zi - 64 + 2] = grbuf[left + 18 * (1 + i)];
        lins[zi - 64 + 3] = grbuf[right + 18 * (1 + i)];
    }

    /**
     * Writes the eight output samples of one output position, right channel first so that the
     * left value wins when both planes are the same array.
     *
     * @param r the results
     * @param a the index of the position's four {@code a} lanes
     * @param b the index of the position's four {@code b} lanes
     * @param i the output position
     * @param outLeft the left output plane
     * @param outRight the right output plane
     * @param outOffset the index of the first output sample
     */
    private static void scatter(float[] r, int a, int b, int i, float[] outLeft, float[] outRight, int outOffset) {
        outRight[outOffset + 15 - i] = r[a + 1];
        outRight[outOffset + 17 + i] = r[b + 1];
        outLeft[outOffset + 15 - i] = r[a];
        outLeft[outOffset + 17 + i] = r[b];
        outRight[outOffset + 47 - i] = r[a + 3];
        outRight[outOffset + 49 + i] = r[b + 3];
        outLeft[outOffset + 47 - i] = r[a + 2];
        outLeft[outOffset + 49 + i] = r[b + 2];
    }
}
