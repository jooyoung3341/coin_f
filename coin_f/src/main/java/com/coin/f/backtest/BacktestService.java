package com.coin.f.backtest;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.coin.f.backtest.BacktestEngine.Simulation;
import com.coin.f.backtest.BacktestModels.Bar;
import com.coin.f.backtest.BacktestModels.EquityPoint;
import com.coin.f.backtest.BacktestModels.GroupStat;
import com.coin.f.backtest.BacktestModels.Params;
import com.coin.f.backtest.BacktestModels.Result;
import com.coin.f.backtest.BacktestModels.Summary;
import com.coin.f.backtest.BacktestModels.SymbolSeries;
import com.coin.f.backtest.BacktestModels.Trade;
import com.coin.f.market.MarketDataProvider;
import com.coin.f.storage.BacktestRunRow;
import com.coin.f.storage.MarketDataRecorder;
import com.coin.f.storage.Rows.CandleRow;
import com.coin.f.storage.Rows.SymbolTimeValue;

import tools.jackson.databind.json.JsonMapper;

/**
 * 백테스트 실행 · 저장(backtest_run / backtest_result) · 조회.
 * 입력은 DB 에 쌓인 1시간 단위 이력(캔들 · OI · 롱숏 · 펀딩비).
 */
@Service
public class BacktestService {

	private static final Logger log = LoggerFactory.getLogger(BacktestService.class);
	private static final long HOUR = BacktestEngine.HOUR;
	private static final long DAY = 24 * HOUR;
	public static final List<Integer> HOLD_OPTIONS = List.of(1, 4, 8, 24);
	public static final List<Integer> DAY_OPTIONS = List.of(7, 14, 21, 30);
	/** 저장·표시용 상한 (집계는 전체 거래로 계산) */
	private static final int MAX_TRADES_STORED = 2000;
	private static final int MAX_EQUITY_POINTS = 800;

	private final MarketDataRecorder recorder;
	private final MarketDataProvider provider;
	private final BacktestEngine engine;
	private final JsonMapper json;
	private final ZoneId zone = ZoneId.systemDefault();

