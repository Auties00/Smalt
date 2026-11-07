/* Ports XZ for Java 1.10 (simple/X86.java, PowerPC.java, IA64.java, ARM.java, ARMThumb.java, SPARC.java, ARM64.java,
 * RISCVDecoder.java, delta/DeltaDecoder.java). Copyright The XZ for Java authors and contributors; 0BSD.
 * Modified for Smalt. */
package com.github.auties00.smalt.image.internal.xz;

/**
 * Reverses the XZ branch-call-jump filters and the delta filter in place.
 *
 * <p>Each method converts a whole block of output at once, as a stream decoder would when the block
 * ends, and leaves the last few bytes that cannot start a complete instruction unconverted.
 */
final class BranchFilters {
    /** Filter id: delta. */
    static final int DELTA = 0x03;
    /** Filter id: x86 BCJ. */
    static final int X86 = 0x04;
    /** Filter id: PowerPC BCJ. */
    static final int POWERPC = 0x05;
    /** Filter id: IA-64 BCJ. */
    static final int IA64 = 0x06;
    /** Filter id: ARM BCJ. */
    static final int ARM = 0x07;
    /** Filter id: ARM-Thumb BCJ. */
    static final int ARM_THUMB = 0x08;
    /** Filter id: SPARC BCJ. */
    static final int SPARC = 0x09;
    /** Filter id: ARM64 BCJ. */
    static final int ARM64 = 0x0A;
    /** Filter id: RISC-V BCJ. */
    static final int RISCV = 0x0B;

    private static final boolean[] MASK_TO_ALLOWED_STATUS = {true, true, true, false, true, false, false, false};
    private static final int[] MASK_TO_BIT_NUMBER = {0, 1, 2, 2, 3, 3, 3, 3};
    private static final int[] BRANCH_TABLE = {
            0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0,
            4, 4, 6, 6, 0, 0, 7, 7,
            4, 4, 0, 0, 4, 4, 0, 0};

    private BranchFilters() {
        throw new AssertionError();
    }

    /**
     * Reverses a filter on a block.
     *
     * @param id the filter id
     * @param property the start offset of a branch filter, or the distance of the delta filter
     * @param buf the block
     * @param off the first byte
     * @param len the number of bytes
     */
    static void decode(int id, int property, byte[] buf, int off, int len) {
        switch (id) {
            case DELTA -> delta(buf, off, len, property);
            case X86 -> x86(buf, off, len, property);
            case POWERPC -> powerPc(buf, off, len, property);
            case IA64 -> ia64(buf, off, len, property);
            case ARM -> arm(buf, off, len, property);
            case ARM_THUMB -> armThumb(buf, off, len, property);
            case SPARC -> sparc(buf, off, len, property);
            case ARM64 -> arm64(buf, off, len, property);
            case RISCV -> riscv(buf, off, len, property);
            default -> throw new IllegalArgumentException("filter " + id);
        }
    }

    /**
     * {@return the longest unconverted tail a filter leaves, which needs more input to convert}
     *
     * @param id the filter id
     */
    static int lookahead(int id) {
        return switch (id) {
            case DELTA -> 0;
            case IA64 -> 16;
            case RISCV -> 8;
            default -> 5;
        };
    }

    private static void delta(byte[] buf, int off, int len, int distance) {
        for (var i = off + distance; i < off + len; i++) {
            buf[i] += buf[i - distance];
        }
    }

    private static boolean test86MSByte(byte b) {
        var i = b & 0xFF;
        return i == 0x00 || i == 0xFF;
    }

    private static void x86(byte[] buf, int off, int len, int startPos) {
        var pos = startPos + 5;
        var prevMask = 0;
        var prevPos = off - 1;
        var end = off + len - 5;
        for (var i = off; i <= end; ++i) {
            if ((buf[i] & 0xFE) != 0xE8) {
                continue;
            }
            prevPos = i - prevPos;
            if ((prevPos & ~3) != 0) {
                prevMask = 0;
            } else {
                prevMask = (prevMask << (prevPos - 1)) & 7;
                if (prevMask != 0) {
                    if (!MASK_TO_ALLOWED_STATUS[prevMask] || test86MSByte(buf[i + 4 - MASK_TO_BIT_NUMBER[prevMask]])) {
                        prevPos = i;
                        prevMask = (prevMask << 1) | 1;
                        continue;
                    }
                }
            }
            prevPos = i;
            if (test86MSByte(buf[i + 4])) {
                var src = intLE(buf, i + 1);
                int dest;
                while (true) {
                    dest = src - (pos + i - off);
                    if (prevMask == 0) {
                        break;
                    }
                    var index = MASK_TO_BIT_NUMBER[prevMask] * 8;
                    if (!test86MSByte((byte) (dest >>> (24 - index)))) {
                        break;
                    }
                    src = dest ^ ((1 << (32 - index)) - 1);
                }
                dest <<= 7;
                dest >>= 7;
                setIntLE(buf, i + 1, dest);
                i += 4;
            } else {
                prevMask = (prevMask << 1) | 1;
            }
        }
    }

