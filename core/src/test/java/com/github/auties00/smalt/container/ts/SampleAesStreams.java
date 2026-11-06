package com.github.auties00.smalt.container.ts;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Rewrites a clear transport stream as an HLS SAMPLE-AES one, with an encryptor written here from Apple's "MPEG-2
 * Stream Encryption Format for HTTP Live Streaming" independently of the muxer's, or rewrites it clear through the same
 * writer so that the two can be compared packet for packet. The input is a stream the transport stream muxer wrote:
 * one program, H.264 (0x1B), ADTS (0x0F), AC-3 (0x81) and E-AC-3 (0x87) streams, every audio PES packet holding whole
 * frames and every video PES packet whole NAL units.
 */
public final class SampleAesStreams {
    private static final int[] AC3_KBPS = {32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 448,
            512, 576, 640};

    private SampleAesStreams() {
    }

    /**
     * What a rewrite changes besides the encryption.
     *
     * @param injectSlices whether every video PES packet gets synthetic slices after its own, of the lengths at the
     * corners of the pattern and with emulation prevention bytes in every part of it
     * @param aacPriming the priming the AAC setup information declares
     * @param aacType the {@code audio_type} of the AAC setup information
     * @param aacConfig the {@code setup_data} of the AAC setup information, or {@code null} for the configuration of
     * the ADTS headers
     * @param ac3Priming the priming the AC-3 and E-AC-3 setup information declares
     * @param audioOnlyAac whether the AC-3 and E-AC-3 streams are dropped
     * @param eac3Dependent whether every E-AC-3 syncframe is followed by a dependent substream copy of it
     * @param adtsCrc whether ADTS headers carry a CRC word, making them 9 bytes
     */
    public record Setup(boolean injectSlices, int aacPriming, String aacType, byte[] aacConfig, int ac3Priming,
                        boolean audioOnlyAac, boolean eac3Dependent, boolean adtsCrc) {
        /**
         * {@return the setup that changes nothing}
         */
        public static Setup plain() {
            return new Setup(false, 0, "zaac", null, 0, false, false, false);
        }

        /**
         * {@return the setup that reaches the corner cases: injected slices, 9-byte ADTS headers, dependent E-AC-3
         * substreams and nonzero priming}
         */
        public static Setup corners() {
            return new Setup(true, 2112, "zaac", null, 256, false, true, true);
        }
    }

    private record Pes(int pid, int streamId, long pts, long dts, boolean randomAccess, byte[] payload) {
    }

    private record Entry(int type, int pid, byte[] descriptors) {
    }

    /**
     * Rewrites a clear transport stream.
     *
     * @param ts the stream the muxer wrote
     * @param key the key, or {@code null} to rewrite the stream clear
     * @param iv the initialization vector, or {@code null} to rewrite the stream clear
     * @param setup what else changes
     * @return the rewritten stream
     */
    public static byte[] rewrite(byte[] ts, byte[] key, byte[] iv, Setup setup) {
        return segments(ts, new byte[][]{key}, new byte[][]{iv}, setup).getFirst();
    }

