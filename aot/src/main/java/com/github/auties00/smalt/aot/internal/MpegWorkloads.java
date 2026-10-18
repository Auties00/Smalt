package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.aot.TrainingFamily;
import com.github.auties00.smalt.aot.TrainingWorkload;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.aac.AacDecoder;
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
        // TODO: train the HEVC, HEIC, MPEG-4 Part 2 and H.263 decoders, which need streams that no Smalt
        //  encoder produces
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
                file("aac-encode", () -> aacEncode(6)),
                file("aac-decode", () -> aacDecode("aac-decode", false, false, 6)),
                file("heaac-decode", () -> aacDecode("heaac-decode", true, false, 6)),
                file("heaacv2-decode", () -> aacDecode("heaacv2-decode", true, true, 6)),
                file("ac3", () -> ac3(6)),
                file("eac3", () -> eac3("eac3", Eac3EncoderOptions.defaults(), 6)),
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
