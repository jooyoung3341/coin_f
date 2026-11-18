package com.coin.f.today;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

import org.springframework.stereotype.Service;

import com.coin.f.market.MarketCapService;
import com.coin.f.market.MarketCapService.Dominance;
import com.coin.f.market.MarketCapService.MarketCap;
import com.coin.f.market.MarketDataProvider;
import com.coin.f.market.MarketModels.FundingRate;
import com.coin.f.market.MarketModels.OpenInterestChange;
import com.coin.f.market.MarketModels.TickerDetail;

/**
 * 오늘의 코인 (메뉴 02) — 최근 24시간 거래대금 · 등락 · 변동폭 기준 HOT 코인.
 *
 * 대상: 원화 시가총액 기준을 통과한 코인 선물 (스테이블코인 제외).
 * 종합 HOT 점수(0~100) = 대상 코인 안에서의 백분위 가중합
 *   거래대금 35% + 등락률 절댓값 30% + 회전율(거래대금/시가총액) 20% + 고저 변동폭 15%
 * 절대 기준이 아닌 상대 순위라 시장 전체가 조용한 날에도 "그날 가장 붐빈" 코인이 뽑힌다.
 */
@Service
public class TodayService {

	public static final int TOP = 20;
	/** 모든 표 상단에 고정하는 기준 코인 */
	static final String PINNED = "BTCUSDT";
	static final double W_VOLUME = 0.35;
	static final double W_CHANGE = 0.30;
	static final double W_TURNOVER = 0.20;
	static final double W_RANGE = 0.15;

	/** 가격이 고정이라 "핫"의 의미가 없는 스테이블코인 */
	private static final Set<String> STABLES = Set.of("USDT", "USDC", "FDUSD", "TUSD", "USDP", "DAI", "USDE",
			"PYUSD", "USD1", "RLUSD", "BUSD", "USDS", "USDD", "BFUSD", "EURI", "XUSD", "U");

	private final MarketDataProvider provider;
	private final MarketCapService marketCaps;

	public TodayService(MarketDataProvider provider, MarketCapService marketCaps) {
		this.provider = provider;
		this.marketCaps = marketCaps;
	}

	public TodayView getView() {
		Map<String, Double> funding = new HashMap<>();
		for (FundingRate f : provider.getFundingRates()) {
			funding.put(f.symbol(), f.rate8h());
		}
		Map<String, Double> oi = new HashMap<>();
		for (OpenInterestChange o : provider.getOpenInterestChanges()) {
			oi.put(o.symbol(), o.changePercent24h());
		}
		double krw = marketCaps.krwPerUsd();
		List<CoinRow> all = build(provider.getAllTickers(), marketCaps::marketCap, krw, funding, oi);
		// BTC 는 시장 기준으로 모든 표 상단에 고정 → 순위 목록에서는 제외 (점수 계산에는 포함)
		CoinRow pinned = all.stream().filter(r -> PINNED.equals(r.symbol())).findFirst().orElse(null);
		List<CoinRow> rows = all.stream().filter(r -> !PINNED.equals(r.symbol())).toList();

		List<CoinRow> hot = rows.stream().sorted(Comparator.comparingDouble(CoinRow::score).reversed()).limit(TOP)
				.toList();
		List<CoinRow> volume = rows.stream().sorted(Comparator.comparingDouble(CoinRow::volumeUsd).reversed())
				.limit(TOP).toList();
		List<CoinRow> gainers = rows.stream().filter(r -> r.change() > 0)
				.sorted(Comparator.comparingDouble(CoinRow::change).reversed()).limit(TOP).toList();
		List<CoinRow> losers = rows.stream().filter(r -> r.change() < 0)
				.sorted(Comparator.comparingDouble(CoinRow::change)).limit(TOP).toList();
		List<CoinRow> volatile_ = rows.stream().sorted(Comparator.comparingDouble(CoinRow::range).reversed())
				.limit(TOP).toList();

		// 상단 요약은 BTC 포함 전체 대상 기준
		int up = (int) all.stream().filter(r -> r.change() > 0).count();
		int down = (int) all.stream().filter(r -> r.change() < 0).count();
		double totalUsd = all.stream().mapToDouble(CoinRow::volumeUsd).sum();
		double[] changes = all.stream().mapToDouble(CoinRow::change).sorted().toArray();
		double median = changes.length == 0 ? 0
				: changes.length % 2 == 1 ? changes[changes.length / 2]
				: (changes[changes.length / 2 - 1] + changes[changes.length / 2]) / 2;

		Summary summary = new Summary(all.size(), up, down, totalUsd, krw > 0 ? totalUsd * krw : null, median,
				marketCaps.isLoaded(), marketCaps.updatedAt(), krw, marketCaps.minMarketCapKrw());
		return new TodayView(summary, List.of(
				new Board("hot", "종합 HOT", "bi-fire", "거래대금·등락·회전율·변동폭 종합 점수", hot),
				new Board("volume", "거래대금", "bi-cash-stack", "24시간 거래대금 순", volume),
				new Board("gainers", "상승률", "bi-graph-up-arrow", "24시간 상승률 순", gainers),
				new Board("losers", "하락률", "bi-graph-down-arrow", "24시간 하락률 순", losers),
				new Board("volatile", "변동폭", "bi-activity", "24시간 저가 대비 고가 폭 순", volatile_)),
				pinned, marketCaps.dominance(), provider.lastUpdated());
	}

