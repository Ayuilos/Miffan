package me.rerere.workspace;

final class RootfsHostFileBridge {
    static final int FILE_MISSING = 0;
    static final int FILE_REGULAR = 1;
    static final int FILE_SYMLINK = 2;
    static final int ENTRY_MISSING = 0;
    static final int ENTRY_REGULAR = 1;
    static final int ENTRY_DIRECTORY = 2;
    static final int ENTRY_SYMLINK = 3;
    static final int ENTRY_OTHER = 4;

    static {
        System.loadLibrary("workspace");
    }

    private RootfsHostFileBridge() {}

    static native boolean directory(byte[] root, byte[] relativePath, boolean create);

    static native int fileKind(byte[] root, byte[] relativePath);

    static native byte[] readFile(
            byte[] root,
            byte[] relativePath,
            int maxBytes,
            boolean rejectHardLinks);

    static native long fileSize(byte[] root, byte[] relativePath, boolean rejectHardLinks);

    static native void writeFile(
            byte[] root,
            byte[] relativePath,
            byte[] content,
            boolean replaceLeafSymlink,
            boolean overwrite);

    static native void chmodDirectory(byte[] root, byte[] relativePath, int mode);

    static native boolean deleteTree(byte[] absolutePath);

    static native boolean renameDirectoryNoReplace(byte[] source, byte[] target);

    static native int openFileCreate(byte[] root, byte[] relativePath);

    static native boolean deleteRelative(byte[] root, byte[] relativePath, boolean recursive);

    static native int entryKind(byte[] root, byte[] relativePath);

    static native boolean renameEntryNoReplace(
            byte[] root,
            byte[] sourceRelativePath,
            byte[] targetRelativePath);
}
