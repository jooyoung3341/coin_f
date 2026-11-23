package com.coin.f.market.binance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.coin.f.market.LiquidationStore;
import com.coin.f.market.MarketCapService;
import com.coin.f.market.MarketDataProvider;
import com.coin.f.market.MarketModels.HistorySeries;
import com.coin.f.market.MarketModels.TickerDetail;
import com.coin.f.storage.Rows.TimeValue;
import com.coin.f.market.MarketModels.FundingRate;
import com.coin.f.market.MarketModels.Kline;
import com.coin.f.market.MarketModels.LiquidationStats;
import com.coin.f.market.MarketModels.LongShortRatio;
import com.coin.f.market.MarketModels.MarketSummary;
import com.coin.f.market.MarketModels.OpenInterestChange;
import com.coin.f.market.MarketModels.Sentiment;
import com.coin.f.market.MarketModels.Ticker;
import com.coin.f.market.binance.BinanceDtos.FundingInfo;
import com.coin.f.market.binance.BinanceDtos.LongShortAccountRatio;
import com.coin.f.market.binance.BinanceDtos.OpenInterestHist;
import com.coin.f.market.binance.BinanceDtos.PremiumIndex;
import com.coin.f.market.binance.BinanceDtos.SymbolInfo;
import com.coin.f.market.binance.BinanceDtos.Ticker24h;
import com.coin.f.storage.MarketDataRecorder;
import com.coin.f.storage.Rows.LongShortRow;
import com.coin.f.storage.Rows.OpenInterestRow;
import com.coin.f.storage.Rows.SymbolRow;

/**
 * Binance Futures 실데이터 공급자.
 *
 * 요청 한도(IP 기준 2,400 weight/분)를 지키기 위해 화면 요청마다 API 를 부르지 않고,
 * 스케줄러가 주기적으로 수집한 스냅샷을 메모리에서 제공한다.
 *  - 빠른 수집 (10초): 전 종목 시세 + 펀딩비          → 약 300 weight/분
 *  - 느린 수집 (5분) : 상위 N종목 OI 이력 + 롱숏 비율 → /futures/data 한도(1,000회/5분) 내
 *  - 캔들: 요청 시 호출 + 10초 캐시
 */
@Component
@ConditionalOnProperty(name = "app.market.source", havingValue = "binance", matchIfMissing = true)
public class BinanceMarketDataProvider implements MarketDataProvider {

	private static final Logger log = LoggerFactory.getLogger(BinanceMarketDataProvider.class);
	private static final long KLINE_CACHE_MILLIS = 10_000;
	private static final long EXCHANGE_INFO_MILLIS = 3_600_000;

	private final BinanceClient client;
	private final LiquidationStore liquidations;
	private final List<String> coreSymbols;
	private final int universeSize;
	private final double minVolumeUsd;

	/** 빠른 수집 결과 */
	private record FastSnapshot(Map<String, Ticker24h> tickers, Map<String, PremiumIndex> premium, long at) {
	}

	/** 느린 수집 결과 */
	private record SlowSnapshot(List<OpenInterestChange> oi, double oiNow, double oi24hAgo,
			List<LongShortRatio> longShort, long at) {
	}

	private record CachedKlines(List<Kline> rows, long at) {
	}

	private volatile List<String> trackedSymbols;
	private volatile Set<String> tradable = Set.of();
	/** tradable 중 시가총액 기준을 통과한 종목 */
	private volatile Set<String> visible = Set.of();
	private volatile Map<String, Integer> fundingIntervals = Map.of();
	private volatile long tradableAt;
	private volatile FastSnapshot fast;
	private volatile SlowSnapshot slow;
	private final Map<String, CachedKlines> klineCache = new ConcurrentHashMap<>();

	private final MarketDataRecorder recorder;
	private final MarketCapService marketCaps;

	public BinanceMarketDataProvider(BinanceClient client, LiquidationStore liquidations, MarketDataRecorder recorder,
			MarketCapService marketCaps,
			@Value("${app.market.symbols}") List<String> coreSymbols,
			@Value("${app.binance.universe-size:30}") int universeSize,
			@Value("${app.binance.min-volume-usd:20000000}") double minVolumeUsd) {
		this.client = client;
		this.liquidations = liquidations;
		this.recorder = recorder;
		this.marketCaps = marketCaps;
		// DB 에서 복원한 과거 청산(기준 미달 종목 포함)도 집계에서 제외
		liquidations.setSymbolFilter(this::isVisible);
		this.coreSymbols = coreSymbols;
		this.trackedSymbols = List.copyOf(coreSymbols);
		this.universeSize = universeSize;
		this.minVolumeUsd = minVolumeUsd;
	}

