/* Written for Smalt's test tooling; it links the reference software, which it does not contain. Runs the CELP core of the ISO/IEC 14496-3 reference decoder (src_lpc of the ISO/IEC 23003-3 reference software) over
   the frames of a file, without its MP4 layer:
     harness <object type> <rpe> <wideband> <frc> <silence> <config> <enh layers decoded> <bws decoded> <bws config>
             <postfilter> <streams> <input> <output>
   The input holds, per frame and stream, a 16-bit big-endian length in bits and the bytes; the output the samples of
   each frame as 32-bit floats in 16-bit units. */
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <string.h>
#include "bitstream.h"
#include "lpc_common.h"
#include "celp_decoder.h"

struct tagBsBitStreamStruct { unsigned char *data; long bits; long pos; };

int BsGetBit(HANDLE_BSBITSTREAM s, unsigned long *value, int n) {
    unsigned long v = 0;
    for (int i = 0; i < n; i++) {
        int bit = s->pos < s->bits ? (s->data[s->pos >> 3] >> (7 - (s->pos & 7))) & 1 : 0;
        s->pos++;
        v = (v << 1) | (unsigned long) bit;
    }
    if (value) *value = v;
    return 0;
}
void CommonExit(int code, char *format, ...) {
    va_list a; va_start(a, format); fprintf(stderr, "CommonExit: "); vfprintf(stderr, format, a); fprintf(stderr, "\n"); exit(code ? code : 1);
}
void CommonWarning(char *format, ...) { (void) format; }

int main(int argc, char **argv) {
    if (argc != 14) { fprintf(stderr, "arguments\n"); return 2; }
    int objectType = atoi(argv[1]);
    long excitation = atoi(argv[2]) ? RegularPulseExc : MultiPulseExc;
    long rate = atoi(argv[3]) ? fs16kHz : fs8kHz;
    long frc = atoi(argv[4]), silence = atoi(argv[5]), config = atoi(argv[6]);
    long enh = atoi(argv[7]), bws = atoi(argv[8]), bwsConfig = atoi(argv[9]), post = atoi(argv[10]);
    int streams = atoi(argv[11]);
    FILE *in = fopen(argv[12], "rb");
    FILE *out = fopen(argv[13], "wb");
    long frameSize, nSub, sbfrm, order, nLpc, nShape, nGain, *alloc;
    long quant = 1, lossless = 0, rpeConfig = config, wideVq = Optimized_VQ, mpeConfig = config;
    int ver = objectType == 8 ? CelpObjectV1 : CelpObjectV2;
    void *ctx;
    long enhIn = enh, bwsIn = bws;
    celp_initialisation_decoder(NULL, enh, bws, post, &frameSize, &nSub, &sbfrm, &order, &nLpc, &nShape, &nGain,
            &alloc, &excitation, &rate, &quant, &frc, &lossless, &rpeConfig, &wideVq, &mpeConfig, &enhIn, &bwsIn,
            &bwsConfig, &silence, ver, &ctx);
    float *buf = malloc(sizeof(float) * 2048);
    float **channels = &buf;
    struct tagBsBitStreamStruct stream[9];
    HANDLE_BSBITSTREAM handles[9];
    unsigned char data[9][1024];
    for (;;) {
        for (int i = 0; i < streams; i++) {
            unsigned char head[2];
            if (fread(head, 1, 2, in) != 2) { fclose(out); return 0; }
            long bits = head[0] << 8 | head[1];
            if (fread(data[i], 1, (bits + 7) / 8, in) != (size_t) ((bits + 7) / 8)) { fclose(out); return 0; }
            stream[i].data = data[i]; stream[i].bits = bits; stream[i].pos = 0;
            handles[i] = &stream[i];
        }
        celp_decoder(handles, channels, excitation, rate, quant, frc, rpeConfig, wideVq, bwsIn, mpeConfig, silence,
                ver, 0, frameSize, nSub, sbfrm, order, nLpc, nShape, nGain, alloc, ctx);
        fwrite(buf, sizeof(float), frameSize, out);
        fflush(out);
    }
}
