package com.example.ecapi.security;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 起動時に「開発用の既定値のまま公開していないか」を確かめて警告する。
 *
 * <h2>なぜ必要か</h2>
 * このアプリは<strong>設定ゼロで動く</strong>ことを売りにしている（`docker run` 一発で
 * ストアフロントも Swagger も立ち上がる）。その代償として、<strong>何も設定しなくても
 * 起動してしまう</strong>——つまり、公開する場所へそのまま置いても何も起きない。
 *
 * <p>compose 側は {@code ${APP_JWT_SECRET:?…}} の形で「未設定なら起動しない」ように
 * できるが、それは compose を使った場合の話で、素の {@code docker run} や
 * {@code java -jar} には効かない。<strong>アプリ自身が言わないと誰も気付けない</strong>。
 *
 * <h2>止めずに警告にする理由</h2>
 * 既定値で起動できなくすると、「クローンして動かしたらすぐ触れる」という配布物としての
 * 価値が消える。壊れているのか設定が要るのか、初見では区別がつかない。
 * そこで<strong>動きはするが、無視できない大きさで言う</strong>形にした。
 *
 * <h2>WARN と INFO を分ける理由（ここが要点）</h2>
 * 「シードが有効＝デモ用アカウントが作られる」は、<strong>公開デモでは意図した設定</strong>で、
 * この構成では毎回必ず真になる。それを WARN で出すと<strong>常時鳴り続ける警告</strong>になり、
 * 数日で背景に溶けて、本当に危ない2つ（鍵とパスワード）まで読まれなくなる。
 * したがって:
 *
 * <ul>
 *   <li><b>WARN</b> — 設定し忘れ（署名鍵・管理者パスワードが既定のまま）。直すべきもの。</li>
 *   <li><b>INFO</b> — 意図しうる設定（シード有効）。事実として1行だけ伝える。</li>
 * </ul>
 *
 * <h2>秘密は出さない</h2>
 * 判定は「既定値と一致するか」だけで、値そのものはログに出さない。
 * 警告ログは平文で長期保存されるうえ、集約基盤へ送られることも多い。
 */
@Component
public class InsecureDefaultsCheck {

    private static final Logger log = LoggerFactory.getLogger(InsecureDefaultsCheck.class);

    /** application.yml の既定値。ここと一致していたら「設定されていない」と見なす。 */
    static final String DEFAULT_JWT_SECRET = "change-me-in-production-please-32bytes-minimum-secret";
    static final String DEFAULT_ADMIN_PASSWORD = "admin1234";
    /** compose の既定値。docker compose 経由でもここを通す。 */
    static final String COMPOSE_JWT_SECRET = "local-dev-secret-please-change-me-32bytes-minimum";
    /** DataSeeder が作るデモ用の買い手アカウント（README に載っている公開値）。 */
    static final String DEMO_USER_EMAIL = "user@example.com";

    private final String jwtSecret;
    private final String adminPassword;
    private final boolean seedEnabled;

    public InsecureDefaultsCheck(
            @Value("${app.jwt.secret}") String jwtSecret,
            @Value("${app.seed.admin-password}") String adminPassword,
            @Value("${app.seed.enabled}") boolean seedEnabled) {
        this.jwtSecret = jwtSecret;
        this.adminPassword = adminPassword;
        this.seedEnabled = seedEnabled;
    }

    /**
     * 設定し忘れ。危険な順に返す。<strong>差し替えれば空になる</strong>——
     * 消せない警告を出さないことが、この検査が読まれ続けるための条件。
     *
     * <p>ログ出力と分けてあるのはテストのため。ログ文字列を検査するテストは、文言を
     * 変えるたびに壊れるうえ、「何を危険と判断したか」を直接は確かめられない。
     */
    public List<String> insecureDefaults() {
        List<String> findings = new ArrayList<>();

        // 🔴 最優先。これが既定のままだと、パスワードをいくら強くしても意味が無い
        //    （任意のトークンを偽造できるので、そもそもログインが要らなくなる）。
        if (DEFAULT_JWT_SECRET.equals(jwtSecret) || COMPOSE_JWT_SECRET.equals(jwtSecret)) {
            findings.add("APP_JWT_SECRET が既定値のままです。"
                    + "この状態では誰でも管理者になりすますトークンを偽造できます"
                    + "（パスワードの強さは関係ありません）。"
                    + "生成例: openssl rand -base64 48");
        }

        // 🟠 README に載っている公開値。シードが無効ならそのアカウント自体が作られないので黙る。
        if (seedEnabled && DEFAULT_ADMIN_PASSWORD.equals(adminPassword)) {
            findings.add("APP_ADMIN_PASSWORD が既定値（README に記載の公開値）のままです。"
                    + "生成例: openssl rand -base64 24");
        }

        return findings;
    }

    /**
     * 意図しうる設定のうち、知らずに公開していると困るもの。
     *
     * <p>公開デモではこれが正しい設定なので、<strong>警告ではなく事実の告知</strong>として扱う。
     */
    public List<String> notices() {
        if (!seedEnabled) {
            return List.of();
        }
        return List.of("APP_SEED_ENABLED=true のため、デモ用の買い手アカウント "
                + DEMO_USER_EMAIL + "（パスワードは README に記載）が作られます。"
                + "権限は USER なので管理APIには届きませんが、実運用では "
                + "APP_SEED_ENABLED=false にしてください（デモ用のカタログも入らなくなります）。");
    }

    /**
     * 起動完了後に一度だけ出す。
     *
     * <p>起動<em>前</em>ではなく後にしているのは、Spring 自身の起動ログに埋もれさせないため。
     * 最後に出れば、端末をスクロールせずに見える位置に残る。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reportOnStartup() {
        List<String> defaults = insecureDefaults();
        if (!defaults.isEmpty()) {
            log.warn("""

                    ============================================================
                     ⚠️  開発用の既定値のまま起動しています（{} 件）
                    ------------------------------------------------------------
                    {}
                    ------------------------------------------------------------
                     ローカルでの検証なら、このままで問題ありません。
                     公開する場所へ置くなら .env.example を参照して差し替えてください。
                    ============================================================""",
                    defaults.size(),
                    String.join("\n", defaults.stream().map(f -> " • " + f).toList()));
        }
        notices().forEach(notice -> log.info("[setup] {}", notice));
    }
}
