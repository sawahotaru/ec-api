package com.example.ecapi.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 起動時の既定値チェック。
 *
 * <p>Spring を起動せず、値を直接与えて判定だけを回す。ここで確かめたいのは
 * <strong>「何を危険と判断するか」</strong>であって、ログの文言ではない。
 * ログ文字列を検査するテストは、文面を直すたびに壊れるうえ、判定そのものは守れない。
 *
 * <p>いちばん大事なのは<strong>差し替えたら黙ること</strong>と、
 * <strong>意図した設定で警告を鳴らさないこと</strong>。常時鳴る警告は数日で背景になり、
 * 本当に危ないときにも読まれなくなる——それは警告が無いのと同じ。
 */
class InsecureDefaultsCheckTest {

    private static final String SAFE_SECRET = "a-real-secret-generated-with-openssl-rand-base64-48";
    private static final String SAFE_PASSWORD = "a-real-admin-password";

    @Test
    @DisplayName("すべて差し替え済み＋シード無効なら、何も言わない")
    void staysQuietWhenEverythingIsSet() {
        InsecureDefaultsCheck check = check(SAFE_SECRET, SAFE_PASSWORD, false);

        assertThat(check.insecureDefaults()).isEmpty();
        assertThat(check.notices()).isEmpty();
    }

    @Test
    @DisplayName("application.yml の既定 JWT 鍵を検出する")
    void detectsYamlDefaultSecret() {
        List<String> findings = check(InsecureDefaultsCheck.DEFAULT_JWT_SECRET, SAFE_PASSWORD, false)
                .insecureDefaults();

        assertThat(findings).singleElement().asString()
                .contains("APP_JWT_SECRET").contains("偽造");
    }

    @Test
    @DisplayName("compose の既定 JWT 鍵も検出する（yml とは別の値なので、片方だけだと漏れる）")
    void detectsComposeDefaultSecret() {
        assertThat(check(InsecureDefaultsCheck.COMPOSE_JWT_SECRET, SAFE_PASSWORD, false).insecureDefaults())
                .singleElement().asString().contains("APP_JWT_SECRET");
    }

    @Test
    @DisplayName("JWT 鍵の指摘が先頭に来る（パスワードより深刻なので）")
    void secretIsReportedFirst() {
        List<String> findings = check(InsecureDefaultsCheck.DEFAULT_JWT_SECRET,
                InsecureDefaultsCheck.DEFAULT_ADMIN_PASSWORD, true).insecureDefaults();

        assertThat(findings).hasSize(2);
        assertThat(findings.get(0)).contains("APP_JWT_SECRET");
        assertThat(findings.get(1)).contains("APP_ADMIN_PASSWORD");
    }

    @Test
    @DisplayName("シードが無効なら、管理者パスワードが既定でも言わない（そのアカウントは作られない）")
    void adminPasswordIsIrrelevantWithoutSeeding() {
        assertThat(check(SAFE_SECRET, InsecureDefaultsCheck.DEFAULT_ADMIN_PASSWORD, false).insecureDefaults())
                .isEmpty();
    }

    /* ---------- 常時鳴る警告を作らない ---------- */

    @Test
    @DisplayName("公開デモ相当の設定（鍵とPWは差し替え済み・シード有効）で警告は0件")
    void deliberateDemoSetupRaisesNoWarning() {
        InsecureDefaultsCheck check = check(SAFE_SECRET, SAFE_PASSWORD, true);

        // ここが 0 でないと、本番でデプロイのたびに警告が出続けて誰も読まなくなる
        assertThat(check.insecureDefaults()).isEmpty();
        // 事実としては伝える（ただし INFO 扱い）
        assertThat(check.notices()).singleElement().asString()
                .contains(InsecureDefaultsCheck.DEMO_USER_EMAIL)
                .contains("APP_SEED_ENABLED=false");
    }

    @Test
    @DisplayName("シードが無効なら告知も出ない")
    void noNoticeWithoutSeeding() {
        assertThat(check(SAFE_SECRET, SAFE_PASSWORD, false).notices()).isEmpty();
    }

    /* ---------- 値そのものは出さない ---------- */

    @Test
    @DisplayName("指摘に秘密の値そのものを含めない（警告ログは平文で長期保存される）")
    void neverEchoesTheActualValues() {
        InsecureDefaultsCheck check = check(InsecureDefaultsCheck.DEFAULT_JWT_SECRET,
                InsecureDefaultsCheck.DEFAULT_ADMIN_PASSWORD, true);

        List<String> all = new java.util.ArrayList<>(check.insecureDefaults());
        all.addAll(check.notices());

        // 既定値であっても、値そのものは書かない（設定名と対処だけを伝える）
        assertThat(all).allSatisfy(f -> {
            assertThat(f).doesNotContain(InsecureDefaultsCheck.DEFAULT_JWT_SECRET);
            assertThat(f).doesNotContain(InsecureDefaultsCheck.COMPOSE_JWT_SECRET);
            assertThat(f).doesNotContain(InsecureDefaultsCheck.DEFAULT_ADMIN_PASSWORD);
        });
    }

    @Test
    @DisplayName("出力そのものは例外を投げない（起動を妨げない）")
    void reportingNeverBreaksStartup() {
        check(InsecureDefaultsCheck.DEFAULT_JWT_SECRET,
                InsecureDefaultsCheck.DEFAULT_ADMIN_PASSWORD, true).reportOnStartup();
        check(SAFE_SECRET, SAFE_PASSWORD, false).reportOnStartup();
    }

    private static InsecureDefaultsCheck check(String secret, String adminPassword, boolean seedEnabled) {
        return new InsecureDefaultsCheck(secret, adminPassword, seedEnabled);
    }
}
