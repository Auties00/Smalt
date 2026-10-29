package com.github.auties00.smalt.video.vc1;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.track.Packet;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Writes small VC-1 streams bit by bit from SMPTE ST 421, for tests that need streams with a known licence (the test
 * vectors of the FATE suite are fetched, never committed) and for the fuzz seeds: an advanced profile elementary
 * stream and a simple profile stream with its STRUCT_C.
 *
 * <p>The pictures are I pictures whose intra blocks code only a DC differential drawn from a seeded generator, P
 * pictures whose macroblocks are all skipped (coded in a row-skip bitplane), B pictures whose macroblocks are all
 * direct and skipped, and skipped pictures. A picture pattern string selects them in decoding order: {@code I},
 * {@code P}, {@code B} and {@code S} (skipped).
 */
public final class Vc1Synthetic {
    private Vc1Synthetic() {
    }

    // TRANSDCTAB = 0: the low motion DC differential codes of symbols 0 to 3 (Tables 173 and 174)
    private static final String[] LUMA_DC = {"1", "01", "0001", "00001"};
    private static final String[] CHROMA_DC = {"00", "01", "101", "1001"};

    /**
     * Writes an advanced profile elementary stream: a sequence header, an entry point and the pictures, each a frame
     * unit with its start code.
     *
     * @param seed the seed of the DC differentials
     * @param width the coded width, even
     * @param height the coded height, even
     * @param pictures the picture types in decoding order, the first an {@code I}
     * @param loopFilter whether the entry point enables the in-loop filter
     * @return the stream
     */
    public static byte[] advanced(long seed, int width, int height, String pictures, boolean loopFilter) {
        var random = new Random(seed);
        var out = new ByteArrayOutputStream();
        var sequence = new MsbBitWriter();
        sequence.write(3, 2);
        sequence.write(2, 3);
        sequence.write(1, 2);
        sequence.write(0, 3);
        sequence.write(0, 5);
        sequence.write(0, 1);
        sequence.write(width / 2 - 1, 12);
        sequence.write(height / 2 - 1, 12);
        sequence.write(0, 4);
        sequence.write(1, 1);
        sequence.write(0, 1);
        sequence.write(0, 1);
        sequence.write(0, 1);
        unit(out, 0x0F, sequence);
        var entry = new MsbBitWriter();
        entry.write(0, 1);
        entry.write(1, 1);
        entry.write(0, 2);
        entry.write(loopFilter ? 1 : 0, 1);
        entry.write(0, 2);
        entry.write(0, 2);
        entry.write(0, 2);
        entry.write(0, 2);
        entry.write(0, 1);
        entry.write(0, 2);
        unit(out, 0x0E, entry);
        var mbWidth = (width + 15) / 16;
        var mbHeight = (height + 15) / 16;
        for (var index = 0; index < pictures.length(); index++) {
            var bits = new MsbBitWriter();
            switch (pictures.charAt(index)) {
                case 'I' -> {
                    code(bits, "110");
                    bits.write(0, 1);
                    quantizer(bits);
                    bits.write(0, 1);
                    code(bits, "0000");
                    code(bits, "0");
                    code(bits, "0");
                    bits.write(0, 1);
                    for (var mb = 0; mb < mbWidth * mbHeight; mb++) {
                        code(bits, "1");
                        bits.write(0, 1);
                        intraBlocks(bits, random);
                    }
                }
                case 'P' -> {
                    code(bits, "0");
                    bits.write(index & 1, 1);
                    quantizer(bits);
                    code(bits, "1");
                    allOnes(bits, mbHeight);
                    bits.write(0, 2);
                    bits.write(0, 2);
                    code(bits, "0");
                    bits.write(0, 1);
                }
                case 'B' -> {
                    code(bits, "10");
                    bits.write(0, 1);
                    code(bits, "000");
                    quantizer(bits);
                    bits.write(1, 1);
                    allOnes(bits, mbHeight);
                    allOnes(bits, mbHeight);
                    bits.write(0, 2);
                    bits.write(0, 2);
                    code(bits, "0");
                    bits.write(0, 1);
                }
                default -> code(bits, "1111");
            }
            unit(out, 0x0D, bits);
        }
        return out.toByteArray();
    }

