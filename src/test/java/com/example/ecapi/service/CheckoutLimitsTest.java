package com.example.ecapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ecapi.domain.Category;
import com.example.ecapi.domain.Product;
import com.example.ecapi.domain.TaxCategory;
import com.example.ecapi.domain.TaxRate;
import com.example.ecapi.exception.BadRequestException;
import com.example.ecapi.exception.ConflictException;
import com.example.ecapi.repository.CartItemRepository;
import com.example.ecapi.repository.CategoryRepository;
import com.example.ecapi.repository.OrderRepository;
import com.example.ecapi.repository.ProductRepository;
import com.example.ecapi.repository.TaxRateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 「未認証で店全体を売り切れにできる」ことへの対処。
 *
 * <h2>何が成り立っていなかったか</h2>
 * 在庫の引当（hold）は売り越しを防ぐ良い仕組みだが、<strong>引き当てるのに支払いが要らない</strong>。
 * 公開カタログは {@code available} を正確に返すので、各商品の available ぴったりを
 * <strong>1要求で</strong>押さえれば店全体が在庫切れ表示になる。本番は決済が無効で注文は必ず
 * 失効するから、攻撃側の費用はゼロ——引当の寿命（30分）ごとに1要求で恒久的に維持できる。
 *
 * <p>直し方は2段。1注文の量を縛るのは「1発で空にする」を止めるためで、これは回数制限と
 * 掛け算になる。だが<strong>送信元を分散されれば回数制限は薄まる</strong>ので、本命は
 * 「1商品の未払い引当は在庫の一定割合まで」——<strong>誰が何回叩こうと</strong>
 * 引当だけでは売り切れ表示にできない、という形にしてある。
 *
 * <p>ここで固定したいのは個別の数値ではなく<strong>その性質</strong>なので、
 * 「断られるまで注文を繰り返しても available が 0 にならない」ことを実際に回して確かめる。
 */
@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "app.order.expiry-sweep-ms=3600000",
        "app.order.purge-sweep-ms=3600000",
        "app.order.max-quantity-per-item=5",
        "app.order.max-lines-per-order=2",
        "app.order.max-hold-percent=50"
})
class CheckoutLimitsTest {

    @Autowired OrderService orderService;
    @Autowired ProductRepository productRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired CartItemRepository cartItemRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired TaxRateRepository taxRateRepository;

    private Product matcha;
    private Product yunomi;
    private Product hashi;

    @BeforeEach
    void reset() {
        orderRepository.deleteAll();
        // H2 はインメモリでもURLが同じ＝**全テストクラスで1つのDB**。他のクラスが残した
        // カート行が products の外部キーを握っていると、ここの deleteAll がそれだけで落ちる。
        cartItemRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        taxRateRepository.deleteAll();

        TaxRate rate = new TaxRate();
        rate.setCategory(TaxCategory.REDUCED);
        rate.setRatePercent(new BigDecimal("8"));
        rate.setEffectiveFrom(LocalDate.of(2019, 10, 1));
        taxRateRepository.save(rate);
        TaxRate standard = new TaxRate();
        standard.setCategory(TaxCategory.STANDARD);
        standard.setRatePercent(new BigDecimal("10"));
        standard.setEffectiveFrom(LocalDate.of(2019, 10, 1));
        taxRateRepository.save(standard);

        Category category = new Category();
        category.setName("雑貨");
        category.setSlug("misc");
        Category saved = categoryRepository.save(category);

        matcha = product("宇治抹茶 30g", 60, TaxCategory.REDUCED, saved);
        yunomi = product("藍染湯呑み", 20, TaxCategory.STANDARD, saved);
        hashi = product("若狭塗箸", 20, TaxCategory.STANDARD, saved);
    }

