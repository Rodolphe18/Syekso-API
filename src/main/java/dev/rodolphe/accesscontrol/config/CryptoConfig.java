package dev.rodolphe.accesscontrol.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * A textbook case for a {@code @Bean} method rather than a stereotype annotation: BCryptPasswordEncoder
 * is a third-party class, so there is no source file of ours to annotate. Declaring it here is the
 * only way to put it in the context.
 *
 * <p>It reads the {@code $2a$} hashes the Kotlin server wrote with jbcrypt — both implement the same
 * bcrypt format, so no password needs rehashing during the migration.
 */
@Configuration
public class CryptoConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
