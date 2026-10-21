package com.example.ecapi.exception;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.query.sqm.UnknownPathException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(BadRequestException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(ConflictException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage());
    }

    /**
     * 回数制限。{@code Retry-After} を付けるのは、これが無いと「いつ戻ってよいか」を
     * 知る手段が本文の日本語しかなく、まともなクライアントほど即座に再試行して
     * 自分で窓を延ばすため。
     */
    @ExceptionHandler(TooManyAttemptsException.class)
    public ResponseEntity<Map<String, Object>> handleTooManyAttempts(TooManyAttemptsException ex) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS);
        if (ex.retryAfterSeconds() > 0) {
            response.header("Retry-After", Long.toString(ex.retryAfterSeconds()));
        }
        return response.body(baseBody(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage()));
    }

    /**
     * {@code ?sort=bogus} のような、存在しない項目での並び替え。
     *
     * <p>ハンドラが無いと <strong>500</strong> になる。{@code Pageable} を取る公開
     * エンドポイント（{@code /api/products} など）は未認証で叩けるので、
     * 「誰でも 500 を起こせて、そのたびにサーバー側にフルスタックが積まれる」経路だった
     * ——本番 {@code /ec/} で実測。展示物としての見栄えも悪い。
     *
     * <p>本文には<strong>利用者が送った項目名しか返さない</strong>。例外の既定メッセージは
     * エンティティのクラス名や候補プロパティまで含むので、そのまま返すと内部構造を教える。
     */
    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<Map<String, Object>> handleBadSort(PropertyReferenceException ex) {
        return build(HttpStatus.BAD_REQUEST, badSortMessage(ex.getPropertyName()));
    }

    /**
     * 同じ「存在しない並び替え項目」でも、{@code @Query} を自分で書いたリポジトリでは
     * Spring Data ではなく <strong>Hibernate</strong> が落とす
     * （{@code /api/products} がこちら。{@link PropertyReferenceException} は派生クエリ側）。
     * 片方だけ直すと、エンドポイントによって 400 と 500 が混ざる。
     *
     * <p>Spring MVC は「例外そのもの」で見つからなければ<strong>その原因</strong>でも
     * ハンドラを探すので、{@code InvalidDataAccessApiUsageException} に包まれていても届く。
     *
     * <p>⚠ これは「自分で書いた JPQL が間違っている」ときにも飛ぶ例外だが、固定の文は
     * 起動時に Hibernate が検証するので、実行時にここへ来るのは動的な並び替えだけ。
     */
    @ExceptionHandler(UnknownPathException.class)
    public ResponseEntity<Map<String, Object>> handleBadSortFromHibernate(UnknownPathException ex) {
        return build(HttpStatus.BAD_REQUEST, badSortMessage(firstQuoted(ex.getMessage())));
    }

    private static String badSortMessage(String property) {
        return property == null || property.isBlank()
                ? "並び替えに指定できない項目です"
                : "並び替えに指定できない項目です: " + property;
    }

    /**
     * 例外メッセージから<strong>利用者が送った項目名だけ</strong>を取り出す。
     * 原文は "Could not resolve attribute 'bogus' of 'com.example.ecapi.domain.Product'" の形で、
     * そのまま返すとエンティティの完全修飾名まで教えることになる。
     */
    private static String firstQuoted(String message) {
        if (message == null) {
            return null;
        }
        int open = message.indexOf('\'');
        int close = open < 0 ? -1 : message.indexOf('\'', open + 1);
        return close < 0 ? null : message.substring(open + 1, close);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuth(AuthenticationException ex) {
        return build(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    /**
     * 「この環境では誰にもできない」種類の拒否（読み取り専用デモなど）。
     * {@link AccessDeniedException} と分けてあるのは、ログインし直しても解決しないことを
     * メッセージで伝えるため。
     */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(ForbiddenException ex) {
        return build(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        return build(HttpStatus.FORBIDDEN, "Access denied");
    }

    /**
     * The servlet container aborts an oversized upload before the controller runs, so this
     * never reaches the size check in ProductImageStorage. Without a handler it surfaces as
     * a 500, which reads as "the server is broken" rather than "your file is too big".
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "アップロードできるファイルサイズを超えています");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fields.put(error.getField(), error.getDefaultMessage());
        }
        Map<String, Object> body = baseBody(HttpStatus.BAD_REQUEST, "Validation failed");
        body.put("fields", fields);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(baseBody(status, message));
    }

    private Map<String, Object> baseBody(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return body;
    }
}
