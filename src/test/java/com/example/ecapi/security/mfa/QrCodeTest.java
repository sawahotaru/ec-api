package com.example.ecapi.security.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * QRコード生成の検証。
 *
 * <h2>何を正しさの根拠にするか</h2>
 * 自作のQRエンコーダには、<strong>「実際にカメラで読めるか」を自動で確かめる手段が無い</strong>
 * （デコーダを持っていないし、それも自作すれば同じ勘違いを2回するだけになる）。
 * 「例外が出ない」「それらしい行列が返る」を確かめても、読めないQRは読めないまま緑になる。
 *
 * <p>そこで<strong>すでに実機で読めている実装の出力と1モジュールずつ突き合わせる</strong>。
 * 基準は同じワークスペースの {@code clinic-reservation/src/core/qr.php}（この移植元）で、
 * 下のハッシュはその PHP をそのまま走らせて得た値。完全に一致すれば、
 * 少なくとも「移植で壊していない」ことは確実に言える。
 *
 * <pre>
 * # 基準値の作り方（clinic-reservation で実行）
 * docker run --rm -v "$PWD:/w" -w /w php:8.4-cli php -r '
 *   require "src/core/qr.php";
 *   $m = qr_matrix($data);
 *   echo count($m) . ":" . substr(sha1(implode("/", array_map(fn($r)=>implode("",$r), $m))), 0, 16);'
 * </pre>
 *
 * <p>⚠️ この一致は「PHP版と同じ」ことしか保証しない。PHP版そのものが間違っていれば両方間違う。
 * 最終的な確認は<strong>実機のカメラで読む</strong>ことでしかできない。
 */
class QrCodeTest {

    /** clinic-reservation の qr.php が出した行列（サイズ:sha1先頭16桁）。 */
    private static final String OTPAUTH_EC =
            "otpauth://totp/EC%20API:admin%40example.com?secret=JBSWY3DPEHPK3PXP"
                    + "&issuer=EC%20API&algorithm=SHA1&digits=6&period=30";
    private static final String OTPAUTH_CLINIC =
            "otpauth://totp/Clinic:owner%40example.jp?secret=MFRGGZDFMZTWQ2LKNNWG23TPOA5A"
                    + "&issuer=Clinic&algorithm=SHA1&digits=6&period=30";

    @Test
    @DisplayName("実機で読めている PHP 実装と、1モジュールも違わない")
    void matchesTheReferenceImplementation() {
        assertThat(fingerprint(OTPAUTH_EC)).as("otpauth (EC API)").isEqualTo("45:47b865ae9cdb8de5");
        assertThat(fingerprint("HELLO")).as("短い文字列").isEqualTo("21:6511bb31071e12f7");
        assertThat(fingerprint(OTPAUTH_CLINIC)).as("otpauth (Clinic)").isEqualTo("49:046b540cad0828f3");
        assertThat(fingerprint("A".repeat(200))).as("長い文字列（複数ブロック）")
                .isEqualTo("57:a613251a7a1f8d55");
    }

    /* ---------- 構造の検査（PHP版と一緒に間違えていないかの補助） ---------- */

    @Test
    @DisplayName("3隅に位置検出パターンがある")
    void hasFinderPatterns() {
        int[][] m = QrCode.matrix(OTPAUTH_EC.getBytes(StandardCharsets.UTF_8));
        int size = m.length;

        for (int[] origin : new int[][]{{0, 0}, {0, size - 7}, {size - 7, 0}}) {
            // 7×7の外枠が暗、その内側1周が明、中央3×3が暗
            assertThat(m[origin[0]][origin[1]]).isEqualTo(1);
            assertThat(m[origin[0] + 1][origin[1] + 1]).isEqualTo(0);
            assertThat(m[origin[0] + 3][origin[1] + 3]).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("タイミングパターンが交互になっている")
    void hasTimingPattern() {
        int[][] m = QrCode.matrix(OTPAUTH_EC.getBytes(StandardCharsets.UTF_8));

        for (int i = 8; i < m.length - 8; i++) {
            assertThat(m[6][i]).as("行6 の %d 列目", i).isEqualTo(i % 2 == 0 ? 1 : 0);
            assertThat(m[i][6]).as("列6 の %d 行目", i).isEqualTo(i % 2 == 0 ? 1 : 0);
        }
    }

    @Test
    @DisplayName("サイズは 17 + 4×型番（データが長いほど大きくなる）")
    void sizeGrowsWithData() {
        int small = QrCode.matrix("HI".getBytes(StandardCharsets.UTF_8)).length;
        int large = QrCode.matrix("A".repeat(400).getBytes(StandardCharsets.UTF_8)).length;

        assertThat((small - 17) % 4).isZero();
        assertThat(large).isGreaterThan(small);
    }

    @Test
    @DisplayName("収まらない長さは例外にする（黙って壊れたQRを返さない）")
    void rejectsTooMuchData() {
        assertThatThrownBy(() -> QrCode.matrix("A".repeat(1000).getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("収まらない");
    }

    /* ---------- SVG ---------- */

    @Test
    @DisplayName("SVG は余白つきで、そのまま HTML に貼れる形")
    void rendersSvg() {
        String svg = QrCode.svg(OTPAUTH_EC, 6, 4, "二段階認証のQRコード");

        assertThat(svg).startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\"");
        assertThat(svg).endsWith("</svg>");
        // 45モジュール + 余白4×2 = 53 → 53 × 6px
        assertThat(svg).contains("width=\"318\"").contains("height=\"318\"");
        assertThat(svg).contains("role=\"img\"").contains("aria-label=\"二段階認証のQRコード\"");
        // 外部リソースを取りに行かない（CSPやオフラインでも壊れない）。
        // xmlns の名前空間URIは「取得しない識別子」なので、それ以外にURLが無いことを見る。
        assertThat(svg.replace("http://www.w3.org/2000/svg", ""))
                .doesNotContain("http://").doesNotContain("https://");
        assertThat(svg).doesNotContain("<image").doesNotContain("xlink:href");
    }

    @Test
    @DisplayName("alt 属性はエスケープされる（SVGはHTMLに直接埋め込むため）")
    void escapesAltText() {
        String svg = QrCode.svg("HELLO", 4, 2, "<script>alert(1)</script>");

        assertThat(svg).doesNotContain("<script>");
        assertThat(svg).contains("&lt;script&gt;");
    }

    /* ---------- helpers ---------- */

    /** PHP 側と同じ「サイズ:行を / で連結した sha1 の先頭16桁」。 */
    private static String fingerprint(String data) {
        int[][] m = QrCode.matrix(data.getBytes(StandardCharsets.UTF_8));
        String joined = IntStream.range(0, m.length)
                .mapToObj(r -> IntStream.of(m[r]).mapToObj(String::valueOf).collect(Collectors.joining()))
                .collect(Collectors.joining("/"));
        return m.length + ":" + sha1Hex(joined).substring(0, 16);
    }

    private static String sha1Hex(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
