package com.coin.f.ai;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.coin.f.ai.AnalysisModels.AnalysisReport;
import com.coin.f.ai.AnalysisModels.Scenario;
import com.coin.f.storage.AiReportRow;
import com.coin.f.storage.MarketDataRecorder;

import jakarta.annotation.PostConstruct;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI 분석 리포트 생성·저장·조회 (7단계).
 * 화면 요청마다 생성하지 않고 주기적으로 생성해 ai_report 에 저장 → 유료 AI 로 바꿔도 비용은 생성 주기로만 결정.
 */
@Service
public class AiReportService {

	private static final Logger log = LoggerFactory.getLogger(AiReportService.class);
	static final String MARKET_KEY = "MARKET";
	private static final long REGENERATE_COOLDOWN = 60_000L;

	private final ReportGenerator generator;
	private final AnalysisInputBuilder inputs;
	private final MarketDataRecorder recorder;
	private final JsonMapper json;
	private final List<String> symbols;

	/** 최신 리포트 캐시: MARKET 또는 심볼 → 리포트 */
	private final Map<String, StoredReport> latest = new ConcurrentHashMap<>();
	private volatile long lastGeneratedAt;

	public AiReportService(ReportGenerator generator, AnalysisInputBuilder inputs, MarketDataRecorder recorder,
			JsonMapper json, @Value("${app.market.symbols}") List<String> symbols) {
		this.generator = generator;
		this.inputs = inputs;
		this.recorder = recorder;
		this.json = json;
		this.symbols = symbols;
	}

	/** 재시작 시 DB 의 최근 리포트로 캐시 복원 */
	@PostConstruct
	void loadLatest() {
		for (AiReportRow r : recorder.aiReports(LocalDateTime.now().minusDays(2), 50)) {
			StoredReport s = toStored(r);
			latest.putIfAbsent(key(s.symbol()), s);
		}
	}

	@Scheduled(initialDelayString = "${app.ai.initial-delay-ms:90000}", fixedDelayString = "${app.ai.interval-ms:3600000}")
	public void scheduledGenerate() {
		generateAll();
	}

	/** 수동 재생성 (1분 쿨다운) */
	public Result regenerate() {
		long wait = REGENERATE_COOLDOWN - (System.currentTimeMillis() - lastGeneratedAt);
		if (wait > 0) {
			return new Result(false, "%d초 후에 다시 시도해 주세요.".formatted(wait / 1000 + 1));
		}
		int n = generateAll();
		return n > 0 ? new Result(true, "리포트 %d건을 생성했습니다.".formatted(n))
				: new Result(false, "아직 데이터 수집 중이라 분석할 수 없습니다.");
	}

	public synchronized int generateAll() {
		if (!inputs.isReady()) {
			log.info("AI 분석 건너뜀: 시세/OI 수집 전");
			return 0;
		}
		lastGeneratedAt = System.currentTimeMillis();
		int count = 0;
		try {
			var in = inputs.market();
			save(null, "MARKET", generator.analyzeMarket(in), in);
			count++;
		} catch (RuntimeException e) {
			log.warn("시장 분석 실패: {}", e.toString());
		}
		for (String s : symbols) {
			try {
				var in = inputs.symbol(s);
				save(s, "SYMBOL", generator.analyzeSymbol(in), in);
				count++;
			} catch (RuntimeException e) {
				log.warn("{} 분석 실패: {}", s, e.toString());
			}
		}
		log.info("AI 분석 리포트 {}건 생성 ({})", count, generator.modelName());
		return count;
	}

	private void save(String symbol, String type, AnalysisReport report, Object input) {
		LocalDateTime now = LocalDateTime.now();
		AiReportRow row = new AiReportRow();
		row.setSymbolId(symbol == null ? null : recorder.symbolId(symbol));
		row.setSymbol(symbol);
		row.setReportType(type);
		row.setSummary(report.summary());
		row.setScenarios(json.writeValueAsString(
				Map.of("items", report.scenarios(), "overheatReason", report.overheatReason())));
		row.setOverheatLevel(report.overheatLevel());
		row.setRisks(json.writeValueAsString(report.risks()));
		row.setInputJson(json.writeValueAsString(input));
		row.setModel(generator.modelName());
		row.setCreatedAt(now);
		recorder.saveAiReport(row); // DB 실패해도 화면용 캐시는 갱신
		latest.put(key(symbol), new StoredReport(row.getReportId(), symbol, type, report, generator.modelName(),
				toEpoch(now), row.getInputJson()));
	}

