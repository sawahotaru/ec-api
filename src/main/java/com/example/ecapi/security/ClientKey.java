package com.example.ecapi.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 「この要求は誰から来たか」を1本の文字列にする。資源の使いすぎを数える単位。
 *
 * <p>ログインしているならアカウント、していないなら接続元 IP。
 * <strong>認証済みを素通しにはしない</strong>——素通しにすると、登録して得たトークンで
 * 同じ攻撃ができてしまい、制限が「1回登録する手間」に化ける。
 *
 * <h2>IP を単位にすることについて</h2>
 * {@link LoginThrottle} は「IP は詐称できるし替えられる」として<strong>使わない</strong>が、
 * あちらが守るのは<em>アカウント</em>で、ここで守るのは<em>共有資源</em>（在庫・行数・CPU）。
 * 単位が違うので結論も違う:
 * <ul>
 *   <li>詐称・分散で抜けられても、1送信元あたりの上限は依然として効く。在庫のほうは
 *       「1商品の未払い引当は在庫の一定割合まで」という<strong>送信元に依存しない</strong>
 *       上限が別に入っているので、分散しても店は売り切れにならない。</li>
 *   <li>詐称が効くのはアプリに直接届く経路がある場合だけ。本番(VM)は Caddy 経由でしか
 *       到達できず、{@code FORWARD_HEADERS_STRATEGY=framework} で Spring が
 *       転送ヘッダから実クライアント IP を復元する。直接届く構成では
 *       {@code forward-headers-strategy} を既定の {@code none} のままにすること
 *       （そのときは {@code getRemoteAddr()} が実ピアを返す）。</li>
 * </ul>
 */
public final class ClientKey {

    /** {@link #current()} が「HTTP 要求の外」を表すときの値。 */
    public static final String INTERNAL = "internal";

    private ClientKey() {
    }

    /** 要求の送り主。ログイン済みなら {@code user:<email>}、未ログインなら {@code ip:<addr>}。 */
    public static String of(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            return "user:" + auth.getName();
        }
        String addr = request.getRemoteAddr();
        return "ip:" + (addr == null || addr.isBlank() ? "unknown" : addr);
    }

    /**
     * MVC のハンドラ引数を持たない層（サービス）から同じキーを得る。
     *
     * <p>要求の外（スケジューラ・テストの直接呼び出し）では {@code "internal"} を返す。
     * 「誰でもない呼び出し」を全部ひとつの枠に入れると裏の処理が自分で自分を締め出すので、
     * この枠は数えない側（呼び出し元が明示的に判定する）。
     */
    public static String current() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes servletAttributes) {
            return of(servletAttributes.getRequest());
        }
        return INTERNAL;
    }
}
