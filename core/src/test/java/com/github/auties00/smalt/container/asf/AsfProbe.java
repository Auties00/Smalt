package com.github.auties00.smalt.container.asf;

import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Compares {@link AsfDemuxer} with ffprobe: the same streams (ffprobe's attached pictures aside), and stream by stream
 * the same packets in the same order, each with ffprobe's decoding timestamp, size, payload CRC-32 and key flag. H.264
 * packets, which the demuxer returns length-prefixed where ffprobe returns the stored Annex B, are compared NAL unit by
 * NAL unit.
 */
final class AsfProbe {
    private AsfProbe() {
    }

    /** The codecs ffprobe names, by ffprobe codec name; the ones Smalt does not know are unknown audio or video. */
    private static final Map<String, Codec> CODECS = Map.ofEntries(Map.entry("vc1", Codec.VC1),
            Map.entry("wmv3", Codec.VC1), Map.entry("h264", Codec.H264), Map.entry("mpeg4", Codec.MPEG4_PART2),
            Map.entry("mjpeg", Codec.MJPEG), Map.entry("mp3", Codec.MP3), Map.entry("mp2", Codec.MP2),
            Map.entry("ac3", Codec.AC3), Map.entry("aac", Codec.AAC), Map.entry("pcm_s16le", Codec.PCM_S16LE),
            Map.entry("pcm_u8", Codec.PCM_U8), Map.entry("adpcm_ima_wav", Codec.ADPCM_IMA_WAV),
            Map.entry("adpcm_ms", Codec.ADPCM_MS), Map.entry("pcm_alaw", Codec.PCM_ALAW),
            Map.entry("pcm_mulaw", Codec.PCM_MULAW), Map.entry("flac", Codec.FLAC));

    /** Runs the comparison on a file and returns the number of packets compared. */
    static int compare(Path file) {
        var probe = ContainerProbe.probe(file);
        var streams = probe.streams().stream()
                .filter(stream -> !"1".equals(stream.get("disposition:attached_pic"))).toList();
        List<byte[]> probedData = null;
        var compared = 0;
        try (var source = MediaSource.of(file); var demuxer = new AsfDemuxer(source)) {
            var tracks = demuxer.tracks();
            assertEquals(streams.size(), tracks.size(), "track count of " + file.getFileName());
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
            for (var index = 0; index < tracks.size(); index++) {
                var track = tracks.get(index);
                var stream = streams.get(index);
                checkTrack(file, track, stream);
                var ours = packets.stream().filter(packet -> packet.trackId() == track.id()).toList();
                var theirs = probe.packetsOf(stream.index());
                assertFalse(theirs.isEmpty(), "ffprobe reads packets of stream " + stream.index());
                var expected = new ArrayList<String>();
                var actual = new ArrayList<String>();
                var lengthPrefixed = track.codec() == Codec.H264 && track.codecConfig().isPresent();
                if (lengthPrefixed && probedData == null) {
                    probedData = ContainerProbe.packetData(file);
                }
                for (var packet : theirs) {
                    var data = lengthPrefixed ? nalUnits(probedData.get(probe.packets().indexOf(packet)), false)
                            : packet.size() + " " + packet.hash();
                    expected.add(packet.dts() + " " + data + (packet.key() ? " K" : ""));
                }
                for (var packet : ours) {
                    var data = lengthPrefixed ? nalUnits(packet.data(), true)
                            : packet.size() + " " + ContainerProbe.crc32(packet.data());
                    actual.add(packet.dts() + " " + data + (packet.keyframe() ? " K" : ""));
                }
                assertEquals(String.join("\n", expected), String.join("\n", actual),
                        "packets of track " + track.id() + " of " + file.getFileName());
                compared += ours.size();
            }
        }
        return compared;
    }

    private static void checkTrack(Path file, Track track, ContainerProbe.ProbeStream stream) {
        var name = file.getFileName() + " track " + track.id();
        var codec = stream.codecName() == null ? null : CODECS.get(stream.codecName());
        if (codec == null) {
            assertEquals(track instanceof VideoTrack ? Codec.UNKNOWN_VIDEO : Codec.UNKNOWN_AUDIO, track.codec(),
                    name + " (" + stream.codecName() + ")");
        } else {
            assertEquals(codec, track.codec(), name);
        }
        switch (track) {
            case VideoTrack video -> {
                assertEquals(stream.get("width"), Integer.toString(video.width()), name + " width");
                assertEquals(stream.get("height"), Integer.toString(video.height()), name + " height");
            }
            case AudioTrack audio -> {
                assertEquals(stream.get("sample_rate"), Integer.toString(audio.sampleRate()), name + " sample rate");
                assertEquals(stream.get("channels"), Integer.toString(audio.layout().map(layout -> layout.channels()).orElse(0)),
                        name + " channels");
            }
            default -> {
            }
        }
    }

    /** Lists the NAL units of an Annex B or length-prefixed access unit as hexadecimal text. */
    private static String nalUnits(byte[] data, boolean lengthPrefixed) {
        var units = new ArrayList<String>();
        NalUnits.Sink sink = (bytes, offset, length) -> units.add(HexFormat.of().formatHex(bytes, offset,
                offset + length));
        if (lengthPrefixed) {
            NalUnits.forEachLengthPrefixed(data, 4, sink);
        } else {
            NalUnits.forEachAnnexB(data, sink);
        }
        return String.join(",", units);
    }
}
