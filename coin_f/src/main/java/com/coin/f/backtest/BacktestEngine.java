package com.coin.f.backtest;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import com.coin.f.backtest.BacktestModels.Bar;
import com.coin.f.backtest.BacktestModels.EquityPoint;
import com.coin.f.backtest.BacktestModels.GroupStat;
import com.coin.f.backtest.BacktestModels.Mode;
import com.coin.f.backtest.BacktestModels.Params;
import com.coin.f.backtest.BacktestModels.Summary;
import com.coin.f.backtest.BacktestModels.SymbolSeries;
import com.coin.f.backtest.BacktestModels.Trade;
import com.coin.f.market.MarketModels.FundingRate;
import com.coin.f.market.MarketModels.LongShortRatio;
import com.coin.f.market.MarketModels.OpenInterestChange;
import com.coin.f.signal.MarketSignal;
import com.coin.f.signal.MarketSignal.SignalType;
import com.coin.f.signal.SignalDetector;

/**
 * 시그널 규칙 재현 백테스트.
 *
 * 과거 1시간 단위 각 시점(t)에 그 시점까지의 펀딩비·OI·롱숏으로 실시간과 같은 {@link SignalDetector} 를 돌리고,
 * 시그널이 나면 다음 1시간봉 시가(t+1h)에 진입 → holdHours 후(또는 익절/손절) 청산한다.
 *
 *  - 미래 데이터 미사용: t 시점 지표 + t+1h 시가 진입. 펀딩비는 t 이전 마지막 정산값
 *  - 대량 청산 시그널은 과거 청산 이력이 수집 시작 이후뿐이라 재현 대상에서 제외
 *  - 같은 종목·유형은 4시간(실시간 중복 기준) 그리고 이전 거래 청산 전에는 다시 진입하지 않음
 *  - OI 급증(중립)은 24시간 가격 방향을 쏠림으로 간주 (가격↑+OI↑ = 신규 롱 유입)
 *  - 익절·손절이 같은 봉에서 모두 닿으면 손절로 처리 (보수적)
 */
@Component
public class BacktestEngine {

	static final long HOUR = 3_600_000L;
	private static final long DEDUPE = 4 * HOUR;
	private static final Trade OPEN = new Trade("", null, "", "", "", 0, 0, 0, 0, 0, "OPEN", 0);

	private final SignalDetector detector;

	public BacktestEngine(SignalDetector detector) {
		this.detector = detector;
	}

	/** 재현 가능한 시그널 유형 */
	public static List<SignalType> replayableTypes() {
		return List.of(SignalType.COMPOSITE, SignalType.FUNDING_OVERHEAT, SignalType.OI_SURGE,
				SignalType.LONG_OVERHEAT, SignalType.SHORT_OVERHEAT);
	}

	/** 시뮬레이션 결과 (거래 + 데이터 끝을 넘어 미청산으로 제외된 수) */
	public record Simulation(List<Trade> trades, int openSkipped) {
	}

	/**
	 * @param from 시그널 판정 시작 시각 (포함), to 판정 끝 시각 (제외)
	 */
	public Simulation simulate(Collection<SymbolSeries> data, Params p, long from, long to) {
		List<Trade> trades = new ArrayList<>();
		int open = 0;
		for (SymbolSeries s : data) {
			if (p.symbol() != null && !p.symbol().equals(s.symbol())) {
				continue;
			}
			long lastBar = s.bars().keySet().stream().mapToLong(Long::longValue).max().orElse(0);
			Map<SignalType, Long> nextAllowed = new HashMap<>();
			List<Long> times = s.bars().keySet().stream().filter(t -> t >= from && t < to).sorted().toList();

			for (long t : times) {
				FundingRate f = fundingAt(s, t);
				if (f == null) {
					continue;
				}
				for (MarketSignal sig : detector.detect(List.of(f), oiAt(s, t), lsAt(s, t), Map.of())) {
					if (sig.type() == SignalType.MASS_LIQUIDATION
							|| (p.signalType() != null && sig.type() != p.signalType())
							|| t < nextAllowed.getOrDefault(sig.type(), Long.MIN_VALUE)) {
						continue;
					}
					String crowded = crowdedSide(s, sig, t);
					if (crowded == null) {
						continue;
					}
					String position = p.mode() == Mode.CONTRARIAN ? opposite(crowded) : crowded;
					Trade tr = execute(s, sig, crowded, position, t, lastBar, p);
					if (tr == OPEN) {
						open++;
						nextAllowed.put(sig.type(), t + DEDUPE); // 같은 시그널을 매시간 중복 집계하지 않도록
						continue;
					}
					if (tr == null) {
						continue; // 중간 캔들 누락
					}
					trades.add(tr);
					// 다음 진입(t'+1h)이 이번 청산 이후가 되도록
					nextAllowed.put(sig.type(), Math.max(t + DEDUPE, tr.exitTime() - HOUR));
				}
			}
		}
		trades.sort(Comparator.comparingLong(Trade::entryTime).thenComparing(Trade::symbol));
		return new Simulation(trades, open);
	}

