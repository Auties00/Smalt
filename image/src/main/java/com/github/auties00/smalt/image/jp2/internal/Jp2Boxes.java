/* Ports OpenJPEG 2.5.3 (jp2.c). Copyright (c) 2002-2014, Universite catholique de Louvain (UCL), Belgium, and
   the OpenJPEG authors; BSD 2-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.tiff.TiffExif;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads the boxes of a JP2 or JPX file (ISO/IEC 15444-1 annex I and ISO/IEC 15444-2 annex M): the codestreams,
 * stored contiguously or in fragment tables, the header of each codestream (palette and component mapping), the
 * compositing layers with their color specification, channel definitions, opacity and codestream registration, the
 * composition and its instructions, and the EXIF and XMP metadata of {@code uuid} and {@code xml } boxes.
 *
 * <p>A raw codestream (starting with the {@code SOC} and {@code SIZ} markers) is accepted too and yields one
 * codestream and one compositing layer. Unknown boxes are skipped. The boxes of the JP2 header are the defaults of
 * every codestream and compositing layer, which their own header boxes override or augment (M.5.2.1);
 * cross-reference boxes in those headers stand for the box they point to (M.5.2.2). Without compositing layer header
 * boxes, each codestream is one compositing layer. Of several color specifications the one of highest precedence
 * with a method this reader understands is used, the first one on a tie, as I.5.3.3 and M.11.7.2 ask. The methods
 * are enumeration, the restricted and the any ICC methods, and the parameterized color space (ISO/IEC 15444-15
 * D.4.3 and M.11.7.3.4) for full-range R'G'B' samples of at least three components, which is read as the ICC
 * profile of its primaries and transfer characteristics.
 *
 * <p>Compositing layer extension boxes ({@code jclx}) are ignored, as M.5.2.4 allows: the top-level layers and the
 * composition box describe the file without them.
 *
 * @implNote This implementation follows OpenJPEG's {@code jp2.c} in its checks of the palette,
 * component mapping and channel definition boxes ({@code opj_jp2_check_color}).
 */
public final class Jp2Boxes {
    /**
     * The JP2 signature box: its length, type {@code jP  } and content {@code 0D 0A 87 0A}.
     */
    static final byte[] SIGNATURE = {0, 0, 0, 12, 'j', 'P', ' ', ' ', 0x0D, 0x0A, (byte) 0x87, 0x0A};

    /**
     * The UUID of the box that holds an EXIF block, {@code JpgTiffExif->JP2}.
     */
    static final byte[] EXIF_UUID = {'J', 'p', 'g', 'T', 'i', 'f', 'f', 'E', 'x', 'i', 'f', '-', '>', 'J', 'P', '2'};

    /**
     * The UUID of the box that holds an XMP packet.
     */
    static final byte[] XMP_UUID = {(byte) 0xBE, 0x7A, (byte) 0xCF, (byte) 0xCB, (byte) 0x97, (byte) 0xA9, 0x42,
            (byte) 0xE8, (byte) 0x9C, 0x71, (byte) 0x99, (byte) 0x94, (byte) 0x91, (byte) 0xE3, (byte) 0xAF, (byte) 0xAC};

    /**
     * The UUID of the box that holds IPTC-IIM data sets, {@code 33C7A4D2-B81D-4723-A0BA-F1A3E097AD38}.
     */
    static final byte[] IPTC_UUID = {0x33, (byte) 0xC7, (byte) 0xA4, (byte) 0xD2, (byte) 0xB8, 0x1D, 0x47, 0x23,
            (byte) 0xA0, (byte) 0xBA, (byte) 0xF1, (byte) 0xA3, (byte) 0xE0, (byte) 0x97, (byte) 0xAD, 0x38};

    /**
     * The largest number of boxes examined.
     */
    private static final int MAX_BOXES = 1 << 16;

    /**
     * The deepest nesting of superboxes followed.
     */
    private static final int MAX_DEPTH = 8;

    /**
     * The input bytes.
     */
    private final byte[] data;

    /**
     * The number of valid bytes of {@link #data}.
     */
    private final int length;

    /**
     * The resource bounds checked before a fragmented codestream is assembled.
     */
    private final MediaLimits limits;

    /**
     * The number of boxes examined so far.
     */
    private int steps;

    /**
     * The codestreams, in file order (M.5.1.3).
     */
    private final List<Stream> codestreams = new ArrayList<>();

    /**
     * The header boxes of the JP2 header box, the defaults of every codestream and compositing layer.
     */
    private final Header defaults = new Header();

    /**
     * The headers of the codestream header boxes, in file order.
     */
    private final List<Header> codestreamHeaders = new ArrayList<>();

    /**
     * The headers of the compositing layer header boxes, in file order.
     */
    private final List<Header> layerHeaders = new ArrayList<>();

    /**
     * The URL of each entry of the data reference box, from entry 1.
     */
    private final List<String> references = new ArrayList<>();

    /**
     * The composition, or {@code null} when the file has no composition box.
     */
    private Composition composition;

    /**
     * The EXIF block of a {@code uuid} box, or {@code null}.
     */
    private byte[] exif;

    /**
     * The XMP packet of a {@code uuid} or {@code xml } box, or {@code null}.
     */
    private byte[] xmp;

    /**
     * Holds a palette (I.5.3.4).
     *
     * @param entries the number of entries
     * @param depths the bit depth of each column, from 1 to 38
     * @param signed whether each column is signed
     * @param values the entries, row-major ({@code entries * columns}), sign-extended
     */
    public record Palette(int entries, int[] depths, boolean[] signed, long[] values) {
        /**
         * Returns the number of columns.
         *
         * @return the column count
         */
        public int columns() {
            return depths.length;
        }
    }

