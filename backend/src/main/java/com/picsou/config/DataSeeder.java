package com.picsou.config;

import com.picsou.model.AppUser;
import com.picsou.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.user.username}")
    private String username;

    @Value("${app.user.password-hash}")
    private String passwordHash;

    public DataSeeder(AppUserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.existsByUsername(username)) {
            return; // idempotent — do nothing if user already exists
        }

        // Validate the hash is a valid bcrypt hash before storing
        if (!passwordHash.startsWith("$2")) {
            throw new IllegalStateException(
                "APP_PASSWORD_HASH must be a valid bcrypt hash starting with $2a$, $2b$, or $2y$. " +
                "Generate one with: htpasswd -bnBC 12 \"\" your_password | tr -d ':\\n'"
            );
        }

        AppUser user = AppUser.builder()
            .username(username)
            .passwordHash(passwordHash)
            .build();

        userRepository.save(user);
        log.info("Created application user: {}", username);
    }
}
