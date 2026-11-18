package com.coin.f.market.binance;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Binance USDⓈ-M Futures 응답 모델.
 * 바이낸스는 숫자를 문자열("73590.40")로 내려주며 Jackson 이 double 로 변환한다.
 */
public final class BinanceDtos {

	private BinanceDtos() {
	}

	/** GET /fapi/v1/exchangeInfo */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ExchangeInfo(List<SymbolInfo> symbols) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record SymbolInfo(String symbol, String contractType, String status, String quoteAsset, String baseAsset) {
		public boolean isTradingUsdtPerpetual() {
			return "PERPETUAL".equals(contractType) && "TRADING".equals(status) && "USDT".equals(quoteAsset);
		}
	}

	/** GET /fapi/v1/ticker/24hr */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Ticker24h(String symbol, double lastPrice, double priceChangePercent, double quoteVolume,
			double highPrice, double lowPrice, long count) {
	}

	/** GET /fapi/v1/premiumIndex */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record PremiumIndex(String symbol, double markPrice, double lastFundingRate, long nextFundingTime) {
	}

	/** GET /fapi/v1/fundingInfo — 정산 주기가 조정된 종목 목록 (없으면 8h) */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record FundingInfo(String symbol, int fundingIntervalHours) {
	}

	/** GET /fapi/v1/fundingRate (확정된 펀딩비 이력). 오래된 데이터는 markPrice 가 "" 일 수 있어 문자열로 받음 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record FundingRateHist(String symbol, long fundingTime, double fundingRate, String markPrice) {
		public Double markPriceOrNull() {
			try {
				return markPrice == null || markPrice.isBlank() ? null : Double.valueOf(markPrice);
			} catch (NumberFormatException e) {
				return null;
			}
		}
	}

	/** GET /futures/data/openInterestHist */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record OpenInterestHist(String symbol, double sumOpenInterest, double sumOpenInterestValue, long timestamp) {
	}

	/** GET /futures/data/globalLongShortAccountRatio */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record LongShortAccountRatio(String symbol, double longAccount, double shortAccount, double longShortRatio,
			long timestamp) {
	}
}