	// ------------------------------------------------------------------ 수집

	@Scheduled(initialDelay = 0, fixedDelayString = "${app.binance.fast-interval-ms:10000}")
	public void refreshFast() {
		try {
			refreshTradableIfStale();
			// 시가총액(원화) 기준 미달 종목은 수집 단계에서 제외 → 펀딩·OI·시그널·대시보드 전체에서 안 보임
			marketCaps.refreshIfStale();
			Set<String> vis = tradable.stream().filter(marketCaps::meetsThreshold)
					.collect(Collectors.toUnmodifiableSet());
			visible = vis;
			Map<String, Ticker24h> tickers = client.tickers24h().stream()
					.filter(t -> vis.contains(t.symbol()))
					.collect(Collectors.toMap(Ticker24h::symbol, Function.identity(), (a, b) -> a));
			Map<String, PremiumIndex> premium = client.premiumIndexAll().stream()
					.filter(p -> vis.contains(p.symbol()))
					.collect(Collectors.toMap(PremiumIndex::symbol, Function.identity(), (a, b) -> a));
			fast = new FastSnapshot(tickers, premium, System.currentTimeMillis());
		} catch (RuntimeException e) {
			log.warn("Binance 시세/펀딩비 수집 실패: {}", e.toString());
		}
	}

	@Scheduled(initialDelay = 3_000, fixedDelayString = "${app.binance.slow-interval-ms:300000}")
	public void refreshSlow() {
		FastSnapshot f = fast;
		if (f == null) {
			log.info("시세 스냅샷이 아직 없어 OI/롱숏 수집을 다음 주기로 미룸");
			return;
		}
		long t0 = System.currentTimeMillis();
		List<String> universe = universe(f);
		List<OpenInterestChange> oi = new ArrayList<>();
		List<LongShortRatio> ls = new ArrayList<>();
		double sumNow = 0;
		double sumAgo = 0;

		List<OpenInterestRow> oiRows = new ArrayList<>();
		List<LongShortRow> lsRows = new ArrayList<>();

		for (String s : universe) {
			Integer sid = recorder.symbolId(s);
			try {
				// 1h 간격 25개 = 현재 + 24시간 전
				List<OpenInterestHist> h = client.openInterestHist(s, "1h", 25);
				if (h.size() >= 2) {
					double now = h.get(h.size() - 1).sumOpenInterestValue();
					double ago = h.get(0).sumOpenInterestValue();
					oi.add(new OpenInterestChange(s, now, ago == 0 ? 0 : (now - ago) / ago * 100));
					sumNow += now;
					sumAgo += ago;
				}
				if (sid != null) {
					h.forEach(x -> oiRows.add(new OpenInterestRow(sid, "1h", x.timestamp(), x.sumOpenInterest(),
							x.sumOpenInterestValue())));
				}
				// 5분 간격 → 5분 주기 수집에서 최근 2개를 받아 누락 없이 저장
				List<LongShortAccountRatio> r = client.globalLongShortAccountRatio(s, "5m", 2);
				if (!r.isEmpty()) {
					LongShortAccountRatio x = r.get(r.size() - 1);
					ls.add(new LongShortRatio(s, x.longAccount() * 100, x.shortAccount() * 100));
					if (sid != null) {
						r.forEach(y -> lsRows.add(new LongShortRow(sid, "GLOBAL_ACCOUNT", "5m", y.timestamp(),
								y.longAccount(), y.shortAccount(), y.longShortRatio())));
					}
				}
			} catch (RuntimeException e) {
				log.warn("{} OI/롱숏 수집 실패: {}", s, e.toString());
			}
		}
		slow = new SlowSnapshot(List.copyOf(oi), sumNow, sumAgo, List.copyOf(ls), System.currentTimeMillis());
		trackedSymbols = universe;
		recorder.saveOpenInterest(oiRows);
		recorder.saveLongShort(lsRows);
		log.info("OI/롱숏 수집 완료: {}종목, {}ms", universe.size(), System.currentTimeMillis() - t0);
	}

