package com.coin.f.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.coin.f.ai.AnalysisModels.MarketInput;
import com.coin.f.ai.AnalysisModels.SymbolInput;
import com.coin.f.coin.CoinService;
import com.coin.f.coin.CoinService.CoinSummary;
import com.coin.f.funding.FundingService;
import com.coin.f.funding.FundingService.Stats;
import com.coin.f.market.LiquidationStore;
import com.coin.f.market.MarketDataProvider;
import com.coin.f.market.MarketModels.Kline;
import com.coin.f.market.MarketModels.LiquidationStats;
import com.coin.f.market.MarketModels.MarketSummary;
import com.coin.f.oi.OpenInterestService;
import com.coin.f.oi.OpenInterestService.OiRow;
import com.coin.f.signal.MarketSignal;
import com.coin.f.signal.MarketSignal.Level;
import com.coin.f.signal.SignalService;

/**
 * 이미 수집·계산된 지표들을 분석 입력 스냅샷으로 모은다.
 */
@Component
public class AnalysisInputBuilder {

	private static final long HOUR = 3_600_000L;

	private final MarketDataProvider provider;
	private final FundingService fundingService;
	private final OpenInterestService oiService;
	private final SignalService signalService;
	private final CoinService coinService;
	private final LiquidationStore liquidations;

	public AnalysisInputBuilder(MarketDataProvider provider, FundingService fundingService,
			OpenInterestService oiService, SignalService signalService, CoinService coinService,
			LiquidationStore liquidations) {
		this.provider = provider;
		this.fundingService = fundingService;
		this.oiService = oiService;
		this.signalService = signalService;
		this.coinService = coinService;
		this.liquidations = liquidations;
	}

	/** 분석에 필요한 최소 데이터(시세 + OI)가 모였는지 */
	public boolean isReady() {
		return provider.lastUpdated() > 0 && signalService.isReady();
	}

	public MarketInput market() {
		long now = System.currentTimeMillis();
		MarketSummary sum = provider.getMarketSummary();
		Stats fs = fundingService.getView().stats();
		Range btc = range("BTCUSDT");
		LiquidationStats l1 = liquidations.stats(HOUR);
		LiquidationStats l24 = liquidations.stats(24 * HOUR);
		double collectedHours = (now - liquidations.collectingSince()) / (double) HOUR;

		List<MarketSignal> signals = signalService.currentSignals();
		Map<String, Integer> byType = new LinkedHashMap<>();
		signals.forEach(s -> byType.merge(s.type().getLabel(), 1, Integer::sum));

		return new MarketInput(now,
				btc.price, btc.change24h, btc.high24h, btc.low24h, btc.high7d, btc.low7d,
				sum.sentiment().score(), sum.sentiment().label(),
				sum.totalVolume24h(),
				sum.totalOpenInterest(), sum.openInterestChange24h(), sum.oiUniverseSize(),
				fs.median8h(), fs.positivePercent(), fs.hotLong(), fs.hotShort(),
				l1.longUsd(), l1.shortUsd(), l24.longUsd(), l24.shortUsd(), collectedHours,
				count(signals, Level.HIGH), count(signals, Level.MEDIUM), count(signals, Level.LOW),
				byType);
	}

	public SymbolInput symbol(String symbol) {
		CoinSummary c = coinService.getSummary(symbol);
		Range r = range(symbol);
		OiRow oi = oiService.getView().rows().stream().filter(x -> x.symbol().equals(symbol)).findFirst()
				.orElse(null);
		// 추적 대상이 아닌 종목: 24h 변화만 직접 조회값으로 (1h·4h 는 없음)
		Double oiUsd = oi == null ? null : oi.openInterestUsd();
		Double oi1h = oi == null ? null : oi.change1h();
		Double oi4h = oi == null ? null : oi.change4h();
		Double oi24h = oi == null ? null : oi.change24h();
		if (oi == null && c.openInterest() != null) {
			oiUsd = c.openInterest().openInterestUsd();
			oi24h = c.openInterest().changePercent24h();
		}
		List<String> signals = signalService.currentSignals().stream()
				.filter(s -> s.symbol().equals(symbol))
				.map(s -> s.type().getLabel() + "(" + s.level().getLabel() + ")")
				.toList();

		return new SymbolInput(symbol, System.currentTimeMillis(),
				r.price, r.change24h, r.high24h, r.low24h, r.high7d, r.low7d,
				c.funding() == null ? null : c.funding().rate(),
				c.funding() == null ? null : c.funding().intervalHours(),
				c.funding() == null ? null : c.funding().rate8h(),
				oiUsd, oi1h, oi4h, oi24h,
				c.longShort() == null ? null : c.longShort().longPercent(),
				r.takerBuyShare24h,
				c.liq1h().longUsd(), c.liq1h().shortUsd(), c.liq24h().longUsd(), c.liq24h().shortUsd(),
				(System.currentTimeMillis() - liquidations.collectingSince()) / (double) HOUR,
				signals);
	}

	private static int count(List<MarketSignal> list, Level level) {
		return (int) list.stream().filter(s -> s.level() == level).count();
	}

	/** 1시간봉 7일치로 현재가·24h/7d 고저·24h 시장가 매수 비중 계산 */
	private Range range(String symbol) {
		List<Kline> k = provider.getKlines(symbol, "1h", 168);
		Range r = new Range();
		if (k.isEmpty()) {
			return r;
		}
		r.price = k.get(k.size() - 1).close();
		r.high7d = k.stream().mapToDouble(Kline::high).max().orElse(r.price);
		r.low7d = k.stream().mapToDouble(Kline::low).min().orElse(r.price);
		List<Kline> d = k.subList(Math.max(0, k.size() - 24), k.size());
		r.high24h = d.stream().mapToDouble(Kline::high).max().orElse(r.price);
		r.low24h = d.stream().mapToDouble(Kline::low).min().orElse(r.price);
		double open24 = d.get(0).open();
		r.change24h = open24 == 0 ? 0 : (r.price - open24) / open24 * 100;
		double buy = d.stream().mapToDouble(x -> x.takerBuyVolume() * x.close()).sum();
		double all = d.stream().mapToDouble(Kline::quoteVolume).sum();
		r.takerBuyShare24h = all == 0 ? 0.5 : buy / all;
		return r;
	}

	private static final class Range {
		double price;
		double change24h;
		double high24h;
		double low24h;
		double high7d;
		double low7d;
		double takerBuyShare24h = 0.5;
	}
}
