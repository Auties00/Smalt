/*
 * A PipeWire video source for the capture tests (capture.yml) that behaves as a compositor sharing a screen in
 * DMA-BUFs, which the GPU-less hosted runners have no compositor to do: real kernel DMA-BUFs from the system DMA heap
 * or udmabuf, negotiated with modifier fixation as PipeWire's DMA-BUF documentation describes, with the pointer in
 * SPA_META_Cursor metadata when the consumer asks for it.
 *
 * Usage: dmabuf-screencast-source NODE_NAME MODE
 *   dmabuf  DMA-BUFs only, chunk and data sizes filled
 *   zero    DMA-BUFs only, chunk and data sizes left at zero, as the documentation allows
 *   both    DMA-BUFs preferred, shared memory as the fallback
 *   tiled   DMA-BUFs of a tiled modifier only, which a consumer reading through mmap must not accept
 *   resize  like dmabuf, renegotiating every 45 ticks between 640x360 and 480x270, the latter with green in place of red
 *
 * The picture is 640x360 BGRx, red left of column 320 and blue from it on, the unused byte set to 0x7F; rows have 64
 * padding bytes and start 4096 bytes into the DMA-BUF. The pointer repeats four phases of half a second:
 *   0  a magenta RGBA image at (100,100), hot spot (4,6), with a full frame
 *   1  the same image at (400,200), in buffers with no pixels and no bitmap
 *   2  no pointer (id 0), in buffers with no pixels
 *   3  a green ARGB image stored bottom-up (negative stride) at (100,100), hot spot (0,0), with a full frame
 * The upper half of each 16x16 image is opaque and the lower half transparent. A bitmap is sent only when it changes.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/dma-buf.h>
#include <linux/dma-heap.h>
#include <linux/udmabuf.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

#include <pipewire/pipewire.h>
#include <spa/buffer/meta.h>
#include <spa/pod/builder.h>
#include <spa/pod/iter.h>
#include <spa/utils/result.h>
#include <spa/param/video/format-utils.h>

#define STRIDE (4 * width + 64)
#define OFFSET 4096
#define SIZE (OFFSET + STRIDE * height)
#define CURSOR_SIDE 16
#define META_SIZE(side) ((int) (sizeof(struct spa_meta_cursor) + sizeof(struct spa_meta_bitmap) + (side) * (side) * 4))
#define PHASE_TICKS 15
#define MOD_LINEAR 0ULL
#define MOD_X_TILED 0x0100000000000001ULL
#define RESIZE_TICKS 45

enum mode { MODE_DMABUF, MODE_ZERO, MODE_BOTH, MODE_TILED, MODE_RESIZE };

/* The size and tint of the buffers in use. */
static int width = 640, height = 360, green;
/* The size and tint offered, which take effect when the format is set, as the old buffers are gone by then. */
static int offered_width = 640, offered_height = 360, offered_green;

struct data {
	struct pw_main_loop *loop;
	struct spa_source *timer;
	struct pw_stream *stream;
	enum mode mode;
	unsigned tick;
	unsigned since_resize;
	int sent_bitmap; /* the phase whose bitmap the consumer holds, or -1 */
};

static void fail(const char *what) {
	fprintf(stderr, "%s: %s\n", what, strerror(errno));
	exit(1);
}

