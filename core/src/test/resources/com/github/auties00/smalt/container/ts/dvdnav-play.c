/*
 * Plays title 1 of a DVD-Video directory through libdvdnav, used as a black box by DvdAuthorInteropTest, and prints
 * the logical block number each navigation pack it hands out carries, one per line, then "end <reason>".
 *
 * Usage: dvdnav-play <directory holding VIDEO_TS>
 */
#include <dvdnav/dvdnav.h>
#include <stdint.h>
#include <stdio.h>

static uint32_t u32(const uint8_t *data) {
    return (uint32_t) data[0] << 24 | (uint32_t) data[1] << 16 | (uint32_t) data[2] << 8 | data[3];
}

int main(int argc, char **argv) {
    static uint8_t buffer[2048];
    dvdnav_t *nav;
    long navigation = 0;
    long budget = 1L << 22;
    if (argc != 2) {
        fprintf(stderr, "usage: dvdnav-play <directory>\n");
        return 2;
    }
    if (dvdnav_open(&nav, argv[1]) != DVDNAV_STATUS_OK) {
        fprintf(stderr, "dvdnav_open failed\n");
        return 3;
    }
    if (dvdnav_title_play(nav, 1) != DVDNAV_STATUS_OK) {
        fprintf(stderr, "dvdnav_title_play: %s\n", dvdnav_err_to_string(nav));
        dvdnav_close(nav);
        return 4;
    }
    while (budget-- > 0) {
        int32_t event;
        int32_t length;
        if (dvdnav_get_next_block(nav, buffer, &event, &length) != DVDNAV_STATUS_OK) {
            fprintf(stderr, "dvdnav_get_next_block: %s\n", dvdnav_err_to_string(nav));
            dvdnav_close(nav);
            return 5;
        }
        if (event == DVDNAV_NAV_PACKET) {
            /* The data search information starts at byte 0x407 of a navigation pack, its block number 4 bytes in. */
            printf("%lu\n", (unsigned long) u32(buffer + 0x407 + 4));
            navigation++;
        } else if (event == DVDNAV_STILL_FRAME) {
            dvdnav_still_skip(nav);
        } else if (event == DVDNAV_WAIT) {
            dvdnav_wait_skip(nav);
        } else if (event == DVDNAV_STOP) {
            printf("end stop\n");
            break;
        } else if (event == DVDNAV_VTS_CHANGE && navigation > 0) {
            /* The title ended and playback moved on to a menu domain. */
            printf("end domain\n");
            break;
        }
    }
    if (budget < 0) {
        printf("end budget\n");
    }
    dvdnav_close(nav);
    return 0;
}
