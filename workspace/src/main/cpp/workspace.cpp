#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include <string>
#include <vector>

namespace {

constexpr const char *kLauncherProcessName = "rk-ws-launcher";
constexpr size_t kMaxEntries = 4096;
constexpr size_t kMaxEntryBytes = 1024 * 1024;
constexpr size_t kMaxTotalBytes = 4 * 1024 * 1024;
constexpr jint kStillRunning = INT32_MIN;
volatile sig_atomic_t g_command_process_group = -1;

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

bool copy_bytes(JNIEnv *env, jbyteArray input, std::string *output, size_t max_bytes) {
    if (input == nullptr) {
        throw_exception(env, "java/lang/IllegalArgumentException", "Native process input is null");
        return false;
    }
    const jsize length = env->GetArrayLength(input);
    if (length < 0 || static_cast<size_t>(length) > max_bytes) {
        throw_exception(env, "java/lang/IllegalArgumentException", "Native process input is too large");
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
    if (output->find('\0') != std::string::npos) {
        throw_exception(env, "java/lang/IllegalArgumentException", "Native process input contains NUL");
        return false;
    }
    return true;
}

bool copy_vector(JNIEnv *env, jobjectArray input, std::vector<std::string> *output) {
    if (input == nullptr) {
        throw_exception(env, "java/lang/IllegalArgumentException", "Native process vector is null");
        return false;
    }
    const jsize count = env->GetArrayLength(input);
    if (count < 0 || static_cast<size_t>(count) > kMaxEntries) {
        throw_exception(env, "java/lang/IllegalArgumentException", "Native process vector is too large");
        return false;
    }
    output->reserve(static_cast<size_t>(count));
    size_t total_bytes = 0;
    for (jsize index = 0; index < count; ++index) {
        auto bytes = static_cast<jbyteArray>(env->GetObjectArrayElement(input, index));
        if (env->ExceptionCheck()) return false;
        std::string value;
        const bool copied = copy_bytes(env, bytes, &value, kMaxEntryBytes);
        env->DeleteLocalRef(bytes);
        if (!copied) return false;
        if (value.size() > kMaxTotalBytes - total_bytes) {
            throw_exception(
                    env,
                    "java/lang/IllegalArgumentException",
                    "Native process vector exceeds its byte limit");
            return false;
        }
        total_bytes += value.size();
        output->push_back(std::move(value));
    }
    return true;
}

std::vector<char *> mutable_pointers(std::vector<std::string> *values) {
    std::vector<char *> pointers;
    pointers.reserve(values->size() + 1);
    for (std::string &value: *values) {
        pointers.push_back(const_cast<char *>(value.c_str()));
    }
    pointers.push_back(nullptr);
    return pointers;
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

bool write_start_token(int fd) {
    const uint8_t token = 1;
    ssize_t result = -1;
    do {
        result = write(fd, &token, sizeof(token));
    } while (result < 0 && errno == EINTR);
    return result == sizeof(token);
}

bool read_start_token(int fd) {
    uint8_t token = 0;
    ssize_t result = -1;
    do {
        result = read(fd, &token, sizeof(token));
    } while (result < 0 && errno == EINTR);
    if (result == 0) errno = EPIPE;
    return result == sizeof(token) && token == 1;
}

[[noreturn]] void fail_launcher(int status_fd, int error_number) {
    write_error_number(status_fd, error_number);
    _exit(127);
}

void parent_death_handler(int signal_number) {
    const pid_t command_process_group = g_command_process_group;
    if (command_process_group > 1) kill(-command_process_group, SIGKILL);
    _exit(128 + signal_number);
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

bool install_parent_death_handler() {
    if (!reset_signal_state()) return false;

    struct sigaction action = {};
    action.sa_handler = parent_death_handler;
    sigemptyset(&action.sa_mask);
    action.sa_flags = SA_RESTART;
    return sigaction(SIGUSR1, &action, nullptr) == 0;
}

int normalized_exit_status(int status) {
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return 127;
}

[[noreturn]] void run_launcher(
        pid_t expected_parent,
        int stdin_pipe[2],
        int stdout_pipe[2],
        int stderr_pipe[2],
        int launch_status_pipe[2],
        std::vector<char *> *argv,
        std::vector<char *> *environment,
        const char *working_directory) {
    close_quietly(launch_status_pipe[0]);
    close_quietly(stdin_pipe[1]);
    close_quietly(stdout_pipe[0]);
    close_quietly(stderr_pipe[0]);

    if (!install_parent_death_handler()) fail_launcher(launch_status_pipe[1], errno);
    if (prctl(PR_SET_PDEATHSIG, SIGUSR1) != 0) fail_launcher(launch_status_pipe[1], errno);
    if (getppid() != expected_parent) fail_launcher(launch_status_pipe[1], ECHILD);
    if (setsid() < 0) fail_launcher(launch_status_pipe[1], errno);
    if (prctl(PR_SET_NAME, kLauncherProcessName, 0, 0, 0) != 0) {
        fail_launcher(launch_status_pipe[1], errno);
    }

    int exec_status_pipe[2] = {-1, -1};
    if (!create_pipe(exec_status_pipe)) fail_launcher(launch_status_pipe[1], errno);
    int start_gate_pipe[2] = {-1, -1};
    if (!create_pipe(start_gate_pipe)) fail_launcher(launch_status_pipe[1], errno);

    const pid_t launcher_pid = getpid();
    const pid_t command_pid = fork();
    if (command_pid < 0) fail_launcher(launch_status_pipe[1], errno);

    if (command_pid == 0) {
        close_quietly(exec_status_pipe[0]);
        close_quietly(start_gate_pipe[1]);
        close_quietly(launch_status_pipe[1]);
        if (!reset_signal_state()) {
            write_error_number(exec_status_pipe[1], errno);
            _exit(127);
        }
        if (setpgid(0, 0) != 0) {
            write_error_number(exec_status_pipe[1], errno);
            _exit(127);
        }
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0) {
            write_error_number(exec_status_pipe[1], errno);
            _exit(127);
        }
        if (getppid() != launcher_pid) {
            write_error_number(exec_status_pipe[1], ECHILD);
            _exit(127);
        }
        if (!read_start_token(start_gate_pipe[0])) {
            write_error_number(exec_status_pipe[1], errno);
            _exit(127);
        }
        close_quietly(start_gate_pipe[0]);
        if (dup2(stdin_pipe[0], STDIN_FILENO) < 0 ||
            dup2(stdout_pipe[1], STDOUT_FILENO) < 0 ||
            dup2(stderr_pipe[1], STDERR_FILENO) < 0) {
            write_error_number(exec_status_pipe[1], errno);
            _exit(127);
        }
        close_quietly(stdin_pipe[0]);
        close_quietly(stdout_pipe[1]);
        close_quietly(stderr_pipe[1]);
        if (chdir(working_directory) != 0) {
            write_error_number(exec_status_pipe[1], errno);
            _exit(127);
        }
        execve((*argv)[0], argv->data(), environment->data());
        write_error_number(exec_status_pipe[1], errno);
        _exit(127);
    }
    g_command_process_group = command_pid;
    close_quietly(start_gate_pipe[0]);
    if (!write_start_token(start_gate_pipe[1])) fail_launcher(launch_status_pipe[1], errno);
    close_quietly(start_gate_pipe[1]);

    close_quietly(exec_status_pipe[1]);
    close_quietly(stdin_pipe[0]);
    close_quietly(stdout_pipe[1]);
    close_quietly(stderr_pipe[1]);

    int command_error = 0;
    const int exec_result = read_error_number(exec_status_pipe[0], &command_error);
    const int exec_read_error = errno;
    close_quietly(exec_status_pipe[0]);
    if (exec_result != 0) {
        write_error_number(
                launch_status_pipe[1],
                exec_result > 0 ? command_error : exec_read_error);
        kill(command_pid, SIGKILL);
        int ignored_status = 0;
        while (waitpid(command_pid, &ignored_status, 0) < 0 && errno == EINTR) {}
        _exit(127);
    }

    // A negative value distinguishes the successfully exec'd command PID from a positive errno.
    write_error_number(launch_status_pipe[1], -command_pid);
    close_quietly(launch_status_pipe[1]);
    siginfo_t command_info = {};
    int observed = -1;
    do {
        observed = waitid(P_PID, command_pid, &command_info, WEXITED | WNOWAIT);
    } while (observed < 0 && errno == EINTR);
    // The main command may have left background descendants in its dedicated process group.
    // Keep its zombie as the group leader until the complete job is killed, so the group id cannot
    // be recycled between observing the main exit and signaling residual descendants.
    kill(-command_pid, SIGKILL);
    int command_status = 0;
    pid_t reaped = -1;
    do {
        reaped = waitpid(command_pid, &command_status, 0);
    } while (reaped < 0 && errno == EINTR);
    _exit(observed == 0 && reaped == command_pid ? normalized_exit_status(command_status) : 127);
}

void close_parent_pipes(int stdin_pipe[2], int stdout_pipe[2], int stderr_pipe[2]) {
    close_quietly(stdin_pipe[1]);
    close_quietly(stdout_pipe[0]);
    close_quietly(stderr_pipe[0]);
}

void kill_and_reap_launcher(pid_t launcher_pid, pid_t command_process_group = -1) {
    if (command_process_group > 1) kill(-command_process_group, SIGKILL);
    kill(launcher_pid, SIGKILL);
    int ignored_status = 0;
    while (waitpid(launcher_pid, &ignored_status, 0) < 0 && errno == EINTR) {}
}

} // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_me_rerere_workspace_WorkspaceNativeBridge_spawn(
        JNIEnv *env,
        jclass,
        jobjectArray command_bytes,
        jobjectArray environment_bytes,
        jbyteArray working_directory_bytes) {
    std::vector<std::string> command;
    std::vector<std::string> environment;
    std::string working_directory;
    if (!copy_vector(env, command_bytes, &command) ||
        !copy_vector(env, environment_bytes, &environment) ||
        !copy_bytes(env, working_directory_bytes, &working_directory, kMaxEntryBytes)) {
        return nullptr;
    }
    if (command.empty() || command.front().empty() || working_directory.empty()) {
        throw_exception(
                env,
                "java/lang/IllegalArgumentException",
                "Command and working directory must not be empty");
        return nullptr;
    }

    std::vector<char *> argv = mutable_pointers(&command);
    std::vector<char *> environment_pointers = mutable_pointers(&environment);
    int stdin_pipe[2] = {-1, -1};
    int stdout_pipe[2] = {-1, -1};
    int stderr_pipe[2] = {-1, -1};
    int launch_status_pipe[2] = {-1, -1};
    if (!create_pipe(stdin_pipe) ||
        !create_pipe(stdout_pipe) ||
        !create_pipe(stderr_pipe) ||
        !create_pipe(launch_status_pipe)) {
        const int pipe_error = errno;
        close_parent_pipes(stdin_pipe, stdout_pipe, stderr_pipe);
        close_quietly(stdin_pipe[0]);
        close_quietly(stdout_pipe[1]);
        close_quietly(stderr_pipe[1]);
        close_quietly(launch_status_pipe[0]);
        close_quietly(launch_status_pipe[1]);
        throw_io_exception(env, "Unable to create workspace process pipes", pipe_error);
        return nullptr;
    }

    const pid_t expected_parent = getpid();
    const pid_t launcher_pid = fork();
    if (launcher_pid < 0) {
        const int fork_error = errno;
        close_parent_pipes(stdin_pipe, stdout_pipe, stderr_pipe);
        close_quietly(stdin_pipe[0]);
        close_quietly(stdout_pipe[1]);
        close_quietly(stderr_pipe[1]);
        close_quietly(launch_status_pipe[0]);
        close_quietly(launch_status_pipe[1]);
        throw_io_exception(env, "Unable to fork workspace process launcher", fork_error);
        return nullptr;
    }
    if (launcher_pid == 0) {
        run_launcher(
                expected_parent,
                stdin_pipe,
                stdout_pipe,
                stderr_pipe,
                launch_status_pipe,
                &argv,
                &environment_pointers,
                working_directory.c_str());
    }

    close_quietly(launch_status_pipe[1]);
    close_quietly(stdin_pipe[0]);
    close_quietly(stdout_pipe[1]);
    close_quietly(stderr_pipe[1]);
    int launch_value = 0;
    const int launch_result = read_error_number(launch_status_pipe[0], &launch_value);
    const int launch_read_error = errno;
    close_quietly(launch_status_pipe[0]);
    if (launch_result != 1 || launch_value >= -1) {
        const int reported_error =
                launch_result == 1 && launch_value > 0
                ? launch_value
                : (launch_result < 0 ? launch_read_error : EPROTO);
        kill_and_reap_launcher(launcher_pid);
        close_parent_pipes(stdin_pipe, stdout_pipe, stderr_pipe);
        throw_io_exception(
                env,
                "Unable to launch workspace process",
                reported_error);
        return nullptr;
    }
    const pid_t command_pid = -launch_value;

    const jint result_values[] = {
            static_cast<jint>(launcher_pid),
            static_cast<jint>(command_pid),
            static_cast<jint>(stdin_pipe[1]),
            static_cast<jint>(stdout_pipe[0]),
            static_cast<jint>(stderr_pipe[0]),
    };
    jintArray result = env->NewIntArray(5);
    if (result == nullptr) {
        kill_and_reap_launcher(launcher_pid, command_pid);
        close_parent_pipes(stdin_pipe, stdout_pipe, stderr_pipe);
        return nullptr;
    }
    env->SetIntArrayRegion(result, 0, 5, result_values);
    if (env->ExceptionCheck()) {
        kill_and_reap_launcher(launcher_pid, command_pid);
        close_parent_pipes(stdin_pipe, stdout_pipe, stderr_pipe);
        return nullptr;
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_me_rerere_workspace_WorkspaceNativeBridge_waitForProcess(
        JNIEnv *env,
        jclass,
        jint process_id) {
    int status = 0;
    pid_t result = -1;
    do {
        result = waitpid(static_cast<pid_t>(process_id), &status, WNOHANG);
    } while (result < 0 && errno == EINTR);
    if (result == 0) return kStillRunning;
    if (result == process_id) return normalized_exit_status(status);
    throw_io_exception(env, "Unable to wait for workspace process", errno);
    return kStillRunning;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_rerere_workspace_WorkspaceNativeBridge_signalProcessGroup(
        JNIEnv *,
        jclass,
        jint process_id,
        jint signal_number) {
    if (process_id <= 1 || signal_number <= 0) return JNI_FALSE;
    if (kill(-static_cast<pid_t>(process_id), signal_number) == 0) return JNI_TRUE;
    return errno == ESRCH ? JNI_TRUE : JNI_FALSE;
}
