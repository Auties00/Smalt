package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.track.Packet;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes synthetic MPEG-4 Studio profile streams (Simple Studio object type, intra VOPs) for the decoding workloads:
 * macroblocks alternate between DCT coding (DC levels, a first AC level and field DCT on interlaced layers) and DPCM
 * coding, one slice per macroblock row.
 */
final class SyntheticStudio {
    /**
     * The luminance DC size codes of sizes 0 to 3.
     */
    private static final int[][] DC_LUMA = {{0b001110, 6}, {0b00110, 5}, {0b0000, 4}, {0b0010, 4}};

    /**
     * The chrominance DC size codes of sizes 0 to 3.
     */
    private static final int[][] DC_CHROMA = {{0b0000, 4}, {0b0010, 4}, {0b111, 3}, {0b101, 3}};

    private SyntheticStudio() {
    }

    /**
     * A Studio stream: the layer header as codec configuration and one packet per VOP.
     *
     * @param config the visual object and video object layer headers
     * @param packets the VOP packets
     */
    record Stream(byte[] config, List<Packet> packets) {
    }

    /**
     * Writes a stream.
     *
     * @param width the picture width, a multiple of 16
     * @param height the picture height, a multiple of 32
     * @param chroma the chroma format code: 1 (4:2:0), 2 (4:2:2) or 3 (4:4:4)
     * @param bits the sample precision: 8, 10 or 12
     * @param interlaced whether the layer is interlaced, with field DCT macroblocks
     * @param frames the number of VOPs
     * @return the stream
     */
    static Stream write(int width, int height, int chroma, int bits, boolean interlaced, int frames) {
        var config = new ByteArrayOutputStream();
        startCode(config, 0x00);
        startCode(config, 0x20);
        var layer = new BitWriter(false);
        layer.write(0, 1);
        layer.write(0x0F, 8);
        layer.write(4, 4);
        layer.write(0, 2);
        layer.write(15, 4);
        layer.write(interlaced ? 0 : 1, 1);
        layer.write(0, 1);
        layer.write(chroma, 2);
        layer.write(bits, 4);
        layer.write(1, 1);
        layer.write(width, 14);
        layer.write(1, 1);
        layer.write(height, 14);
        layer.write(1, 1);
        layer.write(1, 4);
        layer.write(3, 4);
        layer.write(1000, 15);
        layer.write(1, 1);
        layer.write(0, 15);
        layer.write(1, 1);
        layer.write(100, 15);
        layer.write(1, 1);
        layer.write(0, 3);
        layer.write(50, 11);
        layer.write(1, 1);
        layer.write(0, 15);
        layer.write(1, 1);
        layer.write(1, 1);
        layer.write(0, 2);
        config.writeBytes(layer.bytes());
        var packets = new ArrayList<Packet>();
        var mbWidth = width / 16;
        var mbHeight = height / 16;
        var addressBits = 32 - Integer.numberOfLeadingZeros(mbWidth * mbHeight);
        var chromaWidth = chroma == 3 ? 16 : 8;
        var chromaHeight = chroma == 1 ? 8 : 16;
        var chromaBlocks = (chromaWidth / 8) * (chromaHeight / 8);
        for (var frame = 0; frame < frames; frame++) {
            var vop = new ByteArrayOutputStream();
            startCode(vop, 0xB6);
            var header = new BitWriter(false);
            for (var i = 0; i < 4; i++) {
                header.write(0xFFFF, 16);
                header.write(1, 1);
            }
            header.write(0, 4);
            header.write(frame, 10);
            header.write(3, 2);
            header.write(0, 2);
            header.write(1, 1);
            header.write(1, 1);
            header.write(0, 3);
            header.write(0, 1);
            header.write(interlaced ? 0 : 1, 1);
            header.write(0, 4);
            header.write(0, 1);
            header.write(0, 2);
            vop.writeBytes(header.bytes());
            for (var row = 0; row < mbHeight; row++) {
                startCode(vop, 0xB7);
                var slice = new BitWriter(false);
                slice.write(row * mbWidth, addressBits);
                slice.write(6, 5);
                slice.write(0, 1);
                var predictor = new int[]{1 << (bits - 1), 1 << (bits - 1), 1 << (bits - 1)};
                for (var column = 0; column < mbWidth; column++) {
                    var mb = row * mbWidth + column + frame;
                    if ((mb & 1) == 0) {
                        slice.write(1, 1);
                        slice.write(1, 1);
                        if (interlaced) {
                            slice.write(mb >> 1 & 1, 1);
                        }
                        for (var block = 0; block < 4 + 2 * chromaBlocks; block++) {
                            var component = block < 4 ? 0 : 1 + (block & 1);
                            var target = (1 << (bits - 1)) + ((mb * 5 + block * 3) % 7 - 3) * 4;
                            var diff = Math.max(-7, Math.min(7, target - predictor[component]));
                            predictor[component] += diff;
                            var size = diff == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(Math.abs(diff));
                            slice.code((component == 0 ? DC_LUMA : DC_CHROMA)[size]);
                            if (size > 0) {
                                slice.write(diff > 0 ? diff : diff + (1 << size) - 1, size);
                            }
                            if (((mb + block) & 1) == 0) {
                                slice.write(0b11, 2);
                                slice.write(block & 1, 1);
                                slice.write(0b00101, 5);
                            } else {
                                slice.write(0b0101, 4);
                            }
                        }
                    } else {
                        slice.write(0, 1);
                        slice.write(1, 1);
                        slice.write(frame & 1, 1);
                        for (var component = 0; component < 3; component++) {
                            var count = component == 0 ? 256 : chromaWidth * chromaHeight;
                            var mean = (1 << (bits - 1)) + component * 8;
                            slice.write(mean, bits);
                            slice.write(2, 4);
                            for (var i = 0; i < count; i++) {
                                var residual = (i * 7 + mb + component) % 9;
                                for (var p = residual >> 2; p > 0; p--) {
                                    slice.write(0, 1);
                                }
                                slice.write(1, 1);
                                slice.write(residual & 3, 2);
                            }
                            predictor[component] = mean;
                        }
                    }
                }
                vop.writeBytes(slice.bytes());
            }
            packets.add(new Packet(1, vop.toByteArray(), frame, frame, 1, true));
        }
        return new Stream(config.toByteArray(), packets);
    }

    /**
     * Writes a start code.
     *
     * @param out the output
     * @param code the start code value
     */
    private static void startCode(ByteArrayOutputStream out, int code) {
        out.write(0);
        out.write(0);
        out.write(1);
        out.write(code);
    }
}
