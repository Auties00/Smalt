package com.github.auties00.smalt.jit;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.AudioReaderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.aac.AacDecoder;
import com.github.auties00.smalt.audio.aac.AacEncoder;
import com.github.auties00.smalt.audio.ac3.Ac3Decoder;
import com.github.auties00.smalt.audio.ac3.Ac3Encoder;
import com.github.auties00.smalt.audio.ac3.Ac3EncoderOptions;
import com.github.auties00.smalt.audio.aac.AacEncoderOptions;
import com.github.auties00.smalt.audio.amr.AmrDecoder;
import com.github.auties00.smalt.audio.amr.AmrEncoder;
import com.github.auties00.smalt.audio.amr.AmrEncoderOptions;
import com.github.auties00.smalt.audio.amr.AmrMode;
import com.github.auties00.smalt.audio.alac.AlacEncoder;
import com.github.auties00.smalt.audio.flac.FlacEncoder;
import com.github.auties00.smalt.audio.flac.FlacEncoderOptions;
import com.github.auties00.smalt.audio.mp3.Mp3Encoder;
import com.github.auties00.smalt.audio.mp3.Mp3EncoderOptions;
import com.github.auties00.smalt.audio.opus.OpusEncoder;
import com.github.auties00.smalt.audio.opus.OpusEncoderOptions;
import com.github.auties00.smalt.audio.opus.internal.OpusDefines;
import com.github.auties00.smalt.audio.opus.internal.OpusStreamDecoder;
import com.github.auties00.smalt.audio.opus.internal.OpusStreamEncoder;
import com.github.auties00.smalt.audio.process.Resampler;
import com.github.auties00.smalt.audio.voice.VoiceProcessor;
import com.github.auties00.smalt.audio.voice.VoiceProcessorOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * The audio workloads of {@link JitWorkload}: the Opus voice-note and call encoders, the call
 * decoder with its loss recovery, the AAC encoder, the AC-3 encoder and decoder, the MP3 decoder, the resampler and the call
 * voice processor, each fed with deterministic synthetic speech or music and kept alive across
 * rounds like a long-running application's codec. One frame is one codec frame (an Opus or AAC
 * packet, an MP3 frame of 1152 samples), for the resampler one 4096-frame block, and for the voice
 * processor one 10 ms render and capture pair.
 */
final class AudioJitWorkloads {
    private static long sink;

    private AudioJitWorkloads() {
    }

    /**
     * Synthesizes speech-like audio with a source-filter model: syllables of a glottal pulse train
     * gliding in pitch, or of noise, shaped by three formant resonators, with pauses of digital
     * silence between phrases.
     */
    static float[] speech(int rate, double seconds) {
        var out = new float[(int) (rate * seconds)];
        int[][] vowels = {{730, 1090, 2440}, {270, 2290, 3010}, {300, 870, 2240}, {530, 1840, 2480}, {570, 840, 2410}};
        double[] bandwidths = {80, 100, 140};
        var y1 = new double[3];
        var y2 = new double[3];
        var a1 = new double[3];
        var a2 = new double[3];
        var seed = 0x2545F4914F6CDD1DL;
        var pulse = 0.0;
        var n = 0;
        for (var syllable = 0; n < out.length; syllable++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            var length = rate * (120 + (int) ((seed >>> 33) % 180)) / 1000;
            var silent = syllable % 9 == 8;
            var voiced = syllable % 4 != 3;
            var vowel = vowels[(int) ((seed >>> 40) % vowels.length)];
            for (var k = 0; k < 3; k++) {
                var r = Math.exp(-Math.PI * bandwidths[k] / rate);
                a1[k] = 2 * r * Math.cos(2 * Math.PI * Math.min(vowel[k], 0.45 * rate) / rate);
                a2[k] = -r * r;
            }
            var pitch = 95 + 12 * (syllable % 7);
            for (var i = 0; i < length && n < out.length; i++, n++) {
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                var noise = ((seed >>> 40) / (double) (1L << 24)) - 0.5;
                double excitation;
                if (silent) {
                    excitation = 0;
                } else if (voiced) {
                    pulse += (pitch + 25 * Math.sin(Math.PI * i / length)) / rate;
                    excitation = 0.03 * noise;
                    if (pulse >= 1) {
                        pulse -= 1;
                        excitation += 1;
                    }
                } else {
                    excitation = 0.4 * noise;
                }
                var x = excitation;
                for (var k = 0; k < 3; k++) {
                    var y = x + a1[k] * y1[k] + a2[k] * y2[k];
                    y2[k] = y1[k];
                    y1[k] = y;
                    x = y * (1 + a2[k]);
                }
                out[n] = (float) (x * Math.sin(Math.PI * i / length));
            }
        }
        normalize(out, 0.5f);
        return out;
    }

