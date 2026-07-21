package com.chat.talkMe.util;

import com.chat.talkMe.exception.ServiceException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Verifies an uploaded file's REAL content type from its magic bytes and rejects
 * anything that doesn't match the declared category. The client-supplied
 * Content-Type is never trusted (it's trivial to spoof), so a ".jpg" that is
 * actually an HTML/JS polyglot, or a disguised executable, is rejected before the
 * bytes are ever stored.
 *
 * <p>SVG is text (no binary signature); it's allowed for the image category only
 * when it contains no obvious active content. The serve endpoint additionally
 * sandboxes SVG (Content-Disposition: attachment + restrictive CSP) as
 * defense-in-depth.
 */
public final class UploadValidator {

    private UploadValidator() {
    }

    private enum Category {IMAGE, VIDEO, AUDIO, DOCUMENT, UNKNOWN}

    /**
     * @param file         the uploaded file
     * @param declaredType the client's category hint (image|video|audio|document|sticker|…)
     * @throws ServiceException 415 if the real content doesn't match the declared category
     */
    public static void validate(MultipartFile file, String declaredType) {
        Category expected = expectedCategory(declaredType);
        if (expected == Category.UNKNOWN) {
            return; // categories we don't strictly police (kept permissive on purpose)
        }

        byte[] head = readHead(file);
        if (head.length == 0) {
            throw new ServiceException(415, "Empty or unreadable file.", "TM_493");
        }

        // SVG (text) is a special case for the image category.
        if (expected == Category.IMAGE && looksLikeSvg(head)) {
            if (containsActiveSvgContent(file)) {
                throw new ServiceException(415, "SVG contains scripts and can't be uploaded.", "TM_494");
            }
            return;
        }

        Category detected = detect(head);
        if (detected != expected) {
            throw new ServiceException(415,
                    "File content does not match its type (expected " + expected.name().toLowerCase(Locale.ROOT) + ").",
                    "TM_495");
        }
    }

    private static Category expectedCategory(String declaredType) {
        if (declaredType == null) return Category.UNKNOWN;
        return switch (declaredType.toLowerCase(Locale.ROOT)) {
            case "image", "sticker" -> Category.IMAGE;
            case "video" -> Category.VIDEO;
            case "audio", "voice" -> Category.AUDIO;
            case "document", "file" -> Category.DOCUMENT;
            default -> Category.UNKNOWN;
        };
    }

    private static Category detect(byte[] b) {
        // ---- Images ----
        if (startsWith(b, 0xFF, 0xD8, 0xFF)) return Category.IMAGE;                       // JPEG
        if (startsWith(b, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return Category.IMAGE; // PNG
        if (startsWith(b, 0x47, 0x49, 0x46, 0x38)) return Category.IMAGE;                 // GIF87a/89a
        if (startsWith(b, 0x42, 0x4D)) return Category.IMAGE;                             // BMP
        if (isRiff(b, "WEBP")) return Category.IMAGE;                                     // WebP
        // ---- ISO-BMFF (ftyp box at offset 4): classify by brand so HEIC/AVIF photos,
        // M4A audio, and MP4/MOV video are told apart (they share the container). ----
        if (b.length >= 12 && b[4] == 0x66 && b[5] == 0x74 && b[6] == 0x79 && b[7] == 0x70) {
            Category c = classifyFtypBrand(new String(b, 8, 4, StandardCharsets.US_ASCII));
            if (c != Category.UNKNOWN) return c;
        }
        // ---- Video ----
        if (startsWith(b, 0x1A, 0x45, 0xDF, 0xA3)) return Category.VIDEO;                 // Matroska/WebM (EBML)
        if (isRiff(b, "AVI ")) return Category.VIDEO;                                     // AVI
        // ---- Audio ----
        if (startsWith(b, 0x49, 0x44, 0x33)) return Category.AUDIO;                       // MP3 (ID3)
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0) return Category.AUDIO; // MP3 frame sync
        if (startsWith(b, 0x4F, 0x67, 0x67, 0x53)) return Category.AUDIO;                 // OGG
        if (startsWith(b, 0x66, 0x4C, 0x61, 0x43)) return Category.AUDIO;                 // FLAC
        if (isRiff(b, "WAVE")) return Category.AUDIO;                                     // WAV
        // ---- Documents ----
        if (startsWith(b, 0x25, 0x50, 0x44, 0x46)) return Category.DOCUMENT;              // PDF
        return Category.UNKNOWN;
    }

    /**
     * Map an ISO-BMFF major brand to a media category (HEIC/AVIF=image, M4A=audio, else video).
     */
    private static Category classifyFtypBrand(String brand) {
        String x = brand.trim().toLowerCase(Locale.ROOT);
        return switch (x) {
            case "heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1", "avif", "avis" -> Category.IMAGE;
            case "m4a", "m4b", "m4p", "mp4a" -> Category.AUDIO;
            default -> Category.VIDEO; // isom/mp41/mp42/mmp4/M4V/qt/dash/… → video
        };
    }

    private static boolean startsWith(byte[] b, int... sig) {
        if (b.length < sig.length) return false;
        for (int i = 0; i < sig.length; i++) {
            if ((b[i] & 0xFF) != sig[i]) return false;
        }
        return true;
    }

    /**
     * RIFF container: "RIFF" at 0, 4-byte size, then the form type at offset 8.
     */
    private static boolean isRiff(byte[] b, String form) {
        if (b.length < 12) return false;
        if (!(b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46)) return false; // "RIFF"
        byte[] f = form.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < 4; i++) {
            if (b[8 + i] != f[i]) return false;
        }
        return true;
    }

    private static boolean looksLikeSvg(byte[] head) {
        String s = new String(head, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
        return s.startsWith("<?xml") || s.startsWith("<svg") || s.startsWith("<!doctype svg");
    }

    /**
     * Cheap scan for scriptable content in an SVG (defense-in-depth over the serve sandbox).
     */
    private static boolean containsActiveSvgContent(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            return content.contains("<script")
                    || content.contains("javascript:")
                    || content.contains("<foreignobject")
                    || content.matches("(?s).*\\son\\w+\\s*=.*"); // onload=, onclick=, …
        } catch (IOException e) {
            return true; // unreadable → treat as unsafe
        }
    }

    private static byte[] readHead(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            byte[] buf = new byte[64];
            int read = in.readNBytes(buf, 0, 64);
            if (read == 64) return buf;
            byte[] exact = new byte[read];
            System.arraycopy(buf, 0, exact, 0, read);
            return exact;
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
