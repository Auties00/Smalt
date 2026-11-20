package com.github.auties00.smalt.image.jxl.internal.encode;

import com.github.auties00.smalt.image.jxl.internal.header.BitDepth;
import com.github.auties00.smalt.image.jxl.internal.header.ColorEncoding;
import com.github.auties00.smalt.image.jxl.internal.header.ExtraChannelInfo;
import com.github.auties00.smalt.image.jxl.internal.header.ImageHeader;
import com.github.auties00.smalt.image.jxl.internal.header.UpsamplingWeights;
import com.github.auties00.smalt.image.jxl.internal.modular.ModularChannel;
import com.github.auties00.smalt.image.jxl.internal.modular.ModularEncoder;
import com.github.auties00.smalt.image.jxl.internal.vardct.VarDctEncoder;

import java.util.ArrayList;
import java.util.List;

/**
 * Encodes images to JPEG XL codestreams.
 */
public final class JxlImageEncoder {
    private JxlImageEncoder() {
        throw new AssertionError();
    }

    /**
     * The samples and description of an image to encode.
     *
     * @param width the image width in pixels
     * @param height the image height in pixels
     * @param color the color planes, 1 (gray) or 3 (RGB or CMY of a CMYK image), row-major integer samples, or
     * binary32 bit patterns when the depth is floating point
     * @param depth the sample depth of the color planes
     * @param extra the extra channel planes, at full size
     * @param extraInfo the description of each extra channel
     * @param colorEncoding the color encoding written to the header
     * @param icc the ICC profile when the encoding asks for one, or {@code null}
     * @param orientation the EXIF orientation, from 1 to 8
     */
    public record Source(int width, int height, List<int[]> color, BitDepth depth, List<int[]> extra,
                         List<ExtraChannelInfo> extraInfo, ColorEncoding colorEncoding, byte[] icc,
                         int orientation) {
    }

    /**
     * Builds the image header of a source.
     *
     * @param s the source
     * @param xyb whether the frames are XYB-encoded
     * @param animation the animation header, or {@code null} for a still image
     * @param tone the tone mapping
     * @return the header
     */
    public static ImageHeader header(Source s, boolean xyb, ImageHeader.Animation animation,
                                     ImageHeader.ToneMapping tone) {
        // XYB color channels are not coded as modular samples, so only the extra channels count then.
        var modular16 = xyb || (!s.depth().floatSample() && s.depth().bitsPerSample() <= 12);
        for (var e : s.extraInfo()) {
            modular16 &= !e.bitDepth().floatSample() && e.bitDepth().bitsPerSample() <= 12;
        }
        return new ImageHeader(s.width(), s.height(), s.orientation(), 0, 0, 0, 0, animation, s.depth(), modular16,
                s.extraInfo(), xyb, s.colorEncoding(), tone, ImageHeader.OpsinInverse.DEFAULT,
                UpsamplingWeights.UP2, UpsamplingWeights.UP4, UpsamplingWeights.UP8);
    }

    /**
     * Writes the codestream signature, the image header and the ICC profile, byte-aligned.
     *
     * @param out the output
     * @param s the source
     * @param header the image header
     */
    public static void writeHeaders(JxlBitWriter out, Source s, ImageHeader header) {
        HeaderWriter.writeImageHeader(out, header);
        if (header.colorEncoding().wantIcc()) {
            IccWriter.write(out, s.icc());
        }
        out.zeroPadToByte();
    }

    /**
     * Encodes a lossless frame of a source.
     *
     * @param out the codestream, byte-aligned after the headers
     * @param s the source
     * @param header the image header
     * @param frame the frame fields; encoding, filters and group size are set here
     * @param effort the effort, from 1 to 10
     */
    public static void writeLosslessFrame(JxlBitWriter out, Source s, ImageHeader header, HeaderWriter.Frame frame,
                                          int effort) {
        var channels = new ArrayList<ModularChannel>();
        var width = frame.haveCrop ? frame.width : s.width();
        var height = frame.haveCrop ? frame.height : s.height();
        for (var plane : s.color()) {
            channels.add(channel(plane, width, height));
        }
        for (var plane : s.extra()) {
            channels.add(channel(plane, width, height));
        }
        var bitDepth = header.bitDepth().bitsPerSample();
        for (var e : header.extraChannels()) {
            bitDepth = Math.max(bitDepth, e.bitDepth().bitsPerSample());
        }
        frame.modular = true;
        frame.gaborish = false;
        frame.epfIterations = 0;
        frame.groupSizeShift = 1;
        var sections = ModularFrameEncoder.encode(channels, s.color().size(), bitDepth, effort,
                frame.groupSizeShift);
        var groupDim = 128 << frame.groupSizeShift;
        var numGroups = ModularFrameEncoder.ceilDiv(width, groupDim) * ModularFrameEncoder.ceilDiv(height, groupDim);
        CodestreamWriter.writeFrame(out, frame, header, sections, numGroups, 1);
    }

