package com.example.ecapi.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 決済側の記録は消さない（変えるのは状態だけ）という原則の点検。
 * Stripe の SDK を使ってよいのは {@code StripePaymentProvider} だけで、そのファイルに削除の操作を置かない。
 */
class StripeSdkConfinementTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final String PROVIDER = "StripePaymentProvider.java";

    @Test
    void onlyStripeProviderImportsTheSdk() throws IOException {
        List<Path> importing;
        try (Stream<Path> files = Files.walk(MAIN)) {
            importing = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> read(p).contains("import com.stripe") || read(p).contains("com.stripe."))
                    .toList();
        }
        assertThat(importing).hasSize(1);
        assertThat(importing.get(0).getFileName().toString()).isEqualTo(PROVIDER);
    }

    @Test
    void stripeProviderHasNoDeleteOperation() throws IOException {
        Path provider;
        try (Stream<Path> files = Files.walk(MAIN)) {
            provider = files.filter(p -> p.getFileName().toString().equals(PROVIDER)).findFirst().orElseThrow();
        }
        assertThat(read(provider).toLowerCase()).doesNotContain("delete");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
