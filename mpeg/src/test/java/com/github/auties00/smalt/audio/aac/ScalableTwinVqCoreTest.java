package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.aac.internal.ep.EpStreams;
import com.github.auties00.smalt.audio.twinvq.TwinVqDecoder;
import com.github.auties00.smalt.audio.twinvq.TwinVqDecoderOptions;
import com.github.auties00.smalt.audio.twinvq.internal.TwinVqObject;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes scalable AAC over TwinVQ cores of configurations no ISO/IEC 14496-26 stream has: a stereo TwinVQ core (the
 * TVQ-AAC-SS configuration of ISO/IEC 14496-3 subclause 4.5.2.2.6.3), a mono TwinVQ core with TNS (Table 4.90 and
 * Figures 4.9 and 4.15) and a TwinVQ core whose EP frames concatenate frames. The cores are TwinVQ conformance streams
 * ({@code er_tv01}, {@code tv26} and {@code tv22} stereo without TNS, {@code tv03}, {@code tv23} and {@code er_tv02}
 * mono with TNS in some frames, {@code er_tv01} again in EP frames that concatenate two or three of its frames); the
 * AAC layers above them are written here, coded as
 * zero spectra with every {@code diff_control} flag chosen by the test and, where Table 4.90 transmits it, a TNS filter
 * chosen by the test, so the output is the core alone, a channel of it switched off, or the mid channel the M/S matrix
 * spreads to both outputs, which the TwinVQ decoder's own output gives independently of the switch.
 */
@DisplayName("Scalable AAC over stereo, TNS and concatenated TwinVQ cores")
class ScalableTwinVqCoreTest {
    /**
     * The largest difference allowed between two decodes of one spectrum through different code paths, of full scale
     * 1: well under one 16-bit LSB.
     */
    private static final float TOLERANCE = 1.0e-5f;

    private static final int EIGHT_SHORT_SEQUENCE = 2;

    /**
     * The track id of the first AAC layer; the others follow it.
     */
    private static final int LAYER_ID = 100;

    /**
     * How the AAC layers above the core code a frame.
     *
     * @param channels the channels of the AAC layers
     * @param layers the number of AAC layers
     * @param midSide whether the first layer codes every band of the core M/S
     * @param leftOn whether the first channel takes the core ({@code diff_control} clear)
     * @param rightOn whether the second channel of a stereo core takes the core
     * @param errorResilient whether the layers are ER AAC scalable (object type 20) instead of AAC scalable (6)
     * @param layerTns a bit per channel (bit 0 left or mono, bit 1 right) whose first-layer stream codes the TNS
     * filter of {@link #writeTns}, where Table 4.90 transmits {@code tns_data_present}; AAC scalable layers only
     */
    record Layout(int channels, int layers, boolean midSide, boolean leftOn, boolean rightOn,
                  boolean errorResilient, int layerTns) {
        Layout {
            if (layerTns != 0 && errorResilient) {
                throw new IllegalArgumentException("layer TNS is written for AAC scalable layers only");
            }
        }

        Layout(int channels, int layers, boolean midSide, boolean leftOn, boolean rightOn, boolean errorResilient) {
            this(channels, layers, midSide, leftOn, rightOn, errorResilient, 0);
        }
    }

