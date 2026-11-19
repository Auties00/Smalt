package com.github.auties00.smalt.container.dsd;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Synthesizes DSD streams and the DSF and DSDIFF files that carry them, for the DSD tests.
 */
public final class DsdSamples {
    private DsdSamples() {
    }

    /**
     * Modulates sines into DSD with a second-order sigma-delta modulator (two integrators, a one-bit quantizer),
     * most significant bit first, one plane of bytes per channel.
     *
     * @param rate the DSD rate in hertz
     * @param bytes the number of bytes per channel
     * @param amplitude the peak of the sine, 0.5 being the 0 dB of the Super Audio CD
     * @param frequencies the frequency of each channel's sine in hertz
     * @return one plane per channel
     */
    public static byte[][] modulate(int rate, int bytes, double amplitude, double... frequencies) {
        var planes = new byte[frequencies.length][bytes];
        for (var c = 0; c < frequencies.length; c++) {
            var first = 0.0;
            var second = 0.0;
            var y = 1.0;
            for (var i = 0; i < bytes * 8; i++) {
                var x = amplitude * StrictMath.sin(2 * StrictMath.PI * frequencies[c] * i / rate);
                first += x - y;
                second += first - y;
                y = second >= 0 ? 1.0 : -1.0;
                if (y > 0) {
                    planes[c][i >>> 3] |= (byte) (0x80 >>> (i & 7));
                }
            }
        }
        return planes;
    }

    /**
     * Fills planes with seeded pseudo-random bytes.
     */
    public static byte[][] random(int channels, int bytes, long seed) {
        var random = new java.util.Random(seed);
        var planes = new byte[channels][bytes];
        for (var plane : planes) {
            random.nextBytes(plane);
        }
        return planes;
    }

    static byte reverse(byte b) {
        return (byte) (Integer.reverse(b & 0xFF) >>> 24);
    }

    static void le(ByteArrayOutputStream out, long value, int size) {
        for (var k = 0; k < size; k++) {
            out.write((int) (value >>> (8 * k)));
        }
    }

    static void be(ByteArrayOutputStream out, long value, int size) {
        for (var k = size - 1; k >= 0; k--) {
            out.write((int) (value >>> (8 * k)));
        }
    }

    /**
     * Writes an ID3v2.3 tag of text frames.
     *
     * @param frames alternating frame identifiers and ISO-8859-1 texts
     * @return the tag
     */
    public static byte[] id3(String... frames) {
        var body = new ByteArrayOutputStream();
        for (var i = 0; i < frames.length; i += 2) {
            var text = frames[i + 1].getBytes(StandardCharsets.ISO_8859_1);
            body.writeBytes(frames[i].getBytes(StandardCharsets.US_ASCII));
            be(body, text.length + 1, 4);
            be(body, 0, 2);
            body.write(0);
            body.writeBytes(text);
        }
        var size = body.size();
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{'I', 'D', '3', 3, 0, 0});
        out.write((size >>> 21) & 0x7F);
        out.write((size >>> 14) & 0x7F);
        out.write((size >>> 7) & 0x7F);
        out.write(size & 0x7F);
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    /**
     * Writes a DSF file of 4096-byte channel blocks, the last padded with zeros.
     *
     * @param rate the DSD rate in hertz
     * @param channelType the DSF channel type, from 1 to 7
     * @param planes one plane per channel, most significant bit first
     * @param lsbFirst whether the file stores 1 bit per sample (least significant bit first) or 8
     * @param id3 the ID3v2 tag to append, or {@code null} for none
     * @return the file
     */
    public static byte[] dsf(int rate, int channelType, byte[][] planes, boolean lsbFirst, byte[] id3) {
        var channels = planes.length;
        var bytes = planes[0].length;
        var blocks = (bytes + 4095) / 4096;
        var data = new ByteArrayOutputStream();
        for (var b = 0; b < blocks; b++) {
            for (var plane : planes) {
                for (var i = 0; i < 4096; i++) {
                    var at = b * 4096 + i;
                    var value = at < bytes ? plane[at] : 0;
                    data.write(lsbFirst ? reverse(value) : value);
                }
            }
        }
        var dataSize = 12L + data.size();
        var total = 28 + 52 + dataSize + (id3 == null ? 0 : id3.length);
        var out = new ByteArrayOutputStream();
        out.writeBytes("DSD ".getBytes(StandardCharsets.US_ASCII));
        le(out, 28, 8);
        le(out, total, 8);
        le(out, id3 == null ? 0 : 28 + 52 + dataSize, 8);
        out.writeBytes("fmt ".getBytes(StandardCharsets.US_ASCII));
        le(out, 52, 8);
        le(out, 1, 4);
        le(out, 0, 4);
        le(out, channelType, 4);
        le(out, channels, 4);
        le(out, rate, 4);
        le(out, lsbFirst ? 1 : 8, 4);
        le(out, bytes * 8L, 8);
        le(out, 4096, 4);
        le(out, 0, 4);
        out.writeBytes("data".getBytes(StandardCharsets.US_ASCII));
        le(out, dataSize, 8);
        out.writeBytes(data.toByteArray());
        if (id3 != null) {
            out.writeBytes(id3);
        }
        return out.toByteArray();
    }

