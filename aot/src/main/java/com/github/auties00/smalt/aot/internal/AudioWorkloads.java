package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.aot.TrainingFamily;
import com.github.auties00.smalt.aot.TrainingWorkload;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.IntegerAudioBuffer;
import com.github.auties00.smalt.audio.adpcm.AdpcmDecoder;
import com.github.auties00.smalt.audio.adpcm.AdpcmEncoder;
import com.github.auties00.smalt.audio.adpcm.AdpcmEncoderOptions;
import com.github.auties00.smalt.audio.alac.AlacDecoder;
import com.github.auties00.smalt.audio.alac.AlacEncoder;
import com.github.auties00.smalt.audio.alac.AlacEncoderOptions;
import com.github.auties00.smalt.audio.flac.FlacDecoder;
import com.github.auties00.smalt.audio.flac.FlacEncoder;
import com.github.auties00.smalt.audio.flac.FlacEncoderOptions;
import com.github.auties00.smalt.audio.g722.G722Decoder;
import com.github.auties00.smalt.audio.g722.G722Encoder;
import com.github.auties00.smalt.audio.g726.G726Decoder;
import com.github.auties00.smalt.audio.g726.G726Encoder;
import com.github.auties00.smalt.audio.g726.G726EncoderOptions;
import com.github.auties00.smalt.audio.gsm.GsmDecoder;
import com.github.auties00.smalt.audio.gsm.GsmEncoder;
import com.github.auties00.smalt.audio.mp3.Mp2Encoder;
import com.github.auties00.smalt.audio.mp3.Mp2EncoderOptions;
import com.github.auties00.smalt.audio.mp3.Mp3Decoder;
import com.github.auties00.smalt.audio.mp3.Mp3Encoder;
import com.github.auties00.smalt.audio.mp3.Mp3EncoderOptions;
import com.github.auties00.smalt.audio.mp3.Mp3StereoMode;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.opus.OpusApplication;
import com.github.auties00.smalt.audio.opus.OpusChannelMapping;
import com.github.auties00.smalt.audio.opus.OpusDecoder;
import com.github.auties00.smalt.audio.opus.OpusDecoderOptions;
import com.github.auties00.smalt.audio.opus.OpusEncoder;
import com.github.auties00.smalt.audio.opus.OpusEncoderOptions;
import com.github.auties00.smalt.audio.opus.OpusSignal;
import com.github.auties00.smalt.audio.voice.AnalogGainControllerOptions;
import com.github.auties00.smalt.audio.voice.ClippingPrediction;
import com.github.auties00.smalt.audio.voice.EchoCancellation;
import com.github.auties00.smalt.audio.voice.GainControl;
import com.github.auties00.smalt.audio.voice.LevelControlOptions;
import com.github.auties00.smalt.audio.voice.VoiceProcessor;
import com.github.auties00.smalt.audio.voice.VoiceProcessorOptions;
import com.github.auties00.smalt.audio.vorbis.VorbisDecoder;
import com.github.auties00.smalt.audio.vorbis.VorbisEncoder;
import com.github.auties00.smalt.audio.vorbis.VorbisEncoderOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static com.github.auties00.smalt.aot.internal.WorkloadFactory.file;
import static com.github.auties00.smalt.aot.internal.WorkloadFactory.realtime;

/**
 * Creates the workloads of {@link TrainingFamily#AUDIO}, the codecs of {@code smalt-audio}: Opus,
 * Vorbis, FLAC, ALAC, MP3, MP2, IMA and Microsoft ADPCM, G.722, G.726 and GSM 06.10 encoding and decoding, and the
 * voice processor, each on synthetic speech or
 * music and keeping its codecs across rounds as a long-running application does.
 */
final class AudioWorkloads {
    /**
     * Prevents instantiation.
     */
    private AudioWorkloads() {
        throw new AssertionError();
    }

