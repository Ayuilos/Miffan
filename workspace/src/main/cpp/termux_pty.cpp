#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#include <string>
#include <vector>

namespace {

constexpr size_t kMaxEntries = 4096;
constexpr size_t kMaxEntryBytes = 1024 * 1024;
constexpr size_t kMaxTotalBytes = 4 * 1024 * 1024;

void close_quietly(int fd) {
    if (fd >= 0) close(fd);
}

void throw_exception(JNIEnv *env, const char *class_name, const std::string &message) {
    jclass type = env->FindClass(class_name);
    if (type != nullptr) env->ThrowNew(type, message.c_str());
}

void throw_io_exception(JNIEnv *env, const char *operation, int error_number) {
    std::string message(operation);
    message.append(": ");
    message.append(strerror(error_number));
    throw_exception(env, "java/io/IOException", message);
}

bool initialize_process_id(JNIEnv *env, jintArray process_id) {
    if (process_id == nullptr || env->GetArrayLength(process_id) < 1) {
        throw_exception(
                env,
                "java/lang/IllegalArgumentException",
                "Terminal process id output is required");
        return false;
    }
    const jint unavailable = -1;
    env->SetIntArrayRegion(process_id, 0, 1, &unavailable);
    return !env->ExceptionCheck();
}

char *copy_java_string(JNIEnv *env, jstring value, size_t *total_bytes) {
    if (value == nullptr) {
        throw_exception(env, "java/lang/IllegalArgumentException", "Terminal process input is null");
        return nullptr;
    }
    const jsize character_count = env->GetStringLength(value);
    const jchar *characters = env->GetStringChars(value, nullptr);
    if (characters == nullptr) return nullptr;
    bool contains_nul = false;
    for (jsize index = 0; index < character_count; ++index) {
        if (characters[index] == 0) {
            contains_nul = true;
            break;
        }
    }
    env->ReleaseStringChars(value, characters);
    if (contains_nul) {
        throw_exception(
                env,
                "java/lang/IllegalArgumentException",
                "Terminal process input contains NUL");
        return nullptr;
    }

    const jsize byte_count = env->GetStringUTFLength(value);
    if (byte_count < 0 || static_cast<size_t>(byte_count) > kMaxEntryBytes ||
        static_cast<size_t>(byte_count) > kMaxTotalBytes - *total_bytes) {
        throw_exception(
                env,
                "java/lang/IllegalArgumentException",
                "Terminal process input exceeds its byte limit");
        return nullptr;
    }
    const char *bytes = env->GetStringUTFChars(value, nullptr);
    if (bytes == nullptr) return nullptr;
    auto *copy = static_cast<char *>(malloc(static_cast<size_t>(byte_count) + 1));
    if (copy != nullptr) {
        memcpy(copy, bytes, static_cast<size_t>(byte_count));
        copy[byte_count] = '\0';
        *total_bytes += static_cast<size_t>(byte_count);
    }
    env->ReleaseStringUTFChars(value, bytes);
    if (copy == nullptr) {
        throw_exception(env, "java/lang/OutOfMemoryError", "Unable to copy terminal process input");
    }
    return copy;
}

bool copy_java_string_array(
        JNIEnv *env,
        jobjectArray values,
        std::vector<char *> *output,
        size_t *total_bytes) {
    if (values == nullptr) {
        throw_exception(env, "java/lang/IllegalArgumentException", "Terminal process vector is null");
        return false;
    }
    const jsize length = env->GetArrayLength(values);
    if (length < 0 || static_cast<size_t>(length) > kMaxEntries) {
        throw_exception(env, "java/lang/IllegalArgumentException", "Terminal process vector is too large");
        return false;
    }
    output->reserve(static_cast<size_t>(length));
    for (jsize index = 0; index < length; ++index) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(values, index));
        if (env->ExceptionCheck()) return false;
        char *copy = copy_java_string(env, value, total_bytes);
        env->DeleteLocalRef(value);
        if (copy == nullptr) return false;
        output->push_back(copy);
    }
    return true;
}

void free_string_vector(std::vector<char *> *values) {
    for (char *value: *values) free(value);
    values->clear();
}

int open_pty_master(char *slave_name, size_t slave_name_size) {
    const int master = posix_openpt(O_RDWR | O_CLOEXEC);
    if (master < 0) return -1;
    if (grantpt(master) != 0 || unlockpt(master) != 0 ||
        ptsname_r(master, slave_name, slave_name_size) != 0) {
        close(master);
        return -1;
    }
    return master;
}

bool create_pipe(int descriptors[2]) {
    return pipe2(descriptors, O_CLOEXEC) == 0;
}

