package com.coin.f.storage;

import java.time.LocalDateTime;

/**
 * backtest_run 행 (+ 목록 조회 시 backtest_result 요약 컬럼 JOIN).
 *  - signal_type: 시그널 유형 또는 'ALL'
 *  - symbol_id: NULL = 전체 종목
 *  - params_json: 진입 방향 모드 · 수수료 · 기간 등 테이블 컬럼에 없는 조건
 */
public class BacktestRunRow {

	private Long runId;
	private Integer symbolId;
	private String symbol;
	private String signalType;
	private long startTime;
	private long endTime;
	private int holdMinutes;
	private Double takeProfit;
	private Double stopLoss;
	private String paramsJson;
	private String status;
	private LocalDateTime createdAt;

	// backtest_result (목록 조회용, 결과 없으면 null)
	private Integer signalCount;
	private Integer winCount;
	private Double winRate;
	private Double avgReturn;
	private Double totalReturn;
	private Double maxDrawdown;
	private String periodJson;

	public Long getRunId() { return runId; }
	public void setRunId(Long runId) { this.runId = runId; }
	public Integer getSymbolId() { return symbolId; }
	public void setSymbolId(Integer symbolId) { this.symbolId = symbolId; }
	public String getSymbol() { return symbol; }
	public void setSymbol(String symbol) { this.symbol = symbol; }
	public String getSignalType() { return signalType; }
	public void setSignalType(String signalType) { this.signalType = signalType; }
	public long getStartTime() { return startTime; }
	public void setStartTime(long startTime) { this.startTime = startTime; }
	public long getEndTime() { return endTime; }
	public void setEndTime(long endTime) { this.endTime = endTime; }
	public int getHoldMinutes() { return holdMinutes; }
	public void setHoldMinutes(int holdMinutes) { this.holdMinutes = holdMinutes; }
	public Double getTakeProfit() { return takeProfit; }
	public void setTakeProfit(Double takeProfit) { this.takeProfit = takeProfit; }
	public Double getStopLoss() { return stopLoss; }
	public void setStopLoss(Double stopLoss) { this.stopLoss = stopLoss; }
	public String getParamsJson() { return paramsJson; }
	public void setParamsJson(String paramsJson) { this.paramsJson = paramsJson; }
	public String getStatus() { return status; }
	public void setStatus(String status) { this.status = status; }
	public LocalDateTime getCreatedAt() { return createdAt; }
	public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
	public Integer getSignalCount() { return signalCount; }
	public void setSignalCount(Integer signalCount) { this.signalCount = signalCount; }
	public Integer getWinCount() { return winCount; }
	public void setWinCount(Integer winCount) { this.winCount = winCount; }
	public Double getWinRate() { return winRate; }
	public void setWinRate(Double winRate) { this.winRate = winRate; }
	public Double getAvgReturn() { return avgReturn; }
	public void setAvgReturn(Double avgReturn) { this.avgReturn = avgReturn; }
	public Double getTotalReturn() { return totalReturn; }
	public void setTotalReturn(Double totalReturn) { this.totalReturn = totalReturn; }
	public Double getMaxDrawdown() { return maxDrawdown; }
	public void setMaxDrawdown(Double maxDrawdown) { this.maxDrawdown = maxDrawdown; }
	public String getPeriodJson() { return periodJson; }
	public void setPeriodJson(String periodJson) { this.periodJson = periodJson; }
}
