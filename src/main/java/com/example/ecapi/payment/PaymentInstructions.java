package com.example.ecapi.payment;

import java.util.List;

/**
 * 外部の決済ページを持たない手段（銀行振込など）の案内。決済手段に依らない形にしてあり、
 * 画面（ストア）が値をすべてエスケープして表示する。HTML は組み立てない。
 *
 * @param title 見出し
 * @param rows  ラベルと値の組（注文番号・金額・振込先・照合番号・期限など）
 * @param notes 注記（箇条）
 */
public record PaymentInstructions(String title, List<Row> rows, List<String> notes) {

    public record Row(String label, String value) {
    }
}