	/**
	 * 점수·태그 계산 (테스트 가능하도록 정적 메서드)
	 * @param krwPerUsd 0 이면 원화 환산 · 회전율 미계산
	 */
	static List<CoinRow> build(List<TickerDetail> tickers, Function<String, MarketCap> caps, double krwPerUsd,
			Map<String, Double> funding8h, Map<String, Double> oi24h) {
		List<TickerDetail> list = tickers.stream()
				.filter(t -> !STABLES.contains(MarketCapService.assetOf(t.symbol())))
				.filter(t -> t.quoteVolume24h() > 0)
				.toList();
		if (list.isEmpty()) {
			return List.of();
		}

		Map<String, Double> turnover = new HashMap<>();
		for (TickerDetail t : list) {
			MarketCap m = caps.apply(t.symbol());
			if (m != null && m.marketCapKrw() > 0 && krwPerUsd > 0) {
				turnover.put(t.symbol(), t.quoteVolume24h() * krwPerUsd / m.marketCapKrw() * 100);
			}
		}

		Percentile pVol = new Percentile(list, TickerDetail::quoteVolume24h);
		Percentile pChg = new Percentile(list, t -> Math.abs(t.changePercent24h()));
		Percentile pRange = new Percentile(list, TickerDetail::rangePercent);
		double[] turns = turnover.values().stream().mapToDouble(Double::doubleValue).sorted().toArray();

		List<CoinRow> out = new ArrayList<>(list.size());
		for (TickerDetail t : list) {
			MarketCap m = caps.apply(t.symbol());
			Double turn = turnover.get(t.symbol());
			double v = pVol.of(t.quoteVolume24h());
			double c = pChg.of(Math.abs(t.changePercent24h()));
			double r = pRange.of(t.rangePercent());
			// 시가총액을 모르면 회전율 대신 거래대금 백분위로 대체
			double tv = turn == null ? v : Percentile.rank(turns, turn);
			double score = 100 * (W_VOLUME * v + W_CHANGE * c + W_TURNOVER * tv + W_RANGE * r);

			Double f = funding8h.get(t.symbol());
			Double o = oi24h.get(t.symbol());
			out.add(new CoinRow(t.symbol(), MarketCapService.assetOf(t.symbol()), m == null ? null : m.name(),
					t.price(), t.changePercent24h(), t.high24h(), t.low24h(), t.rangePercent(), t.quoteVolume24h(),
					krwPerUsd > 0 ? t.quoteVolume24h() * krwPerUsd : null, t.trades24h(),
					m == null ? null : m.marketCapKrw(), m == null ? null : m.rank(), turn, f, o, score,
					tags(t, v, tv, turn, f, o)));
		}
		return out;
	}

	private static List<String> tags(TickerDetail t, double volPct, double turnPct, Double turnover, Double f,
			Double oi) {
		List<String> tags = new ArrayList<>();
		double chg = t.changePercent24h();
		if (chg >= 10) {
			tags.add("급등");
		} else if (chg <= -10) {
			tags.add("급락");
		} else if (chg >= 5) {
			tags.add("강세");
		} else if (chg <= -5) {
			tags.add("약세");
		}
		if (volPct >= 0.9) {
			tags.add("거래대금 상위");
		}
		if (turnover != null && (turnover >= 30 || turnPct >= 0.9)) {
			tags.add("시총에 비해 거래 많음");
		}
		if (t.rangePercent() >= 15) {
			tags.add("변동성 큼");
		}
		if (oi != null && oi >= 10) {
			tags.add("OI 급증");
		}
		if (f != null && Math.abs(f) >= 0.0003) {
			tags.add(f > 0 ? "롱 펀딩 과열" : "숏 펀딩 과열");
		}
		return tags;
	}

	/** 값 → 0~1 백분위 (자기보다 작은 값의 비율) */
	static final class Percentile {
		private final double[] sorted;

		Percentile(List<TickerDetail> list, ToDoubleFunction<TickerDetail> f) {
			this.sorted = list.stream().mapToDouble(f).sorted().toArray();
		}

		double of(double x) {
			return rank(sorted, x);
		}

		static double rank(double[] sorted, double x) {
			if (sorted.length <= 1) {
				return 1;
			}
			int below = 0;
			while (below < sorted.length && sorted[below] < x) {
				below++;
			}
			return Math.min(1, below / (double) (sorted.length - 1));
		}
	}

	/**
	 * @param volumeKrw 원화 환산 거래대금 (환율 미수신 시 null)
	 * @param turnover 거래대금 / 시가총액 (%) — 하루에 시가총액의 몇 %가 거래됐는지
	 * @param funding8h 펀딩비 8h 환산 (거래대금 필터 미달 시 null)
	 * @param oiChange24h OI 24h 변화 % (추적 상위 30종목만)
	 */
	public record CoinRow(String symbol, String asset, String name, double price, double change, double high,
			double low, double range, double volumeUsd, Double volumeKrw, long trades, Double marketCapKrw,
			Integer marketCapRank, Double turnover, Double funding8h, Double oiChange24h, double score,
			List<String> tags) {
	}

	public record Board(String key, String title, String icon, String description, List<CoinRow> rows) {
	}

	/**
	 * @param count 대상 코인 수 (시가총액 기준 통과, 스테이블 제외)
	 * @param marketCapLoaded false 면 시가총액 미수신 → 필터 미적용 상태
	 */
	public record Summary(int count, int up, int down, double totalVolumeUsd, Double totalVolumeKrw,
			double medianChange, boolean marketCapLoaded, long marketCapUpdatedAt, double krwPerUsd,
			double minMarketCapKrw) {
	}

	/**
	 * @param pinned 상단 고정 BTC (시세 수집 전이면 null)
	 * @param dominance BTC·ETH·알트 도미넌스 (CoinGecko 미수신이면 null)
	 */
	public record TodayView(Summary summary, List<Board> boards, CoinRow pinned, Dominance dominance,
			long updatedAt) {
	}
}
