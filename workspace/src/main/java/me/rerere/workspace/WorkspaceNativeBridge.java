package me.rerere.workspace;

final class WorkspaceNativeBridge {
    static {
        System.loadLibrary("workspace");
    }

    private WorkspaceNativeBridge() {}

    static native int[] spawn(
            byte[][] command,
            byte[][] environment,
            byte[] workingDirectory);

    static native int waitForProcess(int processId);

    static native boolean signalProcessGroup(int processId, int signal);
}
