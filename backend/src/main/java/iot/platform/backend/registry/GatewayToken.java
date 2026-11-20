package iot.platform.backend.registry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

final class GatewayToken {
  private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{48}");

  private GatewayToken() {}

  static boolean valid(String token) {
    return token != null && TOKEN.matcher(token).matches();
  }

  static byte[] digest(String token) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Required SHA-256 algorithm unavailable", impossible);
    }
  }
}
