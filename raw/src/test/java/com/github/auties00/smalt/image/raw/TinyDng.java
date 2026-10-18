package com.github.auties00.smalt.image.raw;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.Deflater;

/**
 * Writes small DNG files for the tests: a single-strip CFA or linear raw image, uncompressed,
 * lossless JPEG or Deflate coded, with the level, crop, color and opcode tags a test asks for.
 */
public final class TinyDng {
    public int width = 64;
    public int height = 48;
    public int samplesPerPixel = 1;
    public int bits = 16;
    public int[] samples;
    public float[] floats;
    public int floatBytes = 4;
    public int compression = 1;
    public int predictor = 1;
    public String cfa = "RGGB";
    public int[] planeColors;
    public double[] black;
    public int[] blackRepeat;
    public double[] blackDeltaH;
    public double[] blackDeltaV;
    public double white = -1;
    public int[] linearization;
    public int[] activeArea;
    public int[] crop;
    public double[] scale;
    public double[] colorMatrix = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    public double[] neutral = {1, 1, 1};
    public double[] cameraCalibration;
    public String cameraSignature;
    public String profileSignature;
    public byte[] opcodes1;
    public byte[] opcodes2;
    public byte[] opcodes3;
    public int orientation = 1;
    public int jpegComponents = 1;
    public byte[] stripData;

    private record Entry(int tag, int type, int count, byte[] value) {
    }

    private final List<Entry> entries = new ArrayList<>();

