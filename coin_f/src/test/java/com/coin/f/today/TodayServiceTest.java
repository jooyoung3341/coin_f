package com.coin.f.today;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.coin.f.market.MarketCapService;
import com.coin.f.market.MarketCapService.MarketCap;
import com.coin.f.market.MarketModels.TickerDetail;
import com.coin.f.today.TodayService.CoinRow;

class TodayServiceTest {

	private static TickerDetail t(String symbol, double change, double high, double low, double volume) {
		return new TickerDetail(symbol, 100, change, high, low, volume, 1000);
	}

	@Test
	void 선물_심볼에서_기초자산_추출() {
		assertEquals("BTC", MarketCapService.assetOf("BTCUSDT"));
		assertEquals("PEPE", MarketCapService.assetOf("1000PEPEUSDT"));
		assertEquals("MOG", MarketCapService.assetOf("1000000MOGUSDT"));
		assertEquals("BABYDOGE", MarketCapService.assetOf("1MBABYDOGEUSDT"));
		assertEquals("1INCH", MarketCapService.assetOf("1INCHUSDT"));
	}

	@Test
	void 거래대금_등락_회전율이_모두_큰_코인이_1위_스테이블은_제외() {
		List<TickerDetail> tickers = List.of(
				t("AAAUSDT", 12, 115, 100, 5e9),   // 거래대금·등락·변동폭 최대
				t("BBBUSDT", 1, 101, 100, 1e9),
				t("CCCUSDT", -3, 104, 100, 2e8),
				t("USDCUSDT", 0, 100, 100, 9e9)); // 스테이블 → 제외
		Map<String, MarketCap> caps = Map.of(
				"AAA", new MarketCap("AAA", "Aaa", 2e12, 50),
				"BBB", new MarketCap("BBB", "Bbb", 100e12, 5),
				"CCC", new MarketCap("CCC", "Ccc", 5e12, 30));
		List<CoinRow> rows = TodayService.build(tickers, s -> caps.get(MarketCapService.assetOf(s)), 1400,
				Map.of(), Map.of("AAAUSDT", 15.0));

		assertEquals(3, rows.size());
		CoinRow top = rows.stream().max(Comparator.comparingDouble(CoinRow::score)).orElseThrow();
		assertEquals("AAAUSDT", top.symbol());
		assertEquals(100.0, top.score(), 1e-9);
		// 회전율 = 50억$ × 1400원 / 2조원 = 350%
		assertEquals(350.0, top.turnover(), 1e-9);
		assertEquals(7e12, top.volumeKrw(), 1);
		assertTrue(top.tags().containsAll(List.of("급등", "거래대금 상위", "시총에 비해 거래 많음", "변동성 큼", "OI 급증")));
	}

	@Test
	void 도미넌스_24시간_변화_추정() {
		// 전체 시총 그대로, BTC 시총만 +10% → 24h 전 점유율 60/1.1 = 54.55% → +5.45%p
		assertEquals(60 - 60 / 1.1, MarketCapService.dominanceChange(60, 10.0, 0.0), 1e-9);
		// 전체·BTC 모두 -5% → 점유율 변화 없음
		assertEquals(0.0, MarketCapService.dominanceChange(60, -5.0, -5.0), 1e-9);
		// 실측 예: 점유율 58.43%, BTC -0.43%, 전체 -3.14% → 약 +1.59%p
		assertEquals(1.59, MarketCapService.dominanceChange(58.43, -0.433, -3.137), 0.01);
		assertEquals(null, MarketCapService.dominanceChange(60, null, 0.0));
	}

	@Test
	void 시가총액_모르면_회전율_대신_거래대금_백분위_사용() {
		List<CoinRow> rows = TodayService.build(List.of(t("XUSDT", 0, 100, 100, 1e9), t("YUSDT", 0, 100, 100, 2e9)),
				s -> null, 0, Map.of(), Map.of());
		CoinRow y = rows.stream().filter(r -> r.symbol().equals("YUSDT")).findFirst().orElseThrow();
		assertEquals(null, y.turnover());
		assertEquals(null, y.volumeKrw());
		// 거래대금 1위(35%) + 회전율 대체(20%) = 55, 등락·변동폭은 동률 0 → 백분위 0
		assertEquals(55.0, y.score(), 1e-9);
	}
}
