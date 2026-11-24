package com.example.ecapi.payment.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ecapi.config.StripeProperties;
import com.example.ecapi.domain.AppSetting;
import com.example.ecapi.domain.Category;
import com.example.ecapi.domain.Coupon;
import com.example.ecapi.domain.DiscountType;
import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.PricingMode;
import com.example.ecapi.domain.Product;
import com.example.ecapi.domain.TaxCategory;
import com.example.ecapi.domain.TaxRate;
import com.example.ecapi.exception.BadRequestException;
import com.example.ecapi.payment.MinorUnits;
import com.example.ecapi.payment.PaymentProperties;
import com.example.ecapi.repository.AppSettingRepository;
import com.example.ecapi.repository.CategoryRepository;
import com.example.ecapi.repository.CouponRepository;
import com.example.ecapi.repository.OrderRepository;
import com.example.ecapi.repository.ProductRepository;
import com.example.ecapi.repository.TaxRateRepository;
import com.example.ecapi.service.OrderService;
import com.example.ecapi.service.SettingService;
import com.example.ecapi.service.ShippingSettings;
import com.stripe.param.checkout.SessionCreateParams;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Stripe へ渡す請求額が、注文に保存した合計（割引・送料・税を含む）と一致すること。
 *
 * <p>共有の H2（{@code DB_CLOSE_DELAY=-1}）を汚さないよう、税の表示方式・送料・送料無料の基準額を
 * 控えて {@link #restore()} で戻し、作った注文・クーポン・商品・カテゴリ・税率は自分で消す。
 */
@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "app.order.expiry-sweep-ms=3600000"
})
class StripeAmountMatchesOrderTotalTest {

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired ProductRepository productRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired TaxRateRepository taxRateRepository;
    @Autowired CouponRepository couponRepository;
    @Autowired SettingService settingService;
    @Autowired ShippingSettings shippingSettings;
    @Autowired AppSettingRepository appSettingRepository;

    /** 設定の行ごとの元の値（元から無かった行は null）。後片付けで行単位に戻す。 */
    private static final List<String> SETTING_KEYS =
            List.of("tax.pricing-mode", "shipping.fee", "shipping.free-threshold");
    private final Map<String, String> savedRows = new HashMap<>();

    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> couponIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> taxRateIds = new ArrayList<>();
    private Category category;
    private Product matcha;   // 1,620円 / 軽減
    private Product yunomi;   // 2,750円 / 標準

    private final StripeProperties props = new StripeProperties();
    private StripePaymentProvider provider;

    @BeforeEach
    void setUp() {
        savedRows.clear();
        for (String key : SETTING_KEYS) {
            savedRows.put(key, appSettingRepository.findById(key).map(AppSetting::getValue).orElse(null));
        }
        shippingSettings.setFreeThreshold(BigDecimal.ZERO);

        // 他クラスの残した税率より後の適用開始日にして、こちらが必ず選ばれるようにする
        taxRateIds.add(taxRateRepository.save(taxRate(TaxCategory.STANDARD, "10.00")).getId());
        taxRateIds.add(taxRateRepository.save(taxRate(TaxCategory.REDUCED, "8.00")).getId());

        Category c = new Category();
        c.setName("ec01");
        c.setSlug("ec01-" + UUID.randomUUID());
        category = categoryRepository.save(c);

        matcha = product("宇治抹茶 30g", "1620", TaxCategory.REDUCED);
        yunomi = product("藍染湯呑み 二客組", "2750", TaxCategory.STANDARD);

        props.setSuccessUrl("http://localhost/success");
        props.setCancelUrl("http://localhost/cancel");
        provider = new StripePaymentProvider(props, new PaymentProperties());
    }

    @AfterEach
    void restore() {
        orderIds.forEach(orderRepository::deleteById);
        couponIds.forEach(couponRepository::deleteById);
        productIds.forEach(productRepository::deleteById);
        categoryRepository.deleteById(category.getId());
        taxRateIds.forEach(taxRateRepository::deleteById);

        // 元から無かった行を、このコンテキストの既定値で作ってしまわないよう、行ごとに戻す
        savedRows.forEach((key, value) -> {
            if (value != null) {
                appSettingRepository.save(new AppSetting(key, value));
            } else if (appSettingRepository.existsById(key)) {
                appSettingRepository.deleteById(key);
            }
        });
    }

