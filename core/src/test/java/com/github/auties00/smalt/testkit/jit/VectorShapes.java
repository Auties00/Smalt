package com.github.auties00.smalt.testkit.jit;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.Vector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives the Vector API operations the kernels use on every shape and element type, so that the type profiles the
 * operations share across shapes see three or more vector classes before a kernel is compiled.
 *
 * <p>C2 inlines a virtual call whose profile records at most {@code TypeProfileWidth} (two) receiver classes; a
 * kernel that reaches such a shared call with a receiver of unknown exact class, in a JVM where other code used more
 * shapes first, has it compiled as a virtual call and its vectors boxed. A JVM running several codecs builds those
 * profiles in an order no test controls; driving every shape first makes the outcome the same in every run.
 */
public final class VectorShapes {
    private static final int LANES = 64;
    private static volatile long sink;

    private VectorShapes() {
        throw new AssertionError();
    }

    /**
     * Runs every operation on every shape and element type, interleaving the shapes.
     *
     * @param rounds the number of rounds; each round runs each operation once per species
     */
    public static void pollute(int rounds) {
        var species = species();
        var bytes = new byte[LANES * 8];
        var shorts = new short[LANES * 8];
        var ints = new int[LANES * 8];
        var longs = new long[LANES * 8];
        var floats = new float[LANES * 8];
        var doubles = new double[LANES * 8];
        for (var i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 7);
            shorts[i] = (short) (i * 31);
            ints[i] = i * 131;
            longs[i] = i * 1031L;
            floats[i] = i * 0.25f;
            doubles[i] = i * 0.125;
        }
        long total = 0;
        for (var round = 0; round < rounds; round++) {
            for (var shape : species) {
                total += switch (shape.elementType().getName()) {
                    case "byte" -> bytes(shape, bytes);
                    case "short" -> shorts(shape, shorts);
                    case "int" -> ints(shape, ints);
                    case "long" -> longs(shape, longs);
                    case "float" -> floats(shape, floats);
                    default -> doubles(shape, doubles);
                };
                total += generic(shape);
            }
        }
        sink = total;
    }

    private static List<VectorSpecies<?>> species() {
        var all = new ArrayList<VectorSpecies<?>>();
        for (var type : List.of(byte.class, short.class, int.class, long.class, float.class, double.class)) {
            for (var shape : List.of(VectorShape.S_64_BIT, VectorShape.S_128_BIT, VectorShape.S_256_BIT,
                    VectorShape.S_512_BIT, VectorShape.S_Max_BIT)) {
                all.add(VectorSpecies.of(type, shape));
            }
        }
        return all;
    }

    private static <E> long generic(VectorSpecies<E> species) {
        var zero = species.zero();
        var one = species.broadcast(1);
        var sum = zero.lanewise(VectorOperators.ADD, one).lanewise(VectorOperators.MUL, 3)
                .lanewise(VectorOperators.MAX, zero);
        VectorMask<E> mask = sum.compare(VectorOperators.GT, one);
        var blended = sum.blend(one, mask);
        var shuffled = blended.rearrange(species.iotaShuffle(1, 1, true));
        Vector<?> reshaped = shuffled.reinterpretShape(species, 0);
        return blended.reduceLanesToLong(VectorOperators.ADD) + mask.trueCount() + reshaped.length()
                + shuffled.slice(1).reduceLanesToLong(VectorOperators.MAX);
    }

    private static long bytes(VectorSpecies<?> shape, byte[] data) {
        @SuppressWarnings("unchecked")
        var species = (VectorSpecies<Byte>) shape;
        var a = ByteVector.fromArray(species, data, 0);
        var b = ByteVector.fromArray(species, data, species.length());
        var c = a.lanewise(VectorOperators.ADD, b).lanewise(VectorOperators.LSHL, 1)
                .lanewise(VectorOperators.AND, (byte) 0x7f).lanewise(VectorOperators.MIN, b);
        c.intoArray(data, 2 * species.length());
        var widened = c.convert(VectorOperators.B2S, 0);
        return c.reduceLanes(VectorOperators.ADD) + widened.length();
    }

    private static long shorts(VectorSpecies<?> shape, short[] data) {
        @SuppressWarnings("unchecked")
        var species = (VectorSpecies<Short>) shape;
        var a = ShortVector.fromArray(species, data, 0);
        var b = ShortVector.fromArray(species, data, species.length());
        var c = a.lanewise(VectorOperators.ADD, b).lanewise(VectorOperators.ASHR, 2)
                .lanewise(VectorOperators.MUL, (short) 3).lanewise(VectorOperators.MAX, b);
        c.intoArray(data, 2 * species.length());
        var mask = c.compare(VectorOperators.LT, a);
        var widened = c.convert(VectorOperators.S2I, 0);
        return c.blend(a, mask).reduceLanes(VectorOperators.ADD) + widened.length();
    }

    private static long ints(VectorSpecies<?> shape, int[] data) {
        @SuppressWarnings("unchecked")
        var species = (VectorSpecies<Integer>) shape;
        var a = IntVector.fromArray(species, data, 0);
        var b = IntVector.fromArray(species, data, species.length());
        var c = a.lanewise(VectorOperators.ADD, b).lanewise(VectorOperators.LSHR, 3)
                .lanewise(VectorOperators.SUB, 5).lanewise(VectorOperators.MIN, b);
        c.intoArray(data, 2 * species.length());
        var mask = c.compare(VectorOperators.GE, b);
        var narrowed = c.convert(VectorOperators.I2S, 0);
        var floats = c.convert(VectorOperators.I2F, 0);
        return c.blend(b, mask).reduceLanes(VectorOperators.ADD) + narrowed.length() + floats.length();
    }

    private static long longs(VectorSpecies<?> shape, long[] data) {
        @SuppressWarnings("unchecked")
        var species = (VectorSpecies<Long>) shape;
        var a = LongVector.fromArray(species, data, 0);
        var b = LongVector.fromArray(species, data, species.length());
        var c = a.lanewise(VectorOperators.ADD, b).lanewise(VectorOperators.ASHR, 1)
                .lanewise(VectorOperators.XOR, 7L);
        c.intoArray(data, 2 * species.length());
        return c.reduceLanes(VectorOperators.ADD) + c.convert(VectorOperators.L2I, 0).length();
    }

    private static long floats(VectorSpecies<?> shape, float[] data) {
        @SuppressWarnings("unchecked")
        var species = (VectorSpecies<Float>) shape;
        var a = FloatVector.fromArray(species, data, 0);
        var b = FloatVector.fromArray(species, data, species.length());
        var c = a.lanewise(VectorOperators.FMA, b, a).lanewise(VectorOperators.MUL, 0.5f)
                .lanewise(VectorOperators.MAX, b);
        c.intoArray(data, 2 * species.length());
        var rounded = c.convert(VectorOperators.F2I, 0);
        return (long) c.reduceLanes(VectorOperators.ADD) + rounded.length();
    }

    private static long doubles(VectorSpecies<?> shape, double[] data) {
        @SuppressWarnings("unchecked")
        var species = (VectorSpecies<Double>) shape;
        var a = DoubleVector.fromArray(species, data, 0);
        var b = DoubleVector.fromArray(species, data, species.length());
        var c = a.lanewise(VectorOperators.ADD, b).lanewise(VectorOperators.MUL, 0.5)
                .lanewise(VectorOperators.MIN, b);
        c.intoArray(data, 2 * species.length());
        return (long) c.reduceLanes(VectorOperators.ADD) + c.convert(VectorOperators.D2F, 0).length();
    }
}
