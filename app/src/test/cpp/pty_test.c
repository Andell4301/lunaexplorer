#define _GNU_SOURCE
#include "pty.h"

#undef NDEBUG
#include <assert.h>
#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static char *const environment[] = {"PATH=/usr/bin:/bin", "TERM=xterm-256color", "HOME=/tmp", NULL};

static void send_text(struct luna_pty pty, const char *text) {
    size_t length = strlen(text);
    size_t sent = 0;
    while (sent < length) {
        ssize_t count = luna_pty_write(pty.master, pty.cancellation, text + sent, length - sent);
        assert(count > 0);
        sent += (size_t) count;
    }
}

static size_t collect(struct luna_pty pty, char *buffer, size_t capacity, const char *until) {
    size_t used = 0;
    buffer[0] = 0;
    while (used + 1 < capacity) {
        ssize_t count = luna_pty_read(pty.master, pty.cancellation, buffer + used, capacity - used - 1);
        assert(count >= 0);
        if (count == 0) return used;
        used += (size_t) count;
        buffer[used] = 0;
        if (until != NULL && strstr(buffer, until) != NULL) return used;
    }
    assert(0 && "Output exceeded buffer");
    return used;
}

static void close_handles(struct luna_pty pty) {
    close(pty.master);
    close(pty.cancellation);
}

static void terminal_size_and_directory(void) {
    char parent[] = "/tmp/luna-pty-XXXXXX";
    assert(mkdtemp(parent) != NULL);
    char directory[256];
    snprintf(directory, sizeof(directory), "%s/ a'b Ω ", parent);
    assert(mkdir(directory, 0700) == 0);
    struct luna_pty pty;
    assert(luna_pty_open(directory, environment, 80, 24, &pty) == 0);
    assert(luna_pty_resize(pty.master, 132, 43) == 0);
    send_text(pty, "stty -echo; if test -t 0 && test -t 1 && test -t 2; then printf '%s=%s\\n' terminal yes; fi; stty size; pwd; exit 37\n");
    char output[4096];
    collect(pty, output, sizeof(output), NULL);
    if (strstr(output, "terminal=yes") == NULL) fprintf(stderr, "%s\n", output);
    assert(strstr(output, "terminal=yes") != NULL);
    assert(strstr(output, "43 132") != NULL);
    assert(strstr(output, directory) != NULL);
    assert(luna_pty_await_exit(pty.pid) == 37);
    luna_pty_reap(pty.pid);
    close_handles(pty);
    assert(rmdir(directory) == 0);
    assert(rmdir(parent) == 0);
}

static void interrupt_foreground_command(void) {
    struct luna_pty pty;
    assert(luna_pty_open("/tmp", environment, 80, 24, &pty) == 0);
    send_text(pty, "stty -echo; printf '%s=%s\\n' wait ready; sleep 20\n");
    char output[4096];
    collect(pty, output, sizeof(output), "wait=ready");
    for (int i = 0; i < 1000 && tcgetpgrp(pty.master) == pty.pid; ++i) usleep(1000);
    assert(tcgetpgrp(pty.master) > 0 && tcgetpgrp(pty.master) != pty.pid);
    send_text(pty, "\003");
    send_text(pty, "printf '%s=%s\\n' interrupt ok; exit\n");
    collect(pty, output, sizeof(output), NULL);
    assert(strstr(output, "interrupt=ok") != NULL);
    assert(luna_pty_await_exit(pty.pid) == 0);
    luna_pty_reap(pty.pid);
    close_handles(pty);
}

static void *read_until_cancelled(void *value) {
    struct luna_pty pty = *(struct luna_pty *) value;
    char buffer[256];
    ssize_t count;
    do {
        count = luna_pty_read(pty.master, pty.cancellation, buffer, sizeof(buffer));
    } while (count > 0);
    assert(count == 0);
    return NULL;
}

static void *write_until_cancelled(void *value) {
    struct luna_pty pty = *(struct luna_pty *) value;
    char buffer[16384];
    memset(buffer, 'x', sizeof(buffer));
    ssize_t count;
    do {
        count = luna_pty_write(pty.master, pty.cancellation, buffer, sizeof(buffer));
    } while (count > 0);
    assert(count == -1 && errno == ECANCELED);
    return NULL;
}

static void cancellation_wakes_read_and_write(void) {
    struct luna_pty pty;
    assert(luna_pty_open("/tmp", environment, 80, 24, &pty) == 0);
    send_text(pty, "stty -echo -icanon; printf '%s=%s\\n' wait ready; sleep 20\n");
    char output[4096];
    collect(pty, output, sizeof(output), "wait=ready");
    pthread_t reader, writer;
    assert(pthread_create(&reader, NULL, read_until_cancelled, &pty) == 0);
    assert(pthread_create(&writer, NULL, write_until_cancelled, &pty) == 0);
    usleep(50000);
    luna_pty_cancel(pty.cancellation);
    assert(pthread_join(reader, NULL) == 0);
    assert(pthread_join(writer, NULL) == 0);
    luna_pty_terminate(pty.master, pty.pid);
    assert(luna_pty_await_exit(pty.pid) >= 128);
    luna_pty_reap(pty.pid);
    assert(kill(pty.pid, 0) == -1 && errno == ESRCH);
    close_handles(pty);
}

static void failed_directory_does_not_start_shell(void) {
    struct luna_pty pty;
    assert(luna_pty_open("/dev/null/not-a-directory", environment, 80, 24, &pty) == -1);
    assert(errno == ENOTDIR);
}

int main(void) {
    terminal_size_and_directory();
    interrupt_foreground_command();
    cancellation_wakes_read_and_write();
    failed_directory_does_not_start_shell();
    puts("Local PTY tests passed");
    return 0;
}
