#ifndef LUNA_PTY_H
#define LUNA_PTY_H

#include <stddef.h>
#include <sys/types.h>

struct luna_pty {
    int master;
    int cancellation;
    pid_t pid;
};

int luna_pty_open(const char *directory, char *const environment[], int columns, int rows, struct luna_pty *result);
ssize_t luna_pty_read(int master, int cancellation, void *buffer, size_t length);
ssize_t luna_pty_write(int master, int cancellation, const void *buffer, size_t length);
int luna_pty_resize(int master, int columns, int rows);
void luna_pty_cancel(int cancellation);
void luna_pty_terminate(int master, pid_t pid);
int luna_pty_await_exit(pid_t pid);
void luna_pty_reap(pid_t pid);

#endif
