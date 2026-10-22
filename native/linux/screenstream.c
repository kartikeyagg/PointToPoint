/*
 * Captures the X11 screen through shared memory (MIT-SHM) and streams changed regions to stdout,
 * in the same format as native/macos/ScreenStream.swift:
 *   frame = little-endian uint32 magic 'SSF1', width, height, rectCount, then per rect
 *           x, y, w, h followed by w*h*4 bytes of BGRA (rows top to bottom, tightly packed).
 * The first frame (and the first after a size change) covers the whole screen; after that only
 * tiles whose pixels changed are sent, and nothing is written while the screen is still.
 *
 * Build:  cc -O2 -o target/screenstream native/linux/screenstream.c -lX11 -lXext
 * Usage:  screenstream [--fps N]      exits when stdin closes; exit 2 = can't capture here
 */
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <X11/extensions/XShm.h>
#include <errno.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ipc.h>
#include <sys/select.h>
#include <sys/shm.h>
#include <time.h>
#include <unistd.h>

#define MAGIC 0x31465353u /* "SSF1" */
#define TILE 64

static void fail(int code, const char *message) {
    fprintf(stderr, "screenstream: %s\n", message);
    exit(code);
}

static int on_x_error(Display *display, XErrorEvent *event) {
    char text[128];
    XGetErrorText(display, event->error_code, text, sizeof text);
    fprintf(stderr, "screenstream: X error: %s\n", text);
    exit(1);
}

typedef struct {
    unsigned char *data;
    size_t length;
    size_t capacity;
} Buffer;

static void put_bytes(Buffer *out, const void *bytes, size_t count) {
    if (out->length + count > out->capacity) {
        size_t capacity = out->capacity ? out->capacity : 1 << 20;
        while (capacity < out->length + count) {
            capacity *= 2;
        }
        out->data = realloc(out->data, capacity);
        if (!out->data) {
            fail(1, "out of memory");
        }
        out->capacity = capacity;
    }
    memcpy(out->data + out->length, bytes, count);
    out->length += count;
}

static void put32(Buffer *out, uint32_t v) {
    unsigned char b[4] = {v & 0xff, (v >> 8) & 0xff, (v >> 16) & 0xff, v >> 24};
    put_bytes(out, b, 4);
}

static void put_rect(Buffer *out, const XImage *image, int x, int y, int w, int h) {
    put32(out, x); put32(out, y); put32(out, w); put32(out, h);
    for (int row = y; row < y + h; row++) {
        put_bytes(out, image->data + (size_t)row * image->bytes_per_line + (size_t)x * 4, (size_t)w * 4);
    }
}

/* Waits up to `micros` for stdin; returns 0 when the parent closed it. */
static int wait_for_parent(long micros) {
    if (micros <= 0) {
        micros = 0;
    }
    fd_set fds;
    FD_ZERO(&fds);
    FD_SET(0, &fds);
    struct timeval timeout = {micros / 1000000, micros % 1000000};
    int ready = select(1, &fds, NULL, NULL, &timeout);
    if (ready > 0) {
        char sink[256];
        ssize_t n = read(0, sink, sizeof sink);
        return n > 0 || (n < 0 && errno == EINTR);
    }
    return 1;
}

static long now_micros(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000000L + ts.tv_nsec / 1000;
}

typedef struct {
    XImage *image;
    XShmSegmentInfo shm;
} Shot;

static void shot_free(Display *display, Shot *shot) {
    if (shot->image) {
        XShmDetach(display, &shot->shm);
        XDestroyImage(shot->image); /* frees the XImage struct, not the shared memory */
        shmdt(shot->shm.shmaddr);
        shot->image = NULL;
    }
}

