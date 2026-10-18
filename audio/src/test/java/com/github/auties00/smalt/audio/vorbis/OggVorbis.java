package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.internal.header.XiphLacing;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal Ogg page reader for tests: splits a (possibly chained) Ogg Vorbis file into its
 * logical streams, extracts their packets with the granule position of the page each packet
 * completes on, laces the three headers into a Matroska-style codec configuration, and decodes a
 * link with the same start/end trimming ffmpeg's Ogg demuxer applies.
 */
final class OggVorbis {
    private OggVorbis() {
    }

    /** One logical stream: three header packets plus audio packets. */
    record Link(int serial, List<byte[]> packets, List<Long> granules, List<byte[]> pages) {
        byte[][] headers() {
            return new byte[][]{packets.get(0), packets.get(1), packets.get(2)};
        }

        List<byte[]> audio() {
            return packets.subList(3, packets.size());
        }

        /** The granule of the page each audio packet completes on when it is that page's last packet, else -1. */
        List<Long> audioGranules() {
            return granules.subList(3, granules.size());
        }

        /** Whether every audio packet completes on a single page. */
        boolean singleAudioPage() {
            return audioGranules().stream().filter(granule -> granule >= 0).count() == 1;
        }

        long lastGranule() {
            for (var i = granules.size() - 1; i >= 0; i--) {
                if (granules.get(i) >= 0) {
                    return granules.get(i);
                }
            }
            return -1;
        }

        byte[] codecConfig() {
            return XiphLacing.lace(headers());
        }

        AudioTrack track() {
            return AudioTrack.builder().codec(Codec.VORBIS).timeBase(new Rational(1, 48000))
                    .codecConfig(codecConfig())
                    .build();
        }

        /** The raw bytes of this link's pages, a standalone Ogg file. */
        byte[] file() {
            var out = new ByteArrayOutputStream();
            for (var page : pages) {
                out.writeBytes(page);
            }
            return out.toByteArray();
        }
    }

    /** The decoded, trimmed PCM of a link. */
    record Decoded(int channels, int sampleRate, float[][] planes, int frames, int[] packetSamples, int rejected,
                   float[][] untrimmed) {
        float[] interleaved() {
            return interleave(planes, frames);
        }

        float[] untrimmedInterleaved() {
            return interleave(untrimmed, untrimmed[0].length);
        }

        private float[] interleave(float[][] source, int length) {
            var out = new float[length * channels];
            for (var i = 0; i < length; i++) {
                for (var c = 0; c < channels; c++) {
                    out[i * channels + c] = source[c][i];
                }
            }
            return out;
        }
    }

    static List<Link> read(byte[] file) {
        Map<Integer, Link> links = new LinkedHashMap<>();
        Map<Integer, ByteArrayOutputStream> partial = new LinkedHashMap<>();
        var position = 0;
        while (position + 27 <= file.length) {
            if (file[position] != 'O' || file[position + 1] != 'g' || file[position + 2] != 'g' || file[position + 3] != 'S') {
                throw new IllegalArgumentException("missing OggS capture at " + position);
            }
            var granule = le64(file, position + 6);
            var serial = (int) le32(file, position + 14);
            var segments = file[position + 26] & 0xFF;
            var dataStart = position + 27 + segments;
            var size = 0;
            for (var i = 0; i < segments; i++) {
                size += file[position + 27 + i] & 0xFF;
            }
            var pageEnd = dataStart + size;
            if (pageEnd > file.length) {
                break;
            }
            var link = links.computeIfAbsent(serial, s -> new Link(s, new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));
            var page = new byte[pageEnd - position];
            System.arraycopy(file, position, page, 0, page.length);
            link.pages().add(page);
            var buffer = partial.computeIfAbsent(serial, s -> new ByteArrayOutputStream());
            var cursor = dataStart;
            var lastCompleted = -1;
            for (var i = 0; i < segments; i++) {
                var lace = file[position + 27 + i] & 0xFF;
                buffer.write(file, cursor, lace);
                cursor += lace;
                if (lace < 255) {
                    link.packets().add(buffer.toByteArray());
                    link.granules().add(-1L);
                    buffer.reset();
                    lastCompleted = link.packets().size() - 1;
                }
            }
            if (lastCompleted >= 0) {
                link.granules().set(lastCompleted, granule);
            }
            position = pageEnd;
        }
        return new ArrayList<>(links.values());
    }

    /**
     * Decodes a link and applies the Ogg start/end trimming: the first page that completes an
     * audio packet fixes the position of the first output sample, and the last granule position
     * ends the stream. When that first page is also the last one, its short granule position
     * only trims the end, as ffmpeg reads it.
     */
    static Decoded decode(Link link) {
        try (var decoder = new VorbisDecoder(link.track())) {
            var channels = decoder.layout().channels();
            var audio = link.audio();
            var capacity = 0L;
            var chunks = new ArrayList<float[][]>();
            var sizes = new int[audio.size()];
            var rejected = 0;
            for (var i = 0; i < audio.size(); i++) {
                var index = i;
                try {
                    decoder.decode(new Packet(0, audio.get(i), Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, true),
                            (AudioBuffer buffer) -> {
                                var copy = new float[channels][buffer.frames()];
                                for (var c = 0; c < channels; c++) {
                                    System.arraycopy(buffer.plane(c), 0, copy[c], 0, buffer.frames());
                                }
                                chunks.add(copy);
                                sizes[index] = buffer.frames();
                            });
                } catch (MediaException.Malformed exception) {
                    // Vorbis I 4.3.1: an undecodable packet is discarded from the stream.
                    rejected++;
                }
                capacity += sizes[i];
            }
            var planes = new float[channels][(int) capacity];
            var at = 0;
            for (var chunk : chunks) {
                for (var c = 0; c < channels; c++) {
                    System.arraycopy(chunk[c], 0, planes[c], at, chunk[c].length);
                }
                at += chunk[0].length;
            }
            var granules = link.audioGranules();
            var firstPosition = 0L;
            var cumulative = 0L;
            for (var i = 0; i < audio.size(); i++) {
                cumulative += sizes[i];
                if (granules.get(i) >= 0) {
                    firstPosition = granules.get(i) - cumulative;
                    break;
                }
            }
            if (link.singleAudioPage()) {
                firstPosition = 0;
            }
            var start = (int) Math.max(0, -firstPosition);
            var end = (int) Math.min(capacity, link.lastGranule() - firstPosition);
            end = Math.max(end, start);
            var trimmed = new float[channels][end - start];
            for (var c = 0; c < channels; c++) {
                System.arraycopy(planes[c], start, trimmed[c], 0, end - start);
            }
            return new Decoded(channels, decoder.sampleRate(), trimmed, end - start, sizes, rejected, planes);
        }
    }

    private static long le64(byte[] data, int offset) {
        return le32(data, offset) | le32(data, offset + 4) << 32;
    }

    private static long le32(byte[] data, int offset) {
        return (data[offset] & 0xFFL) | (data[offset + 1] & 0xFFL) << 8 | (data[offset + 2] & 0xFFL) << 16
                | (data[offset + 3] & 0xFFL) << 24;
    }
}
