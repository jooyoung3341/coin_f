package com.coin.f.coin;

import java.util.List;

import org.springframework.stereotype.Service;

import com.coin.f.market.LiquidationStore;
import com.coin.f.market.MarketDataProvider;
import com.coin.f.market.MarketModels.FundingRate;
import com.coin.f.market.MarketModels.HistorySeries;
import com.coin.f.market.MarketModels.LiquidationStats;
import com.coin.f.market.MarketModels.LongShortRatio;
import com.coin.f.market.MarketModels.OpenInterestChange;
import com.coin.f.market.MarketModels.Ticker;
import com.coin.f.storage.MarketDataRecorder;
import com.coin.f.storage.Rows.TimeValue;

/**
 * 코인 상세 분석 (5단계)
 *  - 현재 상태: 메모리 스냅샷 (10초~5분 주기 수집)
 *  - 추이: DB (price_candle / open_interest / funding_rate / long_short_ratio)
 */
@Service
public class CoinService {

	private static final long HOUR = 3_600_000L;
	private static final long DAY = 24 * HOUR;

	private final MarketDataProvider provider;
	private final MarketDataRecorder recorder;
	private final LiquidationStore liquidations;

	public CoinService(MarketDataProvider provider, MarketDataRecorder recorder, LiquidationStore liquidations) {
		this.provider = provider;
		this.recorder = recorder;
		this.liquidations = liquidations;
	}

	public CoinSummary getSummary(String symbol) {
		Ticker ticker = provider.getTickers(List.of(symbol)).stream().findFirst().orElse(null);
		FundingRate funding = provider.getFundingRates().stream()
				.filter(f -> f.symbol().equals(symbol)).findFirst().orElse(null);
		// 추적 상위 30종목은 스냅샷, 그 외는 거래소 직접 조회(5분 캐시)
		OpenInterestChange oi = provider.openInterestOf(symbol);
		LongShortRatio ls = provider.longShortOf(symbol);
		return new CoinSummary(symbol, ticker, funding, oi, ls, liquidations.stats(HOUR, symbol),
				liquidations.stats(DAY, symbol));
	}

	/**
	 * @param days 1~30 (바이낸스 OI·롱숏 이력은 최근 30일까지만 제공)
	 */
	public CoinHistory getHistory(String symbol, int days) {
		int d = Math.max(1, Math.min(days, 30));
		long since = System.currentTimeMillis() - d * DAY;
		List<TimeValue> price = recorder.closeHistory(symbol, "1h", since);
		List<TimeValue> oi = recorder.openInterestHistory(symbol, "1h", since);
		// DB 이력이 기간의 절반도 안 되면(추적 대상이 아닌 종목) 거래소에서 직접 조회
		int expected = d * 24;
		if (price.size() < expected / 2 || oi.size() < expected / 2) {
			HistorySeries live = provider.liveHistory(symbol, d);
			if (live != null && !live.price().isEmpty()) {
				return new CoinHistory(live.price(), live.openInterest(), live.funding(), live.longShort(), "LIVE");
			}
		}
		return new CoinHistory(price, oi, recorder.fundingHistory(symbol, since),
				recorder.longShortHistory(symbol, "1h", since), "DB");
	}

	public record CoinSummary(String symbol, Ticker ticker, FundingRate funding, OpenInterestChange openInterest,
			LongShortRatio longShort, LiquidationStats liq1h, LiquidationStats liq24h) {
	}

	/**
	 * 모든 값은 시간 오름차순. longShort 는 롱 계정 비율(0~1)
	 * @param source DB(저장 이력) 또는 LIVE(거래소 직접 조회)
	 */
	public record CoinHistory(List<TimeValue> price, List<TimeValue> openInterest, List<TimeValue> funding,
			List<TimeValue> longShort, String source) {
	}

	/** 코인 상세를 볼 수 있는 종목 (기본 종목 또는 시가총액 기준 통과) */
	public boolean isAvailable(String symbol, List<String> coreSymbols) {
		return coreSymbols.contains(symbol) || provider.isVisible(symbol);
	}

	/** 코인 선택 목록: 24h 거래대금 순 */
	public List<String> selectableSymbols() {
		return provider.getAllTickers().stream()
				.sorted(java.util.Comparator.comparingDouble(
						com.coin.f.market.MarketModels.TickerDetail::quoteVolume24h).reversed())
				.map(com.coin.f.market.MarketModels.TickerDetail::symbol)
				.toList();
	}
}
