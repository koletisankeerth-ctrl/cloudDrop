package com.clouddrop.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clouddrop.auth.AuthContext;
import com.clouddrop.auth.AuthenticatedUser;
import com.clouddrop.auth.IssuedToken;
import com.clouddrop.auth.JwtService;
import com.clouddrop.entity.User;
import org.junit.jupiter.api.AfterEach;
import com.clouddrop.exception.EmailAlreadyExistsException;
import com.clouddrop.exception.GlobalExceptionHandler;
import com.clouddrop.exception.InvalidCredentialsException;
import com.clouddrop.service.UserService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// @WebMvcTest loads only the web layer (controller + advice + JSON), with the service mocked. No DB needed.
@WebMvcTest(AuthController.class)
@Import(GlobalExceptionHandler.class)
class AuthControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    UserService userService;

    @MockBean
    JwtService jwtService;

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void login_validCredentials_returnsToken() throws Exception {
        User user = new User();
        user.setId(1L);
        user.setUsername("Alice");
        user.setEmail("alice@example.com");
        user.setCreatedAt(Instant.now());
        when(userService.authenticate("alice@example.com", "password123")).thenReturn(user);
        when(jwtService.generate(user)).thenReturn(new IssuedToken("jwt-value", Instant.now().plusSeconds(60)));

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-value"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.email").value("alice@example.com"));
    }

    @Test
    void me_usesIdentityFromAuthContext() throws Exception {
        User user = new User();
        user.setId(7L);
        user.setUsername("Bob");
        user.setEmail("bob@example.com");
        user.setCreatedAt(Instant.now());
        when(userService.getById(7L)).thenReturn(user);
        AuthContext.set(new AuthenticatedUser(7L, "bob@example.com"));

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7));
    }

    @Test
    void me_withoutAuthContext_returns401() throws Exception {
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void register_validRequest_returns201WithoutPasswordHash() throws Exception {
        User user = new User();
        user.setId(1L);
        user.setUsername("Alice");
        user.setEmail("alice@example.com");
        user.setPasswordHash("secret-hash");
        user.setCreatedAt(Instant.now());
        when(userService.register(eq("Alice"), eq("alice@example.com"), any())).thenReturn(user);

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"Alice\",\"email\":\"alice@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void register_invalidBody_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"email\":\"not-an-email\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists())
                .andExpect(jsonPath("$.fieldErrors.username").exists());
    }

    @Test
    void register_duplicateEmail_returns409() throws Exception {
        when(userService.register(any(), any(), any())).thenThrow(new EmailAlreadyExistsException());

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"Alice\",\"email\":\"alice@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void login_badCredentials_returns401() throws Exception {
        when(userService.authenticate(any(), any())).thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest());
    }
}
