package com.coin.f.ai;

import static com.coin.f.ai.RuleBasedReportGenerator.BASE_FUNDING;
import static com.coin.f.ai.RuleBasedReportGenerator.FUNDING_SAME;
import static com.coin.f.ai.RuleBasedReportGenerator.HOT_FUNDING;
import static com.coin.f.ai.RuleBasedReportGenerator.RETAIL_LONG_CROWDED;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.coin.f.ai.AnalysisModels.Check;
import com.coin.f.ai.AnalysisModels.IndicatorRow;
import com.coin.f.ai.AnalysisModels.IndicatorView;
import com.coin.f.ai.AnalysisModels.SymbolInput;
import com.coin.f.ai.AnalysisModels.SymbolTrends;
import com.coin.f.oi.OpenInterestService;
import com.coin.f.web.Formatters;

/**
 * 코인 상세 "현재 지표 해석" — 지표별 현재 상태 + 하락·상승 위험 확인 체크.
 * AI 분석 리포트와 같은 입력·기준값을 쓰되, 리포트 엔진(rule/claude)과 무관하게 항상 규칙으로 계산한다.
 * 매매 추천이 아니라 "지금 어떤 조건이 충족돼 있는지"를 보여준다.
 */
@Component
public class IndicatorInterpreter {

	/** 가격이 고점·저점 "부근"으로 보는 거리 */
	static final double NEAR = 0.003;
	/** 4시간 매수 비중이 24시간 대비 이만큼(%p) 이상 바뀌면 강해짐/약해짐 */
	static final double TAKER_SHIFT = 3.0;
	/** 4시간 OI·가격 변화 기준 (%) */
	static final double OI_MOVE = 1.0;
	static final double PRICE_MOVE = 0.5;

	private final Formatters fmt;

	public IndicatorInterpreter(Formatters fmt) {
		this.fmt = fmt;
	}

	public IndicatorView interpret(SymbolInput in) {
		SymbolTrends t = in.trends();
		Double retail = in.longPct();
		Double top = t == null ? null : t.topLongPct();
		Double long3d = t == null || t.longPct3dAgo() == null || retail == null ? null : retail - t.longPct3dAgo();
		Double fundingDiff = t == null || t.fundingAvg3d() == null || in.funding() == null ? null
				: in.funding() - t.fundingAvg3d();
		Double change4h = t == null ? null : t.change4h();
		Double oi4h = in.oiChange4h();
		double taker24 = in.takerBuyShare24h() * 100;
		Double taker4 = t == null ? null : t.takerBuyShare4h() * 100;
		Double takerShift = taker4 == null ? null : taker4 - taker24;

		boolean retailVsWhales = retail != null && top != null && retail >= RETAIL_LONG_CROWDED && top < 50;
		boolean whalesVsRetail = retail != null && top != null && top >= 60 && retail <= 65;

		List<IndicatorRow> rows = new ArrayList<>();
		rows.add(longShortRow(retail, top, retailVsWhales, whalesVsRetail));
		rows.add(longTrendRow(retail, t, long3d));
		rows.add(fundingRow(in, t, fundingDiff));
		rows.add(oiPriceRow(oi4h, change4h));
		rows.add(takerRow(taker24, taker4, takerShift));
		rows.add(priceRow(in, t));

		double price = in.price();
		List<Check> down = List.of(
				new Check("개인 롱 · 큰손 숏 엇갈림", known(retail, top) ? retailVsWhales : null,
						known(retail, top) ? "개인 %.1f%% · 탑 %.1f%%".formatted(retail, top) : "탑트레이더 수집 중"),
				new Check("24h 저점 부근에서 하락", change4h == null ? null
						: price <= in.low24h() * (1 + NEAR) && change4h < 0,
						"저점 %s · 4h %s".formatted(fmt.price(in.low24h()), fmt.pctOrDash(change4h))),
				new Check("하락 중 OI 증가 (신규 숏)", known(change4h, oi4h) ? change4h <= -PRICE_MOVE && oi4h >= OI_MOVE : null,
						"가격 4h %s · OI 4h %s".formatted(fmt.pctOrDash(change4h), fmt.pctOrDash(oi4h))),
				new Check("펀딩 양수 · 상승", in.funding8h() == null ? null
						: in.funding8h() >= BASE_FUNDING * 1.5
								|| (in.funding() > 0 && fundingDiff != null && fundingDiff >= FUNDING_SAME),
						fundingDetail(in, t)),
				new Check("4h 매수 비중 하락", takerShift == null ? null : takerShift <= -TAKER_SHIFT,
						takerDetail(taker24, taker4)));
		List<Check> up = List.of(
				new Check("개인 숏 쏠림 · 큰손 롱", retail == null ? null
						: retail <= 60 || whalesVsRetail,
						top == null ? "개인 %s".formatted(pctOrDash(retail))
								: "개인 %s · 탑 %.1f%%".formatted(pctOrDash(retail), top)),
				new Check("24h 고점 부근에서 상승", change4h == null ? null
						: price >= in.high24h() * (1 - NEAR) && change4h > 0,
						"고점 %s · 4h %s".formatted(fmt.price(in.high24h()), fmt.pctOrDash(change4h))),
				new Check("상승 중 OI 감소 (숏 커버)", known(change4h, oi4h) ? change4h >= PRICE_MOVE && oi4h <= -OI_MOVE : null,
						"가격 4h %s · OI 4h %s".formatted(fmt.pctOrDash(change4h), fmt.pctOrDash(oi4h))),
				new Check("펀딩 음수 · 하락", in.funding8h() == null ? null
						: in.funding8h() <= 0 || (fundingDiff != null && fundingDiff <= -FUNDING_SAME),
						fundingDetail(in, t)),
				new Check("4h 매수 비중 상승", takerShift == null ? null : takerShift >= TAKER_SHIFT,
						takerDetail(taker24, taker4)));

		return new IndicatorView(in.symbol(), System.currentTimeMillis(), rows, down, up);
	}

