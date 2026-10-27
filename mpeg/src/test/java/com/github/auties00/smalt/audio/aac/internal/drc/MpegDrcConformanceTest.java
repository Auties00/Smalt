package com.github.auties00.smalt.audio.aac.internal.drc;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the time domain items of the ISO/IEC 23003-4 conformance (the DRC tool decoder command lines of
 * {@code checkConformance_v0.sh} and {@code checkConformance_v1.sh} without a subband domain) through
 * {@link MpegDrcDecoder} and compares each output with its reference within two 24-bit LSBs, which the
 * references quantized to 24 bits need and the floating point ones meet bit for bit. The harness mirrors the
 * reference tool: 1024-sample frames, the delay of the decoder absorbed, the last incomplete frame read over the
 * previous output and the frames after it zeroed, and the in-band or split payloads read frame by frame.
 *
 * <p>The smoke tier runs the items of {@code iso-23003-4-drc-smoke} (gains, parametric DRC, equalizer, shape
 * filters, multi-band, loudness normalization, downmix, split payloads); the full tier all of them
 * ({@code iso-23003-4-drc}).
 */
@DisplayName("MPEG-D DRC conformance (ISO/IEC 23003-4)")
class MpegDrcConformanceTest {
    /**
     * The command lines of the reference tool, without the output verbosity flag.
     */
    private static final String COMMANDS = """
            -if audio-2ch-48000fs-32bit-sines.wav -ib bs-v0-1-uniDrc.bs -ii if-v0-1-uniDrcInterface.bs -of bs-v0-1__DrcToolDec-if-v0-1__Gc__audio-2ch-48000fs-32bit-sines.wav
            -if audio-2ch-48000fs-32bit-sines.wav -ib bs-v0-2-uniDrc.bs -ii if-v0-1-uniDrcInterface.bs -of bs-v0-2__DrcToolDec-if-v0-1-ld__Gc__audio-2ch-48000fs-32bit-sines.wav -dm 1
            -if audio-2ch-48000fs-32bit-sines.wav -ib bs-v0-3-uniDrc.bs -ii if-v0-1-uniDrcInterface.bs -of bs-v0-3__DrcToolDec-if-v0-1__GcMdDf__audio-2ch-48000fs-32bit-sines.wav
            -if audio-5ch-48000fs-32bit-sinesWN.wav -ib bs-v0-4-uniDrc.bs -ii if-v0-1-uniDrcInterface.bs -of bs-v0-4__DrcToolDec-if-v0-1__GcMbDf__audio-5ch-48000fs-32bit-sinesWN.wav
            -if audio-5ch-48000fs-32bit-sinesWN.wav -ib bs-v0-5-uniDrc.bs -ii if-v0-2-uniDrcInterface.bs -of bs-v0-5__DrcToolDec-if-v0-2__GcMbDxDf__audio-5ch-48000fs-32bit-sinesWN.wav
            -if audio-5ch-48000fs-32bit-sinesWN.wav -ib bs-v0-7-uniDrc.bs -ii if-v0-34-uniDrcInterface.bs -of bs-v0-7__DrcToolDec-if-v0-34__GcDxDf__audio-5ch-48000fs-32bit-sinesWN.wav
            -if audio-2ch-48000fs-32bit-sines.wav -ic bs-v0-8-uniDrcConfig.bs -il bs-v0-8-loudnessInfoSet.bs -ig bs-v0-8-uniDrcGain.bs -ii if-v0-1-uniDrcInterface.bs -of bs-v0-8__DrcToolDec-if-v0-1__Gc__audio-2ch-48000fs-32bit-sines.wav
            -if audio-2ch-48000fs-16bit-modulated.wav -ib bs-v1-1-uniDrc.bs -ii if-v1-1-uniDrcInterface.bs -of bs-v1-1__DrcToolDec-if-v1-1__Pd__audio-2ch-48000fs-16bit-modulated.wav
            -if audio-2ch-48000fs-16bit-modulated.wav -ib bs-v1-2-uniDrc.bs -ii if-v1-1-uniDrcInterface.bs -of bs-v1-2__DrcToolDec-if-v1-1__Pd__audio-2ch-48000fs-16bit-modulated.wav
            -if audio-2ch-48000fs-16bit-modulated.wav -ib bs-v1-3-uniDrc.bs -ii if-v1-1-uniDrcInterface.bs -of bs-v1-3__DrcToolDec-if-v1-1__GcPd__audio-2ch-48000fs-16bit-modulated.wav
            -if audio-2ch-44100fs-16bit-sweep.wav -ib bs-v1-4-uniDrc.bs -ii if-v1-2-uniDrcInterface.bs -of bs-v1-4__DrcToolDec-if-v1-2__Eq__audio-2ch-44100fs-16bit-sweep.wav
            -if audio-5ch-44100fs-16bit-sweep.wav -ib bs-v1-5-uniDrc.bs -ii if-v1-2-uniDrcInterface.bs -of bs-v1-5__DrcToolDec-if-v1-2__Eq__audio-5ch-44100fs-16bit-sweep.wav
            -if audio-2ch-48000fs-16bit-modulated.wav -ib bs-v1-7-uniDrc.bs -ii if-v1-4-uniDrcInterface.bs -of bs-v1-7__DrcToolDec-if-v1-4__PdLn__audio-2ch-48000fs-16bit-modulated.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-8-uniDrc.bs -ii if-v1-3-uniDrcInterface.bs -of bs-v1-8__DrcToolDec-if-v1-3__GcGmSf__audio-2ch-48000fs-16bit-noise.wav
            -if audio-8ch-48000fs-16bit-noise.wav -ib bs-v1-9-uniDrc.bs -ii if-v1-3-uniDrcInterface.bs -of bs-v1-9__DrcToolDec-if-v1-3__GcGmSf__audio-8ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-11-uniDrc.bs -ii if-v1-3-uniDrcInterface.bs -of bs-v1-11__DrcToolDec-if-v1-3__GcGmSf__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-13-uniDrc.bs -ii if-v1-4-uniDrcInterface.bs -of bs-v1-13__DrcToolDec-if-v1-4__Ln__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-14-uniDrc.bs -ii if-v1-3-uniDrcInterface.bs -of bs-v1-14__DrcToolDec-if-v1-3__GcGmSf__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-15-uniDrc.bs -ii if-v1-5-uniDrcInterface.bs -of bs-v1-15__DrcToolDec-if-v1-5__GcGmSf__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-15-uniDrc.bs -ii if-v1-6-uniDrcInterface.bs -of bs-v1-15__DrcToolDec-if-v1-6__GcGmSf__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-16-uniDrc.bs -ii if-v1-5-uniDrcInterface.bs -of bs-v1-16__DrcToolDec-if-v1-5__GcGmSf__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-16-uniDrc.bs -ii if-v1-6-uniDrcInterface.bs -of bs-v1-16__DrcToolDec-if-v1-6__GcGmSf__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-17-uniDrc.bs -ii if-v1-5-uniDrcInterface.bs -of bs-v1-17__DrcToolDec-if-v1-5__GcGmSf__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-17-uniDrc.bs -ii if-v1-6-uniDrcInterface.bs -of bs-v1-17__DrcToolDec-if-v1-6__GcGmSf__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-18-uniDrc.bs -ii if-v1-5-uniDrcInterface.bs -of bs-v1-18__DrcToolDec-if-v1-5__GcMb__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-19-uniDrc.bs -ii if-v1-5-uniDrcInterface.bs -of bs-v1-19__DrcToolDec-if-v1-5__GcMb__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-20-uniDrc.bs -ii if-v1-5-uniDrcInterface.bs -of bs-v1-20__DrcToolDec-if-v1-5__GcMb__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-21-uniDrc.bs -ii if-v1-5-uniDrcInterface.bs -of bs-v1-21__DrcToolDec-if-v1-5__GcMb__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-22-uniDrc.bs -ii if-v1-5-uniDrcInterface.bs -of bs-v1-22__DrcToolDec-if-v1-5__GcMb__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-23-uniDrc.bs -ii if-v1-5-uniDrcInterface.bs -of bs-v1-23__DrcToolDec-if-v1-5__GcMb__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-24-uniDrc.bs -ii if-v1-5-uniDrcInterface.bs -of bs-v1-24__DrcToolDec-if-v1-5__GcMb__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-25-uniDrc.bs -ii if-v1-5-uniDrcInterface.bs -of bs-v1-25__DrcToolDec-if-v1-5__GcMb__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-34-uniDrc.bs -ii if-v1-7-uniDrcInterface.bs -of bs-v1-34__DrcToolDec-if-v1-7__GcGm__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-35-uniDrc.bs -ii if-v1-7-uniDrcInterface.bs -of bs-v1-35__DrcToolDec-if-v1-7__GcGm__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-36-uniDrc.bs -ii if-v1-7-uniDrcInterface.bs -of bs-v1-36__DrcToolDec-if-v1-7__GcGm__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-37-uniDrc.bs -ii if-v1-7-uniDrcInterface.bs -of bs-v1-37__DrcToolDec-if-v1-7__GcGm__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-38-uniDrc.bs -ii if-v1-7-uniDrcInterface.bs -of bs-v1-38__DrcToolDec-if-v1-7__GcGm__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-39-uniDrc.bs -ii if-v1-7-uniDrcInterface.bs -of bs-v1-39__DrcToolDec-if-v1-7__GcGm__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-40-uniDrc.bs -ii if-v1-7-uniDrcInterface.bs -of bs-v1-40__DrcToolDec-if-v1-7__GcGm__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-41-uniDrc.bs -ii if-v1-7-uniDrcInterface.bs -of bs-v1-41__DrcToolDec-if-v1-7__GcGm__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-sines2.wav -ib bs-v1-42-uniDrc.bs -ii if-v1-8-uniDrcInterface.bs -of bs-v1-42__DrcToolDec-if-v1-8__GcGmDxDf__audio-2ch-48000fs-16bit-sines2.wav
            -if audio-2ch-48000fs-16bit-sines2.wav -ib bs-v1-43-uniDrc.bs -ii if-v1-8-uniDrcInterface.bs -of bs-v1-43__DrcToolDec-if-v1-8__GcGmDxDf__audio-2ch-48000fs-16bit-sines2.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-54-uniDrc.bs -ii if-v1-7-uniDrcInterface.bs -of bs-v1-54__DrcToolDec-if-v1-7__GcGm__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-55-uniDrc.bs -ii if-v1-7-uniDrcInterface.bs -of bs-v1-55__DrcToolDec-if-v1-7__GcGm__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-56-uniDrc.bs -ii if-v1-7-uniDrcInterface.bs -of bs-v1-56__DrcToolDec-if-v1-7__GcGm__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-57-uniDrc.bs -ii if-v1-7-uniDrcInterface.bs -of bs-v1-57__DrcToolDec-if-v1-7__GcGm__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-58-uniDrc.bs -ii if-v1-9-uniDrcInterface.bs -of bs-v1-58__DrcToolDec-if-v1-9__LnGcGm__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-59-uniDrc.bs -ii if-v1-7-uniDrcInterface.bs -of bs-v1-59__DrcToolDec-if-v1-7__GcGm__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-60-uniDrc.bs -ii if-v1-7-uniDrcInterface.bs -of bs-v1-60__DrcToolDec-if-v1-7__GcGm__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-61-uniDrc.bs -ii if-v1-7-uniDrcInterface.bs -of bs-v1-61__DrcToolDec-if-v1-7__GcGm__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-62-uniDrc.bs -ii if-v1-7-uniDrcInterface.bs -of bs-v1-62__DrcToolDec-if-v1-7__GcGm__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-63-uniDrc.bs -ii if-v1-7-uniDrcInterface.bs -of bs-v1-63__DrcToolDec-if-v1-7__GcGm__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-64-uniDrc.bs -ii if-v1-7-uniDrcInterface.bs -of bs-v1-64__DrcToolDec-if-v1-7__GcGm__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-sines2.wav -ib bs-v1-65-uniDrc.bs -ii if-v1-8-uniDrcInterface.bs -of bs-v1-65__DrcToolDec-if-v1-8__Dx__audio-2ch-48000fs-16bit-sines2.wav
            -if audio-2ch-48000fs-16bit-sines2.wav -ib bs-v1-66-uniDrc.bs -ii if-v1-8-uniDrcInterface.bs -of bs-v1-66__DrcToolDec-if-v1-8__Dx__audio-2ch-48000fs-16bit-sines2.wav
            -if audio-2ch-48000fs-16bit-sines2.wav -ib bs-v1-67-uniDrc.bs -ii if-v1-8-uniDrcInterface.bs -of bs-v1-67__DrcToolDec-if-v1-8__Dx__audio-2ch-48000fs-16bit-sines2.wav
            -if audio-2ch-48000fs-16bit-sines2.wav -ib bs-v1-68-uniDrc.bs -ii if-v1-8-uniDrcInterface.bs -of bs-v1-68__DrcToolDec-if-v1-8__Dx__audio-2ch-48000fs-16bit-sines2.wav
            -if audio-2ch-48000fs-16bit-sines2.wav -ib bs-v1-69-uniDrc.bs -ii if-v1-8-uniDrcInterface.bs -of bs-v1-69__DrcToolDec-if-v1-8__Dx__audio-2ch-48000fs-16bit-sines2.wav
            -if audio-2ch-48000fs-16bit-sines2.wav -ib bs-v1-70-uniDrc.bs -ii if-v1-8-uniDrcInterface.bs -of bs-v1-70__DrcToolDec-if-v1-8__Dx__audio-2ch-48000fs-16bit-sines2.wav
            -if audio-2ch-48000fs-16bit-sines2.wav -ib bs-v1-71-uniDrc.bs -ii if-v1-8-uniDrcInterface.bs -of bs-v1-71__DrcToolDec-if-v1-8__Dx__audio-2ch-48000fs-16bit-sines2.wav
            -if audio-2ch-48000fs-16bit-sines2.wav -ib bs-v1-72-uniDrc.bs -ii if-v1-8-uniDrcInterface.bs -of bs-v1-72__DrcToolDec-if-v1-8__Dx__audio-2ch-48000fs-16bit-sines2.wav
            -if audio-2ch-48000fs-16bit-sines2.wav -ib bs-v1-73-uniDrc.bs -ii if-v1-8-uniDrcInterface.bs -of bs-v1-73__DrcToolDec-if-v1-8__Dx__audio-2ch-48000fs-16bit-sines2.wav
            -if audio-2ch-48000fs-16bit-sines2.wav -ib bs-v1-74-uniDrc.bs -ii if-v1-8-uniDrcInterface.bs -of bs-v1-74__DrcToolDec-if-v1-8__Dx__audio-2ch-48000fs-16bit-sines2.wav
            -if audio-2ch-48000fs-16bit-sines2.wav -ib bs-v1-75-uniDrc.bs -ii if-v1-8-uniDrcInterface.bs -of bs-v1-75__DrcToolDec-if-v1-8__Dx__audio-2ch-48000fs-16bit-sines2.wav
            -if audio-2ch-48000fs-16bit-sines2.wav -ib bs-v1-76-uniDrc.bs -ii if-v1-8-uniDrcInterface.bs -of bs-v1-76__DrcToolDec-if-v1-8__Dx__audio-2ch-48000fs-16bit-sines2.wav
            -if audio-2ch-48000fs-16bit-sines2.wav -ib bs-v1-77-uniDrc.bs -ii if-v1-8-uniDrcInterface.bs -of bs-v1-77__DrcToolDec-if-v1-8__Dx__audio-2ch-48000fs-16bit-sines2.wav
            -if audio-2ch-48000fs-16bit-sines2.wav -ib bs-v1-78-uniDrc.bs -ii if-v1-8-uniDrcInterface.bs -of bs-v1-78__DrcToolDec-if-v1-8__Dx__audio-2ch-48000fs-16bit-sines2.wav
            -if audio-2ch-48000fs-16bit-sines2.wav -ib bs-v1-79-uniDrc.bs -ii if-v1-8-uniDrcInterface.bs -of bs-v1-79__DrcToolDec-if-v1-8__Dx__audio-2ch-48000fs-16bit-sines2.wav
            -if audio-2ch-48000fs-16bit-sines2.wav -ib bs-v1-80-uniDrc.bs -ii if-v1-8-uniDrcInterface.bs -of bs-v1-80__DrcToolDec-if-v1-8__Dx__audio-2ch-48000fs-16bit-sines2.wav
            -if audio-2ch-48000fs-16bit-sines2.wav -ib bs-v1-81-uniDrc.bs -ii if-v1-8-uniDrcInterface.bs -of bs-v1-81__DrcToolDec-if-v1-8__Dx__audio-2ch-48000fs-16bit-sines2.wav
            -if audio-2ch-48000fs-16bit-sines2.wav -ib bs-v1-82-uniDrc.bs -ii if-v1-8-uniDrcInterface.bs -of bs-v1-82__DrcToolDec-if-v1-8__Dx__audio-2ch-48000fs-16bit-sines2.wav
            -if audio-2ch-48000fs-16bit-sines2.wav -ib bs-v1-83-uniDrc.bs -ii if-v1-8-uniDrcInterface.bs -of bs-v1-83__DrcToolDec-if-v1-8__Dx__audio-2ch-48000fs-16bit-sines2.wav
            -if audio-5ch-48000fs-32bit-sinesWN.wav -ib bs-v1-84-uniDrc.bs -ii if-v1-8-uniDrcInterface.bs -of bs-v1-84__DrcToolDec-if-v1-8__Dx__audio-5ch-48000fs-32bit-sinesWN.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-85-uniDrc.bs -ii if-v1-10-uniDrcInterface.bs -of bs-v1-85__DrcToolDec-if-v1-10__GcEq__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-88-uniDrc.bs -ii if-v1-11-uniDrcInterface.bs -of bs-v1-88__DrcToolDec-if-v1-11__Eq__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-91-uniDrc.bs -ii if-v1-11-uniDrcInterface.bs -of bs-v1-91__DrcToolDec-if-v1-11__Eq__audio-2ch-48000fs-16bit-noise.wav
            -if audio-5ch-48000fs-32bit-sinesWN.wav -ib bs-v1-92-uniDrc.bs -ii if-v1-12-uniDrcInterface.bs -of bs-v1-92__DrcToolDec-if-v1-12__EqDx__audio-5ch-48000fs-32bit-sinesWN.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-117-uniDrc.bs -ii if-v1-13-uniDrcInterface.bs -of bs-v1-117__DrcToolDec-if-v1-13__LnLe__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-118-uniDrc.bs -ii if-v1-3-uniDrcInterface.bs -of bs-v1-118__DrcToolDec-if-v1-3__GcGmNrSf__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-119-uniDrc.bs -ii if-v1-14-uniDrcInterface.bs -of bs-v1-119__DrcToolDec-if-v1-14__LnGcGm__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-119-uniDrc.bs -ii if-v1-15-uniDrcInterface.bs -of bs-v1-119__DrcToolDec-if-v1-15__LnGcGm__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-119-uniDrc.bs -ii if-v1-16-uniDrcInterface.bs -of bs-v1-119__DrcToolDec-if-v1-16__LnGcGm__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -ib bs-v1-120-uniDrc.bs -ii if-v1-15-uniDrcInterface.bs -of bs-v1-120__DrcToolDec-if-v1-15__Ln__audio-2ch-48000fs-16bit-noise.wav
            -if audio-2ch-48000fs-16bit-noise.wav -il bs-v1-121-loudnessInfoSet.bs -ii if-v1-15-uniDrcInterface.bs -of bs-v1-121__DrcToolDec-if-v1-15__Ln__audio-2ch-48000fs-16bit-noise.wav
            -if audio-5ch-48000fs-32bit-sinesWN.wav -ib bs-v1-122-uniDrc.bs -ii if-v1-17-uniDrcInterface.bs -of bs-v1-122__DrcToolDec-if-v1-17__GcMb__audio-5ch-48000fs-32bit-sinesWN.wav
            """;

