package com.coin.f.storage;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.coin.f.market.MarketModels.Kline;
import com.coin.f.market.MarketModels.Liquidation;
import com.coin.f.storage.Rows.CandleRow;
import com.coin.f.storage.Rows.FundingRow;
import com.coin.f.storage.Rows.FundingStat;
import com.coin.f.storage.Rows.SymbolTimeValue;
import com.coin.f.storage.Rows.LiquidationRow;
import com.coin.f.storage.Rows.LongShortRow;
import com.coin.f.storage.Rows.OpenInterestRow;
import com.coin.f.storage.Rows.SignalRow;
import com.coin.f.storage.Rows.SymbolId;
import com.coin.f.storage.Rows.SymbolRow;
import com.coin.f.storage.Rows.TimeValue;

/**
 * 시장 데이터 저장/조회 (SQL: resources/mapper/MarketDataMapper.xml)
 * 수집 데이터는 같은 키로 다시 들어올 수 있으므로 대부분 UPSERT.
 */
@Mapper
public interface MarketDataMapper {

	// ---- 종목
	int upsertSymbols(@Param("list") List<SymbolRow> rows);

	List<SymbolId> selectSymbolIds(@Param("exchange") String exchange);

	// ---- 수집 데이터
	int upsertCandles(@Param("symbolId") int symbolId, @Param("interval") String interval,
			@Param("list") List<Kline> klines);

	int upsertFundingRates(@Param("list") List<FundingRow> rows);

	int upsertOpenInterest(@Param("list") List<OpenInterestRow> rows);

	int upsertLongShort(@Param("list") List<LongShortRow> rows);

	int insertLiquidations(@Param("list") List<LiquidationRow> rows);

	/** 재시작 시 메모리 복원용 (시간 오름차순) */
	List<Liquidation> selectLiquidationsSince(@Param("since") long since);

	// ---- 추이 조회 (시간 오름차순)
	List<TimeValue> selectCloseHistory(@Param("symbolId") int symbolId, @Param("interval") String interval,
			@Param("since") long since);

	List<TimeValue> selectOpenInterestHistory(@Param("symbolId") int symbolId, @Param("period") String period,
			@Param("since") long since);

	List<TimeValue> selectFundingHistory(@Param("symbolId") int symbolId, @Param("since") long since);

	List<TimeValue> selectLongShortHistory(@Param("symbolId") int symbolId, @Param("ratioType") String ratioType,
			@Param("period") String period, @Param("since") long since);

	/** 전 종목 OI 이력 (since 이후) */
	List<SymbolTimeValue> selectOpenInterestSince(@Param("period") String period, @Param("since") long since);

	/** 종목별 직전 정산 펀딩비 + since 이후 평균 */
	List<FundingStat> selectFundingStats(@Param("since") long since);

	// ---- 시그널
	int insertSignal(SignalRow row);

	int countSignalsSince(@Param("symbolId") int symbolId, @Param("signalType") String signalType,
			@Param("since") long since);

	// ---- AI 리포트
	int insertAiReport(AiReportRow row);

	/** 최신순. symbol 은 JOIN 으로 채움 (시장 리포트는 null) */
	List<AiReportRow> selectAiReports(@Param("since") java.time.LocalDateTime since, @Param("limit") int limit);

	/** 시그널 이력 (최신순) */
	List<SignalHistoryRow> selectSignalHistory(@Param("since") long since, @Param("limit") int limit);

	/** 발생 후 가격(1h/4h/24h) 중 아직 비어 있고 시점이 지난 시그널 */
	List<SignalHistoryRow> selectSignalsPendingOutcome(@Param("now") long now, @Param("since") long since,
			@Param("limit") int limit);

	int updateSignalOutcome(@Param("signalId") long signalId, @Param("price1h") Double price1h,
			@Param("price4h") Double price4h, @Param("price24h") Double price24h);

	/** market_signal 결과 컬럼(price_1h/4h/24h) 존재 개수 — 3 이면 ALTER 적용됨 */
	int countSignalOutcomeColumns();

	// ---- 백테스트 입력 (전 종목, 시간 오름차순)
	List<CandleRow> selectCandlesSince(@Param("interval") String interval, @Param("since") long since);

	List<SymbolTimeValue> selectLongShortSince(@Param("ratioType") String ratioType, @Param("period") String period,
			@Param("since") long since);

	List<SymbolTimeValue> selectFundingSince(@Param("since") long since);

	// ---- 백테스트 실행/결과
	int insertBacktestRun(BacktestRunRow row);

	int updateBacktestRunStatus(@Param("runId") long runId, @Param("status") String status);

	int insertBacktestResult(@Param("runId") long runId, @Param("signalCount") int signalCount,
			@Param("winCount") int winCount, @Param("winRate") double winRate, @Param("avgReturn") double avgReturn,
			@Param("totalReturn") double totalReturn, @Param("maxDrawdown") double maxDrawdown,
			@Param("periodJson") String periodJson);

	/** 최근 실행 목록 (결과 요약 포함, period_json 제외) */
	List<BacktestRunRow> selectBacktestRuns(@Param("limit") int limit);

	/** 실행 1건 (period_json 포함) */
	BacktestRunRow selectBacktestRun(@Param("runId") long runId);
}
