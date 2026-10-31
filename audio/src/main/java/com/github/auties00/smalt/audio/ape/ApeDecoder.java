/* Ports Monkey's Audio SDK 13.45 (Source/MACLib/APEDecompressCore.cpp, APEHeader.cpp, Prepare.cpp,
   FloatTransform.cpp, Old/UnMACOld.cpp, Old/APEDecompressCoreOld.cpp). Copyright (c) 2000 - 2026 Matthew T.
   Ashland; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.ape;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.DoubleAudioBuffer;
import com.github.auties00.smalt.audio.IntegerAudioBuffer;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/**
 * Decodes Monkey's Audio ({@code .ape}) files of every compression level (fast to insane) and of file versions 3.20
 * to 3.92 and 3.93 onward: integer samples of 8, 16, 24 and 32 bits in any number of channels, and 32-bit
 * floating-point samples.
 *
 * <p>Packets and the configuration are as {@link Codec#APE} describes. The decoded integers are bit-identical to
 * those of the Monkey's Audio 13.45 library ({@link #decodeIntegers(Packet, Consumer)}), and are scaled to floats by
 * {@code 2^-(bits - 1)}; floating-point streams are output unchanged and have no integer output. Every frame is
 * checked against its CRC (or the checksum of files before version 3.80): a frame failing it, a corrupt bitstream
 * or a sample out of range raise {@link MediaException.Malformed}. A 24-bit file that the encoders of versions 5.01
 * to 8.51 wrote with their interim prediction is recognized by its first frame failing the check and decoded with
 * that prediction from then on, as the library does. Channels are in the order of the file, the default layout of
 * their count.
 *
 * <p>Instances are not thread-safe.
 */
public final class ApeDecoder implements AudioDecoder {
    private static final int FLAG_8_BIT = 1;
    private static final int FLAG_CRC = 2;
    private static final int FLAG_24_BIT = 8;
    private static final int FLAG_FLOATING_POINT = 1 << 12;

    private static final int SILENCE_LEFT = 1;
    private static final int SILENCE_RIGHT = 2;
    private static final int PSEUDO_STEREO = 4;

    private final int version;

    private final int level;

    private final int flags;

    private final int channels;

    private final int bits;

    private final int sampleRate;

    /**
     * The largest number of samples per channel a frame holds, from the header.
     */
    private final int blocksPerFrame;

    private final MediaLimits limits;

    private final ChannelLayout layout;

    private final boolean floating;

    private final ApeBits reader;

    private ApePredictor[] predictors;

    private final ApeBits.State[] states;

    private boolean interim;

    private final CRC32 crc = new CRC32();

    private int[][] planes = new int[0][];

    private int frames;

    private byte[] bytes = new byte[0];

    private int[] dataX = new int[0];

    private int[] dataY = new int[0];

    private int[] temp = new int[0];

    private short[] adapt = new short[0];

    private short[] shorts = new short[0];

    private final int[] values;

    private final long[] coefficientsA = new long[64];

    private final long[] coefficientsB = new long[64];

    private AudioBuffer floats;

    private IntegerAudioBuffer integers;

    private DoubleAudioBuffer doubles;

