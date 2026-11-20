import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reproducer of a HotSpot defect seen on Windows runners with AVX-512: C2 code reloads a reference it spilled to
 * an upper-bank XMM register (xmm16 to xmm31) across the safepoint poll of a loop and finds it null.
 *
 * <p>Run with {@code java --add-modules jdk.incubator.vector Repro.java <variant> <seconds> <threads>}, where the
 * variant is {@code field} (the vector loop is followed by a call through an instance field, which C2 keeps live
 * across the loop) or {@code static} (the same loop followed by a static call, so no reference other than the
 * arguments the loop reads is live across it). Frequent safepoints make the defect likely:
 * {@code -XX:+UnlockDiagnosticVMOptions -XX:+SafepointALot -XX:GuaranteedSafepointInterval=1}. Every call's
 * output is compared with the scalar result, so a corrupted value that does not crash is reported too. Exits
 * with 0 when every call matched, 3 on a mismatch, and through the crash otherwise.
 */
public final class Repro {
    private static final VectorSpecies<Integer> I4 = IntVector.SPECIES_128;

    private static final VectorSpecies<Byte> BP4 = ByteVector.SPECIES_128;

    private static final VectorSpecies<Double> D4 = DoubleVector.SPECIES_PREFERRED.length() >= 4 ? DoubleVector.SPECIES_256 : null;

    private static final Class<IntVector> INT4_VECTOR = vectorClass(I4);

    private static final Class<ByteVector> PIXEL4_VECTOR = vectorClass(BP4);

    private static final Class<DoubleVector> DOUBLE_VECTOR = D4 == null ? null : vectorClass(D4);

    private static final IntVector ZERO4 = IntVector.zero(I4);

    private static final IntVector MAX_SAMPLE4 = IntVector.broadcast(I4, 255);

    private static final double QUOTIENT_MARGIN = 0x1p-40;

    private final Tail tail = new Tail();

    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    /**
     * The scalar tail, called through an instance field like the kernel it was reduced from.
     */
    static final class Tail {
        void range(int[] src, int sOff, int width, int scale, byte[] dst, int dOff, int from, int to) {
            rangeStatic(src, sOff, width, scale, dst, dOff, from, to);
        }

        static void rangeStatic(int[] src, int sOff, int width, int scale, byte[] dst, int dOff, int from, int to) {
            var alphaDivisor = 510L * scale;
            for (var x = from; x < to; x++) {
                var out = dOff + 4 * x;
                long a = src[sOff + 3 * width + x];
                if (a <= 0) {
                    dst[out] = 0;
                    dst[out + 1] = 0;
                    dst[out + 2] = 0;
                    dst[out + 3] = 0;
                    continue;
                }
                dst[out] = (byte) divide(src[sOff + x], a);
                dst[out + 1] = (byte) divide(src[sOff + width + x], a);
                dst[out + 2] = (byte) divide(src[sOff + 2 * width + x], a);
                dst[out + 3] = (byte) Math.min(255, (2 * a + 255L * scale) / alphaDivisor);
            }
        }

        private static int divide(int premultiplied, long alpha) {
            var quotient = Math.floorDiv(510L * premultiplied + alpha, 2 * alpha);
            return (int) Math.clamp(quotient, 0, 255);
        }
    }

