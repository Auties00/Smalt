package com.github.auties00.smalt.video.h264;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns an AVC stream into a scalable (SVC) stream whose base layer is the AVC stream: every base layer
 * slice gets a prefix NAL unit, every sequence parameter set a subset sequence parameter set of the
 * Scalable High profile, every picture parameter set a copy that refers to it, and every primary slice a
 * skipped quality refinement slice ({@code dependency_id} 0, {@code quality_id} 1, {@code slice_skip_flag}
 * 1) covering its macroblocks. Decoding the highest layer of the result runs the base layer through the
 * scalable decoding process (ITU-T H.264 Annex F) with the codings of the AVC stream: field pictures,
 * macroblock-adaptive frames, temporal direct prediction, weighted prediction and so on.
 *
 * <p>Streams with data partitioning, SP or SI slices, more than one slice group, redundant pictures or
 * slices out of order are rejected.
 */
final class H264SvcWrapper {
    /** The {@code pic_parameter_set_id} of the copy of a picture parameter set is the original's plus this. */
    private static final int PPS_ID_OFFSET = 128;

    private H264SvcWrapper() {
    }

    /** The fields of a sequence parameter set the slice headers depend on. */
    private record Sps(boolean separatePlanes, int chromaArrayType, int log2MaxFrameNum, boolean frameMbsOnly,
                       boolean mbaff, int pocType, int log2MaxPocLsb, boolean deltaPicOrderAlwaysZero, int widthMbs,
                       int frameHeightMbs) {
    }

    /** The fields of a picture parameter set the slice headers depend on. */
    private record Pps(int spsId, boolean cabac, boolean bottomFieldPicOrderPresent, int refL0, int refL1,
                       boolean weightedPred, int weightedBipred, boolean deblockingControl, boolean redundantPresent) {
    }

    /**
     * A primary base layer slice whose refinement slice is still to write: its header up to
     * {@code slice_skip_flag}, written, and its first macroblock in the units of {@code first_mb_in_slice}.
     */
    private record Pending(int refIdc, boolean idr, int firstMb, Header header) {
    }

    /**
     * Wraps an Annex B AVC stream, without transform coefficient level prediction.
     *
     * @param annexB the stream
     * @return the scalable stream, in Annex B format
     * @throws IllegalArgumentException if the stream uses a coding the wrapper does not handle
     */
    static byte[] wrap(byte[] annexB) {
        return wrap(annexB, false);
    }

    /**
     * Wraps an Annex B AVC stream. With transform coefficient level prediction
     * ({@code seq_tcoeff_level_prediction_flag} 1) the refinement keeps the base layer's intra macroblocks
     * and levels, so the highest layer decodes to the AVC stream's own decoding; without it, intra
     * macroblocks of the base layer become Intra_Base macroblocks and the highest layer has the decoding of
     * the scalable decoding process for skipped refinements.
     *
     * @param annexB the stream
     * @param coefficientPrediction whether the refinement predicts transform coefficient levels
     * @return the scalable stream, in Annex B format
     * @throws IllegalArgumentException if the stream uses a coding the wrapper does not handle
     */
    static byte[] wrap(byte[] annexB, boolean coefficientPrediction) {
        var spsTable = new HashMap<Integer, Sps>();
        var ppsTable = new HashMap<Integer, Pps>();
        var out = new ByteArrayOutputStream();
        var picture = new ArrayList<Pending>();
        for (var nal : H264TestStreams.nalUnits(annexB)) {
            var type = nal[0] & 31;
            var refIdc = (nal[0] >> 5) & 3;
            if (type == 1 || type == 5) {
                var reader = new Reader(nal);
                var firstMb = reader.ue();
                var sliceType = reader.ue() % 5;
                if (sliceType > 2) {
                    throw new IllegalArgumentException("SP and SI slices are not wrapped");
                }
                var ppsId = reader.ue();
                var pps = ppsTable.get(ppsId);
                var sps = spsTable.get(pps.spsId());
                if (firstMb == 0 && !picture.isEmpty()) {
                    flush(out, picture);
                }
                if (!picture.isEmpty() && firstMb <= picture.getLast().firstMb()) {
                    throw new IllegalArgumentException("slices out of order are not wrapped");
                }
                var header = refinementHeader(reader, refIdc, type == 5, firstMb, sliceType, ppsId, pps, sps);
                write(out, prefix(refIdc, type == 5));
                write(out, nal);
                picture.add(new Pending(refIdc, type == 5, firstMb, header));
                continue;
            }
            if (type >= 2 && type <= 4) {
                throw new IllegalArgumentException("data partitioning is not wrapped");
            }
            if (!picture.isEmpty() && (type == 6 || type == 7 || type == 8 || type == 9 || type == 10 || type == 11
                    || (type >= 13 && type <= 18))) {
                flush(out, picture);
            }
            write(out, nal);
            if (type == 7) {
                write(out, subsetSps(nal, spsTable, coefficientPrediction));
            } else if (type == 8) {
                write(out, ppsCopy(nal, ppsTable));
            }
        }
        if (!picture.isEmpty()) {
            flush(out, picture);
        }
        return out.toByteArray();
    }

