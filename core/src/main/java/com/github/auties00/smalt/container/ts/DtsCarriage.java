package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.internal.codec.DtsConfigs;

import java.util.ArrayList;
import java.util.List;

/**
 * Describes how a DTS or DTS-HD elementary stream is declared in a transport stream, from its first access unit: the
 * Blu-ray stream type ({@code 0x82} core, {@code 0x85} DTS-HD without lossless coding, {@code 0x86} DTS-HD Master
 * Audio, {@code 0xA2} DTS Express) and the DVB DTS or DTS-HD descriptor (ETSI EN 300 468 Annex G) of a plain transport
 * stream.
 *
 * <p>Instances are immutable.
 */
final class DtsCarriage {
    /**
     * The core of the core substream ({@code nuCoreExtensionMask}, ETSI TS 102 114 Table 7-15).
     */
    private static final int CORE = 0x001;

    /**
     * The XXCH extension of the core substream.
     */
    private static final int CORE_XXCH = 0x002;

    /**
     * The X96 extension of the core substream.
     */
    private static final int CORE_X96 = 0x004;

    /**
     * The XCh extension of the core substream.
     */
    private static final int CORE_XCH = 0x008;

    /**
     * The core of an extension substream.
     */
    private static final int EXSS_CORE = 0x010;

    /**
     * The XBR extension.
     */
    private static final int XBR = 0x020;

    /**
     * The XXCH extension of an extension substream.
     */
    private static final int EXSS_XXCH = 0x040;

    /**
     * The X96 extension of an extension substream.
     */
    private static final int EXSS_X96 = 0x080;

    /**
     * The LBR coding.
     */
    private static final int LBR = 0x100;

    /**
     * The XLL coding.
     */
    private static final int XLL = 0x200;

    /**
     * The coding components of each {@code asset_construction} code from 1 (ETSI EN 300 468 Table G.10).
     */
    private static final int[] CONSTRUCTIONS = {
            CORE, CORE | CORE_XCH, CORE | CORE_XXCH, CORE | CORE_X96, CORE | EXSS_XXCH, CORE | XBR,
            CORE | CORE_XCH | XBR, CORE | CORE_XXCH | XBR, CORE | EXSS_XXCH | XBR, CORE | EXSS_X96,
            CORE | CORE_XCH | EXSS_X96, CORE | CORE_XXCH | EXSS_X96, CORE | EXSS_XXCH | EXSS_X96, CORE | XLL,
            CORE | CORE_XCH | XLL, CORE | CORE_X96 | XLL, XLL, LBR, EXSS_CORE, EXSS_CORE | EXSS_XXCH, EXSS_CORE | XLL
    };

    /**
     * The asset sample rates by {@code nuMaxSampleRate} (ETSI TS 102 114 Table 7-9), whose codes the DTS-HD
     * descriptor's {@code sampling_frequency} reuses.
     */
    private static final int[] ASSET_RATES = {8000, 16000, 32000, 64000, 128000, 22050, 44100, 88200, 176400, 352800,
            12000, 24000, 48000, 96000, 192000, 384000};

    /**
     * The extension substream reference clock rates by {@code nuRefClockCode}.
     */
    private static final int[] CLOCK_RATES = {32000, 44100, 48000, 0};

    /**
     * The speaker mask bits that stand for a pair of speakers (ETSI TS 102 114 Table 7-10).
     */
    private static final int PAIRS = 0xAE66;

    /**
     * The speaker mask bits of the low frequency effects channels.
     */
    private static final int LFE = 0x1008;

    /**
     * The core frame of the access unit, or {@code null}.
     */
    private final Core core;

    /**
     * The extension substreams of the access unit, in order.
     */
    private final List<Substream> substreams;

    private DtsCarriage(Core core, List<Substream> substreams) {
        this.core = core;
        this.substreams = substreams;
    }

    /**
     * Describes a stream from its first access unit: a core frame, extension substream frames, or a core frame
     * followed by extension substream frames.
     *
     * @param unit the access unit
     * @return the description
     * @throws IllegalArgumentException if the unit does not start with a DTS core or extension substream sync word,
     * or a header it holds is truncated
     */
    static DtsCarriage of(byte[] unit) {
        var position = 0;
        Core core = null;
        if (unit.length >= DtsConfigs.HEADER_SIZE + 2 && DtsConfigs.isSync(unit, 0)) {
            core = Core.read(unit);
            position = Math.min(core.frameSize, unit.length);
        }
        var substreams = new ArrayList<Substream>();
        while (position + DtsConfigs.EXTENSION_HEADER_SIZE <= unit.length && DtsConfigs.isSubstreamSync(unit, position)) {
            var size = DtsConfigs.extensionSize(unit, position);
            var substream = Substream.read(unit, position, Math.min(size, unit.length - position));
            if (substream == null) {
                break;
            }
            substreams.add(substream);
            position += size;
        }
        if (core == null && substreams.isEmpty()) {
            throw new IllegalArgumentException("the first DTS packet starts with no DTS sync word");
        }
        return new DtsCarriage(core, List.copyOf(substreams));
    }

