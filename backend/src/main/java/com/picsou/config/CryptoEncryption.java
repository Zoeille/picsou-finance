package com.picsou.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class CryptoEncryption {

    private static final Logger log = LoggerFactory.getLogger(CryptoEncryption.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public CryptoEncryption(@Value("${app.crypto.encryption-key:}") String base64Key) {
        if (base64Key != null && !base64Key.isBlank()) {
            this.key = new SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES");
        } else {
            this.key = null;
            log.warn("CRYPTO_ENCRYPTION_KEY not set — API secrets will be stored in plain text");
        }
    }

    public String encrypt(String plainText) {
        if (plainText == null || key == null) return plainText;
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes());
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception ex) {
            throw new RuntimeException("Encryption failed", ex);
        }
    }

    public String decrypt(String cipherText) {
        if (cipherText == null || key == null) return cipherText;
        try {
            byte[] combined = Base64.getDecoder().decode(cipherText);
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, iv.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] decrypted = cipher.doFinal(combined, iv.length, combined.length - iv.length);
            return new String(decrypted);
        } catch (Exception ex) {
            throw new RuntimeException("Decryption failed", ex);
        }
    }
}
