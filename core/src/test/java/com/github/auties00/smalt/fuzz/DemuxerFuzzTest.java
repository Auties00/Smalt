package com.github.auties00.smalt.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.testkit.fuzz.FuzzMedia;

/**
 * Fuzz targets of the demuxers, one per container format that Smalt reads. Each input is detected, demuxed to
 * the last packet, seeked, probed and, where the format carries tags, tag-edited (see {@link FuzzMedia#demuxer}).
 */
class DemuxerFuzzTest {
    @FuzzTest
    void mp4(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.MP4, data);
    }

    @FuzzTest
    void matroska(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.MATROSKA, data);
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
    void subRip(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.SUBRIP, data);
    }

    @FuzzTest
    void webVtt(byte[] data) {
        FuzzMedia.demuxer(ContainerFormat.WEBVTT, data);
    }
}