    /**
     * Synthesizes stereo music: a four-chord progression of harmonic notes plucked on every half
     * beat, a bass line and a noise hi-hat, panned differently in each channel.
     */
    static float[][] music(int rate, double seconds) {
        var frames = (int) (rate * seconds);
        var left = new float[frames];
        var right = new float[frames];
        int[][] chords = {{57, 60, 64}, {53, 57, 60}, {48, 52, 55}, {55, 59, 62}};
        var phases = new double[4][6];
        var seed = 0x9E3779B97F4A7C15L;
        var hat = 0.0;
        for (var t = 0; t < frames; t++) {
            var chord = chords[(t / rate) % chords.length];
            var sinceBeat = (t % (rate / 2)) / (double) rate;
            var envelope = Math.exp(-4 * sinceBeat);
            double l = 0;
            double r = 0;
            for (var note = 0; note < 4; note++) {
                var midi = note < 3 ? chord[note] + 12 : chord[0] - 12;
                var frequency = 440 * Math.pow(2, (midi - 69) / 12.0);
                double value = 0;
                for (var h = 0; h < 6; h++) {
                    phases[note][h] += frequency * (h + 1) / rate;
                    phases[note][h] -= Math.floor(phases[note][h]);
                    value += Math.sin(2 * Math.PI * phases[note][h]) / (h + 1);
                }
                var pan = 0.2 + 0.2 * note;
                l += value * envelope * (1 - pan);
                r += value * envelope * pan;
            }
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            var noise = ((seed >>> 40) / (double) (1L << 24)) - 0.5;
            var hatEnvelope = Math.exp(-40 * ((t % (rate / 8)) / (double) rate));
            var highPassed = noise - hat;
            hat = noise;
            left[t] = (float) (l + 0.3 * highPassed * hatEnvelope);
            right[t] = (float) (r + 0.2 * highPassed * hatEnvelope);
        }
        normalize(left, 0.45f);
        normalize(right, 0.45f);
        return new float[][]{left, right};
    }

    private static void normalize(float[] samples, float peak) {
        var max = 1e-9f;
        for (var sample : samples) {
            max = Math.max(max, Math.abs(sample));
        }
        var scale = peak / max;
        for (var i = 0; i < samples.length; i++) {
            samples[i] *= scale;
        }
    }

    /** Copies planar samples into 4096-frame blocks and passes each block to a consumer. */
    private static void feed(float[][] planes, AudioBuffer block, Consumer<AudioBuffer> consumer) {
        var total = planes[0].length;
        for (var position = 0; position < total; position += block.capacity()) {
            var count = Math.min(block.capacity(), total - position);
            for (var channel = 0; channel < planes.length; channel++) {
                System.arraycopy(planes[channel], position, block.plane(channel), 0, count);
            }
            block.setFrames(count);
            consumer.accept(block);
        }
    }

    /** Encodes synthetic speech as voice notes: 16 kHz mono Opus, 20 ms packets. */
    static JitWorkload.Workload opusVoiceNote(double seconds) {
        var speech = new float[][]{speech(16000, seconds)};
        var encoder = new OpusEncoder(OpusEncoderOptions.forSpeech(16000));
        var block = new AudioBuffer(ChannelLayout.MONO, 16000, AudioReaderOptions.DEFAULT_BLOCK_FRAMES);
        int[] packets = {0};
        Consumer<Packet> output = packet -> {
            packets[0]++;
            sink += packet.size();
        };
        return JitWorkload.named("opusvoice", () -> {
            packets[0] = 0;
            feed(speech, block, buffer -> encoder.encode(buffer, output));
            return packets[0];
        });
    }

