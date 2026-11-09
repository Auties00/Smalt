package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jp2.Jp2Channel;
import com.github.auties00.smalt.image.jp2.Jp2ChannelType;
import com.github.auties00.smalt.image.jp2.Jp2Decoder;
import com.github.auties00.smalt.image.jp2.Jp2DecoderOptions;
import com.github.auties00.smalt.image.jp2.Jp2Image;
import com.github.auties00.smalt.image.jp2.internal.Jp2Pictures;
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
 * channel definition, the first four channels are colors when a color specification box of the
 * {@code j2kH} property declares CMYK or YCCK, or an ICC profile of the {@code j2kH} property or of
 * the item's {@code colr} property has a CMYK color space, and there are at least four; otherwise
 * the first three channels are colors when there are at least three, and the first one otherwise.
 * One color channel is monochrome. Three color channels are RGB when the codestream applies a
 * multiple component transformation, when a color specification box of the {@code j2kH}
 * property declares sRGB, or when the item's {@code nclx} color property has the identity matrix;
 * they are Y'CbCr when a color specification declares sYCC, when the second and third channels
 * are subsampled, or when the {@code nclx} property has another matrix; otherwise they are RGB.
 * Four color channels are cyan, magenta, yellow and black inks, 0 being no ink; when a color
 * specification declares YCCK, the first three are Y'CbCr (BT.601) and are converted to the cyan,
 * magenta and yellow inks that complement their R'G'B' values, at the precision of the luma.
 *
 * <p>Signed samples are offset by {@code 2^(n-1)} for a precision of {@code n} bits, so that their
 * range maps onto the unsigned one; components of more than 16 bits are reported as deep planes,
 * keeping their 31 most significant bits when they are wider.
 *
 * @implNote This implementation wraps the codestream and the boxes of the {@code j2kH} property in
 * a JP2 file and decodes its channels with {@link Jp2Decoder#decodeChannels(MediaSource,
 * Jp2DecoderOptions)}.
 */
public final class Jp2HeifComponentCodec implements HeifComponentCodec {
    /**
     * The JP2 signature box.
     */
    private static final byte[] SIGNATURE = {0, 0, 0, 12, 'j', 'P', ' ', ' ', 0x0D, 0x0A, (byte) 0x87, 0x0A};

    /**
     * The enumerated color space of CMYK.
     */
    private static final int CMYK = 12;

