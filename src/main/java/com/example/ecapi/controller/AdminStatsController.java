package com.example.ecapi.controller;

import com.example.ecapi.stats.StatsDtos.StoreStats;
import com.example.ecapi.stats.StatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin: Stats", description = "売上と注文の集計（ADMIN のみ）")
@RestController
@RequestMapping("/api/admin/stats")
public class AdminStatsController {

    /** 月別推移の表示範囲。長くしても読めないし、短いと季節性が見えない。 */
    private static final int MIN_MONTHS = 1;
    private static final int MAX_MONTHS = 36;

    private final StatsService statsService;

    public AdminStatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @Operation(summary = "売上と注文の集計",
            description = "売上に数えるのは支払い済み（PAID / SHIPPED / DELIVERED）だけ。"
                    + "未払い・失効・キャンセルは件数としては返すが、金額には含めない。")
    @GetMapping
    public StoreStats stats(@RequestParam(defaultValue = "12") int months) {
        return statsService.collect(Math.clamp(months, MIN_MONTHS, MAX_MONTHS));
    }
}
