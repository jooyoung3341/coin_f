package com.coin.f.web;

import java.time.Duration;

import org.springframework.stereotype.Component;

/**
 * Thymeleaf 숫자 포맷 헬퍼. 템플릿에서 ${@fmt.usd(x)} 형태로 사용.
 */
@Component("fmt")
public class Formatters {

	/** $142.3B / $4.30B / $780M */
	public String usd(double v) {
		double a = Math.abs(v);
		if (a >= 1e12) return "$%.2fT".formatted(v / 1e12);
		if (a >= 1e9) return "$%.2fB".formatted(v / 1e9);
		if (a >= 1e6) return "$%.1fM".formatted(v / 1e6);
		if (a >= 1e3) return "$%.1fK".formatted(v / 1e3);
		return "$%.2f".formatted(v);
	}

	/** 가격 크기에 따라 소수 자릿수 조정 */
	public String price(double v) {
		if (v >= 1000) return "%,.1f".formatted(v);
		if (v >= 10) return "%,.2f".formatted(v);
		if (v >= 1) return "%.3f".formatted(v);
		return "%.5f".formatted(v);
	}

	/** +2.41% */
	public String pct(double v) {
		return "%+.2f%%".formatted(v);
	}

	/** 펀딩비: 소수 → +0.0100% */
	public String funding(double rate) {
		return "%+.4f%%".formatted(rate * 100);
	}

	/** 펀딩비 막대 폭(%) : |0.06%| 이상이면 100 */
	public long fundingBar(double rate) {
		return Math.round(Math.min(100, Math.abs(rate) / 0.0006 * 100));
	}

	/** 다음 펀딩까지 남은 시간 03:21 */
	public String countdown(long epochMillis) {
		Duration d = Duration.ofMillis(Math.max(0, epochMillis - System.currentTimeMillis()));
		return "%02d:%02d".formatted(d.toHours(), d.toMinutesPart());
	}

	/** epoch ms → 서버 로컬 시각 HH:mm:ss */
	public String time(long epochMillis) {
		return java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault())
				.toLocalTime().withNano(0).toString();
	}

	/** epoch ms → 11/11 10:44 */
	public String dateTime(long epochMillis) {
		java.time.LocalDateTime t = java.time.Instant.ofEpochMilli(epochMillis)
				.atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
		return "%d/%d %02d:%02d".formatted(t.getMonthValue(), t.getDayOfMonth(), t.getHour(), t.getMinute());
	}

	/** 시그널 쏠림 방향 표시 */
	public String side(String direction) {
		if ("LONG".equals(direction)) return "롱 쏠림";
		if ("SHORT".equals(direction)) return "숏 쏠림";
		return "중립";
	}

	/** null 허용 변화율 */
	public String pctOrDash(Double v) {
		return v == null ? "-" : pct(v);
	}

	public String toneOrMuted(Double v) {
		return v == null ? "text-muted" : tone(v);
	}

	/** 양수/음수 색상 클래스 */
	public String tone(double v) {
		return v > 0 ? "text-up" : v < 0 ? "text-down" : "text-muted";
	}

	/** 원화 금액: 1,234.5조 / 12.3조 / 4,567억 / 1,234만 원 */
	public String krw(Double v) {
		if (v == null) return "-";
		double a = Math.abs(v);
		if (a >= 1e14) return "%,.0f조".formatted(v / 1e12);
		if (a >= 1e12) return "%,.1f".formatted(v / 1e12).replace(".0", "") + "조";
		if (a >= 1e8) return "%,.0f억".formatted(v / 1e8);
		if (a >= 1e4) return "%,.0f만".formatted(v / 1e4);
		return "%,.0f원".formatted(v);
	}

	/** 퍼센트포인트 변화 +0.12%p (null 이면 -) */
	public String pp(Double v) {
		return v == null ? "-" : "%+.2f%%p".formatted(v);
	}

	/** 비율 표시 55.3% (부호 없음) */
	public String rate(double v) {
		return "%.1f%%".formatted(v);
	}

	/** epoch ms → 2026-11-04 */
	public String date(long epochMillis) {
		return java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
				.toString();
	}

	/** 백테스트 청산 사유 */
	public String exitReason(String reason) {
		return switch (reason) {
			case "TP" -> "익절";
			case "SL" -> "손절";
			default -> "시간 만료";
		};
	}

	/** 백테스트 진입 방향 */
	public String position(String position) {
		return "LONG".equals(position) ? "롱" : "숏";
	}

	public String base(String symbol) {
		return symbol.endsWith("USDT") ? symbol.substring(0, symbol.length() - 4) : symbol;
	}
}