    /**
     * Returns the factories of the family's workloads.
     *
     * @return the factories, in order
     */
    static List<WorkloadFactory> factories() {
        return List.of(
                realtime("voice-processor", () -> voiceProcessor(6)),
                realtime("voice-processor-layouts", () -> voiceProcessorLayouts(4)),
                realtime("opus-rt-16000-20", () -> opusRealtime("opus-rt-16000-20", 6, 16000, 20)),
                realtime("opus-rt-48000-20", () -> opusRealtime("opus-rt-48000-20", 6, 48000, 20)),
                realtime("opus-rt-48000-10", () -> opusRealtime("opus-rt-48000-10", 6, 48000, 10)),
                realtime("opus-rt-16000-10", () -> opusRealtime("opus-rt-16000-10", 6, 16000, 10)),
                realtime("mp3-encode-rt", () -> mp3Encode("mp3-encode-rt", Mp3EncoderOptions.forRealtime(), 6)),
                realtime("alac-encode-rt", () -> alacEncode("alac-encode-rt", AlacEncoderOptions.forSpeed(), 4)),
                realtime("mp3-encode-retarget", () -> mp3Retarget(6)),
                realtime("mp3-decode-loss", () -> lossyDecode("mp3-decode-loss", Codec.MP3, 4)),
                realtime("vorbis-decode-loss", () -> lossyDecode("vorbis-decode-loss", Codec.VORBIS, 4)),
                realtime("flac-decode-loss", () -> lossyDecode("flac-decode-loss", Codec.FLAC, 4)),
                realtime("g722-encode", () -> telephonyEncode("g722-encode", new G722Encoder(), 6)),
                realtime("g722-decode", () -> telephonyDecode("g722-decode", new G722Encoder(), 6)),
                realtime("g726-encode", () -> telephonyEncode("g726-encode", new G726Encoder(
                        G726EncoderOptions.ofCodeSize(4)), 6)),
                realtime("g726-decode-16k", () -> telephonyDecode("g726-decode-16k", new G726Encoder(
                        G726EncoderOptions.ofCodeSize(2)), 6)),
                realtime("g726-decode-40k", () -> telephonyDecode("g726-decode-40k", new G726Encoder(
                        G726EncoderOptions.ofCodeSize(5)), 6)),
                realtime("gsm-encode", () -> telephonyEncode("gsm-encode", new GsmEncoder(Codec.GSM), 6)),
                realtime("gsm-decode", () -> telephonyDecode("gsm-decode", new GsmEncoder(Codec.GSM_MS), 6)),
                file("opus-encode-speech", () -> opusSpeech(8)),
                file("opus-encode-music", () -> opusMusic(6)),
                file("opus-decode", () -> opusDecode(6)),
                file("opus-encode-surround", () -> opusSurround(3)),
                file("opus-decode-surround", () -> opusSurroundDecode(3)),
                file("opus-encode-projection", () -> opusProjection(2)),
                file("opus-decode-projection", () -> opusProjectionDecode(2)),
                file("vorbis-encode", () -> vorbisEncode(2)),
                file("vorbis-encode-managed", () -> vorbisEncodeManaged(1)),
                file("vorbis-decode", () -> vorbisDecode(6)),
                file("flac-encode", () -> flacEncode("flac-encode", FlacEncoderOptions.defaults(), 44100, 6)),
                file("flac-encode-24", () -> flacEncode("flac-encode-24", FlacEncoderOptions.ofCompressionLevel(8)
                        .toBuilder().format(48000, ChannelLayout.STEREO).bitsPerSample(24).build(), 48000, 4)),
                file("flac-decode", () -> flacDecode(6)),
                file("alac-encode", () -> alacEncode("alac-encode", AlacEncoderOptions.defaults(), 4)),
                file("alac-encode-surround", () -> alacSurround(2)),
                file("alac-decode", () -> alacDecode(6)),
                file("flac-decode-32", () -> flacDecodeIntegers(3)),
                file("mp3-encode", () -> mp3Encode("mp3-encode", Mp3EncoderOptions.defaults(), 6)),
                file("mp3-encode-vbr", () -> mp3Encode("mp3-encode-vbr", Mp3EncoderOptions.ofVariableBitrate(2), 6)),
                file("mp3-decode", AudioWorkloads::mp3Decode),
                file("mp2-encode", () -> mp2Encode("mp2-encode", Mp2EncoderOptions.defaults(), 6)),
                file("mp2-encode-joint", () -> mp2Encode("mp2-encode-joint", Mp2EncoderOptions.builder()
                        .bitrate(128_000).stereoMode(Mp3StereoMode.JOINT_STEREO).crc(true).build(), 4)),
                file("mp1-encode", () -> mp2Encode("mp1-encode", Mp2EncoderOptions.builder().layer(1).build(), 3)),
                file("mp2-decode", AudioWorkloads::mp2Decode),
                file("adpcm-ima-encode", () -> adpcmEncode("adpcm-ima-encode", Codec.ADPCM_IMA_WAV, 4)),
                file("adpcm-ima4-encode", () -> adpcmEncode("adpcm-ima4-encode", Codec.ADPCM_IMA_QT, 4)),
                file("adpcm-ms-encode", () -> adpcmEncode("adpcm-ms-encode", Codec.ADPCM_MS, 2)),
                file("adpcm-ima-decode", () -> adpcmDecode("adpcm-ima-decode", Codec.ADPCM_IMA_WAV, 6)),
                file("adpcm-ima4-decode", () -> adpcmDecode("adpcm-ima4-decode", Codec.ADPCM_IMA_QT, 6)),
                file("adpcm-ms-decode", () -> adpcmDecode("adpcm-ms-decode", Codec.ADPCM_MS, 6)));
    }

