package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.aot.TrainingFamily;
import com.github.auties00.smalt.aot.TrainingWorkload;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.aac.AacCompression;
import com.github.auties00.smalt.audio.aac.AacDecoder;
import com.github.auties00.smalt.audio.aac.AacDecoderOptions;
import com.github.auties00.smalt.audio.aac.AacEncoder;
import com.github.auties00.smalt.audio.aac.AacEncoderOptions;
import com.github.auties00.smalt.audio.ac3.Ac3Decoder;
import com.github.auties00.smalt.audio.ac3.Ac3Encoder;
import com.github.auties00.smalt.audio.ac3.Ac3EncoderOptions;
import com.github.auties00.smalt.audio.ac3.Eac3Encoder;
import com.github.auties00.smalt.audio.ac3.Eac3EncoderOptions;
import com.github.auties00.smalt.audio.amr.AmrDecoder;
import com.github.auties00.smalt.audio.amr.AmrEncoder;
import com.github.auties00.smalt.audio.amr.AmrEncoderOptions;
import com.github.auties00.smalt.audio.amr.AmrMode;
import com.github.auties00.smalt.container.wav.WavDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.h264.H264Decoder;
import com.github.auties00.smalt.video.h264.H264DecoderOptions;
import com.github.auties00.smalt.video.h264.encoder.H264Encoder;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderOptions;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderSpeed;
import com.github.auties00.smalt.video.h264.encoder.H264Profile;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static com.github.auties00.smalt.aot.internal.VideoStreams.HEIGHT;
import static com.github.auties00.smalt.aot.internal.VideoStreams.TIME_BASE;
import static com.github.auties00.smalt.aot.internal.VideoStreams.WIDTH;
import static com.github.auties00.smalt.aot.internal.WorkloadFactory.file;
import static com.github.auties00.smalt.aot.internal.WorkloadFactory.realtime;

/**
 * Creates the workloads of {@link TrainingFamily#MPEG}, the codecs of {@code smalt-mpeg}: H.264,
 * AAC, AC-3 and E-AC-3, and AMR, each keeping its codecs across rounds as a long-running
 * application does.
 */
final class MpegWorkloads {
    /**
     * The bitrate of the H.264 streams, in bits per second.
     */
    private static final int BITRATE = 800_000;

    /**
     * Prevents instantiation.
     */
    private MpegWorkloads() {
        throw new AssertionError();
    }

    /**
     * Returns the factories of the family's workloads.
     *
     * @return the factories, in order
     */
    static List<WorkloadFactory> factories() {
        // TODO: train the HEVC, HEIC, MPEG-4 Part 2 and H.263 decoders and the interlaced (field pictures, MBAFF
        //  frames), high bit depth, 4:2:2, 4:4:4 (color planes coded together or separately) and lossless paths of the
        //  H.264 decoder, which need streams that no Smalt encoder produces
        return List.of(
                realtime("h264-encode-rt-t1", () -> h264Encode(1, null, null)),
                realtime("h264-encode-rt-t2", () -> h264Encode(2, null, null)),
                realtime("h264-decode-rt-t1", () -> h264Decode(1, true)),
                realtime("h264-session-t2", () -> VideoStreams.session("h264-session-t2", Codec.H264,
                        () -> new H264Encoder(WIDTH, HEIGHT, H264EncoderOptions.forRealtime().toBuilder()
                                .timeBase(TIME_BASE).bitrate(BITRATE).threads(2).build()),
                        track -> new H264Decoder(track, H264DecoderOptions.forRealtime()))),
                realtime("ac3-encode-rt", () -> ac3Realtime(6)),
                realtime("eac3-rt", () -> eac3("eac3-rt", Eac3EncoderOptions.forRealtime(), 6)),
                file("h264-encode-balanced-main-t2", () -> h264Encode(2, H264EncoderSpeed.BALANCED, H264Profile.MAIN)),
                file("h264-encode-fast-high-t1", () -> h264Encode(1, H264EncoderSpeed.FAST, H264Profile.HIGH)),
                file("h264-encode-fastest-baseline-t1",
                        () -> h264Encode(1, H264EncoderSpeed.FASTEST, H264Profile.CONSTRAINED_BASELINE)),
                file("h264-decode-t1", () -> h264Decode(1, false)),
                file("h264-decode-t2", () -> h264Decode(2, false)),
                file("h264-decode-film-grain-t2", () -> h264FilmGrainDecode(2)),
                file("aac-encode", () -> aacEncode(6)),
                file("aac-decode", () -> aacDecode("aac-decode", false, false, 6)),
                file("heaac-decode", () -> aacDecode("heaac-decode", true, false, 6)),
                file("heaacv2-decode", () -> aacDecode("heaacv2-decode", true, true, 6)),
                file("aac-drc-decode", () -> aacDynamicRangeDecode("aac-drc-decode", false, AacCompression.LIGHT, 4)),
                file("heaac-drc-decode", () -> aacDynamicRangeDecode("heaac-drc-decode", true, AacCompression.HEAVY, 4)),
                file("aac-main-decode", () -> aacObjectTypeDecode("aac-main-decode", 1, 4)),
                file("aac-ssr-decode", () -> aacObjectTypeDecode("aac-ssr-decode", 3, 4)),
                file("aac-ltp-decode", () -> aacLtpDecode("aac-ltp-decode", false, 4)),
                file("aac-er-ltp-decode", () -> aacLtpDecode("aac-er-ltp-decode", true, 4)),
                file("aac-er-lc-tools-decode", () -> aacResilienceToolsDecode(4)),
                file("aac-ld-decode", () -> aacLowDelayDecode(4)),
                file("aac-eld-decode", () -> aacEnhancedLowDelayDecode("aac-eld-decode", false, 4)),
                file("aac-eld-sbr-decode", () -> aacEnhancedLowDelayDecode("aac-eld-sbr-decode", true, 4)),
                file("ac3", () -> ac3(6)),
                file("eac3", () -> eac3("eac3", Eac3EncoderOptions.defaults(), 6)),
                file("iec61937-wav", () -> iec61937(4)),
                file("aac-wav", () -> aacWave(4)),
                file("amr-nb-encode", () -> amrEncode("amr-nb-encode", Codec.AMR_NB, 4)),
                file("amr-nb-decode", () -> amrDecode("amr-nb-decode", Codec.AMR_NB, 1)),
                file("amr-wb-encode", () -> amrEncode("amr-wb-encode", Codec.AMR_WB, 4)),
                file("amr-wb-decode", () -> amrDecode("amr-wb-decode", Codec.AMR_WB, 1)));
    }

