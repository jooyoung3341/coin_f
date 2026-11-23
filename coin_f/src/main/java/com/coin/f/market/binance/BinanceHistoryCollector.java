package com.coin.f.market.binance;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.coin.f.market.MarketModels.Kline;
import com.coin.f.market.binance.BinanceDtos.FundingRateHist;
import com.coin.f.market.binance.BinanceDtos.LongShortAccountRatio;
import com.coin.f.market.binance.BinanceDtos.OpenInterestHist;
import com.coin.f.storage.MarketDataRecorder;
import com.coin.f.storage.Rows.FundingRow;
import com.coin.f.storage.Rows.LongShortRow;
import com.coin.f.storage.Rows.OpenInterestRow;

/**
 * DB 저장 전용 이력 수집 (화면 스냅샷과 별개).
 *  - 캔들: 기본 종목 × 1m/5m/15m/1h, 1분마다 최근 3개 UPSERT (첫 실행 시 500개 백필)
 *  - 펀딩비: 추적 종목, 30분마다 최근 3건 UPSERT (첫 실행 시 100건 ≈ 33일 백필)
 * 백필은 "이번 실행에서 처음 보는 종목/구간"에 대해 수행 → 재시작 시에도 빈 구간을 메운다.
 */
@Component
@ConditionalOnProperty(name = "app.market.source", havingValue = "binance", matchIfMissing = true)
public class BinanceHistoryCollector {

	private static final Logger log = LoggerFactory.getLogger(BinanceHistoryCollector.class);
	private static final List<String> CANDLE_INTERVALS = List.of("1m", "5m", "15m", "1h");
	private static final int CANDLE_BACKFILL = 500;
	private static final int FUNDING_BACKFILL = 100;

	private final BinanceClient client;
	private final BinanceMarketDataProvider provider;
	private final MarketDataRecorder recorder;
	private final List<String> coreSymbols;

	private final Set<String> candleBackfilled = new HashSet<>();
	private final Set<String> fundingBackfilled = new HashSet<>();
	private final Set<String> hourlyBackfilled = new HashSet<>();
	private static final int HOURLY_BACKFILL = 500;
	private static final long HOURLY_INTERVAL_MILLIS = 900_000;
	private volatile long lastHourlyRun;

	public BinanceHistoryCollector(BinanceClient client, BinanceMarketDataProvider provider,
			MarketDataRecorder recorder, @Value("${app.market.symbols}") List<String> coreSymbols) {
		this.client = client;
		this.provider = provider;
		this.recorder = recorder;
		this.coreSymbols = coreSymbols;
	}

	@Scheduled(initialDelay = 10_000, fixedDelay = 60_000)
	public void collectCandles() {
		if (!recorder.isEnabled()) {
			return;
		}
		int saved = 0;
		for (String symbol : coreSymbols) {
			for (String interval : CANDLE_INTERVALS) {
				String key = symbol + "|" + interval;
				boolean first = !candleBackfilled.contains(key);
				try {
					List<Kline> rows = client.klines(symbol, interval, first ? CANDLE_BACKFILL : 3);
					recorder.saveCandles(symbol, interval, rows);
					saved += rows.size();
					if (first && recorder.symbolId(symbol) != null) {
						candleBackfilled.add(key);
					}
				} catch (RuntimeException e) {
					log.warn("{} {} 캔들 수집 실패: {}", symbol, interval, e.toString());
				}
			}
		}
		log.debug("캔들 {}건 저장", saved);
	}