    /**
     * Rewrites a clear transport stream as segments: its PES packets split into as many runs as there are keys, of
     * about the same number of packets, each run a segment that opens with the program tables and is encrypted with
     * its own key, as one stream whose continuity counters run on from one segment to the next.
     *
     * @param ts the stream the muxer wrote
     * @param keys the key of each segment, all {@code null} to rewrite the stream clear
     * @param ivs the initialization vector of each segment
     * @param setup what else changes
     * @return the segments
     */
    public static List<byte[]> segments(byte[] ts, byte[][] keys, byte[][] ivs, Setup setup) {
        var pmtPid = -1;
        var entries = new ArrayList<Entry>();
        var open = new HashMap<Integer, ByteArrayOutputStream>();
        var order = new ArrayList<Object[]>();
        for (var offset = 0; offset + 188 <= ts.length; offset += 188) {
            if (ts[offset] != 0x47) {
                throw new IllegalArgumentException("sync byte at " + offset);
            }
            var pid = ((ts[offset + 1] & 0x1F) << 8) | (ts[offset + 2] & 0xFF);
            var start = (ts[offset + 1] & 0x40) != 0;
            var control = (ts[offset + 3] >> 4) & 3;
            var position = offset + 4;
            var randomAccess = false;
            if (control == 2 || control == 3) {
                var length = ts[position] & 0xFF;
                randomAccess = length > 0 && (ts[position + 1] & 0x40) != 0;
                position += 1 + length;
            }
            if (control == 2 || position >= offset + 188) {
                continue;
            }
            if (pid == 0 && start) {
                var section = position + 1 + (ts[position] & 0xFF);
                pmtPid = ((ts[section + 10] & 0x1F) << 8) | (ts[section + 11] & 0xFF);
            } else if (pid == pmtPid && start && entries.isEmpty()) {
                var section = position + 1 + (ts[position] & 0xFF);
                var end = section + 3 + (((ts[section + 1] & 0x0F) << 8) | (ts[section + 2] & 0xFF)) - 4;
                var index = section + 12 + (((ts[section + 10] & 0x0F) << 8) | (ts[section + 11] & 0xFF));
                while (index < end) {
                    var length = ((ts[index + 3] & 0x0F) << 8) | (ts[index + 4] & 0xFF);
                    entries.add(new Entry(ts[index] & 0xFF, ((ts[index + 1] & 0x1F) << 8) | (ts[index + 2] & 0xFF),
                            Arrays.copyOfRange(ts, index + 5, index + 5 + length)));
                    index += 5 + length;
                }
            } else if (pid != 0 && pid != pmtPid && pid != 0x1FFF) {
                if (start) {
                    var buffer = new ByteArrayOutputStream();
                    open.put(pid, buffer);
                    order.add(new Object[]{pid, buffer, randomAccess});
                }
                var buffer = open.get(pid);
                if (buffer != null) {
                    buffer.write(ts, position, offset + 188 - position);
                }
            }
        }
        var types = new HashMap<Integer, Integer>();
        for (var entry : entries) {
            types.put(entry.pid(), entry.type());
        }
        var encrypt = Arrays.stream(keys).anyMatch(java.util.Objects::nonNull);
        var streams = new ArrayList<TsWriter.Stream>();
        for (var entry : entries) {
            var type = entry.type();
            if (setup.audioOnlyAac() && (type == 0x81 || type == 0x87)) {
                continue;
            }
            var descriptors = entry.descriptors();
            if (encrypt) {
                var encrypted = switch (type) {
                    case 0x1B -> 0xDB;
                    case 0x0F -> 0xCF;
                    case 0x81 -> 0xC1;
                    case 0x87 -> 0xC2;
                    default -> type;
                };
                descriptors = TsWriter.concat(descriptors, sampleAesDescriptors(type, setup, firstAdts(order, entry)));
                type = encrypted;
            }
            streams.add(new TsWriter.Stream(type, entry.pid(), descriptors));
        }
        var video = entries.stream().filter(entry -> entry.type() == 0x1B).mapToInt(Entry::pid).findFirst()
                .orElse(entries.getFirst().pid());
        var writer = new TsWriter();
        var starts = new ArrayList<Integer>();
        var injected = 0;
        for (var number = 0; number < order.size(); number++) {
            var segment = (int) ((long) number * keys.length / order.size());
            if (segment == starts.size()) {
                starts.add(writer.toByteArray().length);
                writer.pat(1, pmtPid, 0).pmt(pmtPid, 1, 0, video, new byte[0], streams);
            }
            var key = keys[segment];
            var iv = ivs[segment];
            var item = order.get(number);
            var pid = (int) item[0];
            var type = types.getOrDefault(pid, -1);
            if (setup.audioOnlyAac() && (type == 0x81 || type == 0x87)) {
                continue;
            }
            var pes = parsePes(pid, ((ByteArrayOutputStream) item[1]).toByteArray(), (boolean) item[2]);
            var payload = switch (type) {
                case 0x1B -> video(pes.payload(), setup.injectSlices() ? injected++ : -1, key, iv);
                case 0x0F -> adts(pes.payload(), setup.adtsCrc(), key, iv);
                case 0x81 -> ac3(pes.payload(), false, false, key, iv);
                case 0x87 -> ac3(pes.payload(), true, setup.eac3Dependent(), key, iv);
                default -> pes.payload();
            };
            writer.pes(pid, pes.streamId(), pes.pts(), pes.dts(), payload, pes.randomAccess());
        }
        var bytes = writer.toByteArray();
        if (starts.size() != keys.length) {
            throw new IllegalArgumentException(keys.length + " segments of " + order.size() + " PES packets");
        }
        var result = new ArrayList<byte[]>();
        for (var index = 0; index < starts.size(); index++) {
            result.add(Arrays.copyOfRange(bytes, starts.get(index), index + 1 < starts.size() ? starts.get(index + 1)
                    : bytes.length));
        }
        return result;
    }