    /** Writes a NAL unit with a four-byte start code. */
    private static void write(ByteArrayOutputStream out, byte[] nal) {
        out.writeBytes(new byte[]{0, 0, 0, 1});
        out.writeBytes(nal);
    }

    /** Writes the refinement slices of a picture, each covering the macroblocks of its base layer slice. */
    private static void flush(ByteArrayOutputStream out, List<Pending> picture) {
        for (var i = 0; i < picture.size(); i++) {
            var slice = picture.get(i);
            var header = slice.header();
            var end = i + 1 < picture.size() ? picture.get(i + 1).firstMb() : header.end;
            var count = (end - slice.firstMb()) * header.scale;
            header.writer.bit(1).ue(count - 1);
            var rbsp = header.writer.trailing();
            var body = new byte[3 + rbsp.length];
            System.arraycopy(extension(slice.idr(), false, 1, true), 0, body, 0, 3);
            System.arraycopy(rbsp, 0, body, 3, rbsp.length);
            write(out, escapedNal(slice.refIdc(), 20, body, 3));
        }
        picture.clear();
    }

    /**
     * Returns the NAL unit header extension of a slice: {@code svc_extension_flag} 1, {@code priority_id} 0,
     * {@code dependency_id} 0, {@code temporal_id} 0, {@code use_ref_base_pic_flag} 0, {@code output_flag} 1.
     */
    private static byte[] extension(boolean idr, boolean noInterLayerPred, int qualityId, boolean discardable) {
        return new byte[]{(byte) (0x80 | (idr ? 0x40 : 0)), (byte) ((noInterLayerPred ? 0x80 : 0) | qualityId),
                (byte) ((discardable ? 0x08 : 0) | 0x04 | 0x03)};
    }

    /** Returns the prefix NAL unit of a base layer slice, which stores no reference base picture. */
    private static byte[] prefix(int refIdc, boolean idr) {
        var payload = new H264BitWriter();
        if (refIdc != 0) {
            payload.bit(0).bit(0);
        }
        var rbsp = payload.trailing();
        var body = new byte[3 + rbsp.length];
        System.arraycopy(extension(idr, true, 0, false), 0, body, 0, 3);
        System.arraycopy(rbsp, 0, body, 3, rbsp.length);
        return escapedNal(refIdc, 14, body, 3);
    }

    /** Builds a NAL unit from a header byte and a body whose first {@code raw} bytes are not escaped. */
    private static byte[] escapedNal(int refIdc, int type, byte[] body, int raw) {
        var rest = new byte[body.length - raw];
        System.arraycopy(body, raw, rest, 0, rest.length);
        var escaped = H264BitWriter.nal(refIdc, type, rest);
        var result = new byte[raw + escaped.length];
        result[0] = escaped[0];
        System.arraycopy(body, 0, result, 1, raw);
        System.arraycopy(escaped, 1, result, 1 + raw, escaped.length - 1);
        return result;
    }

