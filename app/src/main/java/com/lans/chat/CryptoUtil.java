package com.lans.chat;

import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class CryptoUtil {

    private static final String DEFAULT_PASSWORD = "LanChat@2026";
    private static final String TRANSFORM = "AES/CBC/PKCS5Padding";
    private static final int IV_LENGTH = 16;

    private CryptoUtil() {
    }

    public static SecretKeySpec deriveKey(String password) {
        try {
            String p = (password == null || password.trim().isEmpty()) ? DEFAULT_PASSWORD : password.trim();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return new SecretKeySpec(digest.digest(p.getBytes("UTF-8")), "AES");
        } catch (Exception e) {
            throw new RuntimeException("密钥派生失败", e);
        }
    }

    public static byte[] encrypt(SecretKeySpec key, byte[] plain) throws Exception {
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance(TRANSFORM);
        cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
        byte[] enc = cipher.doFinal(plain);
        byte[] out = new byte[IV_LENGTH + enc.length];
        System.arraycopy(iv, 0, out, 0, IV_LENGTH);
        System.arraycopy(enc, 0, out, IV_LENGTH, enc.length);
        return out;
    }

    public static byte[] decrypt(SecretKeySpec key, byte[] data) throws Exception {
        if (data.length <= IV_LENGTH) {
            throw new IllegalArgumentException("密文长度不足");
        }
        Cipher cipher = Cipher.getInstance(TRANSFORM);
        cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(data, 0, IV_LENGTH));
        return cipher.doFinal(data, IV_LENGTH, data.length - IV_LENGTH);
    }
}
