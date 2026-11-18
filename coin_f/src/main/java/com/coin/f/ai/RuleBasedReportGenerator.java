package com.coin.f.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.coin.f.ai.AnalysisModels.AnalysisReport;
import com.coin.f.ai.AnalysisModels.MarketInput;
import com.coin.f.ai.AnalysisModels.Scenario;
import com.coin.f.ai.AnalysisModels.SymbolInput;
import com.coin.f.oi.OpenInterestService;
import com.coin.f.signal.MarketSignal.SignalType;
import com.coin.f.web.Formatters;

/**
 * 규칙 기반 분석 (무료). 지표를 정해진 기준으로 해석해 문장을 조합한다.
 * 예측이 아니라 "현재 지표가 말하는 것"과 "가격대별 조건부 시나리오"를 제시한다.
 */
@Component
@ConditionalOnProperty(name = "app.ai.generator", havingValue = "rule", matchIfMissing = true)
public class RuleBasedReportGenerator implements ReportGenerator {

	static final double BASE_FUNDING = 0.0001;   // 바이낸스 기본 펀딩 0.01%/8h
	static final double HOT_FUNDING = 0.0003;    // 8h 환산 ±0.03% 과열
	static final double LONG_BASELINE = 70.0;    // 개인 롱 계정 평상시 수준

	private final Formatters fmt;

	public RuleBasedReportGenerator(Formatters fmt) {
		this.fmt = fmt;
	}

	@Override
	public String modelName() {
		return "rule-based-v1";
	}

	@Override
	public String description() {
		return "규칙 기반 분석 v1 — 수집 지표를 정해진 기준으로 해석 (Claude 연동 시 AI 해석으로 교체 가능)";
	}

	@Override
	public boolean isFree() {
		return true;
	}

	// ================================================================== 시장

