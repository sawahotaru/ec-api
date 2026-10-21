package com.example.ecapi.service;

import com.example.ecapi.domain.Coupon;
import com.example.ecapi.domain.DiscountType;
import com.example.ecapi.exception.BadRequestException;
import com.example.ecapi.exception.ConflictException;
import com.example.ecapi.exception.NotFoundException;
import com.example.ecapi.exception.TooManyAttemptsException;
import com.example.ecapi.repository.CouponRepository;
import com.example.ecapi.security.AttemptLimiter;
import com.example.ecapi.security.ClientKey;
import com.example.ecapi.security.CouponAttemptThrottle;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Coupon lookup, validation and redemption.
 *
 * <p>Validation is deliberately in one place and used by both the quote endpoint and
 * checkout. A quote that accepts a code the checkout then rejects is worse than no quote
 * at all, and that only stays true if there is a single implementation of "is this code
 * usable for this cart".
 */
@Service
public class CouponService {

    private final CouponRepository repository;
    private final CouponAttemptThrottle throttle;

    public CouponService(CouponRepository repository, CouponAttemptThrottle throttle) {
        this.repository = repository;
        this.throttle = throttle;
    }

    /** Codes are entered by hand; treat them case- and whitespace-insensitively. */
    public static String normalise(String code) {
        return code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    }

    @Transactional(readOnly = true)
    public List<Coupon> findAll() {
        return repository.findAll(Sort.by(Sort.Direction.DESC, "id"));
    }

