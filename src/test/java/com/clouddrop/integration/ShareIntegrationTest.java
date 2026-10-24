package com.clouddrop.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clouddrop.entity.ShareLink;
import com.clouddrop.util.TokenHasher;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class ShareIntegrationTest extends IntegrationTestBase {

    static final byte[] CONTENT = "shared file content".getBytes();

    @Test
    void shareLink_worksWithoutLogin_forInfoAndDownload() throws Exception {
        String token = newUserToken("alice@example.com");
        long fileId = upload(token, "report.pdf", "application/pdf", CONTENT);
        String shareToken = createShare(token, fileId, 30);

        // NOTE: no Authorization header on either request
        mockMvc.perform(get("/api/shares/" + shareToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalFilename").value("report.pdf"))
                .andExpect(jsonPath("$.storageKey").doesNotExist())
                .andExpect(jsonPath("$.ownerId").doesNotExist());
        mockMvc.perform(get("/api/shares/" + shareToken + "/download"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(CONTENT));
    }

    @Test
    void onlyTheTokenHashIsStored_andTokensAreUniqueAndUnpredictable() throws Exception {
        String token = newUserToken("alice@example.com");
        long fileId = upload(token, "a.txt", "text/plain", CONTENT);

        String first = createShare(token, fileId, 30);
        String second = createShare(token, fileId, 30);

        assertThat(first).isNotEqualTo(second).hasSize(43);
        assertThat(shareLinkRepository.findAll()).extracting(ShareLink::getTokenHash)
                .containsExactlyInAnyOrder(TokenHasher.sha256Hex(first), TokenHasher.sha256Hex(second))
                .doesNotContain(first, second);
    }

    @Test
    void expiredLink_returns410_forInfoAndDownload() throws Exception {
        String token = newUserToken("alice@example.com");
        long fileId = upload(token, "a.txt", "text/plain", CONTENT);
        String shareToken = createShare(token, fileId, 30);

        ShareLink link = shareLinkRepository.findByTokenHash(TokenHasher.sha256Hex(shareToken)).orElseThrow();
        link.setExpiresAt(Instant.now().minus(Duration.ofSeconds(1)));   // move the expiry into the past
        shareLinkRepository.save(link);

        mockMvc.perform(get("/api/shares/" + shareToken))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value("Share link has expired"));
        mockMvc.perform(get("/api/shares/" + shareToken + "/download")).andExpect(status().isGone());
    }

    @Test
    void revokedLink_returns410_evenBeforeExpiry() throws Exception {
        String token = newUserToken("alice@example.com");
        long fileId = upload(token, "a.txt", "text/plain", CONTENT);
        String shareToken = createShare(token, fileId, 60 * 24);

        ShareLink link = shareLinkRepository.findByTokenHash(TokenHasher.sha256Hex(shareToken)).orElseThrow();
        link.setRevokedAt(Instant.now());
        shareLinkRepository.save(link);

        mockMvc.perform(get("/api/shares/" + shareToken))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value("Share link has been revoked"));
        mockMvc.perform(get("/api/shares/" + shareToken + "/download")).andExpect(status().isGone());
    }

    @Test
    void unknownToken_returns404() throws Exception {
        mockMvc.perform(get("/api/shares/not-a-real-token")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/shares/not-a-real-token/download")).andExpect(status().isNotFound());
    }

    @Test
    void deletingTheFile_killsItsShareLinks() throws Exception {
        String token = newUserToken("alice@example.com");
        long fileId = upload(token, "a.txt", "text/plain", CONTENT);
        String shareToken = createShare(token, fileId, 30);

        mockMvc.perform(delete("/api/files/" + fileId).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        assertThat(shareLinkRepository.count()).isZero();   // removed by ON DELETE CASCADE
        mockMvc.perform(get("/api/shares/" + shareToken)).andExpect(status().isNotFound());
    }

    @Test
    void createShare_invalidExpiry_returns400_andCreatesNothing() throws Exception {
        String token = newUserToken("alice@example.com");
        long fileId = upload(token, "a.txt", "text/plain", CONTENT);

        for (String body : new String[]{"{\"expiresInMinutes\":0}", "{\"expiresInMinutes\":43201}",
                "{\"expiresInMinutes\":\"soon\"}"}) {
            mockMvc.perform(post("/api/files/" + fileId + "/shares").header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(shareLinkRepository.count()).isZero();
    }

    @Test
    void createShare_withoutBody_defaultsToTwentyFourHours() throws Exception {
        String token = newUserToken("alice@example.com");
        long fileId = upload(token, "a.txt", "text/plain", CONTENT);

        mockMvc.perform(post("/api/files/" + fileId + "/shares").header("Authorization", bearer(token)))
                .andExpect(status().isCreated());

        ShareLink link = shareLinkRepository.findAll().get(0);
        assertThat(Duration.between(link.getCreatedAt(), link.getExpiresAt())).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void shareEndpoints_cannotBeAbusedToReachPrivateRoutes() throws Exception {
        // only GET /api/shares/{token}[/download] is public: other shapes still need a JWT
        mockMvc.perform(get("/api/shares/a/b")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/shares/some-token")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/shares/")).andExpect(status().isUnauthorized());
    }
}
