package com.github.auties00.smalt.audio.dts.internal;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes synthetic DTS Express streams: extension substream frames whose only asset is an LBR component, with the
 * chunks the tests ask for. The chunk payloads are written least significant bit first, as the decoder reads them.
 */
final class LbrStreamWriter {
    /**
     * The extension substream sample rate codes by rate (Table 7-9).
     */
    private static final Map<Integer, Integer> SUBSTREAM_RATES = Map.of(8000, 0, 16000, 1, 32000, 2, 22050, 5, 44100, 6,
            12000, 10, 24000, 11, 48000, 12);

    /**
     * The LBR sample rate codes by rate (Table 9-3).
     */
    private static final Map<Integer, Integer> LBR_RATES = Map.of(8000, 0, 16000, 1, 32000, 2, 22050, 5, 44100, 6,
            12000, 10, 24000, 11, 48000, 12);

    private LbrStreamWriter() {
    }

    /**
     * A least significant bit first writer.
     */
    static final class Bits {
        private final List<Integer> bits = new ArrayList<>();

        void write(long value, int count) {
            for (var i = 0; i < count; i++) {
                bits.add((int) (value >> i & 1));
            }
        }

        void code(String code) {
            for (var i = 0; i < code.length(); i++) {
                bits.add(code.charAt(i) - '0');
            }
        }

        int size() {
            return bits.size();
        }

        byte[] toByteArray() {
            var out = new byte[(bits.size() + 7) / 8];
            for (var i = 0; i < bits.size(); i++) {
                out[i >> 3] |= (byte) (bits.get(i) << (i & 7));
            }
            return out;
        }
    }

    /**
     * An encoder for an index hopping code book.
     */
    static final class Book {
        private final Map<Integer, String> codes = new HashMap<>();

        Book(int[] table) {
            walk(table, 1, "");
        }

        private void walk(int[] table, int row, String prefix) {
            if (table[2 * row] == 0xFF) {
                codes.put(table[2 * row + 1], prefix);
                return;
            }
            walk(table, row + table[2 * row], prefix + "0");
            walk(table, row + table[2 * row + 1], prefix + "1");
        }

        /**
         * Writes a value, with an escape when the book has no code for it.
         */
        void put(Bits w, int value) {
            var code = codes.get(value + 1);
            if (code != null) {
                w.code(code);
                return;
            }
            var n = Math.max(1, 32 - Integer.numberOfLeadingZeros(value));
            w.code(codes.get(0));
            w.write(n - 1, 3);
            w.write(value, n);
        }

        boolean has(int value) {
            return codes.containsKey(value + 1);
        }
    }

    static final Book[] PRS_DIST = {new Book(LbrTables.PRS_DIST_4), new Book(LbrTables.PRS_DIST_3),
            new Book(LbrTables.PRS_DIST_2), new Book(LbrTables.PRS_DIST_1), new Book(LbrTables.PRS_DIST_0)};
    static final Book TONAL_SCF = new Book(LbrTables.TONAL_SCF);
    static final Book D_AMP = new Book(LbrTables.D_AMP);
    static final Book D_PH = new Book(LbrTables.D_PH);

    /**
     * Writes a frequency distance or an advance of the position (0) with the code book of a group.
     */
    static void distance(Bits w, int group, int value) {
        var book = PRS_DIST[group];
        for (var code = 0; code < LbrTables.FST_AMP.length; code++) {
            var extra = code >> 2;
            var base = LbrTables.FST_AMP[code];
            if (value >= base && value < base + (1 << extra) && book.has(code)) {
                book.put(w, code);
                w.write(value - base, extra);
                return;
            }
        }
        throw new IllegalArgumentException("no code for distance " + value);
    }

    /**
     * A tonal component as the tests write it.
     *
     * @param position the position in the frame
     * @param frequency the frequency in units of 1/2^(group+1) line
     * @param main the main channel
     * @param amplitude the coded amplitude, before the scale factor
     * @param phase the phase
     * @param secondary the amplitude and phase differences of each other channel, or {@code null} where absent
     */
    record Tone(int position, int frequency, int main, int amplitude, int phase, int[][] secondary) {
        Tone(int position, int frequency, int amplitude, int phase) {
            this(position, frequency, 0, amplitude, phase, null);
        }
    }

    /**
     * Writes the tonal components of one group, positions ascending and frequencies ascending within a position,
     * followed by the advances that end the group.
     */
    static void group(Bits w, int group, List<Tone> tones, int channels, boolean exact) {
        var positions = 16 >> group;
        var position = 0;
        var frequency = 1;
        for (var tone : tones) {
            while (position < tone.position()) {
                distance(w, group, 0);
                position++;
                frequency = 1;
            }
            distance(w, group, tone.frequency() - frequency + 2);
            frequency = tone.frequency() + 1;
            if (channels > 1) {
                w.write(tone.main(), LbrTables.BITS_FOR_CHANNELS[channels] - 1);
            }
            TONAL_SCF.put(w, tone.amplitude());
            w.write(tone.phase(), 3);
            for (var ch = 0; ch < channels; ch++) {
                if (ch == tone.main()) {
                    continue;
                }
                var difference = tone.secondary() == null ? null : tone.secondary()[ch];
                w.write(difference == null ? 0 : 1, 1);
                if (difference != null) {
                    D_AMP.put(w, difference[0]);
                    D_PH.put(w, difference[1]);
                }
            }
        }
        var advances = exact ? positions - position : positions - position + 4;
        for (var i = 0; i < advances; i++) {
            distance(w, group, 0);
        }
    }

