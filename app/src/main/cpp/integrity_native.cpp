#include <jni.h>
#include <android/log.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <stdint.h>
#include <string.h>
#include <ctype.h>
#include <stdlib.h>
#include <stdio.h>
#include <sys/system_properties.h>

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif
#ifndef MFD_ALLOW_SEALING
#define MFD_ALLOW_SEALING 0x0002U
#endif
#ifndef F_ADD_SEALS
#define F_ADD_SEALS 1033
#define F_SEAL_SEAL 0x0001
#define F_SEAL_SHRINK 0x0002
#define F_SEAL_GROW 0x0004
#define F_SEAL_WRITE 0x0008
#endif

namespace {
constexpr int MAPS = 1;
constexpr int TRACER = 2;
constexpr int THREAD = 4;
constexpr int FD = 8;
constexpr int SU = 16;
constexpr int ROOT_ARTIFACT = 32;
constexpr int RW_SYSTEM_MOUNT = 64;
constexpr int DEBUG_PROPERTIES = 128;

bool contains_ci(const char* haystack, const char* needle) {
    if (!haystack || !needle) return false;
    const size_t n = strlen(needle);
    for (const char* p = haystack; *p; ++p) {
        size_t i = 0;
        while (i < n && p[i] && tolower(static_cast<unsigned char>(p[i])) ==
               tolower(static_cast<unsigned char>(needle[i]))) ++i;
        if (i == n) return true;
    }
    return false;
}

int open_read(const char* path) {
    return static_cast<int>(syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC, 0));
}

bool read_contains(const char* path, const char* const* needles, size_t count) {
    int fd = open_read(path);
    if (fd < 0) return false;
    char buf[8192];
    ssize_t n;
    bool found = false;
    while ((n = static_cast<ssize_t>(syscall(SYS_read, fd, buf, sizeof(buf) - 1))) > 0) {
        buf[n] = '\0';
        for (size_t i = 0; i < count; ++i) {
            if (contains_ci(buf, needles[i])) { found = true; break; }
        }
        if (found) break;
    }
    syscall(SYS_close, fd);
    memset(buf, 0, sizeof(buf));
    return found;
}

struct linux_dirent64_local {
    uint64_t ino;
    int64_t off;
    unsigned short reclen;
    unsigned char type;
    char name[];
};

template <typename Callback>
void walk_dir(int dirfd, Callback callback) {
    char buffer[4096];
    for (;;) {
        const long nread = syscall(SYS_getdents64, dirfd, buffer, sizeof(buffer));
        if (nread <= 0) break;
        for (long b = 0; b < nread;) {
            auto* entry = reinterpret_cast<linux_dirent64_local*>(buffer + b);
            if (entry->reclen == 0) return;
            callback(entry->name);
            b += entry->reclen;
        }
    }
}