    /**
     * Encodes synthetic speech with the voice-call configuration (16 kHz mono VoIP, 25 kb/s VBR,
     * complexity 9, in-band FEC for 10% loss, DTX, 60 ms frames) and decodes the packets,
     * recovering every fifth from the next packet's FEC and concealing every seventh.
     */
    static JitWorkload.Workload opusCall(double seconds) {
        var speech = speech(16000, seconds);
        var encoder = new OpusStreamEncoder(16000, 1, OpusDefines.OPUS_APPLICATION_VOIP);
        encoder.setBitrate(25000);
        encoder.setVbr(true);
        encoder.setComplexity(9);
        encoder.setInbandFec(1);
        encoder.setPacketLossPerc(10);
        encoder.setDtx(true);
        encoder.setSignal(OpusDefines.OPUS_SIGNAL_VOICE);
        encoder.setLsbDepth(16);
        encoder.setMaxBandwidth(OpusDefines.OPUS_BANDWIDTH_WIDEBAND);
        var decoder = new OpusStreamDecoder(16000, 1);
        var frame = 960;
        var count = speech.length / frame;
        var packets = new byte[count][1500];
        var lengths = new int[count];
        var pcm = new float[2 * frame];
        return JitWorkload.named("opuscall", () -> {
            for (var i = 0; i < count; i++) {
                lengths[i] = encoder.encodeFloat(speech, i * frame, frame, packets[i], 0, packets[i].length);
            }
            for (var i = 0; i < count; i++) {
                int samples;
                if (i % 5 == 3 && i + 1 < count) {
                    samples = decoder.decodeNative(packets[i + 1], 0, lengths[i + 1], pcm, 0, frame, true, false, null, false);
                } else if (i % 7 == 5) {
                    samples = decoder.decodeNative(null, 0, 0, pcm, 0, frame, false, false, null, false);
                } else {
                    samples = decoder.decodeNative(packets[i], 0, lengths[i], pcm, 0, frame, false, false, null, false);
                }
                sink += samples + Float.floatToRawIntBits(pcm[17]);
            }
            return count;
        });
    }

    /** Encodes synthetic stereo music as 44.1 kHz AAC-LC at 128 kb/s. */
    static JitWorkload.Workload aacEncode(double seconds) {
        var music = music(44100, seconds);
        var encoder = new AacEncoder(AacEncoderOptions.of(44100, ChannelLayout.STEREO, 128000));
        var block = new AudioBuffer(ChannelLayout.STEREO, 44100, AudioReaderOptions.DEFAULT_BLOCK_FRAMES);
        int[] packets = {0};
        Consumer<Packet> output = packet -> {
            packets[0]++;
            sink += packet.size();
        };
        return JitWorkload.named("aacenc", () -> {
            packets[0] = 0;
            feed(music, block, buffer -> encoder.encode(buffer, output));
            return packets[0];
        });
    }

    /**
     * Encodes synthetic stereo music as 48 kHz AC-3 at 192 kb/s and decodes the frames back, one
     * encoder and decoder across rounds.
     */
    static JitWorkload.Workload ac3(double seconds) {
        var music = music(48000, seconds);
        var encoder = new Ac3Encoder(Ac3EncoderOptions.of(48000, ChannelLayout.STEREO).toBuilder()
                .bitrate(192000).build());
        var decoder = new Ac3Decoder(AudioTrack.builder().id(0).codec(Codec.AC3).timeBase(Rational.timeBaseOf(48000))
                .sampleRate(48000).layout(ChannelLayout.STEREO).build());
        var block = new AudioBuffer(ChannelLayout.STEREO, 48000, AudioReaderOptions.DEFAULT_BLOCK_FRAMES);
        int[] packets = {0};
        Consumer<AudioBuffer> decoded = buffer -> sink += Float.floatToRawIntBits(buffer.plane(0)[7]);
        Consumer<Packet> output = packet -> {
            packets[0]++;
            decoder.decode(packet, decoded);
        };
        return JitWorkload.named("ac3", () -> {
            packets[0] = 0;
            feed(music, block, buffer -> encoder.encode(buffer, output));
            return packets[0];
        });
    }

