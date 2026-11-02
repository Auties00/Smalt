package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jp2.Jp2Channel;
import com.github.auties00.smalt.image.jp2.Jp2ChannelType;
import com.github.auties00.smalt.image.jp2.Jp2Decoder;
import com.github.auties00.smalt.image.jp2.Jp2DecoderOptions;
import com.github.auties00.smalt.internal.heif.HeifComponentCodec;
import com.github.auties00.smalt.internal.heif.HeifComponents;
import com.github.auties00.smalt.internal.heif.HeifItem;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.io.MediaSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;

/**
 * Decodes JPEG 2000 image items of HEIF files ({@code j2k1}, ISO/IEC 15444-16) for the HEIF
 * decoders of the artifacts that do not depend on this one.
 *
 * <p>The item data is a JPEG 2000 codestream; the channel definition, component mapping and
 * palette boxes of the item's {@code j2kH} property apply to it as they would in a JP2 header, so
 * a palette expands an index component into the channels its component mapping lists. Without a
 * channel definition, the first three channels are colors when there are at least three, and the
 * first one otherwise. One color channel is monochrome. Three color channels are RGB when the codestream applies a
 * multiple component transformation, when a color specification box of the {@code j2kH}
 * property declares sRGB, or when the item's {@code nclx} color property has the identity matrix;
 * they are Y'CbCr when a color specification declares sYCC, when the second and third channels
 * are subsampled, or when the {@code nclx} property has another matrix; otherwise they are RGB.
 *
 * @implNote This implementation wraps the codestream and the boxes of the {@code j2kH} property in
 * a JP2 file and decodes its channels with {@link Jp2Decoder#decodeChannels(MediaSource,
 * Jp2DecoderOptions)}.
 */
public final class Jp2HeifComponentCodec implements HeifComponentCodec {
    // TODO: decode signed samples, samples above 16 bits and four color channels (CMYK)
    /**
     * The JP2 signature box.
     */
    private static final byte[] SIGNATURE = {0, 0, 0, 12, 'j', 'P', ' ', ' ', 0x0D, 0x0A, (byte) 0x87, 0x0A};

    /**
     * The enumerated color space of sRGB.
     */
    private static final int SRGB = 16;

    /**
     * The enumerated color space of grayscale.
     */
    private static final int GRAY = 17;

    /**
     * The enumerated color space of sYCC.
     */
    private static final int SYCC = 18;

    /**
     * Constructs the codec.
     */
    public Jp2HeifComponentCodec() {
    }

    @Override
    public String itemType() {
        return "j2k1";
    }

