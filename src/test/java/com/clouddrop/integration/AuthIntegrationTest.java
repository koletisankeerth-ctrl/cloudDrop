package com.clouddrop.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clouddrop.auth.JwtService;
import com.clouddrop.config.AppProperties;
import com.clouddrop.entity.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class AuthIntegrationTest extends IntegrationTestBase {

    @Test
    void healthEndpoint_isPublic() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void register_thenLogin_thenMe_worksEndToEnd() throws Exception {
        long id = registerUser("alice@example.com");

        String token = loginAndGetToken("alice@example.com");

        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void register_storesBcryptHash_notThePassword() throws Exception {
        registerUser("alice@example.com");

        String stored = userRepository.findByEmail("alice@example.com").orElseThrow().getPasswordHash();

        assertThat(stored).startsWith("$2").isNotEqualTo(PASSWORD);
    }

    @Test
    void register_duplicateEmail_returns409_evenWithDifferentCase() throws Exception {
        registerUser("alice@example.com");

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"Other\",\"email\":\"ALICE@Example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isConflict());
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void register_invalidInput_returns400_withFieldErrors() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"email\":\"nope\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.username").exists())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    void login_wrongPasswordAndUnknownEmail_returnSame401() throws Exception {
        registerUser("alice@example.com");

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void privateEndpoints_withoutToken_return401() throws Exception {
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/files")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/files/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/files/1/download")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/files/1/shares")).andExpect(status().isUnauthorized());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/files/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidTokens_return401() throws Exception {
        String valid = newUserToken("alice@example.com");
        String tampered = valid.substring(0, valid.length() - 5) + (valid.endsWith("AAAAA") ? "BBBBB" : "AAAAA");

        for (String bad : new String[]{"garbage", tampered, ""}) {
            mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + bad))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.anyOf(
                            org.hamcrest.Matchers.is("Invalid or expired token"),
                            org.hamcrest.Matchers.is("Authentication required"))));
        }
    }

    @Test
    void tokenSignedWithAnotherSecret_returns401() throws Exception {
        long id = registerUser("alice@example.com");
        JwtService attacker = new JwtService(new AppProperties(
                new AppProperties.Jwt("a-different-secret-that-is-32-chars-long!", 24),
                appProperties.storage()), Clock.systemUTC());

        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(attacker.generate(user(id)).token())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredToken_returns401() throws Exception {
        long id = registerUser("alice@example.com");
        Clock twoDaysAgo = Clock.fixed(Instant.now().minus(Duration.ofDays(2)), ZoneOffset.UTC);
        JwtService past = new JwtService(appProperties, twoDaysAgo);

        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(past.generate(user(id)).token())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenForDeletedAccount_returns401() throws Exception {
        String token = newUserToken("alice@example.com");
        userRepository.deleteAll();

        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void clientSuppliedIdentity_isIgnored() throws Exception {
        long aliceId = registerUser("alice@example.com");
        String bobToken = newUserToken("bob@example.com");

        // Bob sends Alice's id in headers and query: /me must still be Bob.
        mockMvc.perform(get("/api/auth/me").param("userId", String.valueOf(aliceId))
                        .header("X-User-Id", String.valueOf(aliceId))
                        .header("Authorization", bearer(bobToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("bob@example.com"));
    }

    private static User user(long id) {
        User u = new User();
        u.setId(id);
        u.setEmail("alice@example.com");
        return u;
    }
}