    private record Wav(float[][] planes, int rate, boolean isFloat) {
    }

    @TestFactory
    @DisplayName("the smoke items match their references")
    Stream<DynamicTest> smoke() {
        return items(Conformance.archive("iso-23003-4-drc-smoke"));
    }

    @TestFactory
    @DisplayName("every time domain item matches its reference")
    Stream<DynamicTest> full() {
        return items(Conformance.archive("iso-23003-4-drc"));
    }

    private static Stream<DynamicTest> items(Conformance.Archive archive) {
        var members = new HashMap<String, String>();
        for (var member : archive.members()) {
            members.put(member.substring(member.lastIndexOf('/') + 1), member);
        }
        return COMMANDS.lines().filter(line -> !line.isBlank()).map(String::trim)
                .filter(line -> members.containsKey(argument(line, "-of")))
                .map(line -> DynamicTest.dynamicTest(argument(line, "-of"), () -> run(archive, members, line)));
    }

    private static String argument(String line, String flag) {
        var tokens = line.split(" ");
        for (var i = 0; i < tokens.length - 1; i++) {
            if (tokens[i].equals(flag)) {
                return tokens[i + 1];
            }
        }
        return null;
    }

    private static byte[] read(Conformance.Archive archive, HashMap<String, String> members, String name) {
        return name == null ? null : Fixtures.read(archive.fetch(members.get(name)));
    }

