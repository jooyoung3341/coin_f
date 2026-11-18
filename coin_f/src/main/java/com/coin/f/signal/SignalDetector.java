package com.coin.f.signal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.coin.f.market.MarketModels.FundingRate;
import com.coin.f.market.MarketModels.LiquidationStats;
import com.coin.f.market.MarketModels.LongShortRatio;
import com.coin.f.market.MarketModels.OpenInterestChange;
import com.coin.f.signal.MarketSignal.Level;
import com.coin.f.signal.MarketSignal.SignalType;

/**
 * 규칙 기반 시그널 탐지 (6단계 시그널 엔진).
 * 방향 추천이 아니라 "어느 쪽으로 포지션이 몰렸는지 + 그 근거"를 낸다.
 */
@Component
public class SignalDetector {

	/** 펀딩비 과열: ±0.03% (8시간 환산) */
	public static final double FUNDING_HOT = 0.0003;
	/** OI 급증: 24h +10% */
	public static final double OI_SURGE_PCT = 10.0;
	/**
	 * 롱 계정 비율 쏠림. 개인 계정은 구조적으로 롱 편향(실측 중앙값 약 73%)이라 50% 대칭 기준을 쓰지 않는다.
	 */
	public static final double LONG_CROWDED_PCT = 80.0;
	public static final double SHORT_CROWDED_PCT = 45.0;
	/** 대량 청산: 최근 15분 청산액이 max(최소액, 24h 거래대금의 0.01%) 이상 */
	public static final long LIQ_WINDOW_MILLIS = 15 * 60_000L;
	public static final double LIQ_MIN_USD = 200_000;
	public static final double LIQ_VOLUME_RATIO = 0.0001;
	/** 대량 청산 HIGH: 기준의 3배 이상 */
	public static final double LIQ_HIGH_MULTIPLE = 3.0;

	/**
	 * @param liq15m 종목별 최근 15분 청산 집계 (없는 종목은 생략 가능)
	 */
	public List<MarketSignal> detect(List<FundingRate> fundings, List<OpenInterestChange> ois,
			List<LongShortRatio> ratios, Map<String, LiquidationStats> liq15m) {

		Map<String, FundingRate> fundingBy = fundings.stream()
				.collect(Collectors.toMap(FundingRate::symbol, Function.identity(), (a, b) -> a));
		Map<String, OpenInterestChange> oiBy = ois.stream()
				.collect(Collectors.toMap(OpenInterestChange::symbol, Function.identity(), (a, b) -> a));
		Map<String, LongShortRatio> lsBy = ratios.stream()
				.collect(Collectors.toMap(LongShortRatio::symbol, Function.identity(), (a, b) -> a));

		List<MarketSignal> out = new ArrayList<>();

		for (FundingRate f : fundingBy.values()) {
			String symbol = f.symbol();
			OpenInterestChange oi = oiBy.get(symbol);
			LongShortRatio ls = lsBy.get(symbol);

			// 정산 주기가 다른 종목(4h/1h)도 같은 기준으로 비교하도록 8시간 환산값으로 판정
			boolean fundingHot = f.rate8h() >= FUNDING_HOT;
			boolean fundingCold = f.rate8h() <= -FUNDING_HOT;
			boolean oiSurge = oi != null && oi.changePercent24h() >= OI_SURGE_PCT;
			boolean longCrowded = ls != null && ls.longPercent() >= LONG_CROWDED_PCT;
			boolean shortCrowded = ls != null && ls.longPercent() <= SHORT_CROWDED_PCT;

			String fundingTxt = f.intervalHours() == 8
					? "펀딩비 %+.4f%%".formatted(f.ratePercent())
					: "펀딩비 %+.4f%% (%dh 주기, 8h 환산 %+.4f%%)".formatted(f.ratePercent(), f.intervalHours(),
							f.rate8h() * 100);
			String oiTxt = oi == null ? null : "OI 24h %+.1f%%".formatted(oi.changePercent24h());
			String lsTxt = ls == null ? null : "롱 %.1f%% / 숏 %.1f%%".formatted(ls.longPercent(), ls.shortPercent());

			// ---- 대량 청산 (다른 조건과 독립)
			LiquidationStats lq = liq15m.get(symbol);
			if (lq != null) {
				double threshold = Math.max(LIQ_MIN_USD, f.quoteVolume24h() * LIQ_VOLUME_RATIO);
				if (lq.totalUsd() >= threshold) {
					boolean longSide = lq.longUsd() >= lq.shortUsd();
					double share = lq.totalUsd() == 0 ? 0 : Math.max(lq.longUsd(), lq.shortUsd()) / lq.totalUsd() * 100;
					out.add(new MarketSignal(SignalType.MASS_LIQUIDATION, symbol,
							lq.totalUsd() >= threshold * LIQ_HIGH_MULTIPLE ? Level.HIGH : Level.MEDIUM,
							longSide ? "LONG" : "SHORT",
							longSide ? "15분 내 롱 포지션 강제 청산 급증 — 급락 구간, 연쇄 청산 주의"
									: "15분 내 숏 포지션 강제 청산 급증 — 급등 구간(숏 스퀴즈), 연쇄 청산 주의",
							evidence("15분 청산 $%,.0f (기준 $%,.0f)".formatted(lq.totalUsd(), threshold),
									"%s 청산 비중 %.0f%%".formatted(longSide ? "롱" : "숏", share),
									"%d건".formatted(lq.count()))));
				}
			}

			// ---- 복합: 펀딩 과열 + OI 급증 + 한쪽 쏠림 → 스퀴즈 위험
			if (fundingHot && oiSurge && longCrowded) {
				out.add(new MarketSignal(SignalType.COMPOSITE, symbol, Level.HIGH, "LONG",
						"펀딩비·OI·롱 비율이 동시에 과열 — 신규 롱 진입이 몰리며 롱 청산 연쇄(롱 스퀴즈) 위험",
						evidence(fundingTxt, oiTxt, lsTxt)));
				continue;
			}
			if (fundingCold && oiSurge && shortCrowded) {
				out.add(new MarketSignal(SignalType.COMPOSITE, symbol, Level.HIGH, "SHORT",
						"음(-)의 펀딩비·OI 급증·숏 쏠림이 동시에 발생 — 신규 숏이 몰리며 숏 스퀴즈(급등) 위험",
						evidence(fundingTxt, oiTxt, lsTxt)));
				continue;
			}

			if (fundingHot) {
				out.add(new MarketSignal(SignalType.FUNDING_OVERHEAT, symbol, Level.MEDIUM, "LONG",
						"롱 포지션이 숏에게 높은 비용을 지불하는 중 — 롱 쏠림",
						evidence(fundingTxt, oiTxt)));
			} else if (fundingCold) {
				out.add(new MarketSignal(SignalType.FUNDING_OVERHEAT, symbol, Level.MEDIUM, "SHORT",
						"음(-)의 펀딩비 확대 — 숏 쏠림, 숏 스퀴즈 가능성",
						evidence(fundingTxt, oiTxt)));
			}
			if (oiSurge) {
				out.add(new MarketSignal(SignalType.OI_SURGE, symbol, Level.MEDIUM, "NEUTRAL",
						"24시간 내 신규 포지션 유입 급증 — 변동성 확대 가능",
						evidence(oiTxt, fundingTxt)));
			}
			// 계정 비율 단독 쏠림은 약한 신호 → LOW
			if (longCrowded) {
				out.add(new MarketSignal(SignalType.LONG_OVERHEAT, symbol, Level.LOW, "LONG",
						"롱 계정 비율 %.0f%% 이상 — 평상시(약 70%%)보다 롱 쏠림이 강함".formatted(LONG_CROWDED_PCT),
						evidence(lsTxt, fundingTxt)));
			} else if (shortCrowded) {
				out.add(new MarketSignal(SignalType.SHORT_OVERHEAT, symbol, Level.LOW, "SHORT",
						"롱 계정 비율 %.0f%% 이하 — 평상시보다 숏 쏠림이 강함".formatted(SHORT_CROWDED_PCT),
						evidence(lsTxt, fundingTxt)));
			}
		}

		out.sort(Comparator.comparing(MarketSignal::level).thenComparing(MarketSignal::symbol));
		return out;
	}

