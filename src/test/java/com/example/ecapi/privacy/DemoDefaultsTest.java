package com.example.ecapi.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * デモ向けの制限は<strong>3つとも既定で無効</strong>であること。
 *
 * <p>この確認が要るのは、ec-api が<strong>配布物でもある</strong>ため。持ち出した人の環境で
 * 説明も無く管理機能が使えなかったり連絡先が読めなかったりするのは、機能欠陥にしか見えない。
 * 「安全側に倒す」判断がここでは逆に働く——制限は公開デモ（oracle-lab の env）でだけ
 * 明示的に有効にする。
 */
@SpringBootTest(properties = {"app.seed.enabled=false", "app.order.expiry-sweep-ms=3600000"})
class DemoDefaultsTest {

    @Autowired DemoProperties demo;

    @Test
    @DisplayName("何も設定しなければ、デモ制限はすべて無効")
    void allRestrictionsAreOffByDefault() {
        assertThat(demo.isReadOnly()).isFalse();
        assertThat(demo.isMaskContact()).isFalse();
        assertThat(demo.retentionDays()).isZero();
    }
}
