package com.github.auties00.smalt.audio.opus.internal;

import com.github.auties00.smalt.audio.opus.OpusPackets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the padding extensions of Opus packets: the parser on long length codes and truncations, and the
 * repacketizer, whose merged and split packets must carry exactly the extensions of their input frames, renumbered.
 * Packets are built by hand from CELT 2.5 ms frames of random bytes, so up to 48 frames fit one packet.
 */
class OpusExtensionsTest {
    /** One extension: the frame it belongs to, its id and its payload. */
    private record Ext(int frame, int id, byte[] payload) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Ext e && e.frame == frame && e.id == id && Arrays.equals(e.payload, payload);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * frame + id) + Arrays.hashCode(payload);
        }

        @Override
        public String toString() {
            return "Ext[frame=" + frame + ", id=" + id + ", length=" + payload.length + "]";
        }
    }

    /** Builds a code 3 packet of CELT NB 2.5 ms frames of the given sizes, carrying the extensions in padding. */
    private static byte[] packet(Random random, int[] sizes, List<Ext> extensions) {
        var count = sizes.length;
        var vbr = Arrays.stream(sizes).distinct().count() > 1;
        var n = extensions.size();
        var buffers = new byte[n][];
        var ids = new int[n];
        var frames = new int[n];
        var offsets = new int[n];
        var lengths = new int[n];
        for (var i = 0; i < n; i++) {
            buffers[i] = extensions.get(i).payload();
            ids[i] = extensions.get(i).id();
            frames[i] = extensions.get(i).frame();
            lengths[i] = buffers[i].length;
        }
        var padding = new byte[0];
        if (n > 0) {
            var need = OpusExtensions.generate(null, 0, 1 << 20, buffers, ids, frames, offsets, lengths, n, false);
            assertTrue(need > 0, "generate " + need);
            padding = new byte[need];
            assertEquals(need, OpusExtensions.generate(padding, 0, need, buffers, ids, frames, offsets, lengths, n,
                    false));
        }
        var out = new ByteArrayOutputStream();
        out.write((16 << 3) | 3);
        out.write(count | (vbr ? 0x80 : 0) | (n > 0 ? 0x40 : 0));
        if (n > 0) {
            var left = padding.length;
            while (left >= 255) {
                out.write(255);
                left -= 254;
            }
            out.write(left);
        }
        if (vbr) {
            var tmp = new byte[2];
            for (var i = 0; i < count - 1; i++) {
                var written = OpusPacket.encodeSize(sizes[i], tmp, 0);
                out.write(tmp, 0, written);
            }
        }
        for (var size : sizes) {
            var frame = new byte[size];
            random.nextBytes(frame);
            out.write(frame, 0, size);
        }
        out.write(padding, 0, padding.length);
        return out.toByteArray();
    }

    /** Lists the extensions of a packet, frame numbers relative to it. */
    private static List<Ext> extensions(byte[] packet) {
        var layout = new OpusPacket.Layout();
        assertTrue(OpusPacket.parseImpl(packet, 0, packet.length, false, layout) > 0);
        var capacity = 1 + layout.paddingLen;
        var ids = new int[capacity];
        var frames = new int[capacity];
        var offsets = new int[capacity];
        var lengths = new int[capacity];
        var n = OpusExtensions.parse(packet, layout.paddingOffset, layout.paddingLen, ids, frames, offsets, lengths,
                0, capacity);
        assertTrue(n >= 0, "parse " + n);
        var list = new ArrayList<Ext>();
        for (var i = 0; i < n; i++) {
            list.add(new Ext(frames[i], ids[i], Arrays.copyOfRange(packet, offsets[i], offsets[i] + lengths[i])));
        }
        return list;
    }

    private static List<Ext> randomExtensions(Random random, int frames, int max) {
        var list = new ArrayList<Ext>();
        var n = random.nextInt(max + 1);
        for (var i = 0; i < n; i++) {
            var id = random.nextBoolean() ? 2 + random.nextInt(30) : 32 + random.nextInt(96);
            var length = id < 32 ? random.nextInt(2) : random.nextInt(random.nextInt(4) == 0 ? 700 : 40);
            var payload = new byte[length];
            random.nextBytes(payload);
            list.add(new Ext(random.nextInt(frames), id, payload));
        }
        // generate writes in frame order, keeping the order of the extensions of one frame
        list.sort((a, b) -> Integer.compare(a.frame(), b.frame()));
        return list;
    }

    @Test
    @DisplayName("parses an extension whose length takes more than 255 continuation bytes")
    void longLengthCode() {
        var length = 300 * 255 + 17;
        var data = new byte[1 + 301 + length + 2];
        data[0] = (byte) (100 << 1 | 1);
        Arrays.fill(data, 1, 301, (byte) 255);
        data[301] = 17;
        for (var i = 0; i < length; i++) {
            data[302 + i] = (byte) i;
        }
        data[302 + length] = (byte) (5 << 1 | 1);
        data[303 + length] = 42;
        var skipped = OpusExtensions.skip(data, 0, data.length);
        assertEquals(302, (int) skipped, "header size");
        assertEquals(2, (int) (skipped >>> 32), "bytes left");
        assertEquals(2, OpusExtensions.count(data, 0, data.length));
        var ids = new int[2];
        var frames = new int[2];
        var offsets = new int[2];
        var lengths = new int[2];
        assertEquals(2, OpusExtensions.parse(data, 0, data.length, ids, frames, offsets, lengths, 0, 2));
        assertArrayEquals(new int[]{100, 5}, ids);
        assertArrayEquals(new int[]{302, 303 + length}, offsets);
        assertArrayEquals(new int[]{length, 1}, lengths);
    }

    @Test
    @DisplayName("rejects truncated extensions and frame numbers past 48")
    void malformed() {
        var none = new int[4];
        // Length code that runs off the end.
        var truncated = new byte[]{(byte) (40 << 1 | 1), (byte) 255, (byte) 255};
        assertEquals(-1, OpusExtensions.skip(truncated, 0, truncated.length));
        assertEquals(OpusDefines.OPUS_INVALID_PACKET, OpusExtensions.count(truncated, 0, truncated.length));
        // Payload longer than what is left.
        var shortPayload = new byte[]{(byte) (40 << 1 | 1), 10, 1, 2, 3};
        assertEquals(OpusDefines.OPUS_INVALID_PACKET, OpusExtensions.parse(shortPayload, 0, shortPayload.length,
                none, none, none, none, 0, 4));
        // A one-byte extension without its byte.
        var shortSmall = new byte[]{(byte) (3 << 1 | 1)};
        assertEquals(OpusDefines.OPUS_INVALID_PACKET, OpusExtensions.count(shortSmall, 0, 1));
        // A separator jumping to frame 48.
        var farFrame = new byte[]{0x03, 48, (byte) (40 << 1)};
        assertEquals(OpusDefines.OPUS_INVALID_PACKET, OpusExtensions.parse(farFrame, 0, farFrame.length, none, none,
                none, none, 0, 4));
        // More extensions than the arrays hold.
        var many = new byte[]{(byte) (4 << 1), (byte) (5 << 1), (byte) (6 << 1)};
        assertEquals(OpusDefines.OPUS_BUFFER_TOO_SMALL, OpusExtensions.parse(many, 0, many.length, none, none, none,
                none, 0, 2));
        // A trailing L=0 extension of id >= 32 takes the rest.
        var last = new byte[]{(byte) (40 << 1), 9, 9, 9};
        var ids = new int[1];
        var frames = new int[1];
        var offsets = new int[1];
        var lengths = new int[1];
        assertEquals(1, OpusExtensions.parse(last, 0, last.length, ids, frames, offsets, lengths, 0, 1));
        assertEquals(40, ids[0]);
        assertEquals(1, offsets[0]);
        assertEquals(3, lengths[0]);
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("merging and splitting keep every extension on its frame")
    void mergeSplitKeepExtensions(int seed) {
        var random = new Random(seed);
        for (var trial = 0; trial < 40; trial++) {
            var packets = new ArrayList<byte[]>();
            var expected = new ArrayList<Ext>();
            var base = 0;
            var total = 1 + random.nextInt(48);
            while (base < total) {
                var count = Math.min(total - base, 1 + random.nextInt(6));
                var sizes = new int[count];
                var same = random.nextBoolean();
                for (var i = 0; i < count; i++) {
                    sizes[i] = same && i > 0 ? sizes[0] : 1 + random.nextInt(300);
                }
                var ext = randomExtensions(random, count, 5);
                var packet = packet(random, sizes, ext);
                assertEquals(ext, extensions(packet), "hand-built packet");
                packets.add(packet);
                for (var e : ext) {
                    expected.add(new Ext(e.frame() + base, e.id(), e.payload()));
                }
                base += count;
            }
            var merged = OpusPackets.merge(packets);
            var mergedExtensions = extensions(merged);
            // Within one output frame the merged order follows the inputs, which are in frame order already.
            assertEquals(expected, mergedExtensions, "merged extensions, trial " + trial);
            var split = OpusPackets.split(merged, 0, merged.length);
            assertEquals(total, split.size());
            for (var f = 0; f < total; f++) {
                var frame = f;
                var own = expected.stream().filter(e -> e.frame() == frame).map(e -> new Ext(0, e.id(), e.payload()))
                        .toList();
                assertEquals(own, extensions(split.get(f)),
                        "extensions of split frame " + f + ", trial " + trial);
            }
        }
    }
}