    private static void powerPc(byte[] buf, int off, int len, int startPos) {
        for (var i = off; i <= off + len - 4; i += 4) {
            if ((buf[i] & 0xFC) == 0x48 && (buf[i + 3] & 0x03) == 0x01) {
                var instr = intBE(buf, i);
                var pc = -(startPos + i - off);
                setIntBE(buf, i, 0x48000001 | ((instr + pc) & 0x03FFFFFC));
            }
        }
    }

    private static void ia64(byte[] buf, int off, int len, int startPos) {
        for (var i = off; i <= off + len - 16; i += 16) {
            var mask = BRANCH_TABLE[buf[i] & 0x1F];
            for (int slot = 0, bitPos = 5; slot < 3; ++slot, bitPos += 41) {
                if (((mask >>> slot) & 1) == 0) {
                    continue;
                }
                var bytePos = bitPos >>> 3;
                var bitRes = bitPos & 7;
                var instr = 0L;
                for (var j = 0; j < 6; ++j) {
                    instr |= (buf[i + bytePos + j] & 0xFFL) << (8 * j);
                }
                var instrNorm = instr >>> bitRes;
                if (((instrNorm >>> 37) & 0x0F) != 0x05 || ((instrNorm >>> 9) & 0x07) != 0x00) {
                    continue;
                }
                var src = (int) ((instrNorm >>> 13) & 0x0FFFFF);
                src |= ((int) (instrNorm >>> 36) & 1) << 20;
                src <<= 4;
                var dest = src - (startPos + i - off);
                dest >>>= 4;
                instrNorm &= ~(0x8FFFFFL << 13);
                instrNorm |= (dest & 0x0FFFFFL) << 13;
                instrNorm |= (dest & 0x100000L) << (36 - 20);
                instr &= (1 << bitRes) - 1;
                instr |= instrNorm << bitRes;
                for (var j = 0; j < 6; ++j) {
                    buf[i + bytePos + j] = (byte) (instr >>> (8 * j));
                }
            }
        }
    }

    private static void arm(byte[] buf, int off, int len, int startPos) {
        var pos = startPos + 8;
        for (var i = off; i <= off + len - 4; i += 4) {
            if ((buf[i + 3] & 0xFF) == 0xEB) {
                var src = ((buf[i + 2] & 0xFF) << 16) | ((buf[i + 1] & 0xFF) << 8) | (buf[i] & 0xFF);
                src <<= 2;
                var dest = src - (pos + i - off);
                dest >>>= 2;
                buf[i + 2] = (byte) (dest >>> 16);
                buf[i + 1] = (byte) (dest >>> 8);
                buf[i] = (byte) dest;
            }
        }
    }

    private static void armThumb(byte[] buf, int off, int len, int startPos) {
        var pos = startPos + 4;
        for (var i = off; i <= off + len - 4; i += 2) {
            if ((buf[i + 1] & 0xF8) == 0xF0 && (buf[i + 3] & 0xF8) == 0xF8) {
                var src = ((buf[i + 1] & 0x07) << 19) | ((buf[i] & 0xFF) << 11) | ((buf[i + 3] & 0x07) << 8)
                        | (buf[i + 2] & 0xFF);
                src <<= 1;
                var dest = src - (pos + i - off);
                dest >>>= 1;
                buf[i + 1] = (byte) (0xF0 | ((dest >>> 19) & 0x07));
                buf[i] = (byte) (dest >>> 11);
                buf[i + 3] = (byte) (0xF8 | ((dest >>> 8) & 0x07));
                buf[i + 2] = (byte) dest;
                i += 2;
            }
        }
    }

    private static void sparc(byte[] buf, int off, int len, int startPos) {
        for (var i = off; i <= off + len - 4; i += 4) {
            if ((buf[i] == 0x40 && (buf[i + 1] & 0xC0) == 0x00) || (buf[i] == 0x7F && (buf[i + 1] & 0xC0) == 0xC0)) {
                var src = intBE(buf, i);
                var pc = -((startPos + i - off) >>> 2);
                var dest = src + pc;
                dest <<= 9;
                dest >>= 9;
                dest = 0x40000000 | (dest & 0x3FFFFFFF);
                setIntBE(buf, i, dest);
            }
        }
    }

