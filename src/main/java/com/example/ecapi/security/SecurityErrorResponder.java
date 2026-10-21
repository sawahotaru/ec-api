package com.example.ecapi.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 認証・認可で弾いたときの応答。<strong>401 と 403 を区別する</strong>。
 *
 * <p>既定のままだと、認証機構を何も登録していない構成では Spring Security が
 * <strong>未認証にも 403 を返し、本文は空</strong>になる。これは2つの意味で良くない:
 * <ul>
 *   <li>403 は「ログインし直しても入れない」の意味。未認証に返すと、画面は
 *       「権限がありません」と出すしかなく、<strong>ログインすれば入れる人を追い返す</strong>。</li>
 *   <li>本文が空だと、フロントは {@code "403 Forbidden"} としか言えない。他のエラーが
 *       すべて {@code message} を持つのに、ここだけ持たない。</li>
 * </ul>
 *
 * <p>本文の形は {@link com.example.ecapi.exception.GlobalExceptionHandler} と同じにしてある
 * （{@code timestamp} / {@code status} / {@code error} / {@code message}）。
 * フィルタで弾かれたか、コントローラまで届いてから弾かれたかで応答の形が変わると、
 * 画面側がどちらにも備える羽目になる。
 */
@Component
public class SecurityErrorResponder implements AuthenticationEntryPoint, AccessDeniedHandler {

    /**
     * 自前で持つ。<strong>注入しない</strong>——このクラスは {@code SecurityConfig} の依存なので
     * セキュリティのフィルタチェーンと一緒に<strong>Jackson の自動設定より先に</strong>作られ、
     * コンテナの {@code ObjectMapper} をコンストラクタで要求すると起動そのものが失敗する
     * （実際に踏んだ）。書くのは固定の4フィールドだけなので、共有設定は要らない。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 未認証（資格情報が無い・壊れている・期限切れ）。 */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, "ログインが必要です");
    }

    /** 認証済みだが権限が足りない。 */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(response, HttpStatus.FORBIDDEN, "この操作を行う権限がありません");
    }

    private void write(HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
