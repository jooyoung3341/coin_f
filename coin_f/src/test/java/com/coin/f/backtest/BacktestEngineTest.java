package com.coin.f.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.coin.f.backtest.BacktestEngine.Simulation;
import com.coin.f.backtest.BacktestModels.Bar;
import com.coin.f.backtest.BacktestModels.EquityPoint;
import com.coin.f.backtest.BacktestModels.Mode;
import com.coin.f.backtest.BacktestModels.Params;
import com.coin.f.backtest.BacktestModels.SymbolSeries;
import com.coin.f.backtest.BacktestModels.Trade;
import com.coin.f.signal.MarketSignal.SignalType;
import com.coin.f.signal.SignalDetector;

class BacktestEngineTest {

	private static final long H = BacktestEngine.HOUR;
	private static final long T0 = 472_222L * H; // 정시
	private final BacktestEngine engine = new BacktestEngine(new SignalDetector());

	/** 48개 1시간봉(가격 100 고정) + 8시간 주기 펀딩비 0.05%(과열) → 펀딩비 과열(롱 쏠림)만 발생 */
	private static SymbolSeries series(Map<Integer, Bar> overrides) {
		Map<Long, Bar> bars = new HashMap<>();
		for (int i = 0; i < 48; i++) {
			long t = T0 + i * H;
			bars.put(t, overrides.getOrDefault(i, new Bar(t, 100, 100, 100, 100)));
		}
		TreeMap<Long, Double> funding = new TreeMap<>();
		for (int i = -2; i <= 5; i++) {
			funding.put(T0 + i * 8 * H, 0.0005);
		}
		return new SymbolSeries("XUSDT", bars, Map.of(), Map.of(), funding);
	}

	private static Params params(Mode mode, Double tp, Double sl) {
		return new Params(null, null, 30, 4, mode, tp, sl, 0.08);
	}

	@Test
	void 다음봉_시가_진입_보유시간_후_종가_청산_수수료_차감() {
		// 4번째 봉(진입 후 마지막 보유 봉) 종가 99 → 숏 +1% - 0.08%
		SymbolSeries s = series(Map.of(4, new Bar(T0 + 4 * H, 100, 100, 99, 99)));
		Simulation sim = engine.simulate(List.of(s), params(Mode.CONTRARIAN, null, null), T0, T0 + 48 * H);

		Trade first = sim.trades().get(0);
		assertEquals(SignalType.FUNDING_OVERHEAT, first.type());
		assertEquals("LONG", first.crowdedSide());
		assertEquals("SHORT", first.position());
		assertEquals(T0 + H, first.entryTime());
		assertEquals(T0 + 5 * H, first.exitTime());
		assertEquals(0.92, first.returnPct(), 1e-9);

		// 4시간 간격, 이전 거래 청산 후에만 재진입: t = 0,4,...,40 → 11건, t=44 는 데이터 끝을 넘어 1건 제외
		assertEquals(11, sim.trades().size());
		assertEquals(1, sim.openSkipped());
		for (int i = 1; i < sim.trades().size(); i++) {
			assertTrue(sim.trades().get(i).entryTime() >= sim.trades().get(i - 1).exitTime());
		}

		// 추세 추종이면 반대 결과
		Trade follow = engine.simulate(List.of(s), params(Mode.FOLLOW, null, null), T0, T0 + 48 * H).trades().get(0);
		assertEquals("LONG", follow.position());
		assertEquals(-1.08, follow.returnPct(), 1e-9);
	}

	@Test
	void 같은_봉에서_익절_손절_모두_닿으면_손절() {
		// 숏 진입 100 → 손절 101 / 익절 99, 진입봉이 98~102 로 둘 다 닿음
		SymbolSeries s = series(Map.of(1, new Bar(T0 + H, 100, 102, 98, 100)));
		Trade t = engine.simulate(List.of(s), params(Mode.CONTRARIAN, 1.0, 1.0), T0, T0 + H).trades().get(0);
		assertEquals("SL", t.exitReason());
		assertEquals(101, t.exitPrice(), 1e-9);
		assertEquals(T0 + 2 * H, t.exitTime());
		assertEquals(-1.08, t.returnPct(), 1e-9);
	}

	@Test
	void 유형_필터() {
		Params oiOnly = new Params(SignalType.OI_SURGE, null, 30, 4, Mode.CONTRARIAN, null, null, 0.08);
		assertTrue(engine.simulate(List.of(series(Map.of())), oiOnly, T0, T0 + 48 * H).trades().isEmpty());
	}

	@Test
	void 펀딩_정산주기는_직전_간격으로_추정해_8시간_환산() {
		TreeMap<Long, Double> funding = new TreeMap<>();
		for (int i = 0; i < 4; i++) {
			funding.put(T0 + i * 4 * H + 3, 0.0002); // 4h 주기, 정산 시각 ms 오차
		}
		SymbolSeries s = new SymbolSeries("YUSDT", Map.of(), Map.of(), Map.of(), funding);
		var f = BacktestEngine.fundingAt(s, T0 + 9 * H);
		assertEquals(4, f.intervalHours());
		assertEquals(0.0004, f.rate8h(), 1e-12);
		assertEquals(null, BacktestEngine.fundingAt(s, T0 - H));
	}

	@Test
	void 최대낙폭은_시작점_0을_포함한_고점_대비() {
		assertEquals(3.0, BacktestEngine.maxDrawdown(List.of(new EquityPoint(1, 2), new EquityPoint(2, -1),
				new EquityPoint(3, 3), new EquityPoint(4, 0))), 1e-9);
		assertEquals(2.0, BacktestEngine.maxDrawdown(List.of(new EquityPoint(1, -2))), 1e-9);
	}
}
