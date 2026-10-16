package cn.community.common;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 与初始化脚本相同的nonce12 || ciphertext || tag16封装，无AAD；独立HMAC密钥。 */
@Component
public class Crypto {
  private final byte[] aes, hmac;
  private final SecureRandom random = new SecureRandom();

  public Crypto(@Value("${app.aes-key}") String aes, @Value("${app.hmac-key}") String hmac) {
    try {
      this.aes = HexFormat.of().parseHex(aes);
      this.hmac = HexFormat.of().parseHex(hmac);
    } catch (Exception e) {
      throw new IllegalStateException("加密密钥必须为十六进制");
    }
    if (this.aes.length != 32 || this.hmac.length != 32)
      throw new IllegalStateException("APP_AES_KEY及APP_HMAC_KEY必须设置为32字节密钥");
  }

  public byte[] encrypt(String text) {
    try {
      byte[] nonce = new byte[12];
      random.nextBytes(nonce);
      var c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aes, "AES"), new GCMParameterSpec(128, nonce));
      byte[] encrypted = c.doFinal(text.getBytes(StandardCharsets.UTF_8));
      byte[] all = Arrays.copyOf(nonce, nonce.length + encrypted.length);
      System.arraycopy(encrypted, 0, all, 12, encrypted.length);
      return all;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("加密失败", e);
    }
  }

  public String decrypt(byte[] bytes) {
    try {
      var c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(aes, "AES"),
          new GCMParameterSpec(128, Arrays.copyOf(bytes, 12)));
      return new String(
          c.doFinal(Arrays.copyOfRange(bytes, 12, bytes.length)), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("密钥版本或密文不匹配", e);
    }
  }

  public byte[] hash(String text) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(hmac, "HmacSHA256"));
      return mac.doFinal(text.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public String digest(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
