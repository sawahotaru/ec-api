package com.example.ecapi.privacy;

import com.example.ecapi.exception.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 読み取り専用デモ: {@code /api/admin/**} への<strong>状態を変える要求</strong>を拒否する。
 *
 * <p>買い物側（カート・注文・決済）は止めない。止めたらデモとして意味が無いし、
 * そちらで作られるデータは<strong>作った本人のもの</strong>で、他人の何かを壊さない。
 * 危ないのは管理側で、商品の削除・価格変更・注文状態の書き換え、そして
 * <strong>サーバーへのファイル書き込み</strong>（商品画像アップロード）がここに集まっている。
 *
 * <p>判定を「メソッド名」で行い、エンドポイントの一覧で行わないのは意図的。
 * 一覧方式は<strong>新しい管理APIを足したときに書き漏らす</strong>——そして書き漏らしても
 * 何も起きないので気付けない。「GET 以外は通さない」なら、足し忘れる側が安全に倒れる。
 */
@Component
public class DemoReadOnlyInterceptor implements HandlerInterceptor {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final DemoProperties demo;

    public DemoReadOnlyInterceptor(DemoProperties demo) {
        this.demo = demo;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (demo.isReadOnly() && !SAFE_METHODS.contains(request.getMethod())) {
            throw new ForbiddenException(
                    "これは公開デモのため、管理画面からの変更はできません（閲覧のみ）。"
                            + "自分の環境で動かすと制限なく使えます。");
        }
        return true;
    }
}
