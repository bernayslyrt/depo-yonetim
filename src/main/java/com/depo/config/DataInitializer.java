package com.depo.config;

import com.depo.entity.User;
import com.depo.enums.Role;
import com.depo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Seeds anonymous fixtures for the local development profile only.
 */
@Component
@Profile("local")
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private static final List<UserSeed> LOCAL_DEMO_USERS = List.of(
            new UserSeed("Demo User 01", "demo-user-01", "demo-password-01", Role.USER),
            new UserSeed("Demo User 02", "demo-user-02", "demo-password-02", Role.USER),
            new UserSeed("Demo User 03", "demo-user-03", "demo-password-03", Role.USER),
            new UserSeed("Demo User 04", "demo-user-04", "demo-password-04", Role.USER),
            new UserSeed("Demo User 05", "demo-user-05", "demo-password-05", Role.USER),
            new UserSeed("Demo User 06", "demo-user-06", "demo-password-06", Role.ADMIN),
            new UserSeed("Demo User 07", "demo-user-07", "demo-password-07", Role.USER),
            new UserSeed("Demo User 08", "demo-user-08", "demo-password-08", Role.USER),
            new UserSeed("Demo User 09", "demo-user-09", "demo-password-09", Role.USER),
            new UserSeed("Demo User 10", "demo-user-10", "demo-password-10", Role.USER),
            new UserSeed("Demo User 11", "demo-user-11", "demo-password-11", Role.USER),
            new UserSeed("Demo User 12", "demo-user-12", "demo-password-12", Role.USER),
            new UserSeed("Demo User 13", "demo-user-13", "demo-password-13", Role.USER)
    );

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) {
        seedLocalDemoUsers();
    }

    private void seedLocalDemoUsers() {
        for (UserSeed seed : LOCAL_DEMO_USERS) {
            if (userRepository.existsByUsername(seed.username())) {
                log.info("User already exists; skipping seed: {}", seed.username());
                continue;
            }

            User user = User.builder()
                    .fullName(seed.fullName())
                    .username(seed.username())
                    .password(passwordEncoder.encode(seed.plainTextPassword()))
                    .role(seed.role())
                    .build();

            userRepository.save(user);
            log.info("Local demo user created: {} ({})", seed.username(), seed.role());
        }
    }

    private record UserSeed(String fullName, String username, String plainTextPassword, Role role) {
    }
}