	/** 화면 표시용 판정 기준 (상수에서 생성 → 코드와 항상 일치) */
	public static List<Criterion> criteria() {
		return List.of(
				new Criterion(SignalType.COMPOSITE, "높음",
						"펀딩비 ±%.2f%%(8h) + OI 24h +%.0f%% + 롱 비율 %.0f%% 이상(또는 %.0f%% 이하)이 동시에 충족"
								.formatted(FUNDING_HOT * 100, OI_SURGE_PCT, LONG_CROWDED_PCT, SHORT_CROWDED_PCT)),
				new Criterion(SignalType.MASS_LIQUIDATION, "보통 / 높음",
						"최근 15분 청산액 ≥ max($%,.0f, 24h 거래대금의 %.2f%%) · 기준의 %.0f배 이상이면 높음"
								.formatted(LIQ_MIN_USD, LIQ_VOLUME_RATIO * 100, LIQ_HIGH_MULTIPLE)),
				new Criterion(SignalType.FUNDING_OVERHEAT, "보통",
						"펀딩비 8시간 환산 ±%.2f%% 이상 (4h·1h 주기 종목도 환산 후 비교)".formatted(FUNDING_HOT * 100)),
				new Criterion(SignalType.OI_SURGE, "보통", "미결제약정 24시간 +%.0f%% 이상".formatted(OI_SURGE_PCT)),
				new Criterion(SignalType.LONG_OVERHEAT, "낮음",
						"롱 계정 비율 %.0f%% 이상 (개인 계정 평상시 약 70%%)".formatted(LONG_CROWDED_PCT)),
				new Criterion(SignalType.SHORT_OVERHEAT, "낮음",
						"롱 계정 비율 %.0f%% 이하".formatted(SHORT_CROWDED_PCT)));
	}

	public record Criterion(SignalType type, String level, String rule) {
	}

	private static List<String> evidence(String... items) {
		List<String> list = new ArrayList<>();
		for (String s : items) {
			if (s != null) {
				list.add(s);
			}
		}
		return list;
	}
}
