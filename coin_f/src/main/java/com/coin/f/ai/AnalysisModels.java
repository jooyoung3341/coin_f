package com.coin.f.ai;

import java.util.List;
import java.util.Map;

/**
 * AI 분석 입력/출력 모델.
 * 입력은 분석 시점의 지표 스냅샷 — ai_report.input_json 에 그대로 저장해 나중에 "무엇을 보고 이렇게 판단했는지" 추적.
 */
public final class AnalysisModels {

	private AnalysisModels() {
	}

	/** 시장 전체 분석 입력 (BTC 를 시장 대표로 사용) */
	public record MarketInput(
			long at,
			double btcPrice, double btcChange24h,
			double btcHigh24h, double btcLow24h, double btcHigh7d, double btcLow7d,
			int sentimentScore, String sentimentLabel,
			double totalVolume24h,
			double oiTotal, double oiChange24h, int oiUniverse,
			double medianFunding8h, double fundingPositivePct, int hotLong, int hotShort,
			double liq1hLong, double liq1hShort, double liq24hLong, double liq24hShort, double liqCollectedHours,
			int signalsHigh, int signalsMedium, int signalsLow,
			Map<String, Integer> signalsByType) {
	}

	/** 종목 분석 입력. 수집 전 값은 null */
	public record SymbolInput(
			String symbol, long at,
			double price, double change24h,
			double high24h, double low24h, double high7d, double low7d,
			Double funding, Integer fundingIntervalHours, Double funding8h,
			Double oiUsd, Double oiChange1h, Double oiChange4h, Double oiChange24h,
			Double longPct,
			double takerBuyShare24h,
			double liq1hLong, double liq1hShort, double liq24hLong, double liq24hShort, double liqCollectedHours,
			List<String> signals,
			SymbolTrends trends) {
	}

	/**
	 * 종목 분석의 추이·세부 입력 (구체적 서술용). 수집 전/이력 없음이면 null, trends 자체가 null 일 수도 있음.
	 * 롱 비율은 % 단위(0~100).
	 * @param longPct24hAgo  전체 계정 롱 비율 24시간 전
	 * @param longPct3dAgo   전체 계정 롱 비율 3일 전 (이력 시작점)
	 * @param topLongPct     탑트레이더(상위 20%) 포지션 기준 롱 비율 현재
	 * @param fundingAvg3d   최근 3일 정산 펀딩비 평균 (정산 주기 원값)
	 * @param ma24h          1시간봉 종가 24개 평균
	 * @param ma7d           1시간봉 종가 168개 평균
	 * @param takerBuyShare4h 최근 4시간 시장가 매수 비중 (0~1)
	 * @param signalReasons  "시그널명: 발생 이유" 목록
	 */
	public record SymbolTrends(
			Double longPct24hAgo, Double longPct3dAgo, Double longPctMin3d, Double longPctMax3d,
			Double topLongPct, Double topLongPct24hAgo,
			Double fundingAvg3d, int fundingCount3d,
			double high4h, double low4h, double ma24h, double ma7d,
			double takerBuyShare4h, double change4h,
			List<String> signalReasons) {
	}

	// ------------------------------------------------------------------ 현재 지표 해석 (코인 상세)

	/**
	 * 지표 1개의 현재 상태
	 * @param tone up(상승·롱 쪽) / down(하락·숏 쪽) / warn(주의) / neutral
	 */
	public record IndicatorRow(String name, String value, String state, String tone, String comment) {
	}

	/** @param met true 충족 / false 미충족 / null 데이터 없음 */
	public record Check(String label, Boolean met, String detail) {
	}

	/**
	 * 현재 지표 해석 + 하락·상승 위험 확인 체크 (매매 추천 아님)
	 * @param at 계산 시각 (epoch ms)
	 */
	public record IndicatorView(String symbol, long at, List<IndicatorRow> rows, List<Check> downChecks,
			List<Check> upChecks) {

		public long downMet() {
			return downChecks.stream().filter(c -> Boolean.TRUE.equals(c.met())).count();
		}

		public long upMet() {
			return upChecks.stream().filter(c -> Boolean.TRUE.equals(c.met())).count();
		}
	}

	/** 시나리오 — direction: UP / DOWN / BASE(현재 지표가 무게를 두는 쪽) */
	public record Scenario(String direction, String title, String text) {
	}

	/**
	 * 분석 결과 (설계서: 시장 상황 요약 · 상승·하락 시나리오 · 과열 여부 · 주요 위험 요소)
	 * @param overheatLevel 과열 / 주의 / 중립 / 냉각
	 */
	public record AnalysisReport(String summary, List<Scenario> scenarios, String overheatLevel,
			String overheatReason, List<String> risks) {
	}
}
