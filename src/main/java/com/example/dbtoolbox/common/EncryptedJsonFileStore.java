package com.example.dbtoolbox.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.Supplier;

public class EncryptedJsonFileStore<T> {
    private static final byte[] HEADER = {'D', 'B', 'T', 'X', 1};
    private final Path file, keyFile;
    private final ObjectMapper objectMapper;
    private final TypeReference<T> typeReference;
    private final Supplier<T> emptySupplier;

    public EncryptedJsonFileStore(Path file, Path keyFile, ObjectMapper objectMapper,
                                  TypeReference<T> typeReference, Supplier<T> emptySupplier) {
        this.file=file;this.keyFile=keyFile;this.objectMapper=objectMapper;
        this.typeReference=typeReference;this.emptySupplier=emptySupplier;
    }

    public synchronized T read() {
        try {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return emptySupplier.get();
            PrivateFiles.directory(file.getParent());
            PrivateFiles.protect(file, false);
            return decode(Files.readAllBytes(file));
        } catch (Exception ex) {
            throw new AppException("读取本地配置失败：文件损坏、密钥不匹配或权限不足；原文件已保留");
        }
    }

    private T decode(byte[] bytes) throws Exception {
        boolean modern = isModern(bytes);
        Cipher cipher;
        if (modern) {
            if (bytes.length < HEADER.length+12+16 || bytes[4] != HEADER[4]) throw new IOException("配置格式无效");
            cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(loadKey(false), "AES"), new GCMParameterSpec(128, Arrays.copyOfRange(bytes,5,17)));
            cipher.updateAAD(HEADER);
            bytes=Arrays.copyOfRange(bytes,17,bytes.length);
        } else {
            if (bytes.length==0 || bytes.length%16!=0) throw new IOException("旧配置格式无效");
            cipher=Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(loadKey(false),"AES"));
        }
        // A GCM authentication/format failure never retries ECB.
        return objectMapper.readValue(cipher.doFinal(bytes), typeReference);
    }

    private static boolean isModern(byte[] bytes) {
        return bytes.length>=4 && bytes[0]=='D' && bytes[1]=='B' && bytes[2]=='T' && bytes[3]=='X';
    }

    /** Call only after the owning service validates the catalog. V1 source files remain untouched. */
    public synchronized void upgradeEncryption() {
        try {
            if (!Files.exists(file) || isModern(Files.readAllBytes(file))) return;
            T value=read();
            Path backup=file.resolveSibling(file.getFileName()+".legacy-backup");
            PrivateFiles.directory(backup);
            PrivateFiles.backup(file,backup.resolve(file.getFileName()));
            PrivateFiles.backup(keyFile,backup.resolve("master.key"));
            write(value);
        } catch (Exception ex) { throw new AppException("升级本地配置加密失败，原配置或备份已保留；请检查权限与备份"); }
    }

    public synchronized void write(T value) {
        try {
            byte[] json=objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
            byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(loadKey(!Files.exists(file)),"AES"),new GCMParameterSpec(128,iv));
            cipher.updateAAD(HEADER);
            byte[] ciphertext=cipher.doFinal(json), bytes=new byte[17+ciphertext.length];
            System.arraycopy(HEADER,0,bytes,0,HEADER.length);System.arraycopy(iv,0,bytes,5,12);System.arraycopy(ciphertext,0,bytes,17,ciphertext.length);
            PrivateFiles.replace(file,bytes);
        } catch (Exception ex) { throw new AppException("写入本地配置失败：请检查密钥、目录权限和磁盘空间；未清空配置"); }
    }

    private byte[] loadKey(boolean create) throws IOException {
        PrivateFiles.directory(keyFile.getParent());
        if (!Files.exists(keyFile, LinkOption.NOFOLLOW_LINKS)) {
            if (!create) throw new IOException("密钥丢失");
            byte[] key=new byte[16];new SecureRandom().nextBytes(key);
            try { PrivateFiles.writeNew(keyFile,Base64.getEncoder().encode(key)); }
            catch (FileAlreadyExistsException ignored) { /* Another store created the shared key first. */ }
        }
        PrivateFiles.protect(keyFile,false);
        byte[] key=Base64.getDecoder().decode(new String(Files.readAllBytes(keyFile),StandardCharsets.UTF_8).trim());
        if(key.length!=16)throw new IOException("密钥格式无效");
        return key;
    }
}