    /**
     * Returns a workload that encodes speech with a telephony encoder (G.722, G.726 or GSM 06.10) at its own rate,
     * one encoder across rounds.
     *
     * @param name the name of the workload
     * @param encoder the encoder, of mono input
     * @param seconds the speech encoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload telephonyEncode(String name, AudioEncoder encoder, double seconds) {
        var rate = encoder.sampleRate();
        return AudioStreams.encode(name, new float[][]{SyntheticAudio.speech(rate, seconds)}, ChannelLayout.MONO,
                rate, encoder);
    }

    /**
     * Returns a workload that decodes speech a telephony encoder produced, one decoder across rounds.
     *
     * @param name the name of the workload
     * @param encoder the encoder of the stream, of mono input, closed once the stream is coded
     * @param seconds the speech decoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload telephonyDecode(String name, AudioEncoder encoder, double seconds) {
        var rate = encoder.sampleRate();
        var packets = AudioStreams.encodeAll(encoder, new float[][]{SyntheticAudio.speech(rate, seconds)},
                ChannelLayout.MONO, rate);
        var track = AudioTrack.builder().id(1).codec(encoder.codec()).timeBase(Rational.timeBaseOf(rate))
                .sampleRate(rate).layout(ChannelLayout.MONO).bitsPerSample(encoder.bitsPerSample()).build();
        encoder.close();
        AudioDecoder decoder = switch (encoder.codec()) {
            case ADPCM_G722 -> new G722Decoder(track);
            case ADPCM_G726 -> new G726Decoder(track);
            default -> new GsmDecoder(track);
        };
        return AudioStreams.decode(name, packets, decoder);
    }

    /**
     * Returns a workload that encodes 44.1 kHz stereo music as IMA or Microsoft ADPCM, one encoder across rounds.
     *
     * @param name the name of the workload
     * @param codec the layout: {@link Codec#ADPCM_IMA_WAV}, {@link Codec#ADPCM_IMA_QT} or {@link Codec#ADPCM_MS}
     * @param seconds the music encoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload adpcmEncode(String name, Codec codec, double seconds) {
        return AudioStreams.encode(name, SyntheticAudio.music(44100, seconds), ChannelLayout.STEREO, 44100,
                new AdpcmEncoder(AdpcmEncoderOptions.of(codec, 44100, ChannelLayout.STEREO)));
    }

    /**
     * Returns a workload that decodes 44.1 kHz stereo music that Smalt's own IMA or Microsoft ADPCM encoder
     * produced, one decoder across rounds.
     *
     * @param name the name of the workload
     * @param codec the layout: {@link Codec#ADPCM_IMA_WAV}, {@link Codec#ADPCM_IMA_QT} or {@link Codec#ADPCM_MS}
     * @param seconds the music decoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload adpcmDecode(String name, Codec codec, double seconds) {
        var encoder = new AdpcmEncoder(AdpcmEncoderOptions.of(codec, 44100, ChannelLayout.STEREO));
        var packets = AudioStreams.encodeAll(encoder, SyntheticAudio.music(44100, seconds), ChannelLayout.STEREO,
                44100);
        var track = AudioTrack.builder().id(1).codec(codec).timeBase(Rational.timeBaseOf(44100)).sampleRate(44100)
                .layout(ChannelLayout.STEREO).bitsPerSample(4).codecConfig(encoder.codecConfig().orElse(null))
                .build();
        encoder.close();
        return AudioStreams.decode(name, packets, new AdpcmDecoder(track));
    }

    /**
     * Returns a workload that encodes 44.1 kHz stereo music as MP3 at an average bitrate whose target changes every
     * second, one encoder across rounds.
     *
     * @param seconds the music encoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload mp3Retarget(double seconds) {
        var encoder = new Mp3Encoder(Mp3EncoderOptions.builder().format(44100, ChannelLayout.STEREO).abr(128_000)
                .build());
        return AudioStreams.encodeRetargeted("mp3-encode-retarget", SyntheticAudio.music(44100, seconds),
                ChannelLayout.STEREO, 44100, encoder, 96_000, 160_000, 64_000, 128_000);
    }

    /**
     * Returns a workload that decodes 44.1 kHz stereo music that Smalt's own encoder produced, concealing every
     * seventh packet as a lost one, one decoder across rounds.
     *
     * @param name the name of the workload
     * @param codec the codec: MP3, Vorbis or FLAC
     * @param seconds the music decoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload lossyDecode(String name, Codec codec, double seconds) {
        var encoder = switch (codec) {
            case MP3 -> new Mp3Encoder();
            case VORBIS -> new VorbisEncoder(VorbisEncoderOptions.of(44100, ChannelLayout.STEREO));
            case FLAC -> new FlacEncoder(FlacEncoderOptions.defaults());
            default -> throw new IllegalArgumentException(codec.toString());
        };
        var packets = AudioStreams.encodeAll(encoder, SyntheticAudio.music(44100, seconds), ChannelLayout.STEREO, 44100);
        var config = encoder.codecConfig();
        var track = config.isPresent() ? AudioStreams.track(codec, 44100, ChannelLayout.STEREO, config.get())
                : AudioTrack.builder().id(1).codec(codec).timeBase(Rational.timeBaseOf(44100)).sampleRate(44100)
                        .layout(ChannelLayout.STEREO).build();
        encoder.close();
        return AudioStreams.decodeLossy(name, packets, switch (codec) {
            case MP3 -> new Mp3Decoder(track);
            case VORBIS -> new VorbisDecoder(track);
            default -> new FlacDecoder(track);
        });
    }

    /**
     * Returns a workload that encodes speech as a file: 16 kHz mono Opus with the speech preset,
     * one encoder across rounds.
     *
     * @param seconds the speech encoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload opusSpeech(double seconds) {
        var speech = new float[][]{SyntheticAudio.speech(16000, seconds)};
        var encoder = new OpusEncoder(OpusEncoderOptions.forSpeech(16000));
        return AudioStreams.encode("opus-encode-speech", speech, ChannelLayout.MONO, 16000, encoder);
    }

    /**
     * Returns a workload that encodes stereo music as a 48 kHz Opus file with the default
     * settings, one encoder across rounds.
     *
     * @param seconds the music encoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload opusMusic(double seconds) {
        var encoder = new OpusEncoder(OpusEncoderOptions.builder().format(48000, 2).build());
        return AudioStreams.encode("opus-encode-music", SyntheticAudio.music(48000, seconds), ChannelLayout.STEREO,
                48000, encoder);
    }

    /**
     * Synthesizes 5.1 content in WAVE order: music in front and back, speech in the center and the
     * music's bass in the LFE channel.
     *
     * @param seconds the duration in seconds
     * @return the six planes at 48 kHz
     */
    private static float[][] surround(double seconds) {
        var music = SyntheticAudio.music(48000, seconds);
        var speech = SyntheticAudio.speech(48000, seconds);
        var frames = music[0].length;
        var lfe = new float[frames];
        var back = new float[2][frames];
        var smooth = 0f;
        for (var i = 0; i < frames; i++) {
            smooth += 0.01f * (music[0][i] + music[1][i] - smooth);
            lfe[i] = smooth;
            var delayed = Math.max(0, i - 480);
            back[0][i] = 0.6f * music[1][delayed];
            back[1][i] = 0.6f * music[0][delayed];
        }
        return new float[][]{music[0], music[1], speech, lfe, back[0], back[1]};
    }