    static void chunk(ByteArrayOutputStream out, String id, byte[] body) {
        out.writeBytes(id.getBytes(StandardCharsets.US_ASCII));
        be(out, body.length, 8);
        out.writeBytes(body);
        if ((body.length & 1) != 0) {
            out.write(0);
        }
    }

    /**
     * Writes a DSDIFF file of uncompressed DSD, with an artist and title, a comment and two track markers.
     *
     * @param rate the DSD rate in hertz
     * @param channelIds the channel identifiers of the {@code CHNL} chunk
     * @param planes one plane per channel, most significant bit first
     * @param markers the sample positions of the {@code TrackStart} markers, titled "Track 1", "Track 2"...
     * @return the file
     */
    public static byte[] dsdiff(int rate, String[] channelIds, byte[][] planes, long... markers) {
        var channels = planes.length;
        var bytes = planes[0].length;
        var properties = new ByteArrayOutputStream();
        properties.writeBytes("SND ".getBytes(StandardCharsets.US_ASCII));
        var fs = new ByteArrayOutputStream();
        be(fs, rate, 4);
        chunk(properties, "FS  ", fs.toByteArray());
        var chnl = new ByteArrayOutputStream();
        be(chnl, channels, 2);
        for (var id : channelIds) {
            chnl.writeBytes(String.format("%-4s", id).getBytes(StandardCharsets.US_ASCII));
        }
        chunk(properties, "CHNL", chnl.toByteArray());
        var cmpr = new ByteArrayOutputStream();
        cmpr.writeBytes("DSD ".getBytes(StandardCharsets.US_ASCII));
        cmpr.write(14);
        cmpr.writeBytes("not compressed".getBytes(StandardCharsets.US_ASCII));
        cmpr.write(0);
        chunk(properties, "CMPR", cmpr.toByteArray());
        var sound = new byte[bytes * channels];
        for (var i = 0; i < bytes; i++) {
            for (var c = 0; c < channels; c++) {
                sound[i * channels + c] = planes[c][i];
            }
        }
        var info = new ByteArrayOutputStream();
        chunk(info, "DIAR", text("Smalt Ensemble"));
        chunk(info, "DITI", text("Sigma Delta Suite"));
        for (var m = 0; m < markers.length; m++) {
            var mark = new ByteArrayOutputStream();
            var seconds = markers[m] / rate;
            be(mark, seconds / 3600, 2);
            mark.write((int) (seconds / 60 % 60));
            mark.write((int) (seconds % 60));
            be(mark, markers[m] % rate, 4);
            be(mark, 0, 4);
            be(mark, 0, 2);
            be(mark, 0, 2);
            be(mark, 0, 2);
            var title = ("Track " + (m + 1)).getBytes(StandardCharsets.US_ASCII);
            be(mark, title.length, 4);
            mark.writeBytes(title);
            if ((title.length & 1) != 0) {
                mark.write(0);
            }
            chunk(info, "MARK", mark.toByteArray());
        }
        var comments = new ByteArrayOutputStream();
        be(comments, 1, 2);
        be(comments, 2026, 2);
        comments.writeBytes(new byte[]{10, 31, 12, 0});
        be(comments, 0, 2);
        be(comments, 0, 2);
        var comment = "Synthesized".getBytes(StandardCharsets.US_ASCII);
        be(comments, comment.length, 4);
        comments.writeBytes(comment);
        if ((comment.length & 1) != 0) {
            comments.write(0);
        }
        var form = new ByteArrayOutputStream();
        form.writeBytes("DSD ".getBytes(StandardCharsets.US_ASCII));
        var version = new ByteArrayOutputStream();
        be(version, 0x01050000, 4);
        chunk(form, "FVER", version.toByteArray());
        chunk(form, "PROP", properties.toByteArray());
        chunk(form, "DSD ", sound);
        chunk(form, "COMT", comments.toByteArray());
        chunk(form, "DIIN", info.toByteArray());
        var file = new ByteArrayOutputStream();
        chunk(file, "FRM8", form.toByteArray());
        return file.toByteArray();
    }

    private static byte[] text(String value) {
        var out = new ByteArrayOutputStream();
        var bytes = value.getBytes(StandardCharsets.US_ASCII);
        be(out, bytes.length, 4);
        out.writeBytes(bytes);
        return out.toByteArray();
    }
}