    /**
     * Encodes a lossy VarDCT frame of a source in XYB; extra channels are coded losslessly.
     *
     * @param out the codestream, byte-aligned after the headers
     * @param s the source
     * @param header the image header, XYB-encoded
     * @param frame the frame fields; encoding and filters are set here
     * @param distance the Butteraugli distance target, above 0
     * @param effort the effort, from 1 to 10
     */
    public static void writeLossyFrame(JxlBitWriter out, Source s, ImageHeader header, HeaderWriter.Frame frame,
                                       float distance, int effort) {
        var width = s.width();
        var height = s.height();
        var xyb = XybConverter.convert(s, header.toneMapping().intensityTarget());
        var gaborish = effort >= 5 && distance > 0.5f;
        var vd = VarDctEncoder.fromXyb(xyb, width, height, distance, effort, gaborish);
        var streams = new ArrayList<ModularEncoder.Stream>();
        var settings = new ArrayList<ModularEncoder.TreeSettings>();
        var numLf = vd.numLfGroups();
        ModularFrameEncoder.Prepared extra = null;
        if (!s.extra().isEmpty()) {
            var channels = new ArrayList<ModularChannel>();
            for (var plane : s.extra()) {
                channels.add(channel(plane, width, height));
            }
            var bitDepth = 1;
            for (var e : header.extraChannels()) {
                bitDepth = Math.max(bitDepth, e.bitDepth().bitsPerSample());
            }
            extra = ModularFrameEncoder.prepare(channels, 0, bitDepth, effort, 256, numLf);
            streams.add(extra.global());
            settings.add(ModularFrameEncoder.treeSettings(effort, 30, 0));
        }
        streams.addAll(vd.modularStreams());
        settings.addAll(vd.treeSettings(effort, false));
        if (extra != null) {
            for (var g : extra.groups()) {
                if (g != null) {
                    streams.add(g);
                }
            }
            settings.add(ModularFrameEncoder.treeSettings(effort, 30, 1 + 3 * numLf + 17));
        }
        if (settings.getFirst().firstStream() != 0) {
            var first = settings.removeFirst();
            settings.addFirst(new ModularEncoder.TreeSettings(0, first.kind(), first.predictors(), first.properties(),
                    first.maxPropertyValues(), first.nodeThreshold(), first.sampleFraction(),
                    first.fastDecodeMultiplier()));
        }
        var modular = ModularEncoder.encode(streams, settings, EntropyEncoder.Params.forEffort(effort, true));
        vd.tokenizeHf(EntropyEncoder.Params.forEffort(effort, false));
        frame.modular = false;
        frame.gaborish = gaborish;
        var epf = 0;
        for (var threshold : new float[]{0.7f, 1.5f, 4.0f}) {
            if (distance >= threshold) {
                epf++;
            }
        }
        frame.epfIterations = epf;
        frame.xQmScale = vd.xQmScale();
        frame.bQmScale = vd.bQmScale();
        var sections = new ArrayList<JxlBitWriter>();
        var lfGlobal = new JxlBitWriter(4096);
        vd.writeLfDequant(lfGlobal);
        vd.writeLfGlobalVarDct(lfGlobal);
        modular.writeGlobal(lfGlobal);
        if (extra != null) {
            modular.writeStream(extra.global(), lfGlobal);
        }
        sections.add(lfGlobal);
        for (var i = 0; i < numLf; i++) {
            var w = new JxlBitWriter(4096);
            vd.writeLfGroup(i, modular, w, () -> {
            });
            sections.add(w);
        }
        var hfGlobal = new JxlBitWriter(4096);
        vd.writeHfGlobal(modular, hfGlobal);
        sections.add(hfGlobal);
        for (var g = 0; g < vd.numGroups(); g++) {
            var w = new JxlBitWriter(8192);
            vd.writePassGroup(g, w);
            if (extra != null) {
                extra.writeGroup(modular, g, w);
            }
            sections.add(w);
        }
        CodestreamWriter.writeFrame(out, frame, header, sections, vd.numGroups(), 1);
    }

    private static ModularChannel channel(int[] plane, int width, int height) {
        var c = new ModularChannel(width, height, 0, 0);
        c.data = plane;
        return c;
    }
}