    /**
     * {@return the Blu-ray stream type: {@code 0x86} when an asset is coded with XLL, {@code 0xA2} when the stream
     * is DTS Express (LBR only), {@code 0x85} when an extension substream adds other extensions, {@code 0x82} for a
     * core alone}
     */
    int blurayStreamType() {
        var components = 0;
        for (var substream : substreams) {
            for (var asset : substream.assets) {
                components |= asset.components;
            }
        }
        if ((components & XLL) != 0) {
            return 0x86;
        }
        if (core == null && (components & ~LBR) == 0 && (components & LBR) != 0) {
            return 0xA2;
        }
        return substreams.isEmpty() ? 0x82 : 0x85;
    }

    /**
     * {@return whether the access units hold more than one substream, which forbids gathering several of them in
     * one PES packet (ETSI TS 102 114 clause F.4.3)}
     */
    boolean multipleSubstreams() {
        return (core != null ? 1 : 0) + substreams.size() > 1;
    }

    /**
     * {@return the descriptors of a plain transport stream: the {@code "DTS1"}, {@code "DTS2"} or {@code "DTS3"}
     * registration when a core of 512, 1024 or 2048 samples is present, then the DVB DTS descriptor (tag
     * {@code 0x7B}) for a core alone or the DVB DTS-HD descriptor (extension tag {@code 0x0E}) otherwise}
     */
    byte[] dvbDescriptors() {
        var dvb = substreams.isEmpty() ? dtsDescriptor() : dtsHdDescriptor();
        var identifier = core == null ? null : switch (core.blocks * 32) {
            case 512 -> "DTS1";
            case 1024 -> "DTS2";
            case 2048 -> "DTS3";
            default -> null;
        };
        if (identifier == null) {
            return dvb;
        }
        var out = new byte[6 + dvb.length];
        out[0] = 0x05;
        out[1] = 4;
        for (var index = 0; index < 4; index++) {
            out[2 + index] = (byte) identifier.charAt(index);
        }
        System.arraycopy(dvb, 0, out, 6, dvb.length);
        return out;
    }

    /**
     * {@return the DTS descriptor (ETSI EN 300 468 Table G.1) of the core}
     */
    private byte[] dtsDescriptor() {
        var body = new MsbBitWriter(8);
        body.write(core.sfreq, 4);
        body.write(core.rate, 6);
        body.write(core.blocks - 1, 7);
        body.write(core.frameSize - 1, 14);
        body.write(core.amode, 6);
        body.write(core.lfe != 0 ? 1 : 0, 1);
        body.write(core.extendedSurround(), 2);
        var bytes = body.toByteArray();
        var out = new byte[2 + bytes.length];
        out[0] = 0x7B;
        out[1] = (byte) bytes.length;
        System.arraycopy(bytes, 0, out, 2, bytes.length);
        return out;
    }