    static Stream<Arguments> combinations() {
        List<Arguments> all = new ArrayList<>();
        for (PricingMode mode : PricingMode.values()) {
            for (String coupon : new String[] {"NONE", "PERCENT", "FIXED", "FREE_SHIPPING"}) {
                for (String fee : new String[] {"0", "600"}) {
                    all.add(Arguments.of(mode, coupon, fee));
                }
            }
        }
        return all.stream();
    }

    @ParameterizedTest(name = "{0} / クーポン {1} / 送料 {2}")
    @MethodSource("combinations")
    void chargedAmountEqualsOrderTotal(PricingMode mode, String couponKind, String fee) {
        settingService.setPricingMode(mode);
        shippingSettings.setFee(new BigDecimal(fee));
        String code = switch (couponKind) {
            case "PERCENT" -> coupon("EC01PCT", DiscountType.PERCENT, "10");
            case "FIXED" -> coupon("EC01FIX", DiscountType.FIXED, "500");
            case "FREE_SHIPPING" -> coupon("EC01FREE", DiscountType.FREE_SHIPPING, "0");
            default -> null;
        };

        Order order = place(code, 2, 1);

        // 前提が空振りしていないこと（割引・送料が実際に注文へ乗っている）
        if ("PERCENT".equals(couponKind) || "FIXED".equals(couponKind)) {
            assertThat(order.getDiscountAmount()).isPositive();
        }
        if (!"0".equals(fee) && !"FREE_SHIPPING".equals(couponKind)) {
            assertThat(order.getShippingAmount()).isPositive();
        }
        if ("FREE_SHIPPING".equals(couponKind)) {
            assertThat(order.getShippingAmount()).isZero();
        }

        SessionCreateParams params = provider.buildParams(order, "jpy");

        long charged = params.getLineItems().stream()
                .mapToLong(li -> li.getPriceData().getUnitAmount() * li.getQuantity())
                .sum();
        assertThat(charged).isEqualTo(MinorUnits.of(order.getTotalAmount(), "jpy"));
        assertThat(params.getLineItems()).hasSize(1);
        // 商品は 2 種類（宇治抹茶 ＋ 藍染湯呑み）なので「ほか 1 種」になる
        assertThat(params.getLineItems().get(0).getPriceData().getProductData().getDescription())
                .contains("ほか 1 種").doesNotContain("ほか 1 点");
    }

    @Test
    void zeroTotalOrderIsRejectedBeforeStripeIsCalled() {
        settingService.setPricingMode(PricingMode.INCLUSIVE);
        shippingSettings.setFee(BigDecimal.ZERO);
        String code = coupon("EC01ALL", DiscountType.FIXED, "100000");

        Order order = place(code, 1, 0);
        assertThat(order.getTotalAmount()).isZero();

        // createSession より前の buildParams の段で止まる（Session.create には届かない）
        assertThatThrownBy(() -> provider.buildParams(order, "jpy"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("この注文はカードでお支払いいただけません");
    }

    // --- helpers -------------------------------------------------------------

    private Order place(String couponCode, int matchaQty, int yunomiQty) {
        Map<Long, Integer> lines = new LinkedHashMap<>();
        lines.put(matcha.getId(), matchaQty);
        if (yunomiQty > 0) {
            lines.put(yunomi.getId(), yunomiQty);
        }
        Order order = orderService.guestCheckout("guest@example.com", lines, couponCode);
        orderIds.add(order.getId());
        return order;
    }

    private String coupon(String code, DiscountType type, String value) {
        Coupon c = new Coupon();
        c.setCode(code);
        c.setDiscountType(type);
        c.setValue(new BigDecimal(value));
        couponIds.add(couponRepository.save(c).getId());
        return code;
    }

    private Product product(String name, String price, TaxCategory tax) {
        Product p = new Product();
        p.setName(name);
        p.setDescription(name);
        p.setPrice(new BigDecimal(price));
        p.setStock(100);
        p.setTaxCategory(tax);
        p.setCategory(category);
        Product saved = productRepository.save(p);
        productIds.add(saved.getId());
        return saved;
    }

    private TaxRate taxRate(TaxCategory taxCategory, String percent) {
        TaxRate r = new TaxRate();
        r.setCategory(taxCategory);
        r.setRatePercent(new BigDecimal(percent));
        r.setEffectiveFrom(LocalDate.of(2020, 1, 1));
        return r;
    }
}
