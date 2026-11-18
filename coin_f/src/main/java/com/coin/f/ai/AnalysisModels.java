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
			List<String> signals) {
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