    /**
     * The enumerated color space of YCCK.
     */
    private static final int YCCK = 13;

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
     * The precision above which a component is reported as a deep plane.
     */
    private static final int SHORT_BITS = 16;

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
        var inkProfile = isCmykProfile(item.property(HeifProperty.ColorIcc.class));
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
                        if (box.length >= 15 && box[8] == 1) {
                            if (declared == 0) {
                                declared = ((box[11] & 0xFF) << 24) | ((box[12] & 0xFF) << 16)
                                        | ((box[13] & 0xFF) << 8) | (box[14] & 0xFF);
                            }
                        } else if (box.length >= 31 && (box[8] == 2 || box[8] == 3)) {
                            inkProfile |= box[27] == 'C' && box[28] == 'M' && box[29] == 'Y' && box[30] == 'K';
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        var inks = channelCount >= 4 && (declared == CMYK || declared == YCCK || inkProfile);
        var colr = new byte[7];
        colr[0] = 1;
        var space = inks ? (declared == YCCK ? YCCK : CMYK) : channelCount >= 3 ? SRGB : GRAY;
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
        var planes = new ArrayList<HeifComponents.Plane>();
        switch (colors.size()) {
            case 1 -> planes.add(plane(name, colors.getFirst(), HeifComponents.MONOCHROME, image));
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
                var types = ycc ? new int[]{HeifComponents.Y, HeifComponents.CB, HeifComponents.CR}
                        : new int[]{HeifComponents.RED, HeifComponents.GREEN, HeifComponents.BLUE};
                for (var i = 0; i < 3; i++) {
                    planes.add(plane(name, colors.get(i), types[i], image));
                }
            }
            case 4 -> {
                var types = new int[]{HeifComponents.CYAN, HeifComponents.MAGENTA, HeifComponents.YELLOW,
                        HeifComponents.BLACK};
                if (space == YCCK) {
                    var converted = Jp2Pictures.ycckInks(image, colors);
                    var depth = Math.min(colors.getFirst().precision(), HeifComponents.MAX_BIT_DEPTH);
                    for (var i = 0; i < 3; i++) {
                        planes.add(fullPlane(types[i], image, depth, converted[i]));
                    }
                    planes.add(plane(name, colors.get(3), types[3], image));
                } else {
                    for (var i = 0; i < 4; i++) {
                        planes.add(plane(name, colors.get(i), types[i], image));
                    }
                }
            }
            default -> throw new MediaException.Unsupported(name, "JPEG 2000 items with " + colors.size()
                    + " color channels");
        }
        if (opacity != null) {
            planes.add(plane(name, opacity, premultiplied ? HeifComponents.PREMULTIPLIED_ALPHA : HeifComponents.ALPHA,
                    image));
        }
        return new HeifComponents(image.width(), image.height(), planes);
    }

    /**
     * {@return whether an ICC profile property has the CMYK data color space}
     *
     * @param icc the property, or {@code null}
     */
    private static boolean isCmykProfile(HeifProperty.ColorIcc icc) {
        if (icc == null) {
            return false;
        }
        var profile = icc.profile();
        return profile.length >= 20 && profile[16] == 'C' && profile[17] == 'M' && profile[18] == 'Y'
                && profile[19] == 'K';
    }

    /**
     * Converts a decoded channel into a component plane, signed samples offset to the unsigned
     * range.
     *
     * @param name the format name, for messages
     * @param channel the decoded JPEG 2000 channel
     * @param type the component type
     * @param image the decoded image, for its offset on the reference grid
     * @return the plane: of up to 16 bits in {@link HeifComponents.Plane#samples()}, deeper in
     * {@link HeifComponents.Plane#values()}, keeping the 31 most significant bits of a wider channel
     */
    private static HeifComponents.Plane plane(String name, Jp2Channel channel, int type, Jp2Image image) {
        if (image.x0() % channel.subsamplingX() != 0 || image.y0() % channel.subsamplingY() != 0) {
            throw new MediaException.Unsupported(name, "JPEG 2000 items whose image offset splits the subsampled "
                    + "chroma");
        }
        var precision = channel.precision();
        var offset = channel.signed() ? 1L << (precision - 1) : 0;
        var max = (1L << precision) - 1;
        var shift = Math.max(0, precision - HeifComponents.MAX_BIT_DEPTH);
        var depth = precision - shift;
        var count = channel.width() * channel.height();
        var samples = channel.samples();
        var wide = channel.wideSamples();
        if (depth <= SHORT_BITS) {
            var out = new short[count];
            for (var i = 0; i < count; i++) {
                out[i] = (short) Math.clamp(samples[i] + offset, 0, max);
            }
            return new HeifComponents.Plane(type, channel.width(), channel.height(), channel.subsamplingX(),
                    channel.subsamplingY(), depth, out);
        }
        var out = new int[count];
        for (var i = 0; i < count; i++) {
            var value = samples != null ? samples[i] : wide[i];
            out[i] = (int) (Math.clamp(value + offset, 0, max) >>> shift);
        }
        return new HeifComponents.Plane(type, channel.width(), channel.height(), channel.subsamplingX(),
                channel.subsamplingY(), depth, null, out);
    }

    /**
     * Wraps unsigned samples of the image size into a component plane.
     *
     * @param type the component type
     * @param image the decoded image, for its size
     * @param depth the precision of the samples, at most {@link HeifComponents#MAX_BIT_DEPTH}
     * @param samples the samples, row-major, within the precision
     * @return the plane
     */
    private static HeifComponents.Plane fullPlane(int type, Jp2Image image, int depth, int[] samples) {
        if (depth > SHORT_BITS) {
            return new HeifComponents.Plane(type, image.width(), image.height(), 1, 1, depth, null, samples);
        }
        var out = new short[samples.length];
        for (var i = 0; i < samples.length; i++) {
            out[i] = (short) samples[i];
        }
        return new HeifComponents.Plane(type, image.width(), image.height(), 1, 1, depth, out);
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
