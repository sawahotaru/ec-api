package com.example.ecapi.payment;

/**
 * 決済手段の側に開いている支払い（決済ページなど）を失効させた結果。
 * 失効は決済側の<strong>状態を変えるだけ</strong>で、決済側の記録は消さない。
 */
public enum ExpireResult {
    /** 開いている支払いが無い（閉じる必要が無い）。 */
    NOTHING_OPEN,
    /** 失効させた、または既に失効していた。 */
    EXPIRED,
    /** もう支払いが完了している。 */
    ALREADY_COMPLETED,
    /** 通信の失敗などで分からない。 */
    FAILED
}
