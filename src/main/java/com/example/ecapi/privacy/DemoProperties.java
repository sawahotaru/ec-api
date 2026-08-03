package com.example.ecapi.privacy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 「これは公開デモである」ことに由来する制限。
 *
 * <p><strong>3つとも既定は無効</strong>。配布物として持ち出した人の環境で、説明も無く
 * 管理機能が使えなかったり連絡先が読めなかったりするのは、機能欠陥にしか見えない。
 * 公開デモ（oracle-lab）側で明示的に有効にする。
 *
 * <h2>なぜ「管理画面を隠す」ではなく「壊せなくする」なのか</h2>
 * この管理画面は<strong>展示物そのもの</strong>（税率の有効期間管理・送料・クーポン・売上集計は
 * ここにしか無い）。隠すと見せたいものが見えなくなるうえ、隠蔽は破られたときに何も残らない。
 * かわりに<strong>盗む価値のあるものを置かず、壊せる操作を与えない</strong>方向で守る。
 * 公開されている商用ECのデモが軒並みこの形（資格情報は公開、データは使い捨てか読み取り専用）
 * なのも同じ理由による。
 */
@Component
public class DemoProperties {

    private final boolean readOnly;
    private final boolean maskContact;
    private final int retentionDays;

    public DemoProperties(
            @Value("${app.demo.read-only:false}") boolean readOnly,
            @Value("${app.demo.mask-contact:false}") boolean maskContact,
            @Value("${app.demo.retention-days:0}") int retentionDays) {
        this.readOnly = readOnly;
        this.maskContact = maskContact;
        this.retentionDays = retentionDays;
    }

    /** true なら {@code /api/admin/**} への書き込み（POST/PUT/PATCH/DELETE）を拒否する。 */
    public boolean isReadOnly() {
        return readOnly;
    }

    /** true なら管理画面の応答で連絡先メールを伏せ字にする。 */
    public boolean isMaskContact() {
        return maskContact;
    }

    /** 何日経った注文の連絡先を消すか。0 なら消さない。 */
    public int retentionDays() {
        return retentionDays;
    }
}