    /** Parses a sequence parameter set and returns its subset sequence parameter set copy. */
    private static byte[] subsetSps(byte[] nal, Map<Integer, Sps> table, boolean coefficientPrediction) {
        var reader = new Reader(nal);
        var profile = reader.bits(8);
        reader.bits(8);
        var level = reader.bits(8);
        var id = reader.ue();
        var writer = new H264BitWriter();
        writer.bits(86, 8).bits(0, 8).bits(level, 8).ue(id);
        var chromaFormat = 1;
        var separate = false;
        var high = profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44
                || profile == 83 || profile == 86 || profile == 118 || profile == 128 || profile == 138
                || profile == 139 || profile == 134 || profile == 135;
        var start = reader.position();
        if (high) {
            chromaFormat = reader.ue();
            if (chromaFormat == 3) {
                separate = reader.bit() != 0;
            }
            reader.ue();
            reader.ue();
            reader.bit();
            if (reader.bit() != 0) {
                for (var i = 0; i < (chromaFormat != 3 ? 8 : 12); i++) {
                    if (reader.bit() != 0) {
                        skipScalingList(reader, i < 6 ? 16 : 64);
                    }
                }
            }
        } else {
            writer.ue(1).ue(0).ue(0).bit(0).bit(0);
        }
        var log2MaxFrameNum = reader.ue() + 4;
        var pocType = reader.ue();
        var log2MaxPocLsb = 0;
        var alwaysZero = false;
        if (pocType == 0) {
            log2MaxPocLsb = reader.ue() + 4;
        } else if (pocType == 1) {
            alwaysZero = reader.bit() != 0;
            reader.se();
            reader.se();
            var cycle = reader.ue();
            for (var i = 0; i < cycle; i++) {
                reader.se();
            }
        }
        reader.ue();
        reader.bit();
        var widthMbs = reader.ue() + 1;
        var mapUnits = reader.ue() + 1;
        var frameMbsOnly = reader.bit() != 0;
        var mbaff = !frameMbsOnly && reader.bit() != 0;
        reader.bit();
        if (reader.bit() != 0) {
            reader.ue();
            reader.ue();
            reader.ue();
            reader.ue();
        }
        var end = reader.position();
        reader.seek(start);
        while (reader.position() < end) {
            writer.bit(reader.bit());
        }
        writer.bit(0);
        var chromaArrayType = separate ? 0 : chromaFormat;
        table.put(id, new Sps(separate, chromaArrayType, log2MaxFrameNum, frameMbsOnly, mbaff, pocType, log2MaxPocLsb,
                alwaysZero, widthMbs, mapUnits * (frameMbsOnly ? 1 : 2)));
        writer.bit(0).bits(0, 2);
        if (chromaArrayType == 1 || chromaArrayType == 2) {
            writer.bit(1);
        }
        if (chromaArrayType == 1) {
            writer.bits(1, 2);
        }
        writer.flag(coefficientPrediction);
        if (coefficientPrediction) {
            writer.bit(0);
        }
        writer.bit(1);
        writer.bit(0).bit(0);
        return H264BitWriter.nal((nal[0] >> 5) & 3, 15, writer.trailing());
    }

    /** Skips a {@code scaling_list()}. */
    private static void skipScalingList(Reader reader, int size) {
        var last = 8;
        var next = 8;
        for (var j = 0; j < size; j++) {
            if (next != 0) {
                next = (last + reader.se() + 256) % 256;
            }
            last = next == 0 ? last : next;
        }
    }

