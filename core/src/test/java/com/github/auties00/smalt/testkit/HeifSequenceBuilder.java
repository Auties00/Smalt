package com.github.auties00.smalt.testkit;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.github.auties00.smalt.testkit.HeifBuilder.box;
import static com.github.auties00.smalt.testkit.HeifBuilder.fullBox;
import static com.github.auties00.smalt.testkit.HeifBuilder.u16;
import static com.github.auties00.smalt.testkit.HeifBuilder.u32;

/**
 * Builds HEIF image sequences ({@code msf1}, {@code avis}) box by box: one {@code pict} track per
 * call to {@link #track}, each sample in its own chunk, with the track references and the sample
 * entry child boxes the test asks for. The layout is {@code ftyp, mdat, moov}.
 */
public final class HeifSequenceBuilder {
    /** One track. */
    private static final class Track {
        int id;
        String entry;
        int width;
        int height;
        List<byte[]> entryChildren = new ArrayList<>();
        List<byte[]> samples;
        int delta;
        Map<String, int[]> references = new TreeMap<>();
    }

    private final String majorBrand;
    private final String[] compatible;
    private final int timescale;
    private final List<Track> tracks = new ArrayList<>();

    /**
     * Starts a sequence.
     *
     * @param majorBrand the major brand, such as {@code "msf1"} or {@code "avis"}
     * @param timescale the media timescale of every track
     * @param compatible the compatible brands
     */
    public HeifSequenceBuilder(String majorBrand, int timescale, String... compatible) {
        this.majorBrand = majorBrand;
        this.compatible = compatible;
        this.timescale = timescale;
    }

    /**
     * Adds a track and returns its identifier.
     *
     * @param entry the sample entry type, such as {@code "hvc1"} or {@code "av01"}
     * @param width the track width
     * @param height the track height
     * @param delta the duration of every sample, in timescale units
     * @param samples the samples in decoding order
     * @param entryChildren the boxes of the sample entry, such as {@code hvcC} or {@code auxi}
     * @return the track identifier
     */
    public int track(String entry, int width, int height, int delta, List<byte[]> samples, byte[]... entryChildren) {
        var track = new Track();
        track.id = tracks.size() + 1;
        track.entry = entry;
        track.width = width;
        track.height = height;
        track.delta = delta;
        track.samples = List.copyOf(samples);
        track.entryChildren.addAll(List.of(entryChildren));
        tracks.add(track);
        return track.id;
    }

    /**
     * Adds a track reference.
     *
     * @param type the reference type, such as {@code "auxl"} or {@code "prem"}
     * @param from the referencing track
     * @param to the referenced tracks
     * @return this builder
     */
    public HeifSequenceBuilder reference(String type, int from, int... to) {
        tracks.get(from - 1).references.put(type, to.clone());
        return this;
    }

