package iot.platform.backend.browser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("registry")
@RequestMapping("/api/v1/session")
class BrowserSession {
  private final AuthenticationManager login;
  private final BrowserMemberships memberships;
  private final HttpSessionSecurityContextRepository contexts;
  private final boolean secureCookie;

  BrowserSession(
      AuthenticationManager login,
      BrowserMemberships memberships,
      HttpSessionSecurityContextRepository contexts,
      @Value("${IOT_SESSION_SECURE_COOKIE:false}") boolean secureCookie) {
    this.login = login;
    this.memberships = memberships;
    this.contexts = contexts;
    this.secureCookie = secureCookie;
  }

  @GetMapping("/csrf")
  Csrf responseToken(CsrfToken token) {
    return new Csrf(token.getHeaderName(), token.getToken());
  }

  @PostMapping(consumes = "application/json")
  SessionView login(
      @RequestBody Credentials input, HttpServletRequest request, HttpServletResponse response) {
    if (input.username() == null
        || input.password() == null
        || input.username().isBlank()
        || input.username().length() > 64
        || input.password().isBlank()) {
      throw BrowserProblems.bad("INVALID_CREDENTIALS", "Provide a username and password.");
    }
    try {
      login.authenticate(
          UsernamePasswordAuthenticationToken.unauthenticated(input.username(), input.password()));
    } catch (AuthenticationException failed) {
      throw BrowserProblems.unauthenticated();
    }
    var choices = memberships.forLogin(input.username());
    if (choices.isEmpty()) {
      throw BrowserProblems.unauthenticated();
    }
    var selected =
        input.tenantId() == null ? choices.getFirst() : select(choices, input.tenantId());
    var principal = new Principal(selected.operatorId(), input.username(), selected.tenantId());
    rotate(request, response, principal);
    return sessionView(principal, choices);
  }

  @GetMapping
  SessionView current(@AuthenticationPrincipal Principal principal) {
    var choices = choices(principal);
    select(choices, principal.tenantId());
    return sessionView(principal, choices);
  }

  @PutMapping(value = "/tenant", consumes = "application/json")
  SessionView switchTenant(
      @RequestBody TenantChoice input,
      @AuthenticationPrincipal Principal current,
      HttpServletRequest request,
      HttpServletResponse response) {
    if (input.tenantId() == null) {
      throw BrowserProblems.bad("INVALID_TENANT", "Provide a tenant ID.");
    }
    var choices = choices(current);
    var selected = select(choices, input.tenantId());
    var principal = new Principal(current.operatorId(), current.username(), selected.tenantId());
    rotate(request, response, principal);
    return sessionView(principal, choices);
  }

  @DeleteMapping
  ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    var handler = new SecurityContextLogoutHandler();
    handler.setSecurityContextRepository(contexts);
    handler.logout(request, response, auth);
    var cleared =
        ResponseCookie.from("IOTSESSION", "")
            .path("/api/v1")
            .httpOnly(true)
            .secure(secureCookie)
            .sameSite("Strict")
            .maxAge(Duration.ZERO)
            .build();
    return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cleared.toString()).build();
  }

  private void rotate(
      HttpServletRequest request, HttpServletResponse response, Principal principal) {
    var old = request.getSession(false);
    if (old != null) {
      old.invalidate();
    }
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    SecurityContextHolder.setContext(context);
    contexts.saveContext(context, request, response);
  }

  private List<BrowserMemberships.Membership> choices(Principal principal) {
    return memberships.forLogin(principal.username()).stream()
        .filter(member -> member.operatorId().equals(principal.operatorId()))
        .toList();
  }

  private static BrowserMemberships.Membership select(
      List<BrowserMemberships.Membership> choices, UUID tenant) {
    return choices.stream()
        .filter(member -> member.tenantId().equals(tenant))
        .findFirst()
        .orElseThrow(BrowserProblems::forbidden);
  }

  private static SessionView sessionView(
      Principal principal, List<BrowserMemberships.Membership> choices) {
    var available =
        choices.stream()
            .map(member -> new TenantView(member.tenantId(), member.displayName(), member.role()))
            .toList();
    return new SessionView(principal.username(), principal.tenantId(), available);
  }

  record Principal(UUID operatorId, String username, UUID tenantId) {}

  record Csrf(String headerName, String token) {}

  record Credentials(String username, String password, UUID tenantId) {}

  record TenantChoice(UUID tenantId) {}

  record TenantView(UUID tenantId, String displayName, String role) {}

  record SessionView(String username, UUID activeTenantId, List<TenantView> tenants) {}
}
