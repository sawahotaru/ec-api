package com.example.ecapi.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.OrderStatus;
import com.example.ecapi.dto.OrderDtos.OrderResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 「振込先を表示」を出す印。振込を選んだお支払い待ちの注文だけ true（決済手段の名前は応答に出さない）。 */
class OrderResponseTransferFlagTest {

    private static Order order(OrderStatus status, String provider) {
        Order order = new Order();
        order.setStatus(status);
        order.setPaymentProvider(provider);
        return order;
    }

    @Test
    void bankTransferPendingIsTrue() {
        assertThat(OrderResponse.from(order(OrderStatus.PENDING, "bank_transfer")).transferInstructions()).isTrue();
    }

    @Test
    void cardOrUnchosenPendingIsFalse() {
        assertThat(OrderResponse.from(order(OrderStatus.PENDING, "stripe")).transferInstructions()).isFalse();
        assertThat(OrderResponse.from(order(OrderStatus.PENDING, null)).transferInstructions()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void notPendingIsFalseEvenForBankTransfer(OrderStatus status) {
        assertThat(OrderResponse.from(order(status, "bank_transfer")).transferInstructions()).isFalse();
    }

    @Test
    void flagSurvivesCopiesAndNeverNamesTheProvider() {
        OrderResponse r = OrderResponse.from(order(OrderStatus.PENDING, "bank_transfer"));
        assertThat(r.withPayBy(null).transferInstructions()).isTrue();
        assertThat(r.withoutContact().transferInstructions()).isTrue();
        assertThat(r.toString()).doesNotContain("bank_transfer");
    }
}