    /**
     * Returns a workload that encodes with the H.264 encoder.
     *
     * @param threads the encoder threads
     * @param speed   the speed of the file preset, or {@code null} for the real-time preset
     * @param profile the profile of the file preset, or {@code null} for the real-time preset
     * @return the workload
     */
    private static TrainingWorkload h264Encode(int threads, H264EncoderSpeed speed, H264Profile profile) {
        var realtime = speed == null;
        var options = realtime
                ? H264EncoderOptions.forRealtime().toBuilder().timeBase(TIME_BASE).bitrate(BITRATE).threads(threads).build()
                : fileOptions(profile, speed, threads);
        var name = realtime ? "h264-encode-rt-t" + threads
                : "h264-encode-" + speed.name().toLowerCase(Locale.ROOT) + "-"
                + (profile == H264Profile.CONSTRAINED_BASELINE ? "baseline" : profile.name().toLowerCase(Locale.ROOT))
                + "-t" + threads;
        return VideoStreams.encode(name, new H264Encoder(WIDTH, HEIGHT, options));
    }

    /**
     * Returns a workload that decodes a High profile H.264 stream of the file encoder.
     *
     * @param threads  the decoder threads
     * @param realtime whether the decoder runs its low-delay real-time preset
     * @return the workload
     */
    private static TrainingWorkload h264Decode(int threads, boolean realtime) {
        var encoder = new H264Encoder(WIDTH, HEIGHT, fileOptions(H264Profile.HIGH, H264EncoderSpeed.FAST, 2));
        var codecConfig = encoder.codecConfig();
        var packets = VideoStreams.encodeAll(encoder, SyntheticVideo.natural(WIDTH, HEIGHT, VideoStreams.DECODED_FRAMES / 2));
        var options = realtime ? H264DecoderOptions.forRealtime() : H264DecoderOptions.defaults();
        var decoder = new H264Decoder(VideoStreams.track(Codec.H264, codecConfig.orElse(null)),
                options.toBuilder().threads(threads).build());
        return VideoStreams.decode((realtime ? "h264-decode-rt-t" : "h264-decode-t") + threads, packets, decoder);
    }

    /**
     * An H.264 SEI NAL unit (without start code) holding a film grain characteristics message for the
     * current picture: frequency filtering with additive blending, {@code log2_scale_factor} 3, two
     * intensity intervals per component with different cut frequencies.
     */
    private static final byte[] FILM_GRAIN_SEI = {
            0x06, 0x13, 0x28, 0x00, (byte) 0xF8, 0x0A, 0x00, 0x78, 0x03, (byte) 0xC0, 0x40, 0x63, (byte) 0xCF,
            (byte) 0xF8, 0x0C, (byte) 0x81, 0x01, 0x40, 0x14, 0x00, (byte) 0xFE, 0x05, 0x01, (byte) 0x83, 0x20,
            0x3F, (byte) 0xC0, 0x5A, 0x06, 0x0C, 0x02, (byte) 0x80, 0x1F, (byte) 0xC0, 0x50, 0x18, 0x31, 0x01,
            (byte) 0xFE, 0x0F, 0x02, (byte) 0x81, 0x48, (byte) 0x80
    };

