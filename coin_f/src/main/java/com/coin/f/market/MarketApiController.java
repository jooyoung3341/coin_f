package com.coin.f.market;

import java.util.List;
import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.coin.f.dashboard.DashboardService;
import com.coin.f.market.MarketModels.Kline;
import com.coin.f.market.MarketModels.Liquidation;
import com.coin.f.market.MarketModels.LiquidationStats;
import com.coin.f.market.MarketModels.Ticker;

/**
 * 화면(JavaScript)에서 호출하는 JSON API
 */
@RestController
@RequestMapping("/api/market")
public class MarketApiController {

	private static final Set<String> INTERVALS = Set.of("1m", "5m", "15m", "1h", "4h", "1d");

	private final MarketDataProvider provider;
	private final DashboardService dashboardService;
	private final LiquidationStore liquidationStore;

	public MarketApiController(MarketDataProvider provider, DashboardService dashboardService,
			LiquidationStore liquidationStore) {
		this.provider = provider;
		this.dashboardService = dashboardService;
		this.liquidationStore = liquidationStore;
	}

	@GetMapping("/klines")
	public List<Kline> klines(@RequestParam(defaultValue = "BTCUSDT") String symbol,
			@RequestParam(defaultValue = "1h") String interval,
			@RequestParam(defaultValue = "168") int limit) {
		String iv = INTERVALS.contains(interval) ? interval : "1h";
		int lim = Math.max(10, Math.min(limit, 500));
		return provider.getKlines(symbol.toUpperCase(), iv, lim);
	}

	/**
	 * 현재가 갱신용. symbols 미지정 = 기본 종목, 지정 시 해당 종목(노출 대상만, 최대 10개)
	 */
	@GetMapping("/tickers")
	public TickersResponse tickers(@RequestParam(required = false) List<String> symbols) {
		if (symbols == null || symbols.isEmpty()) {
			return new TickersResponse(provider.lastUpdated(), dashboardService.getTickers());
		}
		List<String> req = symbols.stream().map(String::toUpperCase).filter(provider::isVisible).distinct()
				.limit(10).toList();
		return new TickersResponse(provider.lastUpdated(), provider.getTickers(req));
	}

	/** 최근 청산 (최신순) + 1시간 집계 */
	@GetMapping("/liquidations")
	public LiquidationsResponse liquidations(@RequestParam(defaultValue = "50") int limit) {
		return new LiquidationsResponse(provider.getLiquidationStats(3_600_000L),
				liquidationStore.recent(Math.max(1, Math.min(limit, 500))));
	}

	public record TickersResponse(long updatedAt, List<Ticker> tickers) {
	}

	public record LiquidationsResponse(LiquidationStats lastHour, List<Liquidation> recent) {
	}
}
