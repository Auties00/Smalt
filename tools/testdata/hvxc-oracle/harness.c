/* Written for Smalt's test tooling; it links the reference software, which it does not contain. Runs the HVXC core
   of the ISO/IEC 14496-3 reference decoder (src_par of the ISO/IEC 23003-3 reference software) over the frames of a
   file, without its MP4 and bit stream layers:
     harness <varMode> <rateMode> <extensionFlag> <scalFlag> <epFlag> <delayMode> <testMode> <speed> <pitch>
             <input> <output>
   The input holds, per frame, the decode rate mode, a bit mask of the classes whose CRC failed and the ten bytes of
   the frame; the output, per frame, a 32-bit little-endian sample count and that many 32-bit floats. */
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <string.h>
#include "hvxc.h"
#include "hvxcDec.h"

static unsigned int harness_random = 1;

long random1(void) {
    harness_random = harness_random * 1103515245u + 12345u;
    return (long) (harness_random & 0x7fffffffu);
}

static int harness_crc;

void BsReadInfoFileHvx(HANDLE_ESC_INSTANCE_DATA data, unsigned short *crc) {
    (void) data;
    for (int i = 0; i < 8; i++) {
        crc[i] = (unsigned short) ((harness_crc >> i) & 1);
    }
}

void CommonExit(int code, char *format, ...) {
    va_list a; va_start(a, format); fprintf(stderr, "CommonExit: "); vfprintf(stderr, format, a);
    fprintf(stderr, "\n"); exit(code ? code : 1);
}

void CommonWarning(char *format, ...) { (void) format; }

int main(int argc, char **argv) {
    if (argc != 12) { fprintf(stderr, "arguments\n"); return 2; }
    int varMode = atoi(argv[1]), rateMode = atoi(argv[2]), extension = atoi(argv[3]), scal = atoi(argv[4]);
    int epFlag = atoi(argv[5]), delayMode = atoi(argv[6]), testMode = atoi(argv[7]);
    float speed = (float) atof(argv[8]), pitch = (float) atof(argv[9]);
    FILE *in = fopen(argv[10], "rb");
    FILE *out = fopen(argv[11], "wb");
    int numClass = rateMode == DEC2K ? N_CLS_2K : N_CLS_4K;
    HvxcDecStatus *hds = hvxc_decode_init(varMode, rateMode, extension, numClass, scal, delayMode, testMode);
    float samples[160 * 8];
    for (;;) {
        unsigned char head[2], frame[10];
        if (fread(head, 1, 2, in) != 2 || fread(frame, 1, 10, in) != 10) { fclose(out); return 0; }
        harness_crc = head[1];
        int count = 0;
        hvxc_decode(hds, frame, samples, &count, speed, pitch, head[0], epFlag, NULL);
        fwrite(&count, 4, 1, out);
        fwrite(samples, sizeof(float), count, out);
        fflush(out);
    }
}