    /**
     * The tracks and the packets of each frame of a core with written AAC layers.
     *
     * @param tracks the core tracks, then the AAC layer tracks
     * @param frames the packets of each frame, the core ones first
     * @param core the core tracks alone
     * @param coreFrames the core packets of each frame
     */
    record Stream(List<AudioTrack> tracks, List<List<Packet>> frames, List<AudioTrack> core,
                  List<List<Packet>> coreFrames) {
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"er_tv01_ep0", "tv26", "tv22"})
    @DisplayName("a stereo core under stereo layers that take it everywhere decodes as the core alone")
    void stereoCore(String name) {
        for (var layers = 1; layers <= 2; layers++) {
            var stream = build(name, new Layout(2, layers, false, true, true, name.startsWith("er_")));
            var expected = decodeCore(stream);
            var decoded = decode(stream, -1);
            assertClose(expected[0], decoded[0], name + " left, " + layers + " layers");
            assertClose(expected[1], decoded[1], name + " right, " + layers + " layers");
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"er_tv01_ep0", "tv26"})
    @DisplayName("a stereo core switches each channel by its own diff_control flags")
    void stereoCoreChannelSwitch(String name) {
        var resilient = name.startsWith("er_");
        var stream = build(name, new Layout(2, 1, false, true, false, resilient));
        var expected = decodeCore(stream);
        var decoded = decode(stream, -1);
        assertClose(expected[0], decoded[0], name + " left");
        assertSilent(decoded[1], name + " right");
        var swapped = decode(build(name, new Layout(2, 1, false, false, true, resilient)), -1);
        assertSilent(swapped[0], name + " left");
        assertClose(expected[1], swapped[1], name + " right");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"er_tv01_ep0", "tv26"})
    @DisplayName("a stereo core enters bands coded M/S as mid and side")
    void stereoCoreMidSide(String name) {
        var resilient = name.startsWith("er_");
        var core = build(name, new Layout(2, 1, false, true, true, resilient));
        assertNoCoreTns(core);
        var expected = decodeCore(core);
        // the mid channel alone, which the inverse matrix spreads to both outputs
        var mid = decode(build(name, new Layout(2, 1, true, true, false, resilient)), -1);
        // both: the matrix gives the core's left and right channels back
        var both = decode(build(name, new Layout(2, 1, true, true, true, resilient)), -1);
        var half = new float[expected[0].length];
        for (var i = 0; i < half.length; i++) {
            half[i] = 0.5f * (expected[0][i] + expected[1][i]);
        }
        assertClose(half, mid[0], name + " left of the mid channel");
        assertClose(half, mid[1], name + " right of the mid channel");
        assertClose(expected[0], both[0], name + " left");
        assertClose(expected[1], both[1], name + " right");
    }

    @Test
    @DisplayName("outputs the stereo core alone at a core layer, the AAC layers at theirs")
    void stereoCoreLayers() {
        var stream = build("tv26", new Layout(2, 2, false, true, true, false));
        var expected = decodeCore(stream);
        try (var core = new AacDecoder(stream.tracks(), AacDecoderOptions.builder().maxLayer(1).build());
             var first = new AacDecoder(stream.tracks(), AacDecoderOptions.builder().maxLayer(2).build())) {
            assertEquals(2, core.layout().channels());
            assertEquals(2, first.layout().channels());
        }
        var coreLayer = decode(stream, 1);
        assertArrayEquals(expected[0], Arrays.copyOf(coreLayer[0], expected[0].length));
        assertArrayEquals(expected[1], Arrays.copyOf(coreLayer[1], expected[1].length));
        var first = decode(stream, 2);
        assertClose(expected[0], first[0], "first AAC layer, left");
        assertClose(expected[1], first[1], "first AAC layer, right");
    }

    @Test
    @DisplayName("rejects a stereo core under a mono layer")
    void stereoCoreUnderMono() {
        var stream = build("tv26", new Layout(2, 1, false, true, true, false));
        var tracks = new ArrayList<>(stream.tracks());
        var layer = tracks.getLast();
        tracks.set(tracks.size() - 1, layer.toBuilder().codecConfig(config(6, 1, 0, false, layer.sampleRate())).build());
        assertThrows(MediaException.Malformed.class, () -> new AacDecoder(tracks));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"tv03", "tv23", "er_tv02_ep0"})
    @DisplayName("a mono core with TNS takes its own filters under a mono layer, which codes no TNS for it")
    void monoCoreTnsUnderMono(String name) {
        var stream = build(name, new Layout(1, 2, false, true, false, name.startsWith("er_")));
        assertCoreTns(stream);
        var expected = decodeCore(stream);
        var decoded = decode(stream, -1);
        assertClose(expected[0], decoded[0], name);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"tv03", "tv23", "er_tv02_ep0"})
    @DisplayName("a mono core with TNS under a stereo layer, which codes no tns_channel_mono_layer, filters both outputs")
    void monoCoreTnsUnderStereo(String name) {
        var stream = build(name, new Layout(2, 1, true, true, false, name.startsWith("er_")));
        assertCoreTns(stream);
        var expected = decodeCore(stream);
        var decoded = decode(stream, -1);
        assertClose(expected[0], decoded[0], name + " left");
        assertClose(expected[0], decoded[1], name + " right");
    }

    @ParameterizedTest(name = "channels {0}")
    @ValueSource(ints = {0b01, 0b10, 0b11})
    @DisplayName("a stereo core without TNS runs through the TNS analysis filters of each channel of the first layer")
    void stereoCoreLayerTns(int channels) {
        // every band M/S, so the filters reach the bands the core is switched into; the analysis filter of a channel
        // must undo its synthesis filter on the core part, or that channel would come out filtered
        var stream = build("tv26", new Layout(2, 1, true, true, true, false, channels));
        assertNoCoreTns(stream);
        var expected = decodeCore(stream);
        var decoded = decode(stream, -1);
        assertClose(expected[0], decoded[0], "left");
        assertClose(expected[1], decoded[1], "right");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"tv03", "tv23"})
    @DisplayName("a mono core runs through the first layer's TNS analysis filter only in frames where it has no TNS")
    void monoCoreTnsLayerTns(String name) {
        // both channels code one filter: in frames without core TNS the analysis filter of the left channel
        // (tns_channel_mono_layer 0) takes the mono core into the domain both channels' synthesis filters undo; in
        // frames with core TNS (Figure 4.15) the core enters unfiltered, so the layer's filters and then the core's own
        // filter shape it and the output differs from the core alone
        var stream = build(name, new Layout(2, 1, true, true, false, false, 0b11));
        var tns = coreTnsFrames(stream);
        var expected = decodeCore(stream);
        var decoded = decode(stream, -1);
        var frameLength = probe(stream).frameLength();
        var clean = 0;
        var differing = 0;
        for (var block = 0; block < tns.length; block++) {
            var start = block * frameLength;
            if (start + frameLength > Math.min(expected[0].length, decoded[0].length)) {
                break;
            }
            var anyTns = tns[block] || (block > 0 && tns[block - 1]) || (block + 1 < tns.length && tns[block + 1]);
            var difference = 0.0f;
            for (var channel = 0; channel < 2; channel++) {
                for (var i = start; i < start + frameLength; i++) {
                    assertTrue(Float.isFinite(decoded[channel][i]), "sample " + i);
                    difference = Math.max(difference, Math.abs(expected[0][i] - decoded[channel][i]));
                }
            }
            if (!anyTns) {
                assertTrue(difference <= TOLERANCE, name + " block " + block + " differs by " + difference);
                clean++;
            } else if (tns[block] && difference > 1.0e-3f) {
                differing++;
            }
        }
        assertTrue(clean > 0, name + " has blocks away from core TNS frames");
        assertTrue(differing > 0, name + " has core TNS frames that the layer's filters change");
    }

    @ParameterizedTest(name = "{0} frames per EP frame")
    @ValueSource(ints = {2, 3})
    @DisplayName("a core whose EP frames concatenate frames decodes as the unprotected core")
    void concatenatedCore(int concatenated) {
        // er_tv01's frames all have 1024 bits, which the fixed classes of the EP frames hold without padding: the
        // number of bits of a TwinVQ frame sets its bit allocation
        var stream = build("er_tv01_ep0", new Layout(2, 2, false, true, true, true));
        var frames = stream.frames().size() / concatenated * concatenated;
        var expected = decodeLayers(stream.tracks(), frames, stream.frames()::get);
        var coreTrack = stream.core().getFirst();
        var coreUnits = new ArrayList<byte[]>();
        for (var frame = 0; frame < frames; frame++) {
            coreUnits.add(stream.coreFrames().get(frame).getFirst().data());
        }
        var built = EpStreams.unmapped(coreTrack.codecConfig().orElseThrow(), coreUnits, concatenated,
                new Random(concatenated), 0);
        var tracks = new ArrayList<>(stream.tracks());
        tracks.set(0, coreTrack.toBuilder().codecConfig(built.config()).build());
        var decoded = decodeLayers(tracks, frames, frame -> {
            var packets = new ArrayList<Packet>();
            if (frame % concatenated == 0) {
                packets.add(new Packet(coreTrack.id(), built.units().get(frame / concatenated), frame, frame,
                        concatenated, true));
            }
            for (var packet : stream.frames().get(frame)) {
                if (packet.trackId() != coreTrack.id()) {
                    packets.add(packet);
                }
            }
            return packets;
        });
        assertEquals(expected.length, decoded.length);
        for (var c = 0; c < expected.length; c++) {
            assertArrayEquals(expected[c], decoded[c], "channel " + c);
        }
        // a lost EP frame silences the frames it held, and the next one decodes
        var lost = decodeLayers(tracks, frames, frame -> {
            var packets = new ArrayList<Packet>();
            if (frame % concatenated == 0 && frame / concatenated != 3) {
                packets.add(new Packet(coreTrack.id(), built.units().get(frame / concatenated), frame, frame,
                        concatenated, true));
            }
            for (var packet : stream.frames().get(frame)) {
                if (packet.trackId() != coreTrack.id()) {
                    packets.add(packet);
                }
            }
            return packets;
        });
        var from = 3 * concatenated * 1024;
        var to = 4 * concatenated * 1024;
        for (var c = 0; c < expected.length; c++) {
            for (var i = from + 1024; i < to; i++) {
                assertEquals(0.0f, Math.abs(lost[c][i]), "channel " + c + " sample " + i + " of the lost EP frame");
            }
            var energy = 0.0;
            for (var i = to + 1024; i < to + 1024 * concatenated * 4; i++) {
                assertTrue(Float.isFinite(lost[c][i]));
                energy += (double) lost[c][i] * lost[c][i];
            }
            assertTrue(energy > 0, "channel " + c + " decodes after the lost EP frame");
        }
    }

    /**
     * Writes AAC layers over the core of a TwinVQ conformance stream.
     *
     * @param name the stream name
     * @param layout how the layers code each frame
     * @return the stream
     */
    private static Stream build(String name, Layout layout) {
        var source = ScalableCoreConformanceTest.layers(name);
        var coreTracks = source.tracks();
        var configs = new ArrayList<byte[]>();
        for (var track : coreTracks) {
            assertEquals(Codec.TWINVQ, track.codec(), name + " is TwinVQ alone");
            configs.add(track.codecConfig().orElseThrow());
        }
        var probe = new TwinVqObject(configs, -1, MediaLimits.defaults());
        var rate = probe.sampleRate();
        var aot = layout.errorResilient() ? 20 : 6;
        var tracks = new ArrayList<>(coreTracks);
        for (var layer = 0; layer < layout.layers(); layer++) {
            tracks.add(coreTracks.getFirst().toBuilder().id(LAYER_ID + layer).codec(Codec.AAC)
                    .codecConfig(config(aot, layout.channels(), layer, layout.errorResilient(), rate)).build());
        }
        var frames = new ArrayList<List<Packet>>();
        var coreFrames = new ArrayList<List<Packet>>();
        var count = source.units().getFirst().size();
        var units = new byte[configs.size()][];
        var sizes = new int[configs.size()];
        for (var frame = 0; frame < count; frame++) {
            var core = new ArrayList<Packet>();
            for (var stream = 0; stream < coreTracks.size(); stream++) {
                var packet = source.units().get(stream).get(frame);
                core.add(packet);
                units[stream] = packet.data();
                sizes[stream] = packet.data().length;
            }
            probe.decodeCore(units, sizes, units.length);
            var packets = new ArrayList<>(core);
            var first = core.getFirst();
            for (var layer = 0; layer < layout.layers(); layer++) {
                var data = layer == 0 ? mainLayer(probe, layout) : extensionLayer(probe, layout);
                packets.add(new Packet(LAYER_ID + layer, data, first.pts(), first.dts(), first.duration(), true));
            }
            frames.add(packets);
            coreFrames.add(core);
        }
        return new Stream(tracks, frames, coreTracks, coreFrames);
    }

    /**
     * Writes the {@code AudioSpecificConfig} of a scalable AAC layer that does not depend on a core coder delay.
     */
    private static byte[] config(int objectType, int channels, int layer, boolean errorResilient, int rate) {
        var index = switch (rate) {
            case 48000 -> 3;
            case 44100 -> 4;
            case 32000 -> 5;
            case 24000 -> 6;
            case 22050 -> 7;
            case 16000 -> 8;
            case 12000 -> 9;
            case 11025 -> 10;
            case 8000 -> 11;
            default -> throw new IllegalArgumentException("rate " + rate);
        };
        var bits = new Bits();
        bits.write(objectType, 5);
        bits.write(index, 4);
        bits.write(channels, 4);
        bits.write(0, 1);
        bits.write(0, 1);
        bits.write(errorResilient ? 1 : 0, 1);
        bits.write(layer, 3);
        if (errorResilient) {
            bits.write(0, 3);
            bits.write(0, 1);
            bits.write(0, 2);
        }
        return bits.toByteArray();
    }

    /**
     * Writes the {@code aac_scalable_main_element} of the first layer over the core frame the probe last decoded, as
     * ISO/IEC 14496-3 Table 4.15 lays it out over a TwinVQ core: zero spectra, every core band M/S or none, and one
     * {@code diff_control} code per dc group or flag per short window.
     */
    private static byte[] mainLayer(TwinVqObject core, Layout layout) {
        var bits = new Bits();
        var shortWindows = core.windowSequence() == EIGHT_SHORT_SEQUENCE;
        var bands = core.maxBand();
        var maxSfb = layout.midSide() ? bands : 0;
        var stereo = layout.channels() == 2;
        var stereoCore = core.channels() == 2;
        var monoTns = !stereoCore && core.tnsPresent(0);
        bits.write(0, 1);
        if (shortWindows) {
            bits.write(maxSfb, 4);
            bits.write(0, 7);
        } else {
            bits.write(maxSfb, 6);
        }
        if (stereo) {
            bits.write(layout.midSide() ? 2 : 0, 2);
            if (!stereoCore && !monoTns) {
                bits.write(0, 1);
            }
        }
        var sides = new Bits[layout.channels()];
        for (var channel = 0; channel < layout.channels(); channel++) {
            var side = layout.errorResilient() ? bits : (sides[channel] = new Bits());
            // tns_aac_tvq_en of Table 4.90
            var tnsSent = stereoCore ? !core.tnsPresent(channel) : !monoTns || stereo;
            if (tnsSent) {
                var tns = (layout.layerTns() >>> channel & 1) != 0;
                side.write(tns ? 1 : 0, 1);
                if (tns) {
                    writeTns(side, shortWindows);
                }
            }
            if (channel == 0 || stereoCore) {
                var on = channel == 0 ? layout.leftOn() : layout.rightOn();
                if (shortWindows) {
                    side.write(on ? 0 : 0xFF, 8);
                } else {
                    for (var group = 0; group < (bands + 3) / 4; group++) {
                        side.write(on ? 0 : 1, 2);
                    }
                }
            }
            if (stereo && !stereoCore) {
                // diff_control_lr: per short window, or per band coded left and right below the core's bandwidth
                if (shortWindows) {
                    side.write(channel == 0 ? 0 : 0xFF, 8);
                } else if (!layout.midSide()) {
                    for (var band = 0; band < Math.min(bands, maxSfb); band++) {
                        side.write(channel == 0 ? 0 : 1, 1);
                    }
                }
            }
            if (layout.errorResilient()) {
                zeroStream(bits, maxSfb, shortWindows);
            }
        }
        if (!layout.errorResilient()) {
            for (var side : sides) {
                bits.append(side);
            }
            for (var channel = 0; channel < layout.channels(); channel++) {
                zeroStream(bits, maxSfb, shortWindows);
            }
        }
        return bits.toByteArray();
    }

    /**
     * Writes a {@code tns_data} of one order-4 filter per window over the top 40 long or 12 short bands, strong enough
     * that leaving its analysis filter out of the core changes the output by far more than {@link #TOLERANCE}.
     */
    private static void writeTns(Bits bits, boolean shortWindows) {
        int[] coefficients = {5, -3, 2, 1};
        for (var window = 0; window < (shortWindows ? 8 : 1); window++) {
            bits.write(1, shortWindows ? 1 : 2);
            bits.write(1, 1);
            bits.write(shortWindows ? 12 : 40, shortWindows ? 4 : 6);
            bits.write(coefficients.length, shortWindows ? 3 : 5);
            bits.write(0, 1);
            bits.write(0, 1);
            for (var coefficient : coefficients) {
                bits.write(coefficient & 0xF, 4);
            }
        }
    }

    /**
     * Writes an {@code aac_scalable_extension_element} of zero spectra and no bands above a layer of the same channels.
     */
    private static byte[] extensionLayer(TwinVqObject core, Layout layout) {
        var bits = new Bits();
        var shortWindows = core.windowSequence() == EIGHT_SHORT_SEQUENCE;
        var maxSfb = layout.midSide() ? core.maxBand() : 0;
        bits.write(maxSfb, shortWindows ? 4 : 6);
        if (layout.channels() == 2) {
            bits.write(0, 2);
        }
        for (var channel = 0; channel < layout.channels(); channel++) {
            zeroStream(bits, maxSfb, shortWindows);
        }
        return bits.toByteArray();
    }

    /**
     * Writes an {@code individual_channel_stream} of a scalable layer whose bands all take the zero codebook: the
     * global gain and one section per window group.
     */
    private static void zeroStream(Bits bits, int maxSfb, boolean shortWindows) {
        bits.write(100, 8);
        var lengthBits = shortWindows ? 3 : 5;
        var escape = (1 << lengthBits) - 1;
        for (var group = 0; group < (shortWindows ? 8 : 1); group++) {
            if (maxSfb == 0) {
                continue;
            }
            bits.write(0, 4);
            var length = maxSfb;
            while (length >= escape) {
                bits.write(escape, lengthBits);
                length -= escape;
            }
            bits.write(length, lengthBits);
        }
    }

    /**
     * Decodes the core tracks alone with the TwinVQ decoder.
     */
    private static float[][] decodeCore(Stream stream) {
        var collected = new ArrayList<float[][]>();
        try (var decoder = new TwinVqDecoder(stream.core(), TwinVqDecoderOptions.defaults())) {
            for (var packets : stream.coreFrames()) {
                decoder.decode(packets, buffer -> collected.add(AacTestSupport.copy(buffer)));
            }
        }
        return join(collected);
    }

    /**
     * Decodes a stream with the AAC decoder at a layer, -1 for the top one.
     */
    private static float[][] decode(Stream stream, int layer) {
        var options = layer < 0 ? AacDecoderOptions.defaults() : AacDecoderOptions.builder().maxLayer(layer).build();
        var collected = new ArrayList<float[][]>();
        try (var decoder = new AacDecoder(stream.tracks(), options)) {
            for (var packets : stream.frames()) {
                decoder.decode(packets, buffer -> collected.add(AacTestSupport.copy(buffer)));
            }
        }
        return join(collected);
    }

    /**
     * Decodes the frames of a scalable stream with the AAC decoder at its top layer.
     */
    private static float[][] decodeLayers(List<AudioTrack> tracks, int frames,
                                          java.util.function.IntFunction<List<Packet>> frame) {
        var collected = new ArrayList<float[][]>();
        try (var decoder = new AacDecoder(tracks)) {
            for (var f = 0; f < frames; f++) {
                decoder.decode(frame.apply(f), buffer -> collected.add(AacTestSupport.copy(buffer)));
            }
        }
        return join(collected);
    }

    private static float[][] join(List<float[][]> blocks) {
        var channels = blocks.getFirst().length;
        var length = 0;
        for (var block : blocks) {
            length += block[0].length;
        }
        var out = new float[channels][length];
        var position = 0;
        for (var block : blocks) {
            for (var c = 0; c < channels; c++) {
                System.arraycopy(block[c], 0, out[c], position, block[c].length);
            }
            position += block[0].length;
        }
        return out;
    }

    private static void assertClose(float[] expected, float[] actual, String message) {
        var length = Math.min(expected.length, actual.length);
        assertTrue(length >= expected.length - 2048, message + ": " + actual.length + " samples for " + expected.length);
        var energy = 0.0;
        for (var i = 0; i < length; i++) {
            var difference = Math.abs(expected[i] - actual[i]);
            assertTrue(difference <= TOLERANCE, message + ": sample " + i + " differs by " + difference);
            energy += (double) expected[i] * expected[i];
        }
        assertTrue(energy > 1.0, message + ": the expected output is not silent");
    }

    private static void assertSilent(float[] actual, String message) {
        for (var i = 0; i < actual.length; i++) {
            assertEquals(0.0f, Math.abs(actual[i]), message + ": sample " + i);
        }
    }

    private static void assertCoreTns(Stream stream) {
        assertTrue(tnsFrames(stream) > 0, "the core has frames with TNS");
    }

    private static void assertNoCoreTns(Stream stream) {
        assertEquals(0, tnsFrames(stream), "the core has no frame with TNS");
    }

    private static int tnsFrames(Stream stream) {
        var count = 0;
        for (var tns : coreTnsFrames(stream)) {
            if (tns) {
                count++;
            }
        }
        return count;
    }

    /**
     * {@return whether the base layer of each core frame carries TNS data in any channel}
     */
    private static boolean[] coreTnsFrames(Stream stream) {
        var probe = probe(stream);
        var flags = new boolean[stream.coreFrames().size()];
        for (var frame = 0; frame < flags.length; frame++) {
            var packets = stream.coreFrames().get(frame);
            var units = new byte[packets.size()][];
            var sizes = new int[packets.size()];
            for (var i = 0; i < units.length; i++) {
                units[i] = packets.get(i).data();
                sizes[i] = units[i].length;
            }
            probe.decodeCore(units, sizes, units.length);
            for (var channel = 0; channel < probe.channels(); channel++) {
                flags[frame] |= probe.tnsPresent(channel);
            }
        }
        return flags;
    }

    private static TwinVqObject probe(Stream stream) {
        var configs = new ArrayList<byte[]>();
        for (var track : stream.core()) {
            configs.add(track.codecConfig().orElseThrow());
        }
        return new TwinVqObject(configs, -1, MediaLimits.defaults());
    }

    /**
     * A big-endian bit writer.
     */
    private static final class Bits {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        private final List<Boolean> pending = new ArrayList<>();

        void write(int value, int count) {
            for (var i = count - 1; i >= 0; i--) {
                pending.add((value >>> i & 1) != 0);
            }
        }

        void append(Bits other) {
            pending.addAll(other.pending);
        }

        byte[] toByteArray() {
            bytes.reset();
            for (var i = 0; i < pending.size(); i += 8) {
                var value = 0;
                for (var j = 0; j < 8; j++) {
                    value = value << 1 | (i + j < pending.size() && pending.get(i + j) ? 1 : 0);
                }
                bytes.write(value);
            }
            if (pending.isEmpty()) {
                bytes.write(0);
            }
            return bytes.toByteArray();
        }
    }
}
