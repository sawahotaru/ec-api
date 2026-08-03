package com.example.ecapi.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ecapi.exception.BadRequestException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 商品画像アップロードの検証ロジック。Spring は起動しない素のユニットテスト。
 *
 * <p>ここで固定したいのは「通ること」ではなく<strong>通らないこと</strong>のほう。
 * アップロードは、拡張子も Content-Type もファイル名も**全部が攻撃者の申告**という
 * 数少ない入口で、しかも保存先を自分のオリジンから配信する。だから
 *
 * <ul>
 *   <li>拡張子や Content-Type を偽ってもバイト列で弾かれること</li>
 *   <li>SVG が通らないこと（＝保存型XSSの経路にならない）</li>
 *   <li>ファイル名がこちらの生成であること（＝パストラバーサルの材料が無い）</li>
 *   <li>消してよいのは自分が置いたファイルだけであること</li>
 * </ul>
 *
 * <p>を落とさない。1つでも緩むと「動くけれど危ない」状態になり、通常の動作確認では見えない。
 */
class ProductImageStorageTest {

    @TempDir
    Path uploadDir;

    private ProductImageStorage storage;

    @BeforeEach
    void setUp() {
        storage = new ProductImageStorage(uploadDir.toString(), 2 * 1024 * 1024);
    }

    /* ---------- 受け入れる ---------- */

    @Test
    @DisplayName("PNG は保存され、imageUrl は images/uploads/ 配下の相対パスになる")
    void storesPng() throws IOException {
        String url = storage.store(pngUpload("photo.png"), 7L);

        assertThat(url).startsWith("images/uploads/p7-").endsWith(".png");
        // 相対パスであること。/ec 配下に置かれてもフロントの BASE 解決で正しく引ける。
        assertThat(url).doesNotStartWith("/");
        assertThat(uploadDir.resolve(url.substring("images/uploads/".length()))).exists();
    }

    @Test
    @DisplayName("JPEG も保存できる")
    void storesJpeg() throws IOException {
        String url = storage.store(jpegUpload("photo.jpg"), 1L);
        assertThat(url).endsWith(".jpg");
        assertThat(uploadDir.resolve(url.substring("images/uploads/".length()))).exists();
    }

