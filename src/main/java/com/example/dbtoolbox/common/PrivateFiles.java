package com.example.dbtoolbox.common;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Owner-only storage. Unsupported permission models fail before secret bytes are written. */
public final class PrivateFiles {
    private PrivateFiles() { }

    public static void directory(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            Path parent = path.toAbsolutePath().getParent();
            if (!Files.exists(parent)) directory(parent);
            Files.createDirectory(path, attributes(parent, true));
        }
        protect(path, true);
    }

    public static void protect(Path path, boolean directory) throws IOException {
        if (Files.isSymbolicLink(path) || (directory ? !Files.isDirectory(path) : !Files.isRegularFile(path)))
            throw new IOException("本地配置路径不是普通文件或目录");
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            Set<PosixFilePermission> permissions = PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------");
            posix.setPermissions(permissions);
            if (!posix.readAttributes().permissions().equals(permissions)) throw new IOException("无法限制本地配置权限");
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("文件系统不支持所有者专用权限");
        protectAcl(acl, directory);
    }

    static void protectAcl(AclFileAttributeView acl, boolean directory) throws IOException {
        List<AclEntry> entries = ownerAcl(acl.getOwner(), directory);
        acl.setAcl(entries);
        if (!acl.getAcl().equals(entries)) throw new IOException("无法限制本地配置 ACL");
    }

    private static List<AclEntry> ownerAcl(UserPrincipal owner, boolean directory) {
        AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        return Collections.singletonList(entry.build());
    }

    private static FileAttribute<?>[] attributes(Path parent, boolean directory) throws IOException {
        if (Files.getFileAttributeView(parent, PosixFileAttributeView.class) != null)
            return new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"))};
        if (Files.getFileAttributeView(parent, AclFileAttributeView.class) == null)
            throw new IOException("文件系统不支持所有者专用权限");
        // Bootstrap creation only. Re-read the new file's actual owner and verify its ACL before data.
        // The parent's owner is not necessarily the creating account (e.g. a system-owned directory).
        final UserPrincipal owner = parent.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        return new FileAttribute<?>[]{new FileAttribute<List<AclEntry>>() {
            public String name() { return "acl:acl"; }
            public List<AclEntry> value() { return ownerAcl(owner, directory); }
        }};
    }

    public static void writeNew(Path path, byte[] bytes) throws IOException {
        directory(path.getParent());
        // CREATE_NEW and restrictive creation attributes avoid a permissive-file window.
        try (java.nio.channels.SeekableByteChannel out = Files.newByteChannel(path,
                EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), attributes(path.getParent(), false))) {
            // Fail before writing any secret bytes if the actual owner's ACL cannot be enforced.
            protect(path, false);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) out.write(buffer);
        }
        protect(path, false);
    }

    public static void replace(Path path, byte[] bytes) throws IOException {
        directory(path.getParent());
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) protect(path, false);
        Path tmp = path.resolveSibling("." + path.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            writeNew(tmp, bytes);
            try { Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(tmp); }
    }

    public static void backup(Path source, Path target) throws IOException {
        protect(source, false);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) writeNew(target, Files.readAllBytes(source));
        else {
            protect(target, false);
            if (!Arrays.equals(Files.readAllBytes(source), Files.readAllBytes(target)))
                throw new IOException("已有迁移备份与原文件不一致，未覆盖备份");
        }
    }
}
