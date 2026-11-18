package com.coin.f.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.coin.f.market.MarketModels.Kline;
import com.coin.f.market.MarketModels.Liquidation;
import com.coin.f.storage.Rows.CandleRow;
import com.coin.f.storage.Rows.FundingRow;
import com.coin.f.storage.Rows.FundingStat;
import com.coin.f.storage.Rows.SymbolTimeValue;
import com.coin.f.storage.Rows.LiquidationRow;
import com.coin.f.storage.Rows.LongShortRow;
import com.coin.f.storage.Rows.OpenInterestRow;
import com.coin.f.storage.Rows.SignalRow;
import com.coin.f.storage.Rows.SymbolId;
import com.coin.f.storage.Rows.SymbolRow;
import com.coin.f.storage.Rows.TimeValue;

import jakarta.annotation.PreDestroy;

/**
 * 수집 데이터 DB 저장 창구.
 *
 * - 저장 실패(DB 다운, 테이블 없음 등)는 로그만 남기고 삼킨다 → 수집/화면은 계속 동작
 * - 같은 오류 로그가 10초마다 쏟아지지 않도록 1분에 한 번만 출력
 * - 청산은 이벤트마다 INSERT 하지 않고 큐에 모아 5초마다 일괄 저장
 */
@Component
public class MarketDataRecorder {

	private static final Logger log = LoggerFactory.getLogger(MarketDataRecorder.class);
	private static final int BATCH = 500;

	private final MarketDataMapper mapper;
	private final boolean enabled;
	private final String exchange = "BINANCE";

	private final Map<String, Integer> symbolIds = new ConcurrentHashMap<>();
	private final ConcurrentLinkedQueue<Liquidation> liqQueue = new ConcurrentLinkedQueue<>();
	private volatile long lastErrorLogAt;
	private volatile long lastIdReloadAt;

	public MarketDataRecorder(MarketDataMapper mapper, @Value("${app.storage.enabled:true}") boolean enabled) {
		this.mapper = mapper;
		this.enabled = enabled;
		log.info("DB 저장 {}", enabled ? "사용" : "미사용 (app.storage.enabled=false)");
	}

	public boolean isEnabled() {
		return enabled;
	}

	// ------------------------------------------------------------------ 종목

	public void syncSymbols(List<SymbolRow> rows) {
		if (!enabled || rows.isEmpty()) {
			return;
		}
		run("종목 동기화", () -> {
			for (List<SymbolRow> chunk : chunks(rows)) {
				mapper.upsertSymbols(chunk);
			}
			reloadSymbolIds();
		});
	}

	private void reloadSymbolIds() {
		for (SymbolId s : mapper.selectSymbolIds(exchange)) {
			symbolIds.put(s.symbol(), s.symbolId());
		}
	}

	/** DB 의 symbol_id. 아직 동기화 전이거나 DB 미사용이면 null */
	public Integer symbolId(String symbol) {
		if (!enabled) {
			return null;
		}
		Integer id = symbolIds.get(symbol);
		long now = System.currentTimeMillis();
		// DB 장애 시 호출마다 재조회하면 수집이 멈추므로 1분에 한 번만 시도
		if (id == null && symbolIds.isEmpty() && now - lastIdReloadAt > 60_000) {
			lastIdReloadAt = now;
			run("종목 ID 조회", this::reloadSymbolIds);
			id = symbolIds.get(symbol);
		}
		return id;
	}

	// ------------------------------------------------------------------ 저장

	public void saveCandles(String symbol, String interval, List<Kline> klines) {
		Integer id = symbolId(symbol);
		if (id == null || klines.isEmpty()) {
			return;
		}
		run("캔들 저장 " + symbol + " " + interval, () -> {
			for (List<Kline> chunk : chunks(klines)) {
				mapper.upsertCandles(id, interval, chunk);
			}
		});
	}

	/** rows 의 symbolId 는 호출측에서 symbolId()로 채운다 */
	public void saveFundingRates(List<FundingRow> rows) {
		if (!enabled || rows.isEmpty()) {
			return;
		}
		run("펀딩비 저장", () -> chunks(rows).forEach(mapper::upsertFundingRates));
	}

	public void saveOpenInterest(List<OpenInterestRow> rows) {
		if (!enabled || rows.isEmpty()) {
			return;
		}
		run("OI 저장", () -> chunks(rows).forEach(mapper::upsertOpenInterest));
	}