    /**
     * The vector loop followed by the tail through {@link #tail}.
     */
    void viaField(int[] src, int sOff, int width, int scale, byte[] dst, int dOff) {
        var x = 0;
        if (D4 != null) {
            var alphaFactor = DOUBLE_VECTOR.cast(DoubleVector.broadcast(D4, 1.0 / (510.0 * scale)));
            var alphaBias = DOUBLE_VECTOR.cast(DoubleVector.broadcast(D4, 255.0 * scale));
            var twice = DOUBLE_VECTOR.cast(DoubleVector.broadcast(D4, 2.0));
            var colorScale = DOUBLE_VECTOR.cast(DoubleVector.broadcast(D4, 510.0));
            var half = DOUBLE_VECTOR.cast(DoubleVector.broadcast(D4, 0.5));
            var margin = DOUBLE_VECTOR.cast(DoubleVector.broadcast(D4, QUOTIENT_MARGIN));
            var low = DOUBLE_VECTOR.cast(DoubleVector.broadcast(D4, -1.0));
            var high = DOUBLE_VECTOR.cast(DoubleVector.broadcast(D4, 256.0));
            var zero = DOUBLE_VECTOR.cast(DoubleVector.zero(D4));
            for (; x + 4 <= width; x += 4) {
                var a = DOUBLE_VECTOR.cast(INT4_VECTOR.cast(IntVector.fromArray(I4, src, sOff + 3 * width + x))
                        .convertShape(VectorOperators.I2D, D4, 0));
                var red = DOUBLE_VECTOR.cast(INT4_VECTOR.cast(IntVector.fromArray(I4, src, sOff + x))
                        .convertShape(VectorOperators.I2D, D4, 0));
                var green = DOUBLE_VECTOR.cast(INT4_VECTOR.cast(IntVector.fromArray(I4, src, sOff + width + x))
                        .convertShape(VectorOperators.I2D, D4, 0));
                var blue = DOUBLE_VECTOR.cast(INT4_VECTOR.cast(IntVector.fromArray(I4, src, sOff + 2 * width + x))
                        .convertShape(VectorOperators.I2D, D4, 0));
                var reciprocal = DOUBLE_VECTOR.cast(half).lanewise(VectorOperators.DIV, a);
                var r = INT4_VECTOR.cast(red.lanewise(VectorOperators.MUL, colorScale).lanewise(VectorOperators.ADD, a)
                                .lanewise(VectorOperators.MUL, reciprocal).lanewise(VectorOperators.ADD, margin)
                                .lanewise(VectorOperators.MAX, low).lanewise(VectorOperators.MIN, high)
                                .convertShape(VectorOperators.D2I, I4, 0))
                        .lanewise(VectorOperators.MAX, ZERO4).lanewise(VectorOperators.MIN, MAX_SAMPLE4);
                var g = INT4_VECTOR.cast(green.lanewise(VectorOperators.MUL, colorScale)
                        .lanewise(VectorOperators.ADD, a)
                                .lanewise(VectorOperators.MUL, reciprocal).lanewise(VectorOperators.ADD, margin)
                                .lanewise(VectorOperators.MAX, low).lanewise(VectorOperators.MIN, high)
                                .convertShape(VectorOperators.D2I, I4, 0))
                        .lanewise(VectorOperators.MAX, ZERO4).lanewise(VectorOperators.MIN, MAX_SAMPLE4);
                var b = INT4_VECTOR.cast(blue.lanewise(VectorOperators.MUL, colorScale)
                        .lanewise(VectorOperators.ADD, a)
                                .lanewise(VectorOperators.MUL, reciprocal).lanewise(VectorOperators.ADD, margin)
                                .lanewise(VectorOperators.MAX, low).lanewise(VectorOperators.MIN, high)
                                .convertShape(VectorOperators.D2I, I4, 0))
                        .lanewise(VectorOperators.MAX, ZERO4).lanewise(VectorOperators.MIN, MAX_SAMPLE4);
                var alpha = INT4_VECTOR.cast(a.lanewise(VectorOperators.MUL, twice)
                        .lanewise(VectorOperators.ADD, alphaBias)
                                .lanewise(VectorOperators.MUL, alphaFactor).lanewise(VectorOperators.ADD, margin)
                                .convertShape(VectorOperators.D2I, I4, 0))
                        .lanewise(VectorOperators.MIN, MAX_SAMPLE4);
                var transparent = a.compare(VectorOperators.LE, zero).cast(I4);
                var packed = r.lanewise(VectorOperators.OR, INT4_VECTOR.cast(g).lanewise(VectorOperators.LSHL, 8))
                        .lanewise(VectorOperators.OR, INT4_VECTOR.cast(b).lanewise(VectorOperators.LSHL, 16))
                        .lanewise(VectorOperators.OR, INT4_VECTOR.cast(alpha).lanewise(VectorOperators.LSHL, 24))
                        .blend(ZERO4, transparent);
                PIXEL4_VECTOR.cast(INT4_VECTOR.cast(packed).reinterpretShape(BP4, 0)).intoArray(dst, dOff + 4 * x);
            }
        }
        tail.range(src, sOff, width, scale, dst, dOff, x, width);
    }