    /**
     * Returns a workload that encodes 5.1 content as a 48 kHz Opus multistream at 256 kb/s, which
     * runs the surround masking analysis and the LFE stream, one encoder across rounds.
     *
     * @param seconds the content encoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload opusSurround(double seconds) {
        var encoder = new OpusEncoder(OpusEncoderOptions.builder().format(48000, ChannelLayout.SURROUND_5_1)
                .bitrate(256000).build());
        return AudioStreams.encode("opus-encode-surround", surround(seconds), ChannelLayout.SURROUND_5_1, 48000,
                encoder);
    }

    /**
     * Returns a workload that decodes a 5.1 Opus multistream that Smalt's own encoder produced, one
     * decoder across rounds.
     *
     * @param seconds the content decoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload opusSurroundDecode(double seconds) {
        var encoder = new OpusEncoder(OpusEncoderOptions.builder().format(48000, ChannelLayout.SURROUND_5_1)
                .bitrate(256000).build());
        var packets = AudioStreams.encodeAll(encoder, surround(seconds), ChannelLayout.SURROUND_5_1, 48000);
        var track = AudioStreams.track(Codec.OPUS, 48000, ChannelLayout.SURROUND_5_1,
                encoder.codecConfig().orElseThrow());
        encoder.close();
        return AudioStreams.decode("opus-decode-surround", packets, new OpusDecoder(track));
    }

    /**
     * The layout of second-order ambisonics with a non-diegetic stereo pair: eleven channels of
     * unknown position.
     */
    private static final ChannelLayout AMBISONIC_11 = new ChannelLayout(Collections.nCopies(11, Speaker.UNKNOWN));

    /**
     * Synthesizes second-order ambisonics (ACN order, SN3D) of music panned around the listener, with
     * speech as the non-diegetic stereo pair.
     *
     * @param seconds the duration in seconds
     * @return the eleven planes at 48 kHz
     */
    private static float[][] ambisonic(double seconds) {
        var music = SyntheticAudio.music(48000, seconds);
        var speech = SyntheticAudio.speech(48000, seconds);
        var frames = music[0].length;
        var planes = new float[11][frames];
        for (var i = 0; i < frames; i++) {
            var azimuth = 2 * Math.PI * i / (4.0 * 48000);
            var source = 0.5f * (music[0][i] + music[1][i]);
            var x = (float) StrictMath.cos(azimuth);
            var y = (float) StrictMath.sin(azimuth);
            planes[0][i] = source;
            planes[1][i] = y * source;
            planes[3][i] = x * source;
            planes[4][i] = 0.866f * 2 * x * y * source;
            planes[6][i] = -0.5f * source;
            planes[8][i] = 0.866f * (x * x - y * y) * source;
            planes[9][i] = speech[i];
            planes[10][i] = 0.8f * speech[i];
        }
        return planes;
    }