	public StoredReport latestMarket() {
		return latest.get(MARKET_KEY);
	}

	public StoredReport latestFor(String symbol) {
		return latest.get(symbol);
	}

	/** 즉석 분석 캐시 (저장 안 함): 심볼 → 리포트, 10분 유지 */
	private static final long ON_DEMAND_TTL = 10 * 60_000L;
	private final Map<String, StoredReport> onDemand = new ConcurrentHashMap<>();

	/**
	 * 정기 분석 대상(기본 종목)은 저장된 최신 리포트, 그 외 종목은 무료 엔진일 때만 즉석 분석.
	 * 즉석 분석은 DB 에 저장하지 않고 10분간 캐시 (id == null 로 구분).
	 */
	public StoredReport reportFor(String symbol) {
		StoredReport saved = latest.get(symbol);
		if (saved != null || symbols.contains(symbol) || !generator.isFree() || !inputs.isReady()) {
			return saved;
		}
		long now = System.currentTimeMillis();
		StoredReport c = onDemand.get(symbol);
		if (c != null && now - c.createdAt() < ON_DEMAND_TTL) {
			return c;
		}
		try {
			var in = inputs.symbol(symbol);
			StoredReport r = new StoredReport(null, symbol, "SYMBOL", generator.analyzeSymbol(in),
					generator.modelName(), now, json.writeValueAsString(in));
			if (onDemand.size() > 100) {
				onDemand.clear();
			}
			onDemand.put(symbol, r);
			return r;
		} catch (RuntimeException e) {
			log.warn("{} 즉석 분석 실패: {}", symbol, e.toString());
			return c;
		}
	}

	/** 최근 시장 리포트 이력 (과열 수준 변화 추적용) */
	public List<StoredReport> marketHistory(int limit) {
		List<StoredReport> out = new ArrayList<>();
		for (AiReportRow r : recorder.aiReports(LocalDateTime.now().minusDays(7), limit * 4)) {
			if ("MARKET".equals(r.getReportType()) && out.size() < limit) {
				out.add(toStored(r));
			}
		}
		return out;
	}

	public String generatorDescription() {
		return generator.description();
	}

	public String modelName() {
		return generator.modelName();
	}

	// ------------------------------------------------------------------

	private static String key(String symbol) {
		return symbol == null ? MARKET_KEY : symbol;
	}

	private StoredReport toStored(AiReportRow r) {
		List<Scenario> scenarios = List.of();
		String reason = "";
		try {
			JsonNode node = json.readTree(r.getScenarios());
			scenarios = json.convertValue(node.path("items"), new TypeReference<List<Scenario>>() {});
			reason = node.path("overheatReason").asString("");
		} catch (RuntimeException e) {
			log.debug("시나리오 JSON 파싱 실패 report={}", r.getReportId());
		}
		List<String> risks = List.of();
		try {
			risks = json.readValue(r.getRisks(), new TypeReference<List<String>>() {});
		} catch (RuntimeException e) {
			log.debug("위험 요소 JSON 파싱 실패 report={}", r.getReportId());
		}
		AnalysisReport rep = new AnalysisReport(r.getSummary(), scenarios, r.getOverheatLevel(), reason, risks);
		return new StoredReport(r.getReportId(), r.getSymbol(), r.getReportType(), rep, r.getModel(),
				r.getCreatedAt() == null ? 0 : toEpoch(r.getCreatedAt()), r.getInputJson());
	}

	private static long toEpoch(LocalDateTime t) {
		return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	public record StoredReport(Long id, String symbol, String type, AnalysisReport report, String model,
			long createdAt, String inputJson) {

		/** 과열 수준 → 색상 키 */
		public String levelColor() {
			return switch (report.overheatLevel()) {
				case "과열" -> "danger";
				case "주의" -> "warning";
				case "냉각" -> "info";
				default -> "secondary";
			};
		}
	}

	public record Result(boolean ok, String message) {
	}
}
