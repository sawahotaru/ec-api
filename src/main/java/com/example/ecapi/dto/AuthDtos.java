package com.example.ecapi.dto;

import com.example.ecapi.domain.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 6, max = 100) String password,
            @NotBlank String name) {
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {
    }

    public record UserResponse(Long id, String email, String name, String role) {
        public static UserResponse from(User user) {
            return new UserResponse(user.getId(), user.getEmail(), user.getName(), user.getRole().name());
        }
    }

    public record AuthResponse(String token, String tokenType, long expiresInMs, UserResponse user) {
    }

    /**
     * ログインの結果。二段階認証が有効なら<strong>アクセストークンを返さない</strong>。
     *
     * <p>{@code user} も返さない。パスワードだけを持っている相手に、そのアカウントの
     * 氏名や権限を教える理由が無いため（認証はまだ完了していない）。
     *
     * @param mfaRequired true なら二段階目が必要。{@code mfaToken} を持って /api/auth/mfa へ
     * @param mfaToken    二段階目の引換券。**これでは何のAPIも叩けない**（用途が焼き込んである）
     */
    public record LoginResponse(
            boolean mfaRequired,
            String mfaToken,
            String token,
            String tokenType,
            long expiresInMs,
            UserResponse user) {

        public static LoginResponse authenticated(AuthResponse auth) {
            return new LoginResponse(false, null, auth.token(), auth.tokenType(),
                    auth.expiresInMs(), auth.user());
        }

        public static LoginResponse mfaRequired(String mfaToken, long expiresInMs) {
            return new LoginResponse(true, mfaToken, null, null, expiresInMs, null);
        }
    }

    /** 二段階目。ログインで受け取った mfaToken と、認証アプリの6桁（またはリカバリコード）。 */
    public record MfaLoginRequest(
            @NotBlank String mfaToken,
            @NotBlank String code) {
    }

    /** 登録の確認・解除で使う。コードは6桁 or リカバリコード。 */
    public record MfaCodeRequest(@NotBlank String code) {
    }

    /**
     * 二段階認証の状態。{@code secret}/{@code otpauthUri} は登録開始の応答にだけ入る。
     *
     * @param recoveryCodes 有効化した直後だけ平文で返る（保存はハッシュなので二度と出せない）
     */
    public record MfaStatusResponse(
            boolean enabled,
            boolean enrollmentStarted,
            int remainingRecoveryCodes,
            String secret,
            String otpauthUri,
            // 認証アプリに読ませるQR（SVG文字列）。**サーバーで描く**のは、鍵を外部の
            // QR生成サービスへ送らないため——「これをQRにして」と投げるのは鍵を渡すのと同じ。
            String qrSvg,
            java.util.List<String> recoveryCodes) {
    }
}