int scan_process() {
    int flags = 0;
    const char* const hook_names[] = {"frida", "gum-js", "gadget", "xposed", "lsposed", "edxposed", "substrate", "libriru_", "zygisk"};
    if (read_contains("/proc/self/maps", hook_names, sizeof(hook_names) / sizeof(hook_names[0]))) flags |= MAPS;

    int status = open_read("/proc/self/status");
    if (status >= 0) {
        char buf[4096] = {};
        const ssize_t n = static_cast<ssize_t>(syscall(SYS_read, status, buf, sizeof(buf) - 1));
        syscall(SYS_close, status);
        if (n > 0) {
            char* tracer = strstr(buf, "TracerPid:");
            if (tracer && strtol(tracer + 10, nullptr, 10) != 0) flags |= TRACER;
        }
        memset(buf, 0, sizeof(buf));
    }

    // "gmain" sendiri bukan bukti Frida (thread GLib biasa); Kotlin memeriksanya bersama pipa Frida.
    const char* const thread_names[] = {"gum-js-loop", "frida"};
    int taskfd = open_read("/proc/self/task");
    if (taskfd >= 0) {
        walk_dir(taskfd, [&](const char* name) {
            if (name[0] == '.') return;
            char path[128];
            snprintf(path, sizeof(path), "/proc/self/task/%s/comm", name);
            if (read_contains(path, thread_names, sizeof(thread_names) / sizeof(thread_names[0]))) flags |= THREAD;
        });
        syscall(SYS_close, taskfd);
    }

    int fdfd = open_read("/proc/self/fd");
    if (fdfd >= 0) {
        walk_dir(fdfd, [&](const char* name) {
            if (name[0] == '.') return;
            char target[256] = {};
            const long n = syscall(SYS_readlinkat, fdfd, name, target, sizeof(target) - 1);
            if (n > 0 && (contains_ci(target, "linjector") || contains_ci(target, "frida"))) flags |= FD;
        });
        syscall(SYS_close, fdfd);
    }

    const char* const su_paths[] = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/data/local/xbin/su", "/data/local/bin/su", "/vendor/bin/su"};
    for (const char* path : su_paths) {
        if (syscall(SYS_faccessat, AT_FDCWD, path, F_OK, 0) == 0) { flags |= SU; break; }
    }
    const char* const root_paths[] = {"/sbin/.magisk", "/data/adb/magisk", "/data/adb/ksu", "/data/adb/ap"};
    for (const char* path : root_paths) {
        if (syscall(SYS_faccessat, AT_FDCWD, path, F_OK, 0) == 0) { flags |= ROOT_ARTIFACT; break; }
    }

    const char* const root_mounts[] = {"magisk", "kernelsu", " /data/adb"};
    if (read_contains("/proc/self/mounts", root_mounts, sizeof(root_mounts) / sizeof(root_mounts[0]))) flags |= ROOT_ARTIFACT;
    const char* const rw_system[] = {" /system ", " /vendor ", " /product "};
    int mounts = open_read("/proc/self/mounts");
    if (mounts >= 0) {
        char buf[8192];
        ssize_t n;
        while ((n = static_cast<ssize_t>(syscall(SYS_read, mounts, buf, sizeof(buf) - 1))) > 0) {
            buf[n] = '\0';
            for (size_t i = 0; i < sizeof(rw_system) / sizeof(rw_system[0]); ++i) {
                char* point = strstr(buf, rw_system[i]);
                if (point) {
                    char* line_end = strchr(point, '\n');
                    char* rw = strstr(point, " rw,");
                    if (rw && (!line_end || rw < line_end)) flags |= RW_SYSTEM_MOUNT;
                }
            }
        }
        syscall(SYS_close, mounts);
        memset(buf, 0, sizeof(buf));
    }

    char value[PROP_VALUE_MAX] = {};
    const int debuggable_length = __system_property_get("ro.debuggable", value);
    if (debuggable_length <= 0) return -1;
    const bool system_debuggable = strcmp(value, "1") == 0;
    const int secure_length = __system_property_get("ro.secure", value);
    if (secure_length <= 0) return -1;
    if (system_debuggable || strcmp(value, "0") == 0) flags |= DEBUG_PROPERTIES;
    memset(value, 0, sizeof(value));
    return flags;
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_cloud_depup_office_IntegrityNative_scan(JNIEnv*, jclass) {
    return scan_process();
}

extern "C" JNIEXPORT jint JNICALL
Java_cloud_depup_office_IntegrityNative_createSealedMemfd(JNIEnv* env, jclass, jbyteArray bytes) {
#if defined(SYS_memfd_create)
    if (!bytes) return -1;
    const jsize length = env->GetArrayLength(bytes);
    if (length <= 0) return -1;
    const int fd = static_cast<int>(syscall(SYS_memfd_create, "depup-protected-pdf", MFD_CLOEXEC | MFD_ALLOW_SEALING));
    if (fd < 0) return -1;
    jbyte* data = env->GetByteArrayElements(bytes, nullptr);
    if (!data) { syscall(SYS_close, fd); return -1; }
    jsize offset = 0;
    while (offset < length) {
        const long written = syscall(SYS_write, fd, data + offset, static_cast<size_t>(length - offset));
        if (written <= 0) { offset = -1; break; }
        offset += static_cast<jsize>(written);
    }
    memset(data, 0, static_cast<size_t>(length));
    env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
    if (offset < 0 || syscall(SYS_lseek, fd, 0, SEEK_SET) < 0 ||
        syscall(SYS_fcntl, fd, F_ADD_SEALS, F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL) < 0) {
        syscall(SYS_close, fd);
        return -1;
    }
    return fd;
#else
    (void)env; (void)bytes;
    return -1;
#endif
}
