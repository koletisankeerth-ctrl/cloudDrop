package com.clouddrop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clouddrop.auth.PasswordHasher;
import com.clouddrop.entity.User;
import com.clouddrop.exception.EmailAlreadyExistsException;
import com.clouddrop.exception.InvalidCredentialsException;
import com.clouddrop.repository.UserRepository;
import com.clouddrop.service.impl.UserServiceImpl;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    UserRepository userRepository;

    // Real hasher (fast enough for a few calls) so we test genuine BCrypt behaviour.
    PasswordHasher passwordHasher = new PasswordHasher();
    UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(userRepository, passwordHasher);
    }

    @Test
    void register_savesUserWithHashedPasswordAndNormalizedEmail() {
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User saved = userService.register(" Alice ", "  Alice@Example.com ", "password123");

        assertThat(saved.getEmail()).isEqualTo("alice@example.com");
        assertThat(saved.getUsername()).isEqualTo("Alice");
        assertThat(saved.getPasswordHash()).isNotEqualTo("password123");
        assertThat(passwordHasher.matches("password123", saved.getPasswordHash())).isTrue();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void register_duplicateEmail_throwsConflict() {
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.register("Alice", "alice@example.com", "password123"))
                .isInstanceOf(EmailAlreadyExistsException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_raceOnUniqueConstraint_throwsConflict() {
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> userService.register("Alice", "alice@example.com", "password123"))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    void authenticate_correctPassword_returnsUser() {
        User stored = userWithPassword("password123");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(stored));

        assertThat(userService.authenticate("Alice@example.com", "password123")).isSameAs(stored);
    }

    @Test
    void authenticate_wrongPassword_throwsInvalidCredentials() {
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(userWithPassword("password123")));

        assertThatThrownBy(() -> userService.authenticate("alice@example.com", "wrong-password"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void authenticate_unknownEmail_throwsInvalidCredentials() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.authenticate("nobody@example.com", "password123"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    private User userWithPassword(String raw) {
        User u = new User();
        u.setEmail("alice@example.com");
        u.setUsername("Alice");
        u.setPasswordHash(passwordHasher.hash(raw));
        return u;
    }
}
