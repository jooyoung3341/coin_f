package com.coin.f.market;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 원화(KRW) 기준 시가총액 — CoinGecko 공개 API (키 불필요).
 *
 * 바이낸스는 시가총액을 제공하지 않으므로 CoinGecko 의 시가총액 순위를 받아
 * 선물 심볼(BTCUSDT, 1000PEPEUSDT …)의 기초자산과 티커로 매칭한다.
 *  - 30분마다 갱신 (실패 시 1분 뒤 재시도), 호출은 수집 스레드에서 동기 실행
 *  - 같은 티커를 쓰는 코인이 여럿이면 시가총액이 가장 큰 코인으로 간주
 *  - 한 번도 받지 못했으면 필터를 적용하지 않음 (화면이 비지 않도록)
 */
@Component
public class MarketCapService {

	private static final Logger log = LoggerFactory.getLogger(MarketCapService.class);
	private static final long REFRESH_MILLIS = 30 * 60_000L;
	private static final long RETRY_MILLIS = 60_000L;
	/** 1000PEPE, 1000000MOG, 1MBABYDOGE 처럼 단위를 붙인 선물 심볼 */
	private static final Pattern MULTIPLIER = Pattern.compile("^(1000000|1000|1M)(?=[A-Z])");

	@JsonIgnoreProperties(ignoreUnknown = true)
	record GeckoCoin(String id, String symbol, String name, @JsonProperty("market_cap") Double marketCap,
			@JsonProperty("current_price") Double currentPrice, @JsonProperty("market_cap_rank") Integer rank,
			@JsonProperty("market_cap_change_percentage_24h") Double marketCapChange24h,
			@JsonProperty("price_change_percentage_24h") Double priceChange24h) {
	}

	/** 기초자산(대문자 티커)별 시가총액 */
	public record MarketCap(String asset, String name, double marketCapKrw, int rank) {
	}

	/**
	 * 시가총액 점유율 (%) — CoinGecko /global 기준, 전체 암호화폐 시가총액 대비
	 * @param alt 알트코인 = 100 − BTC − ETH (스테이블코인 포함)
	 * @param stable USDT + USDC 점유율
	 * @param btcChange24h 등 24시간 전 대비 변화 (%p, 추정치). 계산 불가 시 null
	 */
	public record Dominance(double btc, double eth, double alt, double stable, Double btcChange24h,
			Double ethChange24h, Double altChange24h, double totalMarketCapKrw, Double totalChange24h, long at) {

		/** 스테이블코인을 뺀 알트코인 점유율 */
		public double altExStable() {
			return Math.max(0, alt - stable);
		}
	}

	private volatile Dominance dominance;

	/** 재시작 시 재사용할 마지막 성공 결과 (무료 API 요청 한도 보호) */
	record CacheFile(Map<String, MarketCap> byAsset, double krwPerUsd, long at, Dominance dominance) {
	}

	private final Path cacheFile;

	private record Snapshot(Map<String, MarketCap> byAsset, double krwPerUsd, long at) {
	}

	private final RestClient rest;
	private final JsonMapper json;
	private final double minMarketCapKrw;
	private final List<String> alwaysVisible;
	private volatile Snapshot snapshot;
	private volatile long lastAttempt;

	public MarketCapService(JsonMapper json,
			@Value("${app.marketcap.base-url:https://api.coingecko.com/api/v3}") String baseUrl,
			@Value("${app.marketcap.min-krw:1000000000000}") double minMarketCapKrw,
			@Value("${app.market.symbols}") List<String> alwaysVisible,
			@Value("${app.marketcap.cache-file:${user.home}/.futures-insight/marketcap-cache.json}") String cacheFile) {
		this.cacheFile = Path.of(cacheFile);
		SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
		rf.setConnectTimeout(Duration.ofSeconds(5));
		rf.setReadTimeout(Duration.ofSeconds(10));
		this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(rf)
				.defaultHeader("User-Agent", "futures-insight/1.0")
				.defaultHeader("Accept", "application/json")
				.build();
		this.json = json;
		this.minMarketCapKrw = minMarketCapKrw;
		this.alwaysVisible = alwaysVisible;
		loadCache();
	}

