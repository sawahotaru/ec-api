package com.example.ecapi.security.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TOTP（RFC 6238）の検証。
 *
 * <p>自前で書いた暗号まわりは、<strong>自分の実装で自分の実装を確かめても意味が無い</strong>。
 * 「生成したコードが検証を通る」というテストは、両方が同じように間違っていても緑になる。
 * そこで <strong>RFC 6238 Appendix B の公式テストベクタ</strong>（仕様書に載っている
 * 既知の入力と出力）と突き合わせる。これに合えば、認証アプリ側とも必ず一致する。
 *
 * <p>あわせて、実装で意図的に選んだ挙動も固定する:
 * 時刻ずれの許容幅・全角数字の受け入れ・Base32 の往復。
 */
class TotpTest {

    /** RFC 6238 のテスト鍵 "12345678901234567890" を Base32 にしたもの。 */
    private static final String RFC_SECRET =
            Totp.base32Encode("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    @DisplayName("RFC 6238 の公式テストベクタと一致する（SHA1・6桁・30秒）")
    void matchesTheSpecTestVectors() {
        // Appendix B の表。左が時刻（秒）、右が期待されるコードの下6桁。
        // 仕様のサンプルは8桁なので、6桁実装では末尾6桁が一致する。
        assertThat(codeAt(59L)).isEqualTo("287082");
        assertThat(codeAt(1111111109L)).isEqualTo("081804");
        assertThat(codeAt(1111111111L)).isEqualTo("050471");
        assertThat(codeAt(1234567890L)).isEqualTo("005924");
        assertThat(codeAt(2000000000L)).isEqualTo("279037");
        assertThat(codeAt(20000000000L)).isEqualTo("353130");
    }

    private static String codeAt(long epochSeconds) {
        return Totp.generate(RFC_SECRET, epochSeconds / Totp.STEP_SECONDS);
    }

    /* ---------- 検証の窓 ---------- */

    @Test
    @DisplayName("いま生成したコードは通る")
    void currentCodeVerifies() {
        String secret = Totp.generateSecret();
        long step = Instant.now().getEpochSecond() / Totp.STEP_SECONDS;

        assertThat(Totp.verify(secret, Totp.generate(secret, step))).isTrue();
    }

    @Test
    @DisplayName("前後1ステップ（±30秒）まで許容する — 端末の時計はずれるので")
    void toleratesOneStepOfClockDrift() {
        String secret = Totp.generateSecret();
        long step = Instant.now().getEpochSecond() / Totp.STEP_SECONDS;

        assertThat(Totp.verify(secret, Totp.generate(secret, step - 1))).isTrue();
        assertThat(Totp.verify(secret, Totp.generate(secret, step + 1))).isTrue();
    }

    @Test
    @DisplayName("2ステップ以上ずれたコードは通さない（許容を広げすぎない）")
    void rejectsCodesTooFarOut() {
        String secret = Totp.generateSecret();
        long step = Instant.now().getEpochSecond() / Totp.STEP_SECONDS;

        assertThat(Totp.verify(secret, Totp.generate(secret, step - 2))).isFalse();
        assertThat(Totp.verify(secret, Totp.generate(secret, step + 2))).isFalse();
    }

    @Test
    @DisplayName("別の鍵で作ったコードは通らない")
    void rejectsCodeFromAnotherSecret() {
        String mine = Totp.generateSecret();
        String theirs = Totp.generateSecret();
        long step = Instant.now().getEpochSecond() / Totp.STEP_SECONDS;

        assertThat(Totp.verify(mine, Totp.generate(theirs, step))).isFalse();
    }

    @Test
    @DisplayName("空・null・桁数違いは通らない（例外にもしない）")
    void rejectsMalformedInput() {
        String secret = Totp.generateSecret();

        assertThat(Totp.verify(secret, null)).isFalse();
        assertThat(Totp.verify(secret, "")).isFalse();
        assertThat(Totp.verify(secret, "12345")).isFalse();
        assertThat(Totp.verify(secret, "1234567")).isFalse();
        assertThat(Totp.verify(null, "123456")).isFalse();
        assertThat(Totp.verify("", "123456")).isFalse();
    }

    /* ---------- 入力の正規化 ---------- */

    @Test
    @DisplayName("全角数字・空白・ハイフンを受け入れる（見た目が合っているのに弾かれない）")
    void normalisesRealWorldInput() {
        String secret = Totp.generateSecret();
        long step = Instant.now().getEpochSecond() / Totp.STEP_SECONDS;
        String code = Totp.generate(secret, step);

        // 日本語環境のスマホからは全角で入ることがある（clinic 側で実際に踏んだ）
        StringBuilder wide = new StringBuilder();
        for (char c : code.toCharArray()) {
            wide.append((char) ('０' + (c - '0')));
        }
        assertThat(Totp.verify(secret, wide.toString())).as("全角").isTrue();

        assertThat(Totp.verify(secret, code.substring(0, 3) + " " + code.substring(3))).as("空白").isTrue();
        assertThat(Totp.verify(secret, code.substring(0, 3) + "-" + code.substring(3))).as("ハイフン").isTrue();
        assertThat(Totp.verify(secret, "  " + code + "  ")).as("前後の空白").isTrue();
    }

    /* ---------- 鍵と URI ---------- */

    @Test
    @DisplayName("Base32 は往復する")
    void base32RoundTrips() {
        byte[] original = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

        assertThat(Totp.base32Decode(Totp.base32Encode(original))).isEqualTo(original);
    }

    @Test
    @DisplayName("Base32 でない鍵は例外にする（黙って通さない）")
    void rejectsNonBase32Secret() {
        assertThatThrownBy(() -> Totp.base32Decode("not-base32!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("生成した鍵は Base32 の文字だけで、毎回異なる")
    void secretsAreRandomBase32() {
        String a = Totp.generateSecret();
        String b = Totp.generateSecret();

        assertThat(a).matches("[A-Z2-7]{32}");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("otpauth URI に鍵とアルゴリズムが載り、記号はエスケープされる")
    void buildsOtpauthUri() {
        String uri = Totp.uri("JBSWY3DPEHPK3PXP", "admin@example.com", "EC API Demo");

        assertThat(uri).startsWith("otpauth://totp/");
        assertThat(uri).contains("secret=JBSWY3DPEHPK3PXP");
        assertThat(uri).contains("algorithm=SHA1").contains("digits=6").contains("period=30");
        // 空白や @ がそのまま入ると、アプリによっては読めない
        assertThat(uri).doesNotContain(" ");
        assertThat(uri).contains("EC%20API%20Demo");
    }
}