	private void refreshTradableIfStale() {
		if (!tradable.isEmpty() && System.currentTimeMillis() - tradableAt < EXCHANGE_INFO_MILLIS) {
			return;
		}
		List<SymbolInfo> infos = client.exchangeInfo().symbols().stream()
				.filter(SymbolInfo::isTradingUsdtPerpetual)
				.toList();
		Set<String> set = infos.stream().map(SymbolInfo::symbol).collect(Collectors.toUnmodifiableSet());
		if (!set.isEmpty()) {
			tradable = set;
			tradableAt = System.currentTimeMillis();
			log.info("거래 가능 USDT 무기한 선물: {}종목", set.size());
			recorder.syncSymbols(infos.stream()
					.map(i -> new SymbolRow("BINANCE", i.symbol(), i.baseAsset(), i.quoteAsset()))
					.toList());
		}
		try {
			Map<String, Integer> m = new HashMap<>();
			for (FundingInfo fi : client.fundingInfo()) {
				if (fi.fundingIntervalHours() > 0) {
					m.put(fi.symbol(), fi.fundingIntervalHours());
				}
			}
			fundingIntervals = Map.copyOf(m);
		} catch (RuntimeException e) {
			log.warn("펀딩 주기 조회 실패 (8h 로 간주): {}", e.toString());
		}
	}

	/** 정산 주기(시간). fundingInfo 에 없는 종목은 기본 8h */
	public int fundingIntervalHours(String symbol) {
		return fundingIntervals.getOrDefault(symbol, 8);
	}

	/** OI·롱숏 추적 대상 = 기본 종목 + 거래대금 상위 N */
	private List<String> universe(FastSnapshot f) {
		Set<String> set = new LinkedHashSet<>(coreSymbols);
		f.tickers().values().stream()
				.sorted(Comparator.comparingDouble(Ticker24h::quoteVolume).reversed())
				.map(Ticker24h::symbol)
				.limit(universeSize)
				.forEach(set::add);
		return List.copyOf(set);
	}

	// ------------------------------------------------------------------ 조회

	/** OI·롱숏을 추적 중인 종목 (기본 종목 + 거래대금 상위 N). 첫 수집 전에는 기본 종목만 */
	public List<String> trackedSymbols() {
		return trackedSymbols;
	}

	/** 첫 느린 수집이 끝나 추적 종목(상위 N 포함)이 확정됐는지 */
	public boolean isUniverseReady() {
		return slow != null;
	}

	/** 코인 USDT 무기한 선물인지 (주식·원자재 TRADIFI_PERPETUAL 제외). 목록 로딩 전에는 false */
	public boolean isCryptoPerpetual(String symbol) {
		return tradable.contains(symbol);
	}

	/** 코인 선물이면서 원화 시가총액 기준을 통과한 종목. 목록 로딩 전에는 false */
	@Override
	public boolean isVisible(String symbol) {
		return visible.contains(symbol);
	}

	@Override
	public List<TickerDetail> getAllTickers() {
		FastSnapshot f = fast;
		if (f == null) {
			return List.of();
		}
		return f.tickers().values().stream()
				.map(t -> new TickerDetail(t.symbol(), t.lastPrice(), t.priceChangePercent(), t.highPrice(),
						t.lowPrice(), t.quoteVolume(), t.count()))
				.toList();
	}

	@Override
	public String sourceName() {
		return "Binance USDⓈ-M Futures";
	}

	@Override
	public boolean isSample() {
		return false;
	}

	@Override
	public long lastUpdated() {
		FastSnapshot f = fast;
		return f == null ? 0 : f.at();
	}

	@Override
	public List<Ticker> getTickers(List<String> symbols) {
		FastSnapshot f = fast;
		if (f == null) {
			return List.of();
		}
		List<Ticker> out = new ArrayList<>();
		for (String s : symbols) {
			Ticker24h t = f.tickers().get(s);
			if (t != null) {
				out.add(new Ticker(s, t.lastPrice(), t.priceChangePercent(), t.quoteVolume()));
			}
		}
		return out;
	}

