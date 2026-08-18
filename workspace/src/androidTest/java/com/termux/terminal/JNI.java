package com.termux.terminal;

/** Test-only declaration of the Termux JNI contract overridden by this module. */
public final class JNI {
    static {
        System.loadLibrary("termux");
    }

    private JNI() {}

    public static native int createSubprocess(
            String command,
            String workingDirectory,
            String[] arguments,
            String[] environment,
            int[] processId,
            int rows,
            int columns);

    public static native int waitFor(int processId);

    public static native void close(int fileDescriptor);
}
