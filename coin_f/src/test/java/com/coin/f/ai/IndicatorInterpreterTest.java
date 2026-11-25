package com.coin.f.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.coin.f.ai.AnalysisModels.Check;
import com.coin.f.ai.AnalysisModels.IndicatorRow;
import com.coin.f.ai.AnalysisModels.IndicatorView;
import com.coin.f.ai.AnalysisModels.SymbolInput;
import com.coin.f.ai.AnalysisModels.SymbolTrends;
import com.coin.f.web.Formatters;

class IndicatorInterpreterTest {

	private final IndicatorInterpreter interp = new IndicatorInterpreter(new Formatters());

	/** 가격 100,000 / 24h 99,800 ~ 102,000 */
	private static SymbolInput input(double funding, Double oi4h, double longPct, double takerShare24h,
			SymbolTrends t) {
		return new SymbolInput("BTCUSDT", 0, 100_000, -1.0, 102_000, 99_800, 105_000, 95_000,
				funding, 8, funding, 7e9, 0.2, oi4h, 1.0, longPct, takerShare24h,
				0, 0, 0, 0, 30, List.of(), t);
	}

	private static SymbolTrends trends(Double long3dAgo, Double topLong, Double fundingAvg, double taker4h,
			double change4h) {
		return new SymbolTrends(null, long3dAgo, 70.0, 80.0, topLong, null, fundingAvg, fundingAvg == null ? 0 : 9,
				100_800, 99_800, 100_500, 99_000, taker4h, change4h, List.of());
	}

	private static Check check(List<Check> list, String label) {
		return list.stream().filter(c -> c.label().equals(label)).findFirst().orElseThrow();
	}

	private static IndicatorRow row(IndicatorView v, String name) {
		return v.rows().stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
	}

	@Test
	void 하락_조건이_겹치면_하락_체크가_많이_충족된다() {
		// 개인 78 / 탑 45, 24h 저점 부근 하락(-1.2%), OI 4h +2%, 펀딩 상승, 4h 매수 비중 44% (24h 50%)
		IndicatorView v = interp.interpret(input(0.0002, 2.0, 78, 0.50, trends(70.0, 45.0, 0.0001, 0.44, -1.2)));
		assertEquals(5, v.downMet());
		assertEquals(0, v.upMet());
		assertEquals("엇갈림", row(v, "롱숏 (개인 vs 큰손)").state());
		assertEquals("down", row(v, "롱숏 (개인 vs 큰손)").tone());
		assertEquals("신규 숏 유입", row(v, "OI + 가격 (4h)").state());
		assertEquals("롱 증가", row(v, "롱 비율 추이 (3일)").state());
		assertEquals("매도 강화", row(v, "시장가 매수 비중").state());
	}

	@Test
	void 데이터가_없으면_충족_여부는_null() {
		IndicatorView v = interp.interpret(input(0.0001, null, 72, 0.50, null));
		assertNull(check(v.downChecks(), "개인 롱 · 큰손 숏 엇갈림").met());
		assertNull(check(v.downChecks(), "하락 중 OI 증가 (신규 숏)").met());
		assertNull(check(v.upChecks(), "4h 매수 비중 상승").met());
		assertEquals("탑 수집 중", row(v, "롱숏 (개인 vs 큰손)").state());
		assertEquals("이력 부족", row(v, "롱 비율 추이 (3일)").state());
	}

	@Test
	void 숏_스퀴즈_조건() {
		// 개인 58(숏 쏠림), 펀딩 음수, OI 4h -2% + 가격 4h +1% (숏 커버), 4h 매수 56%
		IndicatorView v = interp.interpret(input(-0.0001, -2.0, 58, 0.50, trends(null, 62.0, null, 0.56, 1.0)));
		assertEquals(true, check(v.upChecks(), "개인 숏 쏠림 · 큰손 롱").met());
		assertEquals(true, check(v.upChecks(), "상승 중 OI 감소 (숏 커버)").met());
		assertEquals(true, check(v.upChecks(), "펀딩 음수 · 하락").met());
		assertEquals(true, check(v.upChecks(), "4h 매수 비중 상승").met());
		assertEquals("숏 청산(커버)", row(v, "OI + 가격 (4h)").state());
	}
}
