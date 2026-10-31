package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.RawFormat;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.io.MediaSource;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Recognizes the format of a raw file and hands it to the parser of that format.
 *
 * <p>The format is recognized from the content: the Fujifilm, CR3, MRW, CRW and X3F signatures, the TIFF byte
 * order mark with its signature (42 for TIFF, {@code RO} and {@code RS} for Olympus, {@code U}
 * for Panasonic), Phase One's block signature, Canon's {@code CR} marker, the DNG version tag and finally
 * the camera maker of the first directory.
 */
public final class RawParser {
    /**
     * Prevents instantiation.
     */
    private RawParser() {
        throw new AssertionError();
    }

    /**
     * Parses a raw file without decoding its sensor data.
     *
     * <p>A file of a camera model {@link CameraMatrices} knows gets that model's color matrices
     * when it carries none of its own, and a file of a four-color camera without any gets the
     * matrix {@link FourColorMatrices} gives it.
     *
     * @param source the raw file, read but not closed
     * @param limits the resource bounds
     * @return the description
     * @throws MediaException.Unsupported if the input is not a raw format this decoder knows
     * @throws MediaException.Malformed if the structure is corrupt
     */
    public static RawFile parse(MediaSource source, MediaLimits limits) {
        var file = parseFormat(source, limits);
        CameraMatrices.complete(file);
        FourColorMatrices.complete(file);
        return file;
    }

    /**
     * Recognizes the format of a raw file and parses it with that format's parser.
     *
     * @param source the raw file, read but not closed
     * @param limits the resource bounds
     * @return the description
     * @throws MediaException.Unsupported if the input is not a raw format this decoder knows
     * @throws MediaException.Malformed if the structure is corrupt
     */
    private static RawFile parseFormat(MediaSource source, MediaLimits limits) {
        var head = new byte[16];
        var length = SourceBytes.readAvailable(source, 0, head, 0, head.length);
        if (length >= 15 && new String(head, 0, 15, StandardCharsets.ISO_8859_1).equals("FUJIFILMCCD-RAW")) {
            var file = new RawFile(source, limits, RawFormat.RAF);
            RafParser.parse(file);
            return file;
        }
        if (length >= 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p'
                && head[8] == 'c' && head[9] == 'r' && head[10] == 'x') {
            var file = new RawFile(source, limits, RawFormat.CR3);
            Cr3Parser.parse(file);
            return file;
        }
        if (length >= 8 && head[0] == 0 && head[1] == 'M' && head[2] == 'R' && head[3] == 'M') {
            var file = new RawFile(source, limits, RawFormat.MRW);
            MrwParser.parse(file);
            return file;
        }
        if (length >= 14 && (head[0] == 'I' || head[0] == 'M') && head[1] == head[0]
                && new String(head, 6, 8, StandardCharsets.ISO_8859_1).equals("HEAPCCDR")) {
            var file = new RawFile(source, limits, RawFormat.CRW);
            CrwParser.parse(file);
            return file;
        }
        if (length >= 4 && head[0] == 'F' && head[1] == 'O' && head[2] == 'V' && head[3] == 'b') {
            var file = new RawFile(source, limits, RawFormat.X3F);
            X3fParser.parse(file);
            return file;
        }
        if (length < 8 || !((head[0] == 'I' && head[1] == 'I') || (head[0] == 'M' && head[1] == 'M'))) {
            throw new MediaException.Unsupported("raw", "the input is not a raw format this decoder reads");
        }
        var little = head[0] == 'I';
        var magic = ByteArrays.u16(head, 2, little);
        var reader = new TiffReader(source, 0, little, limits);
        if (magic == 0x4F52 || magic == 0x5352) {
            var file = new RawFile(source, limits, RawFormat.ORF);
            OrfParser.parse(reader, file);
            return file;
        }
        if (magic == 0x55) {
            var file = new RawFile(source, limits, RawFormat.RW2);
            Rw2Parser.parse(reader, file);
            return file;
        }
        if (magic != 42) {
            throw new MediaException.Unsupported("raw", "TIFF signature " + magic + " is not a raw format this decoder reads");
        }
        if (IiqParser.matches(head, length)) {
            var file = new RawFile(source, limits, RawFormat.IIQ);
            IiqParser.parse(reader, file);
            return file;
        }
        if (head[8] == 'C' && head[9] == 'R') {
            var file = new RawFile(source, limits, RawFormat.CR2);
            Cr2Parser.parse(reader, file);
            return file;
        }
        var first = reader.directory(reader.firstDirectory());
        if (first.has(50706)) {
            var file = new RawFile(source, limits, RawFormat.DNG);
            DngParser.parse(reader, file);
            return file;
        }
        if (LeafParser.matches(first)) {
            var file = new RawFile(source, limits, RawFormat.MOS);
            LeafParser.parse(reader, file);
            return file;
        }
        var make = first.string(271);
        var vendor = make == null ? "" : make.toUpperCase(Locale.ROOT);
        if (vendor.startsWith("NIKON")) {
            var file = new RawFile(source, limits, RawFormat.NEF);
            NefParser.parse(reader, file);
            return file;
        }
        if (vendor.startsWith("SONY")) {
            var file = new RawFile(source, limits, RawFormat.ARW);
            ArwParser.parse(reader, file);
            return file;
        }
        if (vendor.startsWith("PENTAX") || vendor.startsWith("RICOH")) {
            var file = new RawFile(source, limits, RawFormat.PEF);
            PefParser.parse(reader, file);
            return file;
        }
        if (vendor.startsWith("SAMSUNG")) {
            var file = new RawFile(source, limits, RawFormat.SRW);
            SrwParser.parse(reader, file);
            return file;
        }
        if (KodakParser.matches(vendor)) {
            var file = new RawFile(source, limits, RawFormat.DCR);
            KodakParser.parse(reader, file);
            return file;
        }
        if (vendor.startsWith("HASSELBLAD")) {
            var file = new RawFile(source, limits, RawFormat.FFF);
            HasselbladParser.parse(reader, file);
            return file;
        }
        if (MefParser.matches(vendor)) {
            var file = new RawFile(source, limits, RawFormat.MEF);
            MefParser.parse(reader, file);
            return file;
        }
        if (vendor.startsWith("SEIKO EPSON")) {
            var file = new RawFile(source, limits, RawFormat.ERF);
            ErfParser.parse(reader, file);
            return file;
        }
        var file = new RawFile(source, limits, RawFormat.OTHER);
        TiffEpParser.parse(reader, file);
        return file;
    }
}