	@Override
	public MarketSummary getMarketSummary() {
		FastSnapshot f = fast;
		if (f == null) {
			return MarketSummary.empty();
		}
		double totalVolume = f.tickers().values().stream().mapToDouble(Ticker24h::quoteVolume).sum();
		double avgFunding = medianFunding(f);

		SlowSnapshot s = slow;
		double oiNow = s == null ? 0 : s.oiNow();
		double oiChange = s == null || s.oi24hAgo() == 0 ? 0 : (s.oiNow() - s.oi24hAgo()) / s.oi24hAgo() * 100;
		int oiCount = s == null ? 0 : s.oi().size();

		return new MarketSummary(totalVolume, oiNow, oiChange, avgFunding, sentiment(f, s, avgFunding), oiCount);
	}

	@Override
	public List<FundingRate> getFundingRates() {
		FastSnapshot f = fast;
		if (f == null) {
			return List.of();
		}
		// 거래대금이 너무 작은 종목은 펀딩비가 왜곡되기 쉬워 제외
		return f.premium().values().stream()
				.filter(p -> {
					Ticker24h t = f.tickers().get(p.symbol());
					return t != null && t.quoteVolume() >= minVolumeUsd;
				})
				.map(p -> new FundingRate(p.symbol(), p.lastFundingRate(), p.nextFundingTime(),
						fundingIntervalHours(p.symbol()), f.tickers().get(p.symbol()).quoteVolume()))
				.toList();
	}

	@Override
	public List<OpenInterestChange> getOpenInterestChanges() {
		SlowSnapshot s = slow;
		return s == null ? List.of() : s.oi();
	}

	@Override
	public List<LongShortRatio> getLongShortRatios() {
		SlowSnapshot s = slow;
		return s == null ? List.of() : s.longShort();
	}

	@Override
	public List<Kline> getKlines(String symbol, String interval, int limit) {
		if (!tradable.isEmpty() && !tradable.contains(symbol)) {
			return List.of();
		}
		String key = symbol + "|" + interval + "|" + limit;
		CachedKlines c = klineCache.get(key);
		long now = System.currentTimeMillis();
		if (c != null && now - c.at() < KLINE_CACHE_MILLIS) {
			return c.rows();
		}
		try {
			List<Kline> rows = client.klines(symbol, interval, limit);
			if (klineCache.size() > 500) {
				klineCache.clear();
			}
			klineCache.put(key, new CachedKlines(rows, now));
			return rows;
		} catch (RuntimeException e) {
			log.warn("{} {} 캔들 조회 실패: {}", symbol, interval, e.toString());
			return c != null ? c.rows() : List.of();
		}
	}

	@Override
	public LiquidationStats getLiquidationStats(long windowMillis) {
		return liquidations.stats(windowMillis);
	}

	// ------------------------------------------------------------------ 종목 1개 직접 조회 (코인 상세)
	// 추적 상위 30종목은 스냅샷/DB 를 쓰고, 그 외 노출 종목만 요청 시 조회 + 5분 캐시

	private static final long ON_DEMAND_CACHE_MILLIS = 5 * 60_000L;

	private record Cached(Object value, long at) {
	}

	private final Map<String, Cached> onDemand = new ConcurrentHashMap<>();

	@SuppressWarnings("unchecked")
	private <T> T cached(String key, java.util.function.Supplier<T> loader) {
		long now = System.currentTimeMillis();
		Cached c = onDemand.get(key);
		if (c != null && now - c.at() < ON_DEMAND_CACHE_MILLIS) {
			return (T) c.value();
		}
		try {
			T v = loader.get();
			if (onDemand.size() > 300) {
				onDemand.clear();
			}
			onDemand.put(key, new Cached(v, now));
			return v;
		} catch (RuntimeException e) {
			log.warn("{} 직접 조회 실패: {}", key, e.toString());
			return c == null ? null : (T) c.value(); // 실패 시 이전 값
		}
	}

	@Override
	public OpenInterestChange openInterestOf(String symbol) {
		OpenInterestChange tracked = MarketDataProvider.super.openInterestOf(symbol);
		if (tracked != null || !isVisible(symbol)) {
			return tracked;
		}
		return cached("oi|" + symbol, () -> {
			List<OpenInterestHist> h = client.openInterestHist(symbol, "1h", 25);
			if (h.size() < 2) {
				return null;
			}
			double now = h.get(h.size() - 1).sumOpenInterestValue();
			double ago = h.get(0).sumOpenInterestValue();
			return new OpenInterestChange(symbol, now, ago == 0 ? 0 : (now - ago) / ago * 100);
		});
	}

