package com.coin.f.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.coin.f.market.MarketModels.FundingRate;
import com.coin.f.market.MarketModels.LiquidationStats;
import com.coin.f.market.MarketModels.LongShortRatio;
import com.coin.f.market.MarketModels.OpenInterestChange;
import com.coin.f.signal.MarketSignal.Level;
import com.coin.f.signal.MarketSignal.SignalType;

class SignalDetectorTest {

	private final SignalDetector detector = new SignalDetector();

	private static FundingRate funding(String s, double rate, int interval, double volume) {
		return new FundingRate(s, rate, 0, interval, volume);
	}

	private static LiquidationStats liq(double longUsd, double shortUsd) {
		return new LiquidationStats(longUsd, shortUsd, 10, SignalDetector.LIQ_WINDOW_MILLIS, 0);
	}

	private List<MarketSignal> detect(List<FundingRate> f, List<OpenInterestChange> oi, List<LongShortRatio> ls,
			Map<String, LiquidationStats> liq) {
		return detector.detect(f, oi, ls, liq);
	}

	@Test
	void 펀딩비는_8시간_환산으로_판정한다() {
		// 4h 주기 0.02% → 8h 환산 0.04% → 과열
		var four = detect(List.of(funding("AAAUSDT", 0.0002, 4, 1e8)), List.of(), List.of(), Map.of());
		assertEquals(1, four.size());
		assertEquals(SignalType.FUNDING_OVERHEAT, four.get(0).type());

		// 8h 주기 0.02% → 과열 아님
		var eight = detect(List.of(funding("BBBUSDT", 0.0002, 8, 1e8)), List.of(), List.of(), Map.of());
		assertTrue(eight.isEmpty());
	}

	@Test
	void 대량청산은_거래대금_비례_기준을_넘을때만_감지한다() {
		// 거래대금 $10B → 기준 = max(200K, 10B × 0.01%) = $1M
		FundingRate btc = funding("BTCUSDT", 0.0001, 8, 10_000_000_000.0);

		var below = detect(List.of(btc), List.of(), List.of(), Map.of("BTCUSDT", liq(900_000, 0)));
		assertTrue(below.isEmpty(), "기준 미만이면 감지 안 함");

		var medium = detect(List.of(btc), List.of(), List.of(), Map.of("BTCUSDT", liq(1_200_000, 100_000)));
		assertEquals(1, medium.size());
		assertEquals(SignalType.MASS_LIQUIDATION, medium.get(0).type());
		assertEquals(Level.MEDIUM, medium.get(0).level());
		assertEquals("LONG", medium.get(0).crowdedSide(), "롱 청산 우세");

		var high = detect(List.of(btc), List.of(), List.of(), Map.of("BTCUSDT", liq(0, 3_500_000)));
		assertEquals(Level.HIGH, high.get(0).level(), "기준 3배 이상이면 높음");
		assertEquals("SHORT", high.get(0).crowdedSide(), "숏 청산 우세");
	}

	@Test
	void 소형종목은_최소금액_기준을_적용한다() {
		// 거래대금 $50M → 비례 기준 $5K 이지만 최소 $200K 적용
		FundingRate small = funding("SMLUSDT", 0.0001, 8, 50_000_000);
		assertTrue(detect(List.of(small), List.of(), List.of(), Map.of("SMLUSDT", liq(150_000, 0))).isEmpty());
		assertEquals(1, detect(List.of(small), List.of(), List.of(), Map.of("SMLUSDT", liq(250_000, 0))).size());
	}

	@Test
	void 펀딩_OI_쏠림이_동시에_충족되면_복합_시그널_하나만_낸다() {
		var longSide = detect(List.of(funding("XUSDT", 0.0005, 8, 1e8)),
				List.of(new OpenInterestChange("XUSDT", 1e8, 15)),
				List.of(new LongShortRatio("XUSDT", 85, 15)), Map.of());
		assertEquals(1, longSide.size());
		assertEquals(SignalType.COMPOSITE, longSide.get(0).type());
		assertEquals(Level.HIGH, longSide.get(0).level());
		assertEquals("LONG", longSide.get(0).crowdedSide());

		var shortSide = detect(List.of(funding("YUSDT", -0.0005, 8, 1e8)),
				List.of(new OpenInterestChange("YUSDT", 1e8, 15)),
				List.of(new LongShortRatio("YUSDT", 40, 60)), Map.of());
		assertEquals(SignalType.COMPOSITE, shortSide.get(0).type());
		assertEquals("SHORT", shortSide.get(0).crowdedSide());
	}

	@Test
	void 중요도_높은_순으로_정렬한다() {
		var list = detect(
				List.of(funding("AUSDT", 0.0001, 8, 1e8), funding("BUSDT", 0.0005, 8, 1e8)),
				List.of(),
				List.of(new LongShortRatio("AUSDT", 85, 15)),
				Map.of("BUSDT", liq(0, 1_000_000)));
		assertEquals(Level.HIGH, list.get(0).level());
		assertEquals(Level.LOW, list.get(list.size() - 1).level());
	}
}
