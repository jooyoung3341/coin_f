package com.coin.f.web;

/**
 * 사이트 좌측 메뉴 (설계서 1. 전체 메뉴 구성)
 */
public enum Menu {

	DASHBOARD("01", "대시보드", "전체 시장 상황 한눈에 확인", "/", "bi-speedometer2"),
	TODAY("02", "오늘의 코인", "24시간 거래대금·등락 HOT 20", "/today", "bi-fire"),
	COIN("03", "코인 상세 분석", "개별 코인 가격 및 지표 분석", "/coin/BTCUSDT", "bi-currency-bitcoin"),
	FUNDING("04", "펀딩비 분석", "거래소별 펀딩비 및 과열 상태", "/funding", "bi-percent"),
	OPEN_INTEREST("05", "미결제약정(OI)", "자금 유입 및 포지션 변화", "/oi", "bi-bar-chart-line"),
	LIQUIDATION("06", "청산 분석", "롱·숏 청산 현황", "/liquidation", "bi-lightning-charge"),
	SIGNAL("07", "매매 시그널", "조건별 시장 신호 탐지", "/signal", "bi-broadcast"),
	AI("08", "AI 분석 / 백테스트", "AI 해석 및 과거 성과 검증", "/ai", "bi-cpu");

	private final String no;
	private final String title;
	private final String description;
	private final String path;
	private final String icon;

	Menu(String no, String title, String description, String path, String icon) {
		this.no = no;
		this.title = title;
		this.description = description;
		this.path = path;
		this.icon = icon;
	}

	public String getNo() { return no; }
	public String getTitle() { return title; }
	public String getDescription() { return description; }
	public String getPath() { return path; }
	public String getIcon() { return icon; }
	public String getKey() { return name(); }
}
