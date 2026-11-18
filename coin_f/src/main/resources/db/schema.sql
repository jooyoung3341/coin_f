-- =============================================================
-- Futures Insight - 핵심 테이블 10종 (MySQL 8.x)
-- 자동 실행되지 않음. MySQL Workbench 등에서 직접 실행.
-- 시간 컬럼은 거래소 기준 epoch ms(BIGINT)로 저장 → 중복 방지/정렬에 유리
-- =============================================================

CREATE DATABASE IF NOT EXISTS futures_insight DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE futures_insight;

-- 1. 종목 정보
CREATE TABLE symbol (
    symbol_id      INT AUTO_INCREMENT PRIMARY KEY,
    exchange       VARCHAR(20)  NOT NULL COMMENT 'BINANCE, BYBIT',
    symbol         VARCHAR(30)  NOT NULL COMMENT 'BTCUSDT',
    base_asset     VARCHAR(20)  NOT NULL,
    quote_asset    VARCHAR(20)  NOT NULL,
    contract_type  VARCHAR(20)  NOT NULL DEFAULT 'PERPETUAL',
    is_active      TINYINT(1)   NOT NULL DEFAULT 1,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_symbol (exchange, symbol)
) COMMENT '종목 정보';

-- 2. 가격 데이터 (캔들)
CREATE TABLE price_candle (
    symbol_id          INT          NOT NULL,
    interval_code      VARCHAR(5)   NOT NULL COMMENT '1m,5m,15m,1h,4h,1d',
    open_time          BIGINT       NOT NULL,
    open_price         DECIMAL(24,8) NOT NULL,
    high_price         DECIMAL(24,8) NOT NULL,
    low_price          DECIMAL(24,8) NOT NULL,
    close_price        DECIMAL(24,8) NOT NULL,
    volume             DECIMAL(30,8) NOT NULL COMMENT '기초자산 수량',
    quote_volume       DECIMAL(30,8) NOT NULL COMMENT 'USDT 거래대금',
    taker_buy_volume   DECIMAL(30,8) NULL     COMMENT '시장가 매수 수량',
    trade_count        INT          NULL,
    PRIMARY KEY (symbol_id, interval_code, open_time),
    CONSTRAINT fk_candle_symbol FOREIGN KEY (symbol_id) REFERENCES symbol (symbol_id)
) COMMENT '캔들 및 거래량';

-- 3. 펀딩비 데이터
CREATE TABLE funding_rate (
    symbol_id      INT           NOT NULL,
    funding_time   BIGINT        NOT NULL,
    funding_rate   DECIMAL(12,8) NOT NULL COMMENT '0.0001 = 0.01%',
    mark_price     DECIMAL(24,8) NULL,
    PRIMARY KEY (symbol_id, funding_time),
    CONSTRAINT fk_funding_symbol FOREIGN KEY (symbol_id) REFERENCES symbol (symbol_id)
) COMMENT '펀딩비 이력';

-- 4. OI 데이터
CREATE TABLE open_interest (
    symbol_id      INT           NOT NULL,
    period         VARCHAR(5)    NOT NULL COMMENT '5m,15m,1h ...',
    ts             BIGINT        NOT NULL,
    oi_amount      DECIMAL(30,8) NOT NULL COMMENT '계약 수량',
    oi_value_usd   DECIMAL(30,4) NOT NULL,
    PRIMARY KEY (symbol_id, period, ts),
    CONSTRAINT fk_oi_symbol FOREIGN KEY (symbol_id) REFERENCES symbol (symbol_id)
) COMMENT '미결제약정 이력';

-- 5. 롱숏 데이터
CREATE TABLE long_short_ratio (
    symbol_id      INT           NOT NULL,
    ratio_type     VARCHAR(20)   NOT NULL COMMENT 'GLOBAL_ACCOUNT, TOP_ACCOUNT, TOP_POSITION, TAKER',
    period         VARCHAR(5)    NOT NULL,
    ts             BIGINT        NOT NULL,
    long_ratio     DECIMAL(8,4)  NOT NULL COMMENT '0.5240 = 52.4%',
    short_ratio    DECIMAL(8,4)  NOT NULL,
    long_short     DECIMAL(10,4) NOT NULL,
    PRIMARY KEY (symbol_id, ratio_type, period, ts),
    CONSTRAINT fk_ls_symbol FOREIGN KEY (symbol_id) REFERENCES symbol (symbol_id)
) COMMENT '롱·숏 비율';

-- 6. 청산 데이터
CREATE TABLE liquidation (
    liquidation_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol_id      INT           NOT NULL,
    side           VARCHAR(5)    NOT NULL COMMENT 'SELL=롱 청산, BUY=숏 청산',
    price          DECIMAL(24,8) NOT NULL,
    quantity       DECIMAL(30,8) NOT NULL,
    value_usd      DECIMAL(30,4) NOT NULL,
    event_time     BIGINT        NOT NULL,
    KEY idx_liq_symbol_time (symbol_id, event_time),
    KEY idx_liq_time (event_time),
    CONSTRAINT fk_liq_symbol FOREIGN KEY (symbol_id) REFERENCES symbol (symbol_id)
) COMMENT '청산 이벤트';