	public void saveLongShort(List<LongShortRow> rows) {
		if (!enabled || rows.isEmpty()) {
			return;
		}
		run("롱숏 저장", () -> chunks(rows).forEach(mapper::upsertLongShort));
	}

	public void queueLiquidation(Liquidation liq) {
		if (enabled) {
			liqQueue.add(liq);
		}
	}

	@Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
	public void flushLiquidations() {
		if (!enabled || liqQueue.isEmpty()) {
			return;
		}
		List<LiquidationRow> rows = new ArrayList<>();
		List<Liquidation> pending = new ArrayList<>();
		Liquidation l;
		while ((l = liqQueue.poll()) != null) {
			Integer id = symbolId(l.symbol());
			if (id == null) {
				continue; // 종목 테이블에 없는 심볼 (동기화 전) → 버림
			}
			pending.add(l);
			rows.add(new LiquidationRow(id, l.side(), l.price(), l.quantity(), l.valueUsd(), l.time()));
		}
		boolean ok = run("청산 저장", () -> chunks(rows).forEach(mapper::insertLiquidations));
		if (!ok && liqQueue.size() < 20_000) {
			liqQueue.addAll(pending); // DB 복구 후 재시도
		}
	}

	public List<Liquidation> loadLiquidationsSince(long since) {
		if (!enabled) {
			return List.of();
		}
		List<List<Liquidation>> holder = new ArrayList<>();
		run("청산 이력 조회", () -> holder.add(mapper.selectLiquidationsSince(since)));
		return holder.isEmpty() ? List.of() : holder.get(0);
	}

	// ------------------------------------------------------------------ 추이 조회

	public List<TimeValue> closeHistory(String symbol, String interval, long since) {
		return query("가격 이력 조회", symbol, id -> mapper.selectCloseHistory(id, interval, since));
	}

	public List<TimeValue> openInterestHistory(String symbol, String period, long since) {
		return query("OI 이력 조회", symbol, id -> mapper.selectOpenInterestHistory(id, period, since));
	}

	public List<TimeValue> fundingHistory(String symbol, long since) {
		return query("펀딩비 이력 조회", symbol, id -> mapper.selectFundingHistory(id, since));
	}

	public List<TimeValue> longShortHistory(String symbol, String period, long since) {
		return query("롱숏 이력 조회", symbol,
				id -> mapper.selectLongShortHistory(id, "GLOBAL_ACCOUNT", period, since));
	}

	public List<SymbolTimeValue> openInterestSince(String period, long since) {
		return enabled ? select("OI 전체 이력 조회", () -> mapper.selectOpenInterestSince(period, since)) : List.of();
	}

	public List<FundingStat> fundingStats(long since) {
		return enabled ? select("펀딩비 통계 조회", () -> mapper.selectFundingStats(since)) : List.of();
	}

	private <T> List<T> select(String what, java.util.function.Supplier<List<T>> q) {
		List<List<T>> holder = new ArrayList<>();
		run(what, () -> holder.add(q.get()));
		return holder.isEmpty() ? List.of() : holder.get(0);
	}

	private List<TimeValue> query(String what, String symbol, java.util.function.IntFunction<List<TimeValue>> q) {
		Integer id = symbolId(symbol);
		if (id == null) {
			return List.of();
		}
		List<List<TimeValue>> holder = new ArrayList<>();
		run(what, () -> holder.add(q.apply(id)));
		return holder.isEmpty() ? List.of() : holder.get(0);
	}

	// ------------------------------------------------------------------ 시그널

	/** 같은 종목·유형 시그널이 since 이후 이미 기록됐는지 */
	public boolean hasSignalSince(int symbolId, String type, long since) {
		int[] count = { 1 }; // 조회 실패 시 "있음"으로 간주 → 중복 저장 방지
		run("시그널 조회", () -> count[0] = mapper.countSignalsSince(symbolId, type, since));
		return count[0] > 0;
	}

	/** @return 저장 성공 여부 */
	public boolean saveAiReport(AiReportRow row) {
		return enabled && run("AI 리포트 저장", () -> mapper.insertAiReport(row));
	}

	public List<AiReportRow> aiReports(java.time.LocalDateTime since, int limit) {
		return enabled ? select("AI 리포트 조회", () -> mapper.selectAiReports(since, limit)) : List.of();
	}

