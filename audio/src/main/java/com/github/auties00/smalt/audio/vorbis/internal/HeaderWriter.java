/* Ports libvorbis 1.3.7 (lib/info.c, codebook.c, floor1.c, res0.c, mapping0.c).
   Copyright Xiph.Org Foundation; BSD 3-Clause. Modified for Smalt. */
package com.github.auties00.smalt.audio.vorbis.internal;

import com.github.auties00.smalt.internal.bits.LsbBitWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Writes the three Vorbis header packets: identification, comment and setup
 * ({@code _vorbis_pack_info}, {@code _vorbis_pack_comment}, {@code _vorbis_pack_books}).
 */
public final class HeaderWriter {
    /**
     * Prevents instantiation.
     */
    private HeaderWriter() {
        throw new AssertionError();
    }

    /**
     * Writes the identification header.
     *
     * @param setup the encoder configuration the header describes
     * @return the packet
     */
    public static byte[] identification(CodecSetup setup) {
        var out = new LsbBitWriter();
        out.write(0x01, 8);
        writeAscii(out, "vorbis");
        out.write(0, 32);
        out.write(setup.channels, 8);
        out.write(setup.rate, 32);
        out.write(setup.bitrateUpper, 32);
        out.write(setup.bitrateNominal, 32);
        out.write(setup.bitrateLower, 32);
        out.write(Ilog.ilog(setup.blockSizes[0] - 1), 4);
        out.write(Ilog.ilog(setup.blockSizes[1] - 1), 4);
        out.write(1, 1);
        return out.toByteArray();
    }

    /**
     * Writes a comment header.
     *
     * @param vendor the vendor string
     * @param comments the {@code KEY=value} comments
     * @return the packet
     */
    public static byte[] comment(String vendor, List<String> comments) {
        var out = new LsbBitWriter();
        out.write(0x03, 8);
        writeAscii(out, "vorbis");
        writeUtf8(out, vendor);
        out.write(comments.size(), 32);
        for (var comment : comments) {
            writeUtf8(out, comment);
        }
        out.write(1, 1);
        return out.toByteArray();
    }

    /**
     * Writes the bytes of an ASCII string, eight bits each.
     *
     * @param out the destination
     * @param text the string
     */
    private static void writeAscii(LsbBitWriter out, String text) {
        for (var i = 0; i < text.length(); i++) {
            out.write(text.charAt(i), 8);
        }
    }

    /**
     * Writes a length-prefixed UTF-8 string.
     *
     * @param out the destination
     * @param text the string
     */
    private static void writeUtf8(LsbBitWriter out, String text) {
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        out.write(bytes.length, 32);
        for (var b : bytes) {
            out.write(b & 0xFF, 8);
        }
    }

    /**
     * Writes the setup header.
     *
     * @param setup the encoder configuration the header describes
     * @return the packet
     */
    public static byte[] setup(CodecSetup setup) {
        var out = new LsbBitWriter();
        out.write(0x05, 8);
        writeAscii(out, "vorbis");
        out.write(setup.books.size() - 1, 8);
        for (var book : setup.books) {
            writeBook(out, book);
        }
        out.write(0, 6);
        out.write(0, 16);
        out.write(setup.floors.size() - 1, 6);
        for (var floor : setup.floors) {
            out.write(1, 16);
            writeFloor(out, floor);
        }
        out.write(setup.residueCount - 1, 6);
        for (var i = 0; i < setup.residueCount; i++) {
            out.write(setup.residueTypes[i], 16);
            writeResidue(out, setup.residues[i]);
        }
        out.write(setup.modes - 1, 6);
        for (var i = 0; i < setup.modes; i++) {
            out.write(0, 16);
            writeMapping(out, setup.maps[i], setup.channels);
        }
        out.write(setup.modes - 1, 6);
        for (var i = 0; i < setup.modes; i++) {
            out.write(i, 1);
            out.write(0, 16);
            out.write(0, 16);
            out.write(i, 8);
        }
        out.write(1, 1);
        return out.toByteArray();
    }