    /**
     * {@return an {@code auxi} box naming an auxiliary track type}
     *
     * @param urn the URN, such as {@code "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha"}
     */
    public static byte[] auxi(String urn) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(urn.getBytes(StandardCharsets.UTF_8));
        out.write(0);
        return fullBox("auxi", 0, 0, out.toByteArray());
    }

    /**
     * {@return the file}
     */
    public byte[] build() {
        var ftypPayload = new ByteArrayOutputStream();
        ftypPayload.writeBytes(majorBrand.getBytes(StandardCharsets.ISO_8859_1));
        u32(ftypPayload, 0);
        for (var brand : compatible) {
            ftypPayload.writeBytes(brand.getBytes(StandardCharsets.ISO_8859_1));
        }
        var ftyp = box("ftyp", ftypPayload.toByteArray());
        var mdat = new ByteArrayOutputStream();
        var offsets = new ArrayList<long[]>();
        var dataStart = ftyp.length + 8L;
        for (var track : tracks) {
            var trackOffsets = new long[track.samples.size()];
            for (var i = 0; i < trackOffsets.length; i++) {
                trackOffsets[i] = dataStart + mdat.size();
                mdat.writeBytes(track.samples.get(i));
            }
            offsets.add(trackOffsets);
        }
        var moov = new ByteArrayOutputStream();
        var mvhd = new ByteArrayOutputStream();
        u32(mvhd, 0);
        u32(mvhd, 0);
        u32(mvhd, timescale);
        u32(mvhd, duration(tracks.getFirst()));
        u32(mvhd, 0x00010000);
        u16(mvhd, 0x0100);
        mvhd.writeBytes(new byte[10]);
        mvhd.writeBytes(identity());
        mvhd.writeBytes(new byte[24]);
        u32(mvhd, tracks.size() + 1);
        moov.writeBytes(fullBox("mvhd", 0, 0, mvhd.toByteArray()));
        for (var i = 0; i < tracks.size(); i++) {
            moov.writeBytes(trak(tracks.get(i), offsets.get(i)));
        }
        var out = new ByteArrayOutputStream();
        out.writeBytes(ftyp);
        out.writeBytes(box("mdat", mdat.toByteArray()));
        out.writeBytes(box("moov", moov.toByteArray()));
        return out.toByteArray();
    }

    private long duration(Track track) {
        return (long) track.delta * track.samples.size();
    }

    private static byte[] identity() {
        var out = new ByteArrayOutputStream();
        for (var value : new long[]{0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000}) {
            u32(out, value);
        }
        return out.toByteArray();
    }

    private byte[] trak(Track track, long[] offsets) {
        var tkhd = new ByteArrayOutputStream();
        u32(tkhd, 0);
        u32(tkhd, 0);
        u32(tkhd, track.id);
        u32(tkhd, 0);
        u32(tkhd, duration(track));
        tkhd.writeBytes(new byte[8]);
        u16(tkhd, 0);
        u16(tkhd, 0);
        u16(tkhd, 0);
        u16(tkhd, 0);
        tkhd.writeBytes(identity());
        u32(tkhd, (long) track.width << 16);
        u32(tkhd, (long) track.height << 16);
        var trak = new ByteArrayOutputStream();
        trak.writeBytes(fullBox("tkhd", 0, 3, tkhd.toByteArray()));
        if (!track.references.isEmpty()) {
            var tref = new ByteArrayOutputStream();
            for (var reference : track.references.entrySet()) {
                var ids = new ByteArrayOutputStream();
                for (var id : reference.getValue()) {
                    u32(ids, id);
                }
                tref.writeBytes(box(reference.getKey(), ids.toByteArray()));
            }
            trak.writeBytes(box("tref", tref.toByteArray()));
        }
        var mdhd = new ByteArrayOutputStream();
        u32(mdhd, 0);
        u32(mdhd, 0);
        u32(mdhd, timescale);
        u32(mdhd, duration(track));
        u16(mdhd, 0x55C4);
        u16(mdhd, 0);
        var vmhd = fullBox("vmhd", 0, 1, new byte[8]);
        var dref = new ByteArrayOutputStream();
        u32(dref, 1);
        dref.writeBytes(fullBox("url ", 0, 1, new byte[0]));
        var dinf = box("dinf", fullBox("dref", 0, 0, dref.toByteArray()));
        var minf = box("minf", concat(vmhd, dinf, stbl(track, offsets)));
        var mdia = box("mdia", concat(fullBox("mdhd", 0, 0, mdhd.toByteArray()), HeifBuilder.hdlr("pict"), minf));
        trak.writeBytes(mdia);
        return box("trak", trak.toByteArray());
    }

    private static byte[] stbl(Track track, long[] offsets) {
        var entry = new ByteArrayOutputStream();
        entry.writeBytes(new byte[6]);
        u16(entry, 1);
        entry.writeBytes(new byte[16]);
        u16(entry, track.width);
        u16(entry, track.height);
        u32(entry, 0x00480000);
        u32(entry, 0x00480000);
        u32(entry, 0);
        u16(entry, 1);
        entry.writeBytes(new byte[32]);
        u16(entry, 0x0018);
        u16(entry, 0xFFFF);
        for (var child : track.entryChildren) {
            entry.writeBytes(child);
        }
        var stsd = new ByteArrayOutputStream();
        u32(stsd, 1);
        stsd.writeBytes(box(track.entry, entry.toByteArray()));
        var stts = new ByteArrayOutputStream();
        u32(stts, 1);
        u32(stts, track.samples.size());
        u32(stts, track.delta);
        var stsc = new ByteArrayOutputStream();
        u32(stsc, 1);
        u32(stsc, 1);
        u32(stsc, 1);
        u32(stsc, 1);
        var stsz = new ByteArrayOutputStream();
        u32(stsz, 0);
        u32(stsz, track.samples.size());
        for (var sample : track.samples) {
            u32(stsz, sample.length);
        }
        var stco = new ByteArrayOutputStream();
        u32(stco, offsets.length);
        for (var offset : offsets) {
            u32(stco, offset);
        }
        return box("stbl", concat(fullBox("stsd", 0, 0, stsd.toByteArray()), fullBox("stts", 0, 0, stts.toByteArray()),
                fullBox("stsc", 0, 0, stsc.toByteArray()), fullBox("stsz", 0, 0, stsz.toByteArray()),
                fullBox("stco", 0, 0, stco.toByteArray())));
    }

    private static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
