package iot.platform.backend.registry;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("registry")
class RegistryController {
  private static final String BEARER = "Bearer ";
  private final GatewayRegistry registry;

  RegistryController(GatewayRegistry registry) {
    this.registry = registry;
  }

  @GetMapping(value = "/internal/v1/registry", produces = "application/x-protobuf")
  ResponseEntity<byte[]> registry(
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String auth,
      @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String version) {
    String token =
        auth != null && auth.regionMatches(true, 0, BEARER, 0, BEARER.length())
            ? auth.substring(BEARER.length())
            : null;
    var snapshot = registry.snapshot(token);
    if (snapshot.isEmpty()) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
          .build();
    }
    byte[] bytes = snapshot.orElseThrow().toByteArray();
    String etag = etag(bytes);
    return etag.equals(version)
        ? ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build()
        : ResponseEntity.ok().eTag(etag).body(bytes);
  }

  private static String etag(byte[] bytes) {
    try {
      return "\""
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
          + "\"";
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Required SHA-256 algorithm unavailable", impossible);
    }
  }
}
