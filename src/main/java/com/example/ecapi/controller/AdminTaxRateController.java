package com.example.ecapi.controller;

import com.example.ecapi.domain.TaxRate;
import com.example.ecapi.dto.TaxDtos.TaxRateRequest;
import com.example.ecapi.dto.TaxDtos.TaxRateResponse;
import com.example.ecapi.exception.NotFoundException;
import com.example.ecapi.repository.TaxRateRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manage effective-dated consumption-tax rates. A rate change = add a new row (and
 * optionally close the old one via effectiveTo). Past orders are unaffected because
 * they snapshot the rate at purchase time.
 */
@Tag(name = "Admin: Tax rates", description = "消費税率の管理（有効期間つき・ADMIN限定）")
@RestController
@RequestMapping("/api/admin/tax-rates")
public class AdminTaxRateController {

    private final TaxRateRepository taxRateRepository;

    public AdminTaxRateController(TaxRateRepository taxRateRepository) {
        this.taxRateRepository = taxRateRepository;
    }

    @Operation(summary = "税率一覧")
    @GetMapping
    public List<TaxRateResponse> list() {
        return taxRateRepository.findAll().stream().map(TaxRateResponse::from).toList();
    }

    @Operation(summary = "税率を追加（例: 標準10%→12%を将来日付で予約）")
    @PostMapping
    public ResponseEntity<TaxRateResponse> create(@Valid @RequestBody TaxRateRequest req) {
        TaxRate r = new TaxRate();
        apply(r, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(TaxRateResponse.from(taxRateRepository.save(r)));
    }

    @Operation(summary = "税率を更新")
    @PutMapping("/{id}")
    public ResponseEntity<TaxRateResponse> update(@PathVariable Long id, @Valid @RequestBody TaxRateRequest req) {
        TaxRate r = taxRateRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Tax rate not found: " + id));
        apply(r, req);
        return ResponseEntity.ok(TaxRateResponse.from(taxRateRepository.save(r)));
    }

    @Operation(summary = "税率を削除")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!taxRateRepository.existsById(id)) {
            throw new NotFoundException("Tax rate not found: " + id);
        }
        taxRateRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private void apply(TaxRate r, TaxRateRequest req) {
        r.setCategory(req.category());
        r.setRatePercent(req.ratePercent());
        r.setEffectiveFrom(req.effectiveFrom());
        r.setEffectiveTo(req.effectiveTo());
    }
}