    /**
     * Returns a workload that decodes a High profile H.264 stream of the file encoder whose pictures
     * carry a film grain characteristics SEI message, so the decoder renders the grain.
     *
     * @param threads the decoder threads
     * @return the workload
     */
    private static TrainingWorkload h264FilmGrainDecode(int threads) {
        var encoder = new H264Encoder(WIDTH, HEIGHT, fileOptions(H264Profile.HIGH, H264EncoderSpeed.FASTEST, 2));
        var codecConfig = encoder.codecConfig().orElse(null);
        // an avcC record means length-prefixed NAL units of its length size, otherwise Annex B
        var lengthSize = codecConfig != null && codecConfig.length > 4 ? (codecConfig[4] & 3) + 1 : 0;
        var packets = new ArrayList<Packet>();
        for (var packet : VideoStreams.encodeAll(encoder, SyntheticVideo.natural(WIDTH, HEIGHT,
                VideoStreams.DECODED_FRAMES / 4))) {
            var prefix = new byte[(lengthSize == 0 ? 4 : lengthSize) + FILM_GRAIN_SEI.length];
            if (lengthSize == 0) {
                prefix[3] = 1;
            } else {
                for (var i = 0; i < lengthSize; i++) {
                    prefix[i] = (byte) (FILM_GRAIN_SEI.length >>> (8 * (lengthSize - 1 - i)));
                }
            }
            System.arraycopy(FILM_GRAIN_SEI, 0, prefix, prefix.length - FILM_GRAIN_SEI.length, FILM_GRAIN_SEI.length);
            var data = Arrays.copyOf(prefix, prefix.length + packet.data().length);
            System.arraycopy(packet.data(), 0, data, prefix.length, packet.data().length);
            packets.add(packet.withData(data));
        }
        var decoder = new H264Decoder(VideoStreams.track(Codec.H264, codecConfig),
                H264DecoderOptions.defaults().toBuilder().threads(threads).build());
        return VideoStreams.decode("h264-decode-film-grain-t" + threads, packets, decoder);
    }

    /**
     * Returns the H.264 file preset with the given profile, speed and threads, at 800 kbit/s.
     *
     * @param profile the profile the stream is coded in
     * @param speed the encoder speed preset
     * @param threads the encoder threads
     * @return the options
     */
    private static H264EncoderOptions fileOptions(H264Profile profile, H264EncoderSpeed speed, int threads) {
        return H264EncoderOptions.forFile().toBuilder()
                .timeBase(TIME_BASE)
                .profile(profile)
                .bitrate(BITRATE)
                .speed(speed)
                .threads(threads)
                .build();
    }

    /**
     * Returns a workload that encodes stereo music as 44.1 kHz AAC-LC at 128 kbit/s, one encoder
     * across rounds.
     *
     * @param seconds the music encoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload aacEncode(double seconds) {
        var encoder = new AacEncoder(AacEncoderOptions.of(44100, ChannelLayout.STEREO, 128000));
        return AudioStreams.encode("aac-encode", SyntheticAudio.music(44100, seconds), ChannelLayout.STEREO, 44100, encoder);
    }

    /**
     * Returns a workload that decodes AAC: Low Complexity music at 44.1 kHz, or, when
     * {@code sbr} is set, a 22.05 kHz core announced as HE-AAC (and, with {@code ps}, as HE-AACv2
     * with a mono core), which runs the decoder's spectral band replication and parametric stereo
     * stages on the packets of the Low Complexity encoder.
     *
     * @param name    the name of the workload
     * @param sbr     whether the configuration announces spectral band replication
     * @param ps      whether the configuration announces parametric stereo, with a mono core
     * @param seconds the music decoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload aacDecode(String name, boolean sbr, boolean ps, double seconds) {
        var rate = sbr ? 22050 : 44100;
        var layout = ps ? ChannelLayout.MONO : ChannelLayout.STEREO;
        var encoder = new AacEncoder(AacEncoderOptions.of(rate, layout, sbr ? 48000 : 128000));
        var music = SyntheticAudio.music(rate, seconds);
        var planes = ps ? new float[][]{music[0]} : music;
        var packets = AudioStreams.encodeAll(encoder, planes, layout, rate);
        var config = !sbr ? encoder.codecConfig().orElseThrow()
                : ps ? new byte[]{(byte) 0xEB, (byte) 0x8A, 0x08, 0x00} : new byte[]{0x2B, (byte) 0x92, 0x08, 0x00};
        var track = AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(new Rational(1, rate)).sampleRate(rate)
                .layout(layout).codecConfig(config).build();
        return AudioStreams.decode(name, packets, new AacDecoder(track));
    }

    /**
     * Returns a workload that decodes AAC carrying dynamic range control: the stereo packets of
     * {@link #aacDecode} (announced as HE-AAC when {@code sbr} is set, so the gains apply in the QMF
     * domain) with a fill element of two-band {@code dynamic_range_info} before each end marker, its
     * gains, interpolation scheme and program reference level changing from packet to packet, and,
     * in every other packet, a data stream element of ETSI TS 101 154 ancillary data whose heavy
     * compression is switched on in half of them.
     *
     * @param name the name of the workload
     * @param sbr whether the configuration announces spectral band replication
     * @param compression the compression the decoder applies, with scaled gains and a target level
     * @param seconds the music decoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload aacDynamicRangeDecode(String name, boolean sbr, AacCompression compression,
                                                          double seconds) {
        var rate = sbr ? 22050 : 44100;
        var encoder = new AacEncoder(AacEncoderOptions.of(rate, ChannelLayout.STEREO, sbr ? 48000 : 128000));
        var packets = AudioStreams.encodeAll(encoder, SyntheticAudio.music(rate, seconds), ChannelLayout.STEREO, rate);
        var withGains = new ArrayList<Packet>(packets.size());
        for (var i = 0; i < packets.size(); i++) {
            withGains.add(packets.get(i).withData(withDynamicRange(packets.get(i).data(), i)));
        }
        var config = sbr ? new byte[]{0x2B, (byte) 0x92, 0x08, 0x00} : encoder.codecConfig().orElseThrow();
        var track = AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(new Rational(1, rate)).sampleRate(rate)
                .layout(ChannelLayout.STEREO).codecConfig(config).build();
        var options = AacDecoderOptions.builder().compression(compression).cutScale(0.5f).boostScale(0.75f)
                .targetLevel(96).build();
        return AudioStreams.decode(name, withGains, new AacDecoder(track, options));
    }

    /**
     * Inserts dynamic range control elements before the end marker of a raw data block.
     *
     * @param block the raw data block, ending with its end marker and zero padding
     * @param index the index of the block in the stream, which varies the gains
     * @return the block with the elements
     */
    private static byte[] withDynamicRange(byte[] block, int index) {
        var last = block.length - 1;
        while (block[last] == 0) {
            last--;
        }
        var end = 8 * last + 7 - Integer.numberOfTrailingZeros(block[last] & 0xFF) - 2;
        var bits = new PacketBits();
        for (var i = 0; i < end; i++) {
            bits.write(block[i >> 3] >> (7 - (i & 7)) & 1, 1);
        }
        bits.write(6, 3);
        bits.write(7, 4);
        bits.write(11, 4);
        bits.write(0b0011, 4);
        bits.write(1, 4);
        bits.write(index % 9, 4);
        bits.write(40, 8);
        bits.write(255, 8);
        bits.write(80 + index % 40, 7);
        bits.write(0, 1);
        bits.write(1, 1);
        bits.write(index % 48, 7);
        bits.write(0, 1);
        bits.write(index * 7 % 30, 7);
        if (index % 2 == 0) {
            bits.write(4, 3);
            bits.write(0, 4);
            bits.write(0, 1);
            bits.write(5, 8);
            bits.write(0xBC, 8);
            bits.write(0xC0, 8);
            bits.write(0x04, 8);
            bits.write(index % 4 == 0 ? 1 : 0, 8);
            bits.write(index % 4 == 0 ? 0x80 + index % 16 : 0, 8);
        }
        bits.write(7, 3);
        return bits.toByteArray();
    }

