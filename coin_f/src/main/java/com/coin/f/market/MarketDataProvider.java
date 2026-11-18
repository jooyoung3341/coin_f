package com.coin.f.market;

import java.util.List;

import com.coin.f.market.MarketModels.FundingRate;
import com.coin.f.market.MarketModels.HistorySeries;
import com.coin.f.market.MarketModels.Kline;
import com.coin.f.market.MarketModels.LiquidationStats;
import com.coin.f.market.MarketModels.LongShortRatio;
import com.coin.f.market.MarketModels.MarketSummary;
import com.coin.f.market.MarketModels.OpenInterestChange;
import com.coin.f.market.MarketModels.Ticker;
import com.coin.f.market.MarketModels.TickerDetail;

/**
 * 시장 데이터 공급자.
 * app.market.source=binance (기본) → BinanceMarketDataProvider
 * app.market.source=sample          → SampleMarketDataProvider (오프라인 화면 개발용)
 */
public interface MarketDataProvider {

	/** 데이터 출처 표시명 */
	String sourceName();

	boolean isSample();

	/** 마지막 수집 시각(epoch ms). 아직 수집 전이면 0 */
	long lastUpdated();

	List<Ticker> getTickers(List<String> symbols);

	MarketSummary getMarketSummary();

	/** 펀딩비 (거래대금 기준 필터링된 종목, 정렬은 호출측에서) */
	List<FundingRate> getFundingRates();

	/** OI 변화 (추적 대상 종목, 정렬은 호출측에서) */
	List<OpenInterestChange> getOpenInterestChanges();

	/** 롱숏 비율 (추적 대상 종목 전체) */
	List<LongShortRatio> getLongShortRatios();

	/**
	 * @param interval 1m, 5m, 15m, 1h, 4h, 1d
	 */
	List<Kline> getKlines(String symbol, String interval, int limit);

	/** 최근 windowMillis 동안의 청산 집계 */
	LiquidationStats getLiquidationStats(long windowMillis);

	/** 화면 노출 대상 종목인지 (원화 시가총액 기준 등). DB 이력처럼 과거에 저장된 종목을 거를 때 사용 */
	default boolean isVisible(String symbol) {
		return true;
	}

	/** 노출 대상 전 종목의 24시간 상세 시세 (오늘의 코인) */
	default List<TickerDetail> getAllTickers() {
		return List.of();
	}

	// ---- 종목 1개 조회 (코인 상세). 추적 대상(상위 30)이 아니면 구현체가 필요 시 직접 조회

	default OpenInterestChange openInterestOf(String symbol) {
		return getOpenInterestChanges().stream().filter(o -> o.symbol().equals(symbol)).findFirst().orElse(null);
	}

	default LongShortRatio longShortOf(String symbol) {
		return getLongShortRatios().stream().filter(r -> r.symbol().equals(symbol)).findFirst().orElse(null);
	}

	/** DB 에 이력이 없는 종목의 1시간 단위 추이 (거래소 직접 조회). 지원하지 않으면 null */
	default HistorySeries liveHistory(String symbol, int days) {
		return null;
	}
}
