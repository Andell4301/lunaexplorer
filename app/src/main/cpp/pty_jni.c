#include "pty.h"

#include <errno.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static void throw_io(JNIEnv *env) {
    int failure = errno;
    jclass type = (*env)->FindClass(env, "java/io/IOException");
    if (type != NULL) (*env)->ThrowNew(env, type, strerror(failure));
}

static char *copy_bytes(JNIEnv *env, jbyteArray bytes) {
    jsize length = (*env)->GetArrayLength(env, bytes);
    char *result = malloc((size_t) length + 1);
    if (result == NULL) {
        errno = ENOMEM;
        throw_io(env);
        return NULL;
    }
    (*env)->GetByteArrayRegion(env, bytes, 0, length, (jbyte *) result);
    if ((*env)->ExceptionCheck(env)) {
        free(result);
        return NULL;
    }
    if (memchr(result, 0, (size_t) length) != NULL) {
        free(result);
        errno = EINVAL;
        throw_io(env);
        return NULL;
    }
    result[length] = 0;
    return result;
}

JNIEXPORT jintArray JNICALL Java_com_lunaexplorer_app_terminal_NativePty_open(
        JNIEnv *env, jobject instance, jbyteArray directory, jobjectArray environment, jint columns, jint rows) {
    (void) instance;
    jintArray result = (*env)->NewIntArray(env, 3);
    if (result == NULL) return NULL;
    char *path = copy_bytes(env, directory);
    if (path == NULL) return NULL;
    jsize count = (*env)->GetArrayLength(env, environment);
    char **variables = calloc((size_t) count + 1, sizeof(char *));
    if (variables == NULL) {
        free(path);
        errno = ENOMEM;
        throw_io(env);
        return NULL;
    }
    for (jsize i = 0; i < count; ++i) {
        jbyteArray value = (jbyteArray) (*env)->GetObjectArrayElement(env, environment, i);
        if (value == NULL) break;
        variables[i] = copy_bytes(env, value);
        (*env)->DeleteLocalRef(env, value);
        if (variables[i] == NULL) break;
    }
    if (!(*env)->ExceptionCheck(env)) {
        struct luna_pty pty;
        if (luna_pty_open(path, variables, columns, rows, &pty) < 0) {
            throw_io(env);
        } else {
            jint values[] = {pty.master, pty.cancellation, pty.pid};
            (*env)->SetIntArrayRegion(env, result, 0, 3, values);
            if ((*env)->ExceptionCheck(env)) {
                luna_pty_terminate(pty.master, pty.pid);
                luna_pty_reap(pty.pid);
                close(pty.master);
                close(pty.cancellation);
            }
        }
    }
    for (jsize i = 0; i < count; ++i) free(variables[i]);
    free(variables);
    free(path);
    return result;
}

JNIEXPORT jint JNICALL Java_com_lunaexplorer_app_terminal_NativePty_read(
        JNIEnv *env, jobject instance, jint master, jint cancellation, jbyteArray bytes, jint offset, jint length) {
    (void) instance;
    char buffer[16384];
    size_t size = (size_t) length < sizeof(buffer) ? (size_t) length : sizeof(buffer);
    ssize_t count = luna_pty_read(master, cancellation, buffer, size);
    if (count < 0) throw_io(env);
    if (count > 0) (*env)->SetByteArrayRegion(env, bytes, offset, (jsize) count, (jbyte *) buffer);
    return count == 0 ? -1 : (jint) count;
}

JNIEXPORT jint JNICALL Java_com_lunaexplorer_app_terminal_NativePty_write(
        JNIEnv *env, jobject instance, jint master, jint cancellation, jbyteArray bytes, jint offset, jint length) {
    (void) instance;
    char buffer[16384];
    size_t size = (size_t) length < sizeof(buffer) ? (size_t) length : sizeof(buffer);
    (*env)->GetByteArrayRegion(env, bytes, offset, (jsize) size, (jbyte *) buffer);
    if ((*env)->ExceptionCheck(env)) return -1;
    ssize_t count = luna_pty_write(master, cancellation, buffer, size);
    if (count < 0) throw_io(env);
    return (jint) count;
}

JNIEXPORT void JNICALL Java_com_lunaexplorer_app_terminal_NativePty_resize(
        JNIEnv *env, jobject instance, jint master, jint columns, jint rows) {
    (void) instance;
    if (luna_pty_resize(master, columns, rows) < 0) throw_io(env);
}

JNIEXPORT void JNICALL Java_com_lunaexplorer_app_terminal_NativePty_cancel(
        JNIEnv *env, jobject instance, jint cancellation) {
    (void) env;
    (void) instance;
    luna_pty_cancel(cancellation);
}

JNIEXPORT void JNICALL Java_com_lunaexplorer_app_terminal_NativePty_terminate(
        JNIEnv *env, jobject instance, jint master, jint pid) {
    (void) env;
    (void) instance;
    luna_pty_terminate(master, pid);
}

JNIEXPORT jint JNICALL Java_com_lunaexplorer_app_terminal_NativePty_awaitExit(
        JNIEnv *env, jobject instance, jint pid) {
    (void) env;
    (void) instance;
    return luna_pty_await_exit(pid);
}

JNIEXPORT void JNICALL Java_com_lunaexplorer_app_terminal_NativePty_reap(
        JNIEnv *env, jobject instance, jint pid) {
    (void) env;
    (void) instance;
    luna_pty_reap(pid);
}

JNIEXPORT void JNICALL Java_com_lunaexplorer_app_terminal_NativePty_dispose(
        JNIEnv *env, jobject instance, jint master, jint cancellation) {
    (void) env;
    (void) instance;
    close(master);
    close(cancellation);
}