    /**
     * {@return a chunk: identifier, 8 or 16-bit length, payload}
     */
    static byte[] chunk(int id, byte[] payload) {
        var out = new ByteArrayOutputStream();
        if (payload.length > 255 || (id & 0x80) != 0) {
            out.write(id | 0x80);
            out.write(payload.length >> 8);
            out.write(payload.length);
        } else {
            out.write(id);
            out.write(payload.length);
        }
        out.writeBytes(payload);
        return out.toByteArray();
    }

    /**
     * {@return an LFE chunk: the initial value (sign and magnitude), the step index and the ADPCM codes, of 4 bits
     * for 16-bit sources and 6 bits for 24-bit ones}
     */
    static byte[] lfe(int initial, int stepIndex, int[] codes, boolean wide) {
        var w = new Bits();
        if (wide) {
            w.write(initial & 0xFF, 8);
            w.write(initial >> 8 & 0xFFFF, 16);
        } else {
            w.write(initial, 16);
        }
        w.write(stepIndex, 8);
        for (var code : codes) {
            w.write(code, wide ? 6 : 4);
        }
        return chunk(0x0A, w.toByteArray());
    }

    /**
     * {@return the scale factor chunk}
     */
    static byte[] scaleFactors(int... factors) {
        var w = new Bits();
        for (var factor : factors) {
            w.write(factor, 6);
        }
        return chunk(0x0E, w.toByteArray());
    }

    /**
     * {@return a single group tonal chunk}
     */
    static byte[] tonalGroup(int group, List<Tone> tones, int channels) {
        var w = new Bits();
        group(w, group, tones, channels, false);
        return chunk(0x11 + group, w.toByteArray());
    }

    /**
     * {@return a combined tonal chunk: with scale factors of its own ({@code 0x16}) or without ({@code 0x10}), then
     * every group from the longest, each ended by exactly the advances it needs}
     */
    static byte[] tonalCombined(int[] factors, Map<Integer, List<Tone>> groups, int channels) {
        var w = new Bits();
        if (factors != null) {
            for (var factor : factors) {
                w.write(factor, 6);
            }
        }
        for (var group = 4; group >= 0; group--) {
            group(w, group, groups.getOrDefault(group, List.of()), channels, true);
        }
        return chunk(factors != null ? 0x16 : 0x10, w.toByteArray());
    }

