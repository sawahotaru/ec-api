package com.example.ecapi.security.mfa;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * QRコード生成（JIS X 0510 / ISO 18004）— 依存なしの最小実装。
 *
 * <p>二段階認証の {@code otpauth://} URI をQRにするためだけに用意した。出力は SVG なので
 * 画像ライブラリも要らない。<strong>秘密鍵を外部のQR生成APIへ送らずに済む</strong>ことが、
 * 自前で持つ一番の理由——「この鍵をQRにしてください」と外部に投げるのは、鍵を渡すのと同じ。
 *
 * <p>同じワークスペースの {@code clinic-reservation/src/core/qr.php} からの移植で、
 * <strong>出力が1モジュールも違わないことをテストで突き合わせている</strong>。
 * 自作のQRエンコーダは「実際にカメラで読めるか」を自動では確かめられないため、
 * <em>すでに実機で読めている実装</em>と一致することを正しさの根拠にしている。
 *
 * <p>対応範囲（用途を満たす最小限）:
 * <ul>
 *   <li>8ビットバイトモードのみ（otpauth URI は記号・小文字を含み、英数字モードでは表せない）</li>
 *   <li>誤り訂正レベル M（約15%復元。認証アプリのスキャンには十分）</li>
 *   <li>型番 1〜20（最大666バイト。otpauth URI は長くても300バイト程度）</li>
 * </ul>
 */
public final class QrCode {

    /** 型番ごとのレベルM構成。[0] が {ブロックあたりEC語数}、以降が {ブロック数, データ語数}。 */
    private static final int[][][] EC_SPEC = {
        null,
        {{10}, {1, 16}},
        {{16}, {1, 28}},
        {{26}, {1, 44}},
        {{18}, {2, 32}},
        {{24}, {2, 43}},
        {{16}, {4, 27}},
        {{18}, {4, 31}},
        {{22}, {2, 38}, {2, 39}},
        {{22}, {3, 36}, {2, 37}},
        {{26}, {4, 43}, {1, 44}},
        {{30}, {1, 50}, {4, 51}},
        {{22}, {6, 36}, {2, 37}},
        {{22}, {8, 37}, {1, 38}},
        {{24}, {4, 40}, {5, 41}},
        {{24}, {5, 41}, {5, 42}},
        {{28}, {7, 45}, {3, 46}},
        {{28}, {10, 46}, {1, 47}},
        {{26}, {9, 43}, {4, 44}},
        {{26}, {3, 44}, {11, 45}},
        {{26}, {3, 41}, {13, 42}},
    };

    /** 位置合わせパターンの中心座標（型番ごと） */
    private static final int[][] ALIGN_CENTERS = {
        {}, {}, {6, 18}, {6, 22}, {6, 26}, {6, 30},
        {6, 34}, {6, 22, 38}, {6, 24, 42}, {6, 26, 46},
        {6, 28, 50}, {6, 30, 54}, {6, 32, 58}, {6, 34, 62},
        {6, 26, 46, 66}, {6, 26, 48, 70}, {6, 26, 50, 74},
        {6, 30, 54, 78}, {6, 30, 56, 82}, {6, 30, 58, 86},
        {6, 34, 62, 90},
    };

    private static final int MAX_VERSION = 20;

    private QrCode() {
    }

    /* ---------- GF(256)（リード・ソロモン用・原始多項式 0x11D） ---------- */

    private static final int[] GF_EXP = new int[512];
    private static final int[] GF_LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            GF_EXP[i] = x;
            GF_LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) {
                x ^= 0x11D;
            }
        }
        for (int i = 255; i < 512; i++) {
            GF_EXP[i] = GF_EXP[i - 255];
        }
    }

    /** 次数 deg の生成多項式（係数は最高次から） */
    private static int[] rsGenerator(int deg) {
        int[] g = {1};
        for (int i = 0; i < deg; i++) {
            int[] next = new int[g.length + 1];
            for (int k = 0; k < g.length; k++) {
                int c = g[k];
                next[k] ^= c;                                               // x を掛ける
                next[k + 1] ^= (c != 0 ? GF_EXP[(GF_LOG[c] + i) % 255] : 0); // α^i を掛ける
            }
            g = next;
        }
        return g;
    }

    /** データ語列から誤り訂正語を計算（多項式剰余） */
    private static int[] rsEncode(int[] data, int ecLen) {
        int[] g = rsGenerator(ecLen);
        int[] res = new int[data.length + ecLen];
        System.arraycopy(data, 0, res, 0, data.length);
        for (int i = 0; i < data.length; i++) {
            int lead = res[i];
            if (lead == 0) {
                continue;
            }
            int ll = GF_LOG[lead];
            for (int j = 0; j <= ecLen; j++) {
                if (g[j] != 0) {
                    res[i + j] ^= GF_EXP[(GF_LOG[g[j]] + ll) % 255];
                }
            }
        }
        int[] ec = new int[ecLen];
        System.arraycopy(res, data.length, ec, 0, ecLen);
        return ec;
    }

    /* ---------- ビット列 → データ語 ---------- */

    private static int dataCapacity(int ver) {
        int[][] spec = EC_SPEC[ver];
        int n = 0;
        for (int i = 1; i < spec.length; i++) {
            n += spec[i][0] * spec[i][1];
        }
        return n;
    }

    /** バイトモードで符号化し、パディングまで済ませたデータ語列を返す */
    private static int[] encodeData(byte[] data, int ver) {
        int capacity = dataCapacity(ver);
        int countBits = ver <= 9 ? 8 : 16;   // 文字数指示子のビット数（型番で変わる）

        StringBuilder bits = new StringBuilder();
        bits.append("0100");                                          // モード: 8ビットバイト
        bits.append(pad(Integer.toBinaryString(data.length), countBits));
        for (byte b : data) {
            bits.append(pad(Integer.toBinaryString(b & 0xFF), 8));
        }

        // 終端パターン（最大4ビット）→ 8ビット境界へ切り上げ
        bits.append("0".repeat(Math.min(4, capacity * 8 - bits.length())));
        if (bits.length() % 8 != 0) {
            bits.append("0".repeat(8 - bits.length() % 8));
        }

        List<Integer> cw = new ArrayList<>();
        for (int i = 0; i < bits.length(); i += 8) {
            cw.add(Integer.parseInt(bits.substring(i, i + 8), 2));
        }
        // 埋め草語を交互に詰めて容量ちょうどにする
        int[] padBytes = {0xEC, 0x11};
        for (int i = 0; cw.size() < capacity; i++) {
            cw.add(padBytes[i % 2]);
        }
        return cw.stream().mapToInt(Integer::intValue).toArray();
    }

    /** ブロック分割 → 各ブロックのEC語計算 → 規格どおりのインターリーブ */
    private static int[] finalCodewords(byte[] data, int ver) {
        int[][] spec = EC_SPEC[ver];
        int ecLen = spec[0][0];
        int[] cw = encodeData(data, ver);

        List<int[]> dataBlocks = new ArrayList<>();
        List<int[]> ecBlocks = new ArrayList<>();
        int pos = 0;
        for (int gi = 1; gi < spec.length; gi++) {
            int blocks = spec[gi][0];
            int dataCw = spec[gi][1];
            for (int b = 0; b < blocks; b++) {
                int[] block = new int[dataCw];
                System.arraycopy(cw, pos, block, 0, dataCw);
                pos += dataCw;
                dataBlocks.add(block);
                ecBlocks.add(rsEncode(block, ecLen));
            }
        }

        List<Integer> out = new ArrayList<>();
        int maxData = dataBlocks.stream().mapToInt(b -> b.length).max().orElse(0);
        for (int i = 0; i < maxData; i++) {
            for (int[] b : dataBlocks) {
                if (i < b.length) {
                    out.add(b[i]);
                }
            }
        }
        for (int i = 0; i < ecLen; i++) {
            for (int[] b : ecBlocks) {
                out.add(b[i]);
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /* ---------- モジュール配置 ---------- */

    /** 機能パターン（位置検出・分離・タイミング・位置合わせ・形式情報の予約）を置く */
    private static void placeFunctionPatterns(int[][] m, boolean[][] reserved, int ver, int size) {
        // 位置検出パターン＋分離パターン（3隅）
        int[][] corners = {{0, 0}, {size - 7, 0}, {0, size - 7}};
        for (int[] corner : corners) {
            for (int r = -1; r <= 7; r++) {
                for (int c = -1; c <= 7; c++) {
                    int rr = corner[0] + r;
                    int cc = corner[1] + c;
                    if (rr < 0 || rr >= size || cc < 0 || cc >= size) {
                        continue;
                    }
                    boolean inner = (r >= 0 && r <= 6 && (c == 0 || c == 6))
                            || (c >= 0 && c <= 6 && (r == 0 || r == 6))
                            || (r >= 2 && r <= 4 && c >= 2 && c <= 4);
                    m[rr][cc] = inner ? 1 : 0;
                    reserved[rr][cc] = true;
                }
            }
        }

        // タイミングパターン（6行目・6列目の交互）
        for (int i = 8; i < size - 8; i++) {
            int v = (i % 2 == 0) ? 1 : 0;
            m[6][i] = v;
            reserved[6][i] = true;
            m[i][6] = v;
            reserved[i][6] = true;
        }

        // 位置合わせパターン（位置検出パターンと重なる組み合わせは置かない）
        int[] centers = ALIGN_CENTERS[ver];
        int last = centers.length - 1;
        for (int ri = 0; ri < centers.length; ri++) {
            for (int ci = 0; ci < centers.length; ci++) {
                if ((ri == 0 && ci == 0) || (ri == 0 && ci == last) || (ri == last && ci == 0)) {
                    continue;
                }
                for (int r = -2; r <= 2; r++) {
                    for (int c = -2; c <= 2; c++) {
                        m[centers[ri] + r][centers[ci] + c] =
                                (Math.max(Math.abs(r), Math.abs(c)) != 1) ? 1 : 0;
                        reserved[centers[ri] + r][centers[ci] + c] = true;
                    }
                }
            }
        }

        // 形式情報の領域を予約する（値は placeFormat で書き込む）
        for (int i = 0; i < 9; i++) {
            if (!reserved[8][i]) {
                m[8][i] = 0;
                reserved[8][i] = true;
            }
            if (!reserved[i][8]) {
                m[i][8] = 0;
                reserved[i][8] = true;
            }
        }
        for (int i = 0; i < 8; i++) {
            m[8][size - 1 - i] = 0;
            reserved[8][size - 1 - i] = true;
            m[size - 1 - i][8] = 0;
            reserved[size - 1 - i][8] = true;
        }
        reserved[size - 8][8] = true;   // 常に暗のモジュール（値は placeFormat で書く）

        // 型番情報の領域を予約（型番7以上のみ）
        if (ver >= 7) {
            for (int i = 0; i < 18; i++) {
                int r = i / 3;
                int c = size - 11 + (i % 3);
                reserved[r][c] = true;
                reserved[c][r] = true;
            }
        }
    }

    /** 型番情報（型番7以上のみ）を書き込む */
    private static void placeVersion(int[][] m, int ver, int size) {
        if (ver < 7) {
            return;
        }
        int rem = ver << 12;
        for (int i = 0; i < 6; i++) {   // BCH(18,6)
            if ((rem & (1 << (17 - i))) != 0) {
                rem ^= 0x1F25 << (5 - i);
            }
        }
        int vinfo = (ver << 12) | rem;
        for (int i = 0; i < 18; i++) {
            int b = (vinfo >> i) & 1;
            int r = i / 3;
            int c = size - 11 + (i % 3);
            m[r][c] = b;
            m[c][r] = b;
        }
    }

    /** データ語をジグザグに配置（6列目のタイミングパターンは飛ばす） */
    private static void placeData(int[][] m, boolean[][] reserved, int[] codewords, int size) {
        StringBuilder bits = new StringBuilder();
        for (int cw : codewords) {
            bits.append(pad(Integer.toBinaryString(cw), 8));
        }

        int idx = 0;
        boolean up = true;
        for (int col = size - 1; col > 0; col -= 2) {
            if (col == 6) {
                col--;   // タイミング列は data 配置の対象外
            }
            for (int i = 0; i < size; i++) {
                int row = up ? (size - 1 - i) : i;
                for (int c = 0; c < 2; c++) {
                    int cc = col - c;
                    if (reserved[row][cc]) {
                        continue;
                    }
                    m[row][cc] = (idx < bits.length()) ? (bits.charAt(idx) - '0') : 0;
                    idx++;
                }
            }
            up = !up;
        }
    }

    /** マスク条件（0〜7） */
    private static boolean maskBit(int mask, int r, int c) {
        return switch (mask) {
            case 0 -> (r + c) % 2 == 0;
            case 1 -> r % 2 == 0;
            case 2 -> c % 3 == 0;
            case 3 -> (r + c) % 3 == 0;
            case 4 -> (r / 2 + c / 3) % 2 == 0;
            case 5 -> ((r * c) % 2) + ((r * c) % 3) == 0;
            case 6 -> (((r * c) % 2) + ((r * c) % 3)) % 2 == 0;
            default -> ((((r + c) % 2) + ((r * c) % 3)) % 2) == 0;
        };
    }

    /** マスク後の読み取りにくさを規格の4基準で採点（小さいほど良い） */
    private static int penalty(int[][] m, int size) {
        int score = 0;

        // 基準1: 同色の連続5個以上
        for (int k = 0; k < 2; k++) {
            for (int i = 0; i < size; i++) {
                int run = 0;
                int prev = -1;
                for (int j = 0; j < size; j++) {
                    int v = k == 0 ? m[i][j] : m[j][i];
                    if (v == prev) {
                        run++;
                    } else {
                        if (run >= 5) {
                            score += 3 + (run - 5);
                        }
                        run = 1;
                        prev = v;
                    }
                }
                if (run >= 5) {
                    score += 3 + (run - 5);
                }
            }
        }

        // 基準2: 2×2の同色ブロック
        for (int r = 0; r < size - 1; r++) {
            for (int c = 0; c < size - 1; c++) {
                int v = m[r][c];
                if (v == m[r][c + 1] && v == m[r + 1][c] && v == m[r + 1][c + 1]) {
                    score += 3;
                }
            }
        }

        // 基準3: 位置検出パターンに似た並び（1011101 の側に空白4）
        int[] p1 = {1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0};
        int[] p2 = {0, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1};
        for (int k = 0; k < 2; k++) {
            for (int i = 0; i < size; i++) {
                int[] line = new int[size];
                for (int j = 0; j < size; j++) {
                    line[j] = k == 0 ? m[i][j] : m[j][i];
                }
                for (int j = 0; j + 11 <= size; j++) {
                    if (matches(line, j, p1) || matches(line, j, p2)) {
                        score += 40;
                    }
                }
            }
        }

        // 基準4: 暗モジュールの比率が50%から離れるほど加点
        int dark = 0;
        for (int[] row : m) {
            for (int v : row) {
                dark += v;
            }
        }
        double ratio = dark * 100.0 / (size * (double) size);
        score += (int) Math.floor(Math.abs(ratio - 50) / 5) * 10;
        return score;
    }

    private static boolean matches(int[] line, int from, int[] pattern) {
        for (int i = 0; i < pattern.length; i++) {
            if (line[from + i] != pattern[i]) {
                return false;
            }
        }
        return true;
    }

    /** 形式情報（誤り訂正レベルM固定）を書き込む */
    private static void placeFormat(int[][] m, int mask, int size) {
        int data = (0b00 << 3) | mask;     // レベルM = 00
        int rem = data << 10;
        for (int i = 0; i < 5; i++) {      // BCH(15,5)
            if ((rem & (1 << (14 - i))) != 0) {
                rem ^= 0x537 << (4 - i);
            }
        }
        int fmt = (((data << 10) | rem) ^ 0x5412);

        for (int i = 0; i < 15; i++) {
            int b = (fmt >> i) & 1;
            // 1本目: 左上の縦（列8を上から）→ 左下の縦
            if (i < 6) {
                m[i][8] = b;
            } else if (i < 8) {
                m[i + 1][8] = b;            // 行6はタイミングなので1つ飛ばす
            } else {
                m[size - 15 + i][8] = b;
            }
            // 2本目: 右上の横（行8を右から）→ 左上の横
            if (i < 8) {
                m[8][size - 1 - i] = b;
            } else if (i == 8) {
                m[8][7] = b;                // 列6はタイミングなので1つ飛ばす
            } else {
                m[8][14 - i] = b;
            }
        }
        m[size - 8][8] = 1;
    }

    /* ---------- 公開API ---------- */

    /**
     * 文字列をQRのモジュール行列（0/1・余白なし）にする。
     *
     * @throws IllegalArgumentException 型番20に収まらない長さのとき
     */
    public static int[][] matrix(byte[] data) {
        int ver = 0;
        for (int v = 1; v <= MAX_VERSION; v++) {
            int countBits = v <= 9 ? 8 : 16;
            if (dataCapacity(v) * 8 >= 4 + countBits + data.length * 8) {
                ver = v;
                break;
            }
        }
        if (ver == 0) {
            throw new IllegalArgumentException("QRに収まらないデータ長です: " + data.length);
        }

        int size = 17 + 4 * ver;
        int[][] base = new int[size][size];
        boolean[][] reserved = new boolean[size][size];
        placeFunctionPatterns(base, reserved, ver, size);
        placeData(base, reserved, finalCodewords(data, ver), size);

        // 8種のマスクを試し、最も減点の少ないものを採用する。
        // 採点は形式情報・型番情報を書き込む**前**に行う（qrcode.js 系の一般的な実装と同じ流儀。
        // 含めて採点する流儀もあり、どちらも規格上妥当。広く使われている実装と同じ出力になる
        // ほうを選んでおくと、他実装と突き合わせて検証できる）。
        int[][] best = null;
        int bestScore = Integer.MAX_VALUE;
        int bestMask = 0;
        for (int mask = 0; mask < 8; mask++) {
            int[][] m = new int[size][];
            for (int r = 0; r < size; r++) {
                m[r] = base[r].clone();
            }
            for (int r = 0; r < size; r++) {
                for (int c = 0; c < size; c++) {
                    if (reserved[r][c]) {
                        continue;
                    }
                    if (maskBit(mask, r, c)) {
                        m[r][c] ^= 1;
                    }
                }
            }
            int score = penalty(m, size);
            if (score < bestScore) {
                bestScore = score;
                best = m;
                bestMask = mask;
            }
        }
        placeFormat(best, bestMask, size);
        placeVersion(best, ver, size);
        return best;
    }

    /**
     * QRを SVG 文字列で返す（そのまま HTML に埋め込める）。
     *
     * @param module 1モジュールの辺(px)
     * @param quiet  余白のモジュール数（規格の推奨は4）
     */
    public static String svg(String data, int module, int quiet, String alt) {
        int[][] m = matrix(data.getBytes(StandardCharsets.UTF_8));
        int size = m.length;
        int dim = (size + quiet * 2) * module;

        // 暗モジュールを1本のパスにまとめる（要素数を抑えて描画を軽くする）
        StringBuilder path = new StringBuilder();
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                if (m[r][c] != 0) {
                    path.append('M').append((c + quiet) * module).append(' ').append((r + quiet) * module)
                            .append('h').append(module).append('v').append(module)
                            .append("h-").append(module).append('z');
                }
            }
        }

        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + dim + "\" height=\"" + dim + "\""
                + " viewBox=\"0 0 " + dim + " " + dim + "\" role=\"img\" aria-label=\"" + escape(alt) + "\">"
                + "<rect width=\"" + dim + "\" height=\"" + dim + "\" fill=\"#fff\"/>"
                + "<path d=\"" + path + "\" fill=\"#000\"/>"
                + "</svg>";
    }

    private static String pad(String bits, int length) {
        return "0".repeat(Math.max(0, length - bits.length())) + bits;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#039;");
    }
}
