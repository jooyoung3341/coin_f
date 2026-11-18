package com.coin.f.liquidation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.coin.f.liquidation.LiquidationService.Distribution;
import com.coin.f.liquidation.LiquidationService.Overview;

/**
 * 청산 분석 JSON API. 실시간 목록은 /api/market/liquidations 사용.
 */
@RestController
@RequestMapping("/api/liquidation")
public class LiquidationApiController {

	private final LiquidationService service;

	public LiquidationApiController(LiquidationService service) {
		this.service = service;
	}

	@GetMapping("/overview")
	public Overview overview() {
		return service.overview();
	}

	@GetMapping("/distribution")
	public Distribution distribution(@RequestParam(defaultValue = "BTCUSDT") String symbol) {
		return service.distribution(symbol.toUpperCase());
	}
}