	@Override
	public AnalysisReport analyzeMarket(MarketInput in) {
		String reading = OpenInterestService.reading(in.btcChange24h(), in.oiChange24h());

		// ---- 요약
		List<String> s = new ArrayList<>();
		s.add("BTC 가격은 24시간 %s 움직여 %s이며, 시장 분위기 지수는 %d점(%s)입니다."
				.formatted(fmt.pct(in.btcChange24h()), fmt.price(in.btcPrice()), in.sentimentScore(),
						in.sentimentLabel()));
		if (in.oiUniverse() > 0) {
			s.add("거래대금 상위 %d종목의 미결제약정은 24시간 %s로, 가격 흐름과 함께 보면 '%s' 국면입니다."
					.formatted(in.oiUniverse(), fmt.pct(in.oiChange24h()), reading));
		}
		s.add("펀딩비 중앙값은 8시간 기준 %s로 %s 수준이며, 양(+) 펀딩 종목은 %.0f%%입니다."
				.formatted(fmt.funding(in.medianFunding8h()), fundingTone(in.medianFunding8h()),
						in.fundingPositivePct()));
		double liq24 = in.liq24hLong() + in.liq24hShort();
		if (liq24 > 0) {
			boolean longDom = in.liq24hLong() >= in.liq24hShort();
			s.add("최근 24시간 청산은 %s 청산이 %.0f%%로 우세해(합계 %s%s) %s."
					.formatted(longDom ? "롱" : "숏", Math.max(in.liq24hLong(), in.liq24hShort()) / liq24 * 100,
							fmt.usd(liq24),
							in.liqCollectedHours() < 24 ? ", 수집 %.1f시간분".formatted(in.liqCollectedHours()) : "",
							longDom ? "하락 과정에서 롱 포지션이 정리되었습니다" : "상승 과정에서 숏 포지션이 정리되었습니다"));
		}
		int totalSignals = in.signalsHigh() + in.signalsMedium() + in.signalsLow();
		if (totalSignals > 0) {
			s.add("현재 감지된 시그널은 %d건(높음 %d · 보통 %d)이며, 유형별로는 %s입니다."
					.formatted(totalSignals, in.signalsHigh(), in.signalsMedium(), topTypes(in.signalsByType())));
		} else {
			s.add("현재 조건을 충족한 시그널은 없습니다.");
		}

		// ---- 과열 여부
		int heat = 0;
		List<String> why = new ArrayList<>();
		heat += fundingHeat(in.medianFunding8h(), why, "펀딩비 중앙값");
		if (in.oiChange24h() >= 5) {
			heat++;
			why.add("OI 24h +%.1f%% (레버리지 누적)".formatted(in.oiChange24h()));
		} else if (in.oiChange24h() <= -5) {
			heat--;
			why.add("OI 24h %.1f%% (디레버리징)".formatted(in.oiChange24h()));
		}
		if (in.sentimentScore() >= 70) {
			heat++;
			why.add("분위기 지수 %d (탐욕)".formatted(in.sentimentScore()));
		} else if (in.sentimentScore() <= 30) {
			heat--;
			why.add("분위기 지수 %d (공포)".formatted(in.sentimentScore()));
		}
		if (in.hotLong() >= 10) {
			heat++;
			why.add("롱 과열 종목 %d개".formatted(in.hotLong()));
		}
		String level = level(heat);

		// ---- 시나리오 (BTC 기준)
		boolean longCrowded = in.medianFunding8h() >= HOT_FUNDING * 0.66;
		boolean shortCrowded = in.medianFunding8h() <= 0;
		List<Scenario> scenarios = scenarios("BTC", in.btcPrice(), in.btcHigh24h(), in.btcLow24h(), in.btcHigh7d(),
				in.btcLow7d(), longCrowded, shortCrowded, reading);

		// ---- 위험 요소
		List<String> risks = new ArrayList<>();
		if (in.hotLong() >= 5) {
			risks.add("롱 과열 종목 %d개 — 알트 롱 쏠림이 해소될 때 급락이 나올 수 있습니다.".formatted(in.hotLong()));
		}
		if (in.hotShort() >= 5) {
			risks.add("숏 과열 종목 %d개 — 숏 스퀴즈(급등)가 나올 수 있습니다.".formatted(in.hotShort()));
		}
		if (in.oiChange24h() >= 5) {
			risks.add("OI가 24시간 %s 빠르게 늘어 작은 가격 변화에도 연쇄 청산이 커질 수 있습니다."
					.formatted(fmt.pct(in.oiChange24h())));
		}
		double hourAvg = liq24 / Math.max(1, Math.min(24, in.liqCollectedHours()));
		double liq1h = in.liq1hLong() + in.liq1hShort();
		if (hourAvg > 0 && liq1h >= hourAvg * 3 && in.liqCollectedHours() >= 3) {
			risks.add("최근 1시간 청산(%s)이 시간당 평균의 %.1f배 — 급변동이 진행 중입니다."
					.formatted(fmt.usd(liq1h), liq1h / hourAvg));
		}
		double range = rangePct(in.btcHigh24h(), in.btcLow24h(), in.btcPrice());
		if (range >= 5) {
			risks.add("BTC 24시간 변동폭 %.1f%% — 변동성이 커진 구간입니다.".formatted(range));
		}
		if (in.signalsHigh() > 0) {
			risks.add("중요도 '높음' 시그널 %d건 — 시그널 화면에서 근거를 확인하세요.".formatted(in.signalsHigh()));
		}
		if (in.sentimentScore() >= 75 || in.sentimentScore() <= 25) {
			risks.add("분위기 지수가 극단값(%d)이라 반대 방향 되돌림에 유의해야 합니다.".formatted(in.sentimentScore()));
		}
		if (in.liqCollectedHours() < 24) {
			risks.add("청산 데이터 수집 기간이 %.1f시간으로 짧아 청산 관련 판단은 불완전합니다.".formatted(in.liqCollectedHours()));
		}
		if (risks.isEmpty()) {
			risks.add("지표 기준으로 두드러진 위험 신호는 없습니다.");
		}

		return new AnalysisReport(String.join(" ", s), scenarios, level,
				why.isEmpty() ? "두드러진 과열·냉각 요인 없음" : String.join(", ", why), risks);
	}