    @Test
    @DisplayName("WebP は先頭 RIFF....WEBP で判定される")
    void storesWebp() {
        byte[] webp = new byte[32];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, webp, 0, 4);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, webp, 8, 4);

        String url = storage.store(new MockMultipartFile("file", "x.webp", "image/webp", webp), 2L);
        assertThat(url).endsWith(".webp");
    }

    /* ---------- 拒否する ---------- */

    @Test
    @DisplayName("拡張子と Content-Type を画像と偽っても、中身がテキストなら弾く")
    void rejectsTextDisguisedAsImage() {
        MockMultipartFile fake = new MockMultipartFile(
                "file", "totally-a-photo.jpg", "image/jpeg",
                "<?php system($_GET['c']); ?>".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> storage.store(fake, 1L))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("対応していない画像形式");
        assertThat(filesIn(uploadDir)).isEmpty();
    }

    @Test
    @DisplayName("SVG は拒否する（同一オリジンから配信すると保存型XSSになる）")
    void rejectsSvg() {
        MockMultipartFile svg = new MockMultipartFile(
                "file", "logo.svg", "image/svg+xml",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                        .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> storage.store(svg, 1L)).isInstanceOf(BadRequestException.class);
        assertThat(filesIn(uploadDir)).isEmpty();
    }

    @Test
    @DisplayName("上限を超えるファイルは弾き、保存もしない")
    void rejectsOversized() throws IOException {
        ProductImageStorage small = new ProductImageStorage(uploadDir.toString(), 1024);
        byte[] png = pngBytes();
        byte[] padded = new byte[4096];
        System.arraycopy(png, 0, padded, 0, png.length);

        assertThatThrownBy(() -> small.store(
                new MockMultipartFile("file", "big.png", "image/png", padded), 1L))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("大きすぎます");
        assertThat(filesIn(uploadDir)).isEmpty();
    }

    @Test
    @DisplayName("空のファイルは弾く")
    void rejectsEmpty() {
        assertThatThrownBy(() -> storage.store(
                new MockMultipartFile("file", "e.png", "image/png", new byte[0]), 1L))
                .isInstanceOf(BadRequestException.class);
    }

    /* ---------- ファイル名は必ずこちらが決める ---------- */

    @Test
    @DisplayName("クライアントのファイル名は保存名に一切使われない（パストラバーサル不可）")
    void ignoresClientFilename() throws IOException {
        String url = storage.store(
                new MockMultipartFile("file", "../../../etc/passwd.png", "image/png", pngBytes()), 3L);

        String filename = url.substring("images/uploads/".length());
        assertThat(filename).matches("p3-[0-9a-f]{8}\\.png");
        // 保存先は uploadDir 直下ただ1つ。上位ディレクトリには何も生まれていない。
        assertThat(filesIn(uploadDir)).containsExactly(filename);
    }

    @Test
    @DisplayName("同じ商品に2回上げても衝突せず別名になる")
    void generatesDistinctNames() throws IOException {
        String first = storage.store(pngUpload("a.png"), 5L);
        String second = storage.store(pngUpload("a.png"), 5L);
        assertThat(first).isNotEqualTo(second);
        assertThat(filesIn(uploadDir)).hasSize(2);
    }

    /* ---------- 消してよいのは自分が置いたものだけ ---------- */

    @Test
    @DisplayName("アップロードした画像は削除できる")
    void deletesUploadedFile() throws IOException {
        String url = storage.store(pngUpload("a.png"), 1L);
        assertThat(storage.isUploaded(url)).isTrue();

        storage.deleteIfUploaded(url);
        assertThat(filesIn(uploadDir)).isEmpty();
    }

    @Test
    @DisplayName("同梱画像・外部URL・null は「自分のもの」ではないので触らない")
    void leavesForeignUrlsAlone() {
        for (String url : List.of("images/products/matcha.jpg", "https://example.com/a.png", "/x.png")) {
            assertThat(storage.isUploaded(url)).as(url).isFalse();
            storage.deleteIfUploaded(url); // 例外を投げずに素通りする
        }
        assertThat(storage.isUploaded(null)).isFalse();
        storage.deleteIfUploaded(null);
    }

    @Test
    @DisplayName("prefix さえ合っていれば消せる、にはなっていない（DB値が汚染されても外へ出ない）")
    void refusesToDeleteOutsideTheDirectory() throws IOException {
        Path sibling = uploadDir.getParent().resolve("keep-me.txt");
        Files.writeString(sibling, "important");

        // 「images/uploads/」で始まってはいるが、残りがファイル名ではない値
        storage.deleteIfUploaded("images/uploads/../keep-me.txt");
        storage.deleteIfUploaded("images/uploads/sub/dir.png");

        assertThat(sibling).exists();
        assertThat(storage.isUploaded("images/uploads/../keep-me.txt")).isFalse();
    }

    /* ---------- 使えないときは正直に使えないと言う ---------- */

    @Test
    @DisplayName("保存先を作れなければ available=false になる（起動は妨げない）")
    void reportsUnavailableWhenDirectoryCannotBeCreated() throws IOException {
        // ディレクトリを作ろうとした先が「ファイル」なら createDirectories は失敗する
        Path blocker = uploadDir.resolve("blocked");
        Files.writeString(blocker, "not a directory");

        ProductImageStorage broken = new ProductImageStorage(blocker.resolve("uploads").toString(), 1024);
        assertThat(broken.isAvailable()).isFalse();
    }

    /* ---------- helpers ---------- */

    private MockMultipartFile pngUpload(String name) throws IOException {
        return new MockMultipartFile("file", name, "image/png", pngBytes());
    }

    private MockMultipartFile jpegUpload(String name) throws IOException {
        return new MockMultipartFile("file", name, "image/jpeg", imageBytes("jpg"));
    }

    private static byte[] pngBytes() throws IOException {
        return imageBytes("png");
    }

    /** 本物のエンコーダで作る（マジックバイトを手書きすると、テストだけが通る形式になりうる）。 */
    private static byte[] imageBytes(String format) throws IOException {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static List<String> filesIn(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
