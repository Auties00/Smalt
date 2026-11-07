package com.github.auties00.smalt.image.internal.jbig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.DataFormatException;

/**
 * Decodes JBIG bi-level image entities (ITU-T T.82 | ISO/IEC 11544).
 *
 * <p>Every parameterization of a self-contained entity decodes: any number of differential layers and
 * bit-planes, stripes, the six stripe orders (high-to-low included), the two- and three-line templates,
 * adaptive template moves, typical prediction in both layer kinds, deterministic prediction with the
 * default or a private table, stripe resets, comments and a new image length. An entity that continues
 * another one (an initial layer above zero, or a deterministic prediction table carried over) is rejected.
 * When the data ends early or is aborted, the stripes that were coded in full decode and the rest stays
 * background (0).
 *
 * <p>Instances are not thread-safe.
 */
public final class JbigDecoder {
    /** Marker byte: escape. */
    private static final int ESC = 0xFF;
    /** Marker byte: stuffing after an escape inside coded data. */
    private static final int STUFF = 0x00;
    /** Marker byte: reserved. */
    private static final int RESERVE = 0x01;
    /** Marker byte: normal stripe end. */
    private static final int SDNORM = 0x02;
    /** Marker byte: stripe end with a state reset. */
    private static final int SDRST = 0x03;
    /** Marker byte: abort. */
    private static final int ABORT = 0x04;
    /** Marker byte: new image length. */
    private static final int NEWLEN = 0x05;
    /** Marker byte: adaptive template move. */
    private static final int ATMOVE = 0x06;
    /** Marker byte: comment. */
    private static final int COMMENT = 0x07;

    private static final int HITOLO = 0x08;
    private static final int SEQ = 0x04;
    private static final int ILEAVE = 0x02;
    private static final int SMID = 0x01;

    private static final int LRLTWO = 0x40;
    private static final int TPDON = 0x10;
    private static final int TPBON = 0x08;
    private static final int DPON = 0x04;
    private static final int DPPRIV = 0x02;
    private static final int DPLAST = 0x01;

    /** The context of the lowest-layer typical prediction pseudo-pixel, three-line template (Figure 11). */
    private static final int SLNTP_THREE_LINE = 0x271;

    /** The context of the lowest-layer typical prediction pseudo-pixel, two-line template (Figure 12). */
    private static final int SLNTP_TWO_LINE = 0x2C5;

    /** The context of the differential-layer typical prediction pseudo-pixel (Figure 8). */
    private static final int LNTP_CONTEXT = 3 << 10 | 0x3F;

