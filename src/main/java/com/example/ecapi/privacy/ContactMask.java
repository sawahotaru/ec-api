package com.example.ecapi.privacy;

/**
 * メールアドレスの伏せ字化。
 *
 * <p>「誰の注文か区別はつくが、連絡先としては使えない」ところを狙う。完全に隠すと
 * 管理画面で同一人物の注文をまとめて見ることすらできず、逆に生で出すと、
 * <strong>公開デモに他人が入れた実アドレス</strong>がそのまま見えてしまう。
 *
 * <p>ログ（{@code LoggingOrderEventListener}）と管理画面の両方から使う。実装が2箇所に
 * 分かれると、片方だけ直して「ログでは伏せているのに画面では出ている」状態を作る。
 */
public final class ContactMask {

    private ContactMask() {
    }

    /**
     * {@code guest@example.com} → {@code g***@example.com}。
     *
     * <p>ドメインは残す。デモの説明で「example.com 宛には送っていない」ことを示せるし、
     * ドメインだけで個人は特定できない。ローカル部が1文字以下なら先頭も伏せる。
     */
    public static String mask(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + (at >= 0 ? email.substring(at) : "");
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    /** ログ用。null を "-" にするだけ（ログ行に空欄が空くのを避ける）。 */
    public static String maskForLog(String email) {
        String masked = mask(email);
        return masked != null ? masked : "-";
    }
}