    /**
     * The vector loop followed by the static tail.
     */
    static void viaStatic(int[] src, int sOff, int width, int scale, byte[] dst, int dOff) {
        var x = 0;
        if (D4 != null) {
            var alphaFactor = DOUBLE_VECTOR.cast(DoubleVector.broadcast(D4, 1.0 / (510.0 * scale)));
            var alphaBias = DOUBLE_VECTOR.cast(DoubleVector.broadcast(D4, 255.0 * scale));
            var twice = DOUBLE_VECTOR.cast(DoubleVector.broadcast(D4, 2.0));
            var colorScale = DOUBLE_VECTOR.cast(DoubleVector.broadcast(D4, 510.0));
            var half = DOUBLE_VECTOR.cast(DoubleVector.broadcast(D4, 0.5));
            var margin = DOUBLE_VECTOR.cast(DoubleVector.broadcast(D4, QUOTIENT_MARGIN));
            var low = DOUBLE_VECTOR.cast(DoubleVector.broadcast(D4, -1.0));
            var high = DOUBLE_VECTOR.cast(DoubleVector.broadcast(D4, 256.0));
            var zero = DOUBLE_VECTOR.cast(DoubleVector.zero(D4));
            for (; x + 4 <= width; x += 4) {
                var a = DOUBLE_VECTOR.cast(INT4_VECTOR.cast(IntVector.fromArray(I4, src, sOff + 3 * width + x))
                        .convertShape(VectorOperators.I2D, D4, 0));
                var red = DOUBLE_VECTOR.cast(INT4_VECTOR.cast(IntVector.fromArray(I4, src, sOff + x))
                        .convertShape(VectorOperators.I2D, D4, 0));
                var green = DOUBLE_VECTOR.cast(INT4_VECTOR.cast(IntVector.fromArray(I4, src, sOff + width + x))
                        .convertShape(VectorOperators.I2D, D4, 0));
                var blue = DOUBLE_VECTOR.cast(INT4_VECTOR.cast(IntVector.fromArray(I4, src, sOff + 2 * width + x))
                        .convertShape(VectorOperators.I2D, D4, 0));
                var reciprocal = DOUBLE_VECTOR.cast(half).lanewise(VectorOperators.DIV, a);
                var r = INT4_VECTOR.cast(red.lanewise(VectorOperators.MUL, colorScale).lanewise(VectorOperators.ADD, a)
                                .lanewise(VectorOperators.MUL, reciprocal).lanewise(VectorOperators.ADD, margin)
                                .lanewise(VectorOperators.MAX, low).lanewise(VectorOperators.MIN, high)
                                .convertShape(VectorOperators.D2I, I4, 0))
                        .lanewise(VectorOperators.MAX, ZERO4).lanewise(VectorOperators.MIN, MAX_SAMPLE4);
                var g = INT4_VECTOR.cast(green.lanewise(VectorOperators.MUL, colorScale)
                        .lanewise(VectorOperators.ADD, a)
                                .lanewise(VectorOperators.MUL, reciprocal).lanewise(VectorOperators.ADD, margin)
                                .lanewise(VectorOperators.MAX, low).lanewise(VectorOperators.MIN, high)
                                .convertShape(VectorOperators.D2I, I4, 0))
                        .lanewise(VectorOperators.MAX, ZERO4).lanewise(VectorOperators.MIN, MAX_SAMPLE4);
                var b = INT4_VECTOR.cast(blue.lanewise(VectorOperators.MUL, colorScale)
                        .lanewise(VectorOperators.ADD, a)
                                .lanewise(VectorOperators.MUL, reciprocal).lanewise(VectorOperators.ADD, margin)
                                .lanewise(VectorOperators.MAX, low).lanewise(VectorOperators.MIN, high)
                                .convertShape(VectorOperators.D2I, I4, 0))
                        .lanewise(VectorOperators.MAX, ZERO4).lanewise(VectorOperators.MIN, MAX_SAMPLE4);
                var alpha = INT4_VECTOR.cast(a.lanewise(VectorOperators.MUL, twice)
                        .lanewise(VectorOperators.ADD, alphaBias)
                                .lanewise(VectorOperators.MUL, alphaFactor).lanewise(VectorOperators.ADD, margin)
                                .convertShape(VectorOperators.D2I, I4, 0))
                        .lanewise(VectorOperators.MIN, MAX_SAMPLE4);
                var transparent = a.compare(VectorOperators.LE, zero).cast(I4);
                var packed = r.lanewise(VectorOperators.OR, INT4_VECTOR.cast(g).lanewise(VectorOperators.LSHL, 8))
                        .lanewise(VectorOperators.OR, INT4_VECTOR.cast(b).lanewise(VectorOperators.LSHL, 16))
                        .lanewise(VectorOperators.OR, INT4_VECTOR.cast(alpha).lanewise(VectorOperators.LSHL, 24))
                        .blend(ZERO4, transparent);
                PIXEL4_VECTOR.cast(INT4_VECTOR.cast(packed).reinterpretShape(BP4, 0)).intoArray(dst, dOff + 4 * x);
            }
        }
        Tail.rangeStatic(src, sOff, width, scale, dst, dOff, x, width);
    }


