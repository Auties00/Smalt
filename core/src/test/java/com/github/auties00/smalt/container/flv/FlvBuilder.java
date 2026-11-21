package com.github.auties00.smalt.container.flv;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Assembles FLV files tag by tag for the tests, from the FLV specification and Enhanced RTMP v2, without Smalt's
 * muxer.
 */
final class FlvBuilder {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    FlvBuilder(boolean audio, boolean video) {
        out.writeBytes(new byte[]{'F', 'L', 'V', 1, (byte) ((audio ? 4 : 0) | (video ? 1 : 0)), 0, 0, 0, 9, 0, 0, 0, 0});
    }

    FlvBuilder tag(int type, int timestamp, byte[] body) {
        return tag(type, timestamp, body, 0);
    }

    FlvBuilder tag(int type, int timestamp, byte[] body, int flags) {
        var header = ByteBuffer.allocate(11);
        header.put((byte) (type | flags));
        header.put((byte) (body.length >>> 16)).put((byte) (body.length >>> 8)).put((byte) body.length);
        header.put((byte) (timestamp >>> 16)).put((byte) (timestamp >>> 8)).put((byte) timestamp);
        header.put((byte) (timestamp >>> 24));
        header.put(new byte[3]);
        out.writeBytes(header.array());
        out.writeBytes(body);
        out.writeBytes(ByteBuffer.allocate(4).putInt(body.length + 11).array());
        return this;
    }

    FlvBuilder raw(byte[] bytes) {
        out.writeBytes(bytes);
        return this;
    }

    byte[] bytes() {
        return out.toByteArray();
    }

    static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    static byte[] bytes(int... values) {
        var out = new byte[values.length];
        for (var index = 0; index < values.length; index++) {
            out[index] = (byte) values[index];
        }
        return out;
    }

    static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] u24(int value) {
        return bytes(value >>> 16, value >>> 8, value);
    }

    /** An AMF0 writer for script data. */
    static final class Amf {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Amf string(String text) {
            var raw = text.getBytes(StandardCharsets.UTF_8);
            out.write(2);
            out.write(raw.length >>> 8);
            out.write(raw.length);
            out.writeBytes(raw);
            return this;
        }

        Amf name(String text) {
            var raw = text.getBytes(StandardCharsets.UTF_8);
            out.write(raw.length >>> 8);
            out.write(raw.length);
            out.writeBytes(raw);
            return this;
        }

        Amf number(double value) {
            out.write(0);
            out.writeBytes(ByteBuffer.allocate(8).putDouble(value).array());
            return this;
        }

        Amf bool(boolean value) {
            out.write(1);
            out.write(value ? 1 : 0);
            return this;
        }

        Amf ecma(int count) {
            out.write(8);
            out.writeBytes(ByteBuffer.allocate(4).putInt(count).array());
            return this;
        }

        Amf object() {
            out.write(3);
            return this;
        }

        Amf end() {
            out.writeBytes(new byte[]{0, 0, 9});
            return this;
        }

        Amf strictArray(double... values) {
            out.write(10);
            out.writeBytes(ByteBuffer.allocate(4).putInt(values.length).array());
            for (var value : values) {
                number(value);
            }
            return this;
        }

        Amf raw(int... values) {
            out.writeBytes(FlvBuilder.bytes(values));
            return this;
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }
}
