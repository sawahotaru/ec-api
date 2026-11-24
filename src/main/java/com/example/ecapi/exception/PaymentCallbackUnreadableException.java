package com.example.ecapi.exception;

/**
 * 決済側の通知は署名を通ったが、中身を復元できなかった。
 * 5xx で返して決済側に再送させるための例外（2xx を返すと再送されず、入金が取りこぼされる）。
 * 例外の文言は応答にもログにも出さない。出すのはイベント ID と原因の種別だけ。
 */
public class PaymentCallbackUnreadableException extends RuntimeException {

    private final String eventId;
    private final String causeType;

    public PaymentCallbackUnreadableException(String eventId, String causeType) {
        super("unreadable payment callback");
        this.eventId = eventId;
        this.causeType = causeType;
    }

    public String eventId() {
        return eventId;
    }

    public String causeType() {
        return causeType;
    }
}
