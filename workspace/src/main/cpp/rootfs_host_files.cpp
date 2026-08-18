#include <jni.h>

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

#include <string>
#include <utility>
#include <vector>

namespace {

constexpr size_t kMaxRootPathBytes = 1024 * 1024;
constexpr size_t kMaxRelativePathBytes = 4096;
constexpr size_t kMaxSegmentBytes = 255;
constexpr size_t kMaxHostFileBytes = 8 * 1024 * 1024;
constexpr size_t kMaxDeletionDepth = 256;
constexpr size_t kMaxDeletionOperations = 1000 * 1000;
constexpr size_t kMaxDiscoveryEntries = 100 * 1000;
constexpr jint kFileMissing = 0;
constexpr jint kFileRegular = 1;
constexpr jint kFileSymlink = 2;
constexpr jint kEntryMissing = 0;
constexpr jint kEntryRegular = 1;
constexpr jint kEntryDirectory = 2;
constexpr jint kEntrySymlink = 3;
constexpr jint kEntryOther = 4;

class ScopedFd {
public:
    explicit ScopedFd(int fd = -1) : fd_(fd) {}
    ~ScopedFd() { reset(); }
    ScopedFd(const ScopedFd &) = delete;
    ScopedFd &operator=(const ScopedFd &) = delete;

    int get() const { return fd_; }

    void reset(int replacement = -1) {
        if (fd_ >= 0) close(fd_);
        fd_ = replacement;
    }