	// ------------------------------------------------------------------ 지표별 행

	private IndicatorRow longShortRow(Double retail, Double top, boolean retailVsWhales, boolean whalesVsRetail) {
		String name = "롱숏 (개인 vs 큰손)";
		if (retail == null) {
			return new IndicatorRow(name, "-", "수집 중", "neutral", "롱숏 비율을 수집하고 있습니다.");
		}
		if (top == null) {
			return new IndicatorRow(name, "개인 %.1f%%".formatted(retail), "탑 수집 중", "neutral",
					"탑트레이더 이력이 쌓이면 개인과 큰 포지션을 비교합니다.");
		}
		String value = "개인 %.1f%% · 탑 %.1f%%".formatted(retail, top);
		if (retailVsWhales) {
			return new IndicatorRow(name, value, "엇갈림", "down", "개인 롱 과밀 — 하락 시 개인 롱 청산이 몰릴 수 있습니다.");
		}
		if (whalesVsRetail) {
			return new IndicatorRow(name, value, "엇갈림", "up", "큰 포지션은 롱, 개인은 숏 쪽 — 상승 시 숏 청산 가능성.");
		}
		if (top >= 55 && retail >= RuleBasedReportGenerator.LONG_BASELINE) {
			return new IndicatorRow(name, value, "모두 롱", "warn", "개인·큰손 모두 롱 쪽으로 기울어 있습니다.");
		}
		if (top <= 45 && retail <= 60) {
			return new IndicatorRow(name, value, "모두 숏", "warn", "개인·큰손 모두 숏 쪽으로 기울어 있습니다.");
		}
		return new IndicatorRow(name, value, "중립", "neutral", "개인과 큰 포지션 사이에 뚜렷한 엇갈림이 없습니다.");
	}

	private IndicatorRow longTrendRow(Double retail, SymbolTrends t, Double long3d) {
		String name = "롱 비율 추이 (3일)";
		if (long3d == null) {
			String v = t != null && t.longPct24hAgo() != null && retail != null
					? "24h %+.1f%%p".formatted(retail - t.longPct24hAgo())
					: "-";
			return new IndicatorRow(name, v, "이력 부족", "neutral", "3일 비교에는 롱숏 이력이 더 필요합니다.");
		}
		String value = "%.1f%% → %.1f%% (%+.1f%%p)".formatted(t.longPct3dAgo(), retail, long3d);
		if (long3d >= 5) {
			return new IndicatorRow(name, value, "롱 증가", "down", "개인 롱 쏠림이 커지는 중입니다.");
		}
		if (long3d <= -5) {
			return new IndicatorRow(name, value, "롱 감소", "up", "개인 롱 쏠림이 풀리는 중입니다.");
		}
		return new IndicatorRow(name, value, "변화 적음", "neutral", "뚜렷한 방향 변화가 없습니다.");
	}

	private IndicatorRow fundingRow(SymbolInput in, SymbolTrends t, Double diff) {
		String name = "펀딩비";
		if (in.funding8h() == null) {
			return new IndicatorRow(name, "-", "수집 중", "neutral", "펀딩비를 수집하고 있습니다.");
		}
		String value = fundingDetail(in, t);
		double f = in.funding8h();
		String trend = diff == null || Math.abs(diff) < FUNDING_SAME ? "" : diff > 0 ? " · 평균보다 상승" : " · 평균보다 하락";
		if (f >= HOT_FUNDING) {
			return new IndicatorRow(name, value, "롱 과열", "down", "롱 보유 비용이 매우 높습니다" + trend + ".");
		}
		if (f <= -HOT_FUNDING) {
			return new IndicatorRow(name, value, "숏 과열", "up", "숏 보유 비용이 매우 높습니다" + trend + ".");
		}
		if (diff != null && diff >= FUNDING_SAME && f > 0) {
			return new IndicatorRow(name, value, "상승", "warn", "롱 쪽 수요가 늘어 보유 비용이 오르고 있습니다.");
		}
		if (f <= 0) {
			return new IndicatorRow(name, value, "숏 우위", "up", "숏이 비용을 내는 구간입니다" + trend + ".");
		}
		return new IndicatorRow(name, value, "평이", "neutral", "기본 수준에 가까운 펀딩비입니다" + trend + ".");
	}

