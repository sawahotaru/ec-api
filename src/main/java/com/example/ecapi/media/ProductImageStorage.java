package com.example.ecapi.media;

import com.example.ecapi.exception.BadRequestException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Stores admin-uploaded product images on disk and hands back the relative URL
 * to put in {@code Product.imageUrl} (e.g. {@code images/uploads/p3-1a2b3c4d.jpg}).
 *
 * <p>Everything about an upload is attacker-controlled except what we decide here,
 * so three rules are non-negotiable:
 *
 * <ol>
 *   <li><b>The file type is decided by the bytes, not by the request.</b> The declared
 *       {@code Content-Type} and the filename extension are both trivially forged.
 *       Only JPEG / PNG / WebP are accepted. SVG is deliberately <i>not</i> accepted:
 *       it can carry script, and we serve these files from the site's own origin,
 *       which would turn an upload into stored XSS.</li>
 *   <li><b>The stored name is generated here.</b> The client filename never reaches
 *       the filesystem, so {@code ../../etc/passwd} and friends have nothing to act on.</li>
 *   <li><b>The extension is derived from the sniffed type.</b> The name we generate and
 *       the bytes we wrote always agree, so the static handler serves the right
 *       Content-Type (and Spring Security's {@code nosniff} keeps browsers from guessing).</li>
 * </ol>
 *
 * <p>Size is capped twice: by {@code spring.servlet.multipart.max-file-size} (the container
 * refuses to buffer more) and by an explicit check here, so the limit still holds if this
 * class is ever called from somewhere other than a multipart request.
 */
@Component
public class ProductImageStorage {

    private static final Logger log = LoggerFactory.getLogger(ProductImageStorage.class);

    /** Path prefix stored in {@code imageUrl}. Relative, so it survives the /ec sub-path. */
    public static final String URL_PREFIX = "images/uploads/";

    /** Enough bytes to recognise every signature below (WebP needs 12). */
    private static final int SNIFF_BYTES = 12;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path directory;
    private final long maxBytes;
    private final boolean available;

    public ProductImageStorage(@Value("${app.uploads.dir:./data/uploads}") String dir,
                               @Value("${app.uploads.max-bytes:2097152}") long maxBytes) {
        this.directory = Path.of(dir).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
        boolean ok;
        try {
            Files.createDirectories(directory);
            ok = Files.isWritable(directory);
            if (!ok) {
                log.error("Product image uploads disabled: {} exists but is not writable", directory);
            }
        } catch (IOException e) {
            ok = false;
            log.error("Product image uploads disabled: cannot create {} ({})", directory, e.toString());
        }
        this.available = ok;
    }

    /** Where the files live. The static resource handler serves {@link #URL_PREFIX} from here. */
    public Path directory() {
        return directory;
    }

    /** False when the directory could not be created or is read-only; the endpoint then reports 503. */
    public boolean isAvailable() {
        return available;
    }

    public long maxBytes() {
        return maxBytes;
    }

    /**
     * Validates and stores a product image.
     *
     * @return the relative URL to store in {@code Product.imageUrl}
     * @throws BadRequestException if the bytes are not a supported image, or too large
     */
    public String store(MultipartFile file, Long productId) {
        return store(file, "p" + productId);
    }

    /**
     * Validates and stores an upload under a caller-chosen name prefix.
     *
     * <p>商品画像以外（店のロゴなど）もここを通す。<strong>検証を1本に保つため</strong>で、
     * 用途ごとに保存処理を書くと、そのうちどれかが先頭バイトの判定や SVG の拒否を
     * 落としたまま増える——しかも通常の操作では気づけない。
     *
     * @param prefix 生成するファイル名の先頭（呼び出し側が決める。利用者の入力は混ぜない）
     */
    public String store(MultipartFile file, String prefix) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("画像ファイルが空です");
        }
        if (file.getSize() > maxBytes) {
            throw new BadRequestException("画像が大きすぎます（上限 " + (maxBytes / 1024) + " KB）");
        }

        byte[] head = readHead(file);
        ImageType type = ImageType.detect(head)
                .orElseThrow(() -> new BadRequestException(
                        "対応していない画像形式です（JPEG / PNG / WebP のみ）"));

        String filename = prefix + "-" + randomToken() + "." + type.extension();
        Path target = directory.resolve(filename);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store uploaded image", e);
        }

        // The multipart limit is the real guard, but a streamed request could in
        // principle report a smaller size than it delivers. Check what actually landed.
        try {
            if (Files.size(target) > maxBytes) {
                Files.deleteIfExists(target);
                throw new BadRequestException("画像が大きすぎます（上限 " + (maxBytes / 1024) + " KB）");
            }
        } catch (IOException e) {
            log.warn("Could not verify size of {}", target, e);
        }

        return URL_PREFIX + filename;
    }

    /**
     * Deletes a previously uploaded file.
     *
     * <p>Only URLs produced by {@link #store} are touched: the seeded catalog images
     * ({@code images/products/…}) ship inside the jar and external URLs are not ours,
     * so anything that does not carry our prefix is ignored rather than deleted.
     * The remainder is also required to be a plain filename, so a stored value of
     * {@code images/uploads/../../app.jar} cannot escape the directory.
     */
    public void deleteIfUploaded(String imageUrl) {
        String filename = uploadedFilename(imageUrl);
        if (filename == null) {
            return;
        }
        try {
            Files.deleteIfExists(directory.resolve(filename));
        } catch (IOException e) {
            log.warn("Could not delete product image {}", filename, e);
        }
    }

    /** True when this URL points at a file we stored (as opposed to a bundled or external image). */
    public boolean isUploaded(String imageUrl) {
        return uploadedFilename(imageUrl) != null;
    }

    private String uploadedFilename(String imageUrl) {
        if (imageUrl == null || !imageUrl.startsWith(URL_PREFIX)) {
            return null;
        }
        String rest = imageUrl.substring(URL_PREFIX.length());
        if (rest.isEmpty() || rest.contains("/") || rest.contains("\\") || rest.contains("..")) {
            return null;
        }
        return rest;
    }

    private byte[] readHead(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(SNIFF_BYTES);
        } catch (IOException e) {
            throw new BadRequestException("画像を読み取れませんでした");
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
