package com.example.ecapi.service;

import com.example.ecapi.domain.Coupon;
import com.example.ecapi.domain.DiscountType;
import com.example.ecapi.exception.BadRequestException;
import com.example.ecapi.exception.ConflictException;
import com.example.ecapi.exception.NotFoundException;
import com.example.ecapi.repository.CouponRepository;
import java.math.BigDecimal;
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

    public CouponService(CouponRepository repository) {
        this.repository = repository;
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
     * @param code       what the shopper typed; null or blank means "no coupon"
     * @param itemsTotal the cart's item total in the shop's display convention
     * @return the coupon, or null when no code was given
     */
    @Transactional(readOnly = true)
    public Coupon validate(String code, BigDecimal itemsTotal, LocalDate on) {
        String normalised = normalise(code);
        if (normalised == null || normalised.isEmpty()) {
            return null;
        }
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
