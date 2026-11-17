/* Writes the unit vectors of Smalt's port of the libopus 1.5.2 neural extensions: the outputs of the activation
   approximations, the LPCNet feature analysis, the Burg cepstrum, the FARGAN vocoder, the deep PLC, the DRED
   RDOVAE decoder and the LACE and NoLACE enhancers on deterministic inputs that Smalt's
   OpusDnnUnitTest generates the same way. build.py links it with the neural build.

   dnnunit <out.bin>

   The output is a sequence of records: a 16-byte zero-padded name, the element count and the element type
   (0 float, 1 int16) as little-endian 32-bit integers, then the elements, little-endian. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "opus_types.h"
#include "lpcnet.h"
#include "lpcnet_private.h"
#include "freq.h"
#include "fargan.h"
#include "nnet.h"
#include "dred_rdovae_dec.h"
#include "dred_rdovae_dec_data.h"
#include "osce.h"
#include "structs.h"
#include "vec.h"

static FILE *out;

static void put32(int v) {
    unsigned char b[4] = {(unsigned char)v, (unsigned char)(v >> 8), (unsigned char)(v >> 16), (unsigned char)(v >> 24)};
    fwrite(b, 1, 4, out);
}

static void record(const char *name, const void *data, int count, int type) {
    char padded[16] = {0};
    strncpy(padded, name, 15);
    fwrite(padded, 1, 16, out);
    put32(count);
    put32(type);
    fwrite(data, type == 0 ? 4 : 2, count, out);
}

static unsigned lcg_state;

/* A uniform float in [-1, 1) from a 32-bit linear congruential generator. */
static float lcg(void) {
    lcg_state = lcg_state * 1664525u + 1013904223u;
    return (float)(int)((lcg_state >> 8) & 0xFFFFFF) * (1.f / 8388608.f) - 1.f;
}

#define FRAMES 60
#define SAMPLES (FRAMES * 160)

/* A voiced test signal: a pulse train whose period changes every 100 ms, with noise, through a two-pole
   resonator, in float arithmetic only. */
static void signal16k(short *s) {
    float y1 = 0, y2 = 0;
    lcg_state = 1;
    for (int n = 0; n < SAMPLES; n++) {
        int period = 60 + ((n / 1600) % 5) * 15;
        float e = (n % period == 0 ? 6000.f : 0.f) + 300.f * lcg();
        float y = e + 1.6f * y1 - 0.8f * y2;
        y2 = y1;
        y1 = y;
        if (y > 32767.f) y = 32767.f;
        if (y < -32768.f) y = -32768.f;
        s[n] = (short)(int)y;
    }
}

