package com.example.ecapi;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 起動スモークテスト。Bean 配線（決済プロバイダの登録、注文イベントのディスパッチャ、
 * スケジューラ）が壊れていれば、ここで落ちる。
 */
@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "app.order.expiry-sweep-ms=3600000"
})
class EcApiApplicationTests {

    @Test
    void contextLoads() {
        // 起動できること自体がアサーション
    }
}
