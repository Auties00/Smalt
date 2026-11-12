package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.audio.aac.internal.AacBitReader;
import com.github.auties00.smalt.audio.aac.internal.OutputChannelPositions;
import com.github.auties00.smalt.audio.aac.internal.mps.MpsBinaural;
import com.github.auties00.smalt.audio.aac.internal.mps.MpsConfig;
import com.github.auties00.smalt.audio.aac.internal.mps.MpsDecoder;
import com.github.auties00.smalt.audio.aac.internal.mps.MpsUpmixer;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares Smalt's MPEG Surround decoder with the decoder of the ISO/IEC 23003-3 reference software
 * (mp4spatialdec, the full ISO/IEC 23003-1 decoder), whose decodes {@code tools/testdata/mps-oracle.py} writes into
 * {@code <conformance cache>/mps-oracle} ({@code -Dsmalt.test.mpsOracle} overrides the directory); MPEG Surround
 * conformance streams are not public. The streams are synthetic ones covering every tree, quantization and TTT
 * mode, temporal shaping, arbitrary downmix, residual coding, the arbitrary tree and 3D stereo downmixes at 24, 48 and
 * 96 kHz with frames of 4 to 64 slots, and the reference encoder's streams of a 5.1 signal. Without those files the
 * comparisons are skipped.
 *
 * <p>A subset of the streams also runs through the binaural rendering of the reference decoder, with every data set
 * and quality, the oracle script patching it as its description says.
 *
 * <p>Both decoders compute the same single precision operations in the same order, so the upmixes agree bit for bit,
 * except for the residual signals, which the reference software dequantizes in double precision: those agree to a few
 * thousandths of a 16-bit step. The block tests run the upmixer of the AAC decoder over the downmix in AAC blocks,
 * one spatial frame spanning one or several of them, with the side information of a separate elementary stream and
 * embedded in fragments, and require the upmix of the whole frames, delayed by all of a frame but one block; with
 * frames shorter than a block, each block carries several whole frames and the upmix is not delayed.
 */
class MpegSurroundOracleTest {
    // a hundredth of a 16-bit step, for the residual signals the reference software dequantizes in double precision
    private static final Tolerance TOLERANCE = Tolerance.maxAbs(0.01 / 32768);

    static Stream<String> cases() {
        var synthetic = Stream.of("t5151", "t5152", "t5151q2", "t525cpc", "t525cld", "t525dual", "t525inv",
                "t525mode4", "t7271", "t7272", "t7571", "t7572", "stp5151", "stp525", "stp7271", "ges5152", "ges525",
                "ges7572", "rate24", "rate96", "slots16", "arbdmx5151", "arbdmx525", "res5151", "res525", "res525cpc0",
                "res7272", "res7571", "res5152x2", "res96u32", "res96u30", "res96u24", "res96u18", "res96u16",
                "res96u15", "res88u32", "res88u24", "arbres96", "arbres96s", "arbres5151", "arbres525", "slots18res",
                "v_q1", "v_q2", "v_oneicc",
                "v_fr3", "v_fr2", "v_gains", "v_q1b", "v_5152", "atpass", "at5151", "at5152", "at525", "at7271",
                "at7572", "atres5151", "at13", "long64", "long30", "long45", "long24", "long96", "atlong", "short8",
                "short4", "short15", "short24k", "short96", "atshort", "3d525", "3d525cpc", "3d525m2", "3d525m3",
                "3d525m5", "3d525inv", "3d525dual", "3d7271", "3d7272", "3d5151", "3d5152", "3d7572", "3dbands", "3dsym",
                "3dflat", "3dlow", "3dlowflat", "3dhigh", "3dnoasym", "3dstp", "3dges", "3darb", "3darbres", "3dres",
                "3dat", "3dlong", "3dshort", "3d24k", "3d96k", "bfr5", "bfr6", "bfr7", "bfr5m", "bfrdual", "bfrarb", "bfrarbm",
                "3dbdual");
        var encoded = Stream.of("5151", "5152", "525").flatMap(tree -> Stream.of("16", "32")
                .map(slots -> "enc" + tree + "_" + slots));
        return Stream.concat(synthetic, encoded);
    }

    static Path directory() {
        return Path.of(System.getProperty("smalt.test.mpsOracle",
                Conformance.directory().resolve("mps-oracle").toString()));
    }