	/** 최근 결과 캐시 (DB 저장 실패 시에도 화면 표시용) */
	private final Map<Long, Result> cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, Result> e) {
			return size() > 20;
		}
	});
	private final AtomicLong localIds = new AtomicLong(-1);

	public BacktestService(MarketDataRecorder recorder, BacktestEngine engine, JsonMapper json,
			MarketDataProvider provider) {
		this.provider = provider;
		this.recorder = recorder;
		this.engine = engine;
		this.json = json;
	}

	/** 동시에 여러 번 눌러도 DB 부하가 겹치지 않도록 직렬 실행 */
	public synchronized Result run(Params p) {
		long started = System.currentTimeMillis();
		long to = Math.floorDiv(started, HOUR) * HOUR;
		long from = to - p.days() * DAY;
		Map<String, SymbolSeries> data = load(from - 25 * HOUR, started);
		long dataFrom = Math.max(from, data.values().stream()
				.mapToLong(s -> s.bars().keySet().stream().mapToLong(Long::longValue).min().orElse(to) + DAY)
				.min().orElse(from));

		BacktestRunRow row = new BacktestRunRow();
		row.setSymbolId(p.symbol() == null ? null : recorder.symbolId(p.symbol()));
		row.setSignalType(p.signalType() == null ? "ALL" : p.signalType().name());
		row.setStartTime(dataFrom);
		row.setEndTime(to);
		row.setHoldMinutes(p.holdHours() * 60);
		row.setTakeProfit(p.takeProfitPct());
		row.setStopLoss(p.stopLossPct());
		row.setParamsJson(json.writeValueAsString(Map.of("mode", p.mode().name(), "feePct", p.feePct(),
				"days", p.days(), "interval", "1h", "entry", "next-bar-open")));
		row.setStatus("RUNNING");
		Long runId = recorder.saveBacktestRun(row);

		try {
			Simulation sim = engine.simulate(data.values(), p, from, to);
			List<Trade> trades = sim.trades();

			List<GroupStat> byHold = new ArrayList<>();
			for (int h : HOLD_OPTIONS) {
				List<Trade> t = h == p.holdHours() ? trades : engine.simulate(data.values(), p.withHold(h), from, to).trades();
				byHold.add(BacktestEngine.stat(String.valueOf(h), h + "시간", t));
			}
			List<GroupStat> byType = new ArrayList<>(
					BacktestEngine.group(trades, t -> t.type().name(), t -> t.type().getLabel()));
			byType.sort(Comparator.comparingInt(GroupStat::count).reversed());
			List<GroupStat> bySymbol = new ArrayList<>(BacktestEngine.group(trades, Trade::symbol, Trade::symbol));
			bySymbol.sort(Comparator.comparingDouble(GroupStat::totalReturn).reversed());

			Summary summary = BacktestEngine.summarize(trades);
			Result result = new Result(null, p, dataFrom, to, data.size(), summary,
					BacktestEngine.weekly(trades, zone), byType, bySymbol, byHold,
					downsample(BacktestEngine.equity(trades)),
					trades.size() > MAX_TRADES_STORED ? trades.subList(trades.size() - MAX_TRADES_STORED, trades.size())
							: trades,
					sim.openSkipped(), System.currentTimeMillis());

			if (runId != null) {
				result = result.withRunId(runId);
				recorder.saveBacktestResult(runId, summary.count(), summary.wins(), summary.winRate(),
						summary.avgReturn(), summary.totalReturn(), summary.maxDrawdown(),
						json.writeValueAsString(result));
			} else {
				result = result.withRunId(localIds.getAndDecrement());
			}
			cache.put(result.runId(), result);
			log.info("백테스트 #{} 완료: {}종목 · 거래 {}건 · 승률 {}% · {}ms", result.runId(), data.size(),
					summary.count(), Math.round(summary.winRate()), System.currentTimeMillis() - started);
			return result;
		} catch (RuntimeException e) {
			if (runId != null) {
				recorder.markBacktestFailed(runId);
			}
			throw e;
		}
	}

	public Result find(long runId) {
		Result cached = cache.get(runId);
		if (cached != null) {
			return cached;
		}
		BacktestRunRow row = recorder.backtestRun(runId);
		if (row == null || row.getPeriodJson() == null) {
			return null;
		}
		try {
			Result r = json.readValue(row.getPeriodJson(), Result.class).withRunId(runId);
			cache.put(runId, r);
			return r;
		} catch (RuntimeException e) {
			log.warn("백테스트 #{} 결과 해석 실패: {}", runId, e.toString());
			return null;
		}
	}

	/** 가장 최근 완료된 실행 */
	public Result latest() {
		return recentRuns(10).stream().filter(r -> "DONE".equals(r.getStatus())).findFirst()
				.map(r -> find(r.getRunId())).orElse(null);
	}

	public List<BacktestRunRow> recentRuns(int limit) {
		return recorder.backtestRuns(limit);
	}

	public boolean outcomeColumnsReady() {
		return recorder.signalOutcomeColumnsReady();
	}

	// ------------------------------------------------------------------ 데이터 적재

	private Map<String, SymbolSeries> load(long since, long now) {
		Map<String, Map<Long, Bar>> bars = new HashMap<>();
		for (CandleRow c : recorder.candlesSince("1h", since)) {
			if (c.ts() + HOUR <= now) { // 진행 중인 봉 제외
				bars.computeIfAbsent(c.symbol(), k -> new HashMap<>())
						.put(c.ts(), new Bar(c.ts(), c.open(), c.high(), c.low(), c.close()));
			}
		}
		Map<String, Map<Long, Double>> oi = byHour(recorder.openInterestSince("1h", since), 1);
		Map<String, Map<Long, Double>> ls = byHour(recorder.longShortSince("1h", since), 100);
		Map<String, TreeMap<Long, Double>> funding = new HashMap<>();
		// 정산 주기 추정에 직전 정산이 필요하므로 이틀 더 앞에서부터
		for (SymbolTimeValue f : recorder.fundingSince(since - 2 * DAY)) {
			funding.computeIfAbsent(f.symbol(), k -> new TreeMap<>()).put(f.ts(), f.value());
		}

		Map<String, SymbolSeries> out = new TreeMap<>();
		bars.forEach((symbol, b) -> {
			// 시가총액 기준 미달 종목은 과거 데이터가 있어도 제외
			if (oi.containsKey(symbol) && funding.containsKey(symbol) && provider.isVisible(symbol)) {
				out.put(symbol, new SymbolSeries(symbol, b, oi.get(symbol), ls.getOrDefault(symbol, Map.of()),
						funding.get(symbol)));
			}
		});
		return out;
	}

	private static Map<String, Map<Long, Double>> byHour(List<SymbolTimeValue> rows, double scale) {
		Map<String, Map<Long, Double>> m = new HashMap<>();
		for (SymbolTimeValue r : rows) {
			long hour = Math.round(r.ts() / (double) HOUR) * HOUR;
			m.computeIfAbsent(r.symbol(), k -> new HashMap<>()).put(hour, r.value() * scale);
		}
		return m;
	}

	/** 차트용 곡선 축소 (MDD 는 원본으로 이미 계산됨) */
	private static List<EquityPoint> downsample(List<EquityPoint> curve) {
		if (curve.size() <= MAX_EQUITY_POINTS) {
			return curve;
		}
		int step = (int) Math.ceil(curve.size() / (double) MAX_EQUITY_POINTS);
		List<EquityPoint> out = new ArrayList<>();
		for (int i = 0; i < curve.size(); i += step) {
			out.add(curve.get(i));
		}
		if (out.get(out.size() - 1) != curve.get(curve.size() - 1)) {
			out.add(curve.get(curve.size() - 1));
		}
		return out;
	}
}
