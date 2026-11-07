/* Minimal stand-in for the AFsp header: the MPS decoder library only writes PCM files through it. */
#ifndef LIBTSP_STUB_H
#define LIBTSP_STUB_H
typedef struct AFILE_stub AFILE;
static AFILE *AFopnWrite(const char *name, int a, int b, long c, double d, void *e) {
    (void) name; (void) a; (void) b; (void) c; (void) d; (void) e;
    return 0;
}
static void AFclose(AFILE *f) {
    (void) f;
}
static void AFfWriteData(AFILE *f, const float *d, long n) {
    (void) f; (void) d; (void) n;
}
#endif