    /**
     * {@return the DTS-HD descriptor (ETSI EN 300 468 Tables G.6, G.7 and G.9) of the core substream and the
     * extension substreams of the access unit}
     */
    private byte[] dtsHdDescriptor() {
        var body = new MsbBitWriter(64);
        body.write(0x0E, 8);
        var present = new Substream[4];
        for (var substream : substreams) {
            present[substream.index] = present[substream.index] == null ? substream : present[substream.index];
        }
        body.write(core != null ? 1 : 0, 1);
        for (var index = 0; index < 4; index++) {
            body.write(present[index] != null ? 1 : 0, 1);
        }
        body.write(0x7, 3);
        if (core != null) {
            var info = new MsbBitWriter(8);
            info.write(0, 3);
            info.write(core.channels(), 5);
            info.write(core.lfe != 0 ? 1 : 0, 1);
            info.write(assetRateCode(core.sampleRate), 4);
            info.write(core.wideSamples() ? 1 : 0, 1);
            info.write(0x3, 2);
            assetInfo(info, construction(core.components()), false, core.bitRate());
            substreamInfo(body, info.toByteArray());
        }
        for (var substream : present) {
            if (substream == null) {
                continue;
            }
            var info = new MsbBitWriter(32);
            var channels = 0;
            var lfe = false;
            var rateCode = 0;
            var wide = false;
            for (var asset : substream.assets) {
                channels = Math.max(channels, asset.channels);
                lfe |= (asset.speakerMask & LFE) != 0;
                if (ASSET_RATES[asset.rateCode] > ASSET_RATES[rateCode]) {
                    rateCode = asset.rateCode;
                }
                wide |= asset.bitResolution > 16;
            }
            info.write(substream.assets.size() - 1, 3);
            info.write(Math.min(channels, 31), 5);
            info.write(lfe ? 1 : 0, 1);
            info.write(rateCode, 4);
            info.write(wide ? 1 : 0, 1);
            info.write(0x3, 2);
            for (var asset : substream.assets) {
                var components = asset.components | (core != null ? core.components() : 0);
                var lossless = (asset.components & XLL) != 0;
                var rate = substream.duration > 0 ? (int) Math.round(asset.size * 8.0 * substream.clockRate
                        / substream.duration / 1000) : 0;
                assetInfo(info, construction(components), lossless, lossless ? 0 : rate);
            }
            substreamInfo(body, info.toByteArray());
        }
        var bytes = body.toByteArray();
        var out = new byte[2 + bytes.length];
        out[0] = 0x7F;
        out[1] = (byte) bytes.length;
        System.arraycopy(bytes, 0, out, 2, bytes.length);
        return out;
    }

    /**
     * Writes a {@code substream_info} structure: its length, then its fields.
     *
     * @param body the descriptor body
     * @param info the fields after {@code substream_length}
     */
    private static void substreamInfo(MsbBitWriter body, byte[] info) {
        body.write(info.length, 8);
        body.writeBytes(info, 0, info.length);
    }

    /**
     * Writes an {@code asset_info} structure without component type or language.
     *
     * @param info the substream information being written
     * @param construction the {@code asset_construction} code
     * @param variable whether the asset has a variable bit rate
     * @param kilobits the bit rate in kbit/s, or {@code 0} when it varies
     */
    private static void assetInfo(MsbBitWriter info, int construction, boolean variable, int kilobits) {
        info.write(construction, 5);
        info.write(variable ? 1 : 0, 1);
        info.write(0, 1);
        info.write(0, 1);
        info.write(0, 1);
        info.write(Math.clamp(kilobits, 0, 8191), 13);
        info.write(0x3, 2);
    }

    /**
     * Returns the {@code asset_construction} code of a set of coding components.
     *
     * @param components the components ({@code nuCoreExtensionMask} bits)
     * @return the code of Table G.10 holding exactly those components, or {@code 0} when none does
     */
    private static int construction(int components) {
        for (var code = 0; code < CONSTRUCTIONS.length; code++) {
            if (CONSTRUCTIONS[code] == components) {
                return code + 1;
            }
        }
        return 0;
    }

    /**
     * Returns the {@code nuMaxSampleRate} code of a sample rate.
     *
     * @param rate the rate in hertz
     * @return the code, or {@code 12} (48 kHz) for a rate the table lacks
     */
    private static int assetRateCode(int rate) {
        for (var code = 0; code < ASSET_RATES.length; code++) {
            if (ASSET_RATES[code] == rate) {
                return code;
            }
        }
        return 12;
    }

