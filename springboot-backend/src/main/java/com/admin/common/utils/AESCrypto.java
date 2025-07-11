package com.admin.common.utils;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES加密工具类
 * 使用AES-256-GCM模式，与Go端保持兼容
 */
@Slf4j
public class AESCrypto {
    
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12; // GCM推荐的IV长度
    private static final int GCM_TAG_LENGTH = 16; // GCM认证标签长度
    
    private final SecretKeySpec secretKey;
    private final SecureRandom secureRandom;
    
    /**
     * 构造函数
     * @param secret 密钥字符串，将使用SHA-256转换为32字节密钥
     */
    public AESCrypto(String secret) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("密钥不能为空");
        }
        
        try {
            // 使用SHA-256将密码转换为32字节密钥，与Go端保持一致
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = sha256.digest(secret.getBytes(StandardCharsets.UTF_8));
            this.secretKey = new SecretKeySpec(keyBytes, ALGORITHM);
            this.secureRandom = new SecureRandom();
            
            log.debug("AES加密器初始化成功");
        } catch (Exception e) {
            log.error("AES加密器初始化失败", e);
            throw new RuntimeException("AES加密器初始化失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 加密数据
     * @param data 要加密的原始数据
     * @return Base64编码的加密数据，格式为: nonce + ciphertext
     */
    public String encrypt(byte[] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("待加密数据不能为空");
        }
        
        try {
            // 生成随机IV（nonce）
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            
            // 创建GCM参数规范
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
            
            // 初始化Cipher
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec);
            
            // 加密数据
            byte[] ciphertext = cipher.doFinal(data);
            
            // 组合IV + ciphertext，与Go端格式保持一致
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);
            
            // 返回Base64编码结果
            return Base64.getEncoder().encodeToString(buffer.array());
            
        } catch (Exception e) {
            log.error("数据加密失败", e);
            throw new RuntimeException("数据加密失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 加密字符串
     * @param data 要加密的字符串
     * @return Base64编码的加密数据
     */
    public String encrypt(String data) {
        if (data == null) {
            throw new IllegalArgumentException("待加密字符串不能为空");
        }
        return encrypt(data.getBytes(StandardCharsets.UTF_8));
    }
    
    /**
     * 解密数据
     * @param encryptedData Base64编码的加密数据
     * @return 解密后的原始数据
     */
    public byte[] decrypt(String encryptedData) {
        if (encryptedData == null || encryptedData.isEmpty()) {
            throw new IllegalArgumentException("加密数据不能为空");
        }
        
        try {
            // Base64解码
            byte[] encrypted = Base64.getDecoder().decode(encryptedData);
            
            if (encrypted.length < GCM_IV_LENGTH) {
                throw new IllegalArgumentException("加密数据长度不足");
            }
            
            // 分离IV和密文
            ByteBuffer buffer = ByteBuffer.wrap(encrypted);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            
            // 创建GCM参数规范
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
            
            // 初始化Cipher
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);
            
            // 解密数据
            return cipher.doFinal(ciphertext);
            
        } catch (Exception e) {
            log.error("数据解密失败", e);
            throw new RuntimeException("数据解密失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 解密字符串
     * @param encryptedData Base64编码的加密数据
     * @return 解密后的字符串
     */
    public String decryptString(String encryptedData) {
        byte[] decrypted = decrypt(encryptedData);
        return new String(decrypted, StandardCharsets.UTF_8);
    }
    
    /**
     * 创建AES加密器实例
     * @param secret 密钥字符串
     * @return AES加密器实例，如果创建失败返回null
     */
    public static AESCrypto create(String secret) {
        try {
            return new AESCrypto(secret);
        } catch (Exception e) {
            log.error("创建AES加密器失败: {}", e.getMessage());
            return null;
        }
    }
} 