    /**
     * Holds one fragment of a fragment list (M.11.3.1).
     *
     * @param offset the offset of the fragment's first byte from the start of the file
     * @param length the length of the fragment in bytes
     * @param reference the data reference: 0 for this file, otherwise an entry of the data reference box
     */
    record Fragment(long offset, long length, int reference) {
    }

    /**
     * Locates one codestream: a contiguous range of the file, or the fragments of a fragment table.
     *
     * @param start the index of the first byte of a contiguous codestream
     * @param end the end of a contiguous codestream (exclusive)
     * @param fragments the fragments of a fragmented codestream, or {@code null} for a contiguous one
     */
    record Stream(int start, int end, List<Fragment> fragments) {
    }

    /**
     * Holds the bytes of a codestream ready to parse.
     *
     * @param data the array holding it
     * @param start the index of its first byte
     * @param end the end of the codestream (exclusive)
     */
    public record CodestreamBytes(byte[] data, int start, int end) {
    }

    /**
     * Holds one color specification box (I.5.3.3 and M.11.7.2).
     *
     * @param method the specification method: 1 enumerated, 2 restricted ICC, 3 any ICC, 5 parameterized
     * @param precedence the {@code PREC} field
     * @param enumerated the enumerated color space, or {@code -1}
     * @param parameters the enumerated parameters ({@code EP}) of the enumerated method, or {@code null}
     * @param icc the ICC profile of an ICC or parameterized method, or {@code null}
     */
    record Color(int method, int precedence, int enumerated, byte[] parameters, byte[] icc) {
    }

    /**
     * Holds the opacity box of a compositing layer (M.11.7.6).
     *
     * @param type 0 for a last opacity channel, 1 for a last premultiplied opacity channel, 2 for a chroma key
     * @param channels the number of chroma-key values ({@code NCH}), 0 for the other types
     * @param key the chroma-key values as stored, each padded to whole bytes; empty for the other types
     */
    public record Opacity(int type, int channels, byte[] key) {
    }

    /**
     * Holds the codestream registration box of a compositing layer (M.11.7.7).
     *
     * @param gridX the number of horizontal fractional grid points per registration grid unit ({@code XS})
     * @param gridY the number of vertical fractional grid points per registration grid unit ({@code YS})
     * @param codestreams the codestream of each entry ({@code CDN})
     * @param resolutionX the horizontal distance between reference grid points of each codestream ({@code XR})
     * @param resolutionY the vertical distance between reference grid points of each codestream ({@code YR})
     * @param offsetX the horizontal offset of each codestream ({@code XO})
     * @param offsetY the vertical offset of each codestream ({@code YO})
     */
    public record Registration(int gridX, int gridY, int[] codestreams, int[] resolutionX, int[] resolutionY,
                               int[] offsetX, int[] offsetY) {
    }

    /**
     * Holds the boxes of a JP2 header, codestream header or compositing layer header box.
     */
    private static final class Header {
        /**
         * The color specifications, in file order.
         */
        final List<Color> colors = new ArrayList<>();

        /**
         * Whether the header has a color group box, whose color specifications replace the defaults even when none
         * is understood.
         */
        boolean colorGroup;

        /**
         * The palette, or {@code null}.
         */
        Palette palette;

        /**
         * The component mapping: for each channel, the component, mapping type and palette column, or {@code null}.
         */
        int[][] mapping;

        /**
         * The channel definitions: for each, the channel, type and association, or {@code null}.
         */
        int[][] definitions;

        /**
         * The opacity box, or {@code null}.
         */
        Opacity opacity;

        /**
         * The codestream registration, or {@code null}.
         */
        Registration registration;

        /**
         * The number of components the image header declares, 0 without one.
         */
        int components;
    }

    /**
     * Describes one compositing layer, its header boxes resolved against the defaults (M.5.2.1).
     *
     * @param codestreams the codestreams whose channels the layer combines, in channel order
     * @param registration the codestream registration, or {@code null} when the reference grids align directly
     * @param enumerated the enumerated color space, or {@code -1}
     * @param parameters the enumerated parameters of the color space, or {@code null}
     * @param icc the ICC profile, or {@code null}
     * @param definitions the channel definitions: for each, the channel, type and association, or {@code null}
     * @param opacity the opacity box, or {@code null}
     */
    public record Layer(int[] codestreams, Registration registration, int enumerated, byte[] parameters, byte[] icc,
                        int[][] definitions, Opacity opacity) {
    }

    /**
     * Holds one compositing instruction (M.11.10.2.1), with the defaults of absent fields filled in.
     *
     * @param x the horizontal placement in the render area ({@code XO})
     * @param y the vertical placement ({@code YO})
     * @param width the rendered width, or {@code -1} for the width of the cropped layer
     * @param height the rendered height, or {@code -1} for the height of the cropped layer
     * @param persist whether the rendered samples stay as the background of the next instruction
     * @param life the duration in timer ticks, {@code 2^31 - 1} for an indefinite pause
     * @param nextUse the number of instructions before the layer is reused, 0 for never
     * @param cropX the left edge of the crop ({@code XC})
     * @param cropY the top edge of the crop ({@code YC})
     * @param cropWidth the crop width, or {@code -1} for the rest of the layer
     * @param cropHeight the crop height, or {@code -1} for the rest of the layer
     * @param rotation the {@code ROT} field: 0 for the codestream's orientation, 1 to 4 for 0, 90, 180 or 270
     * degrees clockwise, plus 16 to flip left to right after the rotation
     */
    public record Instruction(long x, long y, long width, long height, boolean persist, long life, long nextUse,
                              long cropX, long cropY, long cropWidth, long cropHeight, int rotation) {
    }

