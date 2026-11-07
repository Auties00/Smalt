package com.github.auties00.smalt.audio.aac.internal.ep;

import com.github.auties00.smalt.audio.aac.internal.AacBitReader;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes {@code ErrorProtectionSpecificConfig} payloads (ISO/IEC 14496-3 Table 1.49) for the tests.
 */
final class EpConfigWriter {
    /** One class: the fields of Table 1.49, the length, rate and CRC length used when they are not escaped. */
    record Spec(boolean lengthEscape, boolean rateEscape, boolean crcEscape, boolean concatenate, int fec,
                boolean termination, int interleaveSwitch, boolean optional, int lengthBits, int length, int rate,
                int crcCode) {
        static Spec srcpc(int lengthBits, int rate, int crcCode) {
            return new Spec(true, false, false, false, 0, false, 0, false, lengthBits, 0, rate, crcCode);
        }

        static Spec reedSolomon(int lengthBits, int fec, int correctable, int crcCode) {
            return new Spec(true, false, false, false, fec, false, 0, false, lengthBits, 0, correctable, crcCode);
        }

        static Spec fixed(int length, int fec, int rate, int crcCode) {
            return new Spec(false, false, false, false, fec, false, 0, false, 0, length, rate, crcCode);
        }

        Spec terminated() {
            return new Spec(lengthEscape, rateEscape, crcEscape, concatenate, fec, true, interleaveSwitch, optional,
                    lengthBits, length, rate, crcCode);
        }

        Spec asOptional() {
            return new Spec(lengthEscape, rateEscape, crcEscape, concatenate, fec, termination, interleaveSwitch, true,
                    lengthBits, length, rate, crcCode);
        }

        Spec interleaved(int mode) {
            return new Spec(lengthEscape, rateEscape, crcEscape, concatenate, fec, termination, mode, optional,
                    lengthBits, length, rate, crcCode);
        }

        Spec escaped(boolean rate, boolean crc) {
            return new Spec(lengthEscape, rate, crc, concatenate, fec, termination, interleaveSwitch, optional,
                    lengthBits, length, this.rate, crcCode);
        }

        Spec concatenated() {
            return new Spec(lengthEscape, rateEscape, crcEscape, true, fec, termination, interleaveSwitch, optional,
                    lengthBits, length, rate, crcCode);
        }
    }

    /** One pre-defined set: its classes in output order and the frame order, or {@code null} for the identity. */
    record Set(List<Spec> classes, int[] order) {
    }

    private EpConfigWriter() {
    }

    static byte[] write(List<Set> sets, int interleaveType, boolean stuffing, int concatenated, int headerRate,
                        int headerCrcCode) {
        var out = new EpFrameEncoder.BitList();
        append(out, sets, interleaveType, stuffing, concatenated, headerRate, headerCrcCode);
        return EpFrameEncoder.pack(out.toArray());
    }

    static void append(EpFrameEncoder.BitList out, List<Set> sets, int interleaveType, boolean stuffing,
                       int concatenated, int headerRate, int headerCrcCode) {
        out.add(sets.size(), 8);
        out.add(interleaveType, 2);
        out.add(stuffing ? 1 : 0, 3);
        out.add(concatenated, 3);
        for (var set : sets) {
            out.add(set.classes().size(), 6);
            for (var spec : set.classes()) {
                out.add(spec.lengthEscape() ? 1 : 0, 1);
                out.add(spec.rateEscape() ? 1 : 0, 1);
                out.add(spec.crcEscape() ? 1 : 0, 1);
                if (concatenated != 1) {
                    out.add(spec.concatenate() ? 1 : 0, 1);
                }
                out.add(spec.fec(), 2);
                if (spec.fec() == 0) {
                    out.add(spec.termination() ? 1 : 0, 1);
                }
                if (interleaveType == 2) {
                    out.add(spec.interleaveSwitch(), 2);
                }
                out.add(spec.optional() ? 1 : 0, 1);
                if (spec.lengthEscape()) {
                    out.add(spec.lengthBits(), 4);
                } else {
                    out.add(spec.length(), 16);
                }
                if (!spec.rateEscape()) {
                    out.add(spec.rate(), spec.fec() != 0 ? 7 : 5);
                }
                if (!spec.crcEscape()) {
                    out.add(spec.crcCode(), 5);
                }
            }
            if (set.order() == null) {
                out.add(0, 1);
            } else {
                out.add(1, 1);
                for (var index : set.order()) {
                    out.add(index, 6);
                }
            }
        }
        if (headerRate < 0) {
            out.add(0, 1);
        } else {
            out.add(1, 1);
            out.add(headerRate, 5);
            out.add(headerCrcCode, 5);
        }
    }

    static ErrorProtectionConfig parse(byte[] bytes) {
        return ErrorProtectionConfig.read(new AacBitReader(bytes));
    }

    static List<Set> sets(Set... sets) {
        return new ArrayList<>(List.of(sets));
    }
}