    private static void arm64(byte[] buf, int off, int len, int startPos) {
        for (var i = off; i <= off + len - 4; i += 4) {
            int instr = buf[i + 3];
            if ((instr & 0xFC) == 0x94) {
                instr = intLE(buf, i);
                var pc = -((startPos + i - off) >>> 2);
                setIntLE(buf, i, 0x94000000 | ((instr + pc) & 0x03FFFFFF));
            } else if ((instr & 0x9F) == 0x90) {
                instr = intLE(buf, i);
                var src = ((instr >>> 29) & 3) | ((instr >>> 3) & 0x001FFFFC);
                if (((src + 0x00020000) & 0x001C0000) != 0) {
                    continue;
                }
                var pc = -((startPos + i - off) >>> 12);
                var dest = src + pc;
                instr &= 0x9000001F;
                instr |= (dest & 3) << 29;
                instr |= (dest & 0x0003FFFC) << 3;
                instr |= (-(dest & 0x00020000)) & 0x00E00000;
                setIntLE(buf, i, instr);
            }
        }
    }

    private static void riscv(byte[] buf, int off, int len, int startPos) {
        var end = off + len - 8;
        for (var i = off; i <= end; i += 2) {
            var inst = buf[i] & 0xFF;
            if (inst == 0xEF) {
                var b1 = buf[i + 1] & 0xFF;
                if ((b1 & 0x0D) != 0) {
                    continue;
                }
                var b2 = buf[i + 2] & 0xFF;
                var b3 = buf[i + 3] & 0xFF;
                var pc = startPos + i - off;
                var addr = ((b1 & 0xF0) << 13) | (b2 << 9) | (b3 << 1);
                addr -= pc;
                buf[i + 1] = (byte) ((b1 & 0x0F) | ((addr >>> 8) & 0xF0));
                buf[i + 2] = (byte) (((addr >>> 16) & 0x0F) | ((addr >>> 7) & 0x10) | ((addr << 4) & 0xE0));
                buf[i + 3] = (byte) (((addr >>> 4) & 0x7F) | ((addr >>> 13) & 0x80));
                i += 4 - 2;
            } else if ((inst & 0x7F) == 0x17) {
                int inst2;
                inst |= (buf[i + 1] & 0xFF) << 8;
                inst |= (buf[i + 2] & 0xFF) << 16;
                inst |= (buf[i + 3] & 0xFF) << 24;
                if ((inst & 0xE80) != 0) {
                    inst2 = intLE(buf, i + 4);
                    if ((((inst << 8) ^ inst2) & 0xF8003) != 3) {
                        i += 6 - 2;
                        continue;
                    }
                    var addr = (inst & 0xFFFFF000) + (inst2 >>> 20);
                    inst = 0x17 | (2 << 7) | (inst2 << 12);
                    inst2 = addr;
                } else {
                    var inst2Rs1 = inst >>> 27;
                    if (((inst - 0x3100) & 0x3F80) >= (inst2Rs1 & 0x1D)) {
                        i += 4 - 2;
                        continue;
                    }
                    var addr = intBE(buf, i + 4);
                    addr -= startPos + i - off;
                    inst2 = (inst >>> 12) | (addr << 20);
                    inst = 0x17 | (inst2Rs1 << 7) | ((addr + 0x800) & 0xFFFFF000);
                }
                setIntLE(buf, i, inst);
                setIntLE(buf, i + 4, inst2);
                i += 8 - 2;
            }
        }
    }

    private static int intLE(byte[] buf, int i) {
        return (buf[i] & 0xFF) | (buf[i + 1] & 0xFF) << 8 | (buf[i + 2] & 0xFF) << 16 | (buf[i + 3] & 0xFF) << 24;
    }

    private static int intBE(byte[] buf, int i) {
        return (buf[i] & 0xFF) << 24 | (buf[i + 1] & 0xFF) << 16 | (buf[i + 2] & 0xFF) << 8 | (buf[i + 3] & 0xFF);
    }

    private static void setIntLE(byte[] buf, int i, int value) {
        buf[i] = (byte) value;
        buf[i + 1] = (byte) (value >>> 8);
        buf[i + 2] = (byte) (value >>> 16);
        buf[i + 3] = (byte) (value >>> 24);
    }

    private static void setIntBE(byte[] buf, int i, int value) {
        buf[i] = (byte) (value >>> 24);
        buf[i + 1] = (byte) (value >>> 16);
        buf[i + 2] = (byte) (value >>> 8);
        buf[i + 3] = (byte) value;
    }
}