	@Override
	public LongShortRatio longShortOf(String symbol) {
		LongShortRatio tracked = MarketDataProvider.super.longShortOf(symbol);
		if (tracked != null || !isVisible(symbol)) {
			return tracked;
		}
		return cached("ls|" + symbol, () -> {
			List<LongShortAccountRatio> r = client.globalLongShortAccountRatio(symbol, "5m", 1);
			if (r.isEmpty()) {
				return null;
			}
			LongShortAccountRatio x = r.get(r.size() - 1);
			return new LongShortRatio(symbol, x.longAccount() * 100, x.shortAccount() * 100);
		});
	}

	/** OI·롱숏 이력은 바이낸스가 최근 30일·최대 500개까지만 제공 */
	@Override
	public HistorySeries liveHistory(String symbol, int days) {
		if (!isVisible(symbol)) {
			return null;
		}
		int hours = Math.max(24, Math.min(days, 30) * 24);
		int futuresLimit = Math.min(500, hours);
		return cached("hist|" + symbol + "|" + days, () -> new HistorySeries(
				client.klines(symbol, "1h", hours).stream().map(k -> new TimeValue(k.openTime(), k.close())).toList(),
				client.openInterestHist(symbol, "1h", futuresLimit).stream()
						.map(o -> new TimeValue(o.timestamp(), o.sumOpenInterestValue())).toList(),
				client.globalLongShortAccountRatio(symbol, "1h", futuresLimit).stream()
						.map(r -> new TimeValue(r.timestamp(), r.longAccount())).toList(),
				client.topLongShortPositionRatio(symbol, "1h", futuresLimit).stream()
						.map(r -> new TimeValue(r.timestamp(), r.longAccount())).toList()));
	}

	// ------------------------------------------------------------------ 계산

	/** 거래대금 필터를 통과한 종목들의 펀딩비(8h 환산) 중앙값 (한두 종목의 극단값에 흔들리지 않도록) */
	private double medianFunding(FastSnapshot f) {
		double[] rates = f.premium().values().stream()
				.filter(p -> {
					Ticker24h t = f.tickers().get(p.symbol());
					return t != null && t.quoteVolume() >= minVolumeUsd;
				})
				.mapToDouble(p -> p.lastFundingRate() * 8.0 / fundingIntervalHours(p.symbol()))
				.sorted()
				.toArray();
		if (rates.length == 0) {
			return 0;
		}
		int m = rates.length / 2;
		return rates.length % 2 == 1 ? rates[m] : (rates[m - 1] + rates[m]) / 2;
	}

	/**
	 * 개인 계정 롱 비율의 평상시 수준.
	 * 실측(2026-11, 거래대금 상위 30종목) 중앙값 약 73% → 롱으로 구조적 편향이 있어 50 이 아닌 70 을 기준으로 삼는다.
	 */
	private static final double LONG_ACCOUNT_BASELINE = 70.0;

	/**
	 * 시장 분위기 점수 (자체 산출, 0~100)
	 *  50 기준 + 펀딩비 중앙값(±25) + 롱 계정 비율(±15) + BTC 24h 변동(±10)
	 */
	private Sentiment sentiment(FastSnapshot f, SlowSnapshot s, double medianFunding) {
		double score = 50;
		// 바이낸스 기본 펀딩 0.01% 를 중립으로 보고, ±0.01%p 당 ±10점
		score += clamp((medianFunding - 0.0001) / 0.0001 * 10, -25, 25);

		if (s != null && !s.longShort().isEmpty()) {
			Map<String, Double> vol = new HashMap<>();
			f.tickers().forEach((k, v) -> vol.put(k, v.quoteVolume()));
			double w = 0;
			double longSum = 0;
			for (LongShortRatio r : s.longShort()) {
				double v = vol.getOrDefault(r.symbol(), 0.0);
				longSum += r.longPercent() * v;
				w += v;
			}
			if (w > 0) {
				score += clamp((longSum / w - LONG_ACCOUNT_BASELINE) * 1.0, -15, 15);
			}
		}

		Ticker24h btc = f.tickers().get("BTCUSDT");
		if (btc != null) {
			score += clamp(btc.priceChangePercent() * 2, -10, 10);
		}
		return Sentiment.of((int) Math.round(clamp(score, 0, 100)));
	}

	private static double clamp(double v, double min, double max) {
		return Math.max(min, Math.min(max, v));
	}
}