    /**
     * Returns the first ADTS header of a PID's PES packets, or {@code null}.
     */
    private static byte[] firstAdts(List<Object[]> order, Entry entry) {
        if (entry.type() != 0x0F) {
            return null;
        }
        for (var item : order) {
            if ((int) item[0] == entry.pid()) {
                var pes = parsePes(entry.pid(), ((ByteArrayOutputStream) item[1]).toByteArray(), false);
                return Arrays.copyOf(pes.payload(), 7);
            }
        }
        return null;
    }

    private static byte[] sampleAesDescriptors(int type, Setup setup, byte[] adts) {
        var out = new ByteArrayOutputStream();
        var indicator = switch (type) {
            case 0x1B -> "zavc";
            case 0x0F -> "aacd";
            case 0x81 -> "ac3d";
            default -> "ec3d";
        };
        out.write(0x0F);
        out.write(4);
        out.writeBytes(ascii(indicator));
        if (type == 0x1B) {
            return out.toByteArray();
        }
        byte[] config;
        String audioType;
        int priming;
        if (type == 0x0F) {
            audioType = setup.aacType();
            priming = setup.aacPriming();
            var frequency = (adts[2] >> 2) & 0x0F;
            var channels = (adts[2] & 1) << 2 | (adts[3] >> 6) & 3;
            config = setup.aacConfig() != null ? setup.aacConfig() : new byte[]{
                    (byte) (((adts[2] >> 6 & 3) + 1) << 3 | frequency >> 1),
                    (byte) ((frequency & 1) << 7 | channels << 3)};
        } else {
            audioType = type == 0x81 ? "zac3" : "zec3";
            priming = setup.ac3Priming();
            config = new byte[type == 0x81 ? 3 : 5];
        }
        out.write(0x05);
        out.write(4 + 4 + 2 + 1 + 1 + config.length);
        out.writeBytes(ascii("apad"));
        out.writeBytes(ascii(audioType));
        out.write(priming >> 8);
        out.write(priming);
        out.write(1);
        out.write(config.length);
        out.writeBytes(config);
        return out.toByteArray();
    }