    /**
     * Holds an instruction set box (M.11.10.2).
     *
     * @param repeat the number of times the set repeats after its first execution, 65535 for indefinitely
     * @param tick the duration of a timer tick in milliseconds
     * @param instructions the instructions, in execution order
     */
    public record InstructionSet(int repeat, long tick, List<Instruction> instructions) {
    }

    /**
     * Holds the composition box (M.11.10).
     *
     * @param width the width of the render area
     * @param height the height of the render area
     * @param globalRepeat the number of times the whole composition repeats after its first execution, 255 for
     * indefinitely
     * @param sets the instruction sets, in order
     */
    public record Composition(long width, long height, int globalRepeat, List<InstructionSet> sets) {
    }

    /**
     * Constructs a reader over the input.
     *
     * @param data the input bytes
     * @param length the number of valid bytes
     * @param limits the resource bounds
     */
    private Jp2Boxes(byte[] data, int length, MediaLimits limits) {
        this.data = data;
        this.length = length;
        this.limits = limits;
    }

    /**
     * Reads the boxes of a JP2 or JPX file, or locates a raw codestream, under the default limits.
     *
     * @param data the input bytes
     * @param length the number of valid bytes
     * @return the boxes read
     * @throws MediaException.Malformed if the input is neither a JP2/JPX file nor a codestream, or a box is invalid
     */
    public static Jp2Boxes read(byte[] data, int length) {
        return read(data, length, MediaLimits.DEFAULT);
    }

    /**
     * Reads the boxes of a JP2 or JPX file, or locates a raw codestream.
     *
     * @param data the input bytes
     * @param length the number of valid bytes
     * @param limits the resource bounds of the codestream and layer counts
     * @return the boxes read
     * @throws MediaException.Malformed if the input is neither a JP2/JPX file nor a codestream, or a box is invalid
     * @throws MediaException.LimitExceeded if the file holds more codestreams or layers than the frame limit
     */
    public static Jp2Boxes read(byte[] data, int length, MediaLimits limits) {
        var boxes = new Jp2Boxes(data, length, limits);
        if (length >= 4 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0x4F && (data[2] & 0xFF) == 0xFF
                && (data[3] & 0xFF) == 0x51) {
            boxes.codestreams.add(new Stream(0, length, null));
            return boxes;
        }
        if (length < SIGNATURE.length || !Arrays.equals(data, 0, SIGNATURE.length, SIGNATURE, 0, SIGNATURE.length)) {
            throw new MediaException.Malformed("not a JPEG 2000 file");
        }
        boxes.readTop(new View(data, SIGNATURE.length, length), 0);
        if (boxes.codestreams.isEmpty()) {
            throw new MediaException.Malformed("the JPEG 2000 file has no codestream");
        }
        LimitChecks.checkFrames(limits, boxes.codestreams.size());
        LimitChecks.checkFrames(limits, boxes.layerCount());
        boxes.validate(boxes.defaults);
        for (var header : boxes.codestreamHeaders) {
            boxes.validate(header);
        }
        return boxes;
    }

    /**
     * A byte range of boxes: the file, the contents of a superbox, or the bytes a cross-reference assembles.
     *
     * @param bytes the array holding the boxes
     * @param start the index of the first box
     * @param end the end of the range (exclusive)
     */
    private record View(byte[] bytes, int start, int end) {
        /**
         * Reads a big-endian 16-bit value.
         *
         * @param position the index of the first byte
         * @return the value
         */
        int u16(int position) {
            return ByteArrays.u16be(bytes, position);
        }

        /**
         * Reads a big-endian 32-bit unsigned value.
         *
         * @param position the index of the first byte
         * @return the value
         */
        long u32(int position) {
            return ByteArrays.u32be(bytes, position);
        }

        /**
         * Returns the sub-range of a box's contents.
         *
         * @param box the box whose contents are returned
         * @return the view of its contents
         */
        View contents(Box box) {
            return new View(bytes, box.content, box.end);
        }
    }

    /**
     * Describes one box.
     *
     * @param type the four-character type
     * @param content the index of its content
     * @param end its end
     */
    private record Box(String type, int content, int end) {
        /**
         * Returns the size of its content.
         *
         * @return the content length
         */
        int size() {
            return end - content;
        }
    }

    /**
     * Reads the boxes of a range one after the other.
     *
     * @param view the range
     * @param handler receives each box
     */
    private void forEach(View view, java.util.function.Consumer<Box> handler) {
        var position = view.start();
        while (view.end() - position >= 8 && steps++ < MAX_BOXES) {
            var box = box(view, position);
            handler.accept(box);
            if (box.end >= view.end()) {
                break;
            }
            position = box.end;
        }
    }

    /**
     * Reads the top-level boxes, or the contents of a multiple codestream box.
     *
     * @param view the range
     * @param depth the nesting depth
     */
    private void readTop(View view, int depth) {
        if (depth > MAX_DEPTH) {
            throw new MediaException.Malformed("JPEG 2000 boxes nested too deeply");
        }
        forEach(view, box -> {
            switch (box.type) {
                case "jp2h" -> readHeader(view.contents(box), defaults, depth + 1);
                case "jpch" -> {
                    var header = new Header();
                    readHeader(view.contents(box), header, depth + 1);
                    codestreamHeaders.add(header);
                }
                case "jplh" -> {
                    var header = new Header();
                    readHeader(view.contents(box), header, depth + 1);
                    layerHeaders.add(header);
                }
                case "jp2c" -> codestreams.add(new Stream(box.content, box.end, null));
                case "ftbl" -> codestreams.add(new Stream(0, 0, readFragmentTable(view.contents(box))));
                case "j2cx" -> readTop(view.contents(box), depth + 1);
                case "dtbl" -> readReferences(view, box);
                case "comp" -> {
                    if (composition == null) {
                        composition = readComposition(view.contents(box));
                    }
                }
                case "uuid" -> readUuid(view, box);
                case "xml " -> {
                    if (xmp == null && contains(view, box, "x:xmpmeta")) {
                        xmp = Arrays.copyOfRange(view.bytes(), box.content, box.end);
                    }
                }
                default -> {
                    // jclx, asoc, rreq, mdat and the other boxes carry nothing the decoder needs
                }
            }
        });
    }

