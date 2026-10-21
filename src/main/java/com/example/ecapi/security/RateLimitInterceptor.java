package com.example.ecapi.security;

import com.example.ecapi.exception.TooManyAttemptsException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 送信元ごとに「状態を変える要求」の回数を制限する門番。
 *
 * <h2>なぜ要るのか</h2>
 * 読み取り専用デモ（{@link com.example.ecapi.privacy.DemoReadOnlyInterceptor}）は
 * <strong>{@code /api/admin/**} しか見ていない</strong>。買い物側は「作られるデータは作った本人のもの」
 * として意図的に素通しにしてあったが、これは成り立っていなかった:
 * <ul>
 *   <li>{@code POST /api/orders/guest-checkout} は未認証で在庫を引き当てる。公開カタログが
 *       {@code available} を正確に返すので、その数ぴったりを押さえれば<strong>店全体が売り切れ表示</strong>
 *       になる。引当は30分で解けるが、30分おきに1要求で恒久的に維持できる。</li>
 *   <li>同じ口から {@code orders} / {@code order_items} が無制限に増える。</li>
 *   <li>{@code POST /api/auth/register} から {@code users} が無制限に増える。</li>
 * </ul>
 * どれも「自分のデータ」ではなく<strong>共有資源</strong>（在庫・ディスク）を消費する。
 *
 * <h2>書き漏らしがどちらに倒れるか</h2>
 * {@link com.example.ecapi.privacy.DemoReadOnlyInterceptor} は「塞ぐ先の一覧を持たない」形にして
 * あるが、こちらは枠ごとに上限が違うので表を持たざるを得ない。そこで
 * <strong>表に無いものは既定の枠に入る</strong>構造にした。新しい書き込みAPIを足して表に書き忘れても
 * 無制限にはならない（既定の上限が効く）。表に書くのは「既定より緩めたい／締めたい」ときだけ。
 *
 * <h2>対象</h2>
 * <ul>
 *   <li><b>GET/HEAD/OPTIONS は数えない。</b> 閲覧はデモの目的そのもので、ここで誤爆すると
 *       店が見られなくなる。読み取りの洪水はエッジ（Cloudflare / Caddy）の仕事。</li>
 *   <li><b>Webhook は除外。</b> 決済事業者から来る未認証の正当な要求で、認証は署名検証が担う。
 *       ここで 429 を返すと支払い確認を落とす。除外は
 *       {@link com.example.ecapi.config.WebMvcConfig} の登録側に書いてある。</li>
 *   <li><b>ログイン済みも数える。</b> 素通しにすると「1回登録してトークンを取る」だけで
 *       制限が消える。キーの作り方は {@link ClientKey}。</li>
 * </ul>
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final AntPathMatcher matcher = new AntPathMatcher();
    private final RequestRateLimiter limiter;
    private final RateLimitProperties limits;
    private final List<Rule> rules;

    public RateLimitInterceptor(RequestRateLimiter limiter, RateLimitProperties limits) {
        this.limiter = limiter;
        this.limits = limits;
        // 上から順に最初に当たったものが効く。当たらなければ既定の枠。
        this.rules = List.of(
                new Rule("register", "/api/auth/register", limits::register, limits::registerWindow),
                new Rule("checkout", "/api/orders/checkout", limits::checkout, limits::window),
                new Rule("checkout", "/api/orders/guest-checkout", limits::checkout, limits::window),
                new Rule("quote", "/api/checkout/**", limits::quote, limits::window));
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!limits.isEnabled() || SAFE_METHODS.contains(request.getMethod())) {
            return true;
        }
        Rule rule = ruleFor(path(request));
        long retryAfter = limiter.acquire(rule.bucket(), ClientKey.of(request),
                rule.limit().getAsInt(), rule.window().get());
        if (retryAfter == 0) {
            return true;
        }
        // Retry-After は「いつ戻ってよいか」を機械にも人にも伝える唯一の手段。
        // これが無いと、まともなクライアントほど即座に再試行して自分で窓を延ばす。
        response.setHeader("Retry-After", Long.toString(retryAfter));
        throw new TooManyAttemptsException(
                "短時間に操作が集中したため、一時的に受け付けを制限しています。"
                        + "約" + Math.max(1, retryAfter / 60 + 1) + "分後に再度お試しください。",
                retryAfter);
    }

    private Rule ruleFor(String path) {
        for (Rule rule : rules) {
            if (matcher.match(rule.pattern(), path)) {
                return rule;
            }
        }
        return new Rule("default", "/**", limits::fallback, limits::window);
    }

    /** context-path（本番は {@code /ec}）を落としてアプリ内のパスにする。 */
    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context)
                ? uri.substring(context.length())
                : uri;
    }

    private record Rule(String bucket, String pattern,
                        java.util.function.IntSupplier limit,
                        java.util.function.Supplier<Duration> window) {
    }
}