    /** The default deterministic prediction tables (T.82 Tables 19 to 22) in the layout of a private table. */
    private static final byte[] DEFAULT_DP = HexFormat.of().parseHex(""
            + "2aaaaaaaaaaaaaaa2aaaaaaaaaaaaaaa2aaaaaaaaaaaaaaa0aaaaaaaaaaaaaaa2aaaaaaa0aaaaaaa222aaaaa22aaaaaa"
            + "0aaaaaaaaaaaaaa9220aaaaaaaaaaaaaaaaaaaaaaaaaa0002aaaaaaa0aaaa555aaaaaaaaaaaa95552aaaa555aaaaa5a9"
            + "2aaaaaaa2aaaaaaa0aaaaaa08aaaaaaa2aaaa555aaaaaa925aaaaaa6aa0aaaa68aaaaaaa0a2aaaaa802aaaaa000aaaa9"
            + "8aaaaaaa5aaaaaa9aaaaaaa9aa5aaaa9820aaaaaa00aaaaa8882aaaa8a02aaaaa00aaaaa0a0aaaa999a2aaaaaa02aaaa"
            + "aaaa6aaaaaaaaaaa2aaa6aaa2aaa5aaaaaaaaaaa2aaa6aaa2aaa5aaaaa5aaaaa08aa5555082a55550aaa96aa4aaaa56a"
            + "2aaa5aaa0aaa9aaaaaaaa8a8aa0aa5aa8aaa9aaa820aaaaa802aaaa8a00aaaa68a2aa955a22aa59aa00aa69aa0aaaaaa"
            + "820aaaa0a00aaaa6a88aaaaa88a2aaa6a88aaaa0008aaaa6aaa2aaa9a0a8a9a92aaaa55a2aaa5aaaa99aaa00a69aa880"
            + "0000aaaa2aaaa000a0aaaaaaa096aaaa8aaa9a5a8aaaa66aa00aa59a2aaaa9aa8aaaaa8a02aa956a200aaaa0a0a6aaaa"
            + "a22aa55aaa8aaaa0aa8aaaa6aaa2aaa9a00aaaaa086aaaa8a2a2aaaaa0a0aaaaa8aaaaaaaaa2aaa9aaaaaaa9a8a8aaaa"
            + "aaaaaaaaa0a2aaa988a8aaaaa0a8aaaaaaaa5555aaaa55552aaa9555aaaaaaaaaaaa55552aaaaaaa2aaa9aaaaaaaaa82"
            + "0aaa5555aaa2aa596aaaaaa6a0a2aa552aaa9aaaaaaaa862aa2aa46aa00aaaa98aaaa595522aa955a882aaaa08aaaaa6"
            + "a20aaaa5a00aaaa6aa8aaaa6aaa2aaa6a08aa965aaa6aaa99a92aaa9aaaaaa99a65aaaaaa55aaa2aaaa0aaa9a002aaa9"
            + "000055552aaa9aaa8aaaa655aa2aa65a0aaaaaaaaaaa966a822aa555aa0aa99a8a0aa555a20aaa59a00aaa9aaa0aaaa5"
            + "822aa555a0aaa95a56aaaa95aaa2aaa4a80aaaaaa68aaaa6aaa2aaa5aaa0aaa9aa8aaaaaa66aaaaaa000aaa5a000aaa5"
            + "aaa2aaa5aaa8aaa9aaa8aaa9aaaaaaaaaaa0aaa9aaa8aaa9aaaaaaaaaaa8aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            + "aaaaaaaaaaaaaaaaaaaaaaaaaaa2aaa6aaaaaaaaaaaaaaaa2aaa6aaa8aaa9aaaaaaaaaaa2aaa6aaaa559a008aa5aaa0a"
            + "aaaaaaaa8000955580009555a00aa55a82aa96aaaa6aaa2aa82aa96aa0aaa5aa80009555a00aa55aa88aa99a88889999"
            + "a995a880008a559aaaa6aaa2a2aaa6aa2aaa6aaaaaaaaaaaa220a665a965a8200000555500005555a000a555a0a8a5a9"
            + "aaaaaaaa8aaa9aaaaa6aaa2a221a664a800095552aaa6aaa20106545a0a8a5a9aaaaaaaaaa95aa80aa8aaa9aaaa2aaa6"
            + "a000a55508805995a2a0a6a5a0a4a5a1a8a6a9a2aaa6aaa2aaaaaaaaa8a8a9a9aaa8aaa9a0a2a5a688a899a9a0a8a5a9"
            + "aaaaaaaaaaaaaaaa2aaa6aaa2aaa6aaaaaaaaaaa2aaa6aaa2aaa6aaaaa56aa02aaaaaaaaaaa2aaa6a559a008a5aaa0aa"
            + "2aaa6aaaa5a6a0a2a99aa88aa11aa44a8a2a9a6aa6aaa2aaa995a880080a595aa55aa00aa00aa55aaa8aaa9aaaaaaaaa"
            + "a28aa69aaa0aaa5aaaa6aaa2aa2aaa6aa8aaa9aaaaaaaaaaaaa2aaa6a556a002aaaaaaaa2aaa6aaa8aaa9aaaaaaaaaaa"
            + "00005555aaaaaaaa8aaa9aaaa22aa66a8aaa9aaaa5a6a0a2a12aa46aaa5aaa0a9aaa8aaaa2aaa6aaa86aa92aaaa8aaa9"
            + "a955a800a88aa99aaaaaaaaaaaa2aaa6aa80aa95aaa2aaa6a55aa00aa05aa50aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            + "aaa2aaa6aaaaaaaaaaaaaaaaaaa8aaa92aaa6aaaaaaaaaaaaaaaaaaa8aaa9aaaaaaaaaaaaaaaaaaa8aaa9aaaaa56aa02"
            + "a200a655a695a280a6aaa2aa212a646a8aaa9aaaaaaaaaaaa21aa64aa5aaa0aaaaaaaaaaa9a5a8a0aa86aa92aaa6aaa2"
            + "a595a080a88aa99aaaaaaaaaaaaaaaaaa8aaa9aaaaa2aaa6aaaaaaaaaa88aa99aaaaaaaaaaa2aaa6aaa8aaa9aaaaaaaa"
            + "a55aa00a8aaa9aaaa5aaa0aaa22aa66aaaaaaaaa9aaa8aaaaa00aa55aa80aa95aa5aaa0aa22aa66aaaaaaaaaa55aa00a"
            + "a020a565a86aa92aaaaaaaaaaaa0aaa5a88aa99aaaaaaaaaaaa2aaa6aaa8aaa9aaa0aaa5aa52aa06aaa8aaa9aaaaaaaa"
            + "aaa2aaa6aaa8aaa9aaa8aaa9aaaaaaaaaaa8aaa9aaa8aaa9aaaaaaaaaaaaaaaa0000555500005555800095552aaa6aaa"
            + "00005555aaaaaaaa9aaa8aaaaa2aaa6aa000a555aa08aa5921006455aa08aa598aaa9aaaa22aa66aa68aa29aaaa8aaa9"
            + "a000a55508805995aaa8aaa9aa02aa56a8a0a9a5aaa2aaa6aaa2aaa6aaaaaaaaaa20aa65aa04aa518a889a99aa08aa59"
            + "a55aa00aa58aa09aaaaaaaaaaaa8aaa9aaaaaaaa9aaa8aaaa200a655a68aa29aa000a555922a866aa880a995a0aaa5aa"
            + "a0a0a5a5a8a0a9a5aaaaaaaaaaa0aaa5a880a995aa0aaa5a00005555aaaaaaaaaaa0aaa5a5a2a0a6aaa8aaa9aaaaaaaa"
            + "aaa0aaa5a666a222aaaaaaaaaaaaaaaaaaa8aaa9aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

    /**
     * {@return a copy of the default deterministic prediction tables in the layout of a private table}
     */
    static byte[] defaultTable() {
        return DEFAULT_DP.clone();
    }

    /**
     * Holds a decoded image.
     *
     * @param width the pixels across
     * @param height the pixels down
     * @param planes the bit-planes, most significant first, each rows of {@code (width + 7) / 8} bytes with the
     * leftmost pixel in the most significant bit
     */
    public record Image(int width, int height, byte[][] planes) {
    }

    /**
     * Holds one stripe data entity.
     */
    private record Stripe(int start, int end, boolean reset, List<int[]> moves) {
    }

    /** The parameters of the entity being decoded. */
    private int layers;
    private int planeCount;
    private int[] widths;
    private int[] heights;
    private long l0;
    private int maxX;
    private int maxY;
    private int options;
    private byte[][] dpTables;

    /** The arithmetic decoder. */
    private final JbigArithmeticDecoder coder = new JbigArithmeticDecoder();

    /**
     * Decodes an entity.
     *
     * @param src the input
     * @param offset the first byte of the entity
     * @param length the bytes available
     * @param maxPixels the largest image size, in pixels, to decode
     * @return the highest-resolution image of every bit-plane
     * @throws DataFormatException if the data is not a valid self-contained entity, or the image exceeds
     * {@code maxPixels}
     */
    public Image decode(byte[] src, int offset, int length, long maxPixels) throws DataFormatException {
        return decode(src, offset, length, maxPixels, false);
    }

    /**
     * Decodes an entity, or only its most significant bit-plane, skipping the coded data of the others.
     *
     * @param src the input
     * @param offset the first byte of the entity
     * @param length the bytes available
     * @param maxPixels the largest image size, in pixels, to decode
     * @param mostSignificantOnly whether to decode only the most significant bit-plane
     * @return the highest-resolution image of every bit-plane, or of the most significant one
     * @throws DataFormatException if the data is not a valid self-contained entity, or the image exceeds
     * {@code maxPixels}
     */
    public Image decode(byte[] src, int offset, int length, long maxPixels, boolean mostSignificantOnly)
            throws DataFormatException {
        var end = offset + length;
        if (length < 20) {
            throw new DataFormatException("truncated JBIG header");
        }
        var dl = src[offset] & 0xFF;
        var d = src[offset + 1] & 0xFF;
        planeCount = src[offset + 2] & 0xFF;
        var xd = u32(src, offset + 4);
        var yd = u32(src, offset + 8);
        l0 = u32(src, offset + 12);
        maxX = src[offset + 16] & 0xFF;
        maxY = src[offset + 17] & 0xFF;
        var order = src[offset + 18] & 0xFF;
        options = src[offset + 19] & 0xFF;
        if (dl > d) {
            throw new DataFormatException("JBIG initial layer above the final layer");
        }
        if (dl != 0) {
            throw new DataFormatException("the JBIG entity continues the layers of another one");
        }
        if (planeCount == 0 || xd == 0 || yd == 0 || l0 == 0 || maxX > 127) {
            throw new DataFormatException("invalid JBIG header");
        }
        var seqIleaveSmid = order & 7;
        if (seqIleaveSmid == SMID || seqIleaveSmid == (SEQ | ILEAVE | SMID)) {
            throw new DataFormatException("invalid JBIG stripe order");
        }
        var pos = offset + 20;
        dpTables = null;
        if ((options & DPON) != 0) {
            byte[] table;
            if ((options & DPPRIV) != 0) {
                if ((options & DPLAST) != 0) {
                    throw new DataFormatException("the JBIG entity reuses the prediction table of another one");
                }
                if (end - pos < 1728) {
                    throw new DataFormatException("truncated JBIG prediction table");
                }
                table = Arrays.copyOfRange(src, pos, pos + 1728);
                pos += 1728;
            } else {
                table = DEFAULT_DP;
            }
            dpTables = unpack(table);
        }
        var stripes = new ArrayList<Stripe>();
        var pending = new ArrayList<int[]>();
        var height = yd;
        scan:
        while (pos < end) {
            if ((src[pos] & 0xFF) == ESC && pos + 1 < end) {
                switch (src[pos + 1] & 0xFF) {
                    case ATMOVE -> {
                        if (end - pos < 8) {
                            break scan;
                        }
                        pending.add(new int[]{(int) Math.min(u32(src, pos + 2), Integer.MAX_VALUE), src[pos + 6],
                                src[pos + 7] & 0xFF});
                        pos += 8;
                        continue;
                    }
                    case NEWLEN -> {
                        if (end - pos < 6) {
                            break scan;
                        }
                        height = newLength(height, u32(src, pos + 2));
                        pos += 6;
                        continue;
                    }
                    case COMMENT -> {
                        if (end - pos < 6) {
                            break scan;
                        }
                        var skip = u32(src, pos + 2);
                        if (skip > end - pos - 6) {
                            break scan;
                        }
                        pos += 6 + (int) skip;
                        continue;
                    }
                    case ABORT -> {
                        break scan;
                    }
                    case RESERVE -> throw new DataFormatException("reserved JBIG marker");
                    default -> {
                    }
                }
            }
            var q = pos;
            var found = false;
            while (q + 1 < end) {
                if ((src[q] & 0xFF) != ESC) {
                    q++;
                    continue;
                }
                var marker = src[q + 1] & 0xFF;
                if (marker == STUFF) {
                    q += 2;
                    continue;
                }
                if (marker == SDNORM || marker == SDRST) {
                    stripes.add(new Stripe(pos, q, marker == SDRST, List.copyOf(pending)));
                    pending.clear();
                    pos = q + 2;
                    found = true;
                    break;
                }
                if (marker == ABORT) {
                    break scan;
                }
                if (marker == NEWLEN && end - q >= 6) {
                    // a new length may close the coded data of the stripe it shortens
                    height = newLength(height, u32(src, q + 2));
                    if (end - q >= 8 && (src[q + 6] & 0xFF) == ESC
                            && ((src[q + 7] & 0xFF) == SDNORM || (src[q + 7] & 0xFF) == SDRST)) {
                        stripes.add(new Stripe(pos, q, (src[q + 7] & 0xFF) == SDRST, List.copyOf(pending)));
                        pending.clear();
                        pos = q + 8;
                        found = true;
                        break;
                    }
                }
                throw new DataFormatException("JBIG marker 0x" + Integer.toHexString(marker) + " inside stripe data");
            }
            if (!found) {
                break;
            }
        }
        layers = d + 1;
        widths = new int[layers];
        heights = new int[layers];
        long w = xd;
        long h = height;
        for (var layer = d; layer >= 0; layer--) {
            if (w > Integer.MAX_VALUE || h > Integer.MAX_VALUE) {
                throw new DataFormatException("JBIG image too large");
            }
            widths[layer] = (int) w;
            heights[layer] = (int) h;
            w = (w + 1) / 2;
            h = (h + 1) / 2;
        }
        var top = (long) widths[d] * heights[d];
        var decoded = mostSignificantOnly ? 1 : planeCount;
        if (top > maxPixels || top * 4 / 3 * decoded > Integer.MAX_VALUE) {
            throw new DataFormatException("JBIG image of " + widths[d] + "x" + heights[d] + " pixels exceeds the limit");
        }
        var stripeCount = (int) Math.min(Integer.MAX_VALUE, (heights[0] + l0 - 1) / l0);
        var index = new int[planeCount][layers][];
        for (var p = 0; p < planeCount; p++) {
            for (var layer = 0; layer < layers; layer++) {
                index[p][layer] = new int[stripeCount];
                Arrays.fill(index[p][layer], -1);
            }
        }
        assign(index, order, stripes.size(), stripeCount);
        var result = new byte[decoded][];
        for (var p = planeCount - decoded; p < planeCount; p++) {
            var images = new byte[layers][];
            for (var layer = 0; layer < layers; layer++) {
                images[layer] = new byte[widths[layer] * heights[layer]];
            }
            for (var layer = 0; layer < layers; layer++) {
                if (!decodeLayer(src, stripes, index[p][layer], images, layer, stripeCount)) {
                    break;
                }
            }
            result[planeCount - 1 - p] = pack(images[d], widths[d], heights[d]);
        }
        return new Image(widths[d], heights[d], result);
    }

    /**
     * Applies a new image length.
     *
     * @param height the current length
     * @param value the new length
     * @return the new length
     * @throws DataFormatException if the new length is zero or larger than the current one
     */
    private static long newLength(long height, long value) throws DataFormatException {
        if (value == 0 || value > height) {
            throw new DataFormatException("invalid JBIG new image length " + value);
        }
        return value;
    }

    /**
     * Assigns the stripe data entities, in stream order, to their plane, layer and stripe.
     *
     * @param index receives the entity number of each plane, layer and stripe
     * @param order the order byte
     * @param count the number of entities
     * @param stripeCount the stripes of each layer
     */
    private void assign(int[][][] index, int order, int count, int stripeCount) {
        var layerOrder = new int[layers];
        for (var i = 0; i < layers; i++) {
            layerOrder[i] = (order & HITOLO) != 0 ? layers - 1 - i : i;
        }
        var next = 0;
        var loops = switch (order & 7) {
            case 0 -> "pds";
            case ILEAVE -> "dps";
            case ILEAVE | SMID -> "dsp";
            case SEQ -> "spd";
            case SEQ | SMID -> "psd";
            default -> "sdp";
        };
        var counts = new int[3];
        for (var i = 0; i < 3; i++) {
            counts[i] = switch (loops.charAt(i)) {
                case 'p' -> planeCount;
                case 'd' -> layers;
                default -> stripeCount;
            };
        }
        var total = (long) counts[0] * counts[1] * counts[2];
        for (long n = 0; n < total && next < count; n++) {
            var inner = (int) (n % counts[2]);
            var middle = (int) (n / counts[2] % counts[1]);
            var outer = (int) (n / counts[2] / counts[1]);
            int plane = 0;
            int layer = 0;
            int stripe = 0;
            var values = new int[]{outer, middle, inner};
            for (var i = 0; i < 3; i++) {
                switch (loops.charAt(i)) {
                    case 'p' -> plane = planeCount - 1 - values[i];
                    case 'd' -> layer = layerOrder[values[i]];
                    default -> stripe = values[i];
                }
            }
            index[plane][layer][stripe] = next++;
        }
    }

    /**
     * Unpacks a 1728-byte prediction table into the tables of the four phases.
     *
     * @param table the packed table
     * @return the four tables, one entry (0, 1 or 2) per byte
     */
    private static byte[][] unpack(byte[] table) {
        var sizes = new int[]{256, 512, 2048, 4096};
        var tables = new byte[4][];
        var at = 0;
        for (var phase = 0; phase < 4; phase++) {
            tables[phase] = new byte[sizes[phase]];
            for (var i = 0; i < sizes[phase]; i++, at++) {
                tables[phase][i] = (byte) ((table[at >> 2] >> (6 - 2 * (at & 3))) & 3);
            }
        }
        return tables;
    }

    /**
     * Decodes every stripe of one layer of one plane.
     *
     * @return whether every stripe decoded; a missing stripe leaves it and the rest of the layer background
     */
    private boolean decodeLayer(byte[] src, List<Stripe> stripes, int[] index, byte[][] images, int layer,
                                int stripeCount) throws DataFormatException {
        var lowest = layer == 0;
        var contexts = lowest ? 1024 : 4096;
        var states = new byte[contexts];
        var mps = new byte[contexts];
        coder.tables(states, mps);
        var reset = true;
        var atX = 0;
        var atY = 0;
        var lntp = 1;
        var linesPerStripe = Math.min(Integer.MAX_VALUE, l0 << Math.min(layer, 40));
        for (var s = 0; s < stripeCount; s++) {
            if (index[s] < 0) {
                return false;
            }
            var stripe = stripes.get(index[s]);
            if (reset) {
                Arrays.fill(states, (byte) 0);
                Arrays.fill(mps, (byte) 0);
                atX = 0;
                atY = 0;
                lntp = 1;
            }
            var y0 = (int) Math.min((long) s * linesPerStripe, heights[layer]);
            var y1 = (int) Math.min(y0 + linesPerStripe, heights[layer]);
            var top = reset ? y0 : 0;
            coder.start(src, stripe.start(), stripe.end());
            var moves = stripe.moves();
            var move = 0;
            for (var y = y0; y < y1; y++) {
                while (move < moves.size() && moves.get(move)[0] == y - y0) {
                    var tx = moves.get(move)[1];
                    var ty = moves.get(move)[2];
                    if (ty > maxY || Math.abs(tx) > maxX || (ty == 0 && tx <= 0 && tx != 0)) {
                        throw new DataFormatException("invalid JBIG adaptive template move");
                    }
                    atX = tx;
                    atY = ty;
                    move++;
                }
                if (lowest) {
                    lntp = lowestLine(images[0], y, top, atX, atY, lntp);
                } else {
                    lntp = differentialLine(images, layer, y, top, s, atX, atY, lntp);
                }
            }
            reset = stripe.reset();
        }
        return true;
    }

    /**
     * Decodes one line of the lowest layer.
     *
     * @return the line-not-typical flag of the line
     */
    private int lowestLine(byte[] image, int y, int top, int atX, int atY, int lntp) {
        var width = widths[0];
        var twoLine = (options & LRLTWO) != 0;
        if ((options & TPBON) != 0) {
            var same = coder.decode(twoLine ? SLNTP_TWO_LINE : SLNTP_THREE_LINE);
            lntp = (same ^ lntp) == 0 ? 1 : 0;
            if (lntp == 0) {
                if (y - 1 >= top) {
                    System.arraycopy(image, (y - 1) * width, image, y * width, width);
                }
                return lntp;
            }
        }
        var defaultAt = atX == 0 && atY == 0;
        for (var x = 0; x < width; x++) {
            var at = defaultAt ? pixel(image, width, x + 2, y - 1, top) : pixel(image, width, x - atX, y - atY, top);
            int cx;
            if (twoLine) {
                cx = pixel(image, width, x - 1, y, top)
                        | pixel(image, width, x - 2, y, top) << 1
                        | pixel(image, width, x - 3, y, top) << 2
                        | pixel(image, width, x - 4, y, top) << 3
                        | pixel(image, width, x + 1, y - 1, top) << 4
                        | pixel(image, width, x, y - 1, top) << 5
                        | pixel(image, width, x - 1, y - 1, top) << 6
                        | pixel(image, width, x - 2, y - 1, top) << 7
                        | pixel(image, width, x - 3, y - 1, top) << 8
                        | at << 9;
            } else {
                cx = pixel(image, width, x - 1, y, top)
                        | pixel(image, width, x - 2, y, top) << 1
                        | pixel(image, width, x + 1, y - 1, top) << 2
                        | pixel(image, width, x, y - 1, top) << 3
                        | pixel(image, width, x - 1, y - 1, top) << 4
                        | pixel(image, width, x - 2, y - 1, top) << 5
                        | pixel(image, width, x + 1, y - 2, top) << 6
                        | pixel(image, width, x, y - 2, top) << 7
                        | pixel(image, width, x - 1, y - 2, top) << 8
                        | at << 9;
            }
            image[y * width + x] = (byte) coder.decode(cx);
        }
        return lntp;
    }

    /**
     * Decodes one line of a differential layer.
     *
     * @return the line-not-typical flag of the line pair
     */
    private int differentialLine(byte[][] images, int layer, int y, int top, int stripe, int atX, int atY,
                                 int lntp) {
        var high = images[layer];
        var low = images[layer - 1];
        var width = widths[layer];
        var lowWidth = widths[layer - 1];
        var ly = y >> 1;
        var lowLines = Math.min(Integer.MAX_VALUE, l0 << Math.min(layer - 1, 40));
        var lowFirst = (int) Math.min((long) stripe * lowLines, heights[layer - 1]);
        var lowLast = (int) Math.min(lowFirst + lowLines, heights[layer - 1]) - 1;
        var lowTop = top == 0 ? 0 : lowFirst;
        if ((options & TPDON) != 0 && (y & 1) == 0) {
            lntp = coder.decode(LNTP_CONTEXT);
        }
        var defaultAt = atX == 0 && atY == 0;
        for (var x = 0; x < width; x++) {
            var lx = x >> 1;
            var phase = (x & 1) | (y & 1) << 1;
            if ((options & TPDON) != 0 && lntp == 0) {
                var center = low(low, lowWidth, lx, ly, lowTop, lowLast);
                var typical = true;
                for (var dy = -1; dy <= 1 && typical; dy++) {
                    for (var dx = -1; dx <= 1; dx++) {
                        if (low(low, lowWidth, lx + dx, ly + dy, lowTop, lowLast) != center) {
                            typical = false;
                            break;
                        }
                    }
                }
                if (typical) {
                    high[y * width + x] = (byte) center;
                    continue;
                }
            }
            if (dpTables != null) {
                var bx = lx << 1;
                var by = ly << 1;
                var index = low(low, lowWidth, lx - 1, ly - 1, lowTop, lowLast)
                        | low(low, lowWidth, lx, ly - 1, lowTop, lowLast) << 1
                        | low(low, lowWidth, lx - 1, ly, lowTop, lowLast) << 2
                        | low(low, lowWidth, lx, ly, lowTop, lowLast) << 3
                        | pixel(high, width, bx - 1, by - 1, top) << 4
                        | pixel(high, width, bx, by - 1, top) << 5
                        | pixel(high, width, bx + 1, by - 1, top) << 6
                        | pixel(high, width, bx - 1, by, top) << 7;
                if (phase >= 1) {
                    index |= pixel(high, width, bx, by, top) << 8;
                }
                if (phase >= 2) {
                    index |= pixel(high, width, bx + 1, by, top) << 9
                            | pixel(high, width, bx - 1, by + 1, top) << 10;
                }
                if (phase == 3) {
                    index |= pixel(high, width, bx, by + 1, top) << 11;
                }
                var value = dpTables[phase][index];
                if (value != 2) {
                    high[y * width + x] = value;
                    continue;
                }
            }
            var at = defaultAt ? pixel(high, width, x - 1, y - 1, top) : pixel(high, width, x - atX, y - atY, top);
            var left = (x & 1) == 0 ? lx - 1 : lx;
            var cx = pixel(high, width, x - 1, y, top)
                    | pixel(high, width, x - 2, y, top) << 1
                    | pixel(high, width, x + 1, y - 1, top) << 2
                    | pixel(high, width, x, y - 1, top) << 3
                    | pixel(high, width, x, y - 2, top) << 4
                    | at << 5
                    | low(low, lowWidth, left, ly, lowTop, lowLast) << 6
                    | low(low, lowWidth, left + 1, ly, lowTop, lowLast) << 7
                    | low(low, lowWidth, left, ly + 1, lowTop, lowLast) << 8
                    | low(low, lowWidth, left + 1, ly + 1, lowTop, lowLast) << 9
                    | phase << 10;
            high[y * width + x] = (byte) coder.decode(cx);
        }
        return lntp;
    }

    /**
     * Returns a pixel of the layer being decoded: background left, right and above the image or the
     * stripe start after a reset.
     */
    private static int pixel(byte[] image, int width, int x, int y, int top) {
        if (x < 0 || x >= width || y < top) {
            return 0;
        }
        return image[y * width + x];
    }

    /**
     * Returns a pixel of the lower layer: background left, right and above, and lines below the current
     * stripe replicating its last line.
     */
    private static int low(byte[] image, int width, int x, int y, int top, int last) {
        if (x < 0 || x >= width || y < top) {
            return 0;
        }
        return image[Math.min(y, last) * width + x];
    }

    /**
     * Packs one pixel per byte into rows of bits, most significant bit first.
     */
    private static byte[] pack(byte[] image, int width, int height) {
        var rowBytes = (width + 7) / 8;
        var packed = new byte[rowBytes * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                if (image[y * width + x] != 0) {
                    packed[y * rowBytes + (x >> 3)] |= (byte) (0x80 >> (x & 7));
                }
            }
        }
        return packed;
    }

    private static long u32(byte[] src, int at) {
        return ((src[at] & 0xFFL) << 24) | ((src[at + 1] & 0xFF) << 16) | ((src[at + 2] & 0xFF) << 8) | (src[at + 3] & 0xFF);
    }
}