    /**
     * Returns a workload that decodes the packets of the Low Complexity encoder (stereo music at
     * 44.1 kHz) announced as another AAC object type, whose syntax they satisfy: AAC Main runs the
     * predictor banks and AAC SSR the PQF filterbank.
     *
     * @param name       the name of the workload
     * @param objectType the announced audio object type: 1 (Main) or 3 (SSR)
     * @param seconds    the music decoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload aacObjectTypeDecode(String name, int objectType, double seconds) {
        var encoder = new AacEncoder(AacEncoderOptions.of(44100, ChannelLayout.STEREO, 128000));
        var packets = AudioStreams.encodeAll(encoder, SyntheticAudio.music(44100, seconds), ChannelLayout.STEREO, 44100);
        // the two-byte configuration: the object type, sampling frequency index 4 (44.1 kHz), stereo
        var config = new byte[]{(byte) (objectType << 3 | 4 >> 1), (byte) ((4 & 1) << 7 | 2 << 3)};
        var track = AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(new Rational(1, 44100)).sampleRate(44100)
                .layout(ChannelLayout.STEREO).codecConfig(config).build();
        return AudioStreams.decode(name, packets, new AacDecoder(track));
    }

    /**
     * Returns a workload that decodes synthetic mono AAC LTP (or ER AAC LTP) packets at 44.1 kHz
     * whose long term prediction is on in the 40 bands it covers: the noise-substituted band 39
     * keeps the history alive, and every frame runs the prediction, its forward transform and an
     * order 4 TNS analysis filter, alternating the window shapes and sweeping lag and coefficient.
     *
     * @param name           the name of the workload
     * @param errorResilient whether the packets are ER AAC LTP {@code er_raw_data_block}s
     * @param seconds        the audio decoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload aacLtpDecode(String name, boolean errorResilient, double seconds) {
        var frames = (int) Math.ceil(seconds * 44100 / 1024);
        var packets = new ArrayList<Packet>(frames);
        for (var frame = 0; frame < frames; frame++) {
            var bits = new PacketBits();
            if (!errorResilient) {
                bits.write(0, 3);
            }
            bits.write(0, 4);
            bits.write(100, 8);
            bits.write(0, 3);
            bits.write(frame & 1, 1);
            bits.write(40, 6);
            bits.write(1, 1);
            bits.write(1, 1);
            bits.write(256 + frame * 37 % 1792, 11);
            // coefficients below 1 keep the prediction loop stable
            bits.write(frame & 3, 3);
            bits.write(-1, 40);
            bits.write(0, 4);
            bits.write(31, 5);
            bits.write(8, 5);
            bits.write(13, 4);
            bits.write(1, 5);
            bits.write(256 + 40, 9);
            bits.write(0, 1);
            bits.write(1, 1);
            if (!errorResilient) {
                aacTnsData(bits);
            }
            bits.write(0, 1);
            if (errorResilient) {
                aacTnsData(bits);
            } else {
                bits.write(7, 3);
            }
            packets.add(new Packet(1, bits.toByteArray(), frame * 1024L, frame * 1024L, 1024, true));
        }
        // object type 4 or 19, sampling frequency index 4 (44.1 kHz), mono; ER adds the extension
        // flag, no resilience tools and epConfig 0
        var config = errorResilient ? new byte[]{(byte) 0x9A, 0x09, 0x00} : new byte[]{0x22, 0x08};
        var track = AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(new Rational(1, 44100)).sampleRate(44100)
                .layout(ChannelLayout.MONO).codecConfig(config).build();
        return AudioStreams.decode(name, packets, new AacDecoder(track));
    }

    /**
     * Returns a workload that decodes synthetic mono ER AAC LC packets at 44.1 kHz coded with every
     * error resilience tool: virtual codebook sections, RVLC scalefactors and Huffman codeword
     * reordering, around one noise-substituted band and an order 4 TNS filter.
     *
     * @param seconds the audio decoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload aacResilienceToolsDecode(double seconds) {
        var frames = (int) Math.ceil(seconds * 44100 / 1024);
        var packets = new ArrayList<Packet>(frames);
        for (var frame = 0; frame < frames; frame++) {
            var bits = new PacketBits();
            bits.write(0, 4);
            bits.write(100, 8);
            bits.write(0, 3);
            bits.write(frame & 1, 1);
            bits.write(40, 6);
            bits.write(0, 1);
            // five-bit codebooks: 39 zero bands, then one noise band
            bits.write(0, 5);
            bits.write(31, 5);
            bits.write(8, 5);
            bits.write(13, 5);
            bits.write(1, 5);
            // RVLC side information: no concealment, the reverse global gain, nine codeword bits taken
            // by the noise position, the first noise energy, no escapes, and the last noise energy
            // (equal to the first, so the forward and reverse decodes agree)
            var noise = 256 + 40 + (frame & 7);
            bits.write(0, 1);
            bits.write(100, 8);
            bits.write(9, 9);
            bits.write(noise, 9);
            bits.write(0, 1);
            bits.write(noise, 9);
            bits.write(0, 1);
            bits.write(1, 1);
            bits.write(0, 1);
            // codeword reordering of an empty spectrum
            bits.write(0, 14);
            bits.write(0, 6);
            aacTnsData(bits);
            packets.add(new Packet(1, bits.toByteArray(), frame * 1024L, frame * 1024L, 1024, true));
        }
        // object type 17, sampling frequency index 4 (44.1 kHz), mono, the extension flag, all three
        // resilience flags and epConfig 0
        var config = new byte[]{(byte) 0x8A, 0x09, (byte) 0xE0};
        var track = AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(new Rational(1, 44100)).sampleRate(44100)
                .layout(ChannelLayout.MONO).codecConfig(config).build();
        return AudioStreams.decode("aac-er-lc-tools-decode", packets, new AacDecoder(track));
    }

    /**
     * Returns a workload that decodes synthetic mono AAC-LD packets of 512 samples at 44.1 kHz
     * whose long term prediction, with the low delay lag, is on in the 30 bands it covers,
     * alternating the sine and the low overlap windows, with a noise-substituted band and an
     * order 4 TNS filter.
     *
     * @param seconds the audio decoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload aacLowDelayDecode(double seconds) {
        var frames = (int) Math.ceil(seconds * 44100 / 512);
        var packets = new ArrayList<Packet>(frames);
        for (var frame = 0; frame < frames; frame++) {
            var bits = new PacketBits();
            bits.write(0, 4);
            bits.write(100, 8);
            bits.write(0, 3);
            bits.write(frame & 1, 1);
            bits.write(30, 6);
            bits.write(1, 1);
            bits.write(1, 1);
            // a new lag every other frame, the previous one otherwise
            var update = (frame & 1) == 0;
            bits.write(update ? 1 : 0, 1);
            if (update) {
                bits.write(128 + frame * 37 % 768, 10);
            }
            bits.write(frame & 3, 3);
            bits.write(-1, 30);
            bits.write(0, 4);
            bits.write(29, 5);
            bits.write(13, 4);
            bits.write(1, 5);
            bits.write(256 + 40, 9);
            bits.write(0, 1);
            bits.write(1, 1);
            bits.write(0, 1);
            aacTnsData(bits);
            packets.add(new Packet(1, bits.toByteArray(), frame * 512L, frame * 512L, 512, true));
        }
        // object type 23, sampling frequency index 4 (44.1 kHz), mono, 512-sample frames, the
        // extension flag, no resilience tools and epConfig 0
        var config = new byte[]{(byte) 0xBA, 0x09, 0x00};
        var track = AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(new Rational(1, 44100)).sampleRate(44100)
                .layout(ChannelLayout.MONO).codecConfig(config).build();
        return AudioStreams.decode("aac-ld-decode", packets, new AacDecoder(track));
    }

    /**
     * Returns a workload that decodes synthetic mono AAC-ELD packets through the low delay
     * filterbank: 480-sample frames at 48 kHz, or, with {@code sbr}, 512-sample frames of a 24 kHz
     * core with low delay spectral band replication at dual rate. Each core frame holds a
     * noise-substituted band and an order 4 TNS filter; the SBR payload codes one envelope and
     * one noise floor of zero deltas, which runs the low delay QMF banks, the HF generator and
     * the envelope adjustment.
     *
     * @param name the name of the workload
     * @param sbr whether the stream carries low delay spectral band replication
     * @param seconds the audio decoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload aacEnhancedLowDelayDecode(String name, boolean sbr, double seconds) {
        var rate = sbr ? 24000 : 48000;
        var frameLength = sbr ? 512 : 480;
        var frames = (int) Math.ceil(seconds * rate / frameLength);
        var packets = new ArrayList<Packet>(frames);
        for (var frame = 0; frame < frames; frame++) {
            var bits = new PacketBits();
            bits.write(100, 8);
            bits.write(30, 6);
            bits.write(0, 4);
            bits.write(29, 5);
            bits.write(13, 4);
            bits.write(1, 5);
            bits.write(256 + 40 + (frame & 7), 9);
            bits.write(1, 1);
            aacTnsData(bits);
            if (sbr) {
                // no header, no extra data, then a FIXFIX grid of one envelope and zero codes; the
                // padding keeps every field of the payload inside the packet
                bits.write(0, 2);
                bits.write(0, 40);
                bits.write(0, 40);
                bits.write(0, 40);
                bits.write(0, 40);
                bits.write(0, 40);
            }
            packets.add(new Packet(1, bits.toByteArray(), (long) frame * frameLength, (long) frame * frameLength,
                    frameLength, true));
        }
        var config = new PacketBits();
        config.write(31, 5);
        config.write(39 - 32, 6);
        config.write(sbr ? 6 : 3, 4);
        config.write(1, 4);
        config.write(sbr ? 0 : 1, 1);
        config.write(0, 3);
        config.write(sbr ? 1 : 0, 1);
        if (sbr) {
            // dual rate without CRC; sbr_header: 3 dB amplitude resolution, start frequency 12, stop
            // frequency 9, crossover band 0, then the first extra header (frequency scale 1, alter
            // scale on, two noise bands)
            config.write(1, 1);
            config.write(0, 1);
            config.write(1, 1);
            config.write(12, 4);
            config.write(9, 4);
            config.write(0, 3);
            config.write(0, 2);
            config.write(1, 1);
            config.write(0, 1);
            config.write(1, 2);
            config.write(1, 1);
            config.write(2, 2);
        }
        config.write(0, 4);
        config.write(0, 2);
        var track = AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(new Rational(1, sbr ? 2 * rate : rate))
                .sampleRate(sbr ? 2 * rate : rate).layout(ChannelLayout.MONO).codecConfig(config.toByteArray())
                .build();
        return AudioStreams.decode(name, packets, new AacDecoder(track));
    }

    /**
     * Writes the TNS data of a long window: one order 4 filter over the lowest 40 bands.
     *
     * @param bits the packet being written
     */
    private static void aacTnsData(PacketBits bits) {
        bits.write(1, 2);
        bits.write(1, 1);
        bits.write(40, 6);
        bits.write(4, 5);
        bits.write(0, 2);
        bits.write(0x5C39, 16);
    }

