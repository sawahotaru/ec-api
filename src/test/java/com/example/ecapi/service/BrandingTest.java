package com.example.ecapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ecapi.exception.BadRequestException;
import com.example.ecapi.media.ProductImageStorage;
import com.example.ecapi.repository.AppSettingRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * ヘッダの看板（店名とロゴ）。
 *
 * <p>固定したいのは「保存できる」ことより、<strong>ロゴが無い店でもヘッダが空にならない</strong>
 * ことと、<strong>一度上げたロゴを外して文字看板へ戻せる</strong>こと。
 * ロゴを外す手段が無いと、試しに上げた画像から二度と戻れなくなる。
 *
 * <p>アップロードの検証は商品画像と同じ {@link ProductImageStorage} を通す。
 * 用途ごとに保存処理を書き分けると、そのうちどれかが先頭バイトの判定や SVG の拒否を
 * 落としたまま増える——ここではその共通化が効いていることも確かめる。
 */
@SpringBootTest(properties = {"app.seed.enabled=false", "app.order.expiry-sweep-ms=3600000"})
class BrandingTest {

    private static Path uploadDir;

    @DynamicPropertySource
    static void uploads(DynamicPropertyRegistry registry) throws IOException {
        uploadDir = Files.createTempDirectory("ec-api-branding-test");
        registry.add("app.uploads.dir", () -> uploadDir.toString());
    }

    @Autowired BrandingSettings branding;
    @Autowired ProductImageStorage storage;
    @Autowired AppSettingRepository settings;

    @BeforeEach
    void reset() throws IOException {
        settings.deleteAll();
        try (var files = Files.list(uploadDir)) {
            for (Path p : files.toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /* ---------- 既定 ---------- */

    @Test
    @DisplayName("何も設定していなければ、同梱のロゴと既定の店名を返す")
    void fallsBackToTheBundledBranding() {
        assertThat(branding.name()).isEqualTo("和雑貨 みやび");
        assertThat(branding.logoUrl()).isEqualTo("images/brand/miyabi-logo-premium.svg");
    }

    /* ---------- 店名 ---------- */

    @Test
    @DisplayName("店名を変えると以後はDBの値が優先される")
    void storedNameWins() {
        branding.setName("  テスト商店  ");

        assertThat(branding.name()).isEqualTo("テスト商店");   // 前後の空白は落とす
    }

    @Test
    @DisplayName("店名は空にできない（ロゴが無い店ではヘッダが空になるため）")
    void nameCannotBeBlank() {
        assertThatThrownBy(() -> branding.setName("   "))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("店名");
        assertThatThrownBy(() -> branding.setName(null)).isInstanceOf(BadRequestException.class);
    }

    /* ---------- ロゴ ---------- */

    @Test
    @DisplayName("ロゴを外すと空文字になり、既定値へ戻らない（「外した」という意思を打ち消さない）")
    void removingTheLogoIsRemembered() {
        branding.setLogoUrl("");

        assertThat(branding.logoUrl()).isEmpty();
    }

    @Test
    @DisplayName("ロゴをアップロードすると images/uploads/logo-… になる")
    void uploadedLogoGetsItsOwnPrefix() throws IOException {
        String url = storage.store(pngUpload(), "logo");

        assertThat(url).startsWith("images/uploads/logo-");
        assertThat(url).matches("images/uploads/logo-[0-9a-f]{8}\\.png");
        assertThat(uploadDir.resolve(url.substring("images/uploads/".length()))).exists();
    }

    @Test
    @DisplayName("ロゴでも SVG は拒否される（同梱の既定ロゴが SVG でも、アップロードは別扱い）")
    void svgIsRejectedForLogosToo() {
        MockMultipartFile svg = new MockMultipartFile("file", "logo.svg", "image/svg+xml",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                        .getBytes(StandardCharsets.UTF_8));

        // 同梱物は jar の中身だが、アップロードは自オリジンから配信する利用者の入力＝保存型XSSの経路
        assertThatThrownBy(() -> storage.store(svg, "logo"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("対応していない画像形式");
    }

    @Test
    @DisplayName("同梱ロゴは「自分が置いたファイル」ではないので、外しても削除されない")
    void bundledLogoIsNeverDeleted() {
        assertThat(storage.isUploaded("images/brand/miyabi-logo-premium.svg")).isFalse();

        storage.deleteIfUploaded("images/brand/miyabi-logo-premium.svg");   // 例外を投げずに素通り
    }

    @Test
    @DisplayName("差し替えると古いロゴのファイルは消える")
    void replacingDeletesThePreviousLogo() throws IOException {
        String first = storage.store(pngUpload(), "logo");
        branding.setLogoUrl(first);

        String second = storage.store(pngUpload(), "logo");
        storage.deleteIfUploaded(first);
        branding.setLogoUrl(second);

        assertThat(uploadDir.resolve(first.substring("images/uploads/".length()))).doesNotExist();
        assertThat(uploadDir.resolve(second.substring("images/uploads/".length()))).exists();
        assertThat(branding.logoUrl()).isEqualTo(second);
    }

    /* ---------- helpers ---------- */

    private static MockMultipartFile pngUpload() throws IOException {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new MockMultipartFile("file", "logo.png", "image/png", out.toByteArray());
    }
}