    @Transactional(readOnly = true)
    public Coupon get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Coupon not found: " + id));
    }

    /**
     * Resolves a code for a cart, or explains why it does not apply.
     *
     * <p>The messages name the actual reason (expired / minimum not met / used up) rather
     * than a single "invalid code". This is a shop, not a login form: there is nothing to
     * protect by being vague, and "使えません" with no reason is the kind of thing that
     * makes a customer abandon a cart instead of adding one more item.
     *
     * <p>⚠ 理由を返すことと<strong>無制限に試せること</strong>は別の話。未認証で叩ける
     * 見積もりがこの判定をそのまま通すので、理由の違いを使ってコードを列挙できた。
     * 理由はそのままに、<strong>拒否された回数</strong>を送信元ごとに数えて止める
     * （{@link CouponAttemptThrottle}）。validate がここ1箇所なので、見積もりと注文の
     * どちらから来ても同じ計数に乗る——片方だけ素通りする経路が作れない。
     *
     * @param code       what the shopper typed; null or blank means "no coupon"
     * @param itemsTotal the cart's item total in the shop's display convention
     * @return the coupon, or null when no code was given
     */
    @Transactional(readOnly = true)
    public Coupon validate(String code, BigDecimal itemsTotal, LocalDate on) {
        String normalised = normalise(code);
        if (normalised == null || normalised.isEmpty()) {
            // コードを出していない人は数えない（カートを触るたびに呼ばれる経路なので）
            return null;
        }
        String client = ClientKey.current();
        if (ClientKey.INTERNAL.equals(client)) {
            // HTTP 要求の外（スケジューラ・内部呼び出し）。外から到達できる経路は必ず
            // 要求の中を通るので、ここを数えないことで抜け道は増えない。逆に数えると
            // 裏の処理の失敗が、実在の客を締め出す枠を食う。
            return resolve(normalised, itemsTotal, on);
        }
        Instant until = throttle.lockedUntil(client);
        if (until != null) {
            throw new TooManyAttemptsException("クーポンコードの入力を続けて間違えています。約"
                    + AttemptLimiter.minutesUntil(until) + "分後に再度お試しください。");
        }
        try {
            Coupon coupon = resolve(normalised, itemsTotal, on);
            throttle.reset(client);
            return coupon;
        } catch (BadRequestException rejected) {
            // 「見つからない」も「期限切れ」も等しく1回と数える。片方だけ数えると
            // 数えないほうの応答が、そのままコードの存在を答える計数器になる。
            throttle.registerFailure(client);
            throw rejected;
        }
    }

    /** 判定そのもの。回数制限（{@link #validate}）と分けてあるのは、数え方を1箇所に寄せるため。 */
    private Coupon resolve(String normalised, BigDecimal itemsTotal, LocalDate on) {
        Coupon coupon = repository.findByCode(normalised)
                .orElseThrow(() -> new BadRequestException("クーポンコードが見つかりません: " + normalised));

        if (!coupon.isEnabled()) {
            throw new BadRequestException("このクーポンは現在ご利用いただけません");
        }
        if (coupon.getValidFrom() != null && on.isBefore(coupon.getValidFrom())) {
            throw new BadRequestException("このクーポンはまだご利用いただけません（"
                    + coupon.getValidFrom() + " から）");
        }
        // validTo は排他（TaxRate と同じ流儀）。終了日当日はもう使えない。
        if (coupon.getValidTo() != null && !on.isBefore(coupon.getValidTo())) {
            throw new BadRequestException("このクーポンは有効期限が切れています");
        }
        if (coupon.isExhausted()) {
            throw new BadRequestException("このクーポンは利用上限に達しました");
        }
        if (coupon.getMinSubtotal() != null && itemsTotal.compareTo(coupon.getMinSubtotal()) < 0) {
            throw new BadRequestException("このクーポンは商品合計 "
                    + coupon.getMinSubtotal().stripTrailingZeros().toPlainString()
                    + " 円以上でご利用いただけます");
        }
        return coupon;
    }

    /**
     * Claims a redemption at checkout.
     *
     * <p>Separate from {@link #validate} on purpose: validation runs on every quote, but
     * the count must only move when an order is actually created. Losing the race here is
     * a 409 rather than a 400 — nothing about the request was wrong, someone else just got
     * the last one first.
     */
    @Transactional
    public void redeem(Coupon coupon) {
        if (coupon == null) {
            return;
        }
        if (repository.redeem(coupon.getId()) == 0) {
            throw new ConflictException("このクーポンは利用上限に達しました");
        }
    }

    /** Returns a redemption when an order is cancelled or expires unpaid. */
    @Transactional
    public void release(String code) {
        if (code != null && !code.isBlank()) {
            repository.release(normalise(code));
        }
    }

    @Transactional
    public Coupon create(Coupon coupon) {
        coupon.setCode(requireCode(coupon.getCode()));
        if (repository.existsByCode(coupon.getCode())) {
            throw new ConflictException("同じコードのクーポンが既にあります: " + coupon.getCode());
        }
        validateShape(coupon);
        return repository.save(coupon);
    }

    @Transactional
    public Coupon update(Long id, Coupon changes) {
        Coupon coupon = get(id);
        String code = requireCode(changes.getCode());
        if (!code.equals(coupon.getCode()) && repository.existsByCode(code)) {
            throw new ConflictException("同じコードのクーポンが既にあります: " + code);
        }
        coupon.setCode(code);
        coupon.setDescription(changes.getDescription());
        coupon.setDiscountType(changes.getDiscountType());
        coupon.setValue(changes.getValue());
        coupon.setMinSubtotal(changes.getMinSubtotal());
        coupon.setValidFrom(changes.getValidFrom());
        coupon.setValidTo(changes.getValidTo());
        coupon.setMaxRedemptions(changes.getMaxRedemptions());
        coupon.setEnabled(changes.isEnabled());
        validateShape(coupon);
        return repository.save(coupon);
    }

    @Transactional
    public void delete(Long id) {
        repository.delete(get(id));
    }

    private String requireCode(String code) {
        String normalised = normalise(code);
        if (normalised == null || normalised.isEmpty()) {
            throw new BadRequestException("クーポンコードを入力してください");
        }
        return normalised;
    }

    private void validateShape(Coupon coupon) {
        if (coupon.getDiscountType() == DiscountType.PERCENT
                && coupon.getValue().compareTo(new BigDecimal("100")) > 0) {
            throw new BadRequestException("割引率は 100% を超えられません");
        }
        if (coupon.getValue().signum() < 0) {
            throw new BadRequestException("割引額はマイナスにできません");
        }
        if (coupon.getValidFrom() != null && coupon.getValidTo() != null
                && !coupon.getValidFrom().isBefore(coupon.getValidTo())) {
            // 終了日は排他なので from == to だと1日も使えない。設定ミスをここで止める。
            throw new BadRequestException("終了日は開始日より後にしてください（終了日はその日を含みません）");
        }
    }
}
