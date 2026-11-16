package com.porter.wesortmanager;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** One process owns the installation, even when a second container mounts the same volume. */
final class ManagerLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private ManagerLock(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }

    static ManagerLock acquire(Path data) throws IOException {
        FileChannel channel = FileChannel.open(data.resolve(".manager.lock"), StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        AtomicFiles.ownerOnly(data.resolve(".manager.lock"));
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Another WeSort Manager owns this data volume");
            return new ManagerLock(channel, lock);
        } catch (IOException | OverlappingFileLockException e) {
            channel.close();
            throw new IOException("Cannot acquire exclusive WeSort installation lock", e);
        }
    }

    @Override public void close() throws IOException { try { lock.release(); } finally { channel.close(); } }
}
