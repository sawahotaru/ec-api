package com.example.ecapi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecapi.domain.Product;
import com.example.ecapi.media.ProductImageStorage;
import com.example.ecapi.repository.ProductRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 商品と画像ファイルの寿命が一致することを固定する。
 *
 * <p>アップロードは「保存できる」だけでは足りない。DB の {@code imageUrl} と実ファイルは
 * 別々の場所にあるので、放っておくと<strong>片方だけが残る</strong>:
 * 差し替えれば古いファイルが誰からも参照されないまま残り、商品を消せば画像だけが残る。
 * 公開デモの容量は Always Free の 1VM 分しかないため、これは実際に効いてくる。
 *
 * <p>逆に、同梱画像（{@code images/products/…}）や外部URLを指している商品を消したときに
 * ファイル削除が走ってはいけない。そちらは jar の中身であって、こちらの持ち物ではない。
 */
@SpringBootTest
class ProductImageServiceTest {

    private static Path uploadDir;

    @DynamicPropertySource
    static void uploads(DynamicPropertyRegistry registry) throws IOException {
        uploadDir = Files.createTempDirectory("ec-api-uploads-test");
        registry.add("app.uploads.dir", () -> uploadDir.toString());
    }

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductImageStorage storage;

    @AfterEach
    void cleanUp() throws IOException {
        try (var files = Files.list(uploadDir)) {
            for (Path p : files.toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    @Test
    @DisplayName("アップロードすると imageUrl が更新され、実ファイルが置かれる")
    void uploadSetsImageUrl() throws IOException {
        Product product = newProduct(null);

        Product updated = productService.setImage(product.getId(), pngUpload());

        assertThat(updated.getImageUrl()).startsWith(ProductImageStorage.URL_PREFIX);
        assertThat(fileFor(updated.getImageUrl())).exists();
        assertThat(productRepository.findById(product.getId()).orElseThrow().getImageUrl())
                .isEqualTo(updated.getImageUrl());
    }

    @Test
    @DisplayName("差し替えると古いファイルは消える（参照されない孤児を残さない）")
    void replacingDeletesThePreviousFile() throws IOException {
        Product product = newProduct(null);
        String first = productService.setImage(product.getId(), pngUpload()).getImageUrl();

        String second = productService.setImage(product.getId(), pngUpload()).getImageUrl();

        assertThat(second).isNotEqualTo(first);
        assertThat(fileFor(first)).doesNotExist();
        assertThat(fileFor(second)).exists();
    }

    @Test
    @DisplayName("画像を外すと imageUrl は空になり、ファイルも消える")
    void clearingRemovesBoth() throws IOException {
        Product product = newProduct(null);
        String url = productService.setImage(product.getId(), pngUpload()).getImageUrl();

        Product cleared = productService.clearImage(product.getId());

        assertThat(cleared.getImageUrl()).isNull();
        assertThat(fileFor(url)).doesNotExist();
    }

    @Test
    @DisplayName("商品を削除するとアップロード画像も消える")
    void deletingProductRemovesItsUpload() throws IOException {
        Product product = newProduct(null);
        String url = productService.setImage(product.getId(), pngUpload()).getImageUrl();

        productService.delete(product.getId());

        assertThat(fileFor(url)).doesNotExist();
    }

    @Test
    @DisplayName("同梱画像を指している商品を消しても、ファイル削除は走らない")
    void deletingProductWithBundledImageTouchesNothing() throws IOException {
        Product product = newProduct("images/products/matcha.jpg");
        // 誰かの本物のアップロードが同じディレクトリにある状態で
        Product other = newProduct(null);
        String survivor = productService.setImage(other.getId(), pngUpload()).getImageUrl();

        productService.delete(product.getId());

        assertThat(fileFor(survivor)).exists();
        assertThat(storage.isUploaded("images/products/matcha.jpg")).isFalse();
    }

    private Product newProduct(String imageUrl) {
        Product product = new Product();
        product.setName("テスト商品");
        product.setPrice(new BigDecimal("1000"));
        product.setStock(5);
        product.setImageUrl(imageUrl);
        return productRepository.save(product);
    }

    private Path fileFor(String imageUrl) {
        return uploadDir.resolve(imageUrl.substring(ProductImageStorage.URL_PREFIX.length()));
    }

    private static MockMultipartFile pngUpload() throws IOException {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new MockMultipartFile("file", "photo.png", "image/png", out.toByteArray());
    }
}
