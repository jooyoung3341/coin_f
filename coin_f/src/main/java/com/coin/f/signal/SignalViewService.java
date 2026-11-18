package com.coin.f.signal;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.coin.f.market.MarketDataProvider;
import com.coin.f.signal.MarketSignal.Level;
import com.coin.f.signal.MarketSignal.SignalType;
import com.coin.f.storage.MarketDataRecorder;
import com.coin.f.storage.SignalHistoryRow;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 매매 시그널 화면 (메뉴 06) 데이터 조립
 */
@Service
public class SignalViewService {

	private static final long DAY = 86_400_000L;
	private static final int HISTORY_LIMIT = 300;

	private final SignalService signalService;
	private final MarketDataRecorder recorder;
	private final MarketDataProvider provider;
	private final JsonMapper json;

	public SignalViewService(SignalService signalService, MarketDataRecorder recorder, MarketDataProvider provider,
			JsonMapper json) {
		this.signalService = signalService;
		this.recorder = recorder;
		this.provider = provider;
		this.json = json;
	}

	public SignalView getView() {
		long now = System.currentTimeMillis();
		List<MarketSignal> current = signalService.currentSignals();
		// 시가총액 기준 미달 종목의 과거 기록은 숨김
		List<SignalHistoryRow> rows = recorder.signalHistory(now - 7 * DAY, HISTORY_LIMIT).stream()
				.filter(r -> provider.isVisible(r.getSymbol())).toList();

		Map<String, Double> prices = new HashMap<>();
		provider.getTickers(rows.stream().map(SignalHistoryRow::getSymbol).distinct().toList())
				.forEach(t -> prices.put(t.symbol(), t.price()));

		List<HistoryItem> history = new ArrayList<>(rows.size());
		for (SignalHistoryRow r : rows) {
			Double nowPrice = prices.get(r.getSymbol());
			Double changeNow = nowPrice == null || r.getPriceAt() == 0 ? null
					: (nowPrice - r.getPriceAt()) / r.getPriceAt() * 100;
			history.add(new HistoryItem(r.getSignalId(), r.getSymbol(), type(r.getSignalType()), level(r.getLevel()),
					r.getDirection(), r.getReason(), evidence(r.getEvidenceJson()), r.getPriceAt(), r.getDetectedAt(),
					r.change1h(), r.change4h(), r.change24h(), changeNow));
		}

		// 발생 후 가격이 하나도 없으면 화면에 안내 (ALTER 미적용이거나, 아직 1시간이 지난 시그널이 없음)
		boolean outcomeReady = rows.stream().anyMatch(r -> r.getPrice1h() != null);
		return new SignalView(current, countBy(current), history, typeStats(history, now), outcomeReady,
				signalService.isReady());
	}

	/** 유형별 통계 — 발생 건수 + 발생 후 평균 변화 + 쏠림 반대 방향 비율(참고용) */
	private List<TypeStat> typeStats(List<HistoryItem> history, long now) {
		Map<SignalType, List<HistoryItem>> by = new EnumMap<>(SignalType.class);
		for (SignalType t : SignalType.values()) {
			by.put(t, new ArrayList<>());
		}
		history.stream().filter(h -> h.type() != null).forEach(h -> by.get(h.type()).add(h));

		List<TypeStat> out = new ArrayList<>();
		for (SignalType t : SignalType.values()) {
			List<HistoryItem> list = by.get(t);
			int c24 = (int) list.stream().filter(h -> h.detectedAt() >= now - DAY).count();
			out.add(new TypeStat(t, c24, list.size(), avg(list, 4), avg(list, 24), contrarian(list), outcomeN(list)));
		}
		return out;
	}

	private static Double avg(List<HistoryItem> list, int h) {
		double sum = 0;
		int n = 0;
		for (HistoryItem i : list) {
			Double c = h == 4 ? i.change4h() : i.change24h();
			if (c != null) {
				sum += c;
				n++;
			}
		}
		return n == 0 ? null : sum / n;
	}

	/** 쏠린 쪽의 반대로 24h 가격이 움직인 비율 (LONG 쏠림 → 하락, SHORT 쏠림 → 상승). NEUTRAL 제외 */
	private static Double contrarian(List<HistoryItem> list) {
		int hit = 0;
		int n = 0;
		for (HistoryItem i : list) {
			if (i.change24h() == null || i.direction() == null || "NEUTRAL".equals(i.direction())) {
				continue;
			}
			n++;
			if (("LONG".equals(i.direction()) && i.change24h() < 0)
					|| ("SHORT".equals(i.direction()) && i.change24h() > 0)) {
				hit++;
			}
		}
		return n == 0 ? null : hit * 100.0 / n;
	}

	private static int outcomeN(List<HistoryItem> list) {
		return (int) list.stream().filter(i -> i.change24h() != null).count();
	}

	private static Map<Level, Integer> countBy(List<MarketSignal> list) {
		Map<Level, Integer> m = new EnumMap<>(Level.class);
		for (Level l : Level.values()) {
			m.put(l, 0);
		}
		list.forEach(s -> m.merge(s.level(), 1, Integer::sum));
		return m;
	}

	private List<String> evidence(String jsonText) {
		if (jsonText == null || jsonText.isBlank()) {
			return List.of();
		}
		try {
			return json.readValue(jsonText, new TypeReference<List<String>>() {});
		} catch (RuntimeException e) {
			return List.of();
		}
	}

	private static SignalType type(String s) {
		try {
			return SignalType.valueOf(s);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static Level level(String s) {
		try {
			return Level.valueOf(s);
		} catch (RuntimeException e) {
			return Level.LOW;
		}
	}

	public record HistoryItem(long id, String symbol, SignalType type, Level level, String direction, String reason,
			List<String> evidence, double priceAt, long detectedAt, Double change1h, Double change4h,
			Double change24h, Double changeNow) {
	}

	public record TypeStat(SignalType type, int count24h, int count7d, Double avgChange4h, Double avgChange24h,
			Double contrarianRate24h, int outcomeCount) {
	}

	/**
	 * @param outcomeReady 발생 후 가격(1h/4h/24h)이 하나라도 기록되어 있는지 — 없으면 ALTER 안내
	 */
	public record SignalView(List<MarketSignal> current, Map<Level, Integer> currentByLevel, List<HistoryItem> history,
			List<TypeStat> typeStats, boolean outcomeReady, boolean ready) {
	}
}
