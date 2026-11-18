package com.coin.f.storage;

/**
 * market_signal 조회 행.
 * record 가 아닌 setter 클래스인 이유: SELECT m.* 자동 매핑은 결과에 있는 컬럼만 채우므로,
 * price_1h/4h/24h 컬럼을 추가(ALTER)하기 전에도 조회가 실패하지 않는다 (값은 null).
 */
public class SignalHistoryRow {

	private long signalId;
	private String symbol;
	private String signalType;
	private String level;
	private String direction;
	private String reason;
	private String evidenceJson;
	private double priceAt;
	private long detectedAt;
	private Double price1h;
	private Double price4h;
	private Double price24h;

	/** 발생가 대비 변화율(%), 값 없으면 null */
	public Double change1h() {
		return change(price1h);
	}

	public Double change4h() {
		return change(price4h);
	}

	public Double change24h() {
		return change(price24h);
	}

	private Double change(Double p) {
		return p == null || priceAt == 0 ? null : (p - priceAt) / priceAt * 100;
	}

	public long getSignalId() { return signalId; }
	public void setSignalId(long signalId) { this.signalId = signalId; }
	public String getSymbol() { return symbol; }
	public void setSymbol(String symbol) { this.symbol = symbol; }
	public String getSignalType() { return signalType; }
	public void setSignalType(String signalType) { this.signalType = signalType; }
	public String getLevel() { return level; }
	public void setLevel(String level) { this.level = level; }
	public String getDirection() { return direction; }
	public void setDirection(String direction) { this.direction = direction; }
	public String getReason() { return reason; }
	public void setReason(String reason) { this.reason = reason; }
	public String getEvidenceJson() { return evidenceJson; }
	public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }
	public double getPriceAt() { return priceAt; }
	public void setPriceAt(double priceAt) { this.priceAt = priceAt; }
	public long getDetectedAt() { return detectedAt; }
	public void setDetectedAt(long detectedAt) { this.detectedAt = detectedAt; }
	public Double getPrice1h() { return price1h; }
	public void setPrice1h(Double price1h) { this.price1h = price1h; }
	public Double getPrice4h() { return price4h; }
	public void setPrice4h(Double price4h) { this.price4h = price4h; }
	public Double getPrice24h() { return price24h; }
	public void setPrice24h(Double price24h) { this.price24h = price24h; }
}
