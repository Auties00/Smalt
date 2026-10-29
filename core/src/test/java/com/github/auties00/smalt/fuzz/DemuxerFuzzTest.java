package com.github.auties00.smalt.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.DecryptionKeys;
import com.github.auties00.smalt.testkit.fuzz.FuzzMedia;
import com.github.auties00.smalt.testkit.fuzz.FuzzPresentations;

import java.util.HexFormat;

/**
 * Fuzz targets of the demuxers, one per container format that Smalt reads. Each input is detected, demuxed to
 * the last packet, seeked, probed and, where the format carries tags, tag-edited (see {@link FuzzMedia#demuxer}).
 */
class DemuxerFuzzTest {
    // the key of the encrypted seeds, for every key identifier
    private static final DecryptionKeys ENCRYPTED = DecryptionKeys.single(HexFormat.of()
            .parseHex("ebdd62f16814d27b68ef122afce4ae3c"));

    @FuzzTest
    void mp4(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.MP4, data);
    }

    @FuzzTest
    void matroska(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.MATROSKA, data);
    }

    @FuzzTest
    void matroskaLinked(byte[] data) {
        FuzzMedia.linkedMatroska(data);
    }

    @FuzzTest
    void mp4Encrypted(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.MP4, data, ENCRYPTED);
    }

    @FuzzTest
    void matroskaEncrypted(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.MATROSKA, data, ENCRYPTED);
    }

    @FuzzTest
    void ogg(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.OGG, data);
    }

    @FuzzTest
    void mpegAudio(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.MPEG_AUDIO, data);
    }

    @FuzzTest
    void adts(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.ADTS, data);
    }

    @FuzzTest
    void wav(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.WAV, data);
    }

    @FuzzTest
    void flac(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.FLAC, data);
    }

    @FuzzTest
    void amr(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.AMR, data);
    }

    @FuzzTest
    void aiff(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.AIFF, data);
    }

    @FuzzTest
    void caf(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.CAF, data);
    }

    @FuzzTest
    void w64(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.W64, data);
    }

    @FuzzTest
    void au(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.AU, data);
    }

    @FuzzTest
    void loas(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.LOAS, data);
    }

    @FuzzTest
    void adif(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.ADIF, data);
    }

    @FuzzTest
    void ivf(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.IVF, data);
    }

    @FuzzTest
    void avi(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.AVI, data);
    }

    @FuzzTest
    void mpegTs(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.MPEG_TS, data);
    }

    @FuzzTest
    void ac3(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.AC3, data);
    }

    @FuzzTest
    void dts(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.DTS, data);
    }

    @FuzzTest
    void subRip(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.SUBRIP, data);
    }

    @FuzzTest
    void webVtt(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.WEBVTT, data);
    }

    @FuzzTest
    void h264(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.H264, data);
    }

    @FuzzTest
    void hevc(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.HEVC, data);
    }

    @FuzzTest
    void av1(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.AV1, data);
    }

    @FuzzTest
    void av1AnnexB(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.AV1_ANNEX_B, data);
    }

    @FuzzTest
    void mpeg4Video(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.MPEG4_VIDEO, data);
    }

    @FuzzTest
    void h263(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.H263, data);
    }

    @FuzzTest
    void y4m(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.Y4M, data);
    }

    @FuzzTest
    void sup(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.SUP, data);
    }

    @FuzzTest
    void vobSub(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.VOBSUB, data);
    }

    @FuzzTest
    void hls(byte[] data) {
        FuzzPresentations.demuxer(ContainerFormat.HLS, data);
    }

    @FuzzTest
    void dash(byte[] data) {
        FuzzPresentations.demuxer(ContainerFormat.DASH, data);
    }
}
