package com.github.auties00.smalt.image.jp2.internal;

import java.io.ByteArrayOutputStream;

/**
 * Writes the marker segments of ISO/IEC 15444-2 annex A for test codestreams, field by field as the tables of the
 * standard lay them out.
 */
final class Part2Segments {
    static final int RSIZ_DCO = 0x0001;
    static final int RSIZ_ADS = 0x0020;
    static final int RSIZ_ATK = 0x0040;
    static final int RSIZ_WS = 0x0080;
    static final int RSIZ_MCT = 0x0100;
    static final int RSIZ_NLT = 0x0200;
    static final int RSIZ_ROI = 0x0400;

    private Part2Segments() {
        throw new AssertionError();
    }

    /** A {@code DCO} segment (Table A.20): the offset type, then one offset per component. */
    static byte[] dco(int type, double... offsets) {
        var out = new ByteArrayOutputStream();
        out.write(type);
        for (var offset : offsets) {
            switch (type) {
                case 0 -> out.write((int) offset);
                case 1 -> Part2Forward.u16(out, (int) offset & 0xFFFF);
                case 2 -> Part2Forward.u32(out, Float.floatToIntBits((float) offset) & 0xFFFFFFFFL);
                default -> Part2Forward.u64(out, Double.doubleToLongBits(offset));
            }
        }
        return Part2Forward.segment(Codestream.DCO, out.toByteArray());
    }

    /** A {@code CBD} segment (Table A.29) giving each reconstructed component its own bit depth. */
    static byte[] cbd(int[] precisions, boolean[] signed) {
        var out = new ByteArrayOutputStream();
        Part2Forward.u16(out, precisions.length);
        for (var i = 0; i < precisions.length; i++) {
            out.write((precisions[i] - 1) | (signed[i] ? 0x80 : 0));
        }
        return Part2Forward.segment(Codestream.CBD, out.toByteArray());
    }

    /** A {@code CBD} segment giving every reconstructed component one bit depth. */
    static byte[] cbdShared(int count, int precision, boolean signed) {
        var out = new ByteArrayOutputStream();
        Part2Forward.u16(out, 0x8000 | count);
        out.write((precision - 1) | (signed ? 0x80 : 0));
        return Part2Forward.segment(Codestream.CBD, out.toByteArray());
    }

    /**
     * An {@code MCT} segment (Table A.32) holding a whole array: {@code arrayType} 0 dependency, 1 decorrelation, 2
     * offset; {@code elementType} 0 int16, 1 int32, 2 float32, 3 float64.
     */
    static byte[] mct(int index, int arrayType, int elementType, double... values) {
        return mctPart(0, 0, index, arrayType, elementType, values);
    }

    /** One segment of an {@code MCT} series; {@code last} is {@code Ymct}, written only by segment 0. */
    static byte[] mctPart(int z, int last, int index, int arrayType, int elementType, double... values) {
        var out = new ByteArrayOutputStream();
        Part2Forward.u16(out, z);
        Part2Forward.u16(out, index | (arrayType << 8) | (elementType << 10));
        if (z == 0) {
            Part2Forward.u16(out, last);
        }
        for (var value : values) {
            switch (elementType) {
                case 0 -> Part2Forward.u16(out, (int) value & 0xFFFF);
                case 1 -> Part2Forward.u32(out, (long) value & 0xFFFFFFFFL);
                case 2 -> Part2Forward.u32(out, Float.floatToIntBits((float) value) & 0xFFFFFFFFL);
                default -> Part2Forward.u64(out, Double.doubleToLongBits(value));
            }
        }
        return Part2Forward.segment(Codestream.MCT, out.toByteArray());
    }

    /**
     * One component collection of an {@code MCC} segment: {@code type} 0 dependency, 1 decorrelation, 3 wavelet;
     * for an array-based collection {@code transform} and {@code offsets} are MCT indices and {@code levels} is unused,
     * for a wavelet-based one {@code transform} is the kernel and {@code origin} is {@code Omcc}.
     */
    record Collection(int type, int[] inputs, int[] outputs, boolean reversible, int transform, int offsets,
                      int levels, long origin) {
        static Collection array(int type, boolean reversible, int transform, int offsets, int[] inputs, int[] outputs) {
            return new Collection(type, inputs, outputs, reversible, transform, offsets, 0, 0);
        }

        static Collection wavelet(int kernel, int offsets, int levels, long origin, int[] inputs, int[] outputs) {
            return new Collection(3, inputs, outputs, false, kernel, offsets, levels, origin);
        }
    }

    /** An {@code MCC} segment (Table A.34) holding a whole series. */
    static byte[] mcc(int index, Collection... collections) {
        var out = new ByteArrayOutputStream();
        Part2Forward.u16(out, 0);
        out.write(index);
        Part2Forward.u16(out, 0);
        Part2Forward.u16(out, collections.length);
        for (var collection : collections) {
            out.write(collection.type());
            indices(out, collection.inputs());
            indices(out, collection.outputs());
            int tmcc;
            if (collection.type() == 3) {
                tmcc = collection.transform() | (collection.offsets() << 8) | (collection.levels() << 16);
            } else {
                tmcc = collection.transform() | (collection.offsets() << 8) | (collection.reversible() ? 0x10000 : 0);
            }
            out.write((tmcc >> 16) & 0xFF);
            out.write((tmcc >> 8) & 0xFF);
            out.write(tmcc & 0xFF);
            if (collection.type() == 3) {
                Part2Forward.u32(out, collection.origin());
            }
        }
        return Part2Forward.segment(Codestream.MCC, out.toByteArray());
    }