    private static byte[] ascii(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static Pes parsePes(int pid, byte[] pes, boolean randomAccess) {
        if (pes[0] != 0 || pes[1] != 0 || pes[2] != 1) {
            throw new IllegalArgumentException("a PES packet without a start code on PID " + pid);
        }
        var streamId = pes[3] & 0xFF;
        var timing = (pes[7] & 0xFF) >> 6;
        var headerLength = pes[8] & 0xFF;
        var pts = timing >= 2 ? timestamp(pes, 9) : -1;
        var dts = timing == 3 ? timestamp(pes, 14) : -1;
        var start = 9 + headerLength;
        var length = ((pes[4] & 0xFF) << 8) | (pes[5] & 0xFF);
        var end = length == 0 ? pes.length : Math.min(pes.length, 6 + length);
        return new Pes(pid, streamId, pts, dts, randomAccess, Arrays.copyOfRange(pes, start, end));
    }

    private static long timestamp(byte[] data, int offset) {
        return ((long) (data[offset] & 0x0E) << 29) | ((data[offset + 1] & 0xFFL) << 22)
                | ((data[offset + 2] & 0xFEL) << 14) | ((data[offset + 3] & 0xFFL) << 7)
                | ((data[offset + 4] & 0xFFL) >> 1);
    }

    /**
     * Rewrites the NAL units of a video PES payload behind four-byte start codes, encrypting the slices.
     */
    private static byte[] video(byte[] payload, int inject, byte[] key, byte[] iv) {
        var nals = new ArrayList<byte[]>();
        var starts = new ArrayList<Integer>();
        for (var index = 0; index + 2 < payload.length; index++) {
            if (payload[index] == 0 && payload[index + 1] == 0 && payload[index + 2] == 1) {
                starts.add(index + 3);
                index += 2;
            }
        }
        for (var number = 0; number < starts.size(); number++) {
            int start = starts.get(number);
            var end = number + 1 < starts.size() ? starts.get(number + 1) - 3 : payload.length;
            while (end > start && payload[end - 1] == 0) {
                end--;
            }
            nals.add(Arrays.copyOfRange(payload, start, end));
        }
        if (inject >= 0) {
            nals.addAll(syntheticSlices(inject));
        }
        var out = new ByteArrayOutputStream();
        for (var nal : nals) {
            out.writeBytes(new byte[]{0, 0, 0, 1});
            var type = nal[0] & 0x1F;
            out.writeBytes(key != null && (type == 1 || type == 5) && nal.length > 48 ? encryptSlice(nal, key, iv)
                    : nal);
        }
        return out.toByteArray();
    }

    /**
     * Returns slices that continue the access unit ({@code first_mb_in_slice} 1) at the lengths where the pattern
     * changes, with emulation prevention bytes in the leader, in encrypted and in skipped blocks, and a last
     * {@code cabac_zero_word}.
     */
    static List<byte[]> syntheticSlices(int seed) {
        var lengths = new int[]{20, 47, 48, 49, 50, 63, 64, 65, 191, 192, 193, 207, 208, 209, 224, 225, 368, 369,
                1000, 1501};
        var result = new ArrayList<byte[]>();
        var random = new Random(seed);
        for (var index = 0; index < 3; index++) {
            var length = lengths[(seed * 3 + index) % lengths.length];
            var nal = new byte[length];
            random.nextBytes(nal);
            nal[0] = 0x01;
            nal[1] = 0x40;
            for (var position = 4; position < length; position++) {
                if (nal[position - 2] == 0 && nal[position - 1] == 0 && (nal[position] & 0xFF) <= 3) {
                    nal[position] = 4;
                }
            }
            // emulation prevention bytes at chosen places: 00 00 03 0x, x from 0 to 3
            for (var at : new int[]{10, 30, 33, 40, 50, 60, 190, 200, length - 8}) {
                if (at >= 4 && at + 4 < length) {
                    if (nal[at - 1] == 0) {
                        nal[at - 1] = 0x11;
                    }
                    nal[at] = 0;
                    nal[at + 1] = 0;
                    nal[at + 2] = 3;
                    nal[at + 3] = (byte) random.nextInt(4);
                    if (at + 4 < length && nal[at + 3] == 0 && nal[at + 4] == 0) {
                        nal[at + 4] = 5;
                    }
                }
            }
            if ((seed + index) % 2 == 0 && length > 8) {
                // a cabac_zero_word at the end, escaped
                nal[length - 3] = 0;
                nal[length - 2] = 0;
                nal[length - 1] = 3;
                if (nal[length - 4] == 0) {
                    nal[length - 4] = 7;
                }
            } else {
                nal[length - 1] = (byte) 0x80;
            }
            for (var position = 2; position < length; position++) {
                if (nal[position - 2] == 0 && nal[position - 1] == 0 && (nal[position] & 0xFF) < 3) {
                    throw new IllegalStateException("a start code in a synthetic slice");
                }
            }
            result.add(nal);
        }
        return result;
    }

    /**
     * Encrypts a stored slice as Apple's {@code Encrypted_nal_unit()} defines it: the type byte and 31 bytes clear,
     * then while bytes remain an encrypted block when more than 16 remain and up to 144 clear bytes, one CBC chain
     * from the vector; then emulation prevention is applied over the whole unit.
     */
    static byte[] encryptSlice(byte[] nal, byte[] key, byte[] iv) {
        var copy = nal.clone();
        var blocks = new ArrayList<Integer>();
        var position = 32;
        while (copy.length - position > 0) {
            if (copy.length - position > 16) {
                blocks.add(position);
                position += 16;
            }
            position += Math.min(144, copy.length - position);
        }
        cbc(copy, blocks, key, iv);
        var out = new ByteArrayOutputStream();
        var zeros = 0;
        for (var value : copy) {
            if (zeros >= 2 && (value & 0xFF) <= 3) {
                out.write(3);
                zeros = 0;
            }
            out.write(value);
            zeros = value == 0 ? zeros + 1 : 0;
        }
        if (copy[copy.length - 1] == 0) {
            out.write(3);
        }
        return out.toByteArray();
    }

    /**
     * Encrypts, in place, the blocks at the given offsets as one AES-128 CBC chain started from the vector.
     */
    static void cbc(byte[] data, List<Integer> blocks, byte[] key, byte[] iv) {
        if (blocks.isEmpty()) {
            return;
        }
        var joined = new byte[16 * blocks.size()];
        for (var index = 0; index < blocks.size(); index++) {
            System.arraycopy(data, blocks.get(index), joined, 16 * index, 16);
        }
        try {
            var cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            var sealed = cipher.doFinal(joined);
            for (var index = 0; index < blocks.size(); index++) {
                System.arraycopy(sealed, 16 * index, data, blocks.get(index), 16);
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Rewrites the ADTS frames of a PES payload, with 9-byte headers when asked, encrypting each frame's whole
     * blocks after its header and a 16-byte leader.
     */
    private static byte[] adts(byte[] payload, boolean crc, byte[] key, byte[] iv) {
        var out = new ByteArrayOutputStream();
        for (var position = 0; position < payload.length; ) {
            if ((payload[position] & 0xFF) != 0xFF || (payload[position + 1] & 0xF0) != 0xF0) {
                throw new IllegalArgumentException("an audio PES packet that does not hold whole ADTS frames");
            }
            var header = (payload[position + 1] & 1) != 0 ? 7 : 9;
            var length = ((payload[position + 3] & 3) << 11) | ((payload[position + 4] & 0xFF) << 3)
                    | ((payload[position + 5] & 0xFF) >>> 5);
            if (position + length > payload.length) {
                throw new IllegalArgumentException("an ADTS frame across PES packets");
            }
            var body = Arrays.copyOfRange(payload, position + header, position + length);
            var newHeader = crc ? 9 : 7;
            var frame = new byte[newHeader + body.length];
            System.arraycopy(payload, position, frame, 0, 7);
            frame[1] = (byte) ((frame[1] & 0xFE) | (crc ? 0 : 1));
            var total = frame.length;
            frame[3] = (byte) ((frame[3] & 0xFC) | (total >> 11));
            frame[4] = (byte) (total >> 3);
            frame[5] = (byte) ((frame[5] & 0x1F) | (total & 7) << 5);
            if (crc) {
                frame[7] = 0x12;
                frame[8] = 0x34;
            }
            System.arraycopy(body, 0, frame, newHeader, body.length);
            if (key != null) {
                var blocks = new ArrayList<Integer>();
                for (var at = newHeader + 16; at + 16 <= frame.length; at += 16) {
                    blocks.add(at);
                }
                cbc(frame, blocks, key, iv);
            }
            out.writeBytes(frame);
            position += length;
        }
        return out.toByteArray();
    }

    /**
     * Rewrites the AC-3 or E-AC-3 syncframes of a PES payload, adding a dependent substream after each E-AC-3
     * syncframe when asked, encrypting the whole blocks after the 16-byte leader of each syncframe in one chain per
     * frame.
     */
    private static byte[] ac3(byte[] payload, boolean enhanced, boolean dependent, byte[] key, byte[] iv) {
        var frames = new ArrayList<List<byte[]>>();
        for (var position = 0; position < payload.length; ) {
            if ((payload[position] & 0xFF) != 0x0B || (payload[position + 1] & 0xFF) != 0x77) {
                throw new IllegalArgumentException("an audio PES packet that does not hold whole syncframes");
            }
            int length;
            if (enhanced) {
                length = 2 * ((((payload[position + 2] & 7) << 8) | (payload[position + 3] & 0xFF)) + 1);
            } else {
                if ((payload[position + 4] & 0xC0) != 0) {
                    throw new IllegalArgumentException("an AC-3 stream not at 48 kHz");
                }
                length = 4 * AC3_KBPS[(payload[position + 4] & 0x3F) >> 1];
            }
            if (position + length > payload.length) {
                throw new IllegalArgumentException("a syncframe across PES packets");
            }
            var syncframe = Arrays.copyOfRange(payload, position, position + length);
            var independentZero = !enhanced || ((syncframe[2] & 0xFF) >> 6 != 1 && ((syncframe[2] >> 3) & 7) == 0);
            if (independentZero || frames.isEmpty()) {
                frames.add(new ArrayList<>());
            }
            frames.getLast().add(syncframe);
            if (dependent && enhanced) {
                var copy = syncframe.clone();
                copy[2] = (byte) ((copy[2] & 0x3F) | 0x40);
                var crc = com.github.auties00.smalt.internal.header.Crc16.crc16(0, copy, 2, copy.length - 4);
                copy[copy.length - 2] = (byte) (crc >> 8);
                copy[copy.length - 1] = (byte) crc;
                frames.getLast().add(copy);
            }
            position += length;
        }
        var out = new ByteArrayOutputStream();
        for (var frame : frames) {
            var joined = new ByteArrayOutputStream();
            var blocks = new ArrayList<Integer>();
            for (var syncframe : frame) {
                var base = joined.size();
                for (var at = 16; at + 16 <= syncframe.length; at += 16) {
                    blocks.add(base + at);
                }
                joined.writeBytes(syncframe);
            }
            var bytes = joined.toByteArray();
            if (key != null) {
                cbc(bytes, blocks, key, iv);
            }
            out.writeBytes(bytes);
        }
        return out.toByteArray();
    }

    /**
     * Returns the 16-byte initialization vector of a media sequence number.
     *
     * @param sequence the media sequence number
     * @return the vector, the number as a 128-bit big-endian integer
     */
    public static byte[] sequenceIv(long sequence) {
        var iv = new byte[16];
        for (var index = 0; index < 8; index++) {
            iv[15 - index] = (byte) (sequence >>> (8 * index));
        }
        return iv;
    }

    /**
     * Rewrites a clear transport stream with the corner-case setup, clear or encrypted; see
     * {@link #rewrite(byte[], byte[], byte[], Setup)}.
     *
     * @param ts the stream the muxer wrote
     * @param key the key, or {@code null} for a clear rewrite
     * @param iv the initialization vector, or {@code null} for a clear rewrite
     * @return the rewritten stream
     */
    public static byte[] corners(byte[] ts, byte[] key, byte[] iv) {
        return rewrite(ts, key, iv, Setup.corners());
    }

    /**
     * Returns the stream types of a rewritten stream's program map, by PID.
     *
     * @param ts the stream
     * @return the stream types
     */
    static Map<Integer, Integer> streamTypes(byte[] ts) {
        var result = new HashMap<Integer, Integer>();
        var section = 4 + 188 + 1;
        var end = section + 3 + (((ts[section + 1] & 0x0F) << 8) | (ts[section + 2] & 0xFF)) - 4;
        var index = section + 12 + (((ts[section + 10] & 0x0F) << 8) | (ts[section + 11] & 0xFF));
        while (index < end) {
            result.put(((ts[index + 1] & 0x1F) << 8) | (ts[index + 2] & 0xFF), ts[index] & 0xFF);
            index += 5 + (((ts[index + 3] & 0x0F) << 8) | (ts[index + 4] & 0xFF));
        }
        return result;
    }
}