    /**
     * Writes a codebook.
     *
     * @param out the destination
     * @param book the codebook
     */
    private static void writeBook(LsbBitWriter out, StaticCodebook book) {
        var lengths = book.lengths();
        var entries = book.entries();
        out.write(0x564342, 24);
        out.write(book.dim(), 16);
        out.write(entries, 24);
        var i = 1;
        for (; i < entries; i++) {
            if (lengths[i - 1] == 0 || lengths[i] < lengths[i - 1]) {
                break;
            }
        }
        if (i == entries) {
            var count = 0;
            out.write(1, 1);
            out.write(lengths[0] - 1, 5);
            for (i = 1; i < entries; i++) {
                int current = lengths[i];
                int last = lengths[i - 1];
                if (current > last) {
                    for (var j = last; j < current; j++) {
                        out.write(i - count, Ilog.ilog(entries - count));
                        count = i;
                    }
                }
            }
            out.write(i - count, Ilog.ilog(entries - count));
        } else {
            out.write(0, 1);
            for (i = 0; i < entries; i++) {
                if (lengths[i] == 0) {
                    break;
                }
            }
            if (i == entries) {
                out.write(0, 1);
                for (i = 0; i < entries; i++) {
                    out.write(lengths[i] - 1, 5);
                }
            } else {
                out.write(1, 1);
                for (i = 0; i < entries; i++) {
                    if (lengths[i] == 0) {
                        out.write(0, 1);
                    } else {
                        out.write(1, 1);
                        out.write(lengths[i] - 1, 5);
                    }
                }
            }
        }
        out.write(book.mapType(), 4);
        if (book.mapType() == 1 || book.mapType() == 2) {
            out.write(book.quantMin(), 32);
            out.write(book.quantDelta(), 32);
            out.write(book.quantBits() - 1, 4);
            out.write(book.sequenceP() ? 1 : 0, 1);
            var values = book.mapType() == 1 ? book.quantValues() : entries * book.dim();
            for (i = 0; i < values; i++) {
                out.write(Math.abs(book.quantList()[i]), book.quantBits());
            }
        }
    }

    /**
     * Writes a floor type 1 configuration.
     *
     * @param out the destination
     * @param info the floor
     */
    private static void writeFloor(LsbBitWriter out, Floor1Info info) {
        var maxPosition = info.postList[1];
        var maxClass = -1;
        out.write(info.partitions, 5);
        for (var j = 0; j < info.partitions; j++) {
            out.write(info.partitionClass[j], 4);
            maxClass = Math.max(maxClass, info.partitionClass[j]);
        }
        for (var j = 0; j < maxClass + 1; j++) {
            out.write(info.classDim[j] - 1, 3);
            out.write(info.classSubs[j], 2);
            if (info.classSubs[j] != 0) {
                out.write(info.classBook[j], 8);
            }
            for (var k = 0; k < (1 << info.classSubs[j]); k++) {
                out.write(info.classSubbook[j][k] + 1, 8);
            }
        }
        out.write(info.mult - 1, 2);
        var rangeBits = Ilog.ilog(maxPosition - 1);
        out.write(rangeBits, 4);
        var count = 0;
        for (int j = 0, k = 0; j < info.partitions; j++) {
            count += info.classDim[info.partitionClass[j]];
            for (; k < count; k++) {
                out.write(info.postList[k + 2], rangeBits);
            }
        }
    }

    /**
     * Writes a residue configuration.
     *
     * @param out the destination
     * @param info the residue
     */
    private static void writeResidue(LsbBitWriter out, Residue0Info info) {
        var acc = 0;
        out.write(info.begin, 24);
        out.write(info.end, 24);
        out.write(info.grouping - 1, 24);
        out.write(info.partitions - 1, 6);
        out.write(info.groupBook, 8);
        for (var j = 0; j < info.partitions; j++) {
            if (Ilog.ilog(info.secondStages[j]) > 3) {
                out.write(info.secondStages[j], 3);
                out.write(1, 1);
                out.write(info.secondStages[j] >> 3, 5);
            } else {
                out.write(info.secondStages[j], 4);
            }
            acc += Integer.bitCount(info.secondStages[j]);
        }
        for (var j = 0; j < acc; j++) {
            out.write(info.bookList[j], 8);
        }
    }

    /**
     * Writes a mapping type 0 configuration.
     *
     * @param out the destination
     * @param info the mapping
     * @param channels the number of channels
     */
    private static void writeMapping(LsbBitWriter out, Mapping0Info info, int channels) {
        if (info.submaps > 1) {
            out.write(1, 1);
            out.write(info.submaps - 1, 4);
        } else {
            out.write(0, 1);
        }
        if (info.couplingSteps > 0) {
            out.write(1, 1);
            out.write(info.couplingSteps - 1, 8);
            for (var i = 0; i < info.couplingSteps; i++) {
                out.write(info.couplingMagnitude[i], Ilog.ilog(channels - 1));
                out.write(info.couplingAngle[i], Ilog.ilog(channels - 1));
            }
        } else {
            out.write(0, 1);
        }
        out.write(0, 2);
        if (info.submaps > 1) {
            for (var i = 0; i < channels; i++) {
                out.write(info.channelMux[i], 4);
            }
        }
        for (var i = 0; i < info.submaps; i++) {
            out.write(0, 8);
            out.write(info.floorSubmap[i], 8);
            out.write(info.residueSubmap[i], 8);
        }
    }
}
