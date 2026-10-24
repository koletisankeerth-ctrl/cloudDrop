package com.clouddrop.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clouddrop.config.AppProperties;
import com.clouddrop.repository.ShareLinkRepository;
import com.clouddrop.repository.StoredFileRepository;
import com.clouddrop.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Starts the WHOLE application (servlet filter, controllers, services, JPA, real LocalFileStorageService)
 * against in-memory H2. @AutoConfigureMockMvc sends requests through the real filter chain without opening a port.
 * All integration tests share one cached application context, so startup cost is paid once.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class IntegrationTestBase {

    static final Path UPLOAD_DIR = createUploadDir();
    static final String PASSWORD = "password123";

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("clouddrop.storage.upload-dir", UPLOAD_DIR::toString);
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    UserRepository userRepository;
    @Autowired
    StoredFileRepository fileRepository;
    @Autowired
    ShareLinkRepository shareLinkRepository;
    @Autowired
    AppProperties appProperties;

    // Every test starts from an empty database and an empty upload directory.
    @BeforeEach
    void cleanState() throws IOException {
        shareLinkRepository.deleteAll();
        fileRepository.deleteAll();
        userRepository.deleteAll();
        try (Stream<Path> files = Files.list(UPLOAD_DIR)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    long registerUser(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json("User", email, PASSWORD)))
                .andExpect(status().isCreated()).andReturn();
        return body(result).get("id").asLong();
    }

    String loginAndGetToken(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return body(result).get("token").asText();
    }

    /** Registers a user and returns a valid JWT for them. */
    String newUserToken(String email) throws Exception {
        registerUser(email);
        return loginAndGetToken(email);
    }

    long upload(String token, String filename, String contentType, byte[] content) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/files")
                        .file(new MockMultipartFile("file", filename, contentType, content))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isCreated()).andReturn();
        return body(result).get("id").asLong();
    }

    /** Creates a share link and returns the raw token. */
    String createShare(String token, long fileId, int minutes) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/files/" + fileId + "/shares")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresInMinutes\":" + minutes + "}"))
                .andExpect(status().isCreated()).andReturn();
        return body(result).get("token").asText();
    }

    static String bearer(String token) {
        return "Bearer " + token;
    }

    JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    long filesOnDisk() throws IOException {
        try (Stream<Path> files = Files.list(UPLOAD_DIR)) {
            return files.count();
        }
    }

    private static String json(String username, String email, String password) {
        return "{\"username\":\"" + username + "\",\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private static Path createUploadDir() {
        try {
            Path dir = Files.createTempDirectory("clouddrop-it-");
            dir.toFile().deleteOnExit();
            return dir;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