    /**
     * Returns a workload that encodes second-order ambisonics with a non-diegetic pair as an Opus
     * projection (mapping family 3) stream at 384 kb/s, which mixes the channels with the
     * projection matrix, one encoder across rounds.
     *
     * @param seconds the content encoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload opusProjection(double seconds) {
        var encoder = new OpusEncoder(OpusEncoderOptions.builder().format(48000, AMBISONIC_11)
                .channelMapping(OpusChannelMapping.AMBISONIC_PROJECTION).bitrate(384000).build());
        return AudioStreams.encode("opus-encode-projection", ambisonic(seconds), AMBISONIC_11, 48000, encoder);
    }

    /**
     * Returns a workload that decodes an Opus projection stream that Smalt's own encoder produced:
     * every round demixes the track to its eleven channels, mixes it down to stereo, and decodes it
     * packet by packet into 16-bit samples, concealing every seventh packet and rebuilding every
     * fifth from the next packet's forward error correction; the decoders last across rounds.
     *
     * @param seconds the content decoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload opusProjectionDecode(double seconds) {
        var encoder = new OpusEncoder(OpusEncoderOptions.builder().format(48000, AMBISONIC_11)
                .channelMapping(OpusChannelMapping.AMBISONIC_PROJECTION).application(OpusApplication.VOIP)
                .bitrate(192000).inbandFec(true).packetLossPercent(10).build());
        var packets = AudioStreams.encodeAll(encoder, ambisonic(seconds), AMBISONIC_11, 48000);
        var track = AudioStreams.track(Codec.OPUS, 48000, AMBISONIC_11, encoder.codecConfig().orElseThrow());
        encoder.close();
        var full = new OpusDecoder(track);
        var stereo = new OpusDecoder(track, OpusDecoderOptions.builder().channels(2).build());
        var perPacket = new OpusDecoder(track);
        var pcm = new short[5760 * 11];
        Consumer<AudioBuffer> output = AudioStreams::consume;
        return TrainingWorkload.of("opus-decode-projection", () -> {
            for (var i = 0; i < packets.size(); i++) {
                var packet = packets.get(i);
                full.decode(packet, output);
                stereo.decode(packet, output);
                var data = packet.data();
                int samples;
                if (i % 5 == 3 && i + 1 < packets.size()) {
                    var next = packets.get(i + 1).data();
                    samples = perPacket.recover(next, 0, next.length, pcm, 0, 960);
                } else if (i % 7 == 5) {
                    samples = perPacket.conceal(pcm, 0, 960);
                } else {
                    samples = perPacket.decode(data, 0, data.length, pcm, 0, 5760);
                }
                Blackhole.consume(samples + pcm[17]);
            }
        });
    }

    /**
     * Returns a workload that decodes a stereo Opus track of music that Smalt's own encoder
     * produced, one decoder across rounds.
     *
     * @param seconds the music decoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload opusDecode(double seconds) {
        var encoder = new OpusEncoder(OpusEncoderOptions.builder().format(48000, 2).build());
        var packets = AudioStreams.encodeAll(encoder, SyntheticAudio.music(48000, seconds), ChannelLayout.STEREO, 48000);
        var track = AudioStreams.track(Codec.OPUS, 48000, ChannelLayout.STEREO, encoder.codecConfig().orElseThrow());
        encoder.close();
        return AudioStreams.decode("opus-decode", packets, new OpusDecoder(track));
    }

    /**
     * Returns a workload that codes speech in real time: the real-time Opus preset (VoIP, in-band
     * forward error correction for 10% loss) in frames of a given duration, decoded with loss
     * recovery.
     *
     * <p>One encoder and decoder last across rounds, the long run of a stream (the encoder's
     * bitrate history, the decoder's concealment state), concealing every 23rd packet. Every round
     * also creates a new encoder and decoder, as a new stream does, alternating discontinuous
     * transmission and the voice signal hint; that decoder recovers every fifth packet from the
     * next packet's forward error correction and conceals every seventh.
     *
     * @param name        the name of the workload
     * @param seconds     the speech coded per round, in seconds
     * @param rate        the sample rate in hertz
     * @param frameMillis the frame duration in milliseconds
     * @return the workload
     */
    private static TrainingWorkload opusRealtime(String name, double seconds, int rate, int frameMillis) {
        var speech = SyntheticAudio.speech(rate, seconds);
        var frame = rate * frameMillis / 1000;
        var count = speech.length / frame;
        var packets = new byte[count][1500];
        var lengths = new int[count];
        var pcm = new float[2 * frame];
        var realtimeOptions = OpusEncoderOptions.forRealtime().toBuilder().format(rate, 1)
                .application(OpusApplication.VOIP).inbandFec(true).packetLossPercent(10)
                .frameDurationMicros(frameMillis * 1000).build();
        var lastingEncoder = new OpusEncoder(realtimeOptions);
        var lastingDecoder = new OpusDecoder(OpusDecoderOptions.of(rate, 1));
        var lastingPacket = new byte[1500];
        int[] round = {0};
        return TrainingWorkload.of(name, () -> {
            for (var i = 0; i < count; i++) {
                var length = lastingEncoder.encode(speech, i * frame, frame, lastingPacket, 0, lastingPacket.length);
                Blackhole.consume(i % 23 == 5 ? lastingDecoder.conceal(pcm, 0, frame)
                        : lastingDecoder.decode(lastingPacket, 0, length, pcm, 0, frame));
            }
            var kind = round[0]++;
            var options = realtimeOptions.toBuilder().dtx(kind % 2 == 0).build();
            var encoder = new OpusEncoder(kind % 4 < 2 ? options.toBuilder().signal(OpusSignal.VOICE).build()
                    : options);
            var decoder = new OpusDecoder(OpusDecoderOptions.of(rate, 1));
            for (var i = 0; i < count; i++) {
                lengths[i] = encoder.encode(speech, i * frame, frame, packets[i], 0, packets[i].length);
            }
            for (var i = 0; i < count; i++) {
                int samples;
                if (i % 5 == 3 && i + 1 < count) {
                    samples = decoder.recover(packets[i + 1], 0, lengths[i + 1], pcm, 0, frame);
                } else if (i % 7 == 5) {
                    samples = decoder.conceal(pcm, 0, frame);
                } else {
                    samples = decoder.decode(packets[i], 0, lengths[i], pcm, 0, frame);
                }
                Blackhole.consume(samples + Float.floatToRawIntBits(pcm[17]));
            }
        });
    }

    /**
     * Returns a workload that encodes stereo music as 44.1 kHz Vorbis at three qualities, which
     * select different setup templates and codebooks, one encoder of each quality across rounds.
     *
     * @param seconds the music encoded per round by each encoder, in seconds
     * @return the workload
     */
    private static TrainingWorkload vorbisEncode(double seconds) {
        var music = SyntheticAudio.music(44100, seconds);
        var encoders = new ArrayList<VorbisEncoder>();
        for (var quality : new float[]{0, VorbisEncoderOptions.DEFAULT_QUALITY, 8}) {
            encoders.add(new VorbisEncoder(VorbisEncoderOptions.of(44100, ChannelLayout.STEREO).toBuilder()
                    .quality(quality).build()));
        }
        var block = new AudioBuffer(ChannelLayout.STEREO, 44100, AudioStreams.BLOCK_FRAMES);
        Consumer<Packet> output = packet -> Blackhole.consume(packet.size());
        return TrainingWorkload.of("vorbis-encode", () -> {
            for (var encoder : encoders) {
                SyntheticAudio.feed(music, block, buffer -> encoder.encode(buffer, output));
            }
        });
    }

    /**
     * Returns a workload that encodes stereo music as bitrate managed 44.1 kHz Vorbis, which codes
     * every block at all its rates: an average bitrate with a maximum, and a tight maximum at a
     * quality that truncates packets, one encoder of each across rounds.
     *
     * @param seconds the music encoded per round by each encoder, in seconds
     * @return the workload
     */
    private static TrainingWorkload vorbisEncodeManaged(double seconds) {
        var music = SyntheticAudio.music(44100, seconds);
        var encoders = List.of(
                new VorbisEncoder(VorbisEncoderOptions.ofAverageBitrate(128_000).toBuilder().maximumBitrate(160_000)
                        .build()),
                new VorbisEncoder(VorbisEncoderOptions.ofQuality(4).toBuilder().maximumBitrate(48_000)
                        .reservoirBits(8_000).build()));
        var block = new AudioBuffer(ChannelLayout.STEREO, 44100, AudioStreams.BLOCK_FRAMES);
        Consumer<Packet> output = packet -> Blackhole.consume(packet.size());
        return TrainingWorkload.of("vorbis-encode-managed", () -> {
            for (var encoder : encoders) {
                SyntheticAudio.feed(music, block, buffer -> encoder.encode(buffer, output));
            }
        });
    }