    public static TinyDng ramp(int width, int height) {
        var dng = new TinyDng();
        dng.width = width;
        dng.height = height;
        dng.samples = new int[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                dng.samples[y * width + x] = (x * 997 + y * 613 + (x * y) % 251) & 0xFFFF;
            }
        }
        return dng;
    }

    public byte[] write() {
        entries.clear();
        var data = stripData != null ? stripData : pixelData();
        int32(254, 0);
        int32(256, width);
        int32(257, height);
        var bitsValues = new int[samplesPerPixel];
        java.util.Arrays.fill(bitsValues, floats != null ? floatBytes * 8 : bits);
        shorts(258, bitsValues);
        shorts(259, compression);
        shorts(262, samplesPerPixel == 1 && cfa != null ? 32803 : 34892);
        shorts(274, orientation);
        int32(273, 0);
        shorts(277, samplesPerPixel);
        int32(278, height);
        int32(279, data.length);
        if (predictor != 1) {
            shorts(317, predictor);
        }
        if (floats != null) {
            shorts(339, 3);
        }
        if (samplesPerPixel == 1 && cfa != null) {
            shorts(33421, 2, 2);
            var codes = new byte[4];
            for (var i = 0; i < 4; i++) {
                codes[i] = (byte) "RGBCMYW".indexOf(cfa.charAt(i));
            }
            entries.add(new Entry(33422, 1, 4, codes));
            if (planeColors != null) {
                var planes = new byte[planeColors.length];
                for (var i = 0; i < planes.length; i++) {
                    planes[i] = (byte) planeColors[i];
                }
                entries.add(new Entry(50710, 1, planes.length, planes));
            }
        }
        entries.add(new Entry(50706, 1, 4, new byte[]{1, 4, 0, 0}));
        rationals(50721, colorMatrix, true);
        rationals(50728, neutral, false);
        shorts(50778, 21);
        if (cameraCalibration != null) {
            rationals(50723, cameraCalibration, true);
        }
        if (cameraSignature != null) {
            ascii(50931, cameraSignature);
        }
        if (profileSignature != null) {
            ascii(50932, profileSignature);
        }
        ascii(271, "Smalt");
        ascii(272, "Tiny");
        if (linearization != null) {
            shorts(50712, linearization);
        }
        if (blackRepeat != null) {
            shorts(50713, blackRepeat);
        }
        if (black != null) {
            rationals(50714, black, false);
        }
        if (blackDeltaH != null) {
            rationals(50715, blackDeltaH, true);
        }
        if (blackDeltaV != null) {
            rationals(50716, blackDeltaV, true);
        }
        if (white >= 0) {
            int32(50717, (long) white);
        }
        if (scale != null) {
            rationals(50718, scale, false);
        }
        if (crop != null) {
            longs(50719, crop[0], crop[1]);
            longs(50720, crop[2], crop[3]);
        }
        if (activeArea != null) {
            longs(50829, activeArea);
        }
        if (opcodes1 != null) {
            entries.add(new Entry(51008, 7, opcodes1.length, opcodes1));
        }
        if (opcodes2 != null) {
            entries.add(new Entry(51009, 7, opcodes2.length, opcodes2));
        }
        if (opcodes3 != null) {
            entries.add(new Entry(51022, 7, opcodes3.length, opcodes3));
        }
        entries.sort(Comparator.comparingInt(Entry::tag));
        var directorySize = 2 + 12 * entries.size() + 4;
        var valuesStart = 8 + directorySize;
        var values = new ByteArrayOutputStream();
        var directory = ByteBuffer.allocate(directorySize).order(ByteOrder.LITTLE_ENDIAN);
        directory.putShort((short) entries.size());
        var stripOffsetPosition = -1;
        for (var entry : entries) {
            directory.putShort((short) entry.tag).putShort((short) entry.type).putInt(entry.count);
            if (entry.tag == 273) {
                stripOffsetPosition = directory.position();
                directory.putInt(0);
            } else if (entry.value.length <= 4) {
                var inline = java.util.Arrays.copyOf(entry.value, 4);
                directory.put(inline);
            } else {
                directory.putInt(valuesStart + values.size());
                values.writeBytes(entry.value);
                if ((values.size() & 1) != 0) {
                    values.write(0);
                }
            }
        }
        directory.putInt(0);
        var stripOffset = valuesStart + values.size();
        directory.putInt(stripOffsetPosition, stripOffset);
        var file = new ByteArrayOutputStream();
        file.writeBytes(new byte[]{'I', 'I', 42, 0, 8, 0, 0, 0});
        file.writeBytes(directory.array());
        file.writeBytes(values.toByteArray());
        file.writeBytes(data);
        return file.toByteArray();
    }

    private byte[] pixelData() {
        var count = width * height * samplesPerPixel;
        if (floats != null) {
            var buffer = ByteBuffer.allocate(count * floatBytes).order(predictor == 1 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            for (var value : floats) {
                switch (floatBytes) {
                    case 2 -> buffer.putShort(Float.floatToFloat16(value));
                    case 4 -> buffer.putFloat(value);
                    default -> throw new IllegalStateException();
                }
            }
            var bytes = buffer.array();
            if (predictor == 3 || predictor == 34894 || predictor == 34895) {
                bytes = floatPredict(bytes, samplesPerPixel * pixelsBack());
            }
            return compression == 8 ? deflate(bytes) : bytes;
        }
        if (compression == 7) {
            var stride = width * samplesPerPixel;
            return Lj92Encoder.encode(samples, stride / jpegComponents, height, jpegComponents, bits, 1, 0, 0);
        }
        var stored = samples.clone();
        if (predictor == 2 || predictor == 34892 || predictor == 34893) {
            var stride = samplesPerPixel * pixelsBack();
            for (var y = 0; y < height; y++) {
                for (var x = width * samplesPerPixel - 1; x >= stride; x--) {
                    var i = y * width * samplesPerPixel + x;
                    stored[i] = (stored[i] - stored[i - stride]) & 0xFFFF;
                }
            }
        }
        byte[] bytes;
        if (bits == 16) {
            var buffer = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (var value : stored) {
                buffer.putShort((short) value);
            }
            bytes = buffer.array();
        } else {
            var out = new ByteArrayOutputStream();
            for (var y = 0; y < height; y++) {
                long buffer = 0;
                var buffered = 0;
                for (var x = 0; x < width * samplesPerPixel; x++) {
                    buffer = (buffer << bits) | (stored[y * width * samplesPerPixel + x] & ((1 << bits) - 1));
                    buffered += bits;
                    while (buffered >= 8) {
                        out.write((int) (buffer >>> (buffered - 8)));
                        buffered -= 8;
                    }
                }
                if (buffered > 0) {
                    out.write((int) (buffer << (8 - buffered)));
                }
            }
            bytes = out.toByteArray();
        }
        return compression == 8 ? deflate(bytes) : bytes;
    }

    private int pixelsBack() {
        return switch (predictor) {
            case 34892, 34894 -> 2;
            case 34893, 34895 -> 4;
            default -> 1;
        };
    }

    private byte[] floatPredict(byte[] bytes, int stride) {
        var rowBytes = width * samplesPerPixel * floatBytes;
        var count = width * samplesPerPixel;
        var out = new byte[bytes.length];
        for (var y = 0; y < height; y++) {
            var start = y * rowBytes;
            for (var s = 0; s < count; s++) {
                for (var b = 0; b < floatBytes; b++) {
                    out[start + b * count + s] = bytes[start + s * floatBytes + b];
                }
            }
            for (var i = start + rowBytes - 1; i >= start + stride; i--) {
                out[i] = (byte) (out[i] - out[i - stride]);
            }
        }
        return out;
    }

    private static byte[] deflate(byte[] bytes) {
        var deflater = new Deflater();
        deflater.setInput(bytes);
        deflater.finish();
        var out = new ByteArrayOutputStream();
        var buffer = new byte[8192];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }

    private void shorts(int tag, int... values) {
        var buffer = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (var value : values) {
            buffer.putShort((short) value);
        }
        entries.add(new Entry(tag, 3, values.length, buffer.array()));
    }

    private void int32(int tag, long value) {
        longs(tag, (int) value);
    }

    private void longs(int tag, int... values) {
        var buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (var value : values) {
            buffer.putInt(value);
        }
        entries.add(new Entry(tag, 4, values.length, buffer.array()));
    }

    private void rationals(int tag, double[] values, boolean signed) {
        var buffer = ByteBuffer.allocate(values.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (var value : values) {
            buffer.putInt((int) Math.round(value * 10000)).putInt(10000);
        }
        entries.add(new Entry(tag, signed ? 10 : 5, values.length, buffer.array()));
    }

    private void ascii(int tag, String value) {
        var bytes = (value + "\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        entries.add(new Entry(tag, 2, bytes.length, bytes));
    }

    /**
     * Builds opcode lists, big-endian as DNG stores them.
     */
    public static final class Opcodes {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private int count;

        public Opcodes add(int id, int flags, ByteBuffer parameters) {
            var params = java.util.Arrays.copyOf(parameters.array(), parameters.position());
            var header = ByteBuffer.allocate(16).putInt(id).putInt(0x01030000).putInt(flags).putInt(params.length);
            body.writeBytes(header.array());
            body.writeBytes(params);
            count++;
            return this;
        }

        public static ByteBuffer area(int top, int left, int bottom, int right, int plane, int planes, int rowPitch,
                                      int columnPitch, int extra) {
            return ByteBuffer.allocate(32 + extra).putInt(top).putInt(left).putInt(bottom).putInt(right).putInt(plane)
                    .putInt(planes).putInt(rowPitch).putInt(columnPitch);
        }

        public byte[] build() {
            var out = ByteBuffer.allocate(4 + body.size()).putInt(count);
            out.put(body.toByteArray());
            return out.array();
        }
    }
}