    static byte[] read(Path file) {
        Skip.unless(Files.isRegularFile(file), "data:mps-oracle", "no oracle file " + file
                + " (tools/testdata/mps-oracle.py)");
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * {@return the channel of the upmixer's output, in WAVE order, that carries each output channel of the decoder}
     */
    static int[] outputOrder(MpsConfig config) {
        if (config.arbitraryTreeActive()) {
            return OutputChannelPositions.waveOrder(OutputChannelPositions.speakers(config.arbitraryTreePositions()));
        }
        var tree = config.treeConfig();
        if (tree == MpsConfig.TREE_7272 || tree == MpsConfig.TREE_7572) {
            return new int[]{0, 1, 2, 3, 6, 7, 4, 5};
        }
        var order = new int[config.outputChannels()];
        for (var c = 0; c < order.length; c++) {
            order[c] = c;
        }
        return order;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("upmixes the downmix like the reference decoder")
    void matchesReference(String name) {
        var directory = directory();
        var reference = AacTestSupport.readWav(read(directory.resolve(name + "_ref.wav")));
        var downmix = AacTestSupport.readWav(read(directory.resolve(name + "_dmx.wav")));
        var blocks = MpegSurroundTest.blocks(read(directory.resolve(name + ".bs")));
        var config = MpegSurroundTest.config(blocks.getFirst());
        var upmix = MpegSurroundTest.upmix(blocks, downmix, config.samplingFrequency());
        assertEquals(reference.length, upmix.length, name + " channels");
        assertEquals(config.outputChannels(), upmix.length, name + " configured channels");
        for (var c = 0; c < reference.length; c++) {
            assertEquals(reference[c].length, upmix[c].length, name + " length");
            TOLERANCE.assertPcm(name + " channel " + c, reference[c], upmix[c]);
        }
    }

    static final List<String> BINAURAL_ALL = List.of("t5151", "t5152", "t5151q2", "t525cpc", "t525cld", "t525dual",
            "t525inv", "t525mode4", "t7271", "t7272", "stp5151", "stp525", "ges5152", "ges525", "rate24", "rate96",
            "slots16", "arbdmx5151", "arbdmx525", "res5151", "res5152x2", "res525", "res525cpc0", "res7272",
            "arbres5151", "arbres525", "arbres96", "arbres96s", "v_fr3", "v_fr2", "at5151", "at525", "at7271", "long64", "long24", "short8", "short96",
            "3d525", "3d525inv", "3dbands", "3d7271", "3dstp", "3darbres", "enc5151_32", "enc5152_32", "enc525_32", "bfr5",
            "bfr6", "bfr7", "bfr5m", "bfrdual", "bfrarb", "bfrarbm", "3dbdual");
    static final List<String> BINAURAL_MODELS = List.of("t5151", "t5152", "t525cpc", "t525cld", "t525inv", "t7271",
            "rate24", "rate96", "arbres525", "3d525", "enc525_32", "bfr5");

    /**
     * The binaural streams with an arbitrary downmix residual, the one input of the rendering the reference decoder
     * dequantizes in double precision; the others match bit for bit.
     */
    static final Set<String> BINAURAL_INEXACT = Set.of("arbres5151", "arbres525", "arbres96", "arbres96s", "3darbres",
            "bfrarb", "bfrarbm");

    static Stream<Arguments> binauralCases() {
        var cases = new ArrayList<Arguments>();
        for (var name : BINAURAL_ALL) {
            for (var hrtf : BINAURAL_MODELS.contains(name) ? AacBinauralHrtf.values()
                    : new AacBinauralHrtf[]{AacBinauralHrtf.KEMAR}) {
                for (var quality : AacBinauralQuality.values()) {
                    cases.add(Arguments.of(name, hrtf, quality));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("binauralCases")
    @DisplayName("renders the downmix to the ears like the reference decoder")
    void matchesReferenceBinaural(String name, AacBinauralHrtf hrtf, AacBinauralQuality quality) {
        var directory = directory();
        var reference = AacTestSupport.readWav(read(directory.resolve(
                name + "_b" + hrtf.ordinal() + quality.ordinal() + "_ref.wav")));
        var downmix = AacTestSupport.readWav(read(directory.resolve(name + "_dmx.wav")));
        var blocks = MpegSurroundTest.blocks(read(directory.resolve(name + ".bs")));
        var config = MpegSurroundTest.config(blocks.getFirst());
        var upmix = MpegSurroundTest.upmix(blocks, downmix, config.samplingFrequency(),
                new MpsBinaural(hrtf, quality));
        assertEquals(2, reference.length, name + " reference channels");
        assertEquals(2, upmix.length, name + " channels");
        for (var c = 0; c < 2; c++) {
            assertEquals(reference[c].length, upmix[c].length, name + " length");
            (BINAURAL_INEXACT.contains(name) ? TOLERANCE : Tolerance.exact())
                    .assertPcm(name + " " + hrtf + " " + quality + " channel " + c, reference[c], upmix[c]);
        }
    }

    /**
     * {@return the AAC block length (1024 or 960 samples) that a whole number of fits a spatial frame of the case, or
     * 0 when none does}
     */
    static int blockLength(MpsConfig config) {
        var rate = config.samplingFrequency();
        var frame = config.frameLength() * (rate < 27713 ? 32 : rate >= 55426 ? 128 : 64);
        return frame % 1024 == 0 ? 1024 : frame % 960 == 0 ? 960 : 0;
    }

    static Stream<String> blockCases() {
        return cases().filter(name -> {
            var file = directory().resolve(name + ".bs");
            if (!Files.isRegularFile(file)) {
                return true;
            }
            return blockLength(MpegSurroundTest.config(MpegSurroundTest.blocks(read(file)).getFirst())) != 0;
        });
    }

    /**
     * Splits the side information into the {@code sac_extension_data()} payloads of each block: every frame spread
     * over the blocks it spans, the first frame and every seventh after it starting with the header, which the first
     * fragment holds whole.
     */
    static List<List<byte[]>> fragments(List<byte[]> blocks, int blocksPerFrame) {
        var header = blocks.getFirst();
        var perBlock = new ArrayList<List<byte[]>>();
        for (var f = 1; f < blocks.size(); f++) {
            var withHeader = (f - 1) % 7 == 0;
            var parts = MpegSurroundTest.payloadFragments(withHeader ? header : null, blocks.get(f), blocksPerFrame);
            for (var b = 0; b < blocksPerFrame; b++) {
                var bits = new MpegSurroundTest.Bits().put(MpsUpmixer.EXT_SAC_DATA, 4).put(withHeader ? 1 : 0, 2)
                        .put(b == 0 ? 1 : 0, 1).put(b == blocksPerFrame - 1 ? 1 : 0, 1);
                for (var value : parts.get(b)) {
                    bits.put(value & 0xFF, 8);
                }
                perBlock.add(List.of(bits.bytes()));
            }
        }
        return perBlock;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("blockCases")
    @DisplayName("upmixes AAC blocks of the downmix like the reference decoder, frames spanning several blocks")
    void matchesReferenceInBlocks(String name) {
        var directory = directory();
        var reference = AacTestSupport.readWav(read(directory.resolve(name + "_ref.wav")));
        var downmix = AacTestSupport.readWav(read(directory.resolve(name + "_dmx.wav")));
        var blocks = MpegSurroundTest.blocks(read(directory.resolve(name + ".bs")));
        var config = MpegSurroundTest.config(blocks.getFirst());
        var rate = config.samplingFrequency();
        var exact = MpegSurroundTest.upmix(blocks, downmix, rate);
        var length = blockLength(config);
        var blocksPerFrame = new MpsDecoder(config, rate).frameLength() / length;
        var frames = exact[0].length / (length * blocksPerFrame);
        var order = outputOrder(config);
        for (var embedded : new boolean[]{false, true}) {
            var label = name + (embedded ? " embedded" : " separate");
            var upmixer = new MpsUpmixer();
            List<List<byte[]>> fragments = null;
            if (embedded) {
                fragments = fragments(blocks, blocksPerFrame);
            } else {
                upmixer.configure(config);
            }
            var count = frames * blocksPerFrame;
            var delay = blocksPerFrame - 1;
            var block = new float[downmix.length][length];
            var out = new float[exact.length][length];
            var collected = new float[exact.length][count * length];
            for (var k = 0; k < count; k++) {
                if (embedded) {
                    for (var fragment : fragments.get(k)) {
                        var reader = new AacBitReader(fragment);
                        reader.read(4);
                        upmixer.readExtension(reader, fragment.length);
                    }
                } else if (k % blocksPerFrame == 0) {
                    var frame = blocks.get(k / blocksPerFrame + 1);
                    upmixer.addFrame(frame, 0, frame.length);
                }
                for (var c = 0; c < downmix.length; c++) {
                    System.arraycopy(downmix[c], k * length, block[c], 0, length);
                }
                var handled = upmixer.prepare(rate, length);
                assertEquals(blocksPerFrame > 1 || k >= delay, handled, label + " block " + k + " handled");
                if (!handled) {
                    continue;
                }
                assertEquals(k >= delay, upmixer.upmixed(), label + " block " + k + " upmixed");
                upmixer.upmix(block, out);
                if (!upmixer.upmixed()) {
                    for (var c = 0; c < downmix.length; c++) {
                        assertArrayEquals(new float[length], block[c], 0.0f, label + " delayed downmix " + k);
                    }
                    continue;
                }
                assertEquals(config.outputChannels(), upmixer.layout().channels(), label + " layout");
                for (var c = 0; c < exact.length; c++) {
                    System.arraycopy(out[order[c]], 0, collected[c], (k - delay) * length, length);
                }
            }
            var produced = (count - delay) * length;
            assertTrue(produced > 0, label);
            for (var c = 0; c < exact.length; c++) {
                Tolerance.exact().assertPcm(label + " channel " + c + " against whole frames",
                        Arrays.copyOf(exact[c], produced), Arrays.copyOf(collected[c], produced));
                TOLERANCE.assertPcm(label + " channel " + c, Arrays.copyOf(reference[c], produced),
                        Arrays.copyOf(collected[c], produced));
            }
        }
    }

    /**
     * {@return the block lengths (960, 1024, 1920 or 2048 samples) that hold a whole number of the case's spatial
     * frames, more than one}
     */
    static int[] fractionalBlockLengths(MpsConfig config) {
        var rate = config.samplingFrequency();
        var frame = config.frameLength() * (rate < 27713 ? 32 : rate >= 55426 ? 128 : 64);
        return Arrays.stream(new int[]{960, 1024, 1920, 2048})
                .filter(block -> block > frame && block % frame == 0)
                .toArray();
    }

    static Stream<Arguments> fractionalCases() {
        return cases().flatMap(name -> {
            var file = directory().resolve(name + ".bs");
            if (!Files.isRegularFile(file)) {
                return Stream.of(Arguments.of(name, 2048));
            }
            var config = MpegSurroundTest.config(MpegSurroundTest.blocks(read(file)).getFirst());
            return Arrays.stream(fractionalBlockLengths(config)).mapToObj(block -> Arguments.of(name, block));
        });
    }

    @ParameterizedTest(name = "{0} in blocks of {1}")
    @MethodSource("fractionalCases")
    @DisplayName("upmixes AAC blocks of the downmix like the reference decoder, each block holding several frames")
    void matchesReferenceInFractionalBlocks(String name, int length) {
        var directory = directory();
        var reference = AacTestSupport.readWav(read(directory.resolve(name + "_ref.wav")));
        var downmix = AacTestSupport.readWav(read(directory.resolve(name + "_dmx.wav")));
        var blocks = MpegSurroundTest.blocks(read(directory.resolve(name + ".bs")));
        var config = MpegSurroundTest.config(blocks.getFirst());
        var rate = config.samplingFrequency();
        var exact = MpegSurroundTest.upmix(blocks, downmix, rate);
        var framesPerBlock = length / new MpsDecoder(config, rate).frameLength();
        assertTrue(framesPerBlock > 1, name);
        var count = exact[0].length / length;
        assertTrue(count > 0, name);
        var order = outputOrder(config);
        var header = blocks.getFirst();
        for (var embedded : new boolean[]{false, true}) {
            var label = name + (embedded ? " embedded" : " separate") + " in blocks of " + length;
            var upmixer = new MpsUpmixer();
            if (!embedded) {
                upmixer.configure(config);
            }
            var block = new float[downmix.length][length];
            var out = new float[exact.length][length];
            var collected = new float[exact.length][count * length];
            for (var k = 0; k < count; k++) {
                for (var f = 0; f < framesPerBlock; f++) {
                    var index = k * framesPerBlock + f;
                    var frame = blocks.get(index + 1);
                    if (!embedded) {
                        upmixer.addFrame(frame, 0, frame.length);
                        continue;
                    }
                    var withHeader = index % 7 == 0;
                    var part = MpegSurroundTest.payloadFragments(withHeader ? header : null, frame, 1).getFirst();
                    var bits = new MpegSurroundTest.Bits().put(MpsUpmixer.EXT_SAC_DATA, 4).put(withHeader ? 1 : 0, 2)
                            .put(1, 1).put(1, 1);
                    for (var value : part) {
                        bits.put(value & 0xFF, 8);
                    }
                    var fragment = bits.bytes();
                    var reader = new AacBitReader(fragment);
                    reader.read(4);
                    upmixer.readExtension(reader, fragment.length);
                }
                for (var c = 0; c < downmix.length; c++) {
                    System.arraycopy(downmix[c], k * length, block[c], 0, length);
                }
                assertTrue(upmixer.prepare(rate, length), label + " block " + k + " handled");
                assertTrue(upmixer.upmixed(), label + " block " + k + " upmixed");
                upmixer.upmix(block, out);
                assertEquals(config.outputChannels(), upmixer.layout().channels(), label + " layout");
                for (var c = 0; c < exact.length; c++) {
                    System.arraycopy(out[order[c]], 0, collected[c], k * length, length);
                }
            }
            var produced = count * length;
            for (var c = 0; c < exact.length; c++) {
                Tolerance.exact().assertPcm(label + " channel " + c + " against whole frames",
                        Arrays.copyOf(exact[c], produced), collected[c]);
                TOLERANCE.assertPcm(label + " channel " + c, Arrays.copyOf(reference[c], produced), collected[c]);
            }
        }
    }
}
