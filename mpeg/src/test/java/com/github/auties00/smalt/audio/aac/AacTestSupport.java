package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Test-only container helpers: an ADTS parser and writer, a minimal MP4 sample extractor, a WAV
 * reader, and utilities to run the AAC decoder over a whole stream.
 */
final class AacTestSupport {
    private static final int[] RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};

    private AacTestSupport() {
    }

    /**
     * An elementary AAC stream: its configuration and raw data blocks, plus the leading samples
     * and presented length an MP4 edit list declares ({@code 0} and {@code -1} when there is none).
     */
    record Stream(byte[] config, List<byte[]> frames, int sampleRate, int channels, long skip, long presented) {
        Stream(byte[] config, List<byte[]> frames, int sampleRate, int channels) {
            this(config, frames, sampleRate, channels, 0, -1);
        }

        AudioTrack track() {
            return AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(Rational.timeBaseOf(sampleRate))
                    .codecConfig(config)
                    .sampleRate(sampleRate)
                    .build();
        }
    }

    /** Planar decoded samples. */
    record Decoded(float[][] planes, int sampleRate, ChannelLayout layout) {
        int frames() {
            return planes[0].length;
        }

        float[] interleaved() {
            var channels = planes.length;
            var out = new float[frames() * channels];
            for (var i = 0; i < frames(); i++) {
                for (var c = 0; c < channels; c++) {
                    out[i * channels + c] = planes[c][i];
                }
            }
            return out;
        }
    }

    static Stream parseAdts(byte[] data) {
        var frames = new ArrayList<byte[]>();
        var offset = 0;
        byte[] config = null;
        var rate = 0;
        var channels = 0;
        while (offset + 7 <= data.length) {
            if ((data[offset] & 0xFF) != 0xFF || (data[offset + 1] & 0xF0) != 0xF0) {
                throw new IllegalArgumentException("lost ADTS sync at " + offset);
            }
            var protectionAbsent = data[offset + 1] & 1;
            var profile = (data[offset + 2] >> 6) & 3;
            var rateIndex = (data[offset + 2] >> 2) & 15;
            var channelConfig = ((data[offset + 2] & 1) << 2) | ((data[offset + 3] >> 6) & 3);
            var length = ((data[offset + 3] & 3) << 11) | ((data[offset + 4] & 0xFF) << 3) | ((data[offset + 5] & 0xFF) >> 5);
            var blocks = (data[offset + 6] & 3) + 1;
            if (blocks != 1) {
                throw new IllegalArgumentException("multi-block ADTS frames are not supported by the test parser");
            }
            var header = protectionAbsent == 1 ? 7 : 9;
            if (config == null) {
                var objectType = profile + 1;
                config = new byte[]{(byte) (objectType << 3 | rateIndex >> 1), (byte) ((rateIndex & 1) << 7 | channelConfig << 3)};
                rate = RATES[rateIndex];
                channels = channelConfig;
            }
            var frame = new byte[length - header];
            System.arraycopy(data, offset + header, frame, 0, frame.length);
            frames.add(frame);
            offset += length;
        }
        return new Stream(config, frames, rate, channels);
    }

    static byte[] writeAdts(byte[] config, List<byte[]> frames) {
        var objectType = (config[0] & 0xFF) >> 3;
        var rateIndex = ((config[0] & 7) << 1) | ((config[1] & 0xFF) >> 7);
        var channelConfig = (config[1] >> 3) & 15;
        var out = new ByteArrayOutputStream();
        for (var frame : frames) {
            var length = frame.length + 7;
            out.write(0xFF);
            out.write(0xF1);
            out.write(((objectType - 1) << 6) | (rateIndex << 2) | (channelConfig >> 2));
            out.write(((channelConfig & 3) << 6) | (length >> 11));
            out.write((length >> 3) & 0xFF);
            out.write(((length & 7) << 5) | 0x1F);
            out.write(0xFC);
            out.writeBytes(frame);
        }
        return out.toByteArray();
    }

    static Stream parseMp4(byte[] data) {
        var moov = findBox(data, 0, data.length, "moov");
        if (moov == null) {
            throw new IllegalArgumentException("no moov");
        }
        for (var trak : findBoxes(data, moov[0], moov[1], "trak")) {
            var stbl = path(data, trak[0], trak[1], "mdia", "minf", "stbl");
            if (stbl == null) {
                continue;
            }
            var stsd = findBox(data, stbl[0], stbl[1], "stsd");
            var entry = stsd[0] + 8;
            var format = new String(data, entry + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (!format.equals("mp4a")) {
                continue;
            }
            var entrySize = u32(data, entry);
            var channels = u16(data, entry + 24);
            var mdhd = path(data, trak[0], trak[1], "mdia", "mdhd");
            var rate = data[mdhd[0]] == 1 ? u32(data, mdhd[0] + 20) : u32(data, mdhd[0] + 12);
            var esds = findBox(data, entry + 36, entry + entrySize, "esds");
            var config = decoderSpecificInfo(data, esds[0] + 4, esds[1]);
            var sizes = sampleSizes(data, findBox(data, stbl[0], stbl[1], "stsz"));
            var offsets = sampleOffsets(data, stbl, sizes.length);
            var frames = new ArrayList<byte[]>();
            for (var i = 0; i < sizes.length; i++) {
                var frame = new byte[sizes[i]];
                System.arraycopy(data, (int) offsets[i], frame, 0, sizes[i]);
                frames.add(frame);
            }
            var skip = 0L;
            var presented = -1L;
            var elst = path(data, trak[0], trak[1], "edts", "elst");
            var mvhd = findBox(data, moov[0], moov[1], "mvhd");
            if (elst != null && mvhd != null) {
                var movieScale = data[mvhd[0]] == 1 ? u32(data, mvhd[0] + 20) : u32(data, mvhd[0] + 12);
                var wide = data[elst[0]] == 1;
                var entries = u32(data, elst[0] + 4);
                var position = elst[0] + 8;
                for (var i = 0; i < entries; i++) {
                    long duration = wide ? ((long) u32(data, position) << 32) | (u32(data, position + 4) & 0xFFFFFFFFL)
                            : u32(data, position) & 0xFFFFFFFFL;
                    long mediaTime = wide ? ((long) u32(data, position + 8) << 32) | (u32(data, position + 12) & 0xFFFFFFFFL)
                            : u32(data, position + 4);
                    position += wide ? 20 : 12;
                    if (mediaTime >= 0) {
                        skip = mediaTime;
                        presented = duration * rate / movieScale;
                        break;
                    }
                }
            }
            return new Stream(config, frames, rate, channels, skip, presented);
        }
        throw new IllegalArgumentException("no mp4a track");
    }

    private static byte[] decoderSpecificInfo(byte[] data, int start, int end) {
        var position = start;
        while (position < end) {
            var tag = data[position++] & 0xFF;
            var length = 0;
            for (var i = 0; i < 4; i++) {
                var b = data[position++] & 0xFF;
                length = (length << 7) | (b & 0x7F);
                if ((b & 0x80) == 0) {
                    break;
                }
            }
            switch (tag) {
                case 3 -> {
                    position += 2;
                    var flags = data[position++] & 0xFF;
                    if ((flags & 0x80) != 0) {
                        position += 2;
                    }
                    if ((flags & 0x40) != 0) {
                        position += 1 + (data[position] & 0xFF);
                    }
                    if ((flags & 0x20) != 0) {
                        position += 2;
                    }
                }
                case 4 -> position += 13;
                case 5 -> {
                    var config = new byte[length];
                    System.arraycopy(data, position, config, 0, length);
                    return config;
                }
                default -> position += length;
            }
        }
        throw new IllegalArgumentException("no DecoderSpecificInfo");
    }

    private static int[] sampleSizes(byte[] data, int[] stsz) {
        var fixed = u32(data, stsz[0] + 4);
        var count = u32(data, stsz[0] + 8);
        var sizes = new int[count];
        for (var i = 0; i < count; i++) {
            sizes[i] = fixed != 0 ? fixed : u32(data, stsz[0] + 12 + 4 * i);
        }
        return sizes;
    }

    private static long[] sampleOffsets(byte[] data, int[] stbl, int samples) {
        var stco = findBox(data, stbl[0], stbl[1], "stco");
        var wide = false;
        if (stco == null) {
            stco = findBox(data, stbl[0], stbl[1], "co64");
            wide = true;
        }
        var chunkCount = u32(data, stco[0] + 4);
        var chunkOffsets = new long[chunkCount];
        for (var i = 0; i < chunkCount; i++) {
            chunkOffsets[i] = wide ? ((long) u32(data, stco[0] + 8 + 8 * i) << 32) | (u32(data, stco[0] + 12 + 8 * i) & 0xFFFFFFFFL)
                    : u32(data, stco[0] + 8 + 4 * i) & 0xFFFFFFFFL;
        }
        var stsc = findBox(data, stbl[0], stbl[1], "stsc");
        var entries = u32(data, stsc[0] + 4);
        var sizes = sampleSizes(data, findBox(data, stbl[0], stbl[1], "stsz"));
        var offsets = new long[samples];
        var sample = 0;
        for (var e = 0; e < entries; e++) {
            var firstChunk = u32(data, stsc[0] + 8 + 12 * e) - 1;
            var perChunk = u32(data, stsc[0] + 12 + 12 * e);
            var lastChunk = e + 1 < entries ? u32(data, stsc[0] + 8 + 12 * (e + 1)) - 1 : chunkCount;
            for (var chunk = firstChunk; chunk < lastChunk; chunk++) {
                var position = chunkOffsets[chunk];
                for (var i = 0; i < perChunk && sample < samples; i++) {
                    offsets[sample] = position;
                    position += sizes[sample];
                    sample++;
                }
            }
        }
        return offsets;
    }

    private static int[] path(byte[] data, int start, int end, String... names) {
        int[] box = {start, end};
        for (var name : names) {
            box = findBox(data, box[0], box[1], name);
            if (box == null) {
                return null;
            }
        }
        return box;
    }

    private static int[] findBox(byte[] data, int start, int end, String name) {
        var boxes = findBoxes(data, start, end, name);
        return boxes.isEmpty() ? null : boxes.getFirst();
    }

    /** Returns the payload ranges (after the header) of the boxes named {@code name}. */
    private static List<int[]> findBoxes(byte[] data, int start, int end, String name) {
        var result = new ArrayList<int[]>();
        var position = start;
        while (position + 8 <= end) {
            var size = u32(data, position) & 0xFFFFFFFFL;
            var type = new String(data, position + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            var header = 8;
            if (size == 1) {
                size = ((long) u32(data, position + 8) << 32) | (u32(data, position + 12) & 0xFFFFFFFFL);
                header = 16;
            } else if (size == 0) {
                size = end - position;
            }
            if (type.equals(name)) {
                var payload = position + header;
                result.add(new int[]{payload, (int) (position + size)});
            }
            position += (int) size;
        }
        return result;
    }

    private static int u32(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    /** Reads a PCM WAV file (16, 24 or 32 bit integer, or 32-bit float) into planes in [-1, 1]. */
    static float[][] readWav(byte[] data) {
        var position = 12;
        int channels = 0;
        int bits = 0;
        int format = 0;
        while (position + 8 <= data.length) {
            var id = new String(data, position, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            var size = ByteBuffer.wrap(data, position + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (id.equals("fmt ")) {
                var buffer = ByteBuffer.wrap(data, position + 8, size).order(ByteOrder.LITTLE_ENDIAN);
                format = buffer.getShort() & 0xFFFF;
                channels = buffer.getShort();
                buffer.getInt();
                buffer.getInt();
                buffer.getShort();
                bits = buffer.getShort();
            } else if (id.equals("data")) {
                var bytesPerSample = bits / 8;
                var frames = size / (bytesPerSample * channels);
                var planes = new float[channels][frames];
                var p = position + 8;
                for (var i = 0; i < frames; i++) {
                    for (var c = 0; c < channels; c++) {
                        planes[c][i] = switch (bits) {
                            case 16 -> (short) ((data[p] & 0xFF) | (data[p + 1] << 8)) / 32768f;
                            case 24 -> ((data[p] & 0xFF) | ((data[p + 1] & 0xFF) << 8) | (data[p + 2] << 16)) / 8388608f;
                            case 32 -> format == 3
                                    ? Float.intBitsToFloat(ByteBuffer.wrap(data, p, 4).order(ByteOrder.LITTLE_ENDIAN).getInt())
                                    : ByteBuffer.wrap(data, p, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() / 2147483648f;
                            default -> throw new IllegalArgumentException("unsupported bits " + bits);
                        };
                        p += bytesPerSample;
                    }
                }
                return planes;
            }
            position += 8 + size + (size & 1);
        }
        throw new IllegalArgumentException("no data chunk");
    }

    static Decoded decodeAll(Stream stream) {
        try (var decoder = new AacDecoder(stream.track())) {
            return decodeAll(decoder, stream.frames());
        }
    }

    static Decoded decodeAll(AacDecoder decoder, List<byte[]> frames) {
        var collected = new ArrayList<float[][]>();
        var layout = new ChannelLayout[1];
        var rate = new int[1];
        for (var frame : frames) {
            decoder.decode(new Packet(1, frame, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, true), buffer -> {
                layout[0] = buffer.layout();
                rate[0] = buffer.sampleRate();
                collected.add(copy(buffer));
            });
        }
        if (collected.isEmpty()) {
            return new Decoded(new float[decoder.layout().channels()][0], decoder.sampleRate(), decoder.layout());
        }
        var channels = collected.getFirst().length;
        var total = 0;
        for (var block : collected) {
            total += block[0].length;
        }
        var result = new float[channels][total];
        var position = 0;
        for (var block : collected) {
            for (var c = 0; c < channels; c++) {
                System.arraycopy(block[c], 0, result[c], position, block[c].length);
            }
            position += block[0].length;
        }
        return new Decoded(result, rate[0], layout[0]);
    }

    static float[][] copy(AudioBuffer buffer) {
        var result = new float[buffer.channels()][];
        for (var c = 0; c < buffer.channels(); c++) {
            result[c] = java.util.Arrays.copyOf(buffer.plane(c), buffer.frames());
        }
        return result;
    }

    static float[][] deinterleave(float[] interleaved, int channels) {
        var frames = interleaved.length / channels;
        var planes = new float[channels][frames];
        for (var i = 0; i < frames; i++) {
            for (var c = 0; c < channels; c++) {
                planes[c][i] = interleaved[i * channels + c];
            }
        }
        return planes;
    }

    /** Returns the SNR in dB of {@code actual} against {@code expected} over all channels. */
    static double snr(float[][] expected, float[][] actual) {
        double signal = 0;
        double noise = 0;
        for (var c = 0; c < expected.length; c++) {
            var length = Math.min(expected[c].length, actual[c].length);
            for (var i = 0; i < length; i++) {
                signal += (double) expected[c][i] * expected[c][i];
                var d = (double) expected[c][i] - actual[c][i];
                noise += d * d;
            }
        }
        return noise == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(signal / noise);
    }
}