    /**
     * {@return the frame chunk holding the given chunks, with or without checksum}
     */
    static byte[] frame(boolean checksum, byte[]... chunks) {
        var body = new ByteArrayOutputStream();
        for (var chunk : chunks) {
            body.writeBytes(chunk);
        }
        var payload = body.toByteArray();
        var out = new ByteArrayOutputStream();
        if (!checksum) {
            out.write(0x86);
            out.write(payload.length >> 8);
            out.write(payload.length);
            out.writeBytes(payload);
            return out.toByteArray();
        }
        var length = payload.length + 2;
        var header = new byte[]{(byte) 0x84, (byte) (length >> 8), (byte) length};
        var sum = 0;
        for (var b : header) {
            sum += b & 0xFF;
        }
        for (var b : payload) {
            sum += b & 0xFF;
        }
        out.writeBytes(header);
        out.write(sum >> 8 & 0xFF);
        out.write(sum & 0xFF);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    /**
     * {@return the LBR component: the decoder initialization header, or the sync-only header, then the frame}
     */
    static byte[] component(int rate, int speakerMask, int flags, int bitRate, boolean init, byte[] frame) {
        return component(rate, speakerMask, flags, bitRate, bitRate, init, frame);
    }

    /**
     * {@return the LBR component with distinct original and scaled bit rates in its initialization header}
     */
    static byte[] component(int rate, int speakerMask, int flags, int bitRate, int scaledBitRate, boolean init,
                            byte[] frame) {
        var out = new ByteArrayOutputStream();
        out.write(0x0A);
        out.write(0x80);
        out.write(0x19);
        out.write(0x21);
        if (!init) {
            out.write(1);
        } else {
            out.write(2);
            out.write(LBR_RATES.get(rate));
            out.write(speakerMask);
            out.write(speakerMask >> 8);
            out.write(0x00);
            out.write(0x08);
            out.write(flags);
            out.write((bitRate >> 16 & 0x0F) | (scaledBitRate >> 16 & 0x0F) << 4);
            out.write(bitRate);
            out.write(bitRate >> 8);
            out.write(scaledBitRate);
            out.write(scaledBitRate >> 8);
        }
        out.writeBytes(frame);
        return out.toByteArray();
    }

    /**
     * {@return an extension substream frame carrying one asset whose only component is the given LBR component}
     */
    static byte[] substream(byte[] component, int channels, int speakerMask, int rate, int frameSamples) {
        var header = substreamHeader(0, component.length, channels, speakerMask, rate, frameSamples);
        header = substreamHeader(header.length + 2, component.length, channels, speakerMask, rate, frameSamples);
        var crc = 0xFFFF;
        for (var i = 5; i < header.length; i++) {
            crc ^= (header[i] & 0xFF) << 8;
            for (var b = 0; b < 8; b++) {
                crc = (crc & 0x8000) != 0 ? crc << 1 ^ 0x1021 : crc << 1;
                crc &= 0xFFFF;
            }
        }
        var out = new ByteArrayOutputStream();
        out.writeBytes(header);
        out.write(crc >> 8);
        out.write(crc);
        out.writeBytes(component);
        return out.toByteArray();
    }

    private static byte[] substreamHeader(int headerSize, int componentSize, int channels, int speakerMask, int rate,
                                          int frameSamples) {
        var w = new Msb();
        w.write(0x64582025L, 32);
        w.write(0, 8);
        w.write(0, 2);
        w.write(0, 1);
        w.write(Math.max(headerSize, 1) - 1, 8);
        w.write(Math.max(headerSize, 1) + componentSize - 1, 16);
        w.write(1, 1);
        w.write(2, 2);
        w.write(frameSamples / 512 - 1, 3);
        w.write(0, 1);
        w.write(0, 3);
        w.write(0, 3);
        w.write(1, 1);
        w.write(1, 8);
        w.write(0, 1);
        w.write(componentSize - 1, 16);
        var d = new Msb();
        d.write(0, 3);
        d.write(0, 1);
        d.write(0, 1);
        d.write(0, 1);
        d.write(15, 5);
        d.write(SUBSTREAM_RATES.get(rate), 4);
        d.write(channels - 1, 8);
        d.write(1, 1);
        if (channels > 2) {
            d.write(0, 1);
        }
        if (channels > 6) {
            d.write(0, 1);
        }
        d.write(1, 1);
        d.write(3, 2);
        d.write(speakerMask, 16);
        d.write(0, 3);
        d.write(0, 1);
        d.write(0, 1);
        d.write(0, 2);
        d.write(0x100, 12);
        d.write(componentSize - 1, 14);
        d.write(0, 1);
        d.write(0, 1);
        var bytes = (9 + d.bits.size() + 7) / 8;
        var start = w.bits.size();
        w.write(bytes - 1, 9);
        w.bits.addAll(d.bits);
        while (w.bits.size() < start + bytes * 8) {
            w.bits.add(0);
        }
        w.write(0, 1);
        while (w.bits.size() % 8 != 0) {
            w.bits.add(0);
        }
        return w.toByteArray();
    }

    /**
     * A most significant bit first writer.
     */
    private static final class Msb {
        private final List<Integer> bits = new ArrayList<>();

        void write(long value, int count) {
            for (var i = count - 1; i >= 0; i--) {
                bits.add((int) (value >> i & 1));
            }
        }

        byte[] toByteArray() {
            var out = new byte[(bits.size() + 7) / 8];
            for (var i = 0; i < bits.size(); i++) {
                out[i >> 3] |= (byte) (bits.get(i) << (7 - (i & 7)));
            }
            return out;
        }
    }

    /**
     * A frame of the stream: its chunks.
     *
     * @param chunks the chunks of the LBR frame
     * @param checksum whether the frame chunk carries a checksum
     */
    record Frame(List<byte[]> chunks, boolean checksum) {
        Frame(byte[]... chunks) {
            this(List.of(chunks), false);
        }
    }

    /**
     * {@return a stream of frames, every frame with the decoder initialization header}
     */
    static byte[] stream(int rate, int speakerMask, int channels, int flags, List<Frame> frames) {
        return stream(rate, speakerMask, channels, flags, 128000, 128000, frames);
    }

    /**
     * {@return a stream of frames, every frame with the decoder initialization header of the given bit rates}
     */
    static byte[] stream(int rate, int speakerMask, int channels, int flags, int bitRate, int scaledBitRate,
                         List<Frame> frames) {
        var out = new ByteArrayOutputStream();
        var frameSamples = 1024 << (rate < 16000 ? 0 : rate < 32000 ? 1 : 2);
        for (var frame : frames) {
            var lbr = frame(frame.checksum(), frame.chunks().toArray(byte[][]::new));
            var component = component(rate, speakerMask, flags, bitRate, scaledBitRate, true, lbr);
            out.writeBytes(substream(component, channels + ((flags & 2) != 0 ? 1 : 0), speakerMask, rate,
                    frameSamples));
        }
        return out.toByteArray();
    }
}
