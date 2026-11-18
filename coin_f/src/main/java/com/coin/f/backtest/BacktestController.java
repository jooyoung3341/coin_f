package com.coin.f.backtest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.coin.f.backtest.BacktestModels.Mode;
import com.coin.f.backtest.BacktestModels.Params;
import com.coin.f.backtest.BacktestModels.Result;
import com.coin.f.funding.FundingService;
import com.coin.f.signal.MarketSignal.SignalType;
import com.coin.f.web.Menu;

import tools.jackson.databind.json.JsonMapper;

/**
 * 백테스트 화면 (메뉴 07 의 두 번째 탭)
 */
@Controller
public class BacktestController {

	private final BacktestService service;
	private final FundingService fundingService;
	private final JsonMapper json;
	private final List<String> coreSymbols;

	public BacktestController(BacktestService service, FundingService fundingService, JsonMapper json,
			@Value("${app.market.symbols}") List<String> coreSymbols) {
		this.service = service;
		this.fundingService = fundingService;
		this.json = json;
		this.coreSymbols = coreSymbols;
	}

	@GetMapping("/backtest")
	public String page(@RequestParam(required = false) Long run, Model model) {
		Result result = run != null ? service.find(run) : service.latest();
		Params form = result != null ? result.params() : Params.defaults();

		model.addAttribute("activeMenu", Menu.AI);
		model.addAttribute("result", result);
		model.addAttribute("notFound", run != null && result == null);
		model.addAttribute("form", form);
		model.addAttribute("types", BacktestEngine.replayableTypes());
		model.addAttribute("modes", Mode.values());
		model.addAttribute("holds", BacktestService.HOLD_OPTIONS);
		model.addAttribute("dayOptions", BacktestService.DAY_OPTIONS);
		model.addAttribute("symbolOptions", symbolOptions());
		model.addAttribute("runs", service.recentRuns(15));
		model.addAttribute("outcomeReady", service.outcomeColumnsReady());
		Map<String, String> typeLabels = new LinkedHashMap<>();
		typeLabels.put("ALL", "전체 유형");
		for (SignalType t : SignalType.values()) {
			typeLabels.put(t.name(), t.getLabel());
		}
		model.addAttribute("typeLabels", typeLabels);
		if (result != null) {
			Map<String, Object> chart = new LinkedHashMap<>();
			chart.put("equity", result.equity());
			chart.put("weekly", result.weekly());
			model.addAttribute("chartJson", json.writeValueAsString(chart));
			int n = result.trades().size();
			List<BacktestModels.Trade> recent = new ArrayList<>(result.trades().subList(Math.max(0, n - 100), n));
			java.util.Collections.reverse(recent);
			model.addAttribute("recentTrades", recent);
		}
		return "backtest";
	}

	@PostMapping("/backtest")
	public String run(@RequestParam(defaultValue = "ALL") String type,
			@RequestParam(defaultValue = "ALL") String symbol,
			@RequestParam(defaultValue = "30") int days,
			@RequestParam(defaultValue = "4") int hold,
			@RequestParam(defaultValue = "CONTRARIAN") String mode,
			@RequestParam(required = false) Double tp,
			@RequestParam(required = false) Double sl,
			@RequestParam(defaultValue = "0.08") double fee,
			RedirectAttributes redirect) {
		Params p = new Params(
				parseType(type),
				"ALL".equals(symbol) || symbol.isBlank() ? null : symbol.toUpperCase(),
				BacktestService.DAY_OPTIONS.contains(days) ? days : 30,
				BacktestService.HOLD_OPTIONS.contains(hold) ? hold : 4,
				"FOLLOW".equals(mode) ? Mode.FOLLOW : Mode.CONTRARIAN,
				pct(tp), pct(sl),
				Math.max(0, Math.min(1, fee)));
		Result r = service.run(p);
		redirect.addAttribute("run", r.runId());
		return "redirect:/backtest";
	}

	/** 기본 종목 먼저, 나머지는 펀딩 이력이 있는(추적 중인) 종목 */
	private List<String> symbolOptions() {
		List<String> out = new ArrayList<>(coreSymbols);
		fundingService.historySymbols().stream().filter(s -> !out.contains(s)).forEach(out::add);
		return out;
	}

	private static SignalType parseType(String s) {
		try {
			SignalType t = SignalType.valueOf(s);
			return BacktestEngine.replayableTypes().contains(t) ? t : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** 0 이하 · 미입력 = 미사용, 상한 50% */
	private static Double pct(Double v) {
		return v == null || v <= 0 ? null : Math.min(50, v);
	}
}
