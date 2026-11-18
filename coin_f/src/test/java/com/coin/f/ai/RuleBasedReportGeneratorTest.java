package com.coin.f.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.coin.f.ai.AnalysisModels.AnalysisReport;
import com.coin.f.ai.AnalysisModels.MarketInput;
import com.coin.f.ai.AnalysisModels.Scenario;
import com.coin.f.ai.AnalysisModels.SymbolInput;
import com.coin.f.web.Formatters;

class RuleBasedReportGeneratorTest {

	private final RuleBasedReportGenerator gen = new RuleBasedReportGenerator(new Formatters());

	private static SymbolInput symbol(Double funding8h, Double oi24, Double longPct, List<String> signals,
			double liq1h, double liq24h, double collectedHours) {
		return new SymbolInput("BTCUSDT", 0, 100_000, 1.0, 101_000, 99_000, 105_000, 95_000,
				funding8h, 8, funding8h, 7e9, 0.5, 1.0, oi24, longPct, 0.5,
				liq1h, 0, liq24h, 0, collectedHours, signals);
	}

	private static MarketInput market(double medianFunding8h, double oi24, int sentiment, int hotLong) {
		return new MarketInput(0, 100_000, -2, 101_000, 98_000, 105_000, 95_000, sentiment, "중립", 4e10,
				1.5e10, oi24, 30, medianFunding8h, 70, hotLong, 0, 0, 0, 0, 0, 30, 0, 0, 0, Map.of());
	}

	@Test
	void 과열_수준_판정() {
		// 펀딩 과열(+2) + OI 급증(+1) + 롱 쏠림(+1) → 과열
		assertEquals("과열", gen.analyzeSymbol(symbol(0.0005, 15.0, 85.0, List.of(), 0, 0, 30)).overheatLevel());
		// 기본 펀딩, 변화 없음 → 중립
		assertEquals("중립", gen.analyzeSymbol(symbol(0.0001, 1.0, 72.0, List.of(), 0, 0, 30)).overheatLevel());
		// 숏 과열(-2) + OI 급감(-1) → 냉각
		assertEquals("냉각", gen.analyzeSymbol(symbol(-0.0005, -12.0, 70.0, List.of(), 0, 0, 30)).overheatLevel());
		// 시장: 펀딩 롱 우위(+1) + OI +6%(+1) + 탐욕(+1) → 과열
		assertEquals("과열", gen.analyzeMarket(market(0.00016, 6, 75, 0)).overheatLevel());
	}

	@Test
	void 시나리오는_상승_하락_무게_3개() {
		AnalysisReport r = gen.analyzeSymbol(symbol(0.0001, 1.0, 72.0, List.of(), 0, 0, 30));
		assertEquals(List.of("UP", "DOWN", "BASE"), r.scenarios().stream().map(Scenario::direction).toList());
		// 가격 뒤 조사 오류(예: "101,000.0를") 방지 — 괄호 표기 사용
		r.scenarios().forEach(s -> assertFalse(s.text().matches(".*\\d를.*"), s.text()));
		assertTrue(r.scenarios().get(0).text().contains("24시간 고점(101,000.0)"));
	}

	@Test
	void 쏠림_방향에_따라_시나리오_경고가_붙는다() {
		AnalysisReport longCrowd = gen.analyzeSymbol(symbol(0.0005, 1.0, 85.0, List.of(), 0, 0, 30));
		assertTrue(longCrowd.scenarios().get(1).text().contains("롱 청산이 연쇄"));
		AnalysisReport shortCrowd = gen.analyzeSymbol(symbol(-0.0005, 1.0, 40.0, List.of(), 0, 0, 30));
		assertTrue(shortCrowd.scenarios().get(0).text().contains("숏 청산이 상승을 가속"));
	}

	@Test
	void 청산_수집_3시간_미만이면_1시간_급증_경고를_내지_않는다() {
		// 수집 1시간: 1h 청산 = 24h 청산 → 비교 무의미
		AnalysisReport shortCollect = gen.analyzeSymbol(symbol(0.0001, 1.0, 72.0, List.of(), 10_000, 10_000, 1));
		assertTrue(shortCollect.risks().stream().noneMatch(x -> x.contains("시간당 평균")));
		// 수집 24시간, 1h 청산이 평균의 6배
		AnalysisReport spike = gen.analyzeSymbol(symbol(0.0001, 1.0, 72.0, List.of(), 6_000, 24_000, 24));
		assertTrue(spike.risks().stream().anyMatch(x -> x.contains("시간당 평균의 6.0배")));
	}

	@Test
	void 위험_요소가_없으면_없다고_명시한다() {
		AnalysisReport r = gen.analyzeSymbol(symbol(0.0001, 1.0, 72.0, List.of(), 0, 0, 30));
		assertEquals(List.of("지표 기준으로 두드러진 위험 신호는 없습니다."), r.risks());
	}
}
