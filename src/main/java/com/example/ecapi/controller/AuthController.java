package com.example.ecapi.controller;

import com.example.ecapi.domain.User;
import com.example.ecapi.dto.AuthDtos.AuthResponse;
import com.example.ecapi.dto.AuthDtos.LoginRequest;
import com.example.ecapi.dto.AuthDtos.LoginResponse;
import com.example.ecapi.dto.AuthDtos.MfaCodeRequest;
import com.example.ecapi.dto.AuthDtos.MfaLoginRequest;
import com.example.ecapi.dto.AuthDtos.MfaStatusResponse;
import com.example.ecapi.dto.AuthDtos.RegisterRequest;
import com.example.ecapi.dto.AuthDtos.UserResponse;
import com.example.ecapi.exception.ForbiddenException;
import com.example.ecapi.privacy.DemoProperties;
import com.example.ecapi.security.CurrentUserProvider;
import com.example.ecapi.security.mfa.MfaService;
import com.example.ecapi.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "登録・ログイン・二段階認証")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final CurrentUserProvider currentUserProvider;
    private final MfaService mfaService;
    private final DemoProperties demo;

    public AuthController(AuthService authService, CurrentUserProvider currentUserProvider,
                          MfaService mfaService, DemoProperties demo) {
        this.authService = authService;
        this.currentUserProvider = currentUserProvider;
        this.mfaService = mfaService;
        this.demo = demo;
    }

    @Operation(summary = "Register a new user and receive a JWT")
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @Operation(summary = "ログイン",
            description = "二段階認証が有効なら `mfaRequired: true` と `mfaToken` だけを返す"
                    + "（アクセストークンもユーザー情報も返さない）。続けて `/api/auth/mfa` を叩く。")
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @Operation(summary = "ログインの二段階目",
            description = "ログインで受け取った mfaToken と、認証アプリの6桁またはリカバリコード。"
                    + "ここを通って初めてアクセストークンが発行される。")
    @PostMapping("/mfa")
    public ResponseEntity<AuthResponse> verifyMfa(@Valid @RequestBody MfaLoginRequest request) {
        return ResponseEntity.ok(authService.verifyMfa(request));
    }

    @Operation(summary = "Get the current authenticated user")
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me() {
        return ResponseEntity.ok(UserResponse.from(currentUserProvider.require()));
    }

    /* ---------- 二段階認証の設定（本人のみ） ---------- */

    @Operation(summary = "自分の二段階認証の状態")
    @GetMapping("/mfa/status")
    public MfaStatusResponse status() {
        User user = currentUserProvider.require();
        return new MfaStatusResponse(
                user.isMfaEnabled(),
                user.getMfaSecret() != null && !user.isMfaEnabled(),
                mfaService.remainingRecoveryCodes(user),
                null, null, null);
    }

    @Operation(summary = "二段階認証の設定を開始（鍵を発行。まだ有効にはならない）",
            description = "返る `otpauthUri` を認証アプリに読ませ、表示された6桁を /mfa/confirm へ。")
    @PostMapping("/mfa/setup")
    public MfaStatusResponse setup() {
        User user = currentUserProvider.require();
        guardDemo();
        MfaService.Enrollment enrollment = mfaService.startEnrollment(user);
        return new MfaStatusResponse(false, true, 0, enrollment.secret(), enrollment.otpauthUri(), null);
    }

    @Operation(summary = "二段階認証を有効化（コードを1回通して確認）",
            description = "**リカバリコードはここでしか返らない**（保存はハッシュのため二度と取り出せない）。")
    @PostMapping("/mfa/confirm")
    public MfaStatusResponse confirm(@Valid @RequestBody MfaCodeRequest request) {
        User user = currentUserProvider.require();
        guardDemo();
        List<String> recoveryCodes = mfaService.confirmEnrollment(user, request.code());
        return new MfaStatusResponse(true, false, recoveryCodes.size(), null, null, recoveryCodes);
    }

    @Operation(summary = "二段階認証を解除",
            description = "解除にも認証コード（またはリカバリコード）が要る。"
                    + "盗まれたセッションで黙って外されないようにするため。")
    @DeleteMapping("/mfa")
    public MfaStatusResponse disable(@Valid @RequestBody MfaCodeRequest request) {
        User user = currentUserProvider.require();
        guardDemo();
        mfaService.disable(user, request.code());
        return new MfaStatusResponse(false, false, 0, null, null, null);
    }

    /**
     * 🔴 公開デモでは二段階認証の登録・解除を拒否する。
     *
     * <p>読み取り専用デモは {@code /api/admin/**} の書き込みを止めているが、ここは
     * {@code /api/auth/**} なので<strong>素通りしてしまう</strong>。管理者アカウントは
     * 閲覧者どうしで共有されているので、誰か一人が自分の認証アプリを登録すると
     * <strong>他の全員が二度と入れなくなる</strong>——実質的なサービス妨害になる。
     *
     * <p>読み取り専用と同じフラグで塞ぐ。ログイン自体（二段階目の検証）は塞がない:
     * 自分の環境で有効にした人が、デモ設定でも入れなくなっては困るため。
     */
    private void guardDemo() {
        if (demo.isReadOnly()) {
            throw new ForbiddenException(
                    "これは公開デモのため、二段階認証の設定は変更できません"
                            + "（管理者アカウントは閲覧者どうしで共有されており、"
                            + "誰か一人が登録すると他の全員が入れなくなるためです）。"
                            + "自分の環境で動かすと利用できます。");
        }
    }
}
