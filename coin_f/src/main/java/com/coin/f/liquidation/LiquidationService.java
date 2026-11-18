package com.coin.f.liquidation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.coin.f.market.LiquidationStore;
import com.coin.f.market.MarketDataProvider;
import com.coin.f.market.MarketModels.Liquidation;
import com.coin.f.market.MarketModels.LiquidationStats;
import com.coin.f.market.MarketModels.Ticker;

/**
 * 청산 분석 (메뉴 05). 최근 24시간 청산 이벤트(메모리, 시작 시 DB 복원)를 집계.
 * 바이낸스 청산 스트림은 종목당 1초 1건만 보내므로 금액은 실제보다 작은 "표본" 값.
 */
@Service
public class LiquidationService {

	private static final long HOUR = 3_600_000L;
	private static final long DAY = 24 * HOUR;
	private static final int BUCKETS = 30;

	private final LiquidationStore store;
	private final MarketDataProvider provider;

	public LiquidationService(LiquidationStore store, MarketDataProvider provider) {
		this.store = store;
		this.provider = provider;
	}

	public Overview overview() {
		long now = System.currentTimeMillis();
		List<Liquidation> events = store.since(now - DAY);

		// 시간대별 (최근 24개 1시간 구간, 마지막 구간은 진행 중)
		long firstHour = (now / HOUR) * HOUR - 23 * HOUR;
		double[] longs = new double[24];
		double[] shorts = new double[24];
		Map<String, SymbolAgg> bySymbol = new HashMap<>();
		Liquidation largest = null;

		for (Liquidation l : events) {
			int idx = (int) ((l.time() - firstHour) / HOUR);
			if (idx >= 0 && idx < 24) {
				if (l.isLong()) {
					longs[idx] += l.valueUsd();
				} else {
					shorts[idx] += l.valueUsd();
				}
			}
			bySymbol.computeIfAbsent(l.symbol(), SymbolAgg::new).add(l);
			if (largest == null || l.valueUsd() > largest.valueUsd()) {
				largest = l;
			}
		}

		List<HourBucket> hourly = new ArrayList<>(24);
		for (int i = 0; i < 24; i++) {
			hourly.add(new HourBucket(firstHour + i * HOUR, longs[i], shorts[i]));
		}
		List<SymbolRank> ranking = bySymbol.values().stream()
				.map(a -> new SymbolRank(a.symbol, a.longUsd, a.shortUsd, a.count))
				.sorted(Comparator.comparingDouble(SymbolRank::totalUsd).reversed())
				.limit(20).toList();

		return new Overview(store.stats(HOUR), store.stats(DAY), largest, hourly, ranking,
				store.collectingSince());
	}

	/**
	 * 가격대별 청산 분포 — 최근 24시간 실제 발생한 청산을 가격 구간별로 합산.
	 * (거래소가 공개하지 않는 '예상 청산가 히트맵'이 아니라, 실제 체결된 청산의 위치)
	 */
	public Distribution distribution(String symbol) {
		List<Liquidation> events = store.since(System.currentTimeMillis() - DAY).stream()
				.filter(l -> l.symbol().equals(symbol)).toList();
		Double current = provider.getTickers(List.of(symbol)).stream().findFirst()
				.map(Ticker::price).orElse(null);
		if (events.isEmpty()) {
			return new Distribution(symbol, current, 0, List.of(), 0);
		}
		double min = events.stream().mapToDouble(Liquidation::price).min().orElse(0);
		double max = events.stream().mapToDouble(Liquidation::price).max().orElse(0);
		double step = niceStep((max - min) / BUCKETS, min);
		double start = Math.floor(min / step) * step;
		int n = (int) Math.floor((max - start) / step) + 1;

		double[] longs = new double[n];
		double[] shorts = new double[n];
		for (Liquidation l : events) {
			int i = Math.min(n - 1, (int) ((l.price() - start) / step));
			if (l.isLong()) {
				longs[i] += l.valueUsd();
			} else {
				shorts[i] += l.valueUsd();
			}
		}
		List<PriceBucket> buckets = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			buckets.add(new PriceBucket(start + i * step, start + (i + 1) * step, longs[i], shorts[i]));
		}
		return new Distribution(symbol, current, step, buckets, events.size());
	}

	/** 1·2·2.5·5 × 10^k 단위로 반올림한 구간 폭 (가격의 0.05% 미만으로는 쪼개지 않음) */
	static double niceStep(double raw, double priceLevel) {
		double minStep = Math.max(priceLevel * 0.0005, 1e-8);
		double r = Math.max(raw, minStep);
		double mag = Math.pow(10, Math.floor(Math.log10(r)));
		for (double m : new double[] { 1, 2, 2.5, 5, 10 }) {
			if (r <= m * mag) {
				return m * mag;
			}
		}
		return 10 * mag;
	}

	private static final class SymbolAgg {
		final String symbol;
		double longUsd;
		double shortUsd;
		int count;

		SymbolAgg(String symbol) {
			this.symbol = symbol;
		}

		void add(Liquidation l) {
			if (l.isLong()) {
				longUsd += l.valueUsd();
			} else {
				shortUsd += l.valueUsd();
			}
			count++;
		}
	}

	public record HourBucket(long hourStart, double longUsd, double shortUsd) {
	}

	public record SymbolRank(String symbol, double longUsd, double shortUsd, int count) {
		public double totalUsd() {
			return longUsd + shortUsd;
		}

		/** 롱 청산 비중(%) */
		public double longShare() {
			double t = totalUsd();
			return t == 0 ? 0 : longUsd / t * 100;
		}
	}

	public record Overview(LiquidationStats lastHour, LiquidationStats lastDay, Liquidation largest,
			List<HourBucket> hourly, List<SymbolRank> bySymbol, long collectingSince) {
	}

	public record PriceBucket(double from, double to, double longUsd, double shortUsd) {
	}

	public record Distribution(String symbol, Double currentPrice, double step, List<PriceBucket> buckets,
			int eventCount) {
	}
}