    /**
     * Reads the boxes of a JP2 header, codestream header or compositing layer header box.
     *
     * @param view the contents of the header box
     * @param header receives the boxes
     * @param depth the nesting depth
     */
    private void readHeader(View view, Header header, int depth) {
        if (depth > MAX_DEPTH) {
            throw new MediaException.Malformed("JPEG 2000 boxes nested too deeply");
        }
        forEach(view, box -> readHeaderBox(view, box.type, view.contents(box), header, depth));
    }

    /**
     * Reads one box of a header.
     *
     * @param view the range holding the box
     * @param type the box type
     * @param contents the box's contents
     * @param header receives the box
     * @param depth the nesting depth
     */
    private void readHeaderBox(View view, String type, View contents, Header header, int depth) {
        var content = contents.start();
        var size = contents.end() - contents.start();
        var bytes = contents.bytes();
        switch (type) {
            case "ihdr" -> {
                if (size < 14) {
                    throw new MediaException.Malformed("truncated JPEG 2000 image header box");
                }
                header.components = contents.u16(content + 8);
            }
            case "colr" -> readColor(contents, header);
            case "cgrp" -> {
                header.colorGroup = true;
                forEach(contents, sub -> {
                    if (sub.type.equals("colr")) {
                        readColor(contents.contents(sub), header);
                    }
                });
            }
            case "pclr" -> {
                if (header.palette != null) {
                    throw new MediaException.Malformed("duplicate JPEG 2000 palette box");
                }
                header.palette = readPalette(contents);
            }
            case "cmap" -> {
                if (header.mapping != null) {
                    throw new MediaException.Malformed("duplicate JPEG 2000 component mapping box");
                }
                if (size < 4 || size % 4 != 0) {
                    throw new MediaException.Malformed("invalid JPEG 2000 component mapping box");
                }
                var mapping = new int[size / 4][];
                for (var i = 0; i < mapping.length; i++) {
                    var at = content + 4 * i;
                    mapping[i] = new int[]{contents.u16(at), bytes[at + 2] & 0xFF, bytes[at + 3] & 0xFF};
                }
                header.mapping = mapping;
            }
            case "cdef" -> {
                if (header.definitions != null) {
                    throw new MediaException.Malformed("duplicate JPEG 2000 channel definition box");
                }
                if (size < 2) {
                    throw new MediaException.Malformed("truncated JPEG 2000 channel definition box");
                }
                var count = contents.u16(content);
                if (count == 0 || size < 2 + 6 * count) {
                    throw new MediaException.Malformed("invalid JPEG 2000 channel definition box");
                }
                var definitions = new int[count][];
                for (var i = 0; i < count; i++) {
                    var at = content + 2 + 6 * i;
                    definitions[i] = new int[]{contents.u16(at), contents.u16(at + 2), contents.u16(at + 4)};
                }
                header.definitions = definitions;
            }
            case "opct" -> header.opacity = readOpacity(contents);
            case "creg" -> header.registration = readRegistration(contents);
            case "cref" -> readCrossReference(contents, header, depth);
            default -> {
                // res, lbl, roid, ipr, pxfm, bpcc and metadata boxes carry nothing the decoder needs
            }
        }
    }

    /**
     * Reads a cross-reference box (M.11.4): assembles the referenced box's contents from its fragment list and reads
     * it as if the header held it.
     *
     * @param contents the contents of the cross-reference box
     * @param header the header the referenced box belongs to
     * @param depth the nesting depth
     * @throws MediaException.Malformed if the box is truncated or refers to another cross-reference box
     */
    private void readCrossReference(View contents, Header header, int depth) {
        if (contents.end() - contents.start() < 12) {
            throw new MediaException.Malformed("truncated JPEG 2000 cross-reference box");
        }
        var type = new String(contents.bytes(), contents.start(), 4, StandardCharsets.ISO_8859_1);
        if (type.equals("cref")) {
            throw new MediaException.Malformed("JPEG 2000 cross-reference box refers to a cross-reference box");
        }
        var list = box(contents, contents.start() + 4);
        if (!list.type.equals("flst")) {
            throw new MediaException.Malformed("JPEG 2000 cross-reference box without a fragment list");
        }
        var bytes = assemble(readFragmentList(contents.contents(list)));
        readHeaderBox(contents, type, new View(bytes, 0, bytes.length), header, depth + 1);
    }

    /**
     * Reads a fragment table box: its fragment list.
     *
     * @param contents the contents of the fragment table box
     * @return the fragments
     * @throws MediaException.Malformed if the box holds no fragment list
     */
    private List<Fragment> readFragmentTable(View contents) {
        var fragments = new ArrayList<Fragment>();
        var found = new boolean[1];
        forEach(contents, box -> {
            if (box.type.equals("flst") && !found[0]) {
                found[0] = true;
                fragments.addAll(readFragmentList(contents.contents(box)));
            }
        });
        if (!found[0]) {
            throw new MediaException.Malformed("JPEG 2000 fragment table without a fragment list");
        }
        return fragments;
    }

