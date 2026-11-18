package com.coin.f.signal;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.coin.f.market.MarketDataProvider;
import com.coin.f.market.MarketModels.Ticker;
import com.coin.f.signal.MarketSignal.SignalType;
import com.coin.f.storage.MarketDataRecorder;
import com.coin.f.storage.Rows.SignalRow;

import tools.jackson.databind.json.JsonMapper;

/**
 * 1분마다 시그널을 탐지해 market_signal 에 기록 (8단계 백테스트의 원천 데이터).
 * 같은 종목·유형은 일정 시간 안에 한 번만 기록 → 조건이 계속 유지되는 동안 중복 적재 방지.
 */
@Component
public class SignalRecorder {

	private static final Logger log = LoggerFactory.getLogger(SignalRecorder.class);

	/** 유형별 중복 방지 구간: 대량 청산은 짧은 폭발성 이벤트라 1시간, 나머지는 4시간 */
	static long dedupeMillis(SignalType type) {
		return type == SignalType.MASS_LIQUIDATION ? 3_600_000L : 4 * 3_600_000L;
	}

	private final MarketDataProvider provider;
	private final SignalService signalService;
	private final MarketDataRecorder recorder;
	private final JsonMapper json;

	public SignalRecorder(MarketDataProvider provider, SignalService signalService, MarketDataRecorder recorder,
			JsonMapper json) {
		this.provider = provider;
		this.signalService = signalService;
		this.recorder = recorder;
		this.json = json;
	}

	@Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
	public void record() {
		if (!recorder.isEnabled() || provider.isSample() || !signalService.isReady()) {
			return; // 샘플 모드이거나 OI 수집 전이면 판단 근거가 불완전 → 기록 안 함
		}
		List<MarketSignal> signals = signalService.currentSignals();
		if (signals.isEmpty()) {
			return;
		}
		Map<String, Ticker> prices = provider.getTickers(signals.stream().map(MarketSignal::symbol).distinct().toList())
				.stream().collect(Collectors.toMap(Ticker::symbol, Function.identity()));

		long now = System.currentTimeMillis();
		int saved = 0;
		for (MarketSignal s : signals) {
			Integer sid = recorder.symbolId(s.symbol());
			Ticker t = prices.get(s.symbol());
			if (sid == null || t == null
					|| recorder.hasSignalSince(sid, s.type().name(), now - dedupeMillis(s.type()))) {
				continue;
			}
			recorder.saveSignal(new SignalRow(sid, s.type().name(), s.level().name(), s.crowdedSide(), s.reason(),
					json.writeValueAsString(s.evidence()), t.price(), now));
			saved++;
		}
		if (saved > 0) {
			log.info("신규 시그널 {}건 기록", saved);
		}
	}
}
