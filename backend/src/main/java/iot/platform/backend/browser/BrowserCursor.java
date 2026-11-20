package iot.platform.backend.browser;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class BrowserCursor {
  private BrowserCursor() {}

  static String encode(UUID tenant, UUID last, String sessionId) {
    byte[] scope =
        ByteBuffer.allocate(32)
            .putLong(tenant.getMostSignificantBits())
            .putLong(tenant.getLeastSignificantBits())
            .putLong(last.getMostSignificantBits())
            .putLong(last.getLeastSignificantBits())
            .array();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(ByteBuffer.allocate(64).put(scope).put(tag(scope, sessionId)).array());
  }

  static UUID decode(String value, UUID tenant, String sessionId) {
    if (value == null) {
      return null;
    }
    if (value.length() != 86) {
      throw invalid();
    }
    byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(value);
    } catch (IllegalArgumentException malformed) {
      throw invalid();
    }
    if (decoded.length != 64
        || !MessageDigest.isEqual(
            Arrays.copyOfRange(decoded, 32, 64), tag(Arrays.copyOf(decoded, 32), sessionId))) {
      throw invalid();
    }
    var fields = ByteBuffer.wrap(decoded);
    if (!tenant.equals(new UUID(fields.getLong(), fields.getLong()))) {
      throw invalid();
    }
    return new UUID(fields.getLong(), fields.getLong());
  }

  private static BrowserProblems.Failure invalid() {
    return BrowserProblems.bad("INVALID_CURSOR", "Start pagination again for the current tenant.");
  }

  private static byte[] tag(byte[] bytes, String sessionId) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(sessionId.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
      return mac.doFinal(bytes);
    } catch (GeneralSecurityException unavailable) {
      throw new IllegalStateException("HmacSHA256 is unavailable", unavailable);
    }
  }
}