    /**
     * Collects the bits of a synthetic packet, most significant bit first.
     */
    private static final class PacketBits {
        /**
         * The bytes written so far.
         */
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        /**
         * The pending bits, in the low {@link #count} bits.
         */
        private long pending;

        /**
         * The number of pending bits, below 8 between calls.
         */
        private int count;

        /**
         * Appends the low bits of a value.
         *
         * @param value the value, of which only the low {@code width} bits are written
         * @param width the number of bits, from 1 to 48
         */
        void write(long value, int width) {
            pending = pending << width | value & ((1L << width) - 1);
            count += width;
            while (count >= 8) {
                count -= 8;
                bytes.write((int) (pending >>> count));
            }
        }

        /**
         * {@return the bytes written, the last one padded with zero bits}
         */
        byte[] toByteArray() {
            if (count > 0) {
                write(0, 8 - count);
            }
            return bytes.toByteArray();
        }
    }

    /**
     * Returns a workload that encodes stereo music as 48 kHz AC-3 at 192 kbit/s with the full bit
     * allocation search and decodes every frame back, one encoder and one decoder across rounds.
     *
     * @param seconds the music coded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload ac3(double seconds) {
        var music = SyntheticAudio.music(48000, seconds);
        var encoder = new Ac3Encoder(Ac3EncoderOptions.of(48000, ChannelLayout.STEREO).toBuilder().bitrate(192000)
                .build());
        var decoder = new Ac3Decoder(AudioStreams.track(Codec.AC3, 48000, ChannelLayout.STEREO, null));
        var block = new AudioBuffer(ChannelLayout.STEREO, 48000, AudioStreams.BLOCK_FRAMES);
        Consumer<AudioBuffer> decoded = AudioStreams::consume;
        Consumer<Packet> output = packet -> decoder.decode(packet, decoded);
        return TrainingWorkload.of("ac3", () -> SyntheticAudio.feed(music, block, buffer -> encoder.encode(buffer, output)));
    }

    /**
     * Returns a workload that encodes stereo music as 48 kHz AC-3 at 192 kbit/s with the faster
     * bit allocation search of the real-time preset, one encoder across rounds.
     *
     * @param seconds the music encoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload ac3Realtime(double seconds) {
        var encoder = new Ac3Encoder(Ac3EncoderOptions.forRealtime().toBuilder().bitrate(192000).build());
        return AudioStreams.encode("ac3-encode-rt", SyntheticAudio.music(48000, seconds), ChannelLayout.STEREO, 48000,
                encoder);
    }

    /**
     * Returns a workload that encodes stereo music as 48 kHz E-AC-3 at 128 kbit/s, with spectral
     * extension, and decodes every frame back, one encoder and one decoder across rounds.
     *
     * @param name    the name of the workload
     * @param options the encoder settings: the one-block frames of the real-time preset or the
     *                six-block frames of the defaults
     * @param seconds the music coded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload eac3(String name, Eac3EncoderOptions options, double seconds) {
        var music = SyntheticAudio.music(48000, seconds);
        var encoder = new Eac3Encoder(options.toBuilder().bitrate(128000).build());
        var decoder = new Ac3Decoder(AudioStreams.track(Codec.EAC3, 48000, ChannelLayout.STEREO, null));
        var block = new AudioBuffer(ChannelLayout.STEREO, 48000, AudioStreams.BLOCK_FRAMES);
        Consumer<AudioBuffer> decoded = AudioStreams::consume;
        Consumer<Packet> output = packet -> decoder.decode(packet, decoded);
        return TrainingWorkload.of(name, () -> SyntheticAudio.feed(music, block, buffer -> encoder.encode(buffer, output)));
    }

    /**
     * Returns a workload that reads the AC-3 and E-AC-3 IEC 61937 bursts of two WAVE files, every
     * access unit and then those after a seek to the middle, from frames the encoders code once.
     *
     * @param seconds the music coded in each file, in seconds
     * @return the workload
     */
    private static TrainingWorkload iec61937(double seconds) {
        var music = SyntheticAudio.music(48000, seconds);
        var ac3 = new ArrayList<Packet>();
        var ac3Encoder = new Ac3Encoder(Ac3EncoderOptions.of(48000, ChannelLayout.STEREO).toBuilder().bitrate(192000)
                .build());
        SyntheticAudio.feed(music, new AudioBuffer(ChannelLayout.STEREO, 48000, AudioStreams.BLOCK_FRAMES),
                buffer -> ac3Encoder.encode(buffer, ac3::add));
        ac3Encoder.flush(ac3::add);
        var eac3 = new ArrayList<Packet>();
        var eac3Encoder = new Eac3Encoder(Eac3EncoderOptions.defaults().toBuilder().bitrate(128000).build());
        SyntheticAudio.feed(music, new AudioBuffer(ChannelLayout.STEREO, 48000, AudioStreams.BLOCK_FRAMES),
                buffer -> eac3Encoder.encode(buffer, eac3::add));
        eac3Encoder.flush(eac3::add);
        var files = List.of(burstWave(ac3, 1, 6144, 48000), burstWave(eac3, 21, 24576, 192000));
        var middle = (long) (seconds * 500_000);
        return TrainingWorkload.of("iec61937-wav", () -> {
            for (var file : files) {
                try (var demuxer = new WavDemuxer(MediaSource.of(file))) {
                    for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                        Blackhole.consume(packet.size());
                    }
                    demuxer.seek(middle);
                    for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                        Blackhole.consume(packet.pts());
                    }
                }
            }
        });
    }

    /**
     * Returns a workload that reads the raw data blocks of AAC in two WAVE files, unframed (each block's end
     * found by walking its syntax) and in IEC 61937 bursts of ADTS frames, every block and then those after a
     * seek to the middle, from blocks the encoder codes once.
     *
     * @param seconds the music coded in each file, in seconds
     * @return the workload
     */
    private static TrainingWorkload aacWave(double seconds) {
        var encoder = new AacEncoder(AacEncoderOptions.of(48000, ChannelLayout.STEREO, 128000));
        var packets = AudioStreams.encodeAll(encoder, SyntheticAudio.music(48000, seconds), ChannelLayout.STEREO, 48000);
        var config = encoder.codecConfig().orElseThrow();
        var raw = new ByteArrayOutputStream();
        var adts = new ArrayList<Packet>();
        for (var packet : packets) {
            raw.writeBytes(packet.data());
            var length = packet.size() + 7;
            var frame = new byte[length];
            // an ADTS header of MPEG-4 AAC LC, 48 kHz, stereo, without CRC
            frame[0] = (byte) 0xFF;
            frame[1] = (byte) 0xF1;
            frame[2] = 0x4C;
            frame[3] = (byte) (0x80 | length >> 11);
            frame[4] = (byte) (length >> 3);
            frame[5] = (byte) ((length & 7) << 5 | 0x1F);
            frame[6] = (byte) 0xFC;
            System.arraycopy(packet.data(), 0, frame, 7, packet.size());
            adts.add(new Packet(packet.trackId(), frame, packet.pts(), packet.dts(), packet.duration(), true));
        }
        var data = raw.toByteArray();
        var format = ByteBuffer.allocate(18 + config.length).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 0x00FF)
                .putShort((short) 2).putInt(48000).putInt(16000).putShort((short) 1).putShort((short) 16)
                .putShort((short) config.length).put(config).array();
        var header = ByteBuffer.allocate(20 + format.length + 8).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(12 + format.length + 8 + data.length)
                .put(new byte[]{'W', 'A', 'V', 'E'}).put(new byte[]{'f', 'm', 't', ' '}).putInt(format.length).put(format)
                .put(new byte[]{'d', 'a', 't', 'a'}).putInt(data.length);
        var unframed = new ByteArrayOutputStream();
        unframed.writeBytes(header.array());
        unframed.writeBytes(data);
        var files = List.of(unframed.toByteArray(), burstWave(adts, 7, 4096, 48000));
        var middle = (long) (seconds * 500_000);
        return TrainingWorkload.of("aac-wav", () -> {
            for (var file : files) {
                try (var demuxer = new WavDemuxer(MediaSource.of(file))) {
                    for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                        Blackhole.consume(packet.size());
                    }
                    demuxer.seek(middle);
                    for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                        Blackhole.consume(packet.pts());
                    }
                }
            }
        });
    }

    /**
     * Returns a 16-bit stereo WAVE file whose samples carry one IEC 61937 burst per frame.
     *
     * @param frames the AC-3, E-AC-3 or ADTS frames
     * @param type the IEC 61937 data type, 1 for AC-3, 7 for AAC and 21 for E-AC-3
     * @param period the repetition period, in bytes
     * @param rate the sampling rate of the samples
     * @return the file
     */
    private static byte[] burstWave(List<Packet> frames, int type, int period, int rate) {
        var data = ByteBuffer.allocate(frames.size() * period).order(ByteOrder.LITTLE_ENDIAN);
        for (var index = 0; index < frames.size(); index++) {
            var frame = frames.get(index).data();
            data.position(index * period);
            data.putShort((short) 0xF872).putShort((short) 0x4E1F).putShort((short) type)
                    .putShort((short) (type == 21 ? frame.length : frame.length * 8));
            for (var offset = 0; offset < frame.length; offset += 2) {
                data.put(offset + 1 < frame.length ? frame[offset + 1] : 0).put(frame[offset]);
            }
        }
        var header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + data.capacity()).put(new byte[]{'W', 'A', 'V', 'E'})
                .put(new byte[]{'f', 'm', 't', ' '}).putInt(16).putShort((short) 1).putShort((short) 2).putInt(rate)
                .putInt(rate * 4).putShort((short) 4).putShort((short) 16)
                .put(new byte[]{'d', 'a', 't', 'a'}).putInt(data.capacity());
        var file = new ByteArrayOutputStream(44 + data.capacity());
        file.writeBytes(header.array());
        file.writeBytes(data.array());
        return file.toByteArray();
    }

    /**
     * Returns a workload that encodes speech as AMR, switching the mode every round through the
     * codec's modes in turn.
     *
     * @param name    the name of the workload
     * @param codec   the codec, {@link Codec#AMR_NB} or {@link Codec#AMR_WB}
     * @param seconds the speech encoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload amrEncode(String name, Codec codec, double seconds) {
        var wide = codec == Codec.AMR_WB;
        var rate = wide ? 16000 : 8000;
        var speech = new float[][]{SyntheticAudio.speech(rate, seconds)};
        var block = new AudioBuffer(ChannelLayout.MONO, rate, wide ? 320 : 160);
        var encoder = new AmrEncoder(wide ? AmrEncoderOptions.wideband() : AmrEncoderOptions.narrowband());
        var modes = Arrays.stream(AmrMode.values()).filter(mode -> mode.codec() == codec).toList();
        int[] round = {0};
        Consumer<Packet> output = packet -> Blackhole.consume(packet.size());
        return TrainingWorkload.of(name, () -> {
            encoder.setMode(modes.get(modes.size() - 1 - round[0]++ % modes.size()));
            SyntheticAudio.feed(speech, block, buffer -> encoder.encode(buffer, output));
        });
    }

    /**
     * Returns a workload that decodes AMR speech coded in every mode of the codec in turn, one
     * decoder across rounds.
     *
     * @param name    the name of the workload
     * @param codec   the codec, {@link Codec#AMR_NB} or {@link Codec#AMR_WB}
     * @param seconds the speech decoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload amrDecode(String name, Codec codec, double seconds) {
        var wide = codec == Codec.AMR_WB;
        var rate = wide ? 16000 : 8000;
        var speech = SyntheticAudio.speech(rate, seconds);
        var block = new AudioBuffer(ChannelLayout.MONO, rate, wide ? 320 : 160);
        var encoder = new AmrEncoder(wide ? AmrEncoderOptions.wideband() : AmrEncoderOptions.narrowband());
        var modes = Arrays.stream(AmrMode.values()).filter(mode -> mode.codec() == codec).toList();
        var packets = new ArrayList<Packet>();
        for (var mode : modes) {
            encoder.setMode(mode);
            SyntheticAudio.feed(new float[][]{speech}, block, buffer -> encoder.encode(buffer, packets::add));
        }
        encoder.flush(packets::add);
        var track = AudioTrack.builder().id(1).codec(codec).timeBase(new Rational(1, rate)).sampleRate(rate)
                .layout(ChannelLayout.MONO).build();
        return AudioStreams.decode(name, packets, new AmrDecoder(track));
    }
}