/* Returns a real DMA-BUF of SIZE bytes. */
static int allocate_dmabuf(void) {
	int heap = open("/dev/dma_heap/system", O_RDONLY | O_CLOEXEC);
	if (heap >= 0) {
		struct dma_heap_allocation_data allocation = {.len = SIZE, .fd_flags = O_RDWR | O_CLOEXEC};
		if (ioctl(heap, DMA_HEAP_IOCTL_ALLOC, &allocation) < 0) {
			fail("DMA_HEAP_IOCTL_ALLOC");
		}
		close(heap);
		return (int) allocation.fd;
	}
	int device = open("/dev/udmabuf", O_RDWR | O_CLOEXEC);
	if (device < 0) {
		fail("neither /dev/dma_heap/system nor /dev/udmabuf can be opened");
	}
	long page = sysconf(_SC_PAGESIZE);
	long rounded = (SIZE + page - 1) / page * page;
	int memory = memfd_create("smalt-udmabuf", MFD_ALLOW_SEALING | MFD_CLOEXEC);
	if (memory < 0 || ftruncate(memory, rounded) < 0 || fcntl(memory, F_ADD_SEALS, F_SEAL_SHRINK) < 0) {
		fail("memfd for udmabuf");
	}
	struct udmabuf_create create = {.memfd = (unsigned) memory, .flags = UDMABUF_FLAGS_CLOEXEC, .offset = 0, .size = (unsigned long long) rounded};
	int fd = ioctl(device, UDMABUF_CREATE, &create);
	if (fd < 0) {
		fail("UDMABUF_CREATE");
	}
	close(device);
	close(memory);
	return fd;
}

