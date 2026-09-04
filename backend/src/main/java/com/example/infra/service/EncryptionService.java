package com.example.infra.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;

@Service
public class EncryptionService {

    @Value("${app.security.encryption-key:MZC-INFRA-SECRET-KEY}")
    private String masterKey;

    private TextEncryptor encryptor;

    @PostConstruct
    public void init() {
        // Spring Security Encryptors requires a hex-encoded salt.
        String salt = "6d7a633132333435"; 
        this.encryptor = Encryptors.text(masterKey, salt);
    }

    public String encrypt(String plainText) {
        if (plainText == null) return null;
        return encryptor.encrypt(plainText);
    }

    public String decrypt(String encryptedText) {
        if (encryptedText == null) return null;
        try {
            return encryptor.decrypt(encryptedText);
        } catch (Exception e) {
            return "DECRYPTION_FAILED";
        }
    }
}