	/** 마지막 결과 복원 — 30분 이내면 그대로 사용(재조회 안 함), 오래됐어도 조회 성공 전까지 필터에 사용 */
	private void loadCache() {
		try {
			if (!Files.exists(cacheFile)) {
				return;
			}
			CacheFile c = json.readValue(Files.readString(cacheFile), CacheFile.class);
			if (c.byAsset() == null || c.byAsset().isEmpty()) {
				return;
			}
			snapshot = new Snapshot(Map.copyOf(c.byAsset()), c.krwPerUsd(), c.at());
			dominance = c.dominance();
			log.info("시가총액 캐시 복원: {}종목, {}분 전 데이터", c.byAsset().size(),
					(System.currentTimeMillis() - c.at()) / 60_000);
		} catch (Exception e) {
			log.warn("시가총액 캐시 읽기 실패 (무시): {}", e.toString());
		}
	}

	private void saveCache() {
		Snapshot s = snapshot;
		if (s == null) {
			return;
		}
		try {
			Files.createDirectories(cacheFile.getParent());
			Files.writeString(cacheFile, json.writeValueAsString(new CacheFile(s.byAsset(), s.krwPerUsd(), s.at(),
					dominance)));
		} catch (Exception e) {
			log.warn("시가총액 캐시 저장 실패 (무시): {}", e.toString());
		}
	}

	/** 갱신 시점이 지났으면 다시 받는다 (수집 스레드에서 호출) */
	public void refreshIfStale() {
		long now = System.currentTimeMillis();
		Snapshot s = snapshot;
		boolean due = s == null ? now - lastAttempt >= RETRY_MILLIS : now - s.at() >= REFRESH_MILLIS
				&& now - lastAttempt >= RETRY_MILLIS;
		if (!due) {
			return;
		}
		lastAttempt = now;
		try {
			String body = rest.get()
					.uri("/coins/markets?vs_currency=krw&order=market_cap_desc&per_page=250&page=1")
					.retrieve().body(String.class);
			List<GeckoCoin> coins = json.readValue(body, new TypeReference<List<GeckoCoin>>() {});
			Map<String, MarketCap> map = new HashMap<>();
			double krwPerUsd = 0;
			for (GeckoCoin c : coins) {
				if (c.symbol() == null || c.marketCap() == null) {
					continue;
				}
				String asset = c.symbol().toUpperCase();
				// 시가총액 내림차순 → 같은 티커는 먼저 나온(큰) 코인 우선
				map.putIfAbsent(asset, new MarketCap(asset, c.name(), c.marketCap(), c.rank() == null ? 0 : c.rank()));
				if ("tether".equals(c.id()) && c.currentPrice() != null) {
					krwPerUsd = c.currentPrice(); // USDT 원화 가격 ≈ 원/달러
				}
			}
			if (map.isEmpty()) {
				throw new IllegalStateException("빈 응답");
			}
			snapshot = new Snapshot(Map.copyOf(map), krwPerUsd, now);
			long eligible = map.values().stream().filter(m -> m.marketCapKrw() >= minMarketCapKrw).count();
			log.info("시가총액 갱신: {}종목 중 {}원 이상 {}종목 (USDT {}원)", map.size(), krw(minMarketCapKrw), eligible,
					Math.round(krwPerUsd));
			refreshDominance(now);
			saveCache();
		} catch (RuntimeException e) {
			log.warn("시가총액 조회 실패 ({}): {}", s == null ? "필터 미적용 상태, 1분 뒤 재시도" : "이전 값 사용", e.toString());
		}
	}