    /**
     * Reads a fragment list box (M.11.3.1).
     *
     * @param contents its contents
     * @return the fragments
     * @throws MediaException.Malformed if the list is truncated
     */
    private List<Fragment> readFragmentList(View contents) {
        var start = contents.start();
        if (contents.end() - start < 2) {
            throw new MediaException.Malformed("truncated JPEG 2000 fragment list");
        }
        var count = contents.u16(start);
        if (contents.end() - start < 2 + 14L * count) {
            throw new MediaException.Malformed("truncated JPEG 2000 fragment list");
        }
        var fragments = new ArrayList<Fragment>(count);
        for (var i = 0; i < count; i++) {
            var at = start + 2 + 14 * i;
            var offset = (contents.u32(at) << 32) | contents.u32(at + 4);
            fragments.add(new Fragment(offset, contents.u32(at + 8), contents.u16(at + 12)));
        }
        return fragments;
    }

    /**
     * Joins the fragments of a fragment list.
     *
     * @param fragments the fragments, in stream order
     * @return their bytes, in order
     * @throws MediaException.Malformed if a fragment lies outside the file
     * @throws MediaException.Unsupported if a fragment is in another file
     * @throws MediaException.LimitExceeded if the joined size exceeds the allocation limit
     */
    private byte[] assemble(List<Fragment> fragments) {
        var total = 0L;
        for (var fragment : fragments) {
            if (fragment.reference() != 0) {
                var url = fragment.reference() <= references.size() ? references.get(fragment.reference() - 1)
                        : "data reference " + fragment.reference();
                // TODO: read fragments in other files once a source can resolve the URL of a data reference
                throw new MediaException.Unsupported("JPEG 2000", "codestream fragments in another file (" + url + ")");
            }
            if (fragment.offset() < 0 || fragment.offset() > length - fragment.length()) {
                throw new MediaException.Malformed("JPEG 2000 fragment outside the file");
            }
            total += fragment.length();
        }
        LimitChecks.checkAllocation(limits, total);
        if (total > Integer.MAX_VALUE - 8) {
            throw new MediaException.LimitExceeded("JPEG 2000 codestream of " + total + " bytes");
        }
        var result = new byte[(int) total];
        var position = 0;
        for (var fragment : fragments) {
            System.arraycopy(data, (int) fragment.offset(), result, position, (int) fragment.length());
            position += (int) fragment.length();
        }
        return result;
    }

    /**
     * Reads the data reference box (M.11.2): the location of each data entry URL box.
     *
     * @param view the range holding it
     * @param box the data reference box
     * @throws MediaException.Malformed if the box is truncated
     */
    private void readReferences(View view, Box box) {
        if (box.size() < 2) {
            throw new MediaException.Malformed("truncated JPEG 2000 data reference box");
        }
        var count = view.u16(box.content);
        var position = box.content + 2;
        for (var i = 0; i < count && box.end - position >= 8; i++) {
            var url = box(view, position);
            var start = url.content + 4;
            var end = start;
            while (end < url.end && view.bytes()[end] != 0) {
                end++;
            }
            references.add(start <= url.end ? new String(view.bytes(), start, Math.max(0, end - start),
                    StandardCharsets.UTF_8) : "");
            position = url.end;
        }
    }

    /**
     * Reads a color specification box into a header.
     *
     * @param contents the box's contents
     * @param header the header receiving it
     */
    private void readColor(View contents, Header header) {
        var content = contents.start();
        var size = contents.end() - content;
        if (size < 3) {
            throw new MediaException.Malformed("truncated JPEG 2000 color specification box");
        }
        var bytes = contents.bytes();
        var method = bytes[content] & 0xFF;
        var precedence = (int) bytes[content + 1];
        switch (method) {
            case 1 -> {
                if (size < 7) {
                    throw new MediaException.Malformed("truncated JPEG 2000 color specification box");
                }
                var parameters = size > 7 ? Arrays.copyOfRange(bytes, content + 7, content + size) : null;
                header.colors.add(new Color(1, precedence, (int) contents.u32(content + 3), parameters, null));
            }
            case 2, 3 -> header.colors.add(new Color(method, precedence, -1, null,
                    Arrays.copyOfRange(bytes, content + 3, content + size)));
            case 5 -> {
                var components = header.components != 0 ? header.components : defaults.components;
                var parameterized = readParameterizedColor(contents, precedence, components);
                if (parameterized != null) {
                    header.colors.add(parameterized);
                }
            }
            default -> {
                // I.5.3.3 and M.11.7.2: a reader ignores color specifications with other methods
            }
        }
    }

