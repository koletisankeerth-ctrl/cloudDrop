package com.clouddrop.util;

/**
 * Cleans a client-supplied filename before it is stored as METADATA. The name is never used as a
 * filesystem path (storage keys are server-generated UUIDs); this just keeps odd values out of the
 * database and later out of Content-Disposition headers.
 */
public final class FilenameSanitizer {

    private static final int MAX_LENGTH = 255;
    private static final String FALLBACK = "file";

    private FilenameSanitizer() {
    }

    public static String sanitize(String filename) {
        if (filename == null) {
            return FALLBACK;
        }
        // Some browsers send a full client path ("C:\docs\a.txt"): keep only the last segment.
        String name = filename.substring(Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\')) + 1);
        // Drop control characters (newlines, NUL, ...) which can break headers and logs.
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.length() > MAX_LENGTH) {
            name = name.substring(0, MAX_LENGTH);
        }
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return FALLBACK;
        }
        return name;
    }
}
