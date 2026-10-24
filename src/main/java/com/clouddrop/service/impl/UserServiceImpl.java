package com.clouddrop.service.impl;

import com.clouddrop.auth.PasswordHasher;
import com.clouddrop.entity.User;
import com.clouddrop.exception.BadRequestException;
import com.clouddrop.exception.EmailAlreadyExistsException;
import com.clouddrop.exception.InvalidCredentialsException;
import com.clouddrop.exception.UnauthorizedException;
import com.clouddrop.repository.UserRepository;
import com.clouddrop.service.UserService;
import java.time.Instant;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    // A valid hash of a random password, checked when the email is unknown so that
    // "unknown email" and "wrong password" take about the same time (limits user enumeration by timing).
    private final String dummyHash;

    public UserServiceImpl(UserRepository userRepository, PasswordHasher passwordHasher) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.dummyHash = passwordHasher.hash(java.util.UUID.randomUUID().toString());
    }

    // Not @Transactional: repository.save() already runs in its own transaction, and we want the
    // unique-constraint exception to reach the catch block below instead of surfacing at commit time.
    @Override
    public User register(String username, String email, String rawPassword) {
        if (PasswordHasher.tooLong(rawPassword)) {
            throw new BadRequestException("Password must be at most 72 bytes");
        }
        String normalizedEmail = normalize(email);
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new EmailAlreadyExistsException();
        }

        User user = new User();
        user.setUsername(username.trim());
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordHasher.hash(rawPassword));
        user.setCreatedAt(Instant.now());
        try {
            return userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // Two simultaneous registrations passed the exists-check; the DB unique constraint is the real guard.
            throw new EmailAlreadyExistsException();
        }
    }

    @Override
    public User authenticate(String email, String rawPassword) {
        User user = userRepository.findByEmail(normalize(email)).orElse(null);
        if (user == null) {
            passwordHasher.matches(rawPassword, dummyHash);
            throw new InvalidCredentialsException();
        }
        if (!passwordHasher.matches(rawPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return user;
    }

    @Override
    public User getById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UnauthorizedException("Account no longer exists"));
    }

    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
