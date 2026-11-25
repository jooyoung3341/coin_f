package com.coin.f.ai;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.coin.f.ai.AnalysisModels.IndicatorView;

/**
 * 코인 상세 "현재 지표 해석" — 요청 시 계산 + 종목별 1분 캐시.
 * 근거 데이터 갱신 주기(펀딩 10초, OI·롱숏 5분)보다 촘촘할 필요가 없고, 화면 자동 갱신(1분)과 맞춘다.
 */
@Service
public class IndicatorService {

	private static final Logger log = LoggerFactory.getLogger(IndicatorService.class);
	private static final long TTL = 60_000L;

	private final AnalysisInputBuilder inputs;
	private final IndicatorInterpreter interpreter;
	private final Map<String, IndicatorView> cache = new ConcurrentHashMap<>();

	public IndicatorService(AnalysisInputBuilder inputs, IndicatorInterpreter interpreter) {
		this.inputs = inputs;
		this.interpreter = interpreter;
	}

	/** 수집 전이면 null. 계산 실패 시 직전 값 */
	public IndicatorView viewFor(String symbol) {
		IndicatorView c = cache.get(symbol);
		long now = System.currentTimeMillis();
		if (c != null && now - c.at() < TTL) {
			return c;
		}
		if (!inputs.isReady()) {
			return c;
		}
		try {
			IndicatorView v = interpreter.interpret(inputs.symbol(symbol));
			if (cache.size() > 200) {
				cache.clear();
			}
			cache.put(symbol, v);
			return v;
		} catch (RuntimeException e) {
			log.warn("{} 지표 해석 실패: {}", symbol, e.toString());
			return c;
		}
	}
}