	// ------------------------------------------------------------------ 시점별 지표

	/** t 이전 마지막 정산 펀딩비. 정산 주기는 직전 두 정산 간격으로 추정 */
	static FundingRate fundingAt(SymbolSeries s, long t) {
		Map.Entry<Long, Double> last = s.funding().floorEntry(t);
		if (last == null) {
			return null;
		}
		Map.Entry<Long, Double> prev = s.funding().lowerEntry(last.getKey());
		int interval = 8;
		if (prev != null) {
			interval = (int) Math.round((last.getKey() - prev.getKey()) / (double) HOUR);
			interval = Math.max(1, Math.min(8, interval));
		}
		return new FundingRate(s.symbol(), last.getValue(), 0, interval, 0);
	}

	private static List<OpenInterestChange> oiAt(SymbolSeries s, long t) {
		Double now = s.oiUsd().get(t);
		Double before = s.oiUsd().get(t - 24 * HOUR);
		if (now == null || before == null || before <= 0) {
			return List.of();
		}
		return List.of(new OpenInterestChange(s.symbol(), now, (now / before - 1) * 100));
	}

	private static List<LongShortRatio> lsAt(SymbolSeries s, long t) {
		Double l = s.longPct().get(t);
		return l == null ? List.of() : List.of(new LongShortRatio(s.symbol(), l, 100 - l));
	}

	/** 쏠림 방향. 중립(OI 급증)은 직전 24시간 가격 방향으로 판단 */
	private static String crowdedSide(SymbolSeries s, MarketSignal sig, long t) {
		if (!"NEUTRAL".equals(sig.crowdedSide())) {
			return sig.crowdedSide();
		}
		Bar now = s.bars().get(t);
		Bar before = s.bars().get(t - 24 * HOUR);
		if (now == null || before == null || now.close() == before.close()) {
			return null;
		}
		return now.close() > before.close() ? "LONG" : "SHORT";
	}

	private static String opposite(String side) {
		return "LONG".equals(side) ? "SHORT" : "LONG";
	}

	// ------------------------------------------------------------------ 체결

	/** @return 거래, 데이터 끝을 넘으면 OPEN, 중간 캔들 누락이면 null */
	private static Trade execute(SymbolSeries s, MarketSignal sig, String crowded, String position, long t,
			long lastBar, Params p) {
		long entryTime = t + HOUR;
		long lastHeld = entryTime + (p.holdHours() - 1) * HOUR;
		if (lastHeld > lastBar) {
			return OPEN;
		}
		Bar entryBar = s.bars().get(entryTime);
		if (entryBar == null) {
			return null;
		}
		double entry = entryBar.open();
		boolean isLong = "LONG".equals(position);
		Double tp = p.takeProfitPct() == null ? null : entry * (1 + (isLong ? 1 : -1) * p.takeProfitPct() / 100);
		Double sl = p.stopLossPct() == null ? null : entry * (1 - (isLong ? 1 : -1) * p.stopLossPct() / 100);

		double exit = 0;
		long exitTime = entryTime + p.holdHours() * HOUR;
		String reason = "TIME";
		for (int k = 0; k < p.holdHours(); k++) {
			long bt = entryTime + k * HOUR;
			Bar b = s.bars().get(bt);
			if (b == null) {
				return null;
			}
			// 손절 먼저 확인 (같은 봉에서 둘 다 닿으면 손절)
			if (sl != null && (isLong ? b.low() <= sl : b.high() >= sl)) {
				exit = isLong ? Math.min(b.open(), sl) : Math.max(b.open(), sl); // 갭으로 시가가 이미 넘었으면 시가 체결
				exitTime = bt + HOUR;
				reason = "SL";
				break;
			}
			if (tp != null && (isLong ? b.high() >= tp : b.low() <= tp)) {
				exit = isLong ? Math.max(b.open(), tp) : Math.min(b.open(), tp);
				exitTime = bt + HOUR;
				reason = "TP";
				break;
			}
			exit = b.close();
		}
		double ret = (isLong ? 1 : -1) * (exit / entry - 1) * 100 - p.feePct();
		return new Trade(s.symbol(), sig.type(), sig.level().name(), crowded, position, t, entryTime, entry,
				exitTime, exit, reason, ret);
	}

