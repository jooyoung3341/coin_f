package com.coin.f.ai;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.coin.f.ai.AiReportService.Result;
import com.coin.f.ai.AnalysisModels.IndicatorView;
import com.coin.f.coin.CoinService;

@RestController
@RequestMapping("/api/ai")
public class AiApiController {

	private final AiReportService service;
	private final IndicatorService indicators;
	private final CoinService coinService;
	private final List<String> symbols;

	public AiApiController(AiReportService service, IndicatorService indicators, CoinService coinService,
			@Value("${app.market.symbols}") List<String> symbols) {
		this.service = service;
		this.indicators = indicators;
		this.coinService = coinService;
		this.symbols = symbols;
	}

	/** 리포트 즉시 재생성 (1분 쿨다운) */
	@PostMapping("/regenerate")
	public Result regenerate() {
		return service.regenerate();
	}

	/** 코인 상세 "현재 지표 해석" (1분 캐시). 수집 전이면 204 */
	@GetMapping("/indicators/{symbol}")
	public ResponseEntity<IndicatorView> indicators(@PathVariable String symbol) {
		String s = symbol.toUpperCase();
		if (!coinService.isAvailable(s, symbols)) {
			return ResponseEntity.notFound().build();
		}
		IndicatorView v = indicators.viewFor(s);
		return v == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(v);
	}
}
