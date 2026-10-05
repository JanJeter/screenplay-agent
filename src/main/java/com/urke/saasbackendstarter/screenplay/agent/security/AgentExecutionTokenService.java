package com.urke.saasbackendstarter.screenplay.agent.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class AgentExecutionTokenService {
    private static final String ISSUER = "screenplay-agent-backend";
    private static final String AUDIENCE = "screenplay-agent-internal";
    private final SecretKey key;
    private final long ttlSeconds;

    public AgentExecutionTokenService(
            @Value("${agent.execution.secret}") String secret,
            @Value("${agent.execution.ttl-seconds}") long ttlSeconds) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("agent.execution.secret must be at least 32 bytes");
        }
        if (ttlSeconds <= 0) throw new IllegalStateException("agent.execution.ttl-seconds must be positive");
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = ttlSeconds;
    }

    public String issue(AgentExecutionClaims claims) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject("agent-gateway")
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .id(java.util.UUID.randomUUID().toString())
                .claim("organizationId", claims.organizationId())
                .claim("userId", claims.userId())
                .claim("projectId", claims.projectId())
                .claim("screenplayId", claims.screenplayId())
                .claim("applicationRunId", claims.applicationRunId())
                .claim("sessionId", claims.sessionId())
                .claim("scopes", String.join(" ", claims.scopes()))
                .signWith(key)
                .compact();
    }

    public AgentExecutionClaims verifyBearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) unauthorized();
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(authorization.substring("Bearer ".length())).getPayload();
            if (!ISSUER.equals(claims.getIssuer()) || claims.getAudience() == null
                    || !claims.getAudience().contains(AUDIENCE)) unauthorized();
            return new AgentExecutionClaims(
                    number(claims, "organizationId"), number(claims, "userId"),
                    number(claims, "projectId"), number(claims, "screenplayId"),
                    string(claims, "applicationRunId"), string(claims, "sessionId"), scopes(claims));
        } catch (JwtException | IllegalArgumentException ex) {
            unauthorized();
        }
        throw new IllegalStateException("unreachable");
    }

    private Long number(Claims claims, String name) {
        Number value = claims.get(name, Number.class);
        if (value == null || value.longValue() <= 0) unauthorized();
        return value.longValue();
    }

    private String string(Claims claims, String name) {
        String value = claims.get(name, String.class);
        if (value == null || value.isBlank() || value.length() > 128) unauthorized();
        return value;
    }

    private Set<String> scopes(Claims claims) {
        String value = claims.get("scopes", String.class);
        if (value == null || value.isBlank()) unauthorized();
        return Set.copyOf(new LinkedHashSet<>(Arrays.asList(value.split(" "))));
    }

    private void unauthorized() {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid agent execution credential");
    }
}