	// ================================================================== 종목

	@Override
	public AnalysisReport analyzeSymbol(SymbolInput in) {
		String base = in.symbol().replaceAll("USDT$", "");
		Double oi24 = in.oiChange24h();
		String reading = oi24 == null ? "변화 미미" : OpenInterestService.reading(in.change24h(), oi24);

		// ---- 요약
		List<String> s = new ArrayList<>();
		// 심볼 발음에 따라 조사(은/는)가 달라지므로 "가격은"으로 고정
		s.add("%s 가격은 24시간 %s 움직여 %s이며, 24시간 범위는 %s ~ %s입니다."
				.formatted(base, fmt.pct(in.change24h()), fmt.price(in.price()), fmt.price(in.low24h()),
						fmt.price(in.high24h())));
		if (in.oiUsd() != null && oi24 != null) {
			s.add("미결제약정은 %s(1h %s · 4h %s · 24h %s)로 '%s' 국면입니다."
					.formatted(fmt.usd(in.oiUsd()), fmt.pctOrDash(in.oiChange1h()), fmt.pctOrDash(in.oiChange4h()),
							fmt.pct(oi24), reading));
		}
		if (in.funding8h() != null) {
			String f = in.fundingIntervalHours() != null && in.fundingIntervalHours() != 8
					? "%s(%dh 주기, 8h 환산 %s)".formatted(fmt.funding(in.funding()), in.fundingIntervalHours(),
							fmt.funding(in.funding8h()))
					: fmt.funding(in.funding8h());
			s.add("펀딩비는 %s로 %s 수준입니다.".formatted(f, fundingTone(in.funding8h())));
		}
		if (in.longPct() != null) {
			s.add("롱 계정 비율은 %.1f%%로 평상시(약 70%%) %s입니다."
					.formatted(in.longPct(), in.longPct() >= 80 ? "보다 롱 쏠림이 강한 상태"
							: in.longPct() <= 60 ? "보다 숏 쪽으로 기운 상태" : "범위"));
		}
		double taker = in.takerBuyShare24h() * 100;
		String takerTone = taker >= 52 ? "매수 우위입니다" : taker <= 48 ? "매도 우위입니다" : "균형에 가깝습니다";
		s.add("최근 24시간 시장가 매수 비중은 %.1f%%로 %s.".formatted(taker, takerTone));
		double liq24 = in.liq24hLong() + in.liq24hShort();
		if (liq24 > 0) {
			s.add("24시간 청산은 롱 %s / 숏 %s입니다.".formatted(fmt.usd(in.liq24hLong()), fmt.usd(in.liq24hShort())));
		}
		if (!in.signals().isEmpty()) {
			s.add("감지된 시그널: %s.".formatted(String.join(", ", in.signals())));
		}

		// ---- 과열 여부
		int heat = 0;
		List<String> why = new ArrayList<>();
		if (in.funding8h() != null) {
			heat += fundingHeat(in.funding8h(), why, "펀딩비");
		}
		if (oi24 != null && oi24 >= 10) {
			heat++;
			why.add("OI 24h %s".formatted(fmt.pct(oi24)));
		} else if (oi24 != null && oi24 <= -10) {
			heat--;
			why.add("OI 24h %s".formatted(fmt.pct(oi24)));
		}
		if (in.longPct() != null && in.longPct() >= 80) {
			heat++;
			why.add("롱 계정 %.1f%%".formatted(in.longPct()));
		} else if (in.longPct() != null && in.longPct() <= 45) {
			heat--;
			why.add("롱 계정 %.1f%% (숏 쏠림)".formatted(in.longPct()));
		}
		if (in.signals().stream().anyMatch(x -> x.startsWith(SignalType.COMPOSITE.getLabel()))) {
			heat += 2;
			why.add("복합 지표 시그널");
		}
		String level = level(heat);

		// ---- 시나리오
		boolean longCrowded = (in.funding8h() != null && in.funding8h() >= HOT_FUNDING)
				|| (in.longPct() != null && in.longPct() >= 80);
		boolean shortCrowded = (in.funding8h() != null && in.funding8h() <= -HOT_FUNDING)
				|| (in.longPct() != null && in.longPct() <= 45);
		List<Scenario> scenarios = scenarios(base, in.price(), in.high24h(), in.low24h(), in.high7d(), in.low7d(),
				longCrowded, shortCrowded, reading);

		// ---- 위험 요소
		List<String> risks = new ArrayList<>();
		if (longCrowded) {
			risks.add("롱 쏠림(펀딩비·롱 비율) — 지지선 이탈 시 롱 청산이 연쇄될 수 있습니다.");
		}
		if (shortCrowded) {
			risks.add("숏 쏠림 — 저항선 돌파 시 숏 스퀴즈로 급등할 수 있습니다.");
		}
		if (in.oiChange1h() != null && Math.abs(in.oiChange1h()) >= 3) {
			risks.add("OI가 1시간 만에 %s 변해 포지션이 급격히 %s 있습니다."
					.formatted(fmt.pct(in.oiChange1h()), in.oiChange1h() > 0 ? "쌓이고" : "정리되고"));
		}
		double range = rangePct(in.high24h(), in.low24h(), in.price());
		if (range >= 6) {
			risks.add("24시간 변동폭 %.1f%% — 변동성이 큰 구간입니다.".formatted(range));
		}
		// 수집 기간이 짧으면 1시간 = 전체라 비교가 무의미 → 3시간 이상 수집됐을 때만
		double liq1h = in.liq1hLong() + in.liq1hShort();
		double hourAvg = liq24 / Math.max(1, Math.min(24, in.liqCollectedHours()));
		if (in.liqCollectedHours() >= 3 && hourAvg > 0 && liq1h >= hourAvg * 3) {
			risks.add("최근 1시간 청산(%s)이 시간당 평균의 %.1f배 — 급변동이 진행 중입니다."
					.formatted(fmt.usd(liq1h), liq1h / hourAvg));
		}
		if (risks.isEmpty()) {
			risks.add("지표 기준으로 두드러진 위험 신호는 없습니다.");
		}

		return new AnalysisReport(String.join(" ", s), scenarios, level,
				why.isEmpty() ? "두드러진 과열·냉각 요인 없음" : String.join(", ", why), risks);
	}