    public static void main(String[] args) throws InterruptedException {
        var variant = args.length > 0 ? args[0] : "field";
        var seconds = args.length > 1 ? Integer.parseInt(args[1]) : 60;
        var threads = args.length > 2 ? Integer.parseInt(args[2]) : 4;
        if (!variant.equals("field") && !variant.equals("static")) {
            throw new IllegalArgumentException("variant: field or static");
        }
        System.out.println("variant " + variant + ", " + seconds + " s, " + threads + " threads, preferred species "
                + DoubleVector.SPECIES_PREFERRED + ", D4 " + D4);
        var deadline = System.nanoTime() + seconds * 1_000_000_000L;
        var calls = new AtomicLong();
        var mismatches = new AtomicLong();
        var workers = new ArrayList<Thread>();
        for (var t = 0; t < threads; t++) {
            var seed = t;
            workers.add(Thread.ofPlatform().name("repro-" + t).start(() -> {
                var random = new SplittableRandom(seed);
                var kernel = new Repro();
                // widths that are not multiples of 4 leave a tail, so the call after the loop always runs
                var width = 911 + 4 * seed;
                var scale = 1 << 14;
                var src = new int[4 * width];
                for (var x = 0; x < width; x++) {
                    var a = random.nextInt(-2, 256) * scale;
                    src[3 * width + x] = a;
                    for (var c = 0; c < 3; c++) {
                        src[c * width + x] = a <= 0 ? 0 : random.nextInt(0, a / 255 + 1) * 255 + random.nextInt(0, 255);
                    }
                }
                var expected = new byte[4 * width];
                Tail.rangeStatic(src, 0, width, scale, expected, 0, 0, width);
                var dst = new byte[4 * width];
                while (System.nanoTime() < deadline) {
                    for (var i = 0; i < 256; i++) {
                        if (variant.equals("field")) {
                            kernel.viaField(src, 0, width, scale, dst, 0);
                        } else {
                            viaStatic(src, 0, width, scale, dst, 0);
                        }
                        if (!Arrays.equals(dst, expected)) {
                            if (mismatches.incrementAndGet() <= 5) {
                                System.out.println("mismatch at byte " + Arrays.mismatch(dst, expected) + " in " + Thread.currentThread().getName());
                            }
                        }
                        Arrays.fill(dst, (byte) 0x5A);
                    }
                    calls.addAndGet(256);
                }
            }));
        }
        // garbage so that young collections, which stop the workers in their loops, keep happening
        var churn = Thread.ofPlatform().daemon().name("churn").start(() -> {
            var keep = new Object[64];
            var random = new SplittableRandom(99);
            while (true) {
                keep[random.nextInt(keep.length)] = new byte[random.nextInt(16, 65536)];
            }
        });
        for (var worker : workers) {
            worker.join();
        }
        System.out.println("calls " + calls.get() + ", mismatches " + mismatches.get());
        System.exit(mismatches.get() == 0 ? 0 : 3);
    }
}
