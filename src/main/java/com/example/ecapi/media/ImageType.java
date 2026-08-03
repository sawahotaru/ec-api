package com.example.ecapi.media;

import java.util.Optional;

/**
 * The image formats an admin may upload, recognised by their leading bytes.
 *
 * <p>Kept deliberately short. Every format added here becomes a file the site
 * serves from its own origin, so the bar is "a browser renders it as a picture
 * and nothing else". That rules out SVG (XML that can carry script) and anything
 * whose safety depends on how the browser feels about it that day.
 */
public enum ImageType {

    JPEG("jpg"),
    PNG("png"),
    WEBP("webp");

    private final String extension;

    ImageType(String extension) {
        this.extension = extension;
    }

    public String extension() {
        return extension;
    }

    /**
     * @param head the first bytes of the file (at least 12 for WebP)
     * @return the format, or empty if the bytes are not one we accept
     */
    public static Optional<ImageType> detect(byte[] head) {
        if (head == null) {
            return Optional.empty();
        }
        // JPEG: FF D8 FF
        if (startsWith(head, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(JPEG);
        }
        // PNG: 89 "PNG" CR LF 1A LF
        if (startsWith(head, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(PNG);
        }
        // WebP: "RIFF" ....(size).... "WEBP"
        if (head.length >= 12
                && startsWith(head, 'R', 'I', 'F', 'F')
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] head, int... signature) {
        if (head.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((head[i] & 0xFF) != (signature[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }
}
