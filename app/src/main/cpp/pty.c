#define _GNU_SOURCE
#include "pty.h"

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#ifndef LUNA_SHELL_PATH
#define LUNA_SHELL_PATH "/system/bin/sh"
#endif

static void child_failure(int descriptor) {
    int failure = errno;
    ssize_t written;
    do {
        written = write(descriptor, &failure, sizeof(failure));
    } while (written < 0 && errno == EINTR);
    _exit(127);
}

int luna_pty_resize(int master, int columns, int rows) {
    if (columns < 1 || columns > 65535 || rows < 1 || rows > 65535) {
        errno = EINVAL;
        return -1;
    }
    struct winsize size = {.ws_col = (unsigned short) columns, .ws_row = (unsigned short) rows};
    return ioctl(master, TIOCSWINSZ, &size);
}

int luna_pty_open(const char *directory, char *const environment[], int columns, int rows, struct luna_pty *result) {
    int master = -1;
    int cancellation = -1;
    int errors[2] = {-1, -1};
    char slave_name[128];
    master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC | O_NONBLOCK);
    if (master < 0 || grantpt(master) < 0 || unlockpt(master) < 0) goto failure;
    int name_error = ptsname_r(master, slave_name, sizeof(slave_name));
    if (name_error != 0) {
        errno = name_error;
        goto failure;
    }
    if (luna_pty_resize(master, columns, rows) < 0) goto failure;
    cancellation = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    if (cancellation < 0 || pipe2(errors, O_CLOEXEC) < 0) goto failure;
    long limit = sysconf(_SC_OPEN_MAX);
    if (limit < 0) goto failure;
    pid_t pid = fork();
    if (pid < 0) goto failure;
    if (pid == 0) {
        // Only async-signal-safe calls are allowed after forking the Android runtime.
        close(errors[0]);
        if (setsid() < 0) child_failure(errors[1]);
        int slave = open(slave_name, O_RDWR);
        if (slave < 0 || ioctl(slave, TIOCSCTTY, 0) < 0) child_failure(errors[1]);
        struct termios attributes;
        if (tcgetattr(slave, &attributes) < 0) child_failure(errors[1]);
        attributes.c_iflag |= IUTF8;
        if (tcsetattr(slave, TCSANOW, &attributes) < 0) child_failure(errors[1]);
        for (int i = 0; i < 3; ++i) {
            if (dup2(slave, i) < 0) child_failure(errors[1]);
        }
        for (long descriptor = 3; descriptor < limit; ++descriptor) {
            if (descriptor != errors[1]) close((int) descriptor);
        }
        sigset_t unblocked;
        sigemptyset(&unblocked);
        if (sigprocmask(SIG_SETMASK, &unblocked, NULL) < 0) child_failure(errors[1]);
        struct sigaction action = {.sa_handler = SIG_DFL};
        sigemptyset(&action.sa_mask);
        const int signals[] = {SIGINT, SIGQUIT, SIGHUP, SIGTERM, SIGTSTP, SIGTTIN, SIGTTOU, SIGCHLD, SIGPIPE, SIGALRM, SIGWINCH};
        for (size_t i = 0; i < sizeof(signals) / sizeof(signals[0]); ++i) {
            if (sigaction(signals[i], &action, NULL) < 0) child_failure(errors[1]);
        }
        if (chdir(directory) < 0) child_failure(errors[1]);
        char *const arguments[] = {"sh", "-i", NULL};
        execve(LUNA_SHELL_PATH, arguments, environment);
        child_failure(errors[1]);
    }
    close(errors[1]);
    errors[1] = -1;
    int child_error = 0;
    ssize_t count;
    do {
        count = read(errors[0], &child_error, sizeof(child_error));
    } while (count < 0 && errno == EINTR);
    if (count != 0) {
        int saved = count < 0 ? errno : child_error;
        kill(pid, SIGKILL);
        luna_pty_reap(pid);
        errno = saved;
        goto failure;
    }
    close(errors[0]);
    result->master = master;
    result->cancellation = cancellation;
    result->pid = pid;
    return 0;

failure:;
    int saved = errno;
    if (master >= 0) close(master);
    if (cancellation >= 0) close(cancellation);
    if (errors[0] >= 0) close(errors[0]);
    if (errors[1] >= 0) close(errors[1]);
    errno = saved;
    return -1;
}

static int await_ready(int master, int cancellation, short events) {
    struct pollfd descriptors[] = {
        {.fd = master, .events = events},
        {.fd = cancellation, .events = POLLIN},
    };
    int ready;
    do {
        ready = poll(descriptors, 2, -1);
    } while (ready < 0 && errno == EINTR);
    if (ready < 0) return -1;
    if (descriptors[1].revents != 0) return 0;
    if ((descriptors[0].revents & POLLNVAL) != 0) {
        errno = EBADF;
        return -1;
    }
    return 1;
}

ssize_t luna_pty_read(int master, int cancellation, void *buffer, size_t length) {
    while (1) {
        int ready = await_ready(master, cancellation, POLLIN);
        if (ready <= 0) return ready;
        ssize_t count = read(master, buffer, length);
        // Linux reports EIO once the last slave of a PTY closes.
        if (count < 0 && errno == EIO) return 0;
        if (count >= 0 || (errno != EINTR && errno != EAGAIN)) return count;
    }
}

ssize_t luna_pty_write(int master, int cancellation, const void *buffer, size_t length) {
    while (1) {
        int ready = await_ready(master, cancellation, POLLOUT);
        if (ready == 0) errno = ECANCELED;
        if (ready <= 0) return -1;
        ssize_t count = write(master, buffer, length);
        if (count >= 0 || (errno != EINTR && errno != EAGAIN)) return count;
    }
}

void luna_pty_cancel(int cancellation) {
    uint64_t value = 1;
    ssize_t written;
    do {
        written = write(cancellation, &value, sizeof(value));
    } while (written < 0 && errno == EINTR);
}

void luna_pty_terminate(int master, pid_t pid) {
    pid_t foreground = tcgetpgrp(master);
    if (foreground > 0 && foreground != pid) {
        kill(-foreground, SIGHUP);
    }
    kill(-pid, SIGHUP);
    // Give the interactive shell time to hang up its background jobs before forcing it down.
    for (int i = 0; i < 10; ++i) {
        siginfo_t status = {0};
        if (waitid(P_PID, (id_t) pid, &status, WEXITED | WNOWAIT | WNOHANG) < 0 || status.si_pid == pid) break;
        struct timespec pause = {.tv_nsec = 10000000};
        while (nanosleep(&pause, &pause) < 0 && errno == EINTR) {}
    }
    if (foreground > 0 && foreground != pid) kill(-foreground, SIGKILL);
    kill(-pid, SIGKILL);
}

int luna_pty_await_exit(pid_t pid) {
    siginfo_t status;
    int error;
    do {
        // Retain the zombie until the owner reaps it, so close cannot signal a reused PID.
        error = waitid(P_PID, (id_t) pid, &status, WEXITED | WNOWAIT);
    } while (error < 0 && errno == EINTR);
    if (error < 0) return -1;
    return status.si_code == CLD_EXITED ? status.si_status : 128 + status.si_status;
}

void luna_pty_reap(pid_t pid) {
    int status;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {}
}