    /** Parses a picture parameter set and returns its copy for the refinement slices. */
    private static byte[] ppsCopy(byte[] nal, Map<Integer, Pps> table) {
        var reader = new Reader(nal);
        var id = reader.ue();
        var spsId = reader.ue();
        var rest = reader.position();
        var cabac = reader.bit() != 0;
        var bottom = reader.bit() != 0;
        if (reader.ue() != 0) {
            throw new IllegalArgumentException("slice groups are not wrapped");
        }
        var refL0 = reader.ue() + 1;
        var refL1 = reader.ue() + 1;
        var weighted = reader.bit() != 0;
        var bipred = reader.bits(2);
        reader.se();
        reader.se();
        reader.se();
        var deblocking = reader.bit() != 0;
        reader.bit();
        var redundant = reader.bit() != 0;
        table.put(id, new Pps(spsId, cabac, bottom, refL0, refL1, weighted, bipred, deblocking, redundant));
        if (id + PPS_ID_OFFSET > 255) {
            throw new IllegalArgumentException("picture parameter set id out of range");
        }
        var writer = new H264BitWriter();
        writer.ue(id + PPS_ID_OFFSET).ue(spsId);
        reader.seek(rest);
        var end = reader.lastOne();
        while (reader.position() < end) {
            writer.bit(reader.bit());
        }
        return H264BitWriter.nal((nal[0] >> 5) & 3, 8, writer.trailing());
    }

    /** A refinement slice header written up to {@code slice_skip_flag}, with the slice geometry. */
    private static final class Header {
        final H264BitWriter writer = new H264BitWriter();

        /** The {@code first_mb_in_slice} past the last macroblock of the picture. */
        int end;

        /** The macroblocks per {@code first_mb_in_slice} unit: 2 in a frame of macroblock pairs. */
        int scale;
    }

    /**
     * Parses the rest of a base layer slice header and returns the header of the refinement slice for it.
     *
     * @throws IllegalArgumentException for a redundant slice
     */
    private static Header refinementHeader(Reader reader, int refIdc, boolean idr, int firstMb, int sliceType,
                                           int ppsId, Pps pps, Sps sps) {
        if (sps.separatePlanes()) {
            throw new IllegalArgumentException("separate colour planes are not wrapped");
        }
        var frameNum = reader.bits(sps.log2MaxFrameNum());
        var field = false;
        var bottom = false;
        if (!sps.frameMbsOnly()) {
            field = reader.bit() != 0;
            if (field) {
                bottom = reader.bit() != 0;
            }
        }
        var idrPicId = idr ? reader.ue() : 0;
        var pocLsb = 0;
        var deltaBottom = 0;
        var delta0 = 0;
        var delta1 = 0;
        if (sps.pocType() == 0) {
            pocLsb = reader.bits(sps.log2MaxPocLsb());
            if (pps.bottomFieldPicOrderPresent() && !field) {
                deltaBottom = reader.se();
            }
        } else if (sps.pocType() == 1 && !sps.deltaPicOrderAlwaysZero()) {
            delta0 = reader.se();
            if (pps.bottomFieldPicOrderPresent() && !field) {
                delta1 = reader.se();
            }
        }
        if (pps.redundantPresent() && reader.ue() > 0) {
            throw new IllegalArgumentException("redundant pictures are not wrapped");
        }
        var refL0 = pps.refL0();
        var refL1 = pps.refL1();
        if (sliceType == 1) {
            reader.bit();
        }
        if (sliceType != 2) {
            if (reader.bit() != 0) {
                refL0 = reader.ue() + 1;
                if (sliceType == 1) {
                    refL1 = reader.ue() + 1;
                }
            }
            for (var list = 0; list < (sliceType == 1 ? 2 : 1); list++) {
                if (reader.bit() != 0) {
                    while (reader.ue() != 3) {
                        reader.ue();
                    }
                }
            }
        }
        if ((pps.weightedPred() && sliceType == 0) || (pps.weightedBipred() == 1 && sliceType == 1)) {
            reader.ue();
            if (sps.chromaArrayType() != 0) {
                reader.ue();
            }
            for (var list = 0; list < (sliceType == 1 ? 2 : 1); list++) {
                for (var i = 0; i < (list == 0 ? refL0 : refL1); i++) {
                    if (reader.bit() != 0) {
                        reader.se();
                        reader.se();
                    }
                    if (sps.chromaArrayType() != 0 && reader.bit() != 0) {
                        reader.se();
                        reader.se();
                        reader.se();
                        reader.se();
                    }
                }
            }
        }
        if (refIdc != 0) {
            if (idr) {
                reader.bit();
                reader.bit();
            } else if (reader.bit() != 0) {
                int op;
                while ((op = reader.ue()) != 0) {
                    if (op == 1 || op == 2 || op == 3 || op == 4 || op == 6) {
                        reader.ue();
                    }
                    if (op == 3) {
                        reader.ue();
                    }
                }
            }
        }
        var cabacInit = pps.cabac() && sliceType != 2 ? reader.ue() : 0;
        var qpDelta = reader.se();
        var deblockingIdc = 0;
        var alpha = 0;
        var beta = 0;
        if (pps.deblockingControl()) {
            deblockingIdc = reader.ue();
            if (deblockingIdc != 1) {
                alpha = reader.se();
                beta = reader.se();
            }
        }
        var header = new Header();
        var mbaffFrame = sps.mbaff() && !field;
        header.scale = mbaffFrame ? 2 : 1;
        header.end = sps.widthMbs() * sps.frameHeightMbs() / (field || mbaffFrame ? 2 : 1);
        var writer = header.writer;
        writer.ue(firstMb).ue(sliceType).ue(ppsId + PPS_ID_OFFSET).bits(frameNum, sps.log2MaxFrameNum());
        if (!sps.frameMbsOnly()) {
            writer.flag(field);
            if (field) {
                writer.flag(bottom);
            }
        }
        if (idr) {
            writer.ue(idrPicId);
        }
        if (sps.pocType() == 0) {
            writer.bits(pocLsb, sps.log2MaxPocLsb());
            if (pps.bottomFieldPicOrderPresent() && !field) {
                writer.se(deltaBottom);
            }
        } else if (sps.pocType() == 1 && !sps.deltaPicOrderAlwaysZero()) {
            writer.se(delta0);
            if (pps.bottomFieldPicOrderPresent() && !field) {
                writer.se(delta1);
            }
        }
        if (pps.redundantPresent()) {
            writer.ue(0);
        }
        if (pps.cabac() && sliceType != 2) {
            writer.ue(cabacInit);
        }
        writer.se(qpDelta);
        if (pps.deblockingControl()) {
            writer.ue(deblockingIdc);
            if (deblockingIdc != 1) {
                writer.se(alpha).se(beta);
            }
        }
        return header;
    }

