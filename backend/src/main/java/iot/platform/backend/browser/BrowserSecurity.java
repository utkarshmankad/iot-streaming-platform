package iot.platform.backend.browser;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class BrowserSecurity {
  @Bean
  HttpSessionSecurityContextRepository browserContexts() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  SecurityFilterChain browserFilter(
      HttpSecurity http, HttpSessionSecurityContextRepository sessions, BrowserProblems problems)
      throws Exception {
    return http.securityMatcher("/api/v1/**")
        .securityContext(context -> context.securityContextRepository(sessions))
        .headers(
            headers ->
                headers
                    .cacheControl(cache -> cache.disable())
                    .addHeaderWriter(
                        new StaticHeadersWriter(HttpHeaders.CACHE_CONTROL, "no-store")))
        .csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
        .requestCache(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(
            access ->
                access
                    .requestMatchers(HttpMethod.GET, "/api/v1/session/csrf")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/session")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (request, response, failure) ->
                            problems.write(response, BrowserProblems.unauthenticated()))
                    .accessDeniedHandler(
                        (request, response, denial) ->
                            problems.write(
                                response,
                                denial instanceof CsrfException
                                    ? new BrowserProblems.Failure(
                                        HttpStatus.FORBIDDEN,
                                        "INVALID_CSRF",
                                        "Refresh the CSRF token and try again.")
                                    : BrowserProblems.forbidden())))
        .build();
  }

  @Bean
  AuthenticationManager browserAuthentication(
      @Value("${IOT_OPERATOR_A_PASSWORD:}") String first,
      @Value("${IOT_OPERATOR_B_PASSWORD:}") String second) {
    var encoder = new BCryptPasswordEncoder();
    Map<String, String> hashes = new HashMap<>();
    if (!first.isBlank()) {
      hashes.put("operator-a", encoder.encode(first));
    }
    if (!second.isBlank()) {
      hashes.put("operator-b", encoder.encode(second));
    }
    String unknown = encoder.encode(UUID.randomUUID().toString());
    return candidate -> {
      String name = candidate.getName();
      var supplied = candidate.getCredentials();
      if (!(supplied instanceof String password)
          || password.getBytes(StandardCharsets.UTF_8).length > 72
          || !encoder.matches(password, hashes.getOrDefault(name, unknown))
          || !hashes.containsKey(name)) {
        throw new BadCredentialsException("Invalid operator credentials");
      }
      return UsernamePasswordAuthenticationToken.authenticated(name, null, java.util.List.of());
    };
  }
}