    @Override
    public HeifComponents decode(String name, byte[] payload, HeifItem item, MediaLimits limits) {
        var header = item.property(HeifProperty.Jpeg2000Header.class);
        var codestream = Codestream.parse(name, payload);
        var declared = 0;
        var jp2h = new ByteArrayOutputStream();
        writeBox(jp2h, "ihdr", ihdr(codestream));
        var extra = new ByteArrayOutputStream();
        var channelCount = codestream.components();
        if (header != null) {
            for (var box : header.boxes()) {
                var type = new String(box, 4, 4, StandardCharsets.ISO_8859_1);
                switch (type) {
                    case "cdef", "pclr" -> extra.writeBytes(box);
                    case "cmap" -> {
                        extra.writeBytes(box);
                        channelCount = (box.length - 8) / 4;
                    }
                    case "colr" -> {
                        if (declared == 0 && box.length >= 15 && box[8] == 1) {
                            declared = ((box[11] & 0xFF) << 24) | ((box[12] & 0xFF) << 16) | ((box[13] & 0xFF) << 8)
                                    | (box[14] & 0xFF);
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        var colr = new byte[7];
        colr[0] = 1;
        var space = channelCount >= 3 ? SRGB : GRAY;
        colr[6] = (byte) space;
        writeBox(jp2h, "colr", colr);
        jp2h.writeBytes(extra.toByteArray());
        var file = new ByteArrayOutputStream(payload.length + 256);
        file.writeBytes(SIGNATURE);
        writeBox(file, "ftyp", new byte[]{'j', 'p', '2', ' ', 0, 0, 0, 0, 'j', 'p', '2', ' '});
        writeBox(file, "jp2h", jp2h.toByteArray());
        var length = 8L + payload.length;
        file.write((int) (length >>> 24));
        file.write((int) (length >>> 16));
        file.write((int) (length >>> 8));
        file.write((int) length);
        file.writeBytes(new byte[]{'j', 'p', '2', 'c'});
        file.writeBytes(payload);
        var image = Jp2Decoder.decodeChannels(MediaSource.of(file.toByteArray()),
                Jp2DecoderOptions.builder().limits(limits).build());
        var colors = new ArrayList<Jp2Channel>();
        Jp2Channel opacity = null;
        var premultiplied = false;
        for (var channel : image.channels()) {
            if (channel.type() == Jp2ChannelType.COLOR && channel.association() > 0) {
                colors.add(channel);
            } else if (opacity == null && (channel.type() == Jp2ChannelType.OPACITY
                    || channel.type() == Jp2ChannelType.PREMULTIPLIED_OPACITY)) {
                opacity = channel;
                premultiplied = channel.type() == Jp2ChannelType.PREMULTIPLIED_OPACITY;
            }
        }
        colors.sort(Comparator.comparingInt(Jp2Channel::association));
        int[] types;
        switch (colors.size()) {
            case 1 -> types = new int[]{HeifComponents.MONOCHROME};
            case 3 -> {
                var subsampled = colors.get(1).subsamplingX() != colors.getFirst().subsamplingX()
                        || colors.get(1).subsamplingY() != colors.getFirst().subsamplingY();
                var nclx = item.property(HeifProperty.ColorNclx.class);
                boolean ycc;
                if (codestream.transform()) {
                    ycc = false;
                } else if (declared == SRGB || declared == SYCC) {
                    ycc = declared == SYCC;
                } else if (subsampled) {
                    ycc = true;
                } else {
                    ycc = nclx != null && nclx.matrix() != 0;
                }
                types = ycc ? new int[]{HeifComponents.Y, HeifComponents.CB, HeifComponents.CR}
                        : new int[]{HeifComponents.RED, HeifComponents.GREEN, HeifComponents.BLUE};
            }
            default -> throw new MediaException.Unsupported(name, "JPEG 2000 items with " + colors.size()
                    + " color channels");
        }
        var planes = new ArrayList<HeifComponents.Plane>();
        for (var i = 0; i < colors.size(); i++) {
            planes.add(plane(name, colors.get(i), types[i], image.x0(), image.y0()));
        }
        if (opacity != null) {
            planes.add(plane(name, opacity, premultiplied ? HeifComponents.PREMULTIPLIED_ALPHA : HeifComponents.ALPHA,
                    image.x0(), image.y0()));
        }
        return new HeifComponents(image.width(), image.height(), planes);
    }

    /**
     * Converts a decoded channel into a component plane.
     *
     * @param name the format name, for messages
     * @param channel the decoded JPEG 2000 channel
     * @param type the component type
     * @param x0 the left edge of the image on the reference grid
     * @param y0 the top edge of the image on the reference grid
     * @return the plane
     */
    private static HeifComponents.Plane plane(String name, Jp2Channel channel,
                                              int type, long x0, long y0) {
        if (channel.signed()) {
            throw new MediaException.Unsupported(name, "JPEG 2000 items with signed samples");
        }
        if (channel.precision() > 16) {
            throw new MediaException.Unsupported(name, "JPEG 2000 items with " + channel.precision() + "-bit samples");
        }
        if (x0 % channel.subsamplingX() != 0 || y0 % channel.subsamplingY() != 0) {
            throw new MediaException.Unsupported(name, "JPEG 2000 items whose image offset splits the subsampled "
                    + "chroma");
        }
        var samples = channel.samples();
        var out = new short[samples.length];
        var max = (1 << channel.precision()) - 1;
        for (var i = 0; i < samples.length; i++) {
            out[i] = (short) Math.clamp(samples[i], 0, max);
        }
        return new HeifComponents.Plane(type, channel.width(), channel.height(), channel.subsamplingX(),
                channel.subsamplingY(), channel.precision(), out);
    }

    /**
     * Builds the payload of an image header box from the codestream's size marker.
     *
     * @param codestream the parsed main header
     * @return the payload
     */
    private static byte[] ihdr(Codestream codestream) {
        var out = new byte[14];
        putU32(out, 0, codestream.height());
        putU32(out, 4, codestream.width());
        out[8] = (byte) (codestream.components() >>> 8);
        out[9] = (byte) codestream.components();
        out[10] = (byte) codestream.bitsPerComponent();
        out[11] = 7;
        return out;
    }

    /**
     * Writes a big-endian unsigned 32-bit value.
     *
     * @param out the array
     * @param offset the first byte
     * @param value the unsigned value, at most 32 bits
     */
    private static void putU32(byte[] out, int offset, long value) {
        out[offset] = (byte) (value >>> 24);
        out[offset + 1] = (byte) (value >>> 16);
        out[offset + 2] = (byte) (value >>> 8);
        out[offset + 3] = (byte) value;
    }

    /**
     * Writes a box.
     *
     * @param out the stream
     * @param type the four-character type
     * @param payload the box content
     */
    private static void writeBox(ByteArrayOutputStream out, String type, byte[] payload) {
        var size = 8 + payload.length;
        out.write(size >>> 24);
        out.write(size >>> 16);
        out.write(size >>> 8);
        out.write(size);
        out.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(payload);
    }

    /**
     * The facts of a codestream main header this codec needs.
     *
     * @param width the image width on the reference grid, {@code Xsiz - XOsiz}
     * @param height the image height on the reference grid, {@code Ysiz - YOsiz}
     * @param components the number of components, {@code Csiz}
     * @param bitsPerComponent the {@code BPC} field of an image header: the depth minus one and the
     * sign bit when every component shares them, 255 otherwise
     * @param transform whether the coding style default applies a multiple component
     * transformation
     */
    private record Codestream(long width, long height, int components, int bitsPerComponent, boolean transform) {
        /**
         * Parses the size marker and the coding style default of a main header.
         *
         * @param name the format name, for messages
         * @param data the codestream
         * @return the facts
         * @throws MediaException.Malformed if the codestream does not start with the start of
         * codestream and size markers or is truncated
         */
        static Codestream parse(String name, byte[] data) {
            if (data.length < 42 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0x4F || (data[2] & 0xFF) != 0xFF
                    || (data[3] & 0xFF) != 0x51) {
                throw new MediaException.Malformed(name + ": the JPEG 2000 item does not start with a codestream");
            }
            var sizLength = u16(data, 4);
            var xsiz = u32(data, 8);
            var ysiz = u32(data, 12);
            var xosiz = u32(data, 16);
            var yosiz = u32(data, 20);
            var count = u16(data, 40);
            if (4 + sizLength > data.length || 38 + 3L * count > sizLength || count == 0 || xsiz <= xosiz
                    || ysiz <= yosiz) {
                throw new MediaException.Malformed(name + ": the JPEG 2000 item has an invalid size marker");
            }
            var bpc = data[42] & 0xFF;
            for (var c = 1; c < count; c++) {
                if ((data[42 + 3 * c] & 0xFF) != bpc) {
                    bpc = 255;
                    break;
                }
            }
            var transform = false;
            var position = 4 + sizLength;
            while (position + 4 <= data.length && (data[position] & 0xFF) == 0xFF) {
                var marker = data[position + 1] & 0xFF;
                if (marker == 0x90 || marker == 0x93 || marker == 0xD9) {
                    break;
                }
                var length = u16(data, position + 2);
                if (marker == 0x52 && length >= 6 && position + 8 < data.length) {
                    transform = (data[position + 8] & 0xFF) != 0;
                }
                if (length < 2) {
                    break;
                }
                position += 2 + length;
            }
            return new Codestream(xsiz - xosiz, ysiz - yosiz, count, bpc, transform);
        }

        /**
         * {@return a big-endian unsigned 16-bit value}
         *
         * @param data the bytes
         * @param offset the first byte
         */
        private static int u16(byte[] data, int offset) {
            return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
        }

        /**
         * {@return a big-endian unsigned 32-bit value}
         *
         * @param data the bytes
         * @param offset the first byte
         */
        private static long u32(byte[] data, int offset) {
            return ((long) u16(data, offset) << 16) | u16(data, offset + 2);
        }
    }
}