	/** 실패해도 시가총액 필터에는 영향 없음 (이전 값 유지) */
	private void refreshDominance(long now) {
		try {
			// 전체 시총 변화(/global)가 달러 기준이므로 BTC·ETH 시총 변화도 달러 기준으로 받는다
			Double btcChg = null;
			Double ethChg = null;
			String usd = rest.get().uri("/coins/markets?vs_currency=usd&ids=bitcoin,ethereum")
					.retrieve().body(String.class);
			for (GeckoCoin c : json.readValue(usd, new TypeReference<List<GeckoCoin>>() {})) {
				if ("bitcoin".equals(c.id())) {
					btcChg = c.marketCapChange24h();
				} else if ("ethereum".equals(c.id())) {
					ethChg = c.marketCapChange24h();
				}
			}

			String body = rest.get().uri("/global").retrieve().body(String.class);
			JsonNode d = json.readTree(body).path("data");
			JsonNode pct = d.path("market_cap_percentage");
			if (!pct.has("btc")) {
				throw new IllegalStateException("market_cap_percentage 없음");
			}
			double btc = pct.path("btc").asDouble();
			double eth = pct.path("eth").asDouble();
			double stable = pct.path("usdt").asDouble(0) + pct.path("usdc").asDouble(0);
			double total = d.path("total_market_cap").path("krw").asDouble(0);
			Double totalChg = d.has("market_cap_change_percentage_24h_usd")
					? d.path("market_cap_change_percentage_24h_usd").asDouble() : null;

			Double btcD = dominanceChange(btc, btcChg, totalChg);
			Double ethD = dominanceChange(eth, ethChg, totalChg);
			dominance = new Dominance(btc, eth, 100 - btc - eth, stable, btcD, ethD,
					btcD == null || ethD == null ? null : -(btcD + ethD), total, totalChg, now);
		} catch (RuntimeException e) {
			log.warn("도미넌스 조회 실패 ({}): {}", dominance == null ? "표시 안 함" : "이전 값 사용", e.toString());
		}
	}

	/**
	 * 24시간 전 대비 도미넌스 변화 (%p) 추정.
	 * 24h 전 점유율 = 현재 점유율 × (1 + 전체 시총 변화) / (1 + 코인 시총 변화) — 둘 다 달러 기준
	 */
	public static Double dominanceChange(double nowPct, Double coinChgUsd, Double totalChgUsd) {
		if (coinChgUsd == null || totalChgUsd == null || coinChgUsd <= -100) {
			return null;
		}
		double prev = nowPct * (1 + totalChgUsd / 100) / (1 + coinChgUsd / 100);
		return nowPct - prev;
	}

	/** 미수신이면 null */
	public Dominance dominance() {
		return dominance;
	}

	/** 선물 심볼 → 기초자산 티커 (1000PEPEUSDT → PEPE) */
	public static String assetOf(String futuresSymbol) {
		String base = futuresSymbol.endsWith("USDT") ? futuresSymbol.substring(0, futuresSymbol.length() - 4)
				: futuresSymbol;
		return MULTIPLIER.matcher(base).replaceFirst("");
	}

	public MarketCap marketCap(String futuresSymbol) {
		Snapshot s = snapshot;
		return s == null ? null : s.byAsset().get(assetOf(futuresSymbol));
	}

	/** 시가총액 기준을 통과하는지. 기본 종목은 항상 통과, 시총 미수신 상태면 필터 미적용 */
	public boolean meetsThreshold(String futuresSymbol) {
		if (alwaysVisible.contains(futuresSymbol) || snapshot == null) {
			return true;
		}
		MarketCap m = marketCap(futuresSymbol);
		return m != null && m.marketCapKrw() >= minMarketCapKrw;
	}

	public boolean isLoaded() {
		return snapshot != null;
	}

	public long updatedAt() {
		Snapshot s = snapshot;
		return s == null ? 0 : s.at();
	}

	/** 원/달러 (USDT 원화 가격). 미수신이면 0 */
	public double krwPerUsd() {
		Snapshot s = snapshot;
		return s == null ? 0 : s.krwPerUsd();
	}

	public double minMarketCapKrw() {
		return minMarketCapKrw;
	}

	private static String krw(double v) {
		return v >= 1e12 ? "%.0f조".formatted(v / 1e12) : "%.0f억".formatted(v / 1e8);
	}
}