    /**
     * Reads a parameterized color specification (ISO/IEC 15444-15 D.4.3): Rec. ITU-T H.273 code points of the
     * primaries, transfer characteristics and matrix coefficients, and the full range flag. Full-range R'G'B'
     * samples of at least three components become the ICC profile of their primaries and transfer characteristics,
     * or the enumerated sRGB color space; any other specification is ignored, as a method this reader does not
     * understand.
     *
     * @param contents the box's contents
     * @param precedence the box's precedence
     * @param components the number of components the image header declares
     * @return the color specification, or {@code null} when it is ignored
     * @throws MediaException.Malformed if the box is too short
     */
    private static Color readParameterizedColor(View contents, int precedence, int components) {
        var content = contents.start();
        if (contents.end() - content < 10) {
            throw new MediaException.Malformed("truncated JPEG 2000 color specification box");
        }
        var primariesCode = contents.u16(content + 3);
        var transferCode = contents.u16(content + 5);
        var matrixCode = contents.u16(content + 7);
        var fullRange = (contents.bytes()[content + 9] & 0x80) != 0;
        var primaries = ColorPrimaries.of(primariesCode);
        var transfer = TransferCharacteristics.of(transferCode);
        if (matrixCode != MatrixCoefficients.IDENTITY.code() || !fullRange || components < 3
                || primaries.code() != primariesCode || transfer.code() != transferCode
                || primaries == ColorPrimaries.UNSPECIFIED || transfer == TransferCharacteristics.UNSPECIFIED) {
            return null;
        }
        var profile = ColorProfile.of(new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL, primaries, transfer));
        if (profile.isSrgb()) {
            return new Color(5, precedence, 16, null, null);
        }
        return new Color(5, precedence, -1, null, profile.toByteArray());
    }

    /**
     * Reads a palette box.
     *
     * @param contents its contents
     * @return the palette
     * @throws MediaException.Malformed if the box is invalid
     */
    private static Palette readPalette(View contents) {
        var content = contents.start();
        var size = contents.end() - content;
        var bytes = contents.bytes();
        if (size < 3) {
            throw new MediaException.Malformed("truncated JPEG 2000 palette box");
        }
        var entries = contents.u16(content);
        var columns = bytes[content + 2] & 0xFF;
        if (entries == 0 || entries > 1024 || columns == 0) {
            throw new MediaException.Malformed("invalid JPEG 2000 palette size " + entries + "x" + columns);
        }
        if (size < 3 + columns) {
            throw new MediaException.Malformed("truncated JPEG 2000 palette box");
        }
        var depths = new int[columns];
        var signed = new boolean[columns];
        var rowBytes = 0;
        for (var j = 0; j < columns; j++) {
            var b = bytes[content + 3 + j] & 0xFF;
            depths[j] = (b & 0x7F) + 1;
            signed[j] = (b & 0x80) != 0;
            if (depths[j] > Codestream.MAX_PRECISION) {
                throw new MediaException.Malformed("invalid JPEG 2000 palette depth " + depths[j]);
            }
            rowBytes += (depths[j] + 7) >> 3;
        }
        if ((long) size < 3L + columns + (long) entries * rowBytes) {
            throw new MediaException.Malformed("truncated JPEG 2000 palette entries");
        }
        var values = new long[entries * columns];
        var at = content + 3 + columns;
        for (var i = 0; i < entries; i++) {
            for (var j = 0; j < columns; j++) {
                var count = (depths[j] + 7) >> 3;
                var value = 0L;
                for (var k = 0; k < count; k++) {
                    value = (value << 8) | (bytes[at++] & 0xFF);
                }
                value &= (1L << depths[j]) - 1;
                if (signed[j] && (value & (1L << (depths[j] - 1))) != 0) {
                    value -= 1L << depths[j];
                }
                values[i * columns + j] = value;
            }
        }
        return new Palette(entries, depths, signed, values);
    }

    /**
     * Reads an opacity box (M.11.7.6).
     *
     * @param contents its contents
     * @return the opacity
     * @throws MediaException.Malformed if the box is truncated or its type is reserved
     */
    private static Opacity readOpacity(View contents) {
        var content = contents.start();
        var size = contents.end() - content;
        if (size < 1) {
            throw new MediaException.Malformed("truncated JPEG 2000 opacity box");
        }
        var type = contents.bytes()[content] & 0xFF;
        if (type < 2) {
            return new Opacity(type, 0, new byte[0]);
        }
        if (type > 2 || size < 2) {
            throw new MediaException.Malformed("invalid JPEG 2000 opacity box");
        }
        // the size of each chroma-key value follows from its channel's bit depth, known once the channels are
        var count = contents.bytes()[content + 1] & 0xFF;
        return new Opacity(2, count, Arrays.copyOfRange(contents.bytes(), content + 2, contents.end()));
    }

    /**
     * Reads a codestream registration box (M.11.7.7).
     *
     * @param contents its contents
     * @return the registration
     * @throws MediaException.Malformed if the box is truncated or a grid size is 0
     */
    private static Registration readRegistration(View contents) {
        var content = contents.start();
        var size = contents.end() - content;
        if (size < 4 || (size - 4) % 6 != 0) {
            throw new MediaException.Malformed("invalid JPEG 2000 codestream registration box");
        }
        var gridX = contents.u16(content);
        var gridY = contents.u16(content + 2);
        var count = (size - 4) / 6;
        if (gridX == 0 || gridY == 0 || count == 0) {
            throw new MediaException.Malformed("invalid JPEG 2000 codestream registration box");
        }
        var codestreams = new int[count];
        var rx = new int[count];
        var ry = new int[count];
        var ox = new int[count];
        var oy = new int[count];
        var bytes = contents.bytes();
        for (var i = 0; i < count; i++) {
            var at = content + 4 + 6 * i;
            codestreams[i] = contents.u16(at);
            rx[i] = bytes[at + 2] & 0xFF;
            ry[i] = bytes[at + 3] & 0xFF;
            ox[i] = bytes[at + 4] & 0xFF;
            oy[i] = bytes[at + 5] & 0xFF;
            if (rx[i] == 0 || ry[i] == 0 || ox[i] >= gridX || oy[i] >= gridY) {
                throw new MediaException.Malformed("invalid JPEG 2000 codestream registration entry " + i);
            }
        }
        return new Registration(gridX, gridY, codestreams, rx, ry, ox, oy);
    }

    /**
     * Reads a composition box (M.11.10): its options and instruction sets.
     *
     * @param contents its contents
     * @return the composition
     * @throws MediaException.Malformed if the box has no options box or an instruction set is truncated
     */
    private Composition readComposition(View contents) {
        var options = new long[3];
        var found = new boolean[1];
        var sets = new ArrayList<InstructionSet>();
        forEach(contents, box -> {
            switch (box.type) {
                case "copt" -> {
                    if (box.size() < 9) {
                        throw new MediaException.Malformed("truncated JPEG 2000 composition options box");
                    }
                    options[0] = contents.u32(box.content);
                    options[1] = contents.u32(box.content + 4);
                    options[2] = contents.bytes()[box.content + 8] & 0xFF;
                    found[0] = true;
                }
                case "inst" -> sets.add(readInstructions(contents.contents(box)));
                default -> {
                }
            }
        });
        if (!found[0] || options[0] == 0 || options[1] == 0) {
            throw new MediaException.Malformed("JPEG 2000 composition box without valid options");
        }
        return new Composition(options[1], options[0], (int) options[2], List.copyOf(sets));
    }

    /**
     * Reads an instruction set box (M.11.10.2).
     *
     * @param contents its contents
     * @return the instruction set
     * @throws MediaException.Malformed if the box is truncated
     */
    private InstructionSet readInstructions(View contents) {
        var content = contents.start();
        if (contents.end() - content < 8) {
            throw new MediaException.Malformed("truncated JPEG 2000 instruction set box");
        }
        var type = contents.u16(content);
        var repeat = contents.u16(content + 2);
        var tick = contents.u32(content + 4);
        var size = ((type & 1) != 0 ? 8 : 0) + ((type & 2) != 0 ? 8 : 0) + ((type & 8) != 0 ? 8 : 0)
                + ((type & 0x20) != 0 ? 16 : 0) + ((type & 0x40) != 0 ? 4 : 0);
        var instructions = new ArrayList<Instruction>();
        var position = content + 8;
        if (size == 0) {
            return new InstructionSet(repeat, tick, List.of());
        }
        while (contents.end() - position >= size) {
            long x = 0;
            long y = 0;
            long width = -1;
            long height = -1;
            var persist = true;
            long life = 0;
            long nextUse = 0;
            long cropX = 0;
            long cropY = 0;
            long cropWidth = -1;
            long cropHeight = -1;
            var rotation = 0;
            if ((type & 1) != 0) {
                x = contents.u32(position);
                y = contents.u32(position + 4);
                position += 8;
            }
            if ((type & 2) != 0) {
                width = contents.u32(position);
                height = contents.u32(position + 4);
                position += 8;
            }
            if ((type & 8) != 0) {
                var word = contents.u32(position);
                persist = (word & 0x80000000L) != 0;
                life = word & 0x7FFFFFFFL;
                nextUse = contents.u32(position + 4);
                position += 8;
            }
            if ((type & 0x20) != 0) {
                cropX = contents.u32(position);
                cropY = contents.u32(position + 4);
                cropWidth = contents.u32(position + 8);
                cropHeight = contents.u32(position + 12);
                position += 16;
            }
            if ((type & 0x40) != 0) {
                rotation = (int) contents.u32(position);
                position += 4;
                if ((rotation & ~0x17) != 0 || (rotation & 7) > 4) {
                    throw new MediaException.Malformed("invalid JPEG 2000 instruction rotation " + rotation);
                }
            }
            instructions.add(new Instruction(x, y, width, height, persist, life, nextUse, cropX, cropY, cropWidth,
                    cropHeight, rotation));
            LimitChecks.checkFrames(limits, instructions.size());
        }
        return new InstructionSet(repeat, tick, List.copyOf(instructions));
    }

    /**
     * Reads a {@code uuid} box holding EXIF or XMP metadata.
     *
     * @param view the range holding it
     * @param box the {@code uuid} box
     */
    private void readUuid(View view, Box box) {
        var content = box.content;
        var end = box.end;
        var bytes = view.bytes();
        if (end - content < 16) {
            return;
        }
        if (exif == null && Arrays.equals(bytes, content, content + 16, EXIF_UUID, 0, 16)) {
            var start = content + 16;
            start += TiffExif.prefixLength(bytes, start);
            if (end - start >= 8) {
                exif = Arrays.copyOfRange(bytes, start, end);
            }
        } else if (xmp == null && Arrays.equals(bytes, content, content + 16, XMP_UUID, 0, 16)) {
            xmp = Arrays.copyOfRange(bytes, content + 16, end);
        }
    }

    /**
     * Checks the palette and the component mapping of a header against each other; one without the other is
     * ignored, as I.5.3.4 requires both.
     *
     * @param header the header whose effective palette and mapping are checked
     * @throws MediaException.Malformed if a mapping is invalid
     */
    private void validate(Header header) {
        var palette = header.palette != null ? header.palette : defaults.palette;
        var mapping = header.mapping != null ? header.mapping : defaults.mapping;
        if (mapping == null || palette == null) {
            return;
        }
        for (var entry : mapping) {
            var type = entry[1];
            var column = entry[2];
            if (type > 1) {
                throw new MediaException.Malformed("invalid JPEG 2000 component mapping type " + type);
            }
            if (type == 1 && column >= palette.columns()) {
                throw new MediaException.Malformed("invalid JPEG 2000 palette column " + column);
            }
            if (type == 0 && column != 0) {
                throw new MediaException.Malformed("invalid JPEG 2000 direct component mapping");
            }
        }
    }

    /**
     * Reads the header of the box at a position.
     *
     * @param view the range holding the box
     * @param position the index of the box
     * @return the box; a codestream or media data box that runs past the range is cut at its end
     * @throws MediaException.Malformed if its length is invalid
     */
    private static Box box(View view, int position) {
        var end = view.end();
        if (end - position < 8) {
            throw new MediaException.Malformed("truncated JPEG 2000 box header");
        }
        var length = view.u32(position);
        var type = new String(view.bytes(), position + 4, 4, StandardCharsets.ISO_8859_1);
        var header = 8;
        if (length == 1) {
            if (end - position < 16) {
                throw new MediaException.Malformed("truncated JPEG 2000 box header");
            }
            length = (view.u32(position + 8) << 32) | view.u32(position + 12);
            header = 16;
        } else if (length == 0) {
            length = end - position;
        }
        if (length < header) {
            throw new MediaException.Malformed("invalid JPEG 2000 box length " + length);
        }
        var boxEnd = position + length;
        if (boxEnd > end || boxEnd < 0) {
            if (type.equals("jp2c") || type.equals("mdat")) {
                boxEnd = end;
            } else {
                throw new MediaException.Malformed("JPEG 2000 box " + type.strip() + " runs past its container");
            }
        }
        return new Box(type, position + header, (int) boxEnd);
    }

    /**
     * Returns whether a box's contents contain an ASCII string.
     *
     * @param view the range holding the box
     * @param box the box searched
     * @param needle the string
     * @return whether it occurs
     */
    private static boolean contains(View view, Box box, String needle) {
        var bytes = needle.getBytes(StandardCharsets.ISO_8859_1);
        for (var i = box.content; i + bytes.length <= box.end; i++) {
            if (Arrays.equals(view.bytes(), i, i + bytes.length, bytes, 0, bytes.length)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the number of codestreams.
     *
     * @return the codestream count, at least 1
     */
    public int codestreamCount() {
        return codestreams.size();
    }

    /**
     * Returns the bytes of a codestream, joining its fragments when it is fragmented.
     *
     * @param index the codestream index
     * @return the codestream
     * @throws MediaException.Malformed if the index does not exist or a fragment lies outside the file
     * @throws MediaException.Unsupported if a fragment is in another file
     * @throws MediaException.LimitExceeded if a fragmented codestream exceeds the allocation limit
     */
    public CodestreamBytes codestream(int index) {
        if (index < 0 || index >= codestreams.size()) {
            throw new MediaException.Malformed("JPEG 2000 compositing layer refers to missing codestream " + index);
        }
        var stream = codestreams.get(index);
        if (stream.fragments() == null) {
            return new CodestreamBytes(data, stream.start(), stream.end());
        }
        var bytes = assemble(stream.fragments());
        return new CodestreamBytes(bytes, 0, bytes.length);
    }

    /**
     * Returns the palette of a codestream.
     *
     * @param index the codestream index
     * @return the palette, or {@code null} without both a palette and a component mapping
     */
    public Palette palette(int index) {
        var header = index < codestreamHeaders.size() ? codestreamHeaders.get(index) : null;
        var palette = header != null && header.palette != null ? header.palette : defaults.palette;
        return mapping(index) == null ? null : palette;
    }

    /**
     * Returns the component mapping of a codestream.
     *
     * @param index the codestream index
     * @return for each channel the component, mapping type and palette column, or {@code null}
     */
    public int[][] mapping(int index) {
        var header = index < codestreamHeaders.size() ? codestreamHeaders.get(index) : null;
        var mapping = header != null && header.mapping != null ? header.mapping : defaults.mapping;
        var palette = header != null && header.palette != null ? header.palette : defaults.palette;
        return palette == null ? null : mapping;
    }

    /**
     * Returns the number of compositing layers.
     *
     * @return the number of compositing layer header boxes, or the number of codestreams without them
     */
    public int layerCount() {
        return layerHeaders.isEmpty() ? codestreams.size() : layerHeaders.size();
    }

    /**
     * Returns a compositing layer, its headers resolved against the defaults of the JP2 header.
     *
     * @param index the layer index, from 0 to {@link #layerCount()} minus one
     * @return the layer
     * @throws MediaException.Malformed if the layer has no codestream registration while another layer has one
     */
    public Layer layer(int index) {
        var header = layerHeaders.isEmpty() ? null : layerHeaders.get(index);
        Registration registration = null;
        int[] streams;
        if (header != null && header.registration != null) {
            registration = header.registration;
            streams = registration.codestreams();
        } else {
            for (var other : layerHeaders) {
                if (other.registration != null) {
                    throw new MediaException.Malformed("JPEG 2000 compositing layer " + index
                            + " without a codestream registration box");
                }
            }
            streams = new int[]{index};
        }
        var colors = header != null && (header.colorGroup || !header.colors.isEmpty()) ? header.colors
                : defaults.colors;
        var color = best(colors);
        var opacity = header != null ? header.opacity : null;
        int[][] definitions = null;
        if (opacity == null) {
            definitions = header != null && header.definitions != null ? header.definitions : defaults.definitions;
        }
        return new Layer(streams, registration, color == null ? -1 : color.enumerated(),
                color == null ? null : color.parameters(), color == null ? null : color.icc(), definitions, opacity);
    }

    /**
     * Chooses the color specification of highest precedence, the first one on a tie.
     *
     * @param colors the color specifications this reader understands
     * @return the chosen one, or {@code null} when there is none
     */
    private static Color best(List<Color> colors) {
        Color best = null;
        for (var color : colors) {
            if (best == null || color.precedence() > best.precedence()) {
                best = color;
            }
        }
        return best;
    }

    /**
     * Returns the composition.
     *
     * @return the composition, or {@code null} when the file has no composition box
     */
    public Composition composition() {
        return composition;
    }

    /**
     * Returns the EXIF block.
     *
     * @return the TIFF structure starting with its byte-order mark, or {@code null}
     */
    public byte[] exif() {
        return exif;
    }

    /**
     * Returns the XMP packet.
     *
     * @return the packet, or {@code null}
     */
    public byte[] xmp() {
        return xmp;
    }
}
