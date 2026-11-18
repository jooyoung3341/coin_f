package com.coin.f.market;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.coin.f.market.MarketModels.Liquidation;
import com.coin.f.market.MarketModels.LiquidationStats;
import com.coin.f.storage.MarketDataRecorder;

import jakarta.annotation.PostConstruct;

/**
 * 최근 24시간 청산 이벤트 메모리 보관소 (화면 집계용).
 * 영구 이력은 liquidation 테이블에 저장되며, 서버 시작 시 최근 24시간을 DB 에서 다시 채운다.
 */
@Component
public class LiquidationStore {

	private static final Logger log = LoggerFactory.getLogger(LiquidationStore.class);
	private static final long KEEP_MILLIS = 24 * 3_600_000L;
	private static final int MAX_EVENTS = 200_000;

	private final Deque<Liquidation> events = new ArrayDeque<>();
	private final MarketDataRecorder recorder;
	private volatile long collectingSince = System.currentTimeMillis();
	/** 노출 대상 종목 필터 (시가총액 기준) — 공급자가 설정, 기본은 전부 통과 */
	private volatile Predicate<String> symbolFilter = s -> true;

	public LiquidationStore(MarketDataRecorder recorder) {
		this.recorder = recorder;
	}

	public void setSymbolFilter(Predicate<String> filter) {
		this.symbolFilter = filter;
	}

	/** WebSocket 연결(ApplicationReady) 전에 실행됨 */
	@PostConstruct
	void warmUpFromDb() {
		List<Liquidation> saved = recorder.loadLiquidationsSince(System.currentTimeMillis() - KEEP_MILLIS);
		if (saved.isEmpty()) {
			return;
		}
		synchronized (this) {
			saved.forEach(events::addLast);
			// 서버가 꺼져 있던 구간은 비어 있을 수 있음 → 근사값
			collectingSince = saved.get(0).time();
		}
		log.info("DB 에서 최근 24시간 청산 {}건 복원", saved.size());
	}

	public synchronized void add(Liquidation liq) {
		events.addLast(liq);
		long cutoff = System.currentTimeMillis() - KEEP_MILLIS;
		while (!events.isEmpty() && (events.peekFirst().time() < cutoff || events.size() > MAX_EVENTS)) {
			events.pollFirst();
		}
	}

	public LiquidationStats stats(long windowMillis) {
		return stats(windowMillis, null);
	}

	/** @param symbol null 이면 전체 */
	public synchronized LiquidationStats stats(long windowMillis, String symbol) {
		long cutoff = System.currentTimeMillis() - windowMillis;
		double longUsd = 0;
		double shortUsd = 0;
		int count = 0;
		for (Iterator<Liquidation> it = events.descendingIterator(); it.hasNext();) {
			Liquidation l = it.next();
			if (l.time() < cutoff) {
				break;
			}
			if ((symbol != null && !symbol.equals(l.symbol())) || !symbolFilter.test(l.symbol())) {
				continue;
			}
			if (l.isLong()) {
				longUsd += l.valueUsd();
			} else {
				shortUsd += l.valueUsd();
			}
			count++;
		}
		return new LiquidationStats(longUsd, shortUsd, count, windowMillis, collectingSince);
	}

	/** since 이후 이벤트 복사본 (시간 오름차순) */
	public synchronized List<Liquidation> since(long since) {
		List<Liquidation> out = new ArrayList<>();
		for (Iterator<Liquidation> it = events.descendingIterator(); it.hasNext();) {
			Liquidation l = it.next();
			if (l.time() < since) {
				break;
			}
			if (symbolFilter.test(l.symbol())) {
				out.add(l);
			}
		}
		java.util.Collections.reverse(out);
		return out;
	}

	public long collectingSince() {
		return collectingSince;
	}

	/** 최신순 */
	public synchronized List<Liquidation> recent(int limit) {
		List<Liquidation> out = new ArrayList<>(Math.min(limit, events.size()));
		for (Iterator<Liquidation> it = events.descendingIterator(); it.hasNext() && out.size() < limit;) {
			Liquidation l = it.next();
			if (symbolFilter.test(l.symbol())) {
				out.add(l);
			}
		}
		return out;
	}
}
