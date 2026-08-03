package com.example.ecapi.exception;

/**
 * 認証は通っているが、その操作は許されない（403）。
 *
 * <p>{@code AccessDeniedException}（権限不足）とは別に用意してある。こちらは
 * 「あなたには権限が無い」ではなく「<strong>この環境では誰にもできない</strong>」を表す。
 * 読み取り専用デモで管理者が書き込もうとした場合など、ログインし直しても解決しない種類の拒否。
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
