package com.coin.f.signal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.coin.f.market.LiquidationStore;
import com.coin.f.market.MarketDataProvider;
import com.coin.f.market.MarketModels.Liquidation;
import com.coin.f.market.MarketModels.LiquidationStats;

/**
 * 현재 시점 시그널 판정의 단일 진입점 (대시보드 · 시그널 화면 · DB 기록이 같은 결과를 쓰도록).
 */
@Service
public class SignalService {

	private final MarketDataProvider provider;
	private final SignalDetector detector;
	private final LiquidationStore liquidations;

	public SignalService(MarketDataProvider provider, SignalDetector detector, LiquidationStore liquidations) {
		this.provider = provider;
		this.detector = detector;
		this.liquidations = liquidations;
	}

	public List<MarketSignal> currentSignals() {
		return detector.detect(provider.getFundingRates(), provider.getOpenInterestChanges(),
				provider.getLongShortRatios(), liquidationsBySymbol(SignalDetector.LIQ_WINDOW_MILLIS));
	}

	/** OI 수집 전에는 판단 근거가 불완전 */
	public boolean isReady() {
		return !provider.getOpenInterestChanges().isEmpty();
	}

	private Map<String, LiquidationStats> liquidationsBySymbol(long window) {
		long now = System.currentTimeMillis();
		Map<String, double[]> agg = new HashMap<>(); // [long, short, count]
		for (Liquidation l : liquidations.since(now - window)) {
			double[] a = agg.computeIfAbsent(l.symbol(), k -> new double[3]);
			a[l.isLong() ? 0 : 1] += l.valueUsd();
			a[2]++;
		}
		Map<String, LiquidationStats> out = new HashMap<>();
		long since = liquidations.collectingSince();
		agg.forEach((s, a) -> out.put(s, new LiquidationStats(a[0], a[1], (int) a[2], window, since)));
		return out;
	}
}