void write_error_number(int fd, int error_number) {
    const auto *bytes = reinterpret_cast<const uint8_t *>(&error_number);
    size_t written = 0;
    while (written < sizeof(error_number)) {
        const ssize_t result = write(fd, bytes + written, sizeof(error_number) - written);
        if (result > 0) {
            written += static_cast<size_t>(result);
        } else if (result < 0 && errno == EINTR) {
            continue;
        } else {
            break;
        }
    }
}

// Returns 0 for EOF (successful exec), 1 for a child-reported errno, and -1 for read failure.
int read_error_number(int fd, int *error_number) {
    auto *bytes = reinterpret_cast<uint8_t *>(error_number);
    size_t received = 0;
    while (received < sizeof(*error_number)) {
        const ssize_t result = read(fd, bytes + received, sizeof(*error_number) - received);
        if (result > 0) {
            received += static_cast<size_t>(result);
        } else if (result == 0) {
            if (received == 0) return 0;
            errno = EPROTO;
            return -1;
        } else if (errno == EINTR) {
            continue;
        } else {
            return -1;
        }
    }
    return 1;
}

bool reset_signal_state() {
    sigset_t empty_mask = {};
    sigemptyset(&empty_mask);
    if (sigprocmask(SIG_SETMASK, &empty_mask, nullptr) != 0) return false;

    struct sigaction action = {};
    action.sa_handler = SIG_DFL;
    sigemptyset(&action.sa_mask);
    for (int signal_number = 1; signal_number < NSIG; ++signal_number) {
        if (signal_number == SIGKILL || signal_number == SIGSTOP) continue;
        if (sigaction(signal_number, &action, nullptr) != 0 && errno != EINVAL) return false;
    }
    return true;
}

[[noreturn]] void fail_child(int status_fd, int error_number) {
    write_error_number(status_fd, error_number);
    _exit(127);
}

void kill_and_reap_child(pid_t pid, bool isolated_process_group) {
    if (isolated_process_group && pid > 1) kill(-pid, SIGKILL);
    if (pid > 1) kill(pid, SIGKILL);
    int ignored_status = 0;
    while (waitpid(pid, &ignored_status, 0) < 0 && errno == EINTR) {}
}

