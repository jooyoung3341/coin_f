package com.coin.f.funding;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.coin.f.market.MarketDataProvider;
import com.coin.f.market.MarketModels.FundingRate;
import com.coin.f.storage.Rows.TimeValue;

@RestController
@RequestMapping("/api/funding")
public class FundingApiController {

	private final FundingService fundingService;
	private final MarketDataProvider provider;

	public FundingApiController(FundingService fundingService, MarketDataProvider provider) {
		this.fundingService = fundingService;
		this.provider = provider;
	}

	/** 확정 펀딩비 이력 (정산 1회당 원값) + 현재 정산 주기 */
	@GetMapping("/history")
	public History history(@RequestParam(defaultValue = "BTCUSDT") String symbol,
			@RequestParam(defaultValue = "30") int days) {
		String s = symbol.toUpperCase();
		int interval = provider.getFundingRates().stream().filter(f -> f.symbol().equals(s))
				.mapToInt(FundingRate::intervalHours).findFirst().orElse(8);
		return new History(s, interval, fundingService.history(s, days));
	}

	public record History(String symbol, int intervalHours, List<TimeValue> points) {
	}
}
