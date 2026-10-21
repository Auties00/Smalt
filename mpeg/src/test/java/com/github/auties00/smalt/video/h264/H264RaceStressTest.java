package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.Consumer;

class H264RaceStressTest {
    @Test
    void stress() {
        for (var name : System.getProperty("stress.resource", "hd12-cabac-wp").split(";")) {
            run(name);
        }
    }

    void run(String name) {
        var bytes = name.startsWith("chroma422/") ? null : H264HighBitDepthTest.resource(name);
        var units = H264TestStreams.accessUnits(H264TestStreams.nalUnits(bytes));
        var clean = new ArrayList<Packet>();
        for (var unit : units) {
            clean.add(new Packet(1, H264TestStreams.annexB(unit), Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, false));
        }
        var track = H264TestStreams.track(Optional.empty(), Optional.empty());
        var reference = H264TestStreams.decode(track, clean, 1);
        var random = new Random(4);
        var failures = 0;
        var trials = Integer.getInteger("stress.trials", 2000);
        for (var trial = 0; trial < trials; trial++) {
            var packets = new ArrayList<>(clean);
            for (var i = 0; i < 4; i++) {
                switch (random.nextInt(3)) {
                    case 0 -> packets.remove(random.nextInt(packets.size()));
                    case 1 -> packets.add(random.nextInt(packets.size()), packets.get(random.nextInt(packets.size())));
                    default -> {
                        var a = random.nextInt(packets.size());
                        var b = random.nextInt(packets.size());
                        var swap = packets.get(a);
                        packets.set(a, packets.get(b));
                        packets.set(b, swap);
                    }
                }
            }
            var frames = new ArrayList<byte[]>();
            Consumer<VideoFrame> collect = frame -> frames.add(H264TestStreams.serialize((com.github.auties00.smalt.image.YuvPicture) frame.picture()));
            try (var decoder = new H264Decoder(track, H264DecoderOptions.builder().threads(3).build())) {
                for (var packet : packets) {
                    try { decoder.decode(packet, f -> { }); } catch (MediaException e) { }
                }
                for (var packet : clean) {
                    try { decoder.decode(packet, collect); } catch (MediaException e) { }
                }
                try { decoder.flush(collect); } catch (MediaException e) { }
            }
            var offset = frames.size() - reference.size();
            var bad = offset < 0;
            for (var i = 0; !bad && i < reference.size(); i++) {
                bad = !java.util.Arrays.equals(reference.get(i).samples(), frames.get(offset + i));
            }
            if (bad) {
                failures++;
                System.out.println("STRESS failure at trial " + trial);
            }
        }
        System.out.println("STRESS " + name + " failures " + failures + " of " + trials);
    }
}
