package com.github.auties00.smalt.audio.opus.internal.celt;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Decodes CELT-only Opus streams in the opus_demo {@code .bit} format with a minimal Opus framing
 * layer (TOC parsing, codes 0 to 3, end band per bandwidth), mirroring what {@code opus_decode_float}
 * does for CELT-only packets, and encodes raw CELT frames the way a direct
 * {@code celt_encode_with_ec} harness does.
 */
final class CeltOnlyHarness {
    private CeltOnlyHarness() {
    }

    /** One packet of a {@code .bit} file with the encoder's final range. */
    record BitPacket(byte[] data, int finalRange) {
    }

    /** The decoded samples and the number of final-range mismatches. */
    record Decoded(float[] samples, int rangeMismatches, int packets) {
    }

    static List<BitPacket> parseBit(byte[] file) {
        var packets = new ArrayList<BitPacket>();
        var buffer = ByteBuffer.wrap(file).order(ByteOrder.BIG_ENDIAN);
        while (buffer.remaining() >= 8) {
            var len = buffer.getInt();
            var rng = buffer.getInt();
            if (len < 0 || len > buffer.remaining()) {
                break;
            }
            var data = new byte[len];
            buffer.get(data);
            packets.add(new BitPacket(data, rng));
        }
        return packets;
    }

    static int frameSize48(int toc) {
        return 120 << ((toc >> 3) & 3);
    }

    static int endBand(int toc) {
        var config = toc >> 3;
        if (config < 20) {
            return 13;
        } else if (config < 24) {
            return 17;
        } else if (config < 28) {
            return 19;
        }
        return 21;
    }

    private static int parseSize(byte[] data, int offset, int len, int[] size) {
        if (len < 1) {
            size[0] = -1;
            return -1;
        }
        var b0 = data[offset] & 0xFF;
        if (b0 < 252) {
            size[0] = b0;
            return 1;
        }
        if (len < 2) {
            size[0] = -1;
            return -1;
        }
        size[0] = 4 * (data[offset + 1] & 0xFF) + b0;
        return 2;
    }

    /** Splits a packet into frames (offset, length pairs); returns null when malformed. */
    static int[] frames(byte[] data) {
        var len = data.length;
        if (len < 1) {
            return null;
        }
        var toc = data[0] & 0xFF;
        var pos = 1;
        len -= 1;
        var size = new int[1];
        return switch (toc & 3) {
            case 0 -> new int[]{pos, len};
            case 1 -> (len & 1) != 0 ? null : new int[]{pos, len / 2, pos + len / 2, len / 2};
            case 2 -> {
                var bytes = parseSize(data, pos, len, size);
                if (bytes < 0 || size[0] > len - bytes) {
                    yield null;
                }
                pos += bytes;
                len -= bytes;
                yield new int[]{pos, size[0], pos + size[0], len - size[0]};
            }
            default -> {
                if (len < 1) {
                    yield null;
                }
                var ch = data[pos++] & 0xFF;
                len--;
                var count = ch & 0x3F;
                if (count <= 0) {
                    yield null;
                }
                var pad = 0;
                if ((ch & 0x40) != 0) {
                    int p;
                    do {
                        if (len <= 0) {
                            yield null;
                        }
                        p = data[pos++] & 0xFF;
                        len--;
                        var tmp = p == 255 ? 254 : p;
                        len -= tmp;
                        pad += tmp;
                    } while (p == 255);
                }
                if (len < 0) {
                    yield null;
                }
                var result = new int[2 * count];
                if ((ch & 0x80) != 0) {
                    var last = len;
                    for (var i = 0; i < count - 1; i++) {
                        var bytes = parseSize(data, pos, len, size);
                        if (bytes < 0) {
                            yield null;
                        }
                        len -= bytes;
                        pos += bytes;
                        result[2 * i + 1] = size[0];
                        last -= bytes + size[0];
                    }
                    if (last < 0) {
                        yield null;
                    }
                    result[2 * count - 1] = last;
                } else {
                    var each = len / count;
                    if (each * count != len) {
                        yield null;
                    }
                    for (var i = 0; i < count; i++) {
                        result[2 * i + 1] = each;
                    }
                }
                for (var i = 0; i < count; i++) {
                    result[2 * i] = pos;
                    pos += result[2 * i + 1];
                }
                yield result;
            }
        };
    }

