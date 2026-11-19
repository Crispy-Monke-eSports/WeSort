package com.porter.wesort.data;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataDirectoryTest {
    @TempDir
    Path temp;

    @Test
    void newClientAndManagerIgnoreCorruptReadOnlyLegacySiblings() throws Exception {
        Path meteor = Files.createDirectory(temp.resolve("meteor-client"));
        Path old = Files.createDirectory(meteor.resolve("qwerte"));
        byte[] corrupt = {0, -1, 3, 7};
        Path file = Files.write(old.resolve("kits.nbt"), corrupt);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"));
        Files.setPosixFilePermissions(old, PosixFilePermissions.fromString("r-x------"));
        try {
            for (String child : new String[] {"stashes", "kits", "local-index", "local-orders"}) {
                Path path = DataDirectory.clientFolder(meteor, child);
                assertTrue(path.startsWith(meteor.resolve("wesort")));
                assertFalse(Files.exists(path), "nothing imported from Qwerte");
            }
            Path manager = DataDirectory.open(temp.resolve("wesort-manager-data"), "manager");
            try (var paths = Files.list(manager)) {
                assertEquals(1, paths.count());
            }
            assertArrayEquals(corrupt, Files.readAllBytes(file));
            assertEquals(PosixFilePermissions.fromString("r--------"), Files.getPosixFilePermissions(file));
        } finally {
            Files.setPosixFilePermissions(old, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void explicitLegacyPathsAreRefusedBeforeAnyFilesAreChanged() throws Exception {
        for (String name : new String[] {"qwerte", "Qwerte", "qwerte-addon", "stash-manager-data"}) {
            Path old = Files.createDirectory(temp.resolve(name));
            Path file = Files.writeString(old.resolve("config.properties"), "broken secret data");
            assertThrows(IOException.class, () -> DataDirectory.open(old, "manager"));
            assertThrows(IOException.class, () -> DataDirectory.open(old.resolve("nested"), "client"));
            assertEquals("broken secret data", Files.readString(file));
            assertFalse(Files.exists(old.resolve(DataDirectory.MARKER)));
            assertFalse(Files.exists(old.resolve("nested")));
        }
    }

    @Test
    void unknownNonemptyDirectoriesAndWrongSchemaAreNeverAdopted() throws Exception {
        Path unknown = Files.createDirectory(temp.resolve("unknown"));
        Path data = Files.writeString(unknown.resolve("users.json"), "corrupt");
        assertThrows(IOException.class, () -> DataDirectory.open(unknown, "manager"));
        assertEquals("corrupt", Files.readString(data));
        assertFalse(Files.exists(unknown.resolve(DataDirectory.MARKER)));
        Path root = DataDirectory.open(temp.resolve("new"), "manager");
        assertEquals(root, DataDirectory.open(root, "manager"));
        assertThrows(IOException.class, () -> DataDirectory.open(root, "client"));
        Files.writeString(root.resolve(DataDirectory.MARKER), "wesort-manager:999\n");
        assertThrows(IOException.class, () -> DataDirectory.open(root, "manager"));
        assertEquals("wesort-manager:999\n", Files.readString(root.resolve(DataDirectory.MARKER)));
    }

    @Test
    void symlinkRootsAndParentsCannotAliasAnOldInstallation() throws Exception {
        Path old = Files.createDirectory(temp.resolve("qwerte"));
        Path secret = Files.writeString(old.resolve("keys"), "unchanged");
        Path alias = Files.createSymbolicLink(temp.resolve("wesort"), old);
        assertThrows(IOException.class, () -> DataDirectory.open(alias, "manager"));
        assertThrows(IOException.class, () -> DataDirectory.open(alias.resolve("child"), "client"));
        assertEquals("unchanged", Files.readString(secret));
        assertFalse(Files.exists(old.resolve(DataDirectory.MARKER)));
        assertFalse(Files.exists(old.resolve("child")));
    }

    @Test
    void symlinkMarkersAndDescendantsAreRefusedBeforeDataIsOpened() throws Exception {
        Path old = Files.createDirectory(temp.resolve("qwerte"));
        Path secret = Files.writeString(old.resolve("config.properties"), "sentinel");
        Path root = DataDirectory.open(temp.resolve("wesort"), "manager");
        Path link = Files.createSymbolicLink(root.resolve("icons"), old);
        assertThrows(IOException.class, () -> DataDirectory.open(root, "manager"));
        Files.delete(link);
        Files.delete(root.resolve(DataDirectory.MARKER));
        Files.createSymbolicLink(root.resolve(DataDirectory.MARKER), secret);
        assertThrows(IOException.class, () -> DataDirectory.open(root, "manager"));
        assertEquals("sentinel", Files.readString(secret));
    }

    @Test
    void clientSubfolderCannotEscapeItsNamespace() {
        assertThrows(IllegalArgumentException.class, () -> DataDirectory.clientFolder(temp, "../qwerte"));
    }

    @Test
    void healthRootCheckNeverInitializesAMissingInstallation() throws Exception {
        Path missing = temp.resolve("not-started");
        assertThrows(IOException.class, () -> DataDirectory.existingRoot(missing, "manager"));
        assertFalse(Files.exists(missing));
        Path root = DataDirectory.open(missing, "manager");
        assertEquals(root, DataDirectory.existingRoot(root, "manager"));
    }
}
