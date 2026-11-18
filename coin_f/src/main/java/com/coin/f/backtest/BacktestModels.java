package com.coin.f.backtest;

import java.util.List;
import java.util.NavigableMap;
import java.util.Map;

import com.coin.f.signal.MarketSignal.SignalType;

/**
 * 백테스트 입력/출력 모델.
 * 수익률은 모두 % 단위 (1.5 = +1.5%). 포지션 크기는 매 거래 동일(복리 미적용)로 본다.
 */
public final class BacktestModels {

	private BacktestModels() {
	}

	/** 진입 방향 규칙 */
	public enum Mode {
		/** 쏠린 쪽의 반대로 진입 (롱 쏠림 → 숏) */
		CONTRARIAN("역추세 (쏠림 반대)"),
		/** 쏠린 쪽을 따라 진입 (롱 쏠림 → 롱) */
		FOLLOW("추세 추종 (쏠림 방향)");

		private final String label;

		Mode(String label) {
			this.label = label;
		}

		public String getLabel() { return label; }
	}

	/**
	 * 백테스트 조건
	 * @param signalType null = 재현 가능한 전체 유형
	 * @param symbol null = 전체 종목
	 * @param takeProfitPct 익절 % (null = 미사용)
	 * @param stopLossPct 손절 % (null = 미사용)
	 * @param feePct 왕복 수수료 % (진입+청산)
	 */
	public record Params(SignalType signalType, String symbol, int days, int holdHours, Mode mode,
			Double takeProfitPct, Double stopLossPct, double feePct) {

		public static Params defaults() {
			return new Params(null, null, 30, 4, Mode.CONTRARIAN, null, null, 0.08);
		}

		public Params withHold(int hours) {
			return new Params(signalType, symbol, days, hours, mode, takeProfitPct, stopLossPct, feePct);
		}
	}

	/** 1시간봉 */
	public record Bar(long time, double open, double high, double low, double close) {
	}

	/**
	 * 종목별 시계열 (key = epoch ms, 1시간 단위로 정렬된 값)
	 * @param longPct 롱 계정 비율 %
	 * @param funding 정산 시각 → 정산 1회 펀딩비
	 */
	public record SymbolSeries(String symbol, Map<Long, Bar> bars, Map<Long, Double> oiUsd,
			Map<Long, Double> longPct, NavigableMap<Long, Double> funding) {
	}

	/**
	 * 개별 거래
	 * @param crowdedSide 시그널이 판단한 쏠림 (LONG/SHORT)
	 * @param position 실제 진입 방향 (LONG/SHORT)
	 * @param exitReason TIME(보유시간 만료) / TP(익절) / SL(손절)
	 */
	public record Trade(String symbol, SignalType type, String level, String crowdedSide, String position,
			long signalTime, long entryTime, double entryPrice, long exitTime, double exitPrice, String exitReason,
			double returnPct) {

		public boolean win() {
			return returnPct > 0;
		}
	}

	/**
	 * 성과 요약
	 * @param totalReturn 거래별 수익률 단순 합 (%p)
	 * @param maxDrawdown 누적 수익률 곡선의 고점 대비 최대 하락 (%p, 양수)
	 * @param profitFactor 총이익 / 총손실 (손실 없으면 null)
	 */
	public record Summary(int count, int wins, double winRate, double avgReturn, double totalReturn,
			double maxDrawdown, Double profitFactor, double avgWin, double avgLoss, double best, double worst) {

		public static Summary empty() {
			return new Summary(0, 0, 0, 0, 0, 0, null, 0, 0, 0, 0);
		}
	}

	/** 그룹별 성과 (주간 · 유형별 · 종목별 · 보유시간별) */
	public record GroupStat(String key, String label, int count, int wins, double winRate, double avgReturn,
			double totalReturn) {
	}

	/** 누적 수익 곡선 점 (청산 시각, 누적 %p) */
	public record EquityPoint(long time, double value) {
	}

	/**
	 * 백테스트 결과 (backtest_result.period_json 에 통째로 저장)
	 * @param from 검증 구간 시작 (데이터 기준), to 끝
	 * @param symbolsCovered 데이터가 있었던 종목 수
	 * @param openSkipped 보유 기간이 데이터 끝을 넘어 제외된 시그널 수
	 */
	public record Result(Long runId, Params params, long from, long to, int symbolsCovered, Summary summary,
			List<GroupStat> weekly, List<GroupStat> byType, List<GroupStat> bySymbol, List<GroupStat> byHold,
			List<EquityPoint> equity, List<Trade> trades, int openSkipped, long createdAt) {

		public Result withRunId(Long id) {
			return new Result(id, params, from, to, symbolsCovered, summary, weekly, byType, bySymbol, byHold,
					equity, trades, openSkipped, createdAt);
		}
	}
}
