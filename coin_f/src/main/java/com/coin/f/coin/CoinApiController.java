package com.coin.f.coin;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.coin.f.coin.CoinService.CoinHistory;
import com.coin.f.coin.CoinService.CoinSummary;

/**
 * 코인 상세 화면 JSON API
 */
@RestController
@RequestMapping("/api/coin/{symbol}")
public class CoinApiController {

	private final CoinService coinService;
	private final List<String> symbols;

	public CoinApiController(CoinService coinService, @Value("${app.market.symbols}") List<String> symbols) {
		this.coinService = coinService;
		this.symbols = symbols;
	}

	@GetMapping("/summary")
	public ResponseEntity<CoinSummary> summary(@PathVariable String symbol) {
		String s = symbol.toUpperCase();
		return coinService.isAvailable(s, symbols) ? ResponseEntity.ok(coinService.getSummary(s))
				: ResponseEntity.notFound().build();
	}

	@GetMapping("/history")
	public ResponseEntity<CoinHistory> history(@PathVariable String symbol,
			@RequestParam(defaultValue = "7") int days) {
		String s = symbol.toUpperCase();
		return coinService.isAvailable(s, symbols) ? ResponseEntity.ok(coinService.getHistory(s, days))
				: ResponseEntity.notFound().build();
	}
}