    /**
     * The header fields of a core frame (ETSI TS 102 114 clause 5.4.1).
     *
     * @param frameSize the frame size in bytes ({@code FSIZE + 1})
     * @param blocks the number of 32-sample blocks ({@code NBLKS + 1})
     * @param amode the channel arrangement ({@code AMODE})
     * @param sfreq the sampling frequency code ({@code SFREQ})
     * @param sampleRate the sampling rate in hertz
     * @param rate the transmission bit rate code ({@code RATE})
     * @param extension the extension type when one is signaled ({@code EXT_AUDIO_ID}), or {@code -1}
     * @param lfe the low frequency effects flag ({@code LFF})
     * @param resolution the source PCM resolution code ({@code PCMR})
     */
    private record Core(int frameSize, int blocks, int amode, int sfreq, int sampleRate, int rate, int extension,
                        int lfe, int resolution) {
        /**
         * The channel count of each {@code AMODE} up to 15.
         */
        private static final int[] CHANNELS = {1, 2, 2, 2, 2, 3, 3, 4, 4, 5, 6, 6, 6, 7, 8, 8};

        /**
         * Reads the header of the core frame at the start of an access unit.
         */
        static Core read(byte[] unit) {
            var reader = new MsbBitReader(unit, 0, unit.length);
            reader.skip(32 + 1 + 5);
            var crc = reader.readFlag();
            var blocks = reader.read(7) + 1;
            var frameSize = reader.read(14) + 1;
            var amode = reader.read(6);
            var sfreq = reader.read(4);
            var rate = reader.read(5);
            reader.skip(5);
            var extensionType = reader.read(3);
            var extension = reader.readFlag();
            reader.skip(1);
            var lfe = reader.read(2);
            reader.skip(1);
            if (crc) {
                reader.skip(16);
            }
            reader.skip(1 + 4 + 2);
            var resolution = reader.read(3);
            if (reader.overrun()) {
                throw new IllegalArgumentException("truncated DTS core header");
            }
            return new Core(frameSize, blocks, amode, sfreq, DtsConfigs.sampleRate(unit, 0), rate,
                    extension ? extensionType : -1, lfe, resolution);
        }

        /**
         * {@return the core substream components ({@code nuCoreExtensionMask} bits) the header signals}
         */
        int components() {
            return CORE | switch (extension) {
                case 0 -> CORE_XCH;
                case 2 -> CORE_X96;
                case 6 -> CORE_XXCH;
                default -> 0;
            };
        }

        /**
         * {@return the channel count, low frequency effects channel included}
         */
        int channels() {
            return (amode < CHANNELS.length ? CHANNELS[amode] : 2) + (lfe != 0 ? 1 : 0);
        }

        /**
         * {@return whether the source resolution is above 16 bits}
         */
        boolean wideSamples() {
            return resolution >= 2;
        }

        /**
         * {@return the bit rate in kbit/s from the frame size and duration}
         */
        int bitRate() {
            return sampleRate == 0 ? 0 : (int) Math.round(frameSize * 8.0 * sampleRate / (blocks * 32) / 1000);
        }

        /**
         * {@return the {@code extended_surround_flag}: discrete when the XCh extension is present, none otherwise}
         */
        int extendedSurround() {
            return extension == 0 ? 2 : 0;
        }
    }

    /**
     * An asset of an extension substream.
     *
     * @param size the asset size in the frame, in bytes
     * @param channels the total channel count ({@code nuTotalNumChs + 1})
     * @param rateCode the maximum sample rate code ({@code nuMaxSampleRate})
     * @param bitResolution the bit resolution ({@code nuBitResolution + 1})
     * @param speakerMask the speaker activity mask, or {@code 0}
     * @param components the coding components ({@code nuCoreExtensionMask} bits)
     */
    private record Asset(int size, int channels, int rateCode, int bitResolution, int speakerMask, int components) {
    }

    /**
     * An extension substream frame with static fields.
     *
     * @param index the substream index ({@code nuExtSSIndex})
     * @param clockRate the reference clock rate in hertz
     * @param duration the frame duration in reference clock periods
     * @param assets the assets of the frame, in descriptor order
     */
    private record Substream(int index, int clockRate, int duration, List<Asset> assets) {
        /**
         * Reads an extension substream frame (ETSI TS 102 114 Tables 7-2 to 7-7).
         *
         * @return the frame, or {@code null} when it carries no static fields
         */
        static Substream read(byte[] data, int offset, int length) {
            var reader = new MsbBitReader(data, offset, length);
            reader.skip(32 + 8);
            var index = reader.read(2);
            var wide = reader.readFlag();
            var sizeBits = wide ? 20 : 16;
            reader.skip(wide ? 12 : 8);
            reader.skip(sizeBits);
            if (!reader.readFlag()) {
                return null;
            }
            var clockRate = CLOCK_RATES[reader.read(2)];
            var duration = 512 * (reader.read(3) + 1);
            if (reader.readFlag()) {
                reader.skip(36);
            }
            var presentations = reader.read(3) + 1;
            var assetCount = reader.read(3) + 1;
            var active = new int[presentations];
            for (var p = 0; p < presentations; p++) {
                active[p] = reader.read(index + 1);
            }
            for (var p = 0; p < presentations; p++) {
                for (var s = 0; s <= index; s++) {
                    if ((active[p] >> s & 1) != 0) {
                        reader.skip(8);
                    }
                }
            }
            var mixMetadata = reader.readFlag();
            var mixOutChannels = new int[4];
            var mixOutConfigs = 0;
            if (mixMetadata) {
                reader.skip(2);
                var maskBits = (reader.read(2) + 1) << 2;
                mixOutConfigs = reader.read(2) + 1;
                for (var c = 0; c < mixOutConfigs; c++) {
                    mixOutChannels[c] = speakers(reader.read(maskBits));
                }
            }
            var sizes = new int[assetCount];
            for (var a = 0; a < assetCount; a++) {
                sizes[a] = reader.read(sizeBits) + 1;
            }
            var assets = new ArrayList<Asset>();
            for (var a = 0; a < assetCount; a++) {
                var asset = readAsset(reader, sizes[a], mixMetadata, mixOutChannels, mixOutConfigs, sizeBits);
                if (asset == null) {
                    break;
                }
                assets.add(asset);
            }
            if (reader.overrun() || clockRate == 0 || assets.isEmpty()) {
                return null;
            }
            return new Substream(index, clockRate, duration, List.copyOf(assets));
        }

