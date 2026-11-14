/* Minimal stand-in for the reference software's usac_all.h: the window sequence enumeration. */
#ifndef USAC_ALL_H
#define USAC_ALL_H
#include <stdlib.h>
#include "block.h"
typedef enum { ONLY_LONG_SEQUENCE, LONG_START_SEQUENCE, EIGHT_SHORT_SEQUENCE, LONG_STOP_SEQUENCE,
               STOP_START_SEQUENCE, NUM_WIN_SEQ } WINDOW_SEQUENCE;
#endif
