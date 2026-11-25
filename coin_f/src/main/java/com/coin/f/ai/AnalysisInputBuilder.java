package com.coin.f.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.coin.f.ai.AnalysisModels.MarketInput;
import com.coin.f.ai.AnalysisModels.SymbolInput;
import com.coin.f.ai.AnalysisModels.SymbolTrends;
import com.coin.f.coin.CoinService;
import com.coin.f.coin.CoinService.CoinHistory;
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
import com.coin.f.storage.Rows.TimeValue;

/**
 * 이미 수집·계산된 지표들을 분석 입력 스냅샷으로 모은다.
 */
@Component
public class AnalysisInputBuilder {

	private static final Logger log = LoggerFactory.getLogger(AnalysisInputBuilder.class);
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
		List<MarketSignal> mine = signalService.currentSignals().stream()
				.filter(s -> s.symbol().equals(symbol))
				.toList();
		List<String> signals = mine.stream()
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
				signals,
				trends(symbol, r, mine));
	}

	/**
	 * 3일 추이(롱숏 전체·탑트레이더, 펀딩 정산) + 가격 세부(4h 고저·이동평균·4h 매수 비중) + 시그널 근거.
	 * 추이 조회 실패는 분석 전체를 막지 않도록 해당 값만 null.
	 */
	private SymbolTrends trends(String symbol, Range r, List<MarketSignal> sigs) {
		long now = System.currentTimeMillis();
		List<TimeValue> ls = List.of();
		List<TimeValue> top = List.of();
		try {
			CoinHistory h = coinService.getHistory(symbol, 3);
			ls = h.longShort();
			top = h.topLongShort();
		} catch (RuntimeException e) {
			log.debug("{} 롱숏 추이 조회 실패: {}", symbol, e.toString());
		}
		List<TimeValue> funding = List.of();
		try {
			funding = fundingService.history(symbol, 3);
		} catch (RuntimeException e) {
			log.debug("{} 펀딩 이력 조회 실패: {}", symbol, e.toString());
		}

		Double fundingAvg = funding.isEmpty() ? null
				: funding.stream().mapToDouble(TimeValue::value).average().orElse(0);
		List<String> reasons = sigs.stream()
				.map(s -> s.type().getLabel() + ": " + s.reason())
				.toList();

		return new SymbolTrends(
				// 이력이 2.5일 미만이면 "3일 전" 비교는 의미가 없어 null
				pctAt(ls, now - 24 * HOUR),
				ls.isEmpty() || ls.get(0).ts() > now - 60 * HOUR ? null : ls.get(0).value() * 100,
				ls.isEmpty() ? null : ls.stream().mapToDouble(TimeValue::value).min().getAsDouble() * 100,
				ls.isEmpty() ? null : ls.stream().mapToDouble(TimeValue::value).max().getAsDouble() * 100,
				top.isEmpty() ? null : top.get(top.size() - 1).value() * 100, pctAt(top, now - 24 * HOUR),
				fundingAvg, funding.size(),
				r.high4h, r.low4h, r.ma24h, r.ma7d, r.takerBuyShare4h, r.change4h,
				reasons);
	}

	/** ts 시점 이전의 마지막 값(%) — 시간 오름차순 이력 기준, 없으면 null */
	private static Double pctAt(List<TimeValue> series, long ts) {
		Double v = null;
		for (TimeValue p : series) {
			if (p.ts() > ts) {
				break;
			}
			v = p.value() * 100;
		}
		return v;
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
		r.takerBuyShare24h = takerShare(d);
		List<Kline> h4 = k.subList(Math.max(0, k.size() - 4), k.size());
		r.high4h = h4.stream().mapToDouble(Kline::high).max().orElse(r.price);
		r.low4h = h4.stream().mapToDouble(Kline::low).min().orElse(r.price);
		r.takerBuyShare4h = takerShare(h4);
		// 4시간 전 종가 대비 (마지막 봉은 진행 중)
		double close4hAgo = k.size() >= 5 ? k.get(k.size() - 5).close() : 0;
		r.change4h = close4hAgo == 0 ? 0 : (r.price - close4hAgo) / close4hAgo * 100;
		r.ma24h = d.stream().mapToDouble(Kline::close).average().orElse(r.price);
		r.ma7d = k.stream().mapToDouble(Kline::close).average().orElse(r.price);
		return r;
	}

	/** 시장가 매수 거래대금 / 전체 거래대금 (0~1) */
	private static double takerShare(List<Kline> bars) {
		double buy = bars.stream().mapToDouble(x -> x.takerBuyVolume() * x.close()).sum();
		double all = bars.stream().mapToDouble(Kline::quoteVolume).sum();
		return all == 0 ? 0.5 : buy / all;
	}

	private static final class Range {
		double price;
		double change24h;
		double high24h;
		double low24h;
		double high7d;
		double low7d;
		double takerBuyShare24h = 0.5;
		double high4h;
		double low4h;
		double ma24h;
		double ma7d;
		double takerBuyShare4h = 0.5;
		double change4h;
	}
}