    private static void indices(ByteArrayOutputStream out, int[] indices) {
        var wide = false;
        for (var index : indices) {
            wide |= index > 255;
        }
        Part2Forward.u16(out, indices.length | (wide ? 0x8000 : 0));
        for (var index : indices) {
            if (wide) {
                Part2Forward.u16(out, index);
            } else {
                out.write(index);
            }
        }
    }

    /** An {@code MCO} segment (Table A.40) listing the MCC index of each stage. */
    static byte[] mco(int... stages) {
        var out = new ByteArrayOutputStream();
        out.write(stages.length);
        for (var stage : stages) {
            out.write(stage);
        }
        return Part2Forward.segment(Codestream.MCO, out.toByteArray());
    }

    /** An {@code NLT} segment (Table A.41) of type 0 (none) or 3 (two's complement to sign-magnitude). */
    static byte[] nlt(int component, int precision, boolean signed, int type) {
        var out = header(component, precision, signed, type);
        return Part2Forward.segment(Codestream.NLT, out.toByteArray());
    }

    /** A gamma-style {@code NLT} segment (Table A.45): E, S, T, A and B as 8.16 fixed-point values. */
    static byte[] nltGamma(int component, int precision, boolean signed, double e, double s, double t, double a,
                           double b) {
        var out = header(component, precision, signed, 1);
        for (var value : new double[]{e, s, t, a, b}) {
            var integer = (int) Math.floor(value);
            var fraction = (int) Math.round((value - integer) * 65535);
            out.write(integer);
            Part2Forward.u16(out, fraction);
        }
        return Part2Forward.segment(Codestream.NLT, out.toByteArray());
    }

    /** A LUT-style {@code NLT} segment (Table A.46). */
    static byte[] nltLookup(int component, int precision, boolean signed, long dmin, long dmax, int bits,
                            long[] values) {
        var out = header(component, precision, signed, 2);
        Part2Forward.u16(out, values.length - 1);
        Part2Forward.u32(out, dmin);
        Part2Forward.u32(out, dmax);
        out.write(bits);
        for (var value : values) {
            if (bits <= 8) {
                out.write((int) value);
            } else if (bits <= 16) {
                Part2Forward.u16(out, (int) value);
            } else {
                Part2Forward.u32(out, value);
            }
        }
        return Part2Forward.segment(Codestream.NLT, out.toByteArray());
    }

    private static ByteArrayOutputStream header(int component, int precision, boolean signed, int type) {
        var out = new ByteArrayOutputStream();
        Part2Forward.u16(out, component);
        out.write((precision - 1) | (signed ? 0x80 : 0));
        out.write(type);
        return out;
    }

    /** An {@code RGN} segment of the arbitrary styles (Table A.18): 1 rectangle, 2 ellipse. */
    static byte[] rgn(int component, int style, int shift, long xa, long ya, long xb, long yb) {
        var out = new ByteArrayOutputStream();
        Part2Forward.u16(out, component);
        out.write(style);
        out.write(shift);
        Part2Forward.u32(out, xa);
        Part2Forward.u32(out, ya);
        Part2Forward.u32(out, xb);
        Part2Forward.u32(out, yb);
        return Part2Forward.segment(Codestream.RGN, out.toByteArray());
    }

    /** A {@code DFS} segment (A.3.3): its index and the {@code d_R} elements. */
    static byte[] dfs(int index, int... splits) {
        var out = new ByteArrayOutputStream();
        out.write(index);
        out.write(splits.length);
        elements(out, splits);
        return Part2Forward.segment(Codestream.DFS, out.toByteArray());
    }

    /** A {@code DFS} segment whose {@code Sdfs} is 16 bits wide, as Table A.25 (unlike equation A-2) gives it. */
    static byte[] dfsWideIndex(int index, int... splits) {
        var out = new ByteArrayOutputStream();
        Part2Forward.u16(out, index);
        out.write(splits.length);
        elements(out, splits);
        return Part2Forward.segment(Codestream.DFS, out.toByteArray());
    }

    /** An {@code ADS} segment (A.3.4): its index, the {@code d_theta} and the {@code d_S} elements. */
    static byte[] ads(int index, int[] levels, int[] structure) {
        var out = new ByteArrayOutputStream();
        out.write(index);
        out.write(levels.length);
        elements(out, levels);
        out.write(structure.length);
        elements(out, structure);
        return Part2Forward.segment(Codestream.ADS, out.toByteArray());
    }

    private static void elements(ByteArrayOutputStream out, int[] elements) {
        for (var i = 0; i < elements.length; i += 4) {
            var value = 0;
            for (var j = 0; j < 4; j++) {
                value = (value << 2) | (i + j < elements.length ? elements[i + j] : 0);
            }
            out.write(value);
        }
    }

    /** A tile-part {@code COD} segment of one layer and the LRCP progression. */
    static byte[] cod(int mct, int decomposition, int codeBlock, int wavelet) {
        var out = new ByteArrayOutputStream();
        out.write(0);
        out.write(ProgressionChange.LRCP);
        Part2Forward.u16(out, 1);
        out.write(mct);
        out.write(decomposition);
        out.write(codeBlock - 2);
        out.write(codeBlock - 2);
        out.write(0);
        out.write(wavelet);
        return Part2Forward.segment(Codestream.COD, out.toByteArray());
    }
}