-- 7. 시그널 데이터
CREATE TABLE market_signal (
    signal_id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol_id      INT           NOT NULL,
    signal_type    VARCHAR(30)   NOT NULL COMMENT 'LONG_OVERHEAT, SHORT_OVERHEAT, OI_SURGE, FUNDING_OVERHEAT, MASS_LIQUIDATION, COMPOSITE',
    level          VARCHAR(10)   NOT NULL COMMENT 'HIGH, MEDIUM, LOW',
    direction      VARCHAR(10)   NULL     COMMENT '포지션이 몰린 쪽: LONG, SHORT, NEUTRAL (매매 추천 아님)',
    reason         VARCHAR(500)  NOT NULL COMMENT '발생 이유',
    evidence_json  JSON          NULL     COMMENT '관련 지표 스냅샷',
    price_at       DECIMAL(24,8) NOT NULL COMMENT '발생 시점 가격 (백테스트 기준)',
    detected_at    BIGINT        NOT NULL,
    price_1h       DECIMAL(24,8) NULL     COMMENT '발생 1시간 후 가격',
    price_4h       DECIMAL(24,8) NULL     COMMENT '발생 4시간 후 가격',
    price_24h      DECIMAL(24,8) NULL     COMMENT '발생 24시간 후 가격',
    KEY idx_signal_time (detected_at),
    KEY idx_signal_symbol_type (symbol_id, signal_type, detected_at),
    CONSTRAINT fk_signal_symbol FOREIGN KEY (symbol_id) REFERENCES symbol (symbol_id)
) COMMENT '매매 시그널 이력';

-- [6단계 변경] 이미 market_signal 을 만든 경우 아래 ALTER 로 컬럼 추가
-- ALTER TABLE market_signal
--     ADD COLUMN price_1h  DECIMAL(24,8) NULL COMMENT '발생 1시간 후 가격'  AFTER detected_at,
--     ADD COLUMN price_4h  DECIMAL(24,8) NULL COMMENT '발생 4시간 후 가격'  AFTER price_1h,
--     ADD COLUMN price_24h DECIMAL(24,8) NULL COMMENT '발생 24시간 후 가격' AFTER price_4h;

-- 8. AI 리포트
CREATE TABLE ai_report (
    report_id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol_id      INT           NULL     COMMENT 'NULL = 전체 시장',
    report_type    VARCHAR(20)   NOT NULL COMMENT 'MARKET, SYMBOL',
    summary        TEXT          NOT NULL COMMENT '시장 상황 요약',
    scenarios      TEXT          NULL     COMMENT '상승·하락 시나리오',
    overheat_level VARCHAR(10)   NULL,
    risks          TEXT          NULL     COMMENT '주요 위험 요소',
    input_json     JSON          NULL     COMMENT 'AI 에 전달한 지표',
    model          VARCHAR(50)   NOT NULL,
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_ai_created (created_at),
    CONSTRAINT fk_ai_symbol FOREIGN KEY (symbol_id) REFERENCES symbol (symbol_id)
) COMMENT 'AI 분석 결과';

-- 9. 백테스트 실행
CREATE TABLE backtest_run (
    run_id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol_id      INT           NULL,
    signal_type    VARCHAR(30)   NOT NULL,
    start_time     BIGINT        NOT NULL,
    end_time       BIGINT        NOT NULL,
    hold_minutes   INT           NOT NULL COMMENT '진입 후 보유 시간',
    take_profit    DECIMAL(8,4)  NULL,
    stop_loss      DECIMAL(8,4)  NULL,
    params_json    JSON          NULL,
    status         VARCHAR(10)   NOT NULL DEFAULT 'READY' COMMENT 'READY, RUNNING, DONE, FAILED',
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_bt_symbol FOREIGN KEY (symbol_id) REFERENCES symbol (symbol_id)
) COMMENT '백테스트 조건 및 실행 기록';

-- 10. 백테스트 결과
CREATE TABLE backtest_result (
    run_id          BIGINT        PRIMARY KEY,
    signal_count    INT           NOT NULL,
    win_count       INT           NOT NULL,
    win_rate        DECIMAL(8,4)  NOT NULL,
    avg_return      DECIMAL(10,4) NOT NULL COMMENT '%',
    total_return    DECIMAL(12,4) NOT NULL COMMENT '%',
    max_drawdown    DECIMAL(10,4) NOT NULL COMMENT 'MDD %',
    period_json     JSON          NULL     COMMENT '기간별(월/주) 성과',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_btr_run FOREIGN KEY (run_id) REFERENCES backtest_run (run_id)
) COMMENT '백테스트 수익률 및 성과';

-- 종목(symbol) 데이터는 서버 시작 시 바이낸스 거래 가능 종목으로 자동 동기화되므로 INSERT 불필요
