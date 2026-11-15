package com.github.auties00.smalt.container.dv;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Builds DV files no oracle writes: type-1 DV AVI files, whose single interleaved stream carries whole DIF frames.
 */
public final class DvTestFiles {
    private DvTestFiles() {
    }

    /**
     * Wraps the frames of a raw DV stream in a type-1 DV AVI file: one {@code iavs} stream with a {@code DVINFO}
     * format, the frames as {@code 00__} chunks, and an {@code idx1} index.
     */
    public static byte[] typeOneAvi(byte[] dv, int frameSize, int scale, int rate) {
        var frames = dv.length / frameSize;
        var movi = new ByteArrayOutputStream();
        movi.writeBytes("movi".getBytes(StandardCharsets.US_ASCII));
        var index = ByteBuffer.allocate(16 * frames).order(ByteOrder.LITTLE_ENDIAN);
        for (var i = 0; i < frames; i++) {
            index.put("00__".getBytes(StandardCharsets.US_ASCII)).putInt(0x10).putInt(movi.size()).putInt(frameSize);
            movi.writeBytes(chunk("00__", Arrays.copyOfRange(dv, i * frameSize, (i + 1) * frameSize)));
        }
        var avih = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN);
        avih.putInt((int) (1_000_000L * scale / rate)).putInt(frameSize * rate / scale).putInt(0).putInt(0x10)
                .putInt(frames).putInt(0).putInt(1).putInt(frameSize).putInt(720).putInt(480);
        var strh = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN);
        strh.put("iavs".getBytes(StandardCharsets.US_ASCII)).put("dvsd".getBytes(StandardCharsets.US_ASCII))
                .putInt(0).putInt(0).putInt(0).putInt(scale).putInt(rate).putInt(0).putInt(frames).putInt(frameSize)
                .putInt(-1).putInt(0);
        var dvinfo = new byte[32];
        var strl = list("strl", join(List.of(chunk("strh", strh.array()), chunk("strf", dvinfo))));
        var hdrl = list("hdrl", join(List.of(chunk("avih", avih.array()), strl)));
        var body = join(List.of("AVI ".getBytes(StandardCharsets.US_ASCII), hdrl, chunk("LIST", movi.toByteArray()),
                chunk("idx1", index.array())));
        return chunk("RIFF", body);
    }

    private static byte[] chunk(String id, byte[] body) {
        var out = ByteBuffer.allocate(8 + body.length + (body.length & 1)).order(ByteOrder.LITTLE_ENDIAN);
        out.put(id.getBytes(StandardCharsets.US_ASCII)).putInt(body.length).put(body);
        return out.array();
    }

    private static byte[] list(String type, byte[] body) {
        return chunk("LIST", join(List.of(type.getBytes(StandardCharsets.US_ASCII), body)));
    }

    private static byte[] join(List<byte[]> parts) {
        var out = new ByteArrayOutputStream();
        parts.forEach(out::writeBytes);
        return out.toByteArray();
    }
}
