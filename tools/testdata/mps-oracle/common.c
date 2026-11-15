#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
void CommonExit(int code, const char *message, ...) {
    va_list args;
    va_start(args, message);
    vfprintf(stderr, message, args);
    va_end(args);
    fprintf(stderr, "\n");
    exit(code ? code : 1);
}
void CommonWarning(const char *message, ...) {
    va_list args;
    va_start(args, message);
    vfprintf(stderr, message, args);
    va_end(args);
    fprintf(stderr, "\n");
}
char debug[256];
int BsGetBitChar(void *stream, unsigned char *data, int numBit) {
    (void) stream; (void) data; (void) numBit;
    fprintf(stderr, "BsGetBitChar is not available\n");
    exit(1);
}