    /**
     * Encodes synthetic speech as AMR and decodes the frames back, one encoder and decoder across
     * rounds, the mode changing every round through the codec's modes.
     */
    static JitWorkload.Workload amr(String name, Codec codec, double seconds) {
        var wide = codec == Codec.AMR_WB;
        var rate = wide ? 16000 : 8000;
        var speech = new float[][]{speech(rate, seconds)};
        var encoder = new AmrEncoder(wide ? AmrEncoderOptions.wideband() : AmrEncoderOptions.narrowband());
        var decoder = new AmrDecoder(AudioTrack.builder().id(0).codec(codec).timeBase(Rational.timeBaseOf(rate))
                .sampleRate(rate).layout(ChannelLayout.MONO).build());
        var modes = Arrays.stream(AmrMode.values()).filter(mode -> mode.codec() == codec).toList();
        var block = new AudioBuffer(ChannelLayout.MONO, rate, wide ? 320 : 160);
        int[] packets = {0};
        int[] round = {0};
        Consumer<AudioBuffer> decoded = buffer -> sink += Float.floatToRawIntBits(buffer.plane(0)[7]);
        Consumer<Packet> output = packet -> {
            packets[0]++;
            decoder.decode(packet, decoded);
        };
        return JitWorkload.named(name, () -> {
            packets[0] = 0;
            encoder.setMode(modes.get(modes.size() - 1 - round[0]++ % modes.size()));
            feed(speech, block, buffer -> encoder.encode(buffer, output));
            return packets[0];
        });
    }

    /**
     * Decodes the Low Complexity packets of a 22.05 kHz core announced as HE-AAC, which runs the
     * decoder's spectral band replication stages.
     */
    static JitWorkload.Workload heAac(double seconds) {
        var encoder = new AacEncoder(AacEncoderOptions.of(22050, ChannelLayout.STEREO, 48000));
        var block = new AudioBuffer(ChannelLayout.STEREO, 22050, AudioReaderOptions.DEFAULT_BLOCK_FRAMES);
        var packets = new ArrayList<Packet>();
        feed(music(22050, seconds), block, buffer -> encoder.encode(buffer, packets::add));
        encoder.flush(packets::add);
        var decoder = new AacDecoder(AudioTrack.builder().id(0).codec(Codec.AAC).timeBase(Rational.timeBaseOf(22050))
                .sampleRate(22050).layout(ChannelLayout.STEREO)
                .codecConfig(new byte[]{0x2B, (byte) 0x92, 0x08, 0x00}).build());
        Consumer<AudioBuffer> decoded = buffer -> sink += Float.floatToRawIntBits(buffer.plane(0)[7]);
        return JitWorkload.named("heaac", () -> {
            for (var packet : packets) {
                decoder.decode(packet, decoded);
            }
            return packets.size();
        });
    }

    /** Encodes synthetic stereo music as 44.1 kHz 16-bit FLAC at compression level 5, one stream across rounds. */
    static JitWorkload.Workload flacEncode(double seconds) {
        var music = music(44100, seconds);
        var encoder = new FlacEncoder(FlacEncoderOptions.defaults());
        var block = new AudioBuffer(ChannelLayout.STEREO, 44100, AudioReaderOptions.DEFAULT_BLOCK_FRAMES);
        int[] packets = {0};
        Consumer<Packet> output = packet -> {
            packets[0]++;
            sink += packet.size();
        };
        return JitWorkload.named("flacenc", () -> {
            packets[0] = 0;
            feed(music, block, buffer -> encoder.encode(buffer, output));
            return packets[0];
        });
    }

    /** Encodes synthetic stereo music as 44.1 kHz 16-bit ALAC with the full parameter search, one stream across rounds. */
    static JitWorkload.Workload alacEncode(double seconds) {
        var music = music(44100, seconds);
        var encoder = new AlacEncoder();
        var block = new AudioBuffer(ChannelLayout.STEREO, 44100, AudioReaderOptions.DEFAULT_BLOCK_FRAMES);
        int[] packets = {0};
        Consumer<Packet> output = packet -> {
            packets[0]++;
            sink += packet.size();
        };
        return JitWorkload.named("alacenc", () -> {
            packets[0] = 0;
            feed(music, block, buffer -> encoder.encode(buffer, output));
            return packets[0];
        });
    }

