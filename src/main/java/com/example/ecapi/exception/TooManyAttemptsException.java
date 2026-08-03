package com.example.ecapi.exception;

/**
 * 試行回数の上限に達した（429 Too Many Requests）。
 *
 * <p>401 にしないのは、資格情報の正誤とは別の理由で断っているため。401 を返すと
 * クライアントは「パスワードが違う」と解釈して再試行し、ロックを延ばし続ける。
 */
public class TooManyAttemptsException extends RuntimeException {

    public TooManyAttemptsException(String message) {
        super(message);
    }
}
