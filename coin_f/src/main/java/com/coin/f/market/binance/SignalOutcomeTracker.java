package com.coin.f.market.binance;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.coin.f.market.MarketModels.Kline;
import com.coin.f.storage.MarketDataRecorder;
import com.coin.f.storage.SignalHistoryRow;

/**
 * 시그널 발생 1h·4h·24h 후 가격을 채운다 (market_signal.price_1h/4h/24h).
 * 해당 시각의 1분봉 시가를 바이낸스에서 조회 → 스케줄 지연과 무관하게 정확한 시점 가격.
 * 최근 30일 이내 시그널만 대상, 한 번에 최대 50건.
 */
@Component
@ConditionalOnProperty(name = "app.market.source", havingValue = "binance", matchIfMissing = true)
public class SignalOutcomeTracker {

	private static final Logger log = LoggerFactory.getLogger(SignalOutcomeTracker.class);
	private static final long HOUR = 3_600_000L;

	private final BinanceClient client;
	private final MarketDataRecorder recorder;

	public SignalOutcomeTracker(BinanceClient client, MarketDataRecorder recorder) {
		this.client = client;
		this.recorder = recorder;
	}

	@Scheduled(initialDelay = 45_000, fixedDelay = 300_000)
	public void fillOutcomes() {
		if (!recorder.isEnabled()) {
			return;
		}
		long now = System.currentTimeMillis();
		List<SignalHistoryRow> pending = recorder.signalsPendingOutcome(now, now - 30 * 24 * HOUR, 50);
		int filled = 0;
		for (SignalHistoryRow s : pending) {
			try {
				Double p1 = s.getPrice1h() == null ? priceAt(s, 1, now) : null;
				Double p4 = s.getPrice4h() == null ? priceAt(s, 4, now) : null;
				Double p24 = s.getPrice24h() == null ? priceAt(s, 24, now) : null;
				if (p1 != null || p4 != null || p24 != null) {
					recorder.saveSignalOutcome(s.getSignalId(), p1, p4, p24);
					filled++;
				}
			} catch (RuntimeException e) {
				log.warn("시그널 {} 결과 가격 조회 실패: {}", s.getSignalId(), e.toString());
			}
		}
		if (filled > 0) {
			log.info("시그널 발생 후 가격 {}건 갱신", filled);
		}
	}

	/** 발생 hours 시간 후 1분봉 시가. 아직 그 시각이 안 됐으면 null */
	private Double priceAt(SignalHistoryRow s, int hours, long now) {
		long t = s.getDetectedAt() + hours * HOUR;
		if (t > now) {
			return null;
		}
		List<Kline> k = client.klinesFrom(s.getSymbol(), "1m", t, 1);
		return k.isEmpty() ? null : k.get(0).open();
	}
}
