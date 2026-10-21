package com.example.ecapi.exception;

/**
 * 試行回数の上限に達した（429 Too Many Requests）。
 *
 * <p>401 にしないのは、資格情報の正誤とは別の理由で断っているため。401 を返すと
 * クライアントは「パスワードが違う」と解釈して再試行し、ロックを延ばし続ける。
 */
public class TooManyAttemptsException extends RuntimeException {

    /** 窓が開くまでの秒数。分からない（＝時間で解けるが秒は言えない）なら 0。 */
    private final long retryAfterSeconds;

    public TooManyAttemptsException(String message) {
        this(message, 0);
    }

    public TooManyAttemptsException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
