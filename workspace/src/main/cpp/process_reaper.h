#pragma once

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

namespace workspace_process {

constexpr size_t kMaxChildrenFileBytes = 64 * 1024;
constexpr size_t kMaxDirectChildren = 4096;

inline bool child_list_path(char *path, size_t capacity) {
    constexpr char prefix[] = "/proc/self/task/";
    constexpr char suffix[] = "/children";
    if (capacity < sizeof(prefix) + sizeof(suffix) + 16) {
        errno = EOVERFLOW;
        return false;
    }
    size_t used = sizeof(prefix) - 1;
    memcpy(path, prefix, used);
    char reversed_pid[16] = {};
    size_t digits = 0;
    pid_t value = getpid();
    do {
        reversed_pid[digits++] = static_cast<char>('0' + (value % 10));
        value /= 10;
    } while (value > 0 && digits < sizeof(reversed_pid));
    if (value > 0) {
        errno = EOVERFLOW;
        return false;
    }
    while (digits > 0) path[used++] = reversed_pid[--digits];
    memcpy(path + used, suffix, sizeof(suffix));
    return true;
}

inline bool read_direct_children(
        pid_t *children,
        size_t capacity,
        size_t *child_count) {
    *child_count = 0;
    char path[96] = {};
    if (!child_list_path(path, sizeof(path))) return false;

    const int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    char bytes[kMaxChildrenFileBytes + 1] = {};
    size_t used = 0;
    while (used < kMaxChildrenFileBytes) {
        const ssize_t result = read(fd, bytes + used, kMaxChildrenFileBytes - used);
        if (result > 0) {
            used += static_cast<size_t>(result);
        } else if (result == 0) {
            break;
        } else if (errno == EINTR) {
            continue;
        } else {
            const int read_error = errno;
            close(fd);
            errno = read_error;
            return false;
        }
    }
    close(fd);
    if (used == kMaxChildrenFileBytes) {
        errno = EOVERFLOW;
        return false;
    }
    bytes[used] = '\0';

    char *cursor = bytes;
    while (*cursor != '\0') {
        while (*cursor == ' ' || *cursor == '\n' || *cursor == '\t') ++cursor;
        if (*cursor == '\0') break;
        if (*cursor < '0' || *cursor > '9' || *child_count >= capacity) {
            errno = EPROTO;
            return false;
        }
        long value = 0;
        while (*cursor >= '0' && *cursor <= '9') {
            const int digit = *cursor - '0';
            if (value > (INT_MAX - digit) / 10) {
                errno = EOVERFLOW;
                return false;
            }
            value = value * 10 + digit;
            ++cursor;
        }
        if (value <= 1) {
            errno = EPROTO;
            return false;
        }
        children[(*child_count)++] = static_cast<pid_t>(value);
    }
    return true;
}

inline bool become_child_subreaper() {
    if (prctl(PR_SET_CHILD_SUBREAPER, 1) != 0) return false;
    pid_t children[kMaxDirectChildren] = {};
    size_t child_count = 0;
    return read_direct_children(children, kMaxDirectChildren, &child_count);
}

// The caller must be a dedicated child subreaper. Once the main command has been reaped, every
// remaining descendant eventually becomes a direct child even if it called setsid()/setpgid().
inline bool kill_and_reap_descendants() {
    pid_t children[kMaxDirectChildren] = {};
    while (true) {
        size_t child_count = 0;
        if (!read_direct_children(children, kMaxDirectChildren, &child_count)) return false;
        if (child_count == 0) {
            int ignored_status = 0;
            const pid_t reaped = waitpid(-1, &ignored_status, WNOHANG);
            if (reaped > 0) continue;
            if (reaped < 0 && errno == ECHILD) return true;
            // Reparenting after a parent exit is asynchronous with procfs observation. Avoid
            // declaring success until waitpid also proves that this monitor has no children.
            usleep(1000);
            continue;
        }

        for (size_t index = 0; index < child_count; ++index) {
            kill(children[index], SIGKILL);
        }
        for (size_t index = 0; index < child_count; ++index) {
            const pid_t child = children[index];
            int ignored_status = 0;
            pid_t reaped = -1;
            do {
                reaped = waitpid(child, &ignored_status, 0);
            } while (reaped < 0 && errno == EINTR);
            if (reaped < 0 && errno != ECHILD) return false;
        }
    }
}

} // namespace workspace_process
