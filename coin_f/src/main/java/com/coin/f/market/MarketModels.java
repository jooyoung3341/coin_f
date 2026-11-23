package com.coin.f.market;

import java.util.List;

import com.coin.f.storage.Rows.TimeValue;

/**
 * 시장 데이터 모델 모음.
 * 금액 단위는 USD(USDT), 비율은 소수(0.0001 = 0.01%) 기준.
 */
public final class MarketModels {

	private MarketModels() {
	}

	/** 현재가 */
	public record Ticker(String symbol, double price, double changePercent24h, double quoteVolume24h) {
		public String baseAsset() {
			return symbol.endsWith("USDT") ? symbol.substring(0, symbol.length() - 4) : symbol;
		}
	}

	/**
	 * 1시간 단위 추이 묶음 (시간 오름차순)
	 * @param longShort 전체 계정 기준 롱 비율 (0~1)
	 * @param topLongShort 탑트레이더(상위 20%) 포지션 기준 롱 비율 (0~1)
	 */
	public record HistorySeries(List<TimeValue> price, List<TimeValue> openInterest, List<TimeValue> longShort,
			List<TimeValue> topLongShort) {
	}

	/** 24시간 상세 시세 (고가·저가·체결 건수 포함) */
	public record TickerDetail(String symbol, double price, double changePercent24h, double high24h, double low24h,
			double quoteVolume24h, long trades24h) {

		/** 24시간 변동폭 (저가 대비 고가, %) */
		public double rangePercent() {
			return low24h <= 0 ? 0 : (high24h - low24h) / low24h * 100;
		}
	}

	/**
	 * 시장 요약
	 * @param totalOpenInterest 바이낸스는 시장 전체 OI 를 제공하지 않으므로 거래대금 상위 oiUniverseSize 종목 합계
	 * @param medianFundingRate 평균은 소수 종목의 극단값(-1% 등)에 끌려가므로 중앙값 사용
	 */
	public record MarketSummary(double totalVolume24h, double totalOpenInterest, double openInterestChange24h,
			double medianFundingRate, Sentiment sentiment, int oiUniverseSize) {

		public static MarketSummary empty() {
			return new MarketSummary(0, 0, 0, 0, Sentiment.of(50), 0);
		}
	}

	/** 시장 분위기 (0 = 극단적 공포, 100 = 극단적 탐욕) */
	public record Sentiment(int score, String label) {
		public static Sentiment of(int score) {
			String label = score >= 75 ? "과열(탐욕)"
					: score >= 55 ? "낙관"
					: score > 45 ? "중립"
					: score > 25 ? "비관" : "공포";
			return new Sentiment(score, label);
		}
	}

	/**
	 * 펀딩비
	 * @param rate 정산 1회당 비율 (예측값)
	 * @param intervalHours 정산 주기 (바이낸스: 8h 기본, 다수 알트는 4h, 일부 1h)
	 * @param quoteVolume24h 24h 거래대금 (순위 화면 표시용)
	 */
	public record FundingRate(String symbol, double rate, long nextFundingTime, int intervalHours,
			double quoteVolume24h) {

		public double ratePercent() {
			return rate * 100;
		}

		/** 8시간 환산 — 주기가 다른 종목끼리 비교·과열 판정할 때 사용 */
		public double rate8h() {
			return intervalHours <= 0 ? rate : rate * 8.0 / intervalHours;
		}

		/** 연환산(단리) = 8h 환산 × 3회 × 365일 */
		public double annualized() {
			return rate8h() * 3 * 365;
		}
	}

	/** 미결제약정 변화 */
	public record OpenInterestChange(String symbol, double openInterestUsd, double changePercent24h) {
	}

	/** 롱숏 비율 (계정 기준) */
	public record LongShortRatio(String symbol, double longPercent, double shortPercent) {
		public double ratio() {
			return shortPercent == 0 ? 0 : longPercent / shortPercent;
		}
	}

	/**
	 * 청산 이벤트
	 * @param side SELL = 롱 포지션 청산, BUY = 숏 포지션 청산
	 */
	public record Liquidation(String symbol, String side, double price, double quantity, double valueUsd, long time) {
		public boolean isLong() {
			return "SELL".equals(side);
		}
	}

	/** 최근 구간 청산 집계 */
	public record LiquidationStats(double longUsd, double shortUsd, int count, long windowMillis, long collectingSince) {
		public double totalUsd() {
			return longUsd + shortUsd;
		}

		/** 집계 구간보다 수집 시간이 짧으면 true (서버 재시작 직후 등) */
		public boolean partial() {
			return System.currentTimeMillis() - collectingSince < windowMillis;
		}
	}

	/**
	 * 캔들
	 * @param volume 기초자산 수량, quoteVolume USDT 거래대금, takerBuyVolume 시장가 매수 수량
	 */
	public record Kline(long openTime, double open, double high, double low, double close, double volume,
			double quoteVolume, double takerBuyVolume) {
	}
}