    /** Decodes a CELT-only stream; every lossEvery-th packet (1-based) is concealed instead. */
    static Decoded decode(List<BitPacket> packets, int rate, int channels, int lossEvery) {
        return decode(packets, rate, channels, lossEvery, 1);
    }

    /** Decodes raw CELT frames (no TOC); every lossEvery-th frame (1-based) is concealed. */
    static Decoded decodeRaw(List<BitPacket> packets, int rate, int channels, int frame48, int startBand,
                             int streamChannels, int lossEvery) {
        var decoder = new CeltDecoder(rate, channels);
        decoder.setStartBand(startBand);
        decoder.setChannels(streamChannels);
        var frame = frame48 * rate / 48000;
        var pcm = new float[960 * 2];
        var all = new float[packets.size() * frame * channels];
        var mismatches = 0;
        var n = 0;
        var pos = 0;
        for (var packet : packets) {
            n++;
            int ret;
            if (lossEvery > 0 && n % lossEvery == 0) {
                ret = decoder.decodeWithEc(null, 0, 0, pcm, 0, frame, null, 0);
            } else {
                ret = decoder.decodeWithEc(packet.data(), 0, packet.data().length, pcm, 0, frame, null, 0);
                if (decoder.getFinalRange() != packet.finalRange()) {
                    mismatches++;
                }
            }
            if (ret < 0) {
                throw new IllegalStateException("decode error " + ret);
            }
            System.arraycopy(pcm, 0, all, pos, ret * channels);
            pos += ret * channels;
        }
        return new Decoded(java.util.Arrays.copyOf(all, pos), mismatches, n);
    }

    /** Decodes a CELT-only stream; packets n with n % lossEvery < burst (and n > burst) are concealed. */
    static Decoded decode(List<BitPacket> packets, int rate, int channels, int lossEvery, int burst) {
        var decoder = new CeltDecoder(rate, channels);
        var ds = 48000 / rate;
        var out = new ByteArrayOutputStream();
        var pcm = new float[5760 * 2];
        var mismatches = 0;
        var lastFrame = 960 / ds;
        var n = 0;
        for (var packet : packets) {
            n++;
            var data = packet.data();
            var toc = data[0] & 0xFF;
            if ((toc >> 3) < 16) {
                throw new IllegalStateException("not a CELT-only packet");
            }
            var frames = frames(data);
            if (frames == null) {
                throw new IllegalStateException("malformed packet " + n);
            }
            var frameSize = frameSize48(toc) / ds;
            var count = frames.length / 2;
            var produced = 0;
            if (lossEvery > 0 && (burst == 1 ? n % lossEvery == 0 : (n % lossEvery < burst && n > burst))) {
                var samples = frameSize * count;
                while (produced < samples) {
                    var chunk = Math.min(samples - produced, lastFrame);
                    var ret = decoder.decodeWithEc(null, 0, 0, pcm, produced * channels, chunk, null, 0);
                    if (ret < 0) {
                        throw new IllegalStateException("plc error " + ret);
                    }
                    produced += ret;
                }
            } else {
                decoder.setEndBand(endBand(toc));
                decoder.setChannels((toc & 4) != 0 ? 2 : 1);
                decoder.setStartBand(0);
                for (var f = 0; f < count; f++) {
                    var off = frames[2 * f];
                    var len = frames[2 * f + 1];
                    var ret = decoder.decodeWithEc(data, off, len, pcm, produced * channels, frameSize, null, 0);
                    if (ret < 0) {
                        throw new IllegalStateException("decode error " + ret + " at packet " + n);
                    }
                    produced += ret;
                }
                lastFrame = frameSize;
                if (decoder.getFinalRange() != packet.finalRange()) {
                    mismatches++;
                }
            }
            var bytes = ByteBuffer.allocate(produced * channels * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (var i = 0; i < produced * channels; i++) {
                bytes.putFloat(pcm[i]);
            }
            out.writeBytes(bytes.array());
        }
        var raw = out.toByteArray();
        var samples = new float[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples);
        return new Decoded(samples, mismatches, n);
    }

    static byte[] toLittleEndian(float[] samples) {
        var buffer = ByteBuffer.allocate(samples.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (var sample : samples) {
            buffer.putFloat(sample);
        }
        return buffer.array();
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