    /** Reads the bits of an RBSP after its NAL unit header, emulation prevention bytes removed. */
    private static final class Reader {
        private final byte[] data;
        private final int size;
        private int position;

        Reader(byte[] nal) {
            var out = new ByteArrayOutputStream();
            var zeros = 0;
            for (var i = 1; i < nal.length; i++) {
                var value = nal[i] & 0xFF;
                if (zeros >= 2 && value == 3) {
                    zeros = 0;
                    continue;
                }
                zeros = value == 0 ? zeros + 1 : 0;
                out.write(value);
            }
            data = out.toByteArray();
            size = data.length * 8;
        }

        int bit() {
            if (position >= size) {
                throw new IllegalArgumentException("truncated RBSP");
            }
            var value = (data[position >> 3] >> (7 - (position & 7))) & 1;
            position++;
            return value;
        }

        int bits(int count) {
            var value = 0;
            for (var i = 0; i < count; i++) {
                value = (value << 1) | bit();
            }
            return value;
        }

        int ue() {
            var zeros = 0;
            while (bit() == 0) {
                zeros++;
            }
            return (1 << zeros) - 1 + bits(zeros);
        }

        int se() {
            var code = ue();
            return (code & 1) != 0 ? (code + 1) >> 1 : -(code >> 1);
        }

        int position() {
            return position;
        }

        void seek(int bit) {
            position = bit;
        }

        /** Returns the position of the {@code rbsp_stop_one_bit}. */
        int lastOne() {
            var end = data.length - 1;
            while (end >= 0 && data[end] == 0) {
                end--;
            }
            var bit = Integer.numberOfTrailingZeros(data[end] & 0xFF);
            return end * 8 + 7 - bit;
        }
    }
}