    @Test
    @DisplayName("1注文で同じ商品を上限より多く押さえられない")
    void aSingleOrderCannotTakeMoreThanTheItemCap() {
        assertThatThrownBy(() -> orderService.guestCheckout("a@example.com", Map.of(matcha.getId(), 6)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("5点まで");

        // 断ったのに引当が残っていたら、断った意味が無い
        assertThat(reserved(matcha)).isZero();
    }

    @Test
    @DisplayName("1注文に詰め込める商品の種類にも上限がある（カタログ全部を1発で押さえられない）")
    void aSingleOrderCannotCoverTheWholeCatalogue() {
        Map<Long, Integer> everything = new LinkedHashMap<>();
        everything.put(matcha.getId(), 1);
        everything.put(yunomi.getId(), 1);
        everything.put(hashi.getId(), 1);

        assertThatThrownBy(() -> orderService.guestCheckout("a@example.com", everything))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("2種類まで");

        assertThat(reserved(matcha)).isZero();
        assertThat(reserved(yunomi)).isZero();
        assertThat(reserved(hashi)).isZero();
    }

    @Test
    @DisplayName("注文を何回繰り返しても、引当だけでは在庫を 0 にできない")
    void repeatedOrdersCanNeverEmptyTheShelf() {
        // 上限に当たるまで、許される最大量で殴り続ける（＝攻撃者と同じことをする）
        int accepted = 0;
        for (int i = 0; i < 50; i++) {
            try {
                orderService.guestCheckout("attacker@example.com", Map.of(yunomi.getId(), 5));
                accepted++;
            } catch (ConflictException stopped) {
                break;
            }
        }

        assertThat(accepted).as("1件も通らないなら、普通の客も買えない").isPositive();
        // 本題: 店頭に「在庫切れ」が出ない
        assertThat(available(yunomi)).isPositive();
        assertThat(reserved(yunomi)).isLessThanOrEqualTo(10);   // 在庫20の50%
    }

    @Test
    @DisplayName("引当上限で断るときは 409 で、在庫切れ（400）と区別できる")
    void theHoldCeilingIsNotReportedAsBeingOutOfStock() {
        orderService.guestCheckout("a@example.com", Map.of(yunomi.getId(), 5));
        orderService.guestCheckout("b@example.com", Map.of(yunomi.getId(), 5));

        // 在庫は 20 のうち 10 残っているので「在庫がありません」は嘘になる。
        // 時間で解ける話なのに在庫切れと言われた客は、二度と戻って来ない。
        assertThatThrownBy(() -> orderService.guestCheckout("c@example.com", Map.of(yunomi.getId(), 1)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("仮押さえ");
        assertThat(available(yunomi)).isEqualTo(10);
    }

    @Test
    @DisplayName("本当に在庫が足りないときは従来どおり 400 と残数を返す")
    void realStockShortagesStillReadAsShortages() {
        Product rare = product("一点物", 2, TaxCategory.STANDARD, categoryRepository.findAll().getFirst());

        assertThatThrownBy(() -> orderService.guestCheckout("a@example.com", Map.of(rare.getId(), 3)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Not enough stock");
    }

    @Test
    @DisplayName("支払いが済んだ分は割合を動かさない（売れたせいで買えなくならない）")
    void payingDoesNotEatIntoTheHoldAllowance() {
        var order = orderService.guestCheckout("a@example.com", Map.of(matcha.getId(), 5));
        orderService.markPaid(order.getId(), "bank_transfer", "REF-1");

        // 確定で stock も reserved も減るので、割合の分母・分子がそろって動く
        assertThat(stock(matcha)).isEqualTo(55);
        assertThat(reserved(matcha)).isZero();

        // 直後にまた満額で買える＝「前の客が払ったせいで枠が減る」ことが無い
        assertThat(orderService.guestCheckout("b@example.com", Map.of(matcha.getId(), 5))).isNotNull();
    }

    /* ---------- helpers ---------- */

    private Product product(String name, int stock, TaxCategory tax, Category category) {
        Product p = new Product();
        p.setName(name);
        p.setDescription(name);
        p.setPrice(new BigDecimal("1000"));
        p.setStock(stock);
        p.setTaxCategory(tax);
        p.setCategory(category);
        return productRepository.save(p);
    }

    private int reserved(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getReserved();
    }

    private int stock(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getStock();
    }

    private int available(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getAvailable();
    }
}
