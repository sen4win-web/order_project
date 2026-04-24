package com.orderplatform.order.controller;

import com.orderplatform.order.security.JwtUtil;
import com.orderplatform.order.security.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final JwtUtil jwtUtil;
    private final RateLimiter rateLimiter;
    private final String demoUsername;
    private final String demoPassword;

    public AuthController(JwtUtil jwtUtil,
                          RateLimiter rateLimiter,
                          @Value("${app.auth.demo-username}") String demoUsername,
                          @Value("${app.auth.demo-password}") String demoPassword) {
        this.jwtUtil = jwtUtil;
        this.rateLimiter = rateLimiter;
        this.demoUsername = demoUsername;
        this.demoPassword = demoPassword;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String clientIp = getClientIp(httpRequest);

        if (!rateLimiter.isAllowed(clientIp)) {
            log.warn("Rate limit exceeded for login from ip={}", clientIp);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                    "error", "TOO_MANY_REQUESTS",
                    "message", "Too many login attempts. Please try again later."
            ));
        }

        if (demoUsername.equals(request.username()) && demoPassword.equals(request.password())) {
            String token = jwtUtil.generateToken(request.username());
            log.info("Login successful for user={}", request.username());
            return ResponseEntity.ok(Map.of(
                    "token", token,
                    "type", "Bearer",
                    "expiresIn", 3600
            ));
        }

        log.warn("Login failed for user={} from ip={}", request.username(), clientIp);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "error", "UNAUTHORIZED",
                "message", "Invalid username or password"
        ));
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    public record LoginRequest(String username, String password) {}
}