	/**
	 * 1시간 간격 OI·롱숏 이력 (코인 상세 추이 차트용).
	 * 바이낸스는 최근 30일만 제공 → 처음 보는 종목은 500개(약 20일) 백필, 이후 15분마다 최근 3개.
	 * 1분마다 확인하되, 추적 종목 확정(첫 느린 수집 완료) 전에는 건너뛰고
	 * 15분이 지났거나 아직 백필 안 된 종목(상위 N 신규 진입 포함)이 있을 때만 실행.
	 */
	@Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
	public void collectHourlyOiAndLongShort() {
		if (!recorder.isEnabled() || !provider.isUniverseReady()) {
			return;
		}
		boolean due = System.currentTimeMillis() - lastHourlyRun >= HOURLY_INTERVAL_MILLIS;
		// 15분 주기가 아니면 백필 안 된 종목만 (실패 종목 재시도가 전체 재수집으로 번지지 않도록)
		List<String> targets = due ? provider.trackedSymbols()
				: provider.trackedSymbols().stream().filter(s -> !hourlyBackfilled.contains(s)).toList();
		if (targets.isEmpty()) {
			return;
		}
		if (due) {
			lastHourlyRun = System.currentTimeMillis();
		}
		List<OpenInterestRow> oiRows = new ArrayList<>();
		List<LongShortRow> lsRows = new ArrayList<>();
		List<String> backfilledNow = new ArrayList<>();
		Map<String, List<Kline>> candles = new LinkedHashMap<>();
		for (String symbol : targets) {
			Integer sid = recorder.symbolId(symbol);
			if (sid == null) {
				continue;
			}
			int limit = hourlyBackfilled.contains(symbol) ? 3 : HOURLY_BACKFILL;
			try {
				for (OpenInterestHist h : client.openInterestHist(symbol, "1h", limit)) {
					oiRows.add(new OpenInterestRow(sid, "1h", h.timestamp(), h.sumOpenInterest(),
							h.sumOpenInterestValue()));
				}
				for (LongShortAccountRatio r : client.globalLongShortAccountRatio(symbol, "1h", limit)) {
					lsRows.add(new LongShortRow(sid, "GLOBAL_ACCOUNT", "1h", r.timestamp(), r.longAccount(),
							r.shortAccount(), r.longShortRatio()));
				}
				for (LongShortAccountRatio r : client.topLongShortPositionRatio(symbol, "1h", limit)) {
					lsRows.add(new LongShortRow(sid, "TOP_POSITION", "1h", r.timestamp(), r.longAccount(),
							r.shortAccount(), r.longShortRatio()));
				}
				// 백테스트 가격 기준: OI/롱숏과 같은 구간의 1시간봉 (기본 종목은 collectCandles 에서도 저장 — UPSERT 라 중복 무해)
				candles.put(symbol, client.klines(symbol, "1h", limit));
				if (limit == HOURLY_BACKFILL) {
					backfilledNow.add(symbol);
				}
			} catch (RuntimeException e) {
				log.warn("{} OI/롱숏 1h 이력 수집 실패: {}", symbol, e.toString());
			}
		}
		recorder.saveOpenInterest(oiRows);
		recorder.saveLongShort(lsRows);
		candles.forEach((symbol, rows) -> recorder.saveCandles(symbol, "1h", rows));
		hourlyBackfilled.addAll(backfilledNow);
		log.info("OI/롱숏/1h봉 이력 저장: OI {}건, 롱숏 {}건, 캔들 {}건 (신규 백필 {}종목)", oiRows.size(), lsRows.size(),
				candles.values().stream().mapToInt(List::size).sum(), backfilledNow.size());
	}

	@Scheduled(initialDelay = 20_000, fixedDelay = 1_800_000)
	public void collectFundingRates() {
		if (!recorder.isEnabled()) {
			return;
		}
		List<FundingRow> rows = new ArrayList<>();
		List<String> backfilledNow = new ArrayList<>();
		for (String symbol : provider.trackedSymbols()) {
			Integer sid = recorder.symbolId(symbol);
			if (sid == null) {
				continue;
			}
			boolean first = !fundingBackfilled.contains(symbol);
			try {
				for (FundingRateHist h : client.fundingRateHistory(symbol, first ? FUNDING_BACKFILL : 3)) {
					rows.add(new FundingRow(sid, h.fundingTime(), h.fundingRate(), h.markPriceOrNull()));
				}
				if (first) {
					backfilledNow.add(symbol);
				}
			} catch (RuntimeException e) {
				log.warn("{} 펀딩비 이력 수집 실패: {}", symbol, e.toString());
			}
		}
		recorder.saveFundingRates(rows);
		fundingBackfilled.addAll(backfilledNow);
		log.info("펀딩비 이력 {}건 저장 (신규 백필 {}종목)", rows.size(), backfilledNow.size());
	}
}