        /**
         * Reads an asset descriptor up to its coding components and moves past it.
         *
         * @return the asset, or {@code null} when the descriptor is malformed
         */
        private static Asset readAsset(MsbBitReader reader, int size, boolean mixMetadataEnabled, int[] mixOutChannels,
                                       int mixOutConfigs, int sizeBits) {
            var start = reader.bitPosition();
            var descriptorSize = reader.read(9) + 1;
            reader.skip(3);
            if (reader.readFlag()) {
                reader.skip(4);
            }
            if (reader.readFlag()) {
                reader.skip(24);
            }
            if (reader.readFlag()) {
                reader.skip((reader.read(10) + 1) * 8L);
            }
            var bitResolution = reader.read(5) + 1;
            var rateCode = reader.read(4);
            var channels = reader.read(8) + 1;
            var speakerMask = 0;
            var embeddedStereo = false;
            var embeddedSix = false;
            if (reader.readFlag()) {
                embeddedStereo = channels > 2 && reader.readFlag();
                embeddedSix = channels > 6 && reader.readFlag();
                var maskBits = 0;
                if (reader.readFlag()) {
                    maskBits = (reader.read(2) + 1) << 2;
                    speakerMask = reader.read(maskBits);
                }
                var remapSets = reader.read(3);
                var remapSpeakers = new int[remapSets];
                for (var s = 0; s < remapSets; s++) {
                    remapSpeakers[s] = speakers(reader.read(maskBits));
                }
                for (var s = 0; s < remapSets; s++) {
                    var decoded = reader.read(5) + 1;
                    for (var ch = 0; ch < remapSpeakers[s]; ch++) {
                        reader.skip(5L * Integer.bitCount(reader.read(decoded)));
                    }
                }
            } else {
                reader.skip(3);
            }
            var drc = reader.readFlag();
            if (drc) {
                reader.skip(8);
            }
            if (reader.readFlag()) {
                reader.skip(5);
            }
            if (drc && embeddedStereo) {
                reader.skip(8);
            }
            if (mixMetadataEnabled && reader.readFlag()) {
                reader.skip(1 + 6);
                var control = reader.read(2);
                reader.skip(control < 3 ? 3 : 8);
                var perChannel = reader.readFlag();
                for (var c = 0; c < mixOutConfigs; c++) {
                    reader.skip(6L * (perChannel ? mixOutChannels[c] : 1));
                }
                var decodedChannels = new int[3];
                var embedded = 0;
                decodedChannels[embedded++] = channels;
                if (embeddedSix) {
                    decodedChannels[embedded++] = 6;
                }
                if (embeddedStereo) {
                    decodedChannels[embedded++] = 2;
                }
                for (var c = 0; c < mixOutConfigs; c++) {
                    for (var e = 0; e < embedded; e++) {
                        for (var ch = 0; ch < decodedChannels[e]; ch++) {
                            reader.skip(6L * Integer.bitCount(reader.read(mixOutChannels[c])));
                        }
                    }
                }
            }
            var components = switch (reader.read(2)) {
                case 0 -> reader.read(12);
                case 1 -> XLL;
                case 2 -> LBR;
                default -> 0;
            };
            if (reader.overrun() || reader.bitPosition() - start > descriptorSize * 8L) {
                return null;
            }
            reader.seek(start + descriptorSize * 8L);
            return new Asset(size, channels, rateCode, bitResolution, speakerMask, components);
        }

        /**
         * {@return the number of speakers of a speaker mask, a pair bit counting two}
         */
        private static int speakers(int mask) {
            return Integer.bitCount(mask) + Integer.bitCount(mask & PAIRS);
        }
    }
}