    private static void run(Conformance.Archive archive, HashMap<String, String> members, String line) {
        var input = readWav(read(archive, members, argument(line, "-if")));
        var delayMode = argument(line, "-dm") == null ? 0 : Integer.parseInt(argument(line, "-dm"));
        var frameSize = argument(line, "-af") == null ? 1024 : Integer.parseInt(argument(line, "-af"));
        var channels = input.planes.length;
        var length = input.planes[0].length;
        var iface = read(archive, members, argument(line, "-ii"));
        var inBand = argument(line, "-ib") != null;
        MpegDrcDecoder decoder;
        AacBitReader reader;
        if (inBand) {
            decoder = MpegDrcDecoder.inBand(input.rate, channels, frameSize, delayMode, iface);
            reader = new AacBitReader(read(archive, members, argument(line, "-ib")));
        } else {
            var gains = read(archive, members, argument(line, "-ig"));
            decoder = MpegDrcDecoder.split(input.rate, channels, frameSize, delayMode,
                    read(archive, members, argument(line, "-ic")), read(archive, members, argument(line, "-il")),
                    gains != null, iface);
            reader = gains != null ? new AacBitReader(gains) : null;
        }
        var outputs = new ArrayList<float[][]>();
        var frame = new float[channels][frameSize];
        var flushing = -1;
        var complete = false;
        for (var f = 0; flushing < 0 || (long) f * frameSize < length + flushing; f++) {
            // The reference reads the valid samples of the last frame over the previous output and zeroes the
            // frame once after the last read.
            var start = f * frameSize;
            var count = Math.max(0, Math.min(frameSize, length - start));
            for (var c = 0; c < channels; c++) {
                if (count > 0) {
                    System.arraycopy(input.planes[c], start, frame[c], 0, count);
                } else if (start - frameSize < length) {
                    Arrays.fill(frame[c], 0);
                }
            }
            var source = reader != null && reader.bitsLeft() >= 8 ? reader : null;
            if (inBand) {
                try {
                    decoder.processInBand(frame, source);
                } catch (MediaException.Malformed exception) {
                    if (!String.valueOf(exception.getMessage()).contains("truncated")) {
                        throw exception;
                    }
                    complete = true;
                }
            } else {
                decoder.processGain(frame, source);
                if (source != null) {
                    source.alignToByte();
                }
            }
            if (flushing < 0) {
                flushing = decoder.delay();
            }
            var copy = new float[decoder.outputChannels()][];
            for (var c = 0; c < copy.length; c++) {
                copy[c] = frame[c].clone();
            }
            outputs.add(copy);
            if (complete) {
                break;
            }
        }
        var name = argument(line, "-of");
        var reference = readWav(read(archive, members, name));
        assertEquals(reference.planes.length, decoder.outputChannels(), name + " channels");
        var total = Math.min(length, outputs.size() * frameSize - flushing);
        var limit = Math.pow(2, -22);
        for (var c = 0; c < reference.planes.length; c++) {
            assertEquals(reference.planes[c].length, total, name + " length");
            var peak = 0.0;
            var at = -1;
            for (var k = 0; k < total; k++) {
                var p = k + flushing;
                var value = outputs.get(p / frameSize)[c][p % frameSize];
                if (!reference.isFloat) {
                    value = Math.clamp(value, -1.0f, 1.0f);
                }
                var difference = Math.abs((double) reference.planes[c][k] - value);
                if (difference > peak) {
                    peak = difference;
                    at = k;
                }
            }
            assertTrue(peak <= limit, name + " channel " + c + ": error " + peak + " at sample " + at);
        }
    }

