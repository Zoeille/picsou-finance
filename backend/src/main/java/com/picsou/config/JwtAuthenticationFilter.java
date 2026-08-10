package com.picsou.config;

import com.picsou.model.AppUser;
import com.picsou.repository.AppUserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtUtil jwtUtil;
    private final AppUserRepository userRepository;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, AppUserRepository userRepository) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws ServletException, IOException {

        String token = extractAccessTokenFromCookie(request);

        if (token != null) {
            try {
                Claims claims = jwtUtil.validateAndParse(token);
                if (jwtUtil.isAccessToken(claims)) {
                    Long userId = claims.get("uid", Long.class);
                    Long tv = jwtUtil.getTokenVersion(claims);
                    if (userId != null) {
                        AppUser user = userRepository.findByIdWithMember(userId).orElse(null);
                        if (user != null && user.isActivated()
                            && tv != null && tv == user.getTokenVersion()) {
                            String role = "ROLE_" + user.getRole().name();
                            var auth = new UsernamePasswordAuthenticationToken(
                                user,
                                null,
                                List.of(new SimpleGrantedAuthority(role))
                            );
                            SecurityContextHolder.getContext().setAuthentication(auth);
                        }
                    }
                }
            } catch (JwtException ex) {
                // Continue unauthenticated. DEBUG, not WARN: an expired access_token is the
                // normal prelude to a refresh, so this is diagnostics rather than an incident.
                log.debug("Rejected access_token on {} {}: {}",
                    request.getMethod(), request.getRequestURI(), ex.getMessage());
            }
        }

        chain.doFilter(request, response);
    }

    private String extractAccessTokenFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if ("access_token".equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
