package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.audio.opus.internal.celt.RangeDecoder;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives {@link SilkDecoder} over a SILK-only opus_demo {@code .bit} stream exactly like
 * {@code opus_decode_frame} drives {@code silk_Decode}, mirroring the C reference harness
 * {@code ref/harness-silkdec/silk_dec_ref.c}: TOC and frame parsing, 20 ms PLC chunks for lost
 * packets and DTX frames, and LBRR recovery of a lost packet from the next one.
 */
final class SilkReferenceDriver {
    record Packet(byte[] data, int finalRange) {
    }

    private final SilkDecoder decoder = new SilkDecoder();
    private final SilkDecControl control = new SilkDecControl();
    private final int apiRate;
    private final int apiChannels;
    private final short[] pcm = new short[48000 * 2];
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final int[] nSamples = new int[1];
    int rangeMismatches;
    int rangeChecks;
    private boolean anyDecoded;

    SilkReferenceDriver(int apiRate, int apiChannels) {
        this.apiRate = apiRate;
        this.apiChannels = apiChannels;
        control.nChannelsAPI = apiChannels;
        control.API_sampleRate = apiRate;
    }

    static List<Packet> parseBit(byte[] file) {
        var packets = new ArrayList<Packet>();
        var i = 0;
        while (i + 8 <= file.length) {
            var len = (file[i] & 0xFF) << 24 | (file[i + 1] & 0xFF) << 16 | (file[i + 2] & 0xFF) << 8 | (file[i + 3] & 0xFF);
            var rng = (file[i + 4] & 0xFF) << 24 | (file[i + 5] & 0xFF) << 16 | (file[i + 6] & 0xFF) << 8 | (file[i + 7] & 0xFF);
            if (len < 0 || i + 8 + len > file.length) {
                break;
            }
            var data = new byte[len];
            System.arraycopy(file, i + 8, data, 0, len);
            packets.add(new Packet(data, rng));
            i += 8 + len;
        }
        return packets;
    }

    /**
     * Splits an Opus packet into its frames (RFC 6716 section 3.2), returning {offset, length}
     * pairs.
     */
    static int[][] frames(byte[] p) {
        var toc = p[0] & 0xFF;
        var code = toc & 3;
        var pos = 1;
        var end = p.length;
        switch (code) {
            case 0 -> {
                return new int[][]{{1, end - 1}};
            }
            case 1 -> {
                var half = (end - 1) / 2;
                return new int[][]{{1, half}, {1 + half, half}};
            }
            case 2 -> {
                int[] sz = new int[1];
                pos += readSize(p, pos, sz);
                return new int[][]{{pos, sz[0]}, {pos + sz[0], end - pos - sz[0]}};
            }
            default -> {
                var ch = p[pos++] & 0xFF;
                var count = ch & 0x3F;
                var vbr = (ch & 0x80) != 0;
                var padding = 0;
                if ((ch & 0x40) != 0) {
                    int b;
                    do {
                        b = p[pos++] & 0xFF;
                        padding += b == 255 ? 254 : b;
                    } while (b == 255);
                }
                end -= padding;
                var result = new int[count][2];
                if (vbr) {
                    int[] sz = new int[1];
                    var total = 0;
                    for (var f = 0; f < count - 1; f++) {
                        pos += readSize(p, pos, sz);
                        result[f][1] = sz[0];
                        total += sz[0];
                    }
                    result[count - 1][1] = end - pos - total;
                } else {
                    var each = (end - pos) / count;
                    for (var f = 0; f < count; f++) {
                        result[f][1] = each;
                    }
                }
                for (var f = 0; f < count; f++) {
                    result[f][0] = pos;
                    pos += result[f][1];
                }
                return result;
            }
        }
    }

    private static int readSize(byte[] p, int pos, int[] size) {
        var b0 = p[pos] & 0xFF;
        if (b0 < 252) {
            size[0] = b0;
            return 1;
        }
        size[0] = 4 * (p[pos + 1] & 0xFF) + b0;
        return 2;
    }

