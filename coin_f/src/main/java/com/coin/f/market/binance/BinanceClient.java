package com.coin.f.market.binance;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.coin.f.market.MarketModels.Kline;
import com.coin.f.market.binance.BinanceDtos.ExchangeInfo;
import com.coin.f.market.binance.BinanceDtos.FundingInfo;
import com.coin.f.market.binance.BinanceDtos.FundingRateHist;
import com.coin.f.market.binance.BinanceDtos.LongShortAccountRatio;
import com.coin.f.market.binance.BinanceDtos.OpenInterestHist;
import com.coin.f.market.binance.BinanceDtos.PremiumIndex;
import com.coin.f.market.binance.BinanceDtos.Ticker24h;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Binance USDⓈ-M Futures 공개 REST API (API 키 불필요)
 * https://developers.binance.com/docs/derivatives/usds-margined-futures
 */
@Component
@ConditionalOnProperty(name = "app.market.source", havingValue = "binance", matchIfMissing = true)
public class BinanceClient {

	private static final Logger log = LoggerFactory.getLogger(BinanceClient.class);

	private final RestClient rest;
	private final JsonMapper json;

	public BinanceClient(@Value("${app.binance.base-url}") String baseUrl, JsonMapper json) {
		SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
		rf.setConnectTimeout(Duration.ofSeconds(5));
		rf.setReadTimeout(Duration.ofSeconds(10));
		this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(rf).build();
		this.json = json;
	}

	/** weight 1 */
	public ExchangeInfo exchangeInfo() {
		return get("/fapi/v1/exchangeInfo", new TypeReference<>() {});
	}

	/** 전 종목 24시간 시세 — weight 40 */
	public List<Ticker24h> tickers24h() {
		return get("/fapi/v1/ticker/24hr", new TypeReference<>() {});
	}

	/** 전 종목 마크가격·펀딩비 — weight 10 */
	public List<PremiumIndex> premiumIndexAll() {
		return get("/fapi/v1/premiumIndex", new TypeReference<>() {});
	}

	/** 종목별 펀딩 정산 주기 — 별도 한도 500회/5분 */
	public List<FundingInfo> fundingInfo() {
		return get("/fapi/v1/fundingInfo", new TypeReference<>() {});
	}

	/** 확정 펀딩비 이력 — 별도 한도 500회/5분 */
	public List<FundingRateHist> fundingRateHistory(String symbol, int limit) {
		return get("/fapi/v1/fundingRate?symbol={s}&limit={l}", new TypeReference<>() {}, symbol, limit);
	}

	/** OI 이력 (최근 30일까지만 제공) */
	public List<OpenInterestHist> openInterestHist(String symbol, String period, int limit) {
		return get("/futures/data/openInterestHist?symbol={s}&period={p}&limit={l}", new TypeReference<>() {},
				symbol, period, limit);
	}

	/** 계정 기준 롱숏 비율 */
	public List<LongShortAccountRatio> globalLongShortAccountRatio(String symbol, String period, int limit) {
		return get("/futures/data/globalLongShortAccountRatio?symbol={s}&period={p}&limit={l}",
				new TypeReference<>() {}, symbol, period, limit);
	}

	/** 상위 20% 트레이더 포지션(보유 규모) 기준 롱숏 비율 — 응답 형식은 계정 기준과 동일 */
	public List<LongShortAccountRatio> topLongShortPositionRatio(String symbol, String period, int limit) {
		return get("/futures/data/topLongShortPositionRatio?symbol={s}&period={p}&limit={l}",
				new TypeReference<>() {}, symbol, period, limit);
	}

	/** 캔들 — weight 1~10 (limit 에 비례). 응답이 배열의 배열이라 직접 변환 */
	public List<Kline> klines(String symbol, String interval, int limit) {
		List<List<Object>> rows = get("/fapi/v1/klines?symbol={s}&interval={i}&limit={l}",
				new TypeReference<>() {}, symbol, interval, limit);
		List<Kline> out = new ArrayList<>(rows.size());
		// [0]openTime [1]open [2]high [3]low [4]close [5]volume [6]closeTime [7]quoteVolume [8]trades [9]takerBuyBase
		for (List<Object> r : rows) {
			out.add(new Kline(num(r.get(0)).longValue(), num(r.get(1)).doubleValue(), num(r.get(2)).doubleValue(),
					num(r.get(3)).doubleValue(), num(r.get(4)).doubleValue(), num(r.get(5)).doubleValue(),
					num(r.get(7)).doubleValue(), num(r.get(9)).doubleValue()));
		}
		return out;
	}

	/** 특정 시각(startTime)부터의 캔들 — 시그널 발생 후 가격 조회용 */
	public List<Kline> klinesFrom(String symbol, String interval, long startTime, int limit) {
		List<List<Object>> rows = get("/fapi/v1/klines?symbol={s}&interval={i}&startTime={t}&limit={l}",
				new TypeReference<>() {}, symbol, interval, startTime, limit);
		List<Kline> out = new ArrayList<>(rows.size());
		for (List<Object> r : rows) {
			out.add(new Kline(num(r.get(0)).longValue(), num(r.get(1)).doubleValue(), num(r.get(2)).doubleValue(),
					num(r.get(3)).doubleValue(), num(r.get(4)).doubleValue(), num(r.get(5)).doubleValue(),
					num(r.get(7)).doubleValue(), num(r.get(9)).doubleValue()));
		}
		return out;
	}

	private <T> T get(String uri, TypeReference<T> type, Object... vars) {
		long t0 = System.currentTimeMillis();
		String body = rest.get().uri(uri, vars).retrieve().body(String.class);
		if (log.isDebugEnabled()) {
			log.debug("GET {} {} ({}ms)", uri, List.of(vars), System.currentTimeMillis() - t0);
		}
		return json.readValue(body, type);
	}

	private static Number num(Object o) {
		if (o instanceof Number n) {
			return n;
		}
		return Double.valueOf(String.valueOf(o));
	}
}
