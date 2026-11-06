/*
 * The allocator of the ffmpeg oracle on Linux and macOS, linked through ffmpeg's --malloc-prefix=smalt_q_: the
 * av_malloc family calls these functions instead of the C library's, and a small block that ffmpeg frees is held
 * back for the next QUARANTINE small frees before the C library gets it.
 *
 * ffmpeg 7.1.2 (and 7.1.3, 7.1.4) writes into a 40-byte block right after freeing it whenever it opens its libaom
 * encoder (ticket #11617, a heap-use-after-free that AddressSanitizer reports in aom_init). The write corrupts the
 * free lists of the C library: macOS's allocator traps (SIGTRAP) and glibc's later crashes (SIGSEGV), on some
 * runners and not on others. A block in quarantine still belongs to the program, so the late write lands in memory
 * no one else uses. Large blocks are freed at once, so the quarantine holds at most
 * QUARANTINE * SMALL_LIMIT bytes.
 */
#include <pthread.h>
#include <stdlib.h>
#if defined(__APPLE__)
#include <malloc/malloc.h>
#define USABLE_SIZE(pointer) malloc_size(pointer)
#else
#include <malloc.h>
#define USABLE_SIZE(pointer) malloc_usable_size(pointer)
#endif

#define QUARANTINE 4096
#define SMALL_LIMIT 256

static void *held[QUARANTINE];
static size_t next;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;

void *smalt_q_malloc(size_t size) {
    return malloc(size);
}

void *smalt_q_calloc(size_t count, size_t size) {
    return calloc(count, size);
}

void *smalt_q_realloc(void *pointer, size_t size) {
    return realloc(pointer, size);
}

int smalt_q_posix_memalign(void **pointer, size_t alignment, size_t size) {
    return posix_memalign(pointer, alignment, size);
}

void *smalt_q_memalign(size_t alignment, size_t size) {
    void *pointer = NULL;
    return posix_memalign(&pointer, alignment < sizeof(void *) ? sizeof(void *) : alignment, size) == 0 ? pointer
                                                                                                    : NULL;
}

void *smalt_q_aligned_alloc(size_t alignment, size_t size) {
    return smalt_q_memalign(alignment, size);
}

void smalt_q_free(void *pointer) {
    if (pointer == NULL) {
        return;
    }
    if (USABLE_SIZE(pointer) > SMALL_LIMIT) {
        free(pointer);
        return;
    }
    pthread_mutex_lock(&lock);
    void *oldest = held[next];
    held[next] = pointer;
    next = (next + 1) % QUARANTINE;
    pthread_mutex_unlock(&lock);
    free(oldest);
}