	// ------------------------------------------------------------------ 집계

	public static Summary summarize(List<Trade> trades) {
		if (trades.isEmpty()) {
			return Summary.empty();
		}
		int wins = 0;
		double sum = 0;
		double winSum = 0;
		double lossSum = 0;
		double best = Double.NEGATIVE_INFINITY;
		double worst = Double.POSITIVE_INFINITY;
		for (Trade t : trades) {
			double r = t.returnPct();
			sum += r;
			if (t.win()) {
				wins++;
				winSum += r;
			} else {
				lossSum += r;
			}
			best = Math.max(best, r);
			worst = Math.min(worst, r);
		}
		int n = trades.size();
		int losses = n - wins;
		return new Summary(n, wins, wins * 100.0 / n, sum / n, sum, maxDrawdown(equity(trades)),
				lossSum == 0 ? null : winSum / -lossSum,
				wins == 0 ? 0 : winSum / wins, losses == 0 ? 0 : lossSum / losses, best, worst);
	}

	/** 청산 시각 순 누적 수익률 곡선 */
	public static List<EquityPoint> equity(List<Trade> trades) {
		List<Trade> byExit = new ArrayList<>(trades);
		byExit.sort(Comparator.comparingLong(Trade::exitTime));
		List<EquityPoint> out = new ArrayList<>(byExit.size());
		double cum = 0;
		for (Trade t : byExit) {
			cum += t.returnPct();
			out.add(new EquityPoint(t.exitTime(), cum));
		}
		return out;
	}

	/** 고점(시작 0 포함) 대비 최대 하락 폭 */
	public static double maxDrawdown(List<EquityPoint> curve) {
		double peak = 0;
		double mdd = 0;
		for (EquityPoint e : curve) {
			peak = Math.max(peak, e.value());
			mdd = Math.max(mdd, peak - e.value());
		}
		return mdd;
	}

	public static List<GroupStat> group(List<Trade> trades, Function<Trade, String> key,
			Function<Trade, String> label) {
		Map<String, List<Trade>> by = new LinkedHashMap<>();
		Map<String, String> labels = new HashMap<>();
		for (Trade t : trades) {
			String k = key.apply(t);
			by.computeIfAbsent(k, x -> new ArrayList<>()).add(t);
			labels.putIfAbsent(k, label.apply(t));
		}
		List<GroupStat> out = new ArrayList<>();
		by.forEach((k, list) -> out.add(stat(k, labels.get(k), list)));
		return out;
	}

	public static GroupStat stat(String key, String label, List<Trade> list) {
		int wins = (int) list.stream().filter(Trade::win).count();
		double sum = list.stream().mapToDouble(Trade::returnPct).sum();
		int n = list.size();
		return new GroupStat(key, label, n, wins, n == 0 ? 0 : wins * 100.0 / n, n == 0 ? 0 : sum / n, sum);
	}

	/** 진입 시각이 속한 주(월요일 시작) */
	public static List<GroupStat> weekly(List<Trade> trades, ZoneId zone) {
		List<GroupStat> out = new ArrayList<>(group(trades, t -> String.valueOf(weekStart(t.entryTime(), zone)),
				t -> {
					LocalDate d = Instant.ofEpochMilli(weekStart(t.entryTime(), zone)).atZone(zone).toLocalDate();
					return d.getMonthValue() + "/" + d.getDayOfMonth() + " 주";
				}));
		out.sort(Comparator.comparingLong(g -> Long.parseLong(g.key())));
		return out;
	}

	private static long weekStart(long epochMillis, ZoneId zone) {
		LocalDate d = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
				.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
		return d.atStartOfDay(zone).toInstant().toEpochMilli();
	}
}
