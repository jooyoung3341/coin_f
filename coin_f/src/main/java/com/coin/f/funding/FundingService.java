package com.coin.f.funding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.coin.f.market.MarketDataProvider;
import com.coin.f.market.MarketModels.FundingRate;
import com.coin.f.storage.MarketDataRecorder;
import com.coin.f.storage.Rows.FundingStat;
import com.coin.f.storage.Rows.TimeValue;

/**
 * 펀딩비 분석 (메뉴 03)
 * 정산 주기가 종목마다 달라(8h/4h/1h) 순위·과열 판정은 모두 8시간 환산값 기준.
 */
@Service
public class FundingService {

	static final double HOT = 0.0003;           // ±0.03% (8h) 과열
	static final double ALERT_DELTA = 0.0001;   // 직전 정산 대비 ±0.01%p 이상 변하면 급변
	private static final int RANK = 15;
	private static final long DAY = 86_400_000L;

	private final MarketDataProvider provider;
	private final MarketDataRecorder recorder;

	public FundingService(MarketDataProvider provider, MarketDataRecorder recorder) {
		this.provider = provider;
		this.recorder = recorder;
	}

	public FundingView getView() {
		List<FundingRate> all = provider.getFundingRates();

		double[] sorted = all.stream().mapToDouble(FundingRate::rate8h).sorted().toArray();
		double median = sorted.length == 0 ? 0
				: sorted.length % 2 == 1 ? sorted[sorted.length / 2]
				: (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2;
		long positive = all.stream().filter(f -> f.rate() > 0).count();
		long hotLong = all.stream().filter(f -> f.rate8h() >= HOT).count();
		long hotShort = all.stream().filter(f -> f.rate8h() <= -HOT).count();
		long nextFunding = all.stream().filter(f -> f.symbol().equals("BTCUSDT"))
				.mapToLong(FundingRate::nextFundingTime).findFirst().orElse(0);

		Stats stats = new Stats(all.size(), median, all.isEmpty() ? 0 : positive * 100.0 / all.size(),
				(int) hotLong, (int) hotShort, nextFunding);

		return new FundingView(stats,
				all.stream().sorted(Comparator.comparingDouble(FundingRate::rate8h).reversed()).limit(RANK).toList(),
				all.stream().sorted(Comparator.comparingDouble(FundingRate::rate8h)).limit(RANK).toList(),
				alerts(all));
	}

	/**
	 * 급등·급락: 현재 예측 펀딩비 vs 직전 확정 정산값 (둘 다 8h 환산).
	 * DB 에 이력이 있는 종목(추적 상위 30 + 기본 종목)만 대상.
	 */
	private List<FundingAlert> alerts(List<FundingRate> current) {
		Map<String, FundingRate> now = current.stream()
				.collect(Collectors.toMap(FundingRate::symbol, Function.identity(), (a, b) -> a));
		List<FundingAlert> out = new ArrayList<>();
		for (FundingStat s : recorder.fundingStats(System.currentTimeMillis() - 7 * DAY)) {
			FundingRate f = now.get(s.symbol());
			if (f == null) {
				continue;
			}
			double scale = 8.0 / f.intervalHours();
			double last8h = s.lastRate() * scale;
			double avg8h = s.avgRate() * scale;
			double delta = f.rate8h() - last8h;
			if (Math.abs(delta) >= ALERT_DELTA) {
				out.add(new FundingAlert(s.symbol(), f.rate8h(), last8h, avg8h, delta, f.intervalHours()));
			}
		}
		out.sort(Comparator.comparingDouble((FundingAlert a) -> Math.abs(a.delta())).reversed());
		return out.size() > 10 ? out.subList(0, 10) : out;
	}

	/** 펀딩비 이력이 저장된 종목 (차트 선택 목록) */
	public List<String> historySymbols() {
		return recorder.fundingStats(System.currentTimeMillis() - 7 * DAY).stream()
				.map(FundingStat::symbol).filter(provider::isVisible).sorted().toList();
	}

	public List<TimeValue> history(String symbol, int days) {
		long since = System.currentTimeMillis() - Math.max(1, Math.min(days, 90)) * DAY;
		return recorder.fundingHistory(symbol, since);
	}

	public record Stats(int count, double median8h, double positivePercent, int hotLong, int hotShort,
			long nextFundingTime) {
	}

	/** @param delta 현재 - 직전 정산 (8h 환산) */
	public record FundingAlert(String symbol, double current8h, double last8h, double avg7d8h, double delta,
			int intervalHours) {
	}

	public record FundingView(Stats stats, List<FundingRate> top, List<FundingRate> bottom,
			List<FundingAlert> alerts) {
	}
}