	private IndicatorRow oiPriceRow(Double oi4h, Double change4h) {
		String name = "OI + 가격 (4h)";
		if (oi4h == null || change4h == null) {
			return new IndicatorRow(name, "-", "수집 중", "neutral", "4시간 OI 변화는 추적 종목만 제공됩니다.");
		}
		String value = "OI %s · 가격 %s".formatted(fmt.pct(oi4h), fmt.pct(change4h));
		String reading = OpenInterestService.reading(change4h, oi4h);
		return switch (reading) {
			case "신규 롱 유입" -> new IndicatorRow(name, value, reading, "up", "상승 쪽에 신규 포지션이 쌓이는 중입니다.");
			case "신규 숏 유입" -> new IndicatorRow(name, value, reading, "down", "하락 쪽에 신규 포지션이 쌓이는 중입니다.");
			case "숏 청산(커버)" -> new IndicatorRow(name, value, reading, "up", "숏 정리가 상승을 이끄는 중입니다.");
			case "롱 청산·정리" -> new IndicatorRow(name, value, reading, "down", "롱 정리가 하락을 이끄는 중입니다.");
			default -> new IndicatorRow(name, value, "변화 미미", "neutral", "포지션 규모 변화가 크지 않습니다.");
		};
	}

	private IndicatorRow takerRow(double taker24, Double taker4, Double shift) {
		String name = "시장가 매수 비중";
		if (taker4 == null) {
			return new IndicatorRow(name, "24h %.1f%%".formatted(taker24), "-", "neutral", "");
		}
		String value = takerDetail(taker24, taker4);
		if (shift >= TAKER_SHIFT) {
			return new IndicatorRow(name, value, "매수 강화", "up", "최근 4시간 매수세가 24시간 평균보다 강합니다.");
		}
		if (shift <= -TAKER_SHIFT) {
			return new IndicatorRow(name, value, "매도 강화", "down", "최근 4시간 매도세가 24시간 평균보다 강합니다.");
		}
		return new IndicatorRow(name, value, "비슷", "neutral", "24시간 흐름과 비슷합니다.");
	}

	private IndicatorRow priceRow(SymbolInput in, SymbolTrends t) {
		String name = "가격 위치";
		double p = in.price();
		if (t == null || p <= 0) {
			return new IndicatorRow(name, fmt.price(p), "-", "neutral", "");
		}
		String value = "4h %s ~ %s · 24h %s ~ %s".formatted(fmt.price(t.low4h()), fmt.price(t.high4h()),
				fmt.price(in.low24h()), fmt.price(in.high24h()));
		if (p <= in.low24h() * (1 + NEAR)) {
			return new IndicatorRow(name, value, "24h 저점 부근", "down", "24시간 저점 이탈 시 7일 저점 테스트 가능성이 커집니다.");
		}
		if (p >= in.high24h() * (1 - NEAR)) {
			return new IndicatorRow(name, value, "24h 고점 부근", "up", "24시간 고점 돌파가 유지되면 다음 저항은 7일 고점입니다.");
		}
		if (p <= t.low4h() * (1 + NEAR)) {
			return new IndicatorRow(name, value, "4h 저점 근접", "warn", "4시간 저점 이탈이 단기 약세 확인 지점입니다.");
		}
		if (p >= t.high4h() * (1 - NEAR)) {
			return new IndicatorRow(name, value, "4h 고점 근접", "warn", "4시간 고점 돌파가 단기 반등 확인 지점입니다.");
		}
		return new IndicatorRow(name, value, "범위 안", "neutral", "단기 고점·저점 사이에서 움직이고 있습니다.");
	}

	// ------------------------------------------------------------------

	private String fundingDetail(SymbolInput in, SymbolTrends t) {
		if (in.funding() == null) {
			return "-";
		}
		// 3일 평균은 정산 원값이라 현재값도 원값으로 표시 (8h 가 아닌 주기는 주기 병기)
		String now = fmt.funding(in.funding()) + (in.fundingIntervalHours() != null && in.fundingIntervalHours() != 8
				? " (%dh 주기)".formatted(in.fundingIntervalHours()) : "");
		return t != null && t.fundingAvg3d() != null
				? "%s · 3일 평균 %s".formatted(now, fmt.funding(t.fundingAvg3d()))
				: now;
	}

	private static String takerDetail(double taker24, Double taker4) {
		return taker4 == null ? "24h %.1f%%".formatted(taker24) : "4h %.1f%% · 24h %.1f%%".formatted(taker4, taker24);
	}

	private static String pctOrDash(Double v) {
		return v == null ? "-" : "%.1f%%".formatted(v);
	}

	private static boolean known(Double a, Double b) {
		return a != null && b != null;
	}
}
