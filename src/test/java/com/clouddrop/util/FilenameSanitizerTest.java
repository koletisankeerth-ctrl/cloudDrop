package com.clouddrop.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FilenameSanitizerTest {

    @Test
    void normalNameIsKept() {
        assertThat(FilenameSanitizer.sanitize("report final.pdf")).isEqualTo("report final.pdf");
    }

    @Test
    void pathsAreReducedToLastSegment() {
        assertThat(FilenameSanitizer.sanitize("../../etc/passwd")).isEqualTo("passwd");
        assertThat(FilenameSanitizer.sanitize("C:\\docs\\a.txt")).isEqualTo("a.txt");
    }

    @Test
    void controlCharactersAreRemoved() {
        assertThat(FilenameSanitizer.sanitize("a\r\nb\0.txt")).isEqualTo("ab.txt");
    }

    @Test
    void emptyOrDotNamesFallBack() {
        assertThat(FilenameSanitizer.sanitize(null)).isEqualTo("file");
        assertThat(FilenameSanitizer.sanitize("   ")).isEqualTo("file");
        assertThat(FilenameSanitizer.sanitize("..")).isEqualTo("file");
        assertThat(FilenameSanitizer.sanitize("folder/")).isEqualTo("file");
    }

    @Test
    void longNamesAreTruncatedTo255() {
        assertThat(FilenameSanitizer.sanitize("a".repeat(400))).hasSize(255);
    }
}
