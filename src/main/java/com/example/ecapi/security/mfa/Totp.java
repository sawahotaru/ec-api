package com.example.ecapi.security.mfa;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 時刻ベースのワンタイムパスワード（RFC 6238 / TOTP）。
 *
 * <p>ライブラリを足さずに書いてある。中身は「共有鍵で HMAC-SHA1 を取り、決められた位置から
 * 4バイト抜いて 10^6 で割った余り」だけで、外部依存を増やす価値のある複雑さではない。
 * 同じワークスペースの clinic-reservation も純PHPで同じことをしている。
 *
 * <h2>実装上の注意</h2>
 * <ul>
 *   <li><b>比較は定数時間で行う。</b> {@code String.equals} は先頭が違えば即座に false を返すので、
 *       応答時間から「何桁目まで合っていたか」が漏れる。6桁しかないコードでは無視できない。</li>
 *   <li><b>前後1ステップ（±30秒）を許容する。</b> 端末の時計はずれる。許容ゼロだと、
 *       正しいコードを入れているのに通らない事故が定期的に起きる。広げすぎると総当たりが楽になるので ±1。</li>
 *   <li><b>鍵は Base32。</b> 認証アプリの標準（`otpauth://` URI）がそれを前提にしている。
 *       Base64 ではない。</li>
 * </ul>
 */
public final class Totp {

    /** 認証アプリの既定に合わせる（30秒・6桁・SHA1）。ここを変えるとアプリ側の設定も要る。 */
    static final int STEP_SECONDS = 30;
    static final int DIGITS = 6;
    private static final String ALGORITHM = "HmacSHA1";

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    /** 新しい共有鍵（Base32・160bit）。認証アプリに渡すのはこの文字列。 */
    public static String generateSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return base32Encode(bytes);
    }

    /**
     * コードが正しいか。前後 {@code window} ステップを許容する。
     *
     * @param window 0 なら現在のステップのみ。既定は 1（±30秒）
     */
    public static boolean verify(String secret, String code, int window) {
        if (secret == null || secret.isBlank() || code == null) {
            return false;
        }
        String normalised = normaliseCode(code);
        if (normalised.length() != DIGITS) {
            return false;
        }
        long step = Instant.now().getEpochSecond() / STEP_SECONDS;
        boolean matched = false;
        for (long i = -window; i <= window; i++) {
            // 一致しても回し切る。早く抜けると「何ステップ目で当たったか」が時間差に出る。
            matched |= constantTimeEquals(generate(secret, step + i), normalised);
        }
        return matched;
    }

    public static boolean verify(String secret, String code) {
        return verify(secret, code, 1);
    }

    /** 指定ステップのコード。テストから時刻を固定して呼べるように public にしてある。 */
    public static String generate(String secret, long step) {
        byte[] key = base32Decode(secret);
        byte[] message = new byte[8];
        for (int i = 7; i >= 0; i--) {
            message[i] = (byte) (step & 0xFF);
            step >>= 8;
        }
        byte[] hash;
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            hash = mac.doFinal(message);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP could not be computed", e);
        }
        // 動的切り出し（RFC 4226 §5.3）: 末尾4bitが指す位置から4バイト読む
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
        int otp = binary % (int) Math.pow(10, DIGITS);
        return String.format(Locale.ROOT, "%0" + DIGITS + "d", otp);
    }

    /**
     * 認証アプリに読ませる {@code otpauth://} URI。
     *
     * <p>QR画像はサーバーでは作らない。画像生成ライブラリが増えるうえ、この文字列さえ渡せば
     * ブラウザ側で描ける。手入力したい人のために鍵そのものも併記する前提。
     */
    public static String uri(String secret, String account, String issuer) {
        return "otpauth://totp/" + urlEncode(issuer) + ":" + urlEncode(account)
                + "?secret=" + secret
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    /**
     * 入力の正規化。スマホからだと空白やハイフンが混じり、日本語環境では
     * <strong>全角数字</strong>で入ることがある（clinic 側で実際に踏んだ）。
     * 見た目が合っているのに弾かれるのは、利用者にはただの故障に見える。
     */
    static String normaliseCode(String code) {
        StringBuilder out = new StringBuilder(DIGITS);
        for (char c : code.toCharArray()) {
            if (c >= '0' && c <= '9') {
                out.append(c);
            } else if (c >= '０' && c <= '９') {   // 全角
                out.append((char) ('0' + (c - '０')));
            }
            // 空白・ハイフン等は落とす
        }
        return out.toString();
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < x.length; i++) {
            diff |= x[i] ^ y[i];
        }
        return diff == 0;
    }

    static String base32Encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return out.toString();
    }

    static byte[] base32Decode(String encoded) {
        String clean = encoded.trim().replace("=", "").toUpperCase(Locale.ROOT);
        int buffer = 0;
        int bits = 0;
        byte[] out = new byte[clean.length() * 5 / 8];
        int index = 0;
        for (char c : clean.toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Not a Base32 secret");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out[index++] = (byte) ((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out;
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