    /** Encodes synthetic stereo music to 128 kbit/s MP3, the encoder's defaults. */
    static JitWorkload.Workload mp3Encode(double seconds) {
        var music = music(44100, seconds);
        var encoder = new Mp3Encoder(Mp3EncoderOptions.defaults());
        var block = new AudioBuffer(ChannelLayout.STEREO, 44100, AudioReaderOptions.DEFAULT_BLOCK_FRAMES);
        int[] packets = {0};
        Consumer<Packet> output = packet -> {
            packets[0]++;
            sink += packet.size();
        };
        return JitWorkload.named("mp3enc", () -> {
            packets[0] = 0;
            feed(music, block, buffer -> encoder.encode(buffer, output));
            return packets[0];
        });
    }

    /** Decodes an MP3 file at its own rate and layout, as the audio reader does for a received file. */
    static JitWorkload.Workload mp3Decode(Path input) throws Exception {
        var bytes = Files.readAllBytes(input);
        return JitWorkload.named("mp3dec", () -> {
            var samples = 0L;
            try (var reader = AudioReader.open(MediaSource.of(bytes))) {
                AudioBuffer block;
                while ((block = reader.read()) != null) {
                    samples += block.frames();
                    sink += Float.floatToRawIntBits(block.plane(0)[0]);
                }
            }
            return (int) (samples / 1152);
        });
    }

    /**
     * Resamples synthetic stereo music from 44.1 to 48 kHz and synthetic speech from 48 to 16 kHz
     * (the voice-note input path), in 4096-frame blocks.
     */
    static JitWorkload.Workload resample(double seconds) {
        var music = music(44100, seconds);
        var speech = new float[][]{speech(48000, seconds)};
        var up = new Resampler(44100, 48000, 2, Resampler.Quality.STANDARD);
        var down = new Resampler(48000, 16000, 1, Resampler.Quality.STANDARD);
        var blockFrames = AudioReaderOptions.DEFAULT_BLOCK_FRAMES;
        var musicBlock = new AudioBuffer(ChannelLayout.STEREO, 44100, blockFrames);
        var speechBlock = new AudioBuffer(ChannelLayout.MONO, 48000, blockFrames);
        // Room for a block plus the frames a call may add while catching up on the filter delay.
        var upOutput = new float[2][2 * blockFrames];
        var downOutput = new float[1][blockFrames];
        int[] blocks = {0};
        return JitWorkload.named("resample", () -> {
            blocks[0] = 0;
            feed(music, musicBlock, buffer -> {
                sink += up.process(buffer.planes(), buffer.frames(), upOutput, 0);
                blocks[0]++;
            });
            feed(speech, speechBlock, buffer -> {
                sink += down.process(buffer.planes(), buffer.frames(), downOutput, 0);
                blocks[0]++;
            });
            return blocks[0];
        });
    }

    /**
     * Runs the call voice processor (high-pass filter, AEC3 echo canceller, noise suppression) on
     * synthetic far-end speech and a capture made of its echo (60 ms, two reflections) plus a
     * near-end talker that speaks over it, in 10 ms frames.
     */
    static JitWorkload.Workload voiceCall(double seconds) {
        var far = speech(16000, seconds);
        var near = speech(16000, seconds + 1.37);
        var capture = new float[far.length];
        for (var i = 0; i < capture.length; i++) {
            var echo = (i >= 960 ? 0.3f * far[i - 960] : 0) + (i >= 1500 ? -0.12f * far[i - 1500] : 0);
            capture[i] = echo + (i / 16000 % 4 == 3 ? 0.5f * near[near.length - 1 - i] : 0);
        }
        var processor = new VoiceProcessor(VoiceProcessorOptions.forVoip());
        processor.setStreamDelayMs(0);
        var frame = processor.frameSamples();
        var count = far.length / frame;
        var buffer = new float[frame];
        return JitWorkload.named("voice", () -> {
            for (var i = 0; i < count; i++) {
                System.arraycopy(capture, i * frame, buffer, 0, frame);
                processor.processRender(far, i * frame);
                processor.processCapture(buffer, 0, buffer, 0);
                sink += Float.floatToRawIntBits(buffer[17]);
            }
            return count;
        });
    }
}