    /**
     * Returns a workload that decodes a stereo Vorbis track of music that Smalt's own encoder
     * produced, one decoder across rounds.
     *
     * @param seconds the music decoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload vorbisDecode(double seconds) {
        var encoder = new VorbisEncoder(VorbisEncoderOptions.of(44100, ChannelLayout.STEREO));
        var packets = AudioStreams.encodeAll(encoder, SyntheticAudio.music(44100, seconds), ChannelLayout.STEREO, 44100);
        var track = AudioStreams.track(Codec.VORBIS, 44100, ChannelLayout.STEREO, encoder.codecConfig().orElseThrow());
        encoder.close();
        return AudioStreams.decode("vorbis-decode", packets, new VorbisDecoder(track));
    }

    /**
     * Returns a workload that encodes stereo music as FLAC, one stream across rounds.
     *
     * @param name    the name of the workload
     * @param options the encoder settings, whose format is stereo at {@code rate}
     * @param rate    the sample rate of the music
     * @param seconds the music encoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload flacEncode(String name, FlacEncoderOptions options, int rate, double seconds) {
        return AudioStreams.encode(name, SyntheticAudio.music(rate, seconds), ChannelLayout.STEREO, rate,
                new FlacEncoder(options));
    }

    /**
     * Returns a workload that decodes a 44.1 kHz 16-bit stereo FLAC track of music that Smalt's own
     * encoder produced, one decoder across rounds.
     *
     * @param seconds the music decoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload flacDecode(double seconds) {
        var encoder = new FlacEncoder(FlacEncoderOptions.defaults());
        var packets = AudioStreams.encodeAll(encoder, SyntheticAudio.music(44100, seconds), ChannelLayout.STEREO, 44100);
        var track = AudioStreams.track(Codec.FLAC, 44100, ChannelLayout.STEREO, encoder.codecConfig().orElseThrow());
        encoder.close();
        return AudioStreams.decode("flac-decode", packets, new FlacDecoder(track));
    }

    /**
     * Returns a workload that encodes stereo music as 44.1 kHz 16-bit ALAC, one encoder across rounds.
     *
     * @param name the name of the workload
     * @param options the encoder settings, whose format is 44.1 kHz 16-bit stereo
     * @param seconds the music encoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload alacEncode(String name, AlacEncoderOptions options, double seconds) {
        return AudioStreams.encode(name, SyntheticAudio.music(44100, seconds), ChannelLayout.STEREO, 44100,
                new AlacEncoder(options));
    }

    /**
     * Returns a workload that encodes 5.1 content as 48 kHz 24-bit ALAC, which codes single channels and
     * channel pairs with shifted-off low bytes, one encoder across rounds.
     *
     * @param seconds the content encoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload alacSurround(double seconds) {
        var encoder = new AlacEncoder(AlacEncoderOptions.of(48000, ChannelLayout.SURROUND_5_1, 24));
        return AudioStreams.encode("alac-encode-surround", surround(seconds), ChannelLayout.SURROUND_5_1, 48000,
                encoder);
    }

    /**
     * Returns a workload that decodes a 44.1 kHz 16-bit stereo ALAC track of music that Smalt's own
     * encoder produced, one decoder across rounds.
     *
     * @param seconds the music decoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload alacDecode(double seconds) {
        var encoder = new AlacEncoder();
        var packets = AudioStreams.encodeAll(encoder, SyntheticAudio.music(44100, seconds), ChannelLayout.STEREO, 44100);
        var track = AudioStreams.track(Codec.ALAC, 44100, ChannelLayout.STEREO, encoder.codecConfig().orElseThrow());
        encoder.close();
        return AudioStreams.decode("alac-decode", packets, new AlacDecoder(track));
    }

    /**
     * Returns a workload that decodes a 48 kHz 32-bit stereo FLAC track of music that Smalt's own
     * encoder produced from integer blocks, through the integer output with the {@code STREAMINFO}
     * MD5 check, one decoder per round.
     *
     * @param seconds the music decoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload flacDecodeIntegers(double seconds) {
        var options = FlacEncoderOptions.defaults().toBuilder().format(48000, ChannelLayout.STEREO).bitsPerSample(32)
                .build();
        var music = SyntheticAudio.music(48000, seconds);
        var planes = new int[music.length][];
        for (var c = 0; c < music.length; c++) {
            planes[c] = new int[music[c].length];
            for (var i = 0; i < music[c].length; i++) {
                planes[c][i] = (int) Math.round(Math.clamp(music[c][i], -1f, 1f) * 0x1p31 * 0.99);
            }
        }
        var packets = new ArrayList<Packet>();
        var encoder = new FlacEncoder(options);
        encoder.encode(new IntegerAudioBuffer(ChannelLayout.STEREO, 48000, 32, planes, planes[0].length), packets::add);
        encoder.flush(packets::add);
        var track = AudioStreams.track(Codec.FLAC, 48000, ChannelLayout.STEREO, encoder.codecConfig().orElseThrow());
        encoder.close();
        var verify = AudioDecoderOptions.builder().verifyIntegrity(true).build();
        Consumer<IntegerAudioBuffer> output = block -> Blackhole.consume(block.plane(0)[0] + block.frames());
        return TrainingWorkload.of("flac-decode-32", () -> {
            try (var decoder = new FlacDecoder(track, verify)) {
                for (var packet : packets) {
                    decoder.decodeIntegers(packet, output);
                }
                decoder.flushIntegers(output);
            }
        });
    }

    /**
     * Returns a workload that encodes stereo music as MP3, one stream across rounds.
     *
     * @param name    the name of the workload
     * @param options the encoder settings, whose format is 44.1 kHz stereo
     * @param seconds the music encoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload mp3Encode(String name, Mp3EncoderOptions options, double seconds) {
        var encoder = new Mp3Encoder(options.toBuilder().format(44100, ChannelLayout.STEREO).build());
        return AudioStreams.encode(name, SyntheticAudio.music(44100, seconds), ChannelLayout.STEREO, 44100, encoder);
    }

    /**
     * Returns a workload that decodes two MP3 files through {@link AudioReader}: six seconds of
     * music that Smalt's own encoder produced at a constant and at a variable bitrate.
     *
     * @return the workload
     */
    private static TrainingWorkload mp3Decode() {
        var music = SyntheticAudio.music(44100, 6);
        var files = new ArrayList<byte[]>();
        for (var options : List.of(Mp3EncoderOptions.defaults(), Mp3EncoderOptions.ofVariableBitrate(4))) {
            var out = new ByteArrayOutputStream();
            for (Packet packet : AudioStreams.encodeAll(new Mp3Encoder(options), music, ChannelLayout.STEREO, 44100)) {
                out.writeBytes(packet.data());
            }
            files.add(out.toByteArray());
        }
        return TrainingWorkload.of("mp3-decode", () -> {
            for (var file : files) {
                try (var reader = AudioReader.open(MediaSource.of(file))) {
                    for (var decoded = reader.read(); decoded != null; decoded = reader.read()) {
                        AudioStreams.consume(decoded);
                    }
                }
            }
        });
    }