    /**
     * Constructs a decoder for a Monkey's Audio track with the {@link AudioDecoderOptions#defaults()}.
     *
     * @param track the track; its codec must be {@link Codec#APE}
     * @throws IllegalArgumentException if the track is not a Monkey's Audio track
     * @throws MediaException.Malformed if the configuration is not a Monkey's Audio header
     * @throws MediaException.Unsupported if the header names an unsupported file version or sample format
     */
    public ApeDecoder(AudioTrack track) {
        this(track, AudioDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a Monkey's Audio track.
     *
     * <p>The options' limits apply; the decoder outputs the stream's own rate.
     *
     * @param track the track; its codec must be {@link Codec#APE}
     * @param options the decoding options
     * @throws IllegalArgumentException if the track is not a Monkey's Audio track
     * @throws MediaException.Malformed if the configuration is not a Monkey's Audio header
     * @throws MediaException.Unsupported if the header names an unsupported file version or sample format
     * @throws MediaException.LimitExceeded if the channel count exceeds the limits of {@code options}
     */
    public ApeDecoder(AudioTrack track, AudioDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.APE) {
            throw new IllegalArgumentException("not a Monkey's Audio track: " + track.codec());
        }
        var config = track.codecConfig().orElseThrow(
                () -> new MediaException.Malformed("Monkey's Audio track without a header"));
        var header = Header.parse(config);
        this.version = header.version;
        this.level = header.level;
        this.flags = header.flags;
        this.channels = header.channels;
        this.bits = header.bits;
        this.sampleRate = header.sampleRate;
        this.blocksPerFrame = header.blocksPerFrame;
        this.limits = options.limits();
        LimitChecks.checkChannels(options.limits(), channels);
        if (version < 3930 && version > 3920) {
            throw new MediaException.Unsupported("ape", "Monkey's Audio file version " + version);
        }
        if (version >= 3891 && version < 3900) {
            throw new MediaException.Unsupported("ape", "Monkey's Audio file version " + version);
        }
        if (version < 3930 && channels > 2) {
            throw new MediaException.Malformed("Monkey's Audio file version " + version + " with " + channels
                    + " channels");
        }
        if (bits != 8 && bits != 16 && bits != 24 && bits != 32 || version < 3930 && bits == 32) {
            throw new MediaException.Unsupported("ape", "Monkey's Audio samples of " + bits + " bits");
        }
        this.floating = (flags & FLAG_FLOATING_POINT) != 0;
        if (floating && bits != 32) {
            throw new MediaException.Malformed("Monkey's Audio floating point samples of " + bits + " bits");
        }
        if (level != 1000 && level != 2000 && level != 3000 && level != 4000
                && (level != 5000 || version < 3950)) {
            throw new MediaException.Malformed("Monkey's Audio compression level " + level);
        }
        this.layout = track.layout().filter(l -> l.channels() == channels).orElse(ChannelLayout.defaultFor(channels));
        this.reader = new ApeBits(version);
        this.states = new ApeBits.State[channels];
        for (var c = 0; c < channels; c++) {
            states[c] = new ApeBits.State();
        }
        this.values = new int[channels];
        if (version >= 3930) {
            predictors = createPredictors(false);
        }
    }

    private ApePredictor[] createPredictors(boolean interimPrediction) {
        var created = new ApePredictor[channels];
        for (var c = 0; c < channels; c++) {
            created[c] = ApePredictor.of(version, level, bits, interimPrediction);
        }
        return created;
    }

    /**
     * The fields of a Monkey's Audio header.
     */
    private record Header(int version, int level, int flags, int channels, int bits, int sampleRate,
                          int blocksPerFrame) {
        static Header parse(byte[] config) {
            if (config.length < 32 || config[0] != 'M' || config[1] != 'A' || config[2] != 'C'
                    || config[3] != ' ' && config[3] != 'F') {
                throw new MediaException.Malformed("not a Monkey's Audio header");
            }
            var version = u16(config, 4);
            if (version >= 3980) {
                var descriptorBytes = u32(config, 8);
                if (descriptorBytes < 8 || descriptorBytes + 24 > config.length) {
                    throw new MediaException.Malformed("Monkey's Audio descriptor of " + descriptorBytes + " bytes");
                }
                var at = (int) descriptorBytes;
                var level = u16(config, at);
                var flags = u16(config, at + 2);
                var blocks = u32(config, at + 4);
                var bits = u16(config, at + 16);
                var channels = u16(config, at + 18);
                var rate = u32(config, at + 20);
                return checked(version, level, flags, channels, bits, rate, blocks);
            }
            var level = u16(config, 6);
            var flags = u16(config, 8);
            var channels = u16(config, 10);
            var rate = u32(config, 12);
            var bits = (flags & FLAG_8_BIT) != 0 ? 8 : (flags & FLAG_24_BIT) != 0 ? 24 : 16;
            var blocks = version >= 3950 ? 73728 * 4 : version >= 3900 || version >= 3800 && level == 4000 ? 73728
                    : 9216;
            return checked(version, level, flags, channels, bits, rate, blocks);
        }

        private static Header checked(int version, int level, int flags, int channels, int bits, long rate,
                                      long blocks) {
            if (channels < 1 || channels > 32) {
                throw new MediaException.Malformed("Monkey's Audio file of " + channels + " channels");
            }
            if (rate <= 0 || rate > Integer.MAX_VALUE) {
                throw new MediaException.Malformed("Monkey's Audio sample rate " + rate);
            }
            if (blocks <= 0 || blocks > 10_000_000L) {
                throw new MediaException.Malformed("Monkey's Audio frames of " + blocks + " samples");
            }
            return new Header(version, level, flags, channels, bits, (int) rate, (int) blocks);
        }
    }

    private static int u16(byte[] data, int at) {
        return (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8;
    }

    private static long u32(byte[] data, int at) {
        return (data[at] & 0xFFL) | (data[at + 1] & 0xFFL) << 8 | (data[at + 2] & 0xFFL) << 16
                | (data[at + 3] & 0xFFL) << 24;
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public ChannelLayout layout() {
        return layout;
    }

    @Override
    public boolean supportsIntegerOutput() {
        return !floating;
    }

    @Override
    public int bitsPerSample() {
        return floating ? 0 : bits;
    }

    @Override
    public boolean supportsDoubleOutput() {
        return true;
    }

    /**
     * Decodes one packet into {@link #planes}, setting {@link #frames}.
     */
    private void decodePacket(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        var data = packet.data();
        if (data.length < 8) {
            throw new MediaException.Malformed("Monkey's Audio packet of " + data.length + " bytes");
        }
        var blocks = u32(data, 0);
        var skipBits = u32(data, 4);
        if (blocks <= 0 || blocks > blocksPerFrame || skipBits > 31) {
            throw new MediaException.Malformed("Monkey's Audio frame of " + blocks + " samples");
        }
        var count = (int) blocks;
        if (planes.length != channels || planes[0].length < count) {
            LimitChecks.checkAllocation(limits, (long) count * channels * (4 + bits / 8) + 3L * 4 * (count + 16)
                    + 4L * (count + 16));
            planes = new int[channels][count];
            bytes = new byte[count * channels * (bits / 8)];
            dataX = new int[count + 16];
            dataY = new int[count + 16];
            temp = new int[count + 16];
            adapt = new short[count + 16];
            shorts = new short[count + 16];
        }
        frames = 0;
        if (version >= 3930) {
            decodeCurrent(data, count, skipBits);
        } else {
            decodeOld(data, count, skipBits);
        }
        frames = count;
    }

    private void decodeCurrent(byte[] data, int count, long skipBits) {
        while (true) {
            try {
                reader.load(data, 8, data.length - 8, skipBits);
                if (decodeFrame(count)) {
                    return;
                }
            } catch (MediaException.Malformed exception) {
                if (interim || bits != 24) {
                    throw exception;
                }
                interim = true;
                predictors = createPredictors(true);
                continue;
            } catch (RuntimeException exception) {
                throw new MediaException.Malformed("invalid Monkey's Audio frame", exception);
            }
            if (interim || bits != 24) {
                throw new MediaException.Malformed("Monkey's Audio frame failed its CRC");
            }
            interim = true;
            predictors = createPredictors(true);
        }
    }

    /**
     * Decodes a frame of version 3.930 or later.
     *
     * @return whether its CRC matched
     */
    private boolean decodeFrame(int count) {
        var stored = reader.bits(32);
        var special = 0L;
        if ((stored & 0x80000000L) != 0) {
            special = reader.bits(32);
        }
        stored &= 0x7FFFFFFFL;
        for (var predictor : predictors) {
            predictor.flush();
        }
        for (var state : states) {
            state.flush();
        }
        reader.startRange();
        var lastX = 0;
        if (channels > 2) {
            for (var i = 0; i < count; i++) {
                for (var c = 0; c < channels; c++) {
                    values[c] = predictors[c].decompress(reader.rangeValue(states[c]), 0);
                }
                unprepare(i);
            }
        } else if (channels == 2) {
            if ((special & SILENCE_LEFT) != 0 && (special & SILENCE_RIGHT) != 0) {
                for (var i = 0; i < count; i++) {
                    values[0] = 0;
                    values[1] = 0;
                    unprepare(i);
                }
            } else if ((special & PSEUDO_STEREO) != 0) {
                for (var i = 0; i < count; i++) {
                    values[0] = predictors[0].decompress(reader.rangeValue(states[0]), 0);
                    values[1] = 0;
                    unprepare(i);
                }
            } else if (version >= 3950) {
                for (var i = 0; i < count; i++) {
                    var y = reader.rangeValue(states[1]);
                    var x = reader.rangeValue(states[0]);
                    var decodedY = predictors[1].decompress(y, lastX);
                    var decodedX = predictors[0].decompress(x, decodedY);
                    lastX = decodedX;
                    values[0] = decodedX;
                    values[1] = decodedY;
                    unprepare(i);
                }
            } else {
                for (var i = 0; i < count; i++) {
                    values[0] = predictors[0].decompress(reader.rangeValue(states[0]), 0);
                    values[1] = predictors[1].decompress(reader.rangeValue(states[1]), 0);
                    unprepare(i);
                }
            }
        } else {
            if ((special & SILENCE_LEFT) != 0) {
                for (var i = 0; i < count; i++) {
                    values[0] = 0;
                    unprepare(i);
                }
            } else {
                for (var i = 0; i < count; i++) {
                    values[0] = predictors[0].decompress(reader.rangeValue(states[0]), 0);
                    unprepare(i);
                }
            }
        }
        reader.finishRange();
        crc.reset();
        crc.update(bytes, 0, count * channels * (bits / 8));
        return (crc.getValue() >>> 1) == stored;
    }

    private static void checkShort(int first, int second) {
        if (first < -32768 || first > 32767 || second < -32768 || second > 32767) {
            throw new MediaException.Malformed("Monkey's Audio sample out of the 16-bit range");
        }
    }

    /**
     * Converts the decoded values of one block to samples ({@code CPrepare::Unprepare}).
     */
    private void unprepare(int index) {
        if (bits == 32) {
            if (channels == 2) {
                var first = values[0] - values[1] / 2;
                store(0, index, first);
                store(1, index, first + values[1]);
            } else {
                for (var c = 0; c < channels; c++) {
                    store(c, index, values[c]);
                }
            }
            return;
        }
        if (channels > 2) {
            if (bits == 8) {
                for (var c = 0; c < channels; c++) {
                    store(c, index, values[c]);
                }
                return;
            }
            var check = bits == 16;
            if (channels == 4) {
                pair(index, 0, check);
                pair(index, 2, check);
            } else if (channels >= 6) {
                pair(index, 0, check);
                if (check) {
                    checkShort(values[2], values[3]);
                }
                store(2, index, values[2]);
                store(3, index, values[3]);
                pair(index, 4, check);
                if (channels >= 8) {
                    pair(index, 6, check);
                }
                for (var c = channels == 7 ? 6 : 8; c < channels; c++) {
                    store(c, index, values[c]);
                }
            } else {
                for (var c = 0; c < channels; c++) {
                    store(c, index, values[c]);
                }
            }
            return;
        }
        if (channels == 2) {
            pair(index, 0, bits == 16);
        } else {
            store(0, index, values[0]);
        }
    }

    private void pair(int index, int channel, boolean check) {
        var first = values[channel] - values[channel + 1] / 2;
        var second = first + values[channel + 1];
        if (check) {
            checkShort(first, second);
        }
        store(channel, index, first);
        store(channel + 1, index, second);
    }

    /**
     * Stores a sample, truncated to the stream's size, and its bytes for the CRC.
     */
    private void store(int channel, int index, int value) {
        var size = bits / 8;
        var at = (index * channels + channel) * size;
        switch (size) {
            case 1 -> {
                var stored = (byte) (value + 128);
                bytes[at] = stored;
                planes[channel][index] = (byte) value;
            }
            case 2 -> {
                bytes[at] = (byte) value;
                bytes[at + 1] = (byte) (value >> 8);
                planes[channel][index] = (short) value;
            }
            case 3 -> {
                bytes[at] = (byte) value;
                bytes[at + 1] = (byte) (value >> 8);
                bytes[at + 2] = (byte) (value >> 16);
                planes[channel][index] = value << 8 >> 8;
            }
            default -> {
                bytes[at] = (byte) value;
                bytes[at + 1] = (byte) (value >> 8);
                bytes[at + 2] = (byte) (value >> 16);
                bytes[at + 3] = (byte) (value >> 24);
                planes[channel][index] = value;
            }
        }
    }

    private void decodeOld(byte[] data, int count, long skipBits) {
        try {
            reader.load(data, 8, data.length - 8, skipBits);
            var special = 0L;
            long stored;
            if ((flags & FLAG_CRC) == 0) {
                stored = version < 3900 ? reader.riceUnsigned(30) : 0;
                if (stored == 0) {
                    special = SILENCE_LEFT | SILENCE_RIGHT;
                }
            } else {
                stored = reader.bits(32);
                if (version > 3820) {
                    if ((stored & 0x80000000L) != 0) {
                        special = reader.bits(32);
                    }
                    stored &= 0x7FFFFFFFL;
                }
            }
            if (channels == 2) {
                if ((special & SILENCE_LEFT) != 0 && (special & SILENCE_RIGHT) != 0) {
                    java.util.Arrays.fill(dataX, 0, count, 0);
                    java.util.Arrays.fill(dataY, 0, count, 0);
                } else if ((special & PSEUDO_STEREO) != 0) {
                    decodeOldChannel(dataX, count);
                    java.util.Arrays.fill(dataY, 0, count, 0);
                } else {
                    decodeOldChannel(dataX, count);
                    decodeOldChannel(dataY, count);
                }
            } else if ((special & SILENCE_LEFT) != 0) {
                java.util.Arrays.fill(dataX, 0, count, 0);
            } else {
                decodeOldChannel(dataX, count);
            }
            for (var i = 0; i < count; i++) {
                if (channels == 2) {
                    var first = dataX[i] - dataY[i] / 2;
                    storeOld(0, i, first);
                    storeOld(1, i, bits == 16 ? (short) first + dataY[i] : bits == 8 ? oldByte(first) + dataY[i]
                            : first + dataY[i]);
                } else {
                    storeOld(0, i, dataX[i]);
                }
            }
            crc.reset();
            crc.update(bytes, 0, count * channels * (bits / 8));
            var computed = crc.getValue();
            if (version > 3820) {
                computed >>>= 1;
            }
            if ((flags & FLAG_CRC) == 0) {
                var checksum = 0L;
                for (var i = 0; i < count; i++) {
                    if (channels == 2) {
                        var first = dataX[i] - dataY[i] / 2;
                        var second = first + dataY[i];
                        checksum += Math.abs((long) first) + Math.abs((long) second);
                    } else {
                        checksum += Math.abs((long) dataX[i]);
                    }
                }
                computed = checksum & 0xFFFFFFFFL;
            }
            if (computed != stored) {
                throw new MediaException.Malformed("Monkey's Audio frame failed its checksum");
            }
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid Monkey's Audio frame", exception);
        }
    }

    /**
     * Returns the byte an 8-bit sample of the first channel was stored as before adding the second channel's
     * difference, as the versions before 3.830 store unsigned samples.
     */
    private int oldByte(int first) {
        return version > 3830 ? (first + 128) & 0xFF : first & 0xFF;
    }

    /**
     * Stores a sample of a file before version 3.930, whose 8-bit samples before version 3.830 are unsigned bytes.
     */
    private void storeOld(int channel, int index, int value) {
        if (bits == 8) {
            var size = 1;
            var at = (index * channels + channel) * size;
            int stored;
            if (channel == 1) {
                stored = value & 0xFF;
            } else {
                stored = version > 3830 ? (value + 128) & 0xFF : value & 0xFF;
            }
            bytes[at] = (byte) stored;
            planes[channel][index] = stored - 128;
            return;
        }
        store(channel, index, value);
    }

    private void decodeOldChannel(int[] target, int count) {
        var coefficientCount = -1;
        if (level == 4000 && version < 3800) {
            int countBits;
            int valueBits;
            if (version < 3320) {
                countBits = 4;
                valueBits = 6;
            } else if (version < 3600) {
                countBits = 3;
                valueBits = 5;
            } else {
                countBits = 3;
                valueBits = 6;
            }
            coefficientCount = (int) reader.bits(countBits);
            for (var z = 0; z <= coefficientCount; z++) {
                coefficientsA[z] = reader.bits(valueBits);
                coefficientsB[z] = reader.bits(valueBits);
            }
        }
        if (version < 3860) {
            reader.riceArrayOld(target, count);
        } else if (version <= 3890) {
            reader.riceArray(target, count);
        } else {
            reader.rangeArray(target, count);
        }
        ApeAntiPredictors.apply(version, level, target, temp, count, coefficientCount, coefficientsA, coefficientsB,
                adapt, shorts);
    }

    private static float floatOf(int bits) {
        var out = bits & 0xC3FFFFFF;
        out |= ~(bits & 0x3C000000) ^ 0xC3FFFFFF;
        if ((out & 0x80000000) != 0) {
            out = ~out | 0x80000000;
        }
        return Float.intBitsToFloat(out);
    }

    @Override
    public void decode(Packet packet, Consumer<AudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        decodePacket(packet);
        if (floats == null || floats.capacity() < frames) {
            floats = new AudioBuffer(layout, sampleRate, frames);
        }
        var scale = Math.scalb(1f, 1 - bits);
        for (var c = 0; c < channels; c++) {
            var source = planes[c];
            var target = floats.plane(c);
            if (floating) {
                for (var i = 0; i < frames; i++) {
                    target[i] = floatOf(source[i]);
                }
            } else {
                for (var i = 0; i < frames; i++) {
                    target[i] = source[i] * scale;
                }
            }
        }
        floats.setFrames(frames);
        output.accept(floats);
    }

    @Override
    public void flush(Consumer<AudioBuffer> output) {
        Objects.requireNonNull(output, "output");
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.Malformed if the packet is corrupt
     * @throws MediaException.Unsupported if the packet uses a coding tool this decoder lacks
     */
    @Override
    public void decodeIntegers(Packet packet, Consumer<IntegerAudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        if (floating) {
            throw new UnsupportedOperationException("floating-point Monkey's Audio has no integer output");
        }
        decodePacket(packet);
        if (integers == null || integers.capacity() < frames) {
            integers = new IntegerAudioBuffer(layout, sampleRate, bits, frames);
        }
        for (var c = 0; c < channels; c++) {
            System.arraycopy(planes[c], 0, integers.plane(c), 0, frames);
        }
        integers.setFrames(frames);
        output.accept(integers);
    }

    @Override
    public void flushIntegers(Consumer<IntegerAudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        if (floating) {
            throw new UnsupportedOperationException("floating-point Monkey's Audio has no integer output");
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.Malformed if the packet is corrupt
     * @throws MediaException.Unsupported if the packet uses a coding tool this decoder lacks
     */
    @Override
    public void decodeDoubles(Packet packet, Consumer<DoubleAudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        decodePacket(packet);
        if (doubles == null || doubles.capacity() < frames) {
            doubles = new DoubleAudioBuffer(layout, sampleRate, frames);
        }
        var scale = Math.scalb(1.0, 1 - bits);
        for (var c = 0; c < channels; c++) {
            var source = planes[c];
            var target = doubles.plane(c);
            for (var i = 0; i < frames; i++) {
                target[i] = floating ? floatOf(source[i]) : source[i] * scale;
            }
        }
        doubles.setFrames(frames);
        output.accept(doubles);
    }

    @Override
    public void flushDoubles(Consumer<DoubleAudioBuffer> output) {
        Objects.requireNonNull(output, "output");
    }
}
