package com.coin.f.ai;

import com.coin.f.ai.AnalysisModels.AnalysisReport;
import com.coin.f.ai.AnalysisModels.MarketInput;
import com.coin.f.ai.AnalysisModels.SymbolInput;

/**
 * 분석 리포트 생성기.
 * app.ai.generator=rule   → RuleBasedReportGenerator (무료, 기본)
 * app.ai.generator=claude → (추후) Claude API 구현체 — 같은 입력을 프롬프트로 넘기고 같은 형식으로 받는다.
 */
public interface ReportGenerator {

	/** ai_report.model 에 저장될 이름 (예: rule-based-v1, claude-haiku-5-5) */
	String modelName();

	/** 화면 표시용 설명 */
	String description();

	/**
	 * 호출 비용이 없는지. true 일 때만 코인 상세 화면에서 요청 시 즉석 분석을 허용한다
	 * (유료 AI 로 바꾸면 화면 조회마다 과금되지 않도록 기본 false).
	 */
	default boolean isFree() {
		return false;
	}

	AnalysisReport analyzeMarket(MarketInput input);

	AnalysisReport analyzeSymbol(SymbolInput input);
}
