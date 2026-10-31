/* Ports libjxl 0.11.2 (lib/jxl/enc_frame.cc: MakeFrameHeader and ComputeJPEGTranscodingData for JPEG frames;
   lib/jxl/jpeg/enc_jpeg_data.cc: SetChromaSubsamplingFromJpegData, SetColorTransformFromJpegData,
   SetColorEncodingFromJpegData). Copyright the JPEG XL Project Authors; BSD 3-Clause License. Modified for
   Smalt. */
package com.github.auties00.smalt.image.jxl.internal.encode;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.jxl.internal.header.BitDepth;
import com.github.auties00.smalt.image.jxl.internal.header.ColorEncoding;
import com.github.auties00.smalt.image.jxl.internal.header.ImageHeader;
import com.github.auties00.smalt.image.jxl.internal.header.UpsamplingWeights;
import com.github.auties00.smalt.image.jxl.internal.modular.ModularEncoder;
import com.github.auties00.smalt.image.jxl.internal.vardct.VarDctEncoder;
import com.github.auties00.smalt.internal.tiff.TiffExif;

import java.util.ArrayList;
import java.util.List;

/**
 * Recompresses JPEG files losslessly into JPEG XL: the coefficients as a VarDCT frame, the rest in a
 * {@code jbrd} box.
 */
public final class JpegRecompressor {
    private JpegRecompressor() {
        throw new AssertionError();
    }

    /**
     * Recompresses a JPEG file.
     *
     * @param jpeg the JPEG file
     * @param effort the effort, from 1 to 10
     * @param limits the bounds of the image size and memory
     * @return the JPEG XL container
     * @throws MediaException.Malformed if the JPEG file is invalid
     * @throws MediaException.Unsupported if the JPEG file uses a process JPEG XL cannot hold
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     */
    public static byte[] recompress(byte[] jpeg, int effort, com.github.auties00.smalt.MediaLimits limits) {
        var reader = JpegReader.read(jpeg, limits);
        var jbrd = new JbrdWriter(reader);
        var payload = jbrd.payload();
        var count = reader.components.size();
        var ycbcr = isYcbcr(reader);
        var map = count == 1 ? new int[]{-1, 0, -1} : ycbcr ? new int[]{1, 0, 2} : new int[]{0, 1, 2};
        var upsampling = new int[3];
        for (var c = 0; c < 3; c++) {
            var comp = reader.components.get(count == 1 ? 0 : (c < 2 ? c ^ 1 : c));
            upsampling[c] = mode(comp.h, comp.v);
        }
        if (!ycbcr) {
            for (var mode : upsampling) {
                if (mode != 0) {
                    throw new MediaException.Unsupported("JPEG XL", "a subsampled RGB JPEG file cannot be recompressed");
                }
            }
        }
        var channels = new VarDctEncoder.JpegChannel[3];
        for (var c = 0; c < 3; c++) {
            if (map[c] < 0) {
                continue;
            }
            var comp = reader.components.get(map[c]);
            channels[c] = new VarDctEncoder.JpegChannel(comp.coefficients, comp.widthInBlocks, comp.heightInBlocks,
                    reader.quant.get(comp.quantIndex).values);
        }
        var vd = VarDctEncoder.fromJpeg(reader.width, reader.height, upsampling, ycbcr, channels, effort);
        var gray = count == 1;
        ColorEncoding encoding;
        if (jbrd.icc != null) {
            encoding = new ColorEncoding(true, gray ? ColorEncoding.GRAY : ColorEncoding.RGB,
                    ColorEncoding.WHITE_D65, null, ColorEncoding.PRIMARIES_SRGB, null, ColorEncoding.TF_SRGB, 0, 1);
        } else {
            encoding = gray ? new ColorEncoding(false, ColorEncoding.GRAY, ColorEncoding.WHITE_D65, null,
                    ColorEncoding.PRIMARIES_SRGB, null, ColorEncoding.TF_SRGB, 0, 1) : ColorEncoding.SRGB;
        }
        var orientation = 1;
        if (jbrd.exif != null) {
            try {
                orientation = TiffExif.orientation(jbrd.exif, 0).exifCode();
            } catch (RuntimeException ignored) {
                orientation = 1;
            }
        }
        var header = new ImageHeader(reader.width, reader.height, orientation, 0, 0, 0, 0, null, BitDepth.DEFAULT,
                true, List.of(), false, encoding, ImageHeader.ToneMapping.DEFAULT, ImageHeader.OpsinInverse.DEFAULT,
                UpsamplingWeights.UP2, UpsamplingWeights.UP4, UpsamplingWeights.UP8);
        var out = new JxlBitWriter(jpeg.length);
        HeaderWriter.writeImageHeader(out, header);
        if (jbrd.icc != null) {
            IccWriter.write(out, jbrd.icc);
        }
        out.zeroPadToByte();
        var streams = new ArrayList<>(vd.modularStreams());
        var settings = new ArrayList<>(vd.treeSettings(effort, true));
        var first = settings.removeFirst();
        settings.addFirst(new ModularEncoder.TreeSettings(0, first.kind(), first.predictors(), first.properties(),
                first.maxPropertyValues(), first.nodeThreshold(), first.sampleFraction(), first.fastDecodeMultiplier()));
        var modular = ModularEncoder.encode(streams, settings,
                com.github.auties00.smalt.image.jxl.internal.encode.EntropyEncoder.Params.forEffort(effort, true));
        vd.tokenizeHf(EntropyEncoder.Params.forEffort(effort, false));
        var frame = new HeaderWriter.Frame();
        frame.modular = false;
        frame.doYCbCr = ycbcr;
        frame.jpegUpsampling = upsampling;
        frame.flags = 0x80;
        frame.gaborish = false;
        frame.epfIterations = 0;
        var sections = new ArrayList<JxlBitWriter>();
        var lfGlobal = new JxlBitWriter(4096);
        vd.writeLfDequant(lfGlobal);
        vd.writeLfGlobalVarDct(lfGlobal);
        modular.writeGlobal(lfGlobal);
        sections.add(lfGlobal);
        for (var i = 0; i < vd.numLfGroups(); i++) {
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
            sections.add(w);
        }
        CodestreamWriter.writeFrame(out, frame, header, sections, vd.numGroups(), 1);
        var codestream = out.toByteArray();
        var level = CodestreamWriter.level(codestream, jbrd.icc == null ? 0 : jbrd.icc.length);
        return CodestreamWriter.container(codestream, jbrd.exif, jbrd.xmp, level, null, payload);
    }

    private static int mode(int h, int v) {
        if (h == 1 && v == 1) {
            return 0;
        }
        if (h == 2 && v == 2) {
            return 1;
        }
        if (h == 2) {
            return 2;
        }
        return 3;
    }

    private static boolean isYcbcr(JpegReader jpeg) {
        if (jpeg.components.size() == 1) {
            return true;
        }
        var markers = jpeg.markers;
        if (markers.contains(0xE0)) {
            return true;
        }
        var apps = 0;
        for (var marker : markers) {
            if ((marker & 0xF0) == 0xE0) {
                if (marker == 0xEE) {
                    var data = jpeg.app.get(apps);
                    if (data.length == 15 && data[3] == 'A' && data[4] == 'd' && data[5] == 'o' && data[6] == 'b'
                            && data[7] == 'e') {
                        return data[14] != 0;
                    }
                }
                apps++;
            }
        }
        var c = jpeg.components;
        return !(c.get(0).id == 'R' && c.get(1).id == 'G' && c.get(2).id == 'B');
    }
}
