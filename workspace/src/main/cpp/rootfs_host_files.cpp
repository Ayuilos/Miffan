#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <string>
#include <utility>
#include <vector>

namespace {

constexpr size_t kMaxRootPathBytes = 1024 * 1024;
constexpr size_t kMaxRelativePathBytes = 4096;
constexpr size_t kMaxSegmentBytes = 255;
constexpr size_t kMaxMaintenanceFileBytes = 256 * 1024;
constexpr jint kFileMissing = 0;
constexpr jint kFileRegular = 1;
constexpr jint kFileSymlink = 2;

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

int open_root(JNIEnv *env, const std::string &root, bool *missing) {
    *missing = false;
    if (root.empty() || root.front() != '/') {
        throw_unsafe_path(env, "Rootfs host root must be absolute");
        return -1;
    }
    const int fd = open(root.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) {
        if (errno == ENOENT) {
            *missing = true;
            return -1;
        }
        if (errno == ELOOP || errno == ENOTDIR) {
            throw_unsafe_path(env, "Rootfs host root must be a real directory");
        } else {
            throw_io_exception(env, "Unable to open Rootfs host root", errno);
        }
        return -1;
    }
    if (!validate_owned_directory(env, fd, "Rootfs root")) {
        close(fd);
        return -1;
    }
    return fd;
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
        const std::string &relative_path) {
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
    if (status.st_nlink != 1) {
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
        jint max_bytes) {
    if (max_bytes < 0 || static_cast<size_t>(max_bytes) > kMaxMaintenanceFileBytes) {
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
    if (!validate_regular_file(env, status, relative)) return nullptr;
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

extern "C" JNIEXPORT void JNICALL
Java_me_rerere_workspace_RootfsHostFileBridge_writeFile(
        JNIEnv *env,
        jclass,
        jbyteArray root_bytes,
        jbyteArray relative_bytes,
        jbyteArray content_bytes,
        jboolean replace_leaf_symlink) {
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
            kMaxMaintenanceFileBytes,
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
