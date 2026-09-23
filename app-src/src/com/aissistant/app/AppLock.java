package com.aissistant.app;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Password hashing only. The stored record never contains the user's password. */
final class AppLock {
    static final int MIN_PASSWORD_LENGTH = 4;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final int ITERATIONS = 180000;
    private static final String[] KDFS = { "PBKDF2WithHmacSHA256", "PBKDF2WithHmacSHA1" };
    private static final SecureRandom RANDOM = new SecureRandom();

    private AppLock() { }

    static final class PasswordHash {
        final String salt;
        final String hash;
        final String kdf;
        final int iterations;

        PasswordHash(String salt, String hash, String kdf, int iterations) {
            this.salt = salt;
            this.hash = hash;
            this.kdf = kdf;
            this.iterations = iterations;
        }
    }

    static PasswordHash create(char[] password) throws GeneralSecurityException {
        if (password == null || password.length < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        GeneralSecurityException failure = null;
        try {
            for (String kdf : KDFS) {
                try {
                    byte[] hash = derive(password, salt, kdf, ITERATIONS);
                    try {
                        return new PasswordHash(hex(salt), hex(hash), kdf, ITERATIONS);
                    } finally {
                        Arrays.fill(hash, (byte) 0);
                    }
                } catch (java.security.NoSuchAlgorithmException unsupported) {
                    failure = unsupported;
                }
            }
            throw failure == null ? new GeneralSecurityException("PBKDF2 unavailable") : failure;
        } finally {
            Arrays.fill(salt, (byte) 0);
        }
    }

    static boolean verify(char[] password, String saltText, String expectedText, String kdf, int iterations) {
        if (password == null || password.length == 0 || iterations < 10000 || !knownKdf(kdf)) return false;
        byte[] salt = null;
        byte[] expected = null;
        byte[] actual = null;
        try {
            salt = unhex(saltText);
            expected = unhex(expectedText);
            actual = derive(password, salt, kdf, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (salt != null) Arrays.fill(salt, (byte) 0);
            if (expected != null) Arrays.fill(expected, (byte) 0);
            if (actual != null) Arrays.fill(actual, (byte) 0);
        }
    }

    static void wipe(char[] password) {
        if (password != null) Arrays.fill(password, '\0');
    }

    private static boolean knownKdf(String value) {
        for (String candidate : KDFS) if (candidate.equals(value)) return true;
        return false;
    }

    private static byte[] derive(char[] password, byte[] salt, String kdf, int iterations)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance(kdf).generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static String hex(byte[] value) {
        char[] out = new char[value.length * 2];
        final char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < value.length; i++) {
            int n = value[i] & 0xff;
            out[i * 2] = alphabet[n >>> 4];
            out[i * 2 + 1] = alphabet[n & 15];
        }
        return new String(out);
    }

    private static byte[] unhex(String value) {
        if (value == null || (value.length() & 1) != 0) throw new IllegalArgumentException("bad hex");
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(value.charAt(i * 2), 16);
            int lo = Character.digit(value.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("bad hex");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