    int release() {
        const int result = fd_;
        fd_ = -1;
        return result;
    }

private:
    int fd_;
};

void throw_exception(JNIEnv *env, const char *class_name, const std::string &message) {
    jclass type = env->FindClass(class_name);
    if (type != nullptr) env->ThrowNew(type, message.c_str());
}

void throw_io_exception(JNIEnv *env, const std::string &operation, int error_number) {
    std::string message(operation);
    message.append(": ");
    message.append(strerror(error_number));
    throw_exception(env, "java/io/IOException", message);
}

void throw_unsafe_path(JNIEnv *env, const std::string &message) {
    throw_exception(env, "java/lang/IllegalArgumentException", message);
}

bool copy_bytes(
        JNIEnv *env,
        jbyteArray input,
        std::string *output,
        size_t max_bytes,
        bool reject_nul) {
    if (input == nullptr) {
        throw_exception(env, "java/lang/IllegalArgumentException", "Rootfs host path is null");
        return false;
    }
    const jsize length = env->GetArrayLength(input);
    if (length < 0 || static_cast<size_t>(length) > max_bytes) {
        throw_exception(env, "java/lang/IllegalArgumentException", "Rootfs host path is too large");
        return false;
    }
    output->resize(static_cast<size_t>(length));
    if (length > 0) {
        env->GetByteArrayRegion(
                input,
                0,
                length,
                reinterpret_cast<jbyte *>(&(*output)[0]));
        if (env->ExceptionCheck()) return false;
    }
    if (reject_nul && output->find('\0') != std::string::npos) {
        throw_exception(env, "java/lang/IllegalArgumentException", "Rootfs host path contains NUL");
        return false;
    }
    return true;
}

bool parse_relative_path(
        JNIEnv *env,
        const std::string &path,
        bool allow_empty,
        std::vector<std::string> *segments) {
    if (path.empty()) {
        if (allow_empty) return true;
        throw_unsafe_path(env, "Rootfs maintenance path must identify a file");
        return false;
    }
    if (path.front() == '/' || path.back() == '/' || path.find('\\') != std::string::npos) {
        throw_unsafe_path(env, "Rootfs maintenance path must be an unambiguous relative path");
        return false;
    }
    size_t start = 0;
    while (start < path.size()) {
        const size_t separator = path.find('/', start);
        const size_t end = separator == std::string::npos ? path.size() : separator;
        const size_t length = end - start;
        if (length == 0 || length > kMaxSegmentBytes) {
            throw_unsafe_path(env, "Rootfs maintenance path has an invalid segment");
            return false;
        }
        std::string segment = path.substr(start, length);
        if (segment == "." || segment == "..") {
            throw_unsafe_path(env, "Rootfs maintenance path is ambiguous");
            return false;
        }
        segments->push_back(std::move(segment));
        if (separator == std::string::npos) break;
        start = separator + 1;
    }
    return true;
}

bool parse_absolute_path(
        JNIEnv *env,
        const std::string &path,
        bool allow_root,
        std::vector<std::string> *segments) {
    if (path.empty() || path.front() != '/' || path.find('\\') != std::string::npos) {
        throw_unsafe_path(env, "Rootfs host path must be an unambiguous absolute path");
        return false;
    }
    if (path == "/") {
        if (allow_root) return true;
        throw_unsafe_path(env, "Refusing host maintenance on the filesystem root");
        return false;
    }
    if (path.back() == '/') {
        throw_unsafe_path(env, "Rootfs host path must not have a trailing separator");
        return false;
    }
    return parse_relative_path(env, path.substr(1), false, segments);
}

bool validate_owned_directory(JNIEnv *env, int fd, const std::string &label) {
    struct stat status = {};
    if (fstat(fd, &status) != 0) {
        throw_io_exception(env, "Unable to inspect " + label, errno);
        return false;
    }
    if (!S_ISDIR(status.st_mode) || status.st_uid != getuid()) {
        throw_unsafe_path(env, "Refusing unsafe Rootfs directory: " + label);
        return false;
    }
    return true;
}

int open_absolute_directory_segments(
        JNIEnv *env,
        const std::vector<std::string> &segments,
        size_t segment_count,
        const std::string &label,
        bool require_owned,
        bool *missing) {
    *missing = false;
    ScopedFd current(open("/", O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW));
    if (current.get() < 0) {
        throw_io_exception(env, "Unable to anchor host filesystem root", errno);
        return -1;
    }
    for (size_t index = 0; index < segment_count; ++index) {
        const std::string &segment = segments[index];
        const int next = openat(
                current.get(),
                segment.c_str(),
                O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
        if (next < 0) {
            if (errno == ENOENT) {
                *missing = true;
            } else if (errno == ELOOP || errno == ENOTDIR) {
                throw_unsafe_path(
                        env,
                        "Refusing host access through symbolic link or non-directory: " + label);
            } else {
                throw_io_exception(env, "Unable to open host directory " + label, errno);
            }
            return -1;
        }
        current.reset(next);
    }
    if (require_owned && !validate_owned_directory(env, current.get(), label)) {
        return -1;
    }
    return current.release();
}

int open_root(JNIEnv *env, const std::string &root, bool *missing) {
    std::vector<std::string> segments;
    if (!parse_absolute_path(env, root, true, &segments)) return -1;
    return open_absolute_directory_segments(
            env,
            segments,
            segments.size(),
            "Rootfs root",
            true,
            missing);
}

int open_directory_chain(
        JNIEnv *env,
        const std::string &root,
        const std::vector<std::string> &segments,
        size_t segment_count,
        bool create,
        bool *missing) {
    ScopedFd current(open_root(env, root, missing));
    if (current.get() < 0) return -1;
    for (size_t index = 0; index < segment_count; ++index) {
        const std::string &segment = segments[index];
        int next = openat(
                current.get(),
                segment.c_str(),
                O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
        if (next < 0 && errno == ENOENT && create) {
            if (mkdirat(current.get(), segment.c_str(), 0755) != 0 && errno != EEXIST) {
                throw_io_exception(env, "Unable to create Rootfs directory /" + segment, errno);
                return -1;
            }
            next = openat(
                    current.get(),
                    segment.c_str(),
                    O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
        }
        if (next < 0) {
            if (errno == ENOENT && !create) {
                *missing = true;
            } else if (errno == ELOOP || errno == ENOTDIR) {
                throw_unsafe_path(
                        env,
                        "Refusing Rootfs host access through symbolic link or non-directory: /" +
                            segment);
            } else {
                throw_io_exception(env, "Unable to open Rootfs directory /" + segment, errno);
            }
            return -1;
        }
        if (!validate_owned_directory(env, next, "/" + segment)) {
            close(next);
            return -1;
        }
        current.reset(next);
    }
    return current.release();
}

bool validate_regular_file(
        JNIEnv *env,
        const struct stat &status,
        const std::string &relative_path,
        bool reject_hardlinks = true) {
    if (!S_ISREG(status.st_mode)) {
        throw_unsafe_path(
                env,
                "Rootfs maintenance path is not a regular file: /" + relative_path);
        return false;
    }
    if (status.st_uid != getuid()) {
        throw_unsafe_path(
                env,
                "Rootfs maintenance file has an unexpected owner: /" + relative_path);
        return false;
    }
    if (reject_hardlinks && status.st_nlink != 1) {
        throw_unsafe_path(
                env,
                "Refusing Rootfs host access through hard-linked file: /" + relative_path);
        return false;
    }
    return true;
}

int inspect_leaf(
        JNIEnv *env,
        int parent_fd,
        const std::string &leaf,
        const std::string &relative_path,
        struct stat *status) {
    if (fstatat(parent_fd, leaf.c_str(), status, AT_SYMLINK_NOFOLLOW) != 0) {
        if (errno == ENOENT) return kFileMissing;
        throw_io_exception(env, "Unable to inspect Rootfs file /" + relative_path, errno);
        return -1;
    }
    if (S_ISLNK(status->st_mode)) return kFileSymlink;
    if (!validate_regular_file(env, *status, relative_path)) return -1;
    return kFileRegular;
}

bool prepare_absolute_leaf(
        JNIEnv *env,
        jbyteArray path_bytes,
        std::string *path,
        std::vector<std::string> *segments) {
    return copy_bytes(env, path_bytes, path, kMaxRootPathBytes, true) &&
        parse_absolute_path(env, *path, false, segments);
}

bool delete_entry_at(
        JNIEnv *env,
        int parent_fd,
        const std::string &name,
        size_t depth,
        size_t *operations,
        bool allow_directory) {
    if (depth > kMaxDeletionDepth) {
        throw_unsafe_path(env, "Host maintenance tree exceeds maximum directory depth");
        return false;
    }
    *operations += 1;
    if (*operations > kMaxDeletionOperations) {
        throw_unsafe_path(env, "Host maintenance tree exceeds maximum deletion operations");
        return false;
    }

    struct stat status = {};
    if (fstatat(parent_fd, name.c_str(), &status, AT_SYMLINK_NOFOLLOW) != 0) {
        if (errno == ENOENT) return true;
        throw_io_exception(env, "Unable to inspect host maintenance entry", errno);
        return false;
    }
    if (S_ISDIR(status.st_mode) && !allow_directory) {
        throw_unsafe_path(env, "Directory delete requires recursive = true");
        return false;
    }
    if (!S_ISDIR(status.st_mode)) {
        if (unlinkat(parent_fd, name.c_str(), 0) == 0 || errno == ENOENT) return true;
        if (errno == EISDIR || errno == EPERM) {
            throw_unsafe_path(env, "Host maintenance entry changed during deletion");
            return false;
        }
        throw_io_exception(env, "Unable to unlink host maintenance entry", errno);
        return false;
    }

    ScopedFd directory(openat(
            parent_fd,
            name.c_str(),
            O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW));
    if (directory.get() < 0) {
        if (errno == ENOENT) return true;
        if (errno == ELOOP || errno == ENOTDIR) {
            throw_unsafe_path(env, "Host maintenance entry changed during deletion");
            return false;
        }
        throw_io_exception(env, "Unable to open host maintenance directory", errno);
        return false;
    }
    if (!validate_owned_directory(env, directory.get(), "host maintenance tree")) return false;

    const int stream_fd = fcntl(directory.get(), F_DUPFD_CLOEXEC, 0);
    if (stream_fd < 0) {
        throw_io_exception(env, "Unable to duplicate host maintenance directory", errno);
        return false;
    }
    DIR *stream = fdopendir(stream_fd);
    if (stream == nullptr) {
        const int saved_errno = errno;
        close(stream_fd);
        throw_io_exception(env, "Unable to enumerate host maintenance directory", saved_errno);
        return false;
    }
    while (true) {
        errno = 0;
        dirent *entry = readdir(stream);
        if (entry == nullptr) {
            const int saved_errno = errno;
            closedir(stream);
            if (saved_errno != 0) {
                throw_io_exception(env, "Unable to enumerate host maintenance directory", saved_errno);
                return false;
            }
            break;
        }
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) continue;
        if (!delete_entry_at(
                env,
                directory.get(),
                entry->d_name,
                depth + 1,
                operations,
                true)) {
            closedir(stream);
            return false;
        }
    }

    struct stat current = {};
    if (fstatat(parent_fd, name.c_str(), &current, AT_SYMLINK_NOFOLLOW) != 0) {
        if (errno == ENOENT) return true;
        throw_io_exception(env, "Unable to re-inspect host maintenance directory", errno);
        return false;
    }
    if (!S_ISDIR(current.st_mode) || current.st_dev != status.st_dev || current.st_ino != status.st_ino) {
        throw_unsafe_path(env, "Host maintenance directory changed during deletion");
        return false;
    }
    if (unlinkat(parent_fd, name.c_str(), AT_REMOVEDIR) == 0 || errno == ENOENT) return true;
    if (errno == ENOTEMPTY || errno == EEXIST) {
        throw_unsafe_path(env, "Host maintenance directory changed during deletion");
    } else {
        throw_io_exception(env, "Unable to remove host maintenance directory", errno);
    }
    return false;
}

int renameat_no_replace(
        int source_parent,
        const char *source,
        int target_parent,
        const char *target) {
#if defined(SYS_renameat2)
    return static_cast<int>(syscall(
            SYS_renameat2,
            source_parent,
            source,
            target_parent,
            target,
            1 /* RENAME_NOREPLACE */));
#else
    errno = ENOSYS;
    return -1;
#endif
}

bool prepare_paths(
        JNIEnv *env,
        jbyteArray root_bytes,
        jbyteArray relative_bytes,
        bool allow_empty,
        std::string *root,
        std::string *relative,
        std::vector<std::string> *segments) {
    return copy_bytes(env, root_bytes, root, kMaxRootPathBytes, true) &&
        copy_bytes(env, relative_bytes, relative, kMaxRelativePathBytes, true) &&
        parse_relative_path(env, *relative, allow_empty, segments);
}

struct DirectoryRecord {
    std::string relative_path;
    bool is_directory;
    int64_t size;
    int64_t updated_at_ms;
};

int64_t modified_time_millis(const struct stat &status) {
    return static_cast<int64_t>(status.st_mtim.tv_sec) * 1000 +
        static_cast<int64_t>(status.st_mtim.tv_nsec) / 1000 / 1000;
}

bool append_directory_records(
        JNIEnv *env,
        int directory_fd,
        const std::string &prefix,
        bool recursive,
        size_t depth,
        size_t max_scanned_entries,
        size_t *scanned_entries,
        std::vector<DirectoryRecord> *records) {
    if (depth > kMaxDeletionDepth) {
        throw_unsafe_path(env, "Workspace discovery exceeds maximum directory depth");
        return false;
    }
    const int duplicate = dup(directory_fd);
    if (duplicate < 0) {
        throw_io_exception(env, "Unable to duplicate workspace discovery directory", errno);
        return false;
    }
    DIR *directory = fdopendir(duplicate);
    if (directory == nullptr) {
        const int saved_errno = errno;
        close(duplicate);
        throw_io_exception(env, "Unable to enumerate workspace directory", saved_errno);
        return false;
    }

    while (true) {
        errno = 0;
        dirent *entry = readdir(directory);
        if (entry == nullptr) {
            const int saved_errno = errno;
            closedir(directory);
            if (saved_errno != 0) {
                throw_io_exception(env, "Unable to enumerate workspace directory", saved_errno);
                return false;
            }
            return true;
        }
        const std::string name(entry->d_name);
        if (name == "." || name == "..") continue;
        ++(*scanned_entries);
        if (*scanned_entries > max_scanned_entries) {
            closedir(directory);
            throw_unsafe_path(env, "Workspace discovery exceeds maximum scanned entries");
            return false;
        }
        if (name.rfind(".l2s.", 0) == 0 || name.find('\\') != std::string::npos) continue;

        struct stat status = {};
        if (fstatat(directory_fd, name.c_str(), &status, AT_SYMLINK_NOFOLLOW) != 0) {
            if (errno == ENOENT) {
                errno = 0;
                continue;
            }
            const int saved_errno = errno;
            closedir(directory);
            throw_io_exception(env, "Unable to inspect workspace entry", saved_errno);
            return false;
        }
        if (S_ISLNK(status.st_mode)) continue;
        if (!S_ISREG(status.st_mode) && !S_ISDIR(status.st_mode)) continue;
        if (status.st_uid != getuid()) {
            closedir(directory);
            throw_unsafe_path(env, "Workspace discovery entry has an unexpected owner");
            return false;
        }

        std::string relative_path = prefix.empty() ? name : prefix + "/" + name;
        if (relative_path.size() > kMaxRelativePathBytes) {
            closedir(directory);
            throw_unsafe_path(env, "Workspace discovery path is too long");
            return false;
        }
        records->push_back(DirectoryRecord{
            relative_path,
            S_ISDIR(status.st_mode),
            S_ISREG(status.st_mode) ? static_cast<int64_t>(status.st_size) : 0,
            modified_time_millis(status),
        });

        if (!recursive || !S_ISDIR(status.st_mode)) continue;
        ScopedFd child(openat(
                directory_fd,
                name.c_str(),
                O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW));
        if (child.get() < 0) {
            if (errno == ENOENT || errno == ELOOP || errno == ENOTDIR) {
                errno = 0;
                continue;
            }
            const int saved_errno = errno;
            closedir(directory);
            throw_io_exception(env, "Unable to open workspace discovery directory", saved_errno);
            return false;
        }
        struct stat opened_status = {};
        if (fstat(child.get(), &opened_status) != 0) {
            const int saved_errno = errno;
            closedir(directory);
            throw_io_exception(env, "Unable to verify workspace discovery directory", saved_errno);
            return false;
        }
        if (!S_ISDIR(opened_status.st_mode) ||
            opened_status.st_uid != getuid() ||
            opened_status.st_dev != status.st_dev ||
            opened_status.st_ino != status.st_ino) {
            continue;
        }
        if (!append_directory_records(
                env,
                child.get(),
                relative_path,
                true,
                depth + 1,
                max_scanned_entries,
                scanned_entries,
                records)) {
            closedir(directory);
            return false;
        }
    }
}

void append_int64_le(std::vector<jbyte> *encoded, int64_t value) {
    const uint64_t bits = static_cast<uint64_t>(value);
    for (size_t index = 0; index < sizeof(bits); ++index) {
        encoded->push_back(static_cast<jbyte>((bits >> (index * 8)) & 0xff));
    }
}

jobjectArray encode_directory_records(
        JNIEnv *env,
        const std::vector<DirectoryRecord> &records) {
    jclass byte_array_class = env->FindClass("[B");
    if (byte_array_class == nullptr) return nullptr;
    jobjectArray result = env->NewObjectArray(
            static_cast<jsize>(records.size()),
            byte_array_class,
            nullptr);
    env->DeleteLocalRef(byte_array_class);
    if (result == nullptr) return nullptr;

    for (size_t index = 0; index < records.size(); ++index) {
        const DirectoryRecord &record = records[index];
        std::vector<jbyte> encoded;
        encoded.reserve(17 + record.relative_path.size());
        encoded.push_back(record.is_directory ? 2 : 1);
        append_int64_le(&encoded, record.size);
        append_int64_le(&encoded, record.updated_at_ms);
        encoded.insert(
                encoded.end(),
                reinterpret_cast<const jbyte *>(record.relative_path.data()),
                reinterpret_cast<const jbyte *>(record.relative_path.data()) +
                    record.relative_path.size());
        jbyteArray item = env->NewByteArray(static_cast<jsize>(encoded.size()));
        if (item == nullptr) return nullptr;
        env->SetByteArrayRegion(item, 0, static_cast<jsize>(encoded.size()), encoded.data());
        if (!env->ExceptionCheck()) {
            env->SetObjectArrayElement(result, static_cast<jsize>(index), item);
        }
        env->DeleteLocalRef(item);
        if (env->ExceptionCheck()) return nullptr;
    }
    return result;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_me_rerere_workspace_RootfsHostFileBridge_directory(
        JNIEnv *env,
        jclass,
        jbyteArray root_bytes,
        jbyteArray relative_bytes,
        jboolean create) {
    std::string root;
    std::string relative;
    std::vector<std::string> segments;
    if (!prepare_paths(
            env,
            root_bytes,
            relative_bytes,
            true,
            &root,
            &relative,
            &segments)) {
        return JNI_FALSE;
    }
    bool missing = false;
    ScopedFd directory(open_directory_chain(
            env,
            root,
            segments,
            segments.size(),
            create == JNI_TRUE,
            &missing));
    if (env->ExceptionCheck()) return JNI_FALSE;
    return directory.get() >= 0 && !missing ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_me_rerere_workspace_RootfsHostFileBridge_fileKind(
        JNIEnv *env,
        jclass,
        jbyteArray root_bytes,
        jbyteArray relative_bytes) {
    std::string root;
    std::string relative;
    std::vector<std::string> segments;
    if (!prepare_paths(
            env,
            root_bytes,
            relative_bytes,
            false,
            &root,
            &relative,
            &segments)) {
        return -1;
    }
    bool missing = false;
    ScopedFd parent(open_directory_chain(
            env,
            root,
            segments,
            segments.size() - 1,
            false,
            &missing));
    if (parent.get() < 0) return missing && !env->ExceptionCheck() ? kFileMissing : -1;
    struct stat status = {};
    return inspect_leaf(env, parent.get(), segments.back(), relative, &status);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_me_rerere_workspace_RootfsHostFileBridge_readFile(
        JNIEnv *env,
        jclass,
        jbyteArray root_bytes,
        jbyteArray relative_bytes,
        jint max_bytes,
        jboolean reject_hardlinks) {
    if (max_bytes < 0 || static_cast<size_t>(max_bytes) > kMaxHostFileBytes) {
        throw_exception(env, "java/lang/IllegalArgumentException", "Invalid Rootfs read limit");
        return nullptr;
    }
    std::string root;
    std::string relative;
    std::vector<std::string> segments;
    if (!prepare_paths(
            env,
            root_bytes,
            relative_bytes,
            false,
            &root,
            &relative,
            &segments)) {
        return nullptr;
    }
    bool missing = false;
    ScopedFd parent(open_directory_chain(
            env,
            root,
            segments,
            segments.size() - 1,
            false,
            &missing));
    if (parent.get() < 0) return nullptr;

    ScopedFd file(openat(
            parent.get(),
            segments.back().c_str(),
            O_RDONLY | O_CLOEXEC | O_NOFOLLOW));
    if (file.get() < 0) {
        if (errno == ENOENT) return nullptr;
        if (errno == ELOOP) {
            throw_unsafe_path(
                    env,
                    "Refusing Rootfs host access through symbolic link: /" + relative);
        } else {
            throw_io_exception(env, "Unable to open Rootfs file /" + relative, errno);
        }
        return nullptr;
    }
    struct stat status = {};
    if (fstat(file.get(), &status) != 0) {
        throw_io_exception(env, "Unable to inspect Rootfs file /" + relative, errno);
        return nullptr;
    }
    if (!validate_regular_file(env, status, relative, reject_hardlinks == JNI_TRUE)) return nullptr;
    if (status.st_size < 0 || status.st_size > max_bytes) {
        throw_unsafe_path(env, "Rootfs maintenance file is too large: /" + relative);
        return nullptr;
    }

    std::vector<jbyte> content;
    content.reserve(static_cast<size_t>(status.st_size));
    jbyte buffer[8192] = {};
    while (true) {
        const ssize_t read_count = read(file.get(), buffer, sizeof(buffer));
        if (read_count > 0) {
            if (content.size() + static_cast<size_t>(read_count) >
                static_cast<size_t>(max_bytes)) {
                throw_unsafe_path(env, "Rootfs maintenance file is too large: /" + relative);
                return nullptr;
            }
            content.insert(content.end(), buffer, buffer + read_count);
        } else if (read_count == 0) {
            break;
        } else if (errno != EINTR) {
            throw_io_exception(env, "Unable to read Rootfs file /" + relative, errno);
            return nullptr;
        }
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(content.size()));
    if (result == nullptr) return nullptr;
    if (!content.empty()) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(content.size()), content.data());
    }
    return env->ExceptionCheck() ? nullptr : result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_rerere_workspace_RootfsHostFileBridge_fileSize(
        JNIEnv *env,
        jclass,
        jbyteArray root_bytes,
        jbyteArray relative_bytes,
        jboolean reject_hardlinks) {
    std::string root;
    std::string relative;
    std::vector<std::string> segments;
    if (!prepare_paths(
            env,
            root_bytes,
            relative_bytes,
            false,
            &root,
            &relative,
            &segments)) {
        return -1;
    }
    bool missing = false;
    ScopedFd parent(open_directory_chain(
            env,
            root,
            segments,
            segments.size() - 1,
            false,
            &missing));
    if (parent.get() < 0) return -1;

    ScopedFd file(openat(
            parent.get(),
            segments.back().c_str(),
            O_RDONLY | O_CLOEXEC | O_NOFOLLOW));
    if (file.get() < 0) {
        if (errno == ENOENT) return -1;
        if (errno == ELOOP) {
            throw_unsafe_path(
                    env,
                    "Refusing Rootfs host access through symbolic link: /" + relative);
        } else {
            throw_io_exception(env, "Unable to open Rootfs file /" + relative, errno);
        }
        return -1;
    }
    struct stat status = {};
    if (fstat(file.get(), &status) != 0) {
        throw_io_exception(env, "Unable to inspect Rootfs file /" + relative, errno);
        return -1;
    }
    if (!S_ISREG(status.st_mode)) return -2;
    if (!validate_regular_file(env, status, relative, reject_hardlinks == JNI_TRUE)) return -1;
    return static_cast<jlong>(status.st_size);
}

extern "C" JNIEXPORT void JNICALL
Java_me_rerere_workspace_RootfsHostFileBridge_writeFile(
        JNIEnv *env,
        jclass,
        jbyteArray root_bytes,
        jbyteArray relative_bytes,
        jbyteArray content_bytes,
        jboolean replace_leaf_symlink,
        jboolean overwrite) {
    std::string root;
    std::string relative;
    std::vector<std::string> segments;
    if (!prepare_paths(
            env,
            root_bytes,
            relative_bytes,
            false,
            &root,
            &relative,
            &segments)) {
        return;
    }
    std::string content;
    if (!copy_bytes(
            env,
            content_bytes,
            &content,
            kMaxHostFileBytes,
            false)) {
        return;
    }

    bool missing = false;
    ScopedFd parent(open_directory_chain(
            env,
            root,
            segments,
            segments.size() - 1,
            true,
            &missing));
    if (parent.get() < 0) return;
    const std::string &leaf = segments.back();
    struct stat before = {};
    int kind = inspect_leaf(env, parent.get(), leaf, relative, &before);
    if (kind < 0) return;
    if (kind == kFileSymlink) {
        if (replace_leaf_symlink != JNI_TRUE) {
            throw_unsafe_path(
                    env,
                    "Refusing Rootfs host access through symbolic link: /" + relative);
            return;
        }
        if (unlinkat(parent.get(), leaf.c_str(), 0) != 0 && errno != ENOENT) {
            throw_io_exception(env, "Unable to replace Rootfs symlink /" + relative, errno);
            return;
        }
        kind = kFileMissing;
    }
    if (kind == kFileRegular && overwrite != JNI_TRUE) {
        throw_unsafe_path(env, "Rootfs maintenance file already exists: /" + relative);
        return;
    }

    int flags = O_WRONLY | O_CLOEXEC | O_NOFOLLOW;
    if (kind == kFileMissing) flags |= O_CREAT | O_EXCL;
    ScopedFd file(openat(parent.get(), leaf.c_str(), flags, 0644));
    if (file.get() < 0) {
        if (errno == ELOOP) {
            throw_unsafe_path(
                    env,
                    "Refusing Rootfs host access through symbolic link: /" + relative);
        } else {
            throw_io_exception(env, "Unable to open Rootfs file /" + relative, errno);
        }
        return;
    }
    struct stat opened = {};
    if (fstat(file.get(), &opened) != 0) {
        throw_io_exception(env, "Unable to inspect opened Rootfs file /" + relative, errno);
        return;
    }
    if (!validate_regular_file(env, opened, relative)) return;
    if (kind == kFileMissing && fchmod(file.get(), 0644) != 0) {
        throw_io_exception(env, "Unable to set Rootfs file mode /" + relative, errno);
        return;
    }
    if (ftruncate(file.get(), 0) != 0) {
        throw_io_exception(env, "Unable to truncate Rootfs file /" + relative, errno);
        return;
    }
    size_t written = 0;
    while (written < content.size()) {
        const ssize_t result = write(
                file.get(),
                content.data() + written,
                content.size() - written);
        if (result > 0) {
            written += static_cast<size_t>(result);
        } else if (result < 0 && errno == EINTR) {
            continue;
        } else {
            throw_io_exception(env, "Unable to write Rootfs file /" + relative, errno);
            return;
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_me_rerere_workspace_RootfsHostFileBridge_chmodDirectory(
        JNIEnv *env,
        jclass,
        jbyteArray root_bytes,
        jbyteArray relative_bytes,
        jint mode) {
    if (mode < 0 || mode > 07777) {
        throw_exception(env, "java/lang/IllegalArgumentException", "Invalid Rootfs directory mode");
        return;
    }
    std::string root;
    std::string relative;
    std::vector<std::string> segments;
    if (!prepare_paths(
            env,
            root_bytes,
            relative_bytes,
            true,
            &root,
            &relative,
            &segments)) {
        return;
    }
    bool missing = false;
    ScopedFd directory(open_directory_chain(
            env,
            root,
            segments,
            segments.size(),
            false,
            &missing));
    if (directory.get() < 0) {
        if (missing && !env->ExceptionCheck()) {
            throw_unsafe_path(env, "Rootfs directory is missing: /" + relative);
        }
        return;
    }
    if (fchmod(directory.get(), static_cast<mode_t>(mode)) != 0) {
        throw_io_exception(env, "Unable to chmod Rootfs directory /" + relative, errno);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_rerere_workspace_RootfsHostFileBridge_deleteTree(
        JNIEnv *env,
        jclass,
        jbyteArray absolute_path_bytes) {
    std::string absolute_path;
    std::vector<std::string> segments;
    if (!prepare_absolute_leaf(env, absolute_path_bytes, &absolute_path, &segments)) {
        return JNI_FALSE;
    }

    bool missing = false;
    ScopedFd parent(open_absolute_directory_segments(
            env,
            segments,
            segments.size() - 1,
            "host maintenance parent",
            true,
            &missing));
    if (parent.get() < 0) {
        return missing && !env->ExceptionCheck() ? JNI_TRUE : JNI_FALSE;
    }
    size_t operations = 0;
    return delete_entry_at(
            env,
            parent.get(),
            segments.back(),
            0,
            &operations,
            true) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_rerere_workspace_RootfsHostFileBridge_renameDirectoryNoReplace(
        JNIEnv *env,
        jclass,
        jbyteArray source_path_bytes,
        jbyteArray target_path_bytes) {
    std::string source_path;
    std::vector<std::string> source_segments;
    if (!prepare_absolute_leaf(
            env,
            source_path_bytes,
            &source_path,
            &source_segments)) {
        return JNI_FALSE;
    }
    std::string target_path;
    std::vector<std::string> target_segments;
    if (!prepare_absolute_leaf(
            env,
            target_path_bytes,
            &target_path,
            &target_segments)) {
        return JNI_FALSE;
    }

    bool source_parent_missing = false;
    ScopedFd source_parent(open_absolute_directory_segments(
            env,
            source_segments,
            source_segments.size() - 1,
            "host maintenance source parent",
            true,
            &source_parent_missing));
    if (source_parent.get() < 0) return JNI_FALSE;
    bool target_parent_missing = false;
    ScopedFd target_parent(open_absolute_directory_segments(
            env,
            target_segments,
            target_segments.size() - 1,
            "host maintenance destination parent",
            true,
            &target_parent_missing));
    if (target_parent.get() < 0) return JNI_FALSE;

    const std::string &source_name = source_segments.back();
    const std::string &target_name = target_segments.back();
    struct stat source_status = {};
    if (fstatat(
            source_parent.get(),
            source_name.c_str(),
            &source_status,
            AT_SYMLINK_NOFOLLOW) != 0) {
        if (errno == ENOENT) return JNI_FALSE;
        throw_io_exception(env, "Unable to inspect host maintenance source", errno);
        return JNI_FALSE;
    }
    if (!S_ISDIR(source_status.st_mode) || source_status.st_uid != getuid()) {
        throw_unsafe_path(env, "Host maintenance source must be an owned real directory");
        return JNI_FALSE;
    }
    struct stat target_status = {};
    if (fstatat(
            target_parent.get(),
            target_name.c_str(),
            &target_status,
            AT_SYMLINK_NOFOLLOW) == 0) {
        throw_unsafe_path(env, "Host maintenance destination already exists");
        return JNI_FALSE;
    }
    if (errno != ENOENT) {
        throw_io_exception(env, "Unable to inspect host maintenance destination", errno);
        return JNI_FALSE;
    }

    if (renameat_no_replace(
            source_parent.get(),
            source_name.c_str(),
            target_parent.get(),
            target_name.c_str()) != 0) {
        if (errno == EEXIST) {
            throw_unsafe_path(env, "Host maintenance destination appeared during rename");
        } else if (errno == ENOENT) {
            throw_unsafe_path(env, "Host maintenance source changed during rename");
        } else {
            throw_io_exception(env, "Unable to atomically rename host maintenance directory", errno);
        }
        return JNI_FALSE;
    }

    struct stat moved_status = {};
    if (fstatat(
            target_parent.get(),
            target_name.c_str(),
            &moved_status,
            AT_SYMLINK_NOFOLLOW) != 0) {
        throw_io_exception(env, "Unable to verify renamed host maintenance directory", errno);
        return JNI_FALSE;
    }
    if (!S_ISDIR(moved_status.st_mode) ||
        moved_status.st_uid != getuid() ||
        moved_status.st_dev != source_status.st_dev ||
        moved_status.st_ino != source_status.st_ino) {
        throw_unsafe_path(env, "Host maintenance source changed during rename");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_me_rerere_workspace_RootfsHostFileBridge_openFileCreate(
        JNIEnv *env,
        jclass,
        jbyteArray root_bytes,
        jbyteArray relative_bytes) {
    std::string root;
    std::string relative;
    std::vector<std::string> segments;
    if (!prepare_paths(
            env,
            root_bytes,
            relative_bytes,
            false,
            &root,
            &relative,
            &segments)) {
        return -1;
    }
    bool missing = false;
    ScopedFd parent(open_directory_chain(
            env,
            root,
            segments,
            segments.size() - 1,
            true,
            &missing));
    if (parent.get() < 0) return -1;

    const std::string &leaf = segments.back();
    ScopedFd file(openat(
            parent.get(),
            leaf.c_str(),
            O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW,
            0644));
    if (file.get() < 0) {
        if (errno == EEXIST) return -2;
        if (errno == ELOOP) {
            throw_unsafe_path(env, "Refusing host file creation through symbolic link");
        } else {
            throw_io_exception(env, "Unable to create host file", errno);
        }
        return -1;
    }
    struct stat status = {};
    if (fstat(file.get(), &status) != 0) {
        throw_io_exception(env, "Unable to inspect created host file", errno);
        return -1;
    }
    if (!validate_regular_file(env, status, relative)) return -1;
    if (fchmod(file.get(), 0644) != 0) {
        throw_io_exception(env, "Unable to set created host file mode", errno);
        return -1;
    }
    return file.release();
}

extern "C" JNIEXPORT jint JNICALL
Java_me_rerere_workspace_RootfsHostFileBridge_openFileRead(
        JNIEnv *env,
        jclass,
        jbyteArray root_bytes,
        jbyteArray relative_bytes,
        jboolean reject_hardlinks) {
    std::string root;
    std::string relative;
    std::vector<std::string> segments;
    if (!prepare_paths(
            env,
            root_bytes,
            relative_bytes,
            false,
            &root,
            &relative,
            &segments)) {
        return -1;
    }
    bool missing = false;
    ScopedFd parent(open_directory_chain(
            env,
            root,
            segments,
            segments.size() - 1,
            false,
            &missing));
    if (parent.get() < 0) return missing && !env->ExceptionCheck() ? -1 : -3;

    ScopedFd file(openat(
            parent.get(),
            segments.back().c_str(),
            O_RDONLY | O_CLOEXEC | O_NOFOLLOW));
    if (file.get() < 0) {
        if (errno == ENOENT) return -1;
        if (errno == ELOOP) {
            throw_unsafe_path(
                    env,
                    "Refusing Rootfs host access through symbolic link: /" + relative);
        } else {
            throw_io_exception(env, "Unable to open Rootfs file /" + relative, errno);
        }
        return -3;
    }
    struct stat status = {};
    if (fstat(file.get(), &status) != 0) {
        throw_io_exception(env, "Unable to inspect Rootfs file /" + relative, errno);
        return -3;
    }
    if (!S_ISREG(status.st_mode)) return -2;
    if (!validate_regular_file(env, status, relative, reject_hardlinks == JNI_TRUE)) return -3;
    return file.release();
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_me_rerere_workspace_RootfsHostFileBridge_discoverEntries(
        JNIEnv *env,
        jclass,
        jbyteArray root_bytes,
        jbyteArray relative_bytes,
        jboolean recursive,
        jint max_scanned_entries) {
    if (max_scanned_entries <= 0 ||
        static_cast<size_t>(max_scanned_entries) > kMaxDiscoveryEntries) {
        throw_unsafe_path(env, "Invalid workspace discovery entry limit");
        return nullptr;
    }
    std::string root;
    std::string relative;
    std::vector<std::string> segments;
    if (!prepare_paths(
            env,
            root_bytes,
            relative_bytes,
            true,
            &root,
            &relative,
            &segments)) {
        return nullptr;
    }
    bool missing = false;
    ScopedFd directory(open_directory_chain(
            env,
            root,
            segments,
            segments.size(),
            false,
            &missing));
    if (directory.get() < 0) return nullptr;

    std::vector<DirectoryRecord> records;
    size_t scanned_entries = 0;
    if (!append_directory_records(
            env,
            directory.get(),
            relative,
            recursive == JNI_TRUE,
            0,
            static_cast<size_t>(max_scanned_entries),
            &scanned_entries,
            &records)) {
        return nullptr;
    }
    return encode_directory_records(env, records);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_rerere_workspace_RootfsHostFileBridge_deleteRelative(
        JNIEnv *env,
        jclass,
        jbyteArray root_bytes,
        jbyteArray relative_bytes,
        jboolean recursive) {
    std::string root;
    std::string relative;
    std::vector<std::string> segments;
    if (!prepare_paths(
            env,
            root_bytes,
            relative_bytes,
            false,
            &root,
            &relative,
            &segments)) {
        return JNI_FALSE;
    }
    bool missing = false;
    ScopedFd parent(open_directory_chain(
            env,
            root,
            segments,
            segments.size() - 1,
            false,
            &missing));
    if (parent.get() < 0) return JNI_FALSE;
    struct stat status = {};
    if (fstatat(
            parent.get(),
            segments.back().c_str(),
            &status,
            AT_SYMLINK_NOFOLLOW) != 0) {
        if (errno == ENOENT) return JNI_FALSE;
        throw_io_exception(env, "Unable to inspect host delete target", errno);
        return JNI_FALSE;
    }
    size_t operations = 0;
    return delete_entry_at(
            env,
            parent.get(),
            segments.back(),
            0,
            &operations,
            recursive == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_me_rerere_workspace_RootfsHostFileBridge_entryKind(
        JNIEnv *env,
        jclass,
        jbyteArray root_bytes,
        jbyteArray relative_bytes) {
    std::string root;
    std::string relative;
    std::vector<std::string> segments;
    if (!prepare_paths(
            env,
            root_bytes,
            relative_bytes,
            false,
            &root,
            &relative,
            &segments)) {
        return -1;
    }
    bool missing = false;
    ScopedFd parent(open_directory_chain(
            env,
            root,
            segments,
            segments.size() - 1,
            false,
            &missing));
    if (parent.get() < 0) {
        return missing && !env->ExceptionCheck() ? kEntryMissing : -1;
    }
    struct stat status = {};
    if (fstatat(
            parent.get(),
            segments.back().c_str(),
            &status,
            AT_SYMLINK_NOFOLLOW) != 0) {
        if (errno == ENOENT) return kEntryMissing;
        throw_io_exception(env, "Unable to inspect host entry", errno);
        return -1;
    }
    if (S_ISLNK(status.st_mode)) return kEntrySymlink;
    if (status.st_uid != getuid()) {
        throw_unsafe_path(env, "Host entry has an unexpected owner");
        return -1;
    }
    if (S_ISREG(status.st_mode)) return kEntryRegular;
    if (S_ISDIR(status.st_mode)) return kEntryDirectory;
    return kEntryOther;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_rerere_workspace_RootfsHostFileBridge_renameEntryNoReplace(
        JNIEnv *env,
        jclass,
        jbyteArray root_bytes,
        jbyteArray source_relative_bytes,
        jbyteArray target_relative_bytes) {
    std::string root;
    std::string source_relative;
    std::vector<std::string> source_segments;
    if (!prepare_paths(
            env,
            root_bytes,
            source_relative_bytes,
            false,
            &root,
            &source_relative,
            &source_segments)) {
        return JNI_FALSE;
    }
    std::string target_relative;
    std::vector<std::string> target_segments;
    if (!copy_bytes(
            env,
            target_relative_bytes,
            &target_relative,
            kMaxRelativePathBytes,
            true) ||
        !parse_relative_path(env, target_relative, false, &target_segments)) {
        return JNI_FALSE;
    }

    bool source_parent_missing = false;
    ScopedFd source_parent(open_directory_chain(
            env,
            root,
            source_segments,
            source_segments.size() - 1,
            false,
            &source_parent_missing));
    if (source_parent.get() < 0) return JNI_FALSE;
    bool target_parent_missing = false;
    ScopedFd target_parent(open_directory_chain(
            env,
            root,
            target_segments,
            target_segments.size() - 1,
            false,
            &target_parent_missing));
    if (target_parent.get() < 0) return JNI_FALSE;

    const std::string &source_name = source_segments.back();
    const std::string &target_name = target_segments.back();
    struct stat source_status = {};
    if (fstatat(
            source_parent.get(),
            source_name.c_str(),
            &source_status,
            AT_SYMLINK_NOFOLLOW) != 0) {
        if (errno == ENOENT) return JNI_FALSE;
        throw_io_exception(env, "Unable to inspect host move source", errno);
        return JNI_FALSE;
    }
    if ((!S_ISREG(source_status.st_mode) && !S_ISDIR(source_status.st_mode)) ||
        source_status.st_uid != getuid()) {
        throw_unsafe_path(env, "Host move source must be an owned regular file or directory");
        return JNI_FALSE;
    }
    struct stat target_status = {};
    if (fstatat(
            target_parent.get(),
            target_name.c_str(),
            &target_status,
            AT_SYMLINK_NOFOLLOW) == 0) {
        throw_unsafe_path(env, "Host move destination already exists");
        return JNI_FALSE;
    }
    if (errno != ENOENT) {
        throw_io_exception(env, "Unable to inspect host move destination", errno);
        return JNI_FALSE;
    }
    if (renameat_no_replace(
            source_parent.get(),
            source_name.c_str(),
            target_parent.get(),
            target_name.c_str()) != 0) {
        if (errno == EEXIST) {
            throw_unsafe_path(env, "Host move destination appeared during rename");
        } else if (errno == ENOENT) {
            throw_unsafe_path(env, "Host move source changed during rename");
        } else {
            throw_io_exception(env, "Unable to atomically move host entry", errno);
        }
        return JNI_FALSE;
    }
    struct stat moved_status = {};
    if (fstatat(
            target_parent.get(),
            target_name.c_str(),
            &moved_status,
            AT_SYMLINK_NOFOLLOW) != 0) {
        throw_io_exception(env, "Unable to verify moved host entry", errno);
        return JNI_FALSE;
    }
    if (moved_status.st_uid != getuid() ||
        moved_status.st_dev != source_status.st_dev ||
        moved_status.st_ino != source_status.st_ino ||
        (moved_status.st_mode & S_IFMT) != (source_status.st_mode & S_IFMT)) {
        throw_unsafe_path(env, "Host move source changed during rename");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}