	// ================================================================== 공통 규칙

	/** 가격대 기반 조건부 시나리오 3개: 상승 / 하락 / 지표상 무게 */
	private List<Scenario> scenarios(String name, double price, double h24, double l24, double h7, double l7,
			boolean longCrowded, boolean shortCrowded, String reading) {
		List<Scenario> out = new ArrayList<>();

		StringBuilder up = new StringBuilder();
		// 숫자 뒤 조사(을/를) 오류를 피하려고 "고점(가격)" 형태로 표기
		if (price >= h24 * 0.997) {
			up.append("현재 24시간 고점(%s) 부근입니다. 돌파가 유지되면 ".formatted(fmt.price(h24)));
		} else {
			up.append("24시간 고점(%s) 회복·돌파 시 ".formatted(fmt.price(h24)));
		}
		up.append(h7 > h24 * 1.002
				? "7일 고점(%s)까지가 다음 저항 구간입니다.".formatted(fmt.price(h7))
				: "7일 고점도 같은 구간이라 신고점 영역으로 들어갑니다.");
		if (shortCrowded) {
			up.append(" 숏 쏠림 상태라 돌파 시 숏 청산이 상승을 가속할 수 있습니다.");
		}
		up.append(" 이때 OI가 함께 늘면 신규 롱 주도, 줄면 숏 커버 주도 상승으로 봅니다.");
		out.add(new Scenario("UP", "상승 시나리오", up.toString()));

		StringBuilder down = new StringBuilder();
		if (price <= l24 * 1.003) {
			down.append("현재 24시간 저점(%s) 부근입니다. 이탈이 이어지면 ".formatted(fmt.price(l24)));
		} else {
			down.append("24시간 저점(%s) 이탈 시 ".formatted(fmt.price(l24)));
		}
		down.append(l7 < l24 * 0.998
				? "7일 저점(%s) 테스트 가능성이 커집니다.".formatted(fmt.price(l7))
				: "7일 저점도 같은 구간이라 신저점 영역으로 들어갑니다.");
		if (longCrowded) {
			down.append(" 롱 쏠림 상태라 이탈 시 롱 청산이 연쇄될 수 있습니다.");
		}
		down.append(" OI가 늘며 하락하면 신규 숏 주도, 줄며 하락하면 롱 정리 주도입니다.");
		out.add(new Scenario("DOWN", "하락 시나리오", down.toString()));

		String lean = switch (reading) {
			case "신규 롱 유입" -> "상승 쪽에 신규 포지션이 쌓이는 중이라 상승 시나리오에 무게가 실립니다. 다만 펀딩비가 과열되면 되돌림 위험이 커집니다.";
			case "신규 숏 유입" -> "하락 쪽에 신규 포지션이 쌓이는 중이라 하락 시나리오에 무게가 실립니다. 다만 숏이 과밀해지면 반등(숏 스퀴즈) 위험이 있습니다.";
			case "숏 청산(커버)" -> "숏 커버가 이끈 상승이라, 신규 롱 유입(OI 증가)이 뒤따르지 않으면 상승 탄력이 약해질 수 있습니다.";
			case "롱 청산·정리" -> "레버리지가 정리되는 하락 국면입니다. 청산이 잦아들면 변동성이 줄며 %s ~ %s 박스권으로 전환될 수 있습니다."
					.formatted(fmt.price(l24), fmt.price(h24));
			default -> "뚜렷한 포지션 변화가 없어 %s ~ %s 범위 안에서 등락할 가능성이 높습니다.".formatted(fmt.price(l24),
					fmt.price(h24));
		};
		out.add(new Scenario("BASE", "현재 지표가 무게를 두는 쪽", "가격·OI 흐름은 '%s'입니다. %s".formatted(reading, lean)));
		return out;
	}

