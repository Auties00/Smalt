package com.github.auties00.smalt.fixture;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

// Fixture of the operator rules: constant operators, vector operands, and int shift counts.
final class VectorOperands {
    static final VectorSpecies<Integer> S = IntVector.SPECIES_128;
    @SuppressWarnings("unchecked")
    static final Class<IntVector> INTS = (Class<IntVector>) S.vectorType();
    static final IntVector ONES = IntVector.broadcast(S, 1);
    static final VectorSpecies<Long> L = LongVector.SPECIES_128;
    @SuppressWarnings("unchecked")
    static final Class<LongVector> LONGS = (Class<LongVector>) L.vectorType();
    static VectorOperators.Binary variable = VectorOperators.ADD;

    static void scalarOperand(int[] a) {
        var v = INTS.cast(IntVector.fromArray(S, a, 0));
        v.lanewise(VectorOperators.ADD, 3).intoArray(a, 4); // expect: vector-scalar-operand
    }

    static void vectorOperand(int[] a) {
        var v = INTS.cast(IntVector.fromArray(S, a, 0));
        v.lanewise(VectorOperators.ADD, ONES).intoArray(a, 4);
    }

    static void shiftCount(int[] a) {
        var v = INTS.cast(IntVector.fromArray(S, a, 0));
        v.lanewise(VectorOperators.LSHL, 3).intoArray(a, 4);
    }

    static void longShiftCount(long[] a) {
        var v = LONGS.cast(LongVector.fromArray(L, a, 0));
        v.lanewise(VectorOperators.ASHR, 2L).intoArray(a, 2);
    }

    static void vectorShift(int[] a) {
        var v = INTS.cast(IntVector.fromArray(S, a, 0));
        v.lanewise(VectorOperators.LSHL, ONES).intoArray(a, 4); // expect: vector-shift-count
    }

    static int scalarCompare(int[] a) {
        var v = INTS.cast(IntVector.fromArray(S, a, 0));
        return v.compare(VectorOperators.LT, 5).trueCount(); // expect: vector-scalar-operand
    }

    static void scalarBlend(int[] a, VectorMask<Integer> m) {
        var v = INTS.cast(IntVector.fromArray(S, a, 0)); // expect: vector-helper
        v.blend(9, m).intoArray(a, 4); // expect: vector-scalar-operand
    }

    static void variableOperator(int[] a) {
        var v = INTS.cast(IntVector.fromArray(S, a, 0));
        v.lanewise(variable, ONES).intoArray(a, 4); // expect: vector-operator
    }

    static void localOperator(int[] a) {
        var op = VectorOperators.MUL;
        var v = INTS.cast(IntVector.fromArray(S, a, 0));
        v.lanewise(op, ONES).intoArray(a, 4);
    }

    static void chosenOperator(int[] a, boolean f) {
        var op = f ? VectorOperators.ADD : VectorOperators.SUB;
        var v = INTS.cast(IntVector.fromArray(S, a, 0));
        v.lanewise(op, ONES).intoArray(a, 4); // expect: vector-operator
    }

    static void localShift(int[] a) {
        var op = VectorOperators.LSHR;
        var v = INTS.cast(IntVector.fromArray(S, a, 0));
        v.lanewise(op, 2).intoArray(a, 4);
    }
}
