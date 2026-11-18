package com.coin.f.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.coin.f.ai.AiReportService;
import com.coin.f.ai.AiReportService.StoredReport;
import com.coin.f.coin.CoinService;
import com.coin.f.dashboard.DashboardService;
import com.coin.f.funding.FundingService;
import com.coin.f.oi.OpenInterestService;
import com.coin.f.oi.OpenInterestService.OiView;
import com.coin.f.signal.MarketSignal;
import com.coin.f.signal.SignalDetector;
import com.coin.f.signal.SignalViewService;
import com.coin.f.today.TodayService;

import tools.jackson.databind.json.JsonMapper;

/**
 * 7개 메뉴 화면 라우팅
 */
@Controller
public class PageController {

	private final DashboardService dashboardService;
	private final TodayService todayService;
	private final CoinService coinService;
	private final FundingService fundingService;
	private final OpenInterestService oiService;
	private final SignalViewService signalViewService;
	private final AiReportService aiReportService;
	private final JsonMapper json;
	private final List<String> symbols;

	public PageController(DashboardService dashboardService, CoinService coinService, FundingService fundingService,
			OpenInterestService oiService, SignalViewService signalViewService, AiReportService aiReportService,
			JsonMapper json, @Value("${app.market.symbols}") List<String> symbols, TodayService todayService) {
		this.todayService = todayService;
		this.dashboardService = dashboardService;
		this.coinService = coinService;
		this.fundingService = fundingService;
		this.oiService = oiService;
		this.signalViewService = signalViewService;
		this.aiReportService = aiReportService;
		this.json = json;
		this.symbols = symbols;
	}

	@GetMapping("/")
	public String dashboard(Model model) {
		model.addAttribute("activeMenu", Menu.DASHBOARD);
		model.addAttribute("dashboard", dashboardService.getDashboard());
		return "dashboard";
	}

	@GetMapping("/today")
	public String today(Model model) {
		model.addAttribute("activeMenu", Menu.TODAY);
		model.addAttribute("view", todayService.getView());
		model.addAttribute("top", TodayService.TOP);
		return "today";
	}

	@GetMapping("/coin/{symbol}")
	public String coin(@PathVariable String symbol, Model model) {
		String upper = symbol.toUpperCase();
		// 기본 종목 + 시가총액 기준을 통과한 모든 코인 선물
		String s = coinService.isAvailable(upper, symbols) ? upper : symbols.get(0);
		model.addAttribute("activeMenu", Menu.COIN);
		model.addAttribute("symbol", s);
		model.addAttribute("coin", coinService.getSummary(s));
		model.addAttribute("aiReport", aiReportService.reportFor(s));
		model.addAttribute("coinOptions", coinService.selectableSymbols());
		return "coin";
	}

	@GetMapping("/funding")
	public String funding(Model model) {
		model.addAttribute("activeMenu", Menu.FUNDING);
		model.addAttribute("view", fundingService.getView());
		model.addAttribute("historySymbols", fundingService.historySymbols());
		return "funding";
	}

	@GetMapping("/oi")
	public String openInterest(Model model) {
		OiView view = oiService.getView();
		model.addAttribute("activeMenu", Menu.OPEN_INTEREST);
		model.addAttribute("view", view);
		// Thymeleaf 인라인 직렬화는 Jackson 2 전용 → Jackson 3 로 직접 직렬화
		model.addAttribute("oiJson", json.writeValueAsString(view.rows()));
		return "oi";
	}

	@GetMapping("/liquidation")
	public String liquidation(Model model) {
		model.addAttribute("activeMenu", Menu.LIQUIDATION);
		return "liquidation";
	}

	@GetMapping("/signal")
	public String signal(Model model) {
		model.addAttribute("activeMenu", Menu.SIGNAL);
		model.addAttribute("view", signalViewService.getView());
		model.addAttribute("criteria", SignalDetector.criteria());
		model.addAttribute("signalTypes", MarketSignal.SignalType.values());
		return "signal";
	}

	@GetMapping("/ai")
	public String ai(Model model) {
		Map<String, StoredReport> bySymbol = new LinkedHashMap<>();
		symbols.forEach(s -> {
			StoredReport r = aiReportService.latestFor(s);
			if (r != null) {
				bySymbol.put(s, r);
			}
		});
		model.addAttribute("activeMenu", Menu.AI);
		model.addAttribute("market", aiReportService.latestMarket());
		model.addAttribute("symbolReports", bySymbol);
		model.addAttribute("history", aiReportService.marketHistory(12));
		model.addAttribute("engine", aiReportService.generatorDescription());
		return "ai";
	}

}