int main(int argc, char **argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: dnnunit <out.bin>\n");
        return 2;
    }
    out = fopen(argv[1], "wb");
    static short sig[SAMPLES];
    static float x[SAMPLES];
    signal16k(sig);
    for (int i = 0; i < SAMPLES; i++) x[i] = sig[i];
    record("signal", sig, SAMPLES, 1);

    static float act[2 * 2001];
    for (int i = 0; i <= 2000; i++) {
        float v = (i - 1000) * 0.01f;
        act[i] = tanh_approx(v);
        act[2001 + i] = sigmoid_approx(v);
    }
    record("activations", act, 2 * 2001, 0);

    static float feat[FRAMES * NB_TOTAL_FEATURES];
    LPCNetEncState *enc = lpcnet_encoder_create();
    for (int f = 0; f < FRAMES; f++) {
        lpcnet_compute_single_frame_features_float(enc, &x[f * 160], &feat[f * NB_TOTAL_FEATURES], 0);
    }
    record("features", feat, FRAMES * NB_TOTAL_FEATURES, 0);

    static float burg[FRAMES * 2 * NB_BANDS];
    for (int f = 0; f < FRAMES; f++) burg_cepstral_analysis(&burg[f * 2 * NB_BANDS], &x[f * 160]);
    record("burg", burg, FRAMES * 2 * NB_BANDS, 0);

    static FARGANState fargan;
    static float cont[5 * NB_FEATURES];
    static float pcm0[FARGAN_CONT_SAMPLES];
    static short synth[(FRAMES - 5) * 160];
    fargan_init(&fargan);
    for (int i = 0; i < 5; i++) memcpy(&cont[i * NB_FEATURES], &feat[i * NB_TOTAL_FEATURES], sizeof(float) * NB_FEATURES);
    for (int i = 0; i < FARGAN_CONT_SAMPLES; i++) pcm0[i] = x[480 + i] * (1.f / 32768.f);
    fargan_cont(&fargan, pcm0, cont);
    for (int f = 5; f < FRAMES; f++) fargan_synthesize_int(&fargan, &synth[(f - 5) * 160], &feat[f * NB_TOTAL_FEATURES]);
    record("fargan", synth, (FRAMES - 5) * 160, 1);

    /* Deep PLC: frames received or lost by a fixed pattern; DRED features queued before one burst. */
    static LPCNetPLCState plc;
    static short plcout[SAMPLES];
    lpcnet_plc_init(&plc);
    for (int f = 0; f < FRAMES; f++) {
        int lostf = (f >= 20 && f < 26) || (f >= 36 && f < 39) || f == 45 || (f >= 50 && f < 56);
        if (f == 50) {
            for (int k = 0; k < 4; k++) lpcnet_plc_fec_add(&plc, &feat[(48 + k) * NB_TOTAL_FEATURES]);
            lpcnet_plc_fec_add(&plc, NULL);
        }
        if (lostf) {
            lpcnet_plc_conceal(&plc, &plcout[f * 160]);
        } else {
            memcpy(&plcout[f * 160], &sig[f * 160], sizeof(short) * 160);
            lpcnet_plc_update(&plc, &plcout[f * 160]);
        }
    }
    record("plc", plcout, SAMPLES, 1);

    static RDOVAEDec rdovae;
    static float state[DRED_STATE_DIM];
    static float latents[26 * DRED_LATENT_DIM];
    static float rfeat[2 * 52 * DRED_NUM_FEATURES];
    init_rdovaedec(&rdovae, rdovaedec_arrays);
    lcg_state = 7;
    for (int i = 0; i < DRED_STATE_DIM; i++) state[i] = 2.f * lcg();
    for (int i = 0; i < 26 * DRED_LATENT_DIM; i++) latents[i] = 3.f * lcg();
    DRED_rdovae_decode_all(&rdovae, rfeat, state, latents, 26, 0);
    record("rdovae", rfeat, 2 * 52 * DRED_NUM_FEATURES, 0);

    static OSCEModel osce_model;
    osce_load_models(&osce_model, NULL, 0);
    osce_model.loaded = 1;
    for (int method = 1; method <= 2; method++) {
        static silk_decoder_state dec;
        static silk_decoder_control ctrl;
        static short oout[20 * 320];
        memset(&dec, 0, sizeof(dec));
        memset(&ctrl, 0, sizeof(ctrl));
        osce_reset(&dec.osce, method);
        dec.fs_kHz = 16;
        dec.nb_subfr = 4;
        dec.LPC_order = 16;
        lcg_state = 11 + method;
        for (int fr = 0; fr < 20; fr++) {
            short *xq = &oout[fr * 320];
            memcpy(xq, &sig[fr * 320], sizeof(short) * 320);
            dec.indices.signalType = fr % 5 == 4 ? 1 : 2;
            for (int h = 0; h < 2; h++) {
                for (int i = 0; i < 16; i++) ctrl.PredCoef_Q12[h][i] = (short)(int)(4096.f * 0.9f * lcg() / (i + 1));
            }
            for (int k = 0; k < 4; k++) {
                ctrl.pitchL[k] = 40 + (int)(100.f * (lcg() + 1.f));
                ctrl.Gains_Q16[k] = 65536 * (50 + (int)(40.f * lcg()));
                for (int i = 0; i < 5; i++) ctrl.LTPCoef_Q14[k * 5 + i] = (short)(int)(4000.f * lcg());
            }
            osce_enhance_frame(&osce_model, &dec, &ctrl, xq, 300 + (int)(200.f * lcg()), 0);
        }
        record(method == 1 ? "lace" : "nolace", oout, 20 * 320, 1);
    }
    fclose(out);
    return 0;
}
