package com.example.dbtoolbox.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EncryptedJsonFileStoreTest {
    @TempDir Path root;
    final ObjectMapper mapper=new ObjectMapper();
    Path file(){return root.resolve("config/connections-v2.enc");}
    Path key(){return root.resolve("config/master.key");}
    EncryptedJsonFileStore<Map<String,String>> store(){return new EncryptedJsonFileStore<>(file(),key(),mapper,new TypeReference<Map<String,String>>(){},HashMap::new);}
    Map<String,String> value(){return Collections.singletonMap("password","synthetic-only");}
    void oldFile()throws Exception{
        Files.createDirectories(file().getParent());byte[] key=new byte[16];new java.security.SecureRandom().nextBytes(key);
        Files.write(key(),Base64.getEncoder().encode(key));Cipher c=Cipher.getInstance("AES/ECB/PKCS5Padding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"));Files.write(file(),c.doFinal(mapper.writeValueAsBytes(value())));
    }
    @Test void randomIvRoundTripAndAuthenticationFailurePreserveFile()throws Exception{
        store().write(value());byte[] first=Files.readAllBytes(file());store().write(value());byte[] second=Files.readAllBytes(file());
        assertFalse(Arrays.equals(first,second));assertEquals(value(),store().read());assertEquals("DBTX",new String(second,0,4,"UTF-8"));
        second[second.length-1]^=1;Files.write(file(),second);assertThrows(AppException.class,()->store().read());assertArrayEquals(second,Files.readAllBytes(file()));
        second[4]=99;Files.write(file(),second);assertThrows(AppException.class,()->store().read());
    }
    @Test void legacyUpgradeBacksUpExactCiphertextAndKeyAndRunsOnce()throws Exception{
        oldFile();byte[] original=Files.readAllBytes(file()),keyBytes=Files.readAllBytes(key());
        assertEquals(value(),store().read());assertArrayEquals(original,Files.readAllBytes(file()));store().upgradeEncryption();
        Path backup=file().resolveSibling("connections-v2.enc.legacy-backup");
        assertArrayEquals(original,Files.readAllBytes(backup.resolve("connections-v2.enc")));assertArrayEquals(keyBytes,Files.readAllBytes(backup.resolve("master.key")));
        byte[] migrated=Files.readAllBytes(file());assertFalse(Arrays.equals(original,migrated));store().upgradeEncryption();assertArrayEquals(migrated,Files.readAllBytes(file()));assertEquals(value(),store().read());
    }
    @Test void missingKeyNeverRegeneratesOnReadOrOverwrite()throws Exception{
        store().write(value());byte[] before=Files.readAllBytes(file());Files.delete(key());
        assertThrows(AppException.class,()->store().read());assertThrows(AppException.class,()->store().write(value()));assertFalse(Files.exists(key()));assertArrayEquals(before,Files.readAllBytes(file()));
    }
    @Test void insecureExistingModesAndBackupsBecomeOwnerOnly()throws Exception{
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"));
        oldFile();store().read();store().upgradeEncryption();
        try(java.util.stream.Stream<Path> paths=Files.walk(file().getParent())){for(Path p:(Iterable<Path>)paths::iterator)assertEquals(Files.isDirectory(p)?"rwx------":"rw-------",PosixFilePermissions.toString(Files.getPosixFilePermissions(p)));}
    }
    @Test void symlinkAndMismatchedBackupFailWithoutOverwritingTargets()throws Exception{
        oldFile();Path backup=file().resolveSibling("connections-v2.enc.legacy-backup");PrivateFiles.directory(backup);PrivateFiles.writeNew(backup.resolve("connections-v2.enc"),new byte[]{7});byte[] original=Files.readAllBytes(file());
        assertThrows(AppException.class,()->store().upgradeEncryption());assertArrayEquals(original,Files.readAllBytes(file()));assertArrayEquals(new byte[]{7},Files.readAllBytes(backup.resolve("connections-v2.enc")));
        Path target=root.resolve("untouched");Files.write(target,new byte[]{9});Files.delete(file());Files.createSymbolicLink(file(),target);
        assertThrows(AppException.class,()->store().read());assertThrows(AppException.class,()->store().write(value()));assertArrayEquals(new byte[]{9},Files.readAllBytes(target));
    }
}
