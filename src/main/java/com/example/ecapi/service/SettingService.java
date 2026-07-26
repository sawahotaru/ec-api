package com.example.ecapi.service;

import com.example.ecapi.domain.AppSetting;
import com.example.ecapi.domain.PricingMode;
import com.example.ecapi.repository.AppSettingRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runtime store settings. The tax pricing mode (内税/外税) can be switched by an admin
 * without a redeploy. A change only affects future orders — past orders snapshot their
 * mode, so history is never rewritten.
 */
@Service
public class SettingService {

    static final String KEY_PRICING_MODE = "tax.pricing-mode";

    private final AppSettingRepository repository;
    private final PricingMode defaultPricingMode;

    public SettingService(AppSettingRepository repository,
                          @Value("${app.tax.pricing-mode:INCLUSIVE}") PricingMode defaultPricingMode) {
        this.repository = repository;
        this.defaultPricingMode = defaultPricingMode;
    }

    /** Current mode: the stored value, or the config default if never set. */
    @Transactional(readOnly = true)
    public PricingMode getPricingMode() {
        return repository.findById(KEY_PRICING_MODE)
                .map(s -> parse(s.getValue()))
                .orElse(defaultPricingMode);
    }

    @Transactional
    public PricingMode setPricingMode(PricingMode mode) {
        repository.save(new AppSetting(KEY_PRICING_MODE, mode.name()));
        return mode;
    }

    private PricingMode parse(String value) {
        try {
            return PricingMode.valueOf(value);
        } catch (IllegalArgumentException e) {
            return defaultPricingMode;
        }
    }
}