	public List<SignalHistoryRow> signalHistory(long since, int limit) {
		return enabled ? select("시그널 이력 조회", () -> mapper.selectSignalHistory(since, limit)) : List.of();
	}

	/** 컬럼(price_1h 등)이 없으면 실패 → 빈 목록 (로그 1분 1회) */
	public List<SignalHistoryRow> signalsPendingOutcome(long now, long since, int limit) {
		return enabled ? select("시그널 결과 대상 조회", () -> mapper.selectSignalsPendingOutcome(now, since, limit))
				: List.of();
	}

	public void saveSignalOutcome(long signalId, Double p1h, Double p4h, Double p24h) {
		if (enabled) {
			run("시그널 결과 저장", () -> mapper.updateSignalOutcome(signalId, p1h, p4h, p24h));
		}
	}

	public void saveSignal(SignalRow row) {
		if (enabled) {
			run("시그널 저장", () -> mapper.insertSignal(row));
		}
	}

	/** market_signal 에 price_1h/4h/24h 컬럼이 모두 있는지 (조회 실패 시 false) */
	public boolean signalOutcomeColumnsReady() {
		int[] n = { 0 };
		return enabled && run("시그널 결과 컬럼 확인", () -> n[0] = mapper.countSignalOutcomeColumns()) && n[0] == 3;
	}

	// ------------------------------------------------------------------ 백테스트

	public List<CandleRow> candlesSince(String interval, long since) {
		return enabled ? select("캔들 일괄 조회", () -> mapper.selectCandlesSince(interval, since)) : List.of();
	}

	public List<SymbolTimeValue> longShortSince(String period, long since) {
		return enabled ? select("롱숏 일괄 조회", () -> mapper.selectLongShortSince("GLOBAL_ACCOUNT", period, since))
				: List.of();
	}

	public List<SymbolTimeValue> fundingSince(long since) {
		return enabled ? select("펀딩비 일괄 조회", () -> mapper.selectFundingSince(since)) : List.of();
	}

	/** @return 생성된 run_id, 실패 시 null */
	public Long saveBacktestRun(BacktestRunRow row) {
		if (!enabled) {
			return null;
		}
		return run("백테스트 실행 저장", () -> mapper.insertBacktestRun(row)) ? row.getRunId() : null;
	}

	public void saveBacktestResult(long runId, int count, int wins, double winRate, double avgReturn,
			double totalReturn, double mdd, String periodJson) {
		if (!enabled) {
			return;
		}
		boolean ok = run("백테스트 결과 저장", () -> mapper.insertBacktestResult(runId, count, wins, winRate, avgReturn,
				totalReturn, mdd, periodJson));
		run("백테스트 상태 갱신", () -> mapper.updateBacktestRunStatus(runId, ok ? "DONE" : "FAILED"));
	}

	public void markBacktestFailed(long runId) {
		if (enabled) {
			run("백테스트 상태 갱신", () -> mapper.updateBacktestRunStatus(runId, "FAILED"));
		}
	}

	public List<BacktestRunRow> backtestRuns(int limit) {
		return enabled ? select("백테스트 목록 조회", () -> mapper.selectBacktestRuns(limit)) : List.of();
	}

	public BacktestRunRow backtestRun(long runId) {
		if (!enabled) {
			return null;
		}
		List<BacktestRunRow> one = select("백테스트 조회", () -> {
			BacktestRunRow r = mapper.selectBacktestRun(runId);
			return r == null ? List.of() : List.of(r);
		});
		return one.isEmpty() ? null : one.get(0);
	}

	@PreDestroy
	public void shutdown() {
		flushLiquidations();
	}

	// ------------------------------------------------------------------ 공통

	private boolean run(String what, Runnable action) {
		try {
			action.run();
			return true;
		} catch (RuntimeException e) {
			long now = System.currentTimeMillis();
			if (now - lastErrorLogAt > 60_000) {
				lastErrorLogAt = now;
				Throwable root = e;
				while (root.getCause() != null) {
					root = root.getCause();
				}
				log.warn("DB {} 실패: {} (1분간 같은 종류 로그 생략)", what, root.toString());
			}
			return false;
		}
	}

	private static <T> List<List<T>> chunks(List<T> list) {
		List<List<T>> out = new ArrayList<>();
		for (int i = 0; i < list.size(); i += BATCH) {
			out.add(list.subList(i, Math.min(i + BATCH, list.size())));
		}
		return out;
	}
}
