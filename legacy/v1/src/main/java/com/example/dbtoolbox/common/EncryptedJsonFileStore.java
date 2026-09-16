package com.example.dbtoolbox.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.function.Supplier;

public class EncryptedJsonFileStore<T> {

    private static final String AES = "AES";

    private final Path file;
    private final Path keyFile;
    private final ObjectMapper objectMapper;
    private final TypeReference<T> typeReference;
    private final Supplier<T> emptySupplier;

    public EncryptedJsonFileStore(Path file,
                                  Path keyFile,
                                  ObjectMapper objectMapper,
                                  TypeReference<T> typeReference,
                                  Supplier<T> emptySupplier) {
        this.file = file;
        this.keyFile = keyFile;
        this.objectMapper = objectMapper;
        this.typeReference = typeReference;
        this.emptySupplier = emptySupplier;
    }

    public synchronized T read() {
        try {
            if (!Files.exists(file) || Files.size(file) == 0) {
                return emptySupplier.get();
            }
            byte[] encrypted = Files.readAllBytes(file);
            byte[] json = cipher(Cipher.DECRYPT_MODE).doFinal(encrypted);
            return objectMapper.readValue(json, typeReference);
        } catch (Exception ex) {
            throw new AppException("读取本地配置失败: " + ex.getMessage());
        }
    }

    public synchronized void write(T value) {
        try {
            Files.createDirectories(file.getParent());
            byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE).doFinal(json);
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            Files.write(tmp, encrypted);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ex) {
            throw new AppException("写入本地配置失败: " + ex.getMessage());
        }
    }

    private Cipher cipher(int mode) throws Exception {
        Cipher cipher = Cipher.getInstance(AES);
        cipher.init(mode, new SecretKeySpec(loadKey(), AES));
        return cipher;
    }

    private byte[] loadKey() throws IOException {
        Files.createDirectories(keyFile.getParent());
        if (Files.exists(keyFile)) {
            String encoded = new String(Files.readAllBytes(keyFile), StandardCharsets.UTF_8).trim();
            return Base64.getDecoder().decode(encoded);
        }
        byte[] key = new byte[16];
        new SecureRandom().nextBytes(key);
        Files.write(keyFile, Base64.getEncoder().encode(key));
        return key;
    }
}