    /**
     * Returns a workload that encodes 48 kHz stereo music as MPEG audio Layer I or II, one stream across rounds.
     *
     * @param name    the name of the workload
     * @param options the encoder settings, whose format is 48 kHz stereo
     * @param seconds the music encoded per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload mp2Encode(String name, Mp2EncoderOptions options, double seconds) {
        var encoder = new Mp2Encoder(options);
        return AudioStreams.encode(name, SyntheticAudio.music(48000, seconds), ChannelLayout.STEREO, 48000, encoder);
    }

    /**
     * Returns a workload that decodes two MPEG audio files through {@link AudioReader}: four seconds of music
     * that Smalt's own encoder produced in Layer II joint stereo with CRC words and in Layer I.
     *
     * @return the workload
     */
    private static TrainingWorkload mp2Decode() {
        var music = SyntheticAudio.music(48000, 4);
        var files = new ArrayList<byte[]>();
        for (var options : List.of(Mp2EncoderOptions.builder().bitrate(128_000).stereoMode(Mp3StereoMode.JOINT_STEREO)
                .crc(true).build(), Mp2EncoderOptions.builder().layer(1).build())) {
            var out = new ByteArrayOutputStream();
            for (Packet packet : AudioStreams.encodeAll(new Mp2Encoder(options), music, ChannelLayout.STEREO, 48000)) {
                out.writeBytes(packet.data());
            }
            files.add(out.toByteArray());
        }
        return TrainingWorkload.of("mp2-decode", () -> {
            for (var file : files) {
                try (var reader = AudioReader.open(MediaSource.of(file))) {
                    for (var decoded = reader.read(); decoded != null; decoded = reader.read()) {
                        AudioStreams.consume(decoded);
                    }
                }
            }
        });
    }

    /**
     * Returns a workload that runs the voice processor (high-pass filter, AEC3 echo canceller,
     * noise suppression) on far-end speech and a microphone signal made of its echo and of
     * near-end speech, in 10 ms frames.
     *
     * <p>Every round creates a fresh processor, whose first blocks (start-up phases, delay search)
     * differ from its steady state, over one of five conversations in turn: an echo with a late
     * reflection and near-end talk every fourth second; a short echo path with frequent double talk
     * and a reported stream delay; a long echo path with the far end only; no echo with the near end
     * silent; and the first conversation with a far end that starts silent. One processor also runs
     * across every round, on the first conversation with a stationary tone added: its long run
     * (converged filters, hold counters, comfort noise).
     *
     * @param seconds the duration of each conversation, in seconds
     * @return the workload
     */
    private static TrainingWorkload voiceProcessor(double seconds) {
        var far = SyntheticAudio.speech(16000, seconds);
        var near = SyntheticAudio.speech(16000, seconds + 1.37);
        var captures = new float[][]{
                microphone(far, near, 960, 1500, 3),
                microphone(far, near, 480, -1, 2),
                microphone(far, near, 1600, 2400, 0),
                microphone(far, null, 0, -1, 0)};
        var farSilentStart = far.clone();
        Arrays.fill(farSilentStart, 0, Math.min(farSilentStart.length, 16000), 0f);
        var tonal = captures[0].clone();
        for (var i = 0; i < tonal.length; i += 7) {
            tonal[i] += 0.01f;
        }
        var lasting = new VoiceProcessor(VoiceProcessorOptions.forVoip());
        lasting.setStreamDelayMs(0);
        var lastingFrame = lasting.frameSamples();
        var lastingBuffer = new float[lastingFrame];
        int[] round = {0};
        return TrainingWorkload.of("voice-processor", () -> {
            for (var i = 0; i < far.length / lastingFrame; i++) {
                System.arraycopy(tonal, i * lastingFrame, lastingBuffer, 0, lastingFrame);
                lasting.processRender(far, i * lastingFrame);
                lasting.processCapture(lastingBuffer, 0, lastingBuffer, 0);
                Blackhole.consume(Float.floatToRawIntBits(lastingBuffer[5]));
            }
            var kind = round[0]++ % (captures.length + 1);
            var render = kind == captures.length ? farSilentStart : far;
            var capture = kind == captures.length ? captures[0] : captures[kind];
            var processor = new VoiceProcessor(VoiceProcessorOptions.forVoip());
            processor.setStreamDelayMs(kind == 1 ? 30 : 0);
            var frame = processor.frameSamples();
            var buffer = new float[frame];
            for (var i = 0; i < render.length / frame; i++) {
                System.arraycopy(capture, i * frame, buffer, 0, frame);
                processor.processRender(render, i * frame);
                processor.processCapture(buffer, 0, buffer, 0);
                Blackhole.consume(Float.floatToRawIntBits(buffer[17]));
            }
        });
    }

