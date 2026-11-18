package com.coin.f.oi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.coin.f.market.MarketDataProvider;
import com.coin.f.market.MarketModels.OpenInterestChange;
import com.coin.f.market.MarketModels.Ticker;
import com.coin.f.storage.MarketDataRecorder;
import com.coin.f.storage.Rows.SymbolTimeValue;

/**
 * 미결제약정 분석 (메뉴 04)
 *  - 24h 변화: 실시간 스냅샷 (5분 주기)
 *  - 1h·4h 변화: DB 의 1시간 간격 OI 이력
 */
@Service
public class OpenInterestService {

	private static final long HOUR = 3_600_000L;
	// 급증·급감 탐색 기준
	static final double SPIKE_1H = 3.0;
	static final double SPIKE_4H = 5.0;
	static final double SPIKE_24H = 10.0;

	private final MarketDataProvider provider;
	private final MarketDataRecorder recorder;

	public OpenInterestService(MarketDataProvider provider, MarketDataRecorder recorder) {
		this.provider = provider;
		this.recorder = recorder;
	}

	public OiView getView() {
		List<OpenInterestChange> current = provider.getOpenInterestChanges();
		List<String> symbols = current.stream().map(OpenInterestChange::symbol).toList();
		Map<String, Ticker> prices = new HashMap<>();
		provider.getTickers(symbols).forEach(t -> prices.put(t.symbol(), t));

		// 종목별 1h 이력 → 1h·4h 전 값
		Map<String, List<SymbolTimeValue>> hist = new HashMap<>();
		for (SymbolTimeValue v : recorder.openInterestSince("1h", System.currentTimeMillis() - 6 * HOUR)) {
			hist.computeIfAbsent(v.symbol(), k -> new ArrayList<>()).add(v);
		}

		List<OiRow> rows = new ArrayList<>();
		for (OpenInterestChange oi : current) {
			List<SymbolTimeValue> h = hist.getOrDefault(oi.symbol(), List.of());
			Double ch1 = change(h, 1);
			Double ch4 = change(h, 4);
			Ticker t = prices.get(oi.symbol());
			Double price24 = t == null ? null : t.changePercent24h();
			rows.add(new OiRow(oi.symbol(), oi.openInterestUsd(), ch1, ch4, oi.changePercent24h(), price24,
					reading(price24, oi.changePercent24h())));
		}
		rows.sort(Comparator.comparingDouble(OiRow::openInterestUsd).reversed());

		double total = rows.stream().mapToDouble(OiRow::openInterestUsd).sum();
		List<OiRow> spikes = rows.stream().filter(OiRow::isSpike)
				.sorted(Comparator.comparingDouble(OiRow::spikeScore).reversed()).toList();
		long up = rows.stream().filter(r -> r.change24h() >= SPIKE_24H).count();
		long down = rows.stream().filter(r -> r.change24h() <= -SPIKE_24H).count();

		return new OiView(new Stats(rows.size(), total, provider.getMarketSummary().openInterestChange24h(),
				(int) up, (int) down), rows, spikes);
	}

	/** 마지막 값 대비 hoursAgo 시간 전 값의 변화율(%). 이력 부족 시 null */
	private static Double change(List<SymbolTimeValue> h, int hoursAgo) {
		if (h.size() < 2) {
			return null;
		}
		SymbolTimeValue last = h.get(h.size() - 1);
		long target = last.ts() - hoursAgo * HOUR;
		for (int i = h.size() - 1; i >= 0; i--) {
			if (h.get(i).ts() <= target) {
				double base = h.get(i).value();
				return base == 0 ? null : (last.value() - base) / base * 100;
			}
		}
		return null;
	}

	/** 가격·OI 동시 변화의 교과서적 해석 (확정 신호 아님) */
	public static String reading(Double price, double oi) {
		if (price == null || Math.abs(oi) < 1) {
			return "변화 미미";
		}
		if (oi > 0) {
			return price >= 0 ? "신규 롱 유입" : "신규 숏 유입";
		}
		return price >= 0 ? "숏 청산(커버)" : "롱 청산·정리";
	}

	public record OiRow(String symbol, double openInterestUsd, Double change1h, Double change4h, double change24h,
			Double priceChange24h, String reading) {

		public boolean isSpike() {
			return Math.abs(change24h) >= SPIKE_24H
					|| (change1h != null && Math.abs(change1h) >= SPIKE_1H)
					|| (change4h != null && Math.abs(change4h) >= SPIKE_4H);
		}

		/** 기준 대비 가장 강하게 넘어선 정도 */
		public double spikeScore() {
			double s = Math.abs(change24h) / SPIKE_24H;
			if (change1h != null) {
				s = Math.max(s, Math.abs(change1h) / SPIKE_1H);
			}
			if (change4h != null) {
				s = Math.max(s, Math.abs(change4h) / SPIKE_4H);
			}
			return s;
		}
	}

	public record Stats(int count, double totalUsd, double totalChange24h, int surge, int drop) {
	}

	public record OiView(Stats stats, List<OiRow> rows, List<OiRow> spikes) {
	}
}