    private int tocSamples(byte[] p) {
        var ms = new int[]{10, 20, 40, 60}[((p[0] & 0xFF) >> 3) & 3];
        return apiRate * ms / 1000;
    }

    private static int bwRate(int config) {
        return config < 4 ? 8000 : config < 8 ? 12000 : 16000;
    }

    private int decodeFrame(byte[] data, int off, int len, int audiosize, int lostFlag, int streamChannels,
                            int internalRate, int[] rngOut) {
        if (data == null && !anyDecoded) {
            // opus_decode_frame outputs silence for PLC before the first packet (mode == 0)
            for (var i = 0; i < audiosize * apiChannels * 2; i++) {
                out.write(0);
            }
            return audiosize;
        }
        if (data != null) {
            anyDecoded = true;
        }
        control.payloadSize_ms = Math.max(10, 1000 * audiosize / apiRate);
        var dec = new RangeDecoder();
        if (data != null) {
            control.nChannelsInternal = streamChannels;
            control.internalSampleRate = internalRate;
            dec.init(data, off, len);
        } else {
            dec.init(new byte[0], 0, 0);
        }
        var decoded = 0;
        var p = 0;
        do {
            var r = decoder.decode(control, lostFlag, decoded == 0 ? 1 : 0, dec, pcm, p, nSamples);
            var n = nSamples[0];
            if (r != 0) {
                if (lostFlag != 0) {
                    n = audiosize;
                    java.util.Arrays.fill(pcm, p, p + audiosize * apiChannels, (short) 0);
                } else {
                    throw new IllegalStateException("silk error " + r);
                }
            }
            p += n * apiChannels;
            decoded += n;
        } while (decoded < audiosize);
        if (rngOut != null) {
            rngOut[0] = dec.finalRange();
        }
        for (var i = 0; i < decoded * apiChannels; i++) {
            out.write(pcm[i] & 0xFF);
            out.write((pcm[i] >> 8) & 0xFF);
        }
        return decoded;
    }

    private void plc(int duration) {
        var f20 = apiRate / 50;
        while (duration > 0) {
            var chunk = Math.min(duration, f20);
            decodeFrame(null, 0, 0, chunk, 1, 0, 0, null);
            duration -= chunk;
        }
    }

    private void decodePacket(Packet packet) {
        var p = packet.data();
        var frames = frames(p);
        var fsz = tocSamples(p);
        var toc = p[0] & 0xFF;
        var streamChannels = ((toc >> 2) & 1) != 0 ? 2 : 1;
        var rate = bwRate(toc >> 3);
        var rng = new int[1];
        var dtx = false;
        for (var f : frames) {
            if (f[1] <= 1) {
                plc(fsz);
                dtx = true;
            } else {
                decodeFrame(p, f[0], f[1], fsz, 0, streamChannels, rate, rng);
            }
        }
        if (!dtx) {
            rangeChecks++;
            if (rng[0] != packet.finalRange()) {
                rangeMismatches++;
            }
        }
    }

    byte[] decodeAll(List<Packet> packets, int lossPeriod, boolean fec) {
        for (var i = 0; i < packets.size(); i++) {
            var lost = lossPeriod > 0 && i % lossPeriod == lossPeriod - 1;
            if (!lost) {
                decodePacket(packets.get(i));
                continue;
            }
            var lostPacket = packets.get(i).data();
            var dur = frames(lostPacket).length * tocSamples(lostPacket);
            if (fec && i + 1 < packets.size() && packets.get(i + 1).data().length > 1) {
                var nx = packets.get(i + 1).data();
                var frames = frames(nx);
                var pfs = tocSamples(nx);
                if (dur > pfs) {
                    plc(dur - pfs);
                }
                var toc = nx[0] & 0xFF;
                if (frames[0][1] > 1) {
                    decodeFrame(nx, frames[0][0], frames[0][1], pfs, 2, ((toc >> 2) & 1) != 0 ? 2 : 1,
                            bwRate(toc >> 3), null);
                } else {
                    plc(pfs);
                }
            } else {
                plc(dur);
            }
        }
        return out.toByteArray();
    }
}
