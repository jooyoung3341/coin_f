package com.coin.f.storage;

import java.time.LocalDateTime;

/**
 * ai_report 행.
 *  - scenarios: JSON 객체 {"items":[{direction,title,text}...], "overheatReason":"..."}
 *    (과열 판단 근거를 담을 별도 컬럼이 없어 시나리오 JSON 에 함께 저장)
 *  - risks: JSON 배열 ["...", ...]
 *  - input_json: 분석 시점 지표 스냅샷
 */
public class AiReportRow {

	private Long reportId;
	private Integer symbolId;
	private String symbol;
	private String reportType;
	private String summary;
	private String scenarios;
	private String overheatLevel;
	private String risks;
	private String inputJson;
	private String model;
	private LocalDateTime createdAt;

	public Long getReportId() { return reportId; }
	public void setReportId(Long reportId) { this.reportId = reportId; }
	public Integer getSymbolId() { return symbolId; }
	public void setSymbolId(Integer symbolId) { this.symbolId = symbolId; }
	public String getSymbol() { return symbol; }
	public void setSymbol(String symbol) { this.symbol = symbol; }
	public String getReportType() { return reportType; }
	public void setReportType(String reportType) { this.reportType = reportType; }
	public String getSummary() { return summary; }
	public void setSummary(String summary) { this.summary = summary; }
	public String getScenarios() { return scenarios; }
	public void setScenarios(String scenarios) { this.scenarios = scenarios; }
	public String getOverheatLevel() { return overheatLevel; }
	public void setOverheatLevel(String overheatLevel) { this.overheatLevel = overheatLevel; }
	public String getRisks() { return risks; }
	public void setRisks(String risks) { this.risks = risks; }
	public String getInputJson() { return inputJson; }
	public void setInputJson(String inputJson) { this.inputJson = inputJson; }
	public String getModel() { return model; }
	public void setModel(String model) { this.model = model; }
	public LocalDateTime getCreatedAt() { return createdAt; }
	public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
