package com.coin.f.signal;

import java.util.List;

/**
 * 감지된 시장 신호. 방향만이 아니라 발생 이유와 관련 지표(evidence)를 함께 담는다.
 * @param crowdedSide 포지션이 몰린 쪽 (LONG/SHORT/NEUTRAL). 매수·매도 추천이 아님.
 */
public record MarketSignal(SignalType type, String symbol, Level level, String crowdedSide, String reason,
		List<String> evidence) {

	public enum Level {
		HIGH("높음", "danger"), MEDIUM("보통", "warning"), LOW("낮음", "secondary");

		private final String label;
		private final String color;

		Level(String label, String color) {
			this.label = label;
			this.color = color;
		}

		public String getLabel() { return label; }
		public String getColor() { return color; }
	}

	public enum SignalType {
		LONG_OVERHEAT("롱 과열"),
		SHORT_OVERHEAT("숏 과열"),
		OI_SURGE("OI 급증"),
		FUNDING_OVERHEAT("펀딩비 과열"),
		MASS_LIQUIDATION("대량 청산"),
		COMPOSITE("복합 지표");

		private final String label;

		SignalType(String label) {
			this.label = label;
		}

		public String getLabel() { return label; }
	}
}
