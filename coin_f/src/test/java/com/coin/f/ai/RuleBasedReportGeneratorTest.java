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
import com.coin.f.ai.AnalysisModels.SymbolTrends;
import com.coin.f.web.Formatters;

class RuleBasedReportGeneratorTest {

	private final RuleBasedReportGenerator gen = new RuleBasedReportGenerator(new Formatters());

	private static SymbolInput symbol(Double funding8h, Double oi24, Double longPct, List<String> signals,
			double liq1h, double liq24h, double collectedHours) {
		return symbol(funding8h, oi24, longPct, signals, liq1h, liq24h, collectedHours, null);
	}

	private static SymbolInput symbol(Double funding8h, Double oi24, Double longPct, List<String> signals,
			double liq1h, double liq24h, double collectedHours, SymbolTrends trends) {
		return new SymbolInput("BTCUSDT", 0, 100_000, 1.0, 101_000, 99_000, 105_000, 95_000,
				funding8h, 8, funding8h, 7e9, 0.5, 1.0, oi24, longPct, 0.5,
				liq1h, 0, liq24h, 0, collectedHours, signals, trends);
	}

	/** 롱 비율 3일 전·24시간 전, 탑트레이더 롱, 3일 펀딩 평균만 바꾸는 추이 */
	private static SymbolTrends trends(Double long3dAgo, Double long24hAgo, Double topLong, Double fundingAvg,
			List<String> reasons) {
		return new SymbolTrends(long24hAgo, long3dAgo, 60.0, 85.0, topLong, topLong == null ? null : 52.0,
				fundingAvg, fundingAvg == null ? 0 : 9,
				100_500, 99_500, 100_200, 99_000, 0.56, -0.5, reasons);
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
	void 추이가_있으면_이동평균_롱비율변화_4시간매수비중을_서술한다() {
		AnalysisReport r = gen.analyzeSymbol(
				symbol(0.0001, 1.0, 72.0, List.of(), 0, 0, 30, trends(70.0, 71.0, 55.0, 0.0001, List.of())));
		String sum = r.summary();
		// 현재가 100,000 / 24h 평균 100,200 / 7일 평균 99,000 → 중기 상승 속 단기 조정
		assertTrue(sum.contains("24시간 평균(100,200.0)보다 0.2% 아래"), sum);
		assertTrue(sum.contains("중기 상승 흐름 안에서 단기 조정 중"), sum);
		assertTrue(sum.contains("3일 전 70.0%(+2.0%p)"), sum);
		assertTrue(sum.contains("뚜렷한 방향 변화는 없습니다"), sum);
		assertTrue(sum.contains("최근 4시간은 56.0%로 24시간 평균보다 매수세가 강해졌습니다"), sum);
		assertTrue(sum.contains("3일 정산 9회 평균"), sum);
		// 4시간 고점(100,500)이 현재가와 24시간 고점 사이 → 단기 저항으로 제시
		assertTrue(r.scenarios().get(0).text().startsWith("1차로 4시간 고점(100,500.0)"), r.scenarios().get(0).text());
	}

	@Test
	void 개인_롱_쏠림과_탑트레이더_숏이_엇갈리면_과열_근거와_위험에_넣는다() {
		AnalysisReport r = gen.analyzeSymbol(
				symbol(0.0001, 1.0, 78.0, List.of(), 0, 0, 30, trends(null, null, 45.0, null, List.of())));
		assertTrue(r.summary().contains("개인 롱 과밀 구간"), r.summary());
		assertTrue(r.overheatReason().contains("개인 롱 78.0% vs 탑트레이더 롱 45.0%"), r.overheatReason());
		assertEquals("주의", r.overheatLevel());
		assertTrue(r.risks().stream().anyMatch(x -> x.contains("개인 롱 청산이 몰릴 수")));
		// 롱 쏠림으로 취급 → 하락 시나리오 경고
		assertTrue(r.scenarios().get(1).text().contains("롱 청산이 연쇄"));
		// 3일 이력이 없으면 추이 문장은 이력 부족으로 표기하지 않음(24h 도 없음 → 문장 생략)
		assertFalse(r.summary().contains("이전 값은"));
	}

	@Test
	void 롱비율_급증과_시그널_근거를_위험에_넣는다() {
		AnalysisReport r = gen.analyzeSymbol(symbol(0.0001, 1.0, 80.0, List.of(), 0, 0, 30,
				trends(70.0, 75.0, 60.0, null, List.of("롱 과열: 롱 계정 80% · 펀딩 +0.03%"))));
		assertTrue(r.overheatReason().contains("롱 비율 3일 +10.0%p"), r.overheatReason());
		assertTrue(r.risks().stream().anyMatch(x -> x.contains("3일 만에 70.0% → 80.0%")));
		assertTrue(r.risks().contains("시그널 근거 — 롱 과열: 롱 계정 80% · 펀딩 +0.03%"));
	}

	@Test
	void 위험_요소가_없으면_없다고_명시한다() {
		AnalysisReport r = gen.analyzeSymbol(symbol(0.0001, 1.0, 72.0, List.of(), 0, 0, 30));
		assertEquals(List.of("지표 기준으로 두드러진 위험 신호는 없습니다."), r.risks());
	}
}