    private static Wav readWav(byte[] data) {
        var position = 12;
        int channels = 0;
        int bits = 0;
        int format = 0;
        int rate = 0;
        while (position + 8 <= data.length) {
            var id = new String(data, position, 4, StandardCharsets.ISO_8859_1);
            var size = ByteBuffer.wrap(data, position + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (id.equals("fmt ")) {
                var buffer = ByteBuffer.wrap(data, position + 8, size).order(ByteOrder.LITTLE_ENDIAN);
                format = buffer.getShort() & 0xFFFF;
                channels = buffer.getShort();
                rate = buffer.getInt();
                buffer.getInt();
                buffer.getShort();
                bits = buffer.getShort();
                if (format == 0xFFFE && size >= 26) {
                    buffer.getShort();
                    buffer.getShort();
                    buffer.getInt();
                    format = buffer.getShort() & 0xFFFF;
                }
            } else if (id.equals("data")) {
                var bytesPerSample = bits / 8;
                var frames = Math.min(size, data.length - position - 8) / (bytesPerSample * channels);
                var planes = new float[channels][frames];
                var p = position + 8;
                var view = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                for (var i = 0; i < frames; i++) {
                    for (var c = 0; c < channels; c++) {
                        planes[c][i] = switch (bits) {
                            case 16 -> view.getShort(p) / 32768f;
                            case 24 -> ((data[p] & 0xFF) | ((data[p + 1] & 0xFF) << 8) | (data[p + 2] << 16))
                                    / 8388608f;
                            case 32 -> format == 3 ? view.getFloat(p) : view.getInt(p) / 2147483648f;
                            default -> throw new IllegalArgumentException("unsupported bits " + bits);
                        };
                        p += bytesPerSample;
                    }
                }
                return new Wav(planes, rate, format == 3);
            }
            position += 8 + size + (size & 1);
        }
        throw new IllegalArgumentException("no data chunk");
    }
}
