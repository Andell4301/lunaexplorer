package com.lunaexplorer.app.storage.shizuku;

import android.os.ParcelFileDescriptor;

// Entries and roots travel as JSON. A failure is an IllegalStateException whose message is the
// StorageError name, a newline, then the text: binder carries few exception types, and that is one.
interface IFileHelper {
    // The code Shizuku calls to end the process.
    void destroy() = 16777114;

    int uid() = 1;
    void setRoots(String roots) = 2;

    String stat(String key) = 3;
    // A listing is read a batch at a time: one reply may not exceed the binder's 1 MB.
    long list(String key, boolean complete) = 4;
    String more(long listing) = 5;
    void done(long listing) = 6;

    String create(String parent, String name, boolean directory, String mimeType) = 7;
    String rename(String key, String name) = 8;
    void delete(String key) = 9;
    String relocate(String key, String parent, String name) = 10;
    String commit(String staged, String parent, String name, String replace) = 11;
    long setModified(String key, long epochMillis) = 12;
    long availableBytes(String key) = 13;

    ParcelFileDescriptor openRead(String key) = 14;
    ParcelFileDescriptor openWrite(String key) = 15;

    // Wire.PROTOCOL of the build the helper was started from.
    int protocol() = 16;
    // Who the helper runs as, for the log.
    String describe() = 17;

    // The same bytes without a descriptor, for a device whose policy will not let one cross.
    // A block is small: every call in flight in a process shares one 1 MB buffer.
    long open(String key, boolean writing) = 18;
    byte[] readAt(long handle, long offset, int length) = 19;
    void append(long handle, in byte[] bytes) = 20;
    void close(long handle, boolean sync) = 21;
}
