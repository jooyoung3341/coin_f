package com.coin.f.market.binance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.coin.f.market.LiquidationStore;
import com.coin.f.market.MarketModels.Liquidation;
import com.coin.f.storage.MarketDataRecorder;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 전 종목 강제청산 실시간 스트림 (wss://fstream.binance.com/market/ws/!forceOrder@arr)
 *
 * 주의: 바이낸스는 종목별로 1초에 최대 1건(가장 큰 청산)만 푸시한다.
 * 따라서 집계 금액은 실제 청산 총액보다 작게 나오는 "표본" 값이다.
 */
@Component
@ConditionalOnProperty(name = "app.market.source", havingValue = "binance", matchIfMissing = true)
public class BinanceLiquidationStream {

	private static final Logger log = LoggerFactory.getLogger(BinanceLiquidationStream.class);

	private final URI uri;
	private final JsonMapper json;
	private final LiquidationStore store;
	private final ExecutorService httpExecutor = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "binance-liq-http");
		t.setDaemon(true); // 종료 시 JVM 을 붙잡지 않도록
		return t;
	});
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.executor(httpExecutor)
			.build();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "binance-liq-ws");
		t.setDaemon(true);
		return t;
	});

	private volatile WebSocket socket;
	private volatile boolean stopped;
	private volatile long lastMessageAt;
	private int retry;

	private final BinanceMarketDataProvider provider;
	private final MarketDataRecorder recorder;

	public BinanceLiquidationStream(@Value("${app.binance.liquidation-ws-url}") String url, JsonMapper json,
			LiquidationStore store, BinanceMarketDataProvider provider, MarketDataRecorder recorder) {
		this.uri = URI.create(url);
		this.json = json;
		this.store = store;
		this.provider = provider;
		this.recorder = recorder;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		connect();
		// 바이낸스는 24시간마다 연결을 끊음 + 무응답 감시: 10분간 메시지가 없으면 재연결
		scheduler.scheduleWithFixedDelay(() -> {
			WebSocket s = socket;
			if (s != null && System.currentTimeMillis() - lastMessageAt > Duration.ofMinutes(10).toMillis()) {
				log.warn("청산 스트림 10분간 무응답 → 재연결");
				dropAndReconnect(s);
			}
		}, 1, 1, TimeUnit.MINUTES);
	}

	private void connect() {
		if (stopped) {
			return;
		}
		http.newWebSocketBuilder().buildAsync(uri, new Listener()).whenComplete((ws, err) -> {
			if (err != null) {
				log.warn("청산 스트림 연결 실패: {}", err.toString());
				scheduleReconnect();
			} else {
				socket = ws;
				retry = 0;
				lastMessageAt = System.currentTimeMillis();
				log.info("청산 스트림 연결됨: {}", uri);
			}
		});
	}

	/** 현재 소켓일 때만 한 번 재연결 (중복 재연결 방지) */
	private synchronized void dropAndReconnect(WebSocket ws) {
		if (ws != socket) {
			return;
		}
		socket = null;
		ws.abort();
		scheduleReconnect();
	}

	private void scheduleReconnect() {
		if (stopped) {
			return;
		}
		long delay = Math.min(60, 1L << Math.min(retry++, 6)); // 1,2,4..최대 60초
		scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
	}

	@PreDestroy
	public void stop() {
		stopped = true;
		if (socket != null) {
			socket.abort();
		}
		scheduler.shutdownNow();
		httpExecutor.shutdownNow(); // HttpClient.shutdownNow() 는 Java 21+ 라 사용하지 않음 (프로젝트는 17)
	}

	private void handle(String text) {
		try {
			JsonNode o = json.readTree(text).path("o");
			String symbol = o.path("s").asString();
			if (!provider.isVisible(symbol)) {
				return; // 주식·원자재(TradFi) 선물, 원화 시가총액 기준 미달 종목 제외
			}
			double price = o.path("ap").asDouble(0) > 0 ? o.path("ap").asDouble() : o.path("p").asDouble();
			double qty = o.path("z").asDouble(0) > 0 ? o.path("z").asDouble() : o.path("q").asDouble();
			Liquidation liq = new Liquidation(symbol, o.path("S").asString(), price, qty, price * qty,
					o.path("T").asLong());
			store.add(liq);
			recorder.queueLiquidation(liq);
		} catch (RuntimeException e) {
			log.debug("청산 메시지 파싱 실패: {}", text, e);
		}
	}

	private class Listener implements WebSocket.Listener {
		private final StringBuilder buf = new StringBuilder();

		@Override
		public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
			buf.append(data);
			if (last) {
				lastMessageAt = System.currentTimeMillis();
				handle(buf.toString());
				buf.setLength(0);
			}
			ws.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
			log.info("청산 스트림 종료 ({} {}) → 재연결", statusCode, reason);
			dropAndReconnect(ws);
			return null;
		}

		@Override
		public void onError(WebSocket ws, Throwable error) {
			log.warn("청산 스트림 오류: {} → 재연결", error.toString());
			dropAndReconnect(ws);
		}
	}
}