	private String fundingTone(double f8h) {
		if (f8h >= HOT_FUNDING) return "롱 과열";
		if (f8h >= BASE_FUNDING * 1.5) return "롱 우위";
		if (f8h >= BASE_FUNDING * 0.5) return "평이한";
		if (f8h > -BASE_FUNDING * 0.5) return "중립에 가까운";
		if (f8h > -HOT_FUNDING) return "숏 우위";
		return "숏 과열";
	}

	private static int fundingHeat(double f8h, List<String> why, String label) {
		if (f8h >= HOT_FUNDING) {
			why.add("%s %+.4f%% (과열)".formatted(label, f8h * 100));
			return 2;
		}
		if (f8h >= BASE_FUNDING * 1.5) {
			why.add("%s %+.4f%% (롱 우위)".formatted(label, f8h * 100));
			return 1;
		}
		if (f8h <= -HOT_FUNDING) {
			why.add("%s %+.4f%% (숏 과열)".formatted(label, f8h * 100));
			return -2;
		}
		if (f8h <= 0) {
			why.add("%s %+.4f%% (숏 우위)".formatted(label, f8h * 100));
			return -1;
		}
		return 0;
	}

	static String level(int heat) {
		if (heat >= 3) return "과열";
		if (heat >= 1) return "주의";
		if (heat <= -2) return "냉각";
		return "중립";
	}

	private static double rangePct(double high, double low, double price) {
		return price <= 0 ? 0 : (high - low) / price * 100;
	}

	private static String topTypes(Map<String, Integer> byType) {
		return byType.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.limit(3)
				.map(e -> e.getKey() + " " + e.getValue() + "건")
				.collect(Collectors.joining(", "));
	}
}