    /**
     * Returns a workload that runs the voice processor at the other rates and channel layouts and
     * with every gain controller, on far-end speech and a microphone signal made of its echo and of
     * near-end speech every third second, in 10 ms frames.
     *
     * <p>Every round creates a fresh processor in one of three configurations in turn, feeding the
     * recommended microphone volume back: 48 kHz stereo with the multichannel echo canceller and
     * the level controller with its input volume controller (band splitting into three bands, the
     * RNN voice activity detector, the limiter); 32 kHz with the managed analog gain controller and
     * clipping prediction (two bands, the analog controller's voice activity detector and pitch
     * analysis); and 8 kHz with the mobile echo canceller and the adaptive digital gain controller
     * (the resamplers).
     *
     * @param seconds the duration of each conversation, in seconds
     * @return the workload
     */
    private static TrainingWorkload voiceProcessorLayouts(double seconds) {
        var options = List.of(
                VoiceProcessorOptions.forVoip().toBuilder().sampleRate(48000).captureChannels(2).renderChannels(2)
                        .multichannelCapture(true).multichannelRender(true)
                        .levelControl(LevelControlOptions.builder().inputVolumeController(true).build()).build(),
                VoiceProcessorOptions.forVoip().toBuilder().sampleRate(32000).gainControl(GainControl.MANAGED_ANALOG)
                        .analogGainController(AnalogGainControllerOptions.builder()
                                .clippingPrediction(ClippingPrediction.CLIPPING_EVENT).build()).build(),
                VoiceProcessorOptions.forVoip().toBuilder().sampleRate(8000).echoCancellation(EchoCancellation.MOBILE)
                        .gainControl(GainControl.ADAPTIVE_DIGITAL).build());
        var renders = new float[options.size()][];
        var captures = new float[options.size()][];
        for (var k = 0; k < options.size(); k++) {
            var layout = options.get(k);
            var rate = layout.sampleRate();
            var far = SyntheticAudio.speech(rate, seconds);
            var near = SyntheticAudio.speech(rate, seconds + 1.37);
            var microphone = new float[far.length];
            var delay = rate / 20;
            for (var i = 0; i < microphone.length; i++) {
                var echo = i >= delay ? 0.3f * far[i - delay] : 0;
                var talk = i / rate % 3 == 2 ? 0.5f * near[near.length - 1 - i] : 0;
                microphone[i] = echo + talk;
            }
            renders[k] = interleave(far, layout.renderChannels());
            captures[k] = interleave(microphone, layout.captureChannels());
        }
        int[] round = {0};
        return TrainingWorkload.of("voice-processor-layouts", () -> {
            var k = round[0]++ % options.size();
            var layout = options.get(k);
            var processor = new VoiceProcessor(layout);
            processor.setStreamDelayMs(50);
            var renderLength = processor.frameSamples() * layout.renderChannels();
            var captureLength = processor.frameSamples() * layout.captureChannels();
            var buffer = new float[captureLength];
            var level = 128;
            for (var i = 0; i < captures[k].length / captureLength; i++) {
                System.arraycopy(captures[k], i * captureLength, buffer, 0, captureLength);
                processor.processRender(renders[k], i * renderLength);
                processor.setAnalogLevel(level);
                processor.processCapture(buffer, 0, buffer, 0);
                level = processor.recommendedAnalogLevel();
                Blackhole.consume(Float.floatToRawIntBits(buffer[17]));
            }
        });
    }

    /**
     * Interleaves copies of a signal, each further channel 2 dB quieter.
     *
     * @param signal the samples
     * @param channels the number of channels
     * @return the interleaved samples
     */
    private static float[] interleave(float[] signal, int channels) {
        var out = new float[signal.length * channels];
        for (var c = 0; c < channels; c++) {
            var gain = (float) StrictMath.pow(10, -2.0 * c / 20);
            for (var i = 0; i < signal.length; i++) {
                out[i * channels + c] = gain * signal[i];
            }
        }
        return out;
    }

    /**
     * Returns a microphone signal: the far end through an echo path, plus near-end speech in some
     * seconds; without echo and near end, a faint deterministic noise.
     *
     * @param far        the far-end speech
     * @param near       the near-end speech, or {@code null} for none
     * @param delay      the delay of the direct echo in samples, or {@code 0} for no echo
     * @param reflection the delay of a weaker, inverted reflection in samples, or {@code -1} for
     *                   none
     * @param talkPeriod the near end speaks during the last second of every {@code talkPeriod}
     *                   seconds; {@code 0} for never
     * @return the microphone samples, as many as {@code far} has
     */
    private static float[] microphone(float[] far, float[] near, int delay, int reflection, int talkPeriod) {
        var capture = new float[far.length];
        for (var i = 0; i < capture.length; i++) {
            if (delay == 0 && near == null) {
                capture[i] = 0.001f * ((i * 7919) % 13 - 6);
                continue;
            }
            var echo = (delay > 0 && i >= delay ? 0.3f * far[i - delay] : 0)
                    + (reflection > 0 && i >= reflection ? -0.12f * far[i - reflection] : 0);
            var talk = near != null && talkPeriod > 0 && i / 16000 % talkPeriod == talkPeriod - 1
                    ? 0.5f * near[near.length - 1 - i] : 0;
            capture[i] = echo + talk;
        }
        return capture;
    }
}