/* Writes the picture into the descriptor through a mapping, with the cache synchronization of a DMA-BUF. */
static void paint(int fd, int dmabuf) {
	unsigned char *memory = mmap(NULL, SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
	if (memory == MAP_FAILED) {
		fail("mmap of a buffer to paint");
	}
	struct dma_buf_sync sync = {.flags = DMA_BUF_SYNC_START | DMA_BUF_SYNC_WRITE};
	if (dmabuf && ioctl(fd, DMA_BUF_IOCTL_SYNC, &sync) < 0) {
		fail("DMA_BUF_IOCTL_SYNC start");
	}
	memset(memory, 0x55, SIZE);
	for (int y = 0; y < height; y++) {
		unsigned char *row = memory + OFFSET + y * STRIDE;
		for (int x = 0; x < width; x++) {
			row[4 * x] = x < width / 2 ? 0x00 : 0xFF;
			row[4 * x + 1] = x < width / 2 && green ? 0xFF : 0x00;
			row[4 * x + 2] = x < width / 2 && !green ? 0xFF : 0x00;
			row[4 * x + 3] = 0x7F;
		}
	}
	sync.flags = DMA_BUF_SYNC_END | DMA_BUF_SYNC_WRITE;
	if (dmabuf && ioctl(fd, DMA_BUF_IOCTL_SYNC, &sync) < 0) {
		fail("DMA_BUF_IOCTL_SYNC end");
	}
	munmap(memory, SIZE);
}

static const struct spa_pod *build_format(struct spa_pod_builder *b, const uint64_t *modifiers, int count, int dont_fixate) {
	struct spa_pod_frame object, choice;
	spa_pod_builder_push_object(b, &object, SPA_TYPE_OBJECT_Format, SPA_PARAM_EnumFormat);
	spa_pod_builder_add(b, SPA_FORMAT_mediaType, SPA_POD_Id(SPA_MEDIA_TYPE_video), 0);
	spa_pod_builder_add(b, SPA_FORMAT_mediaSubtype, SPA_POD_Id(SPA_MEDIA_SUBTYPE_raw), 0);
	spa_pod_builder_add(b, SPA_FORMAT_VIDEO_format, SPA_POD_Id(SPA_VIDEO_FORMAT_BGRx), 0);
	if (count > 0) {
		spa_pod_builder_prop(b, SPA_FORMAT_VIDEO_modifier,
				SPA_POD_PROP_FLAG_MANDATORY | (dont_fixate ? SPA_POD_PROP_FLAG_DONT_FIXATE : 0));
		spa_pod_builder_push_choice(b, &choice, SPA_CHOICE_Enum, 0);
		spa_pod_builder_long(b, (int64_t) modifiers[0]);
		for (int i = 0; i < count; i++) {
			spa_pod_builder_long(b, (int64_t) modifiers[i]);
		}
		spa_pod_builder_pop(b, &choice);
	}
	spa_pod_builder_add(b, SPA_FORMAT_VIDEO_size, SPA_POD_Rectangle(&SPA_RECTANGLE(offered_width, offered_height)), 0);
	spa_pod_builder_add(b, SPA_FORMAT_VIDEO_framerate, SPA_POD_Fraction(&SPA_FRACTION(30, 1)), 0);
	return spa_pod_builder_pop(b, &object);
}

static int build_offers(const struct data *data, struct spa_pod_builder *b, const struct spa_pod **params) {
	static const uint64_t all[] = {MOD_LINEAR, MOD_X_TILED};
	static const uint64_t tiled[] = {MOD_X_TILED};
	int count = 0;
	if (data->mode == MODE_TILED) {
		params[count++] = build_format(b, tiled, 1, 1);
	} else {
		params[count++] = build_format(b, all, 2, 1);
	}
	if (data->mode == MODE_BOTH) {
		params[count++] = build_format(b, NULL, 0, 0);
	}
	return count;
}

static void on_param_changed(void *userdata, uint32_t id, const struct spa_pod *param) {
	struct data *data = userdata;
	uint8_t storage[2048];
	struct spa_pod_builder b = SPA_POD_BUILDER_INIT(storage, sizeof(storage));
	const struct spa_pod *params[4];
	if (param == NULL || id != SPA_PARAM_Format) {
		return;
	}
	width = offered_width;
	height = offered_height;
	green = offered_green;
	const struct spa_pod_prop *modifier = spa_pod_find_prop(param, NULL, SPA_FORMAT_VIDEO_modifier);
	if (modifier != NULL && (modifier->flags & SPA_POD_PROP_FLAG_DONT_FIXATE) != 0) {
		uint32_t count, choice;
		const struct spa_pod *values = spa_pod_get_values(&modifier->value, &count, &choice);
		const uint64_t *candidates = SPA_POD_BODY_CONST(values);
		uint64_t chosen = candidates[0];
		printf("fixating modifier 0x%llx of %u values\n", (unsigned long long) chosen, count);
		fflush(stdout);
		int n = 0;
		params[n++] = build_format(&b, &chosen, 1, 0);
		n += build_offers(data, &b, &params[n]);
		pw_stream_update_params(data->stream, params, n);
		return;
	}
	int dmabuf = modifier != NULL;
	printf("format fixed, %s\n", dmabuf ? "DMA-BUF" : "shared memory");
	fflush(stdout);
	params[0] = spa_pod_builder_add_object(&b, SPA_TYPE_OBJECT_ParamBuffers, SPA_PARAM_Buffers,
			SPA_PARAM_BUFFERS_buffers, SPA_POD_CHOICE_RANGE_Int(4, 2, 8),
			SPA_PARAM_BUFFERS_blocks, SPA_POD_Int(1),
			SPA_PARAM_BUFFERS_size, SPA_POD_Int(SIZE),
			SPA_PARAM_BUFFERS_stride, SPA_POD_Int(STRIDE),
			SPA_PARAM_BUFFERS_dataType, SPA_POD_CHOICE_FLAGS_Int(dmabuf ? 1 << SPA_DATA_DmaBuf : 1 << SPA_DATA_MemFd));
	params[1] = spa_pod_builder_add_object(&b, SPA_TYPE_OBJECT_ParamMeta, SPA_PARAM_Meta,
			SPA_PARAM_META_type, SPA_POD_Id(SPA_META_Cursor),
			SPA_PARAM_META_size, SPA_POD_CHOICE_RANGE_Int(META_SIZE(64), META_SIZE(1), META_SIZE(256)));
	pw_stream_update_params(data->stream, params, 2);
	data->sent_bitmap = -1;
}

static void on_add_buffer(void *userdata, struct pw_buffer *buffer) {
	struct data *data = userdata;
	struct spa_data *d = &buffer->buffer->datas[0];
	int dmabuf = (d->type & (1 << SPA_DATA_DmaBuf)) != 0;
	if (dmabuf) {
		d->fd = allocate_dmabuf();
		d->type = SPA_DATA_DmaBuf;
		printf("allocated a DMA-BUF\n");
		fflush(stdout);
	} else if ((d->type & (1 << SPA_DATA_MemFd)) != 0) {
		d->fd = memfd_create("smalt-source", MFD_CLOEXEC);
		if (d->fd < 0 || ftruncate((int) d->fd, SIZE) < 0) {
			fail("memfd");
		}
		d->type = SPA_DATA_MemFd;
	} else {
		fprintf(stderr, "unsupported data types %x\n", d->type);
		exit(1);
	}
	paint((int) d->fd, dmabuf);
	d->flags = SPA_DATA_FLAG_READABLE;
	d->mapoffset = 0;
	d->maxsize = data->mode == MODE_ZERO ? 0 : SIZE;
	d->data = NULL;
}

static void on_remove_buffer(void *userdata, struct pw_buffer *buffer) {
	struct spa_data *d = &buffer->buffer->datas[0];
	if (d->fd >= 0) {
		close((int) d->fd);
		d->fd = -1;
	}
}

/* Writes one phase's pointer. */
static void write_cursor(struct data *data, struct spa_meta_cursor *cursor, int phase) {
	cursor->flags = 0;
	cursor->bitmap_offset = 0;
	if (phase == 2) {
		cursor->id = 0;
		return;
	}
	cursor->id = 1;
	cursor->position = phase == 1 ? SPA_POINT(400, 200) : SPA_POINT(100, 100);
	int image = phase == 3 ? 3 : 0;
	if (data->sent_bitmap == image) {
		return;
	}
	data->sent_bitmap = image;
	cursor->hotspot = image == 3 ? SPA_POINT(0, 0) : SPA_POINT(4, 6);
	cursor->bitmap_offset = sizeof(*cursor);
	struct spa_meta_bitmap *bitmap = SPA_PTROFF(cursor, cursor->bitmap_offset, struct spa_meta_bitmap);
	bitmap->size = SPA_RECTANGLE(CURSOR_SIDE, CURSOR_SIDE);
	unsigned char *pixels = SPA_PTROFF(bitmap, sizeof(*bitmap), unsigned char);
	if (image == 0) {
		bitmap->format = SPA_VIDEO_FORMAT_RGBA;
		bitmap->stride = 4 * CURSOR_SIDE;
		bitmap->offset = sizeof(*bitmap);
		for (int y = 0; y < CURSOR_SIDE; y++) {
			for (int x = 0; x < CURSOR_SIDE; x++) {
				unsigned char *p = pixels + (y * CURSOR_SIDE + x) * 4;
				int opaque = y < CURSOR_SIDE / 2;
				p[0] = opaque ? 0xFF : 0;
				p[1] = 0;
				p[2] = opaque ? 0xFF : 0;
				p[3] = opaque ? 0xFF : 0;
			}
		}
	} else {
		bitmap->format = SPA_VIDEO_FORMAT_ARGB;
		bitmap->stride = -4 * CURSOR_SIDE;
		bitmap->offset = sizeof(*bitmap) + (CURSOR_SIDE - 1) * 4 * CURSOR_SIDE;
		for (int y = 0; y < CURSOR_SIDE; y++) {
			for (int x = 0; x < CURSOR_SIDE; x++) {
				unsigned char *p = pixels + ((CURSOR_SIDE - 1 - y) * CURSOR_SIDE + x) * 4;
				int opaque = y < CURSOR_SIDE / 2;
				p[0] = opaque ? 0xFF : 0;
				p[1] = 0;
				p[2] = opaque ? 0xFF : 0;
				p[3] = 0;
			}
		}
	}
}

static void on_process(void *userdata) {
	struct data *data = userdata;
	struct pw_buffer *buffer = pw_stream_dequeue_buffer(data->stream);
	if (buffer == NULL) {
		return;
	}
	int phase = (int) (data->tick++ / PHASE_TICKS % 4);
	struct spa_buffer *spa = buffer->buffer;
	struct spa_meta_cursor *cursor = spa_buffer_find_meta_data(spa, SPA_META_Cursor, sizeof(*cursor));
	if (cursor != NULL) {
		write_cursor(data, cursor, phase);
	}
	struct spa_chunk *chunk = spa->datas[0].chunk;
	int full = phase == 0 || phase == 3;
	chunk->offset = OFFSET;
	chunk->stride = STRIDE;
	if (data->mode == MODE_ZERO) {
		chunk->size = 0;
		chunk->flags = full ? SPA_CHUNK_FLAG_NONE : SPA_CHUNK_FLAG_CORRUPTED;
	} else {
		chunk->size = full ? STRIDE * height : 0;
		chunk->flags = SPA_CHUNK_FLAG_NONE;
	}
	pw_stream_queue_buffer(data->stream, buffer);
}

static void on_timeout(void *userdata, uint64_t expirations) {
	struct data *data = userdata;
	if (data->mode == MODE_RESIZE && ++data->since_resize >= RESIZE_TICKS) {
		uint8_t storage[2048];
		struct spa_pod_builder b = SPA_POD_BUILDER_INIT(storage, sizeof(storage));
		const struct spa_pod *params[4];
		data->since_resize = 0;
		offered_green = !offered_green;
		offered_width = offered_green ? 480 : 640;
		offered_height = offered_green ? 270 : 360;
		printf("offering %dx%d\n", offered_width, offered_height);
		fflush(stdout);
		int count = build_offers(data, &b, params);
		pw_stream_update_params(data->stream, params, count);
	}
	pw_stream_trigger_process(data->stream);
}

static void on_state_changed(void *userdata, enum pw_stream_state old, enum pw_stream_state state, const char *error) {
	struct data *data = userdata;
	printf("stream state: %s %s\n", pw_stream_state_as_string(state), error == NULL ? "" : error);
	fflush(stdout);
	struct timespec value = {0, 1}, interval = {0, 1000000000 / 30};
	if (state == PW_STREAM_STATE_STREAMING) {
		data->sent_bitmap = -1;
		pw_loop_update_timer(pw_main_loop_get_loop(data->loop), data->timer, &value, &interval, false);
	} else {
		pw_loop_update_timer(pw_main_loop_get_loop(data->loop), data->timer, NULL, NULL, false);
	}
}

static const struct pw_stream_events events = {
		PW_VERSION_STREAM_EVENTS,
		.state_changed = on_state_changed,
		.param_changed = on_param_changed,
		.add_buffer = on_add_buffer,
		.remove_buffer = on_remove_buffer,
		.process = on_process,
};

int main(int argc, char **argv) {
	struct data data = {.sent_bitmap = -1};
	if (argc != 3) {
		fprintf(stderr, "usage: %s NODE_NAME dmabuf|zero|both|tiled|resize\n", argv[0]);
		return 2;
	}
	data.mode = strcmp(argv[2], "zero") == 0 ? MODE_ZERO : strcmp(argv[2], "both") == 0 ? MODE_BOTH
			: strcmp(argv[2], "tiled") == 0 ? MODE_TILED : strcmp(argv[2], "resize") == 0 ? MODE_RESIZE : MODE_DMABUF;
	close(allocate_dmabuf()); /* the fixture itself: a real DMA-BUF must be available */
	pw_init(&argc, &argv);
	data.loop = pw_main_loop_new(NULL);
	data.timer = pw_loop_add_timer(pw_main_loop_get_loop(data.loop), on_timeout, &data);
	data.stream = pw_stream_new_simple(pw_main_loop_get_loop(data.loop), argv[1],
			pw_properties_new(PW_KEY_MEDIA_CLASS, "Video/Source", PW_KEY_MEDIA_TYPE, "Video", PW_KEY_MEDIA_CATEGORY, "Capture",
					PW_KEY_MEDIA_ROLE, "Screen", PW_KEY_NODE_NAME, argv[1], NULL), &events, &data);
	uint8_t storage[2048];
	struct spa_pod_builder b = SPA_POD_BUILDER_INIT(storage, sizeof(storage));
	const struct spa_pod *params[4];
	int count = build_offers(&data, &b, params);
	int result = pw_stream_connect(data.stream, PW_DIRECTION_OUTPUT, PW_ID_ANY,
			PW_STREAM_FLAG_DRIVER | PW_STREAM_FLAG_ALLOC_BUFFERS, params, count);
	if (result < 0) {
		fprintf(stderr, "pw_stream_connect: %s\n", spa_strerror(result));
		return 1;
	}
	pw_main_loop_run(data.loop);
	return 0;
}
