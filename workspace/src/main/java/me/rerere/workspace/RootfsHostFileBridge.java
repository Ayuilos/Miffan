package me.rerere.workspace;

final class RootfsHostFileBridge {
    static final int FILE_MISSING = 0;
    static final int FILE_REGULAR = 1;
    static final int FILE_SYMLINK = 2;

    static {
        System.loadLibrary("workspace");
    }

    private RootfsHostFileBridge() {}

    static native boolean directory(byte[] root, byte[] relativePath, boolean create);

    static native int fileKind(byte[] root, byte[] relativePath);

    static native byte[] readFile(byte[] root, byte[] relativePath, int maxBytes);

    static native void writeFile(
            byte[] root,
            byte[] relativePath,
            byte[] content,
            boolean replaceLeafSymlink);

    static native void chmodDirectory(byte[] root, byte[] relativePath, int mode);
}
