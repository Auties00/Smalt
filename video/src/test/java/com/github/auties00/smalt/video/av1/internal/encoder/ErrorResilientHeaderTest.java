package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.av1.Av1Encoder;
import com.github.auties00.smalt.video.av1.Av1EncoderOptions;
import com.github.auties00.smalt.video.av1.internal.BitReader;
import com.github.auties00.smalt.video.av1.internal.FrameHeader;
import com.github.auties00.smalt.video.av1.internal.Obu;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@code ref_order_hint[]} syntax of error resilient frame headers (AV1 specification
 * section 5.9.2): the hints are present when {@code !FrameIsIntra || refresh_frame_flags !=
 * allFrames}, so an error resilient inter frame carries them whatever its refresh flags, and only
 * an intra frame that refreshes every slot omits them. The headers are real ones of the encoder's
 * error resilient stream, rewritten by its {@link ObuWriter} with other refresh flags and parsed
 * back by the decoder's {@link FrameHeader}.
 */
@DisplayName("error resilient frame headers")
class ErrorResilientHeaderTest {
    @ParameterizedTest(name = "refresh_frame_flags {0}")
    @ValueSource(ints = {0x01, 0x7F, 0xFF})
    @DisplayName("an error resilient inter frame carries ref_order_hint[] whatever its refresh flags")
    void interHints(int refreshFrameFlags) {
        var stream = Stream.parse(encode());
        var inter = stream.inter;
        assertTrue(inter.errorResilientMode, "the real-time stream is error resilient");
        assertTrue(stream.seq.orderHint, "the stream signals order hints");
        assertFalse(inter.isKeyOrIntra());
        var original = write(stream.seq, inter, stream.hints);
        inter.refreshFrameFlags = refreshFrameFlags;
        var rewritten = write(stream.seq, inter, stream.hints);
        assertEquals(original.length, rewritten.length, "only the refresh flags changed");
        var parsed = new FrameHeader();
        var reader = new BitReader();
        reader.init(rewritten, 0, rewritten.length);
        assertTrue(parsed.parse(stream.seq, reader, stream.refsBeforeInter, true));
        assertFalse(reader.error());
        assertEquals(rewritten.length, (reader.bitPosition() + 7) >> 3, "the header ends where it was written");
        assertEquals(refreshFrameFlags, parsed.refreshFrameFlags);
        assertTrue(parsed.errorResilientMode);
        assertArrayEquals(inter.refidx, parsed.refidx);
        assertEquals(inter.quantYac, parsed.quantYac);
        assertArrayEquals(inter.loopfilterLevelY, parsed.loopfilterLevelY);
        assertEquals(inter.txfmMode, parsed.txfmMode);
    }

    @Test
    @DisplayName("an intra frame that refreshes every slot carries no ref_order_hint[]")
    void keyFrameOmitsHints() {
        var stream = Stream.parse(encode());
        var key = stream.key;
        assertTrue(key.isKeyOrIntra());
        assertEquals(0xFF, key.refreshFrameFlags);
        var written = write(stream.seq, key, new int[8]);
        var withHints = write(stream.seq, key, new int[]{1, 2, 3, 4, 5, 6, 7, 8});
        assertArrayEquals(written, withHints, "a key frame refreshing every slot writes no hints");
        var parsed = new FrameHeader();
        var reader = new BitReader();
        reader.init(written, 0, written.length);
        assertTrue(parsed.parse(stream.seq, reader, new FrameHeader[8], true));
        assertEquals(key.quantYac, parsed.quantYac);
    }

    /**
     * Returns the bytes of a frame header written with given reference slot order hints.
     */
    private static byte[] write(SequenceHeader seq, FrameHeader header, int[] hints) {
        var writer = new ObuWriter().frameHeader(seq, header, hints);
        return Arrays.copyOf(writer.buffer(), writer.size());
    }

    /**
     * Encodes three frames of an error resilient real-time stream.
     */
    private static List<Packet> encode() {
        var packets = new ArrayList<Packet>();
        try (var encoder = new Av1Encoder(64, 64, Av1EncoderOptions.forRealtime().toBuilder().dropFrames(false)
                .build())) {
            for (var i = 0; i < 3; i++) {
                var picture = picture(i);
                encoder.encode(new VideoFrame(picture, i * 3000L, 3000, false), packets::add);
            }
        }
        return packets;
    }

    /**
     * Returns a moving textured picture.
     */
    private static YuvPicture picture(int index) {
        var picture = YuvPicture.allocate(64, 64, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
        for (var y = 0; y < 64; y++) {
            for (var x = 0; x < 64; x++) {
                picture.y()[y * picture.yStride() + x] = (byte) (((x + 2 * index) * 7 ^ y * 5) & 0xFF);
            }
        }
        Arrays.fill(picture.u(), (byte) 100);
        Arrays.fill(picture.v(), (byte) 150);
        return picture;
    }

    /**
     * The sequence header, the key frame header and the first inter frame header of a stream,
     * with the reference slots and their order hints as the inter frame found them.
     */
    private static final class Stream {
        private SequenceHeader seq;
        private FrameHeader key;
        private FrameHeader inter;
        private FrameHeader[] refsBeforeInter;
        private int[] hints;

        static Stream parse(List<Packet> packets) {
            var stream = new Stream();
            var refs = new FrameHeader[8];
            for (var packet : packets) {
                var data = packet.data();
                var pos = 0;
                while (pos < data.length) {
                    var type = (data[pos] >> 3) & 15;
                    var extension = (data[pos] & 4) != 0;
                    pos += extension ? 2 : 1;
                    long size = 0;
                    for (var shift = 0; ; shift += 7) {
                        var b = data[pos++] & 0xFF;
                        size |= (long) (b & 0x7F) << shift;
                        if ((b & 0x80) == 0) {
                            break;
                        }
                    }
                    var reader = new BitReader();
                    reader.init(data, pos, (int) size);
                    if (type == Obu.OBU_SEQ_HDR) {
                        stream.seq = new SequenceHeader();
                        stream.seq.parse(reader, false);
                    } else if (type == Obu.OBU_FRAME || type == Obu.OBU_FRAME_HDR) {
                        var before = refs.clone();
                        var header = new FrameHeader();
                        assertTrue(header.parse(stream.seq, reader, refs, true));
                        if (header.isKeyOrIntra() && stream.key == null) {
                            stream.key = header;
                        } else if (!header.isKeyOrIntra() && stream.inter == null) {
                            stream.inter = header;
                            stream.refsBeforeInter = before;
                            stream.hints = new int[8];
                            for (var i = 0; i < 8; i++) {
                                stream.hints[i] = before[i] == null ? 0 : before[i].frameOffset;
                            }
                        }
                        for (var slot = 0; slot < 8; slot++) {
                            if ((header.refreshFrameFlags & (1 << slot)) != 0) {
                                refs[slot] = header;
                            }
                        }
                    }
                    pos += (int) size;
                }
            }
            assertNotNull(stream.key);
            assertNotNull(stream.inter);
            return stream;
        }
    }
}