static void shot_alloc(Display *display, Shot *shot, int width, int height) {
    int screen = DefaultScreen(display);
    shot->image = XShmCreateImage(display, DefaultVisual(display, screen), DefaultDepth(display, screen),
                                  ZPixmap, NULL, &shot->shm, width, height);
    if (!shot->image) {
        fail(2, "XShmCreateImage failed");
    }
    XImage *image = shot->image;
    if (image->bits_per_pixel != 32 || image->byte_order != LSBFirst || image->red_mask != 0xff0000
            || image->green_mask != 0xff00 || image->blue_mask != 0xff) {
        fail(2, "unsupported pixel format (need 32-bit BGRX)");
    }
    shot->shm.shmid = shmget(IPC_PRIVATE, (size_t)image->bytes_per_line * height, IPC_CREAT | 0600);
    if (shot->shm.shmid < 0) {
        fail(2, "shmget failed");
    }
    shot->shm.shmaddr = image->data = shmat(shot->shm.shmid, NULL, 0);
    shot->shm.readOnly = False;
    if (image->data == (char *)-1 || !XShmAttach(display, &shot->shm)) {
        fail(2, "can't attach shared memory");
    }
    XSync(display, False);
    shmctl(shot->shm.shmid, IPC_RMID, NULL); /* freed once both sides detach, even on a crash */
}

int main(int argc, char **argv) {
    int fps = 30;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--fps") == 0 && i + 1 < argc && atoi(argv[i + 1]) > 0) {
            fps = atoi(argv[++i]);
        } else {
            fail(1, "usage: screenstream [--fps N]");
        }
    }
    signal(SIGPIPE, SIG_IGN);

    const char *session = getenv("XDG_SESSION_TYPE");
    if (session && strcmp(session, "wayland") == 0) {
        fail(2, "Wayland session: X11 capture would only see XWayland windows");
    }
    Display *display = XOpenDisplay(NULL);
    if (!display) {
        fail(2, "can't open the X display");
    }
    if (!XShmQueryExtension(display)) {
        fail(2, "the X server has no MIT-SHM extension");
    }
    XSetErrorHandler(on_x_error);
    Window root = DefaultRootWindow(display);

    Shot shot = {0};
    unsigned char *previous = NULL;
    int width = 0, height = 0;
    Buffer out = {0};
    long interval = 1000000L / fps;
    long frame = 0;

    for (;;) {
        long start = now_micros();
        if (frame++ % fps == 0 || !shot.image) { /* notice resolution changes about once a second */
            XWindowAttributes attributes;
            XGetWindowAttributes(display, root, &attributes);
            if (attributes.width != width || attributes.height != height) {
                shot_free(display, &shot);
                free(previous);
                width = attributes.width;
                height = attributes.height;
                shot_alloc(display, &shot, width, height);
                previous = NULL;
            }
        }
        if (!XShmGetImage(display, root, shot.image, 0, 0, AllPlanes)) {
            fail(1, "XShmGetImage failed");
        }
        XImage *image = shot.image;
        size_t stride = image->bytes_per_line;

        out.length = 0;
        put32(&out, MAGIC); put32(&out, width); put32(&out, height); put32(&out, 0);
        uint32_t rects = 0;
        if (!previous) {
            put_rect(&out, image, 0, 0, width, height);
            rects = 1;
            previous = malloc(stride * height);
            if (!previous) {
                fail(1, "out of memory");
            }
        } else {
            /* Compare tile by tile; adjacent changed tiles in a tile row become one rect. */
            for (int ty = 0; ty < height; ty += TILE) {
                int th = height - ty < TILE ? height - ty : TILE;
                int run_start = -1;
                for (int tx = 0; tx <= width; tx += TILE) {
                    int changed = 0;
                    if (tx < width) {
                        int tw = width - tx < TILE ? width - tx : TILE;
                        for (int row = ty; row < ty + th && !changed; row++) {
                            size_t offset = row * stride + (size_t)tx * 4;
                            changed = memcmp(image->data + offset, previous + offset, (size_t)tw * 4) != 0;
                        }
                    }
                    if (changed && run_start < 0) {
                        run_start = tx;
                    } else if (!changed && run_start >= 0) {
                        int end = tx < width ? tx : width;
                        put_rect(&out, image, run_start, ty, end - run_start, th);
                        rects++;
                        run_start = -1;
                    }
                }
            }
        }
        if (rects > 0) {
            for (int i = 0; i < 4; i++) {
                out.data[12 + i] = (rects >> (8 * i)) & 0xff;
            }
            memcpy(previous, image->data, stride * height);
            if (fwrite(out.data, 1, out.length, stdout) != out.length || fflush(stdout) != 0) {
                return 0; /* the parent went away */
            }
        }
        if (!wait_for_parent(interval - (now_micros() - start))) {
            return 0;
        }
    }
}
