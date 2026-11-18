package com.coin.f.market;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.coin.f.market.MarketModels.FundingRate;
import com.coin.f.market.MarketModels.Kline;
import com.coin.f.market.MarketModels.LiquidationStats;
import com.coin.f.market.MarketModels.LongShortRatio;
import com.coin.f.market.MarketModels.MarketSummary;
import com.coin.f.market.MarketModels.OpenInterestChange;
import com.coin.f.market.MarketModels.Sentiment;
import com.coin.f.market.MarketModels.Ticker;

/**
 * 화면 개발용 샘플 데이터. (실제 시세 아님)
 * app.market.source=sample 일 때만 활성화 — 인터넷 없이 화면 작업할 때 사용.
 */
@Component
@ConditionalOnProperty(name = "app.market.source", havingValue = "sample")
public class SampleMarketDataProvider implements MarketDataProvider {

	private static final List<String> ALL = List.of("BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT", "DOGEUSDT",
			"BNBUSDT", "ADAUSDT", "AVAXUSDT", "LINKUSDT", "SUIUSDT");

	private static final Map<String, Double> BASE_PRICE = Map.of(
			"BTCUSDT", 98_500.0, "ETHUSDT", 3_450.0, "SOLUSDT", 182.0,
			"XRPUSDT", 2.35, "DOGEUSDT", 0.31, "BNBUSDT", 690.0,
			"ADAUSDT", 0.92, "AVAXUSDT", 38.5, "LINKUSDT", 21.4, "SUIUSDT", 4.1);

	@Override
	public String sourceName() {
		return "샘플 데이터";
	}

	@Override
	public boolean isSample() {
		return true;
	}

	@Override
	public long lastUpdated() {
		return System.currentTimeMillis();
	}

	@Override
	public LiquidationStats getLiquidationStats(long windowMillis) {
		return new LiquidationStats(48_200_000, 21_700_000, 1_284, windowMillis, 0);
	}

	@Override
	public List<Ticker> getTickers(List<String> symbols) {
		List<Ticker> list = new ArrayList<>();
		for (String s : symbols) {
			List<Kline> k = getKlines(s, "1h", 25);
			double last = k.get(k.size() - 1).close();
			double first = k.get(0).open();
			double vol = k.stream().mapToDouble(x -> x.volume() * x.close()).sum();
			list.add(new Ticker(s, last, (last - first) / first * 100, vol));
		}
		return list;
	}

	@Override
	public MarketSummary getMarketSummary() {
		double[] rates = getFundingRates().stream().mapToDouble(FundingRate::rate8h).sorted().toArray();
		double avgFunding = rates.length == 0 ? 0 : rates[rates.length / 2];
		return new MarketSummary(142_300_000_000.0, 98_700_000_000.0, 3.8, avgFunding, Sentiment.of(64), ALL.size());
	}

	@Override
	public List<FundingRate> getFundingRates() {
		long next = nextFundingTime();
		double[] rates = { 0.000100, 0.000135, 0.000412, 0.000058, 0.000520, 0.000071, -0.000094, -0.000215,
				0.000033, -0.000377 };
		List<FundingRate> list = new ArrayList<>();
		for (int i = 0; i < ALL.size(); i++) {
			String s = ALL.get(i);
			int interval = i < 3 ? 8 : 4; // 실제처럼 알트는 4h 주기
			list.add(new FundingRate(s, rates[i], next, interval, 1e9 / (i + 1)));
		}
		return list;
	}

	@Override
	public List<OpenInterestChange> getOpenInterestChanges() {
		return List.of(
				new OpenInterestChange("BTCUSDT", 67_200_000_000.0, 2.4),
				new OpenInterestChange("ETHUSDT", 18_900_000_000.0, 5.1),
				new OpenInterestChange("SOLUSDT", 4_300_000_000.0, 14.7),
				new OpenInterestChange("XRPUSDT", 2_100_000_000.0, -6.3),
				new OpenInterestChange("DOGEUSDT", 1_700_000_000.0, 11.2),
				new OpenInterestChange("BNBUSDT", 900_000_000.0, 0.8),
				new OpenInterestChange("ADAUSDT", 600_000_000.0, -9.8),
				new OpenInterestChange("AVAXUSDT", 450_000_000.0, -4.1),
				new OpenInterestChange("LINKUSDT", 520_000_000.0, 3.3),
				new OpenInterestChange("SUIUSDT", 780_000_000.0, -12.5));
	}

	@Override
	public List<LongShortRatio> getLongShortRatios() {
		List<LongShortRatio> list = new ArrayList<>();
		for (String s : ALL) {
			double longPct = switch (s) {
				case "BTCUSDT" -> 52.4;
				case "ETHUSDT" -> 58.1;
				case "SOLUSDT" -> 71.3;
				default -> 50 + new Random(s.hashCode()).nextDouble() * 20 - 10;
			};
			list.add(new LongShortRatio(s, longPct, 100 - longPct));
		}
		return list;
	}

	/** 심볼별 고정 시드 랜덤워크 캔들 (새로고침해도 모양이 동일) */
	@Override
	public List<Kline> getKlines(String symbol, String interval, int limit) {
		long step = intervalMillis(interval);
		long end = System.currentTimeMillis() / step * step;
		Random rnd = new Random(symbol.hashCode() * 31L + step);
		double target = BASE_PRICE.getOrDefault(symbol, 100.0);

		// 끝값이 기준가 근처에 오도록 뒤에서부터 역산
		double[] closes = new double[limit];
		closes[limit - 1] = target;
		double vola = 0.006 * Math.sqrt(step / 3_600_000.0);
		for (int i = limit - 2; i >= 0; i--) {
			closes[i] = closes[i + 1] / (1 + rnd.nextGaussian() * vola + 0.0004);
		}

		List<Kline> list = new ArrayList<>(limit);
		double prevClose = closes[0] * (1 - rnd.nextGaussian() * vola);
		for (int i = 0; i < limit; i++) {
			double open = prevClose;
			double close = closes[i];
			double wick = Math.abs(rnd.nextGaussian()) * vola * open * 0.6;
			double high = Math.max(open, close) + wick;
			double low = Math.min(open, close) - Math.abs(rnd.nextGaussian()) * vola * open * 0.6;
			double volume = (800 + rnd.nextDouble() * 1_600) * (98_500 / target) * (step / 3_600_000.0);
			list.add(new Kline(end - (long) (limit - 1 - i) * step, open, high, low, close, volume,
					volume * close, volume * (0.4 + rnd.nextDouble() * 0.2)));
			prevClose = close;
		}
		return list;
	}

	private static long intervalMillis(String interval) {
		return switch (interval) {
			case "1m" -> 60_000L;
			case "5m" -> 300_000L;
			case "15m" -> 900_000L;
			case "4h" -> 14_400_000L;
			case "1d" -> 86_400_000L;
			default -> 3_600_000L;
		};
	}

	/** 바이낸스 펀딩 주기: 00/08/16시 UTC */
	private static long nextFundingTime() {
		long eightHours = 8 * 3_600_000L;
		return (System.currentTimeMillis() / eightHours + 1) * eightHours;
	}
}
