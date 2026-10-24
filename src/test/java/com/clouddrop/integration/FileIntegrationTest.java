package com.clouddrop.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clouddrop.entity.StoredFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

class FileIntegrationTest extends IntegrationTestBase {

    static final byte[] PDF_BYTES = "%PDF-1.4 fake pdf content éè".getBytes(StandardCharsets.UTF_8);

    @Test
    void upload_list_metadata_download_delete_fullFlow() throws Exception {
        String token = newUserToken("alice@example.com");

        long id = upload(token, "My Report.pdf", "application/pdf", PDF_BYTES);

        // exactly one stored file on disk, with a server-generated name unrelated to the client filename
        assertThat(filesOnDisk()).isEqualTo(1);
        StoredFile saved = fileRepository.findById(id).orElseThrow();
        assertThat(saved.getStorageKey()).matches("[0-9a-f-]{36}");
        assertThat(saved.getSizeBytes()).isEqualTo(PDF_BYTES.length);

        mockMvc.perform(get("/api/files").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].originalFilename").value("My Report.pdf"))
                .andExpect(jsonPath("$.items[0].storageKey").doesNotExist());

        mockMvc.perform(get("/api/files/" + id).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentType").value("application/pdf"));

        MvcResult download = mockMvc.perform(get("/api/files/" + id + "/download").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(PDF_BYTES);   // byte-for-byte identical

        mockMvc.perform(delete("/api/files/" + id).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());
        assertThat(filesOnDisk()).isZero();
        assertThat(fileRepository.count()).isZero();
        mockMvc.perform(get("/api/files/" + id).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/files/" + id).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
    }

    @Test
    void upload_maliciousFilename_isStoredAsMetadataOnly_andCannotEscapeTheUploadDirectory() throws Exception {
        String token = newUserToken("alice@example.com");

        long id = upload(token, "../../../evil.txt", "text/plain", "x".getBytes());

        assertThat(fileRepository.findById(id).orElseThrow().getOriginalFilename()).isEqualTo("evil.txt");
        assertThat(filesOnDisk()).isEqualTo(1);
        assertThat(UPLOAD_DIR.getParent().resolve("evil.txt")).doesNotExist();
    }

    @Test
    void upload_largeFile_isStreamedAndReturnedIntact() throws Exception {
        String token = newUserToken("alice@example.com");
        byte[] big = new byte[3 * 1024 * 1024 + 17];   // bigger than the 1MB in-memory multipart threshold
        Arrays.fill(big, (byte) 7);
        big[big.length - 1] = 42;

        long id = upload(token, "big.bin", "application/octet-stream", big);

        byte[] returned = mockMvc.perform(get("/api/files/" + id + "/download").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(returned).isEqualTo(big);
    }

    @Test
    void upload_invalidRequests_areRejected_andLeaveNothingBehind() throws Exception {
        String token = newUserToken("alice@example.com");

        // empty file
        mockMvc.perform(multipart("/api/files").file(new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("File is empty"));
        // wrong part name
        mockMvc.perform(multipart("/api/files").file(new MockMultipartFile("document", "a.txt", "text/plain", "x".getBytes()))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());
        // JSON instead of multipart
        mockMvc.perform(post("/api/files").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isUnsupportedMediaType());

        assertThat(filesOnDisk()).isZero();
        assertThat(fileRepository.count()).isZero();
    }

    @Test
    void upload_withClientSuppliedOwnerId_ignoresIt() throws Exception {
        long aliceId = registerUser("alice@example.com");
        String bobToken = newUserToken("bob@example.com");

        mockMvc.perform(multipart("/api/files").file(new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes()))
                        .param("ownerId", String.valueOf(aliceId)).header("Authorization", bearer(bobToken)))
                .andExpect(status().isCreated());

        long bobId = userRepository.findByEmail("bob@example.com").orElseThrow().getId();
        assertThat(fileRepository.findAll()).singleElement().satisfies(f -> assertThat(f.getOwnerId()).isEqualTo(bobId));
    }

    @Test
    void anotherUser_cannotReadDownloadDeleteOrShare_andFileSurvives() throws Exception {
        String aliceToken = newUserToken("alice@example.com");
        String bobToken = newUserToken("bob@example.com");
        long aliceFile = upload(aliceToken, "secret.pdf", "application/pdf", PDF_BYTES);

        mockMvc.perform(get("/api/files/" + aliceFile).header("Authorization", bearer(bobToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/files/" + aliceFile + "/download").header("Authorization", bearer(bobToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/files/" + aliceFile + "/shares").header("Authorization", bearer(bobToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/files/" + aliceFile).header("Authorization", bearer(bobToken)))
                .andExpect(status().isForbidden());

        // Bob's list is empty, Alice's file is untouched, and no share link was created
        mockMvc.perform(get("/api/files").header("Authorization", bearer(bobToken)))
                .andExpect(jsonPath("$.totalElements").value(0));
        assertThat(fileRepository.existsById(aliceFile)).isTrue();
        assertThat(filesOnDisk()).isEqualTo(1);
        assertThat(shareLinkRepository.count()).isZero();
    }

    @Test
    void list_isPaginated_andNewestFirst() throws Exception {
        String token = newUserToken("alice@example.com");
        for (int i = 1; i <= 3; i++) {
            upload(token, "file" + i + ".txt", "text/plain", ("data" + i).getBytes());
            Thread.sleep(5);   // distinct upload timestamps
        }

        mockMvc.perform(get("/api/files").param("size", "2").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].originalFilename").value("file3.txt"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void download_inline_onlyForSafeTypes() throws Exception {
        String token = newUserToken("alice@example.com");
        long pdf = upload(token, "a.pdf", "application/pdf", PDF_BYTES);
        long html = upload(token, "a.html", "text/html", "<script>alert(1)</script>".getBytes());

        mockMvc.perform(get("/api/files/" + pdf + "/download").param("inline", "true")
                        .header("Authorization", bearer(token)))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline")));
        mockMvc.perform(get("/api/files/" + html + "/download").param("inline", "true")
                        .header("Authorization", bearer(token)))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")));
    }

    @Test
    void download_whenBytesMissingFromDisk_returns404() throws Exception {
        String token = newUserToken("alice@example.com");
        long id = upload(token, "a.txt", "text/plain", "x".getBytes());
        java.nio.file.Files.delete(UPLOAD_DIR.resolve(fileRepository.findById(id).orElseThrow().getStorageKey()));

        mockMvc.perform(get("/api/files/" + id + "/download").header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
    }
}