int normalized_exit_status(int status) {
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return 127;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_termux_terminal_JNI_createSubprocess(
        JNIEnv *env,
        jclass,
        jstring cmd,
        jstring cwd,
        jobjectArray args,
        jobjectArray env_vars,
        jintArray process_id,
        jint rows,
        jint columns) {
    if (!initialize_process_id(env, process_id)) return -1;
    // Linux binds PR_SET_PDEATHSIG to the thread that calls fork(). The Android main thread has
    // process lifetime, while arbitrary coroutine or worker threads may retire independently.
    if (gettid() != getpid()) {
        throw_exception(
                env,
                "java/lang/IllegalStateException",
                "Workspace terminal must be launched from the Android main thread");
        return -1;
    }
    if (rows <= 0 || columns <= 0) {
        throw_exception(env, "java/lang/IllegalArgumentException", "Invalid terminal dimensions");
        return -1;
    }

    size_t total_bytes = 0;
    char *command = copy_java_string(env, cmd, &total_bytes);
    char *working_dir = command == nullptr ? nullptr : copy_java_string(env, cwd, &total_bytes);
    std::vector<char *> java_args;
    std::vector<char *> java_env;
    const bool copied = command != nullptr && working_dir != nullptr &&
        copy_java_string_array(env, args, &java_args, &total_bytes) &&
        copy_java_string_array(env, env_vars, &java_env, &total_bytes);
    if (!copied || command[0] == '\0' || working_dir[0] == '\0') {
        if (!env->ExceptionCheck()) {
            throw_exception(
                    env,
                    "java/lang/IllegalArgumentException",
                    "Terminal command and working directory must not be empty");
        }
        free(command);
        free(working_dir);
        free_string_vector(&java_args);
        free_string_vector(&java_env);
        return -1;
    }

    std::vector<char *> argv;
    argv.reserve(java_args.size() + 2);
    argv.push_back(command);
    argv.insert(argv.end(), java_args.begin(), java_args.end());
    argv.push_back(nullptr);
    java_env.push_back(nullptr);

    char slave_name[128] = {};
    const int master = open_pty_master(slave_name, sizeof(slave_name));
    if (master < 0) {
        const int open_error = errno;
        free(command);
        free(working_dir);
        free_string_vector(&java_args);
        free_string_vector(&java_env);
        throw_io_exception(env, "Unable to open terminal PTY", open_error);
        return -1;
    }
    int launch_status_pipe[2] = {-1, -1};
    if (!create_pipe(launch_status_pipe)) {
        const int pipe_error = errno;
        close(master);
        free(command);
        free(working_dir);
        free_string_vector(&java_args);
        free_string_vector(&java_env);
        throw_io_exception(env, "Unable to create terminal launch pipe", pipe_error);
        return -1;
    }

    const pid_t expected_parent = getpid();
    const pid_t pid = fork();
    if (pid < 0) {
        const int fork_error = errno;
        close(master);
        close_quietly(launch_status_pipe[0]);
        close_quietly(launch_status_pipe[1]);
        free(command);
        free(working_dir);
        free_string_vector(&java_args);
        free_string_vector(&java_env);
        throw_io_exception(env, "Unable to fork terminal process", fork_error);
        return -1;
    }

    if (pid == 0) {
        close_quietly(launch_status_pipe[0]);
        if (!reset_signal_state()) fail_child(launch_status_pipe[1], errno);
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0) fail_child(launch_status_pipe[1], errno);
        if (getppid() != expected_parent) fail_child(launch_status_pipe[1], ECHILD);
        if (setsid() < 0) fail_child(launch_status_pipe[1], errno);

        const int slave = open(slave_name, O_RDWR);
        if (slave < 0) fail_child(launch_status_pipe[1], errno);
        if (ioctl(slave, TIOCSCTTY, 0) != 0) fail_child(launch_status_pipe[1], errno);

        winsize size = {};
        size.ws_row = static_cast<unsigned short>(rows);
        size.ws_col = static_cast<unsigned short>(columns);
        if (ioctl(slave, TIOCSWINSZ, &size) != 0) fail_child(launch_status_pipe[1], errno);
        if (dup2(slave, STDIN_FILENO) < 0 ||
            dup2(slave, STDOUT_FILENO) < 0 ||
            dup2(slave, STDERR_FILENO) < 0) {
            fail_child(launch_status_pipe[1], errno);
        }
        if (slave > STDERR_FILENO) close(slave);
        close(master);
        if (chdir(working_dir) != 0) fail_child(launch_status_pipe[1], errno);

        execve(command, argv.data(), java_env.data());
        fail_child(launch_status_pipe[1], errno);
    }

    close_quietly(launch_status_pipe[1]);
    int command_error = 0;
    const int launch_result = read_error_number(launch_status_pipe[0], &command_error);
    const int launch_read_error = errno;
    close_quietly(launch_status_pipe[0]);
    if (launch_result != 0) {
        const int reported_error = launch_result > 0 ? command_error : launch_read_error;
        kill_and_reap_child(pid, true);
        close(master);
        free(command);
        free(working_dir);
        free_string_vector(&java_args);
        free_string_vector(&java_env);
        throw_io_exception(env, "Unable to launch terminal process", reported_error);
        return -1;
    }

    const jint published_pid = static_cast<jint>(pid);
    env->SetIntArrayRegion(process_id, 0, 1, &published_pid);
    if (env->ExceptionCheck()) {
        kill_and_reap_child(pid, true);
        close(master);
        free(command);
        free(working_dir);
        free_string_vector(&java_args);
        free_string_vector(&java_env);
        return -1;
    }

    free(command);
    free(working_dir);
    free_string_vector(&java_args);
    free_string_vector(&java_env);
    return master;
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_terminal_JNI_setPtyWindowSize(JNIEnv *, jclass, jint fd, jint rows, jint columns) {
    if (fd < 0 || rows <= 0 || columns <= 0) return;
    winsize size = {};
    size.ws_row = static_cast<unsigned short>(rows);
    size.ws_col = static_cast<unsigned short>(columns);
    ioctl(fd, TIOCSWINSZ, &size);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_termux_terminal_JNI_waitFor(JNIEnv *, jclass, jint pid) {
    if (pid <= 1) return 127;
    siginfo_t child_info = {};
    int observed = -1;
    do {
        observed = waitid(P_PID, static_cast<id_t>(pid), &child_info, WEXITED | WNOWAIT);
    } while (observed < 0 && errno == EINTR);
    if (observed != 0) return 127;

    // Keep the exited process as the group leader until every residual terminal job is killed.
    // This prevents process-group id reuse between observing the shell exit and cleanup.
    kill(-static_cast<pid_t>(pid), SIGKILL);
    int status = 0;
    pid_t reaped = -1;
    do {
        reaped = waitpid(static_cast<pid_t>(pid), &status, 0);
    } while (reaped < 0 && errno == EINTR);
    return reaped == pid ? normalized_exit_status(status) : 127;
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_terminal_JNI_close(JNIEnv *, jclass, jint fd) {
    close_quietly(fd);
}
