package com.coin.f.dashboard;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.coin.f.market.MarketDataProvider;
import com.coin.f.market.MarketModels.FundingRate;
import com.coin.f.market.MarketModels.LiquidationStats;
import com.coin.f.market.MarketModels.LongShortRatio;
import com.coin.f.market.MarketModels.MarketSummary;
import com.coin.f.market.MarketModels.OpenInterestChange;
import com.coin.f.market.MarketModels.Ticker;
import com.coin.f.signal.MarketSignal;
import com.coin.f.signal.SignalService;

/**
 * 메인 대시보드 데이터 조립
 */
@Service
public class DashboardService {

	private static final int RANK_SIZE = 5;
	private static final long LIQ_WINDOW = 3_600_000L; // 1시간

	private final MarketDataProvider provider;
	private final SignalService signalService;
	private final List<String> symbols;

	public DashboardService(MarketDataProvider provider, SignalService signalService,
			@Value("${app.market.symbols}") List<String> symbols) {
		this.provider = provider;
		this.signalService = signalService;
		this.symbols = symbols;
	}

	public DashboardView getDashboard() {
		List<FundingRate> fundings = provider.getFundingRates();
		List<OpenInterestChange> ois = provider.getOpenInterestChanges();
		List<LongShortRatio> ratios = provider.getLongShortRatios();

		// 롱숏 카드는 기본 종목만, 시그널 탐지는 추적 종목 전체
		Map<String, LongShortRatio> ratioBy = ratios.stream()
				.collect(Collectors.toMap(LongShortRatio::symbol, Function.identity(), (a, b) -> a));
		List<LongShortRatio> coreRatios = symbols.stream().map(ratioBy::get).filter(r -> r != null).toList();

		return new DashboardView(
				provider.sourceName(),
				provider.isSample(),
				provider.lastUpdated(),
				provider.getTickers(symbols),
				provider.getMarketSummary(),
				provider.getLiquidationStats(LIQ_WINDOW),
				fundings.stream().sorted(Comparator.comparingDouble(FundingRate::rate8h).reversed())
						.limit(RANK_SIZE).toList(),
				fundings.stream().sorted(Comparator.comparingDouble(FundingRate::rate8h))
						.limit(RANK_SIZE).toList(),
				ois.stream().filter(o -> o.changePercent24h() > 0)
						.sorted(Comparator.comparingDouble(OpenInterestChange::changePercent24h).reversed())
						.limit(RANK_SIZE).toList(),
				ois.stream().filter(o -> o.changePercent24h() < 0)
						.sorted(Comparator.comparingDouble(OpenInterestChange::changePercent24h))
						.limit(RANK_SIZE).toList(),
				coreRatios,
				signalService.currentSignals());
	}

	public List<Ticker> getTickers() {
		return provider.getTickers(symbols);
	}

	public record DashboardView(
			String source,
			boolean sample,
			long lastUpdated,
			List<Ticker> tickers,
			MarketSummary summary,
			LiquidationStats liquidations,
			List<FundingRate> fundingTop,
			List<FundingRate> fundingBottom,
			List<OpenInterestChange> oiSurge,
			List<OpenInterestChange> oiDrop,
			List<LongShortRatio> longShort,
			List<MarketSignal> signals) {

		/** 아직 첫 수집 전 */
		public boolean loading() {
			return lastUpdated == 0;
		}
	}
}
