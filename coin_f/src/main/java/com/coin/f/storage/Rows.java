package com.coin.f.storage;

/**
 * DB 테이블 행 모델 (MyBatis 파라미터/결과용)
 */
public final class Rows {

	private Rows() {
	}

	/** symbol */
	public record SymbolRow(String exchange, String symbol, String baseAsset, String quoteAsset) {
	}

	/** symbol_id ↔ symbol 매핑 조회 결과 */
	public record SymbolId(int symbolId, String symbol) {
	}

	/** funding_rate */
	public record FundingRow(int symbolId, long fundingTime, double fundingRate, Double markPrice) {
	}

	/** open_interest */
	public record OpenInterestRow(int symbolId, String period, long ts, double oiAmount, double oiValueUsd) {
	}

	/** long_short_ratio — 비율은 0~1 소수 */
	public record LongShortRow(int symbolId, String ratioType, String period, long ts, double longRatio,
			double shortRatio, double longShort) {
	}

	/** liquidation */
	public record LiquidationRow(int symbolId, String side, double price, double quantity, double valueUsd,
			long eventTime) {
	}

	/** 추이 차트용 (시각 epoch ms, 값) */
	public record TimeValue(long ts, double value) {
	}

	/** 종목별 시계열 (여러 종목 한 번에 조회) */
	public record SymbolTimeValue(String symbol, long ts, double value) {
	}

	/** 종목별 펀딩비 통계: 직전 정산값 + 기간 평균 (정산 1회당 원값) */
	public record FundingStat(String symbol, double lastRate, long lastTime, double avgRate, int count) {
	}

	/** 여러 종목 캔들 일괄 조회 (백테스트) */
	public record CandleRow(String symbol, long ts, double open, double high, double low, double close) {
	}

	/** market_signal */
	public record SignalRow(int symbolId, String signalType, String level, String direction, String reason,
			String evidenceJson, double priceAt, long detectedAt) {
	}
}