    /**
     * Writes a simple profile stream: its STRUCT_C configuration and one packet per frame, a skipped frame being a
     * packet of one zero byte.
     *
     * @param seed the seed of the DC differentials
     * @param width the width, a multiple of 16
     * @param height the height, a multiple of 16
     * @param pictures the picture types in decoding order, {@code I}, {@code P} or {@code S}, the first an {@code I}
     * @return the configuration and the packets
     */
    public static Vc1TestStreams.Stream simple(long seed, int width, int height, String pictures) {
        var random = new Random(seed);
        var structC = new MsbBitWriter();
        structC.write(0, 4);
        structC.write(0, 3);
        structC.write(0, 5);
        structC.write(0, 1);
        structC.write(0, 1);
        structC.write(0, 1);
        structC.write(1, 1);
        structC.write(1, 1);
        structC.write(0, 1);
        structC.write(0, 2);
        structC.write(0, 1);
        structC.write(0, 1);
        structC.write(0, 1);
        structC.write(0, 1);
        structC.write(0, 1);
        structC.write(0, 3);
        structC.write(0, 2);
        structC.write(0, 1);
        structC.write(1, 1);
        var mbs = (width / 16) * (height / 16);
        var packets = new ArrayList<Packet>();
        for (var index = 0; index < pictures.length(); index++) {
            byte[] data;
            var type = pictures.charAt(index);
            if (type == 'S') {
                data = new byte[1];
            } else {
                var bits = new MsbBitWriter();
                bits.write(index & 3, 2);
                if (type == 'I') {
                    bits.write(0, 1);
                    bits.write(0, 7);
                    quantizer(bits);
                    code(bits, "0");
                    code(bits, "0");
                    bits.write(0, 1);
                    for (var mb = 0; mb < mbs; mb++) {
                        code(bits, "1");
                        bits.write(0, 1);
                        intraBlocks(bits, random);
                    }
                } else {
                    bits.write(1, 1);
                    quantizer(bits);
                    code(bits, "1");
                    allOnes(bits, height / 16);
                    bits.write(0, 2);
                    bits.write(0, 2);
                    code(bits, "0");
                    bits.write(0, 1);
                }
                bits.alignWithZeros();
                data = bits.toByteArray();
            }
            packets.add(new Packet(1, data, index * 40L, index * 40L, Packet.UNKNOWN_DURATION, type == 'I'));
        }
        return new Vc1TestStreams.Stream(structC.toByteArray(), width, height, List.copyOf(packets));
    }

    /**
     * Serializes a simple or main profile stream as an RCV file (SMPTE ST 421 Annex L): the sequence layer with
     * STRUCT_C, the picture size and STRUCT_B, then each frame with its size, key flag and millisecond timestamp.
     *
     * @param stream the stream
     * @return the file
     */
    public static byte[] rcv(Vc1TestStreams.Stream stream) {
        var out = new ByteArrayOutputStream();
        littleEndian(out, 0xC5000000 | stream.packets().size());
        littleEndian(out, 4);
        out.writeBytes(stream.config());
        littleEndian(out, stream.height());
        littleEndian(out, stream.width());
        littleEndian(out, 12);
        out.writeBytes(new byte[12]);
        for (var packet : stream.packets()) {
            littleEndian(out, packet.data().length | (packet.keyframe() ? 0x80000000 : 0));
            littleEndian(out, (int) packet.pts());
            out.writeBytes(packet.data());
        }
        return out.toByteArray();
    }

    private static void littleEndian(ByteArrayOutputStream out, int value) {
        out.write(value);
        out.write(value >>> 8);
        out.write(value >>> 16);
        out.write(value >>> 24);
    }

    /**
     * Writes PQINDEX 6 (the implicit quantizer 6, uniform) and its HALFQP.
     */
    private static void quantizer(MsbBitWriter bits) {
        bits.write(6, 5);
        bits.write(0, 1);
    }

    /**
     * Writes a bitplane of ones: INVERT set, row-skip mode, every row skipped.
     */
    private static void allOnes(MsbBitWriter bits, int rows) {
        bits.write(1, 1);
        code(bits, "010");
        for (var row = 0; row < rows; row++) {
            bits.write(0, 1);
        }
    }

    /**
     * Writes the six intra blocks of a macroblock without AC coefficients: a DC differential each.
     */
    private static void intraBlocks(MsbBitWriter bits, Random random) {
        for (var block = 0; block < 6; block++) {
            var symbol = random.nextInt(4);
            code(bits, block < 4 ? LUMA_DC[symbol] : CHROMA_DC[symbol]);
            if (symbol != 0) {
                bits.write(random.nextInt(2), 1);
            }
        }
    }

    private static void code(MsbBitWriter bits, String code) {
        for (var index = 0; index < code.length(); index++) {
            bits.write(code.charAt(index) - '0', 1);
        }
    }

    /**
     * Writes one bitstream data unit: the start code, then the payload ended by a one bit and zero padding, with the
     * emulation prevention bytes of SMPTE ST 421 Annex E.
     */
    private static void unit(ByteArrayOutputStream out, int code, MsbBitWriter bits) {
        bits.writeTrailingBits();
        var payload = bits.toByteArray();
        out.writeBytes(new byte[]{0, 0, 1, (byte) code});
        var zeros = 0;
        for (var value : payload) {
            if (zeros >= 2 && (value & 0xFF) <= 3) {
                out.write(3);
                zeros = 0;
            }
            out.write(value);
            zeros = value == 0 ? zeros + 1 : 0;
        }
    }
}
