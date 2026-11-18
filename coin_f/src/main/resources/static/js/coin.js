/* Futures Insight - 코인 상세 분석 */
(function () {
	'use strict';

	const symbol = window.COIN_CFG.symbol;
	const C = FI.colors;
	const BORDER = '#1f2734';
	const UP_A = 'rgba(22,199,132,0.45)';
	const DOWN_A = 'rgba(234,57,67,0.45)';
	// lightweight-charts 는 UTC 로 표시 → 브라우저 시간대로 이동
	const TZ = -new Date().getTimezoneOffset() * 60;

	const p2 = n => String(n).padStart(2, '0');
	const fmtHour = ms => { const d = new Date(ms); return `${d.getMonth() + 1}/${d.getDate()} ${p2(d.getHours())}시`; };
	const pct = (v, digits = 2) => (v > 0 ? '+' : '') + v.toFixed(digits) + '%';
	const tone = v => v > 0 ? 'text-up' : v < 0 ? 'text-down' : 'text-muted';

	/* ============================================================ 캔들 */
	const candleEl = document.getElementById('candleChart');
	const chart = LightweightCharts.createChart(candleEl, {
		autoSize: true,
		layout: { background: { type: 'solid', color: 'transparent' }, textColor: C.muted, fontFamily: 'Pretendard, sans-serif' },
		grid: { vertLines: { color: C.grid }, horzLines: { color: C.grid } },
		rightPriceScale: { borderColor: BORDER },
		timeScale: { borderColor: BORDER, timeVisible: true, secondsVisible: false },
		crosshair: { mode: LightweightCharts.CrosshairMode.Normal }
	});
	const candles = chart.addCandlestickSeries({
		upColor: C.up, downColor: C.down, borderVisible: false, wickUpColor: C.up, wickDownColor: C.down
	});
	candles.priceScale().applyOptions({ scaleMargins: { top: 0.06, bottom: 0.24 } });
	const volume = chart.addHistogramSeries({
		priceFormat: { type: 'volume' }, priceScaleId: 'vol', lastValueVisible: false, priceLineVisible: false
	});
	chart.priceScale('vol').applyOptions({ scaleMargins: { top: 0.8, bottom: 0 } });

	let interval = document.querySelector('#candleInterval .active').dataset.interval;
	let lastCandleTime = 0;

	const toCandle = r => ({ time: r.openTime / 1000 + TZ, open: r.open, high: r.high, low: r.low, close: r.close });
	const toVol = r => ({ time: r.openTime / 1000 + TZ, value: r.quoteVolume, color: r.close >= r.open ? UP_A : DOWN_A });

	async function loadCandles(reset) {
		let rows;
		try {
			const res = await fetch(`/api/market/klines?symbol=${symbol}&interval=${interval}&limit=300`);
			if (!res.ok) return;
			rows = await res.json();
		} catch (e) { return; }
		if (!rows.length) return;

		if (reset) {
			const last = rows[rows.length - 1].close;
			const precision = last >= 1000 ? 1 : last >= 10 ? 2 : 4;
			candles.applyOptions({ priceFormat: { type: 'price', precision, minMove: 1 / 10 ** precision } });
			candles.setData(rows.map(toCandle));
			volume.setData(rows.map(toVol));
			chart.timeScale().setVisibleLogicalRange({ from: rows.length - 120, to: rows.length + 3 });
		} else {
			// 주기 갱신: 스크롤 위치를 유지하도록 마지막 캔들들만 update
			rows.filter(r => r.openTime >= lastCandleTime).forEach(r => { candles.update(toCandle(r)); volume.update(toVol(r)); });
		}
		lastCandleTime = rows[rows.length - 1].openTime;
		renderTaker(rows);
	}

	document.getElementById('candleInterval').addEventListener('click', e => {
		const btn = e.target.closest('button');
		if (!btn) return;
		document.querySelectorAll('#candleInterval button').forEach(b => b.classList.toggle('active', b === btn));
		interval = btn.dataset.interval;
		loadCandles(true);
	});
	setInterval(() => loadCandles(false), 10_000);

	/* ============================================================ 매수·매도 */
	const takerChart = new Chart(document.getElementById('takerChart'), {
		type: 'bar',
		data: { labels: [], datasets: [
			{ label: '시장가 매수', data: [], backgroundColor: UP_A, stack: 's' },
			{ label: '시장가 매도', data: [], backgroundColor: DOWN_A, stack: 's' }
		] },
		options: {
			responsive: true, maintainAspectRatio: false, animation: false,
			interaction: { mode: 'index', intersect: false },
			plugins: {
				legend: { display: false },
				tooltip: { callbacks: { label: c => ` ${c.dataset.label} ${FI.fmtUsd(Math.abs(c.parsed.y))}` } }
			},
			scales: {
				x: { stacked: true, grid: { display: false }, ticks: { color: C.muted, maxTicksLimit: 8, maxRotation: 0 } },
				y: { stacked: true, grid: { color: C.grid }, ticks: { color: C.muted, callback: v => FI.fmtUsd(Math.abs(v)) } }
			}
		}
	});

	function renderTaker(rows) {
		const recent = rows.slice(-60);
		const buys = recent.map(r => r.takerBuyVolume * r.close);
		const sells = recent.map(r => (r.volume - r.takerBuyVolume) * r.close);
		takerChart.data.labels = recent.map(r => {
			const d = new Date(r.openTime);
			return interval === '1h' ? `${d.getDate()}일 ${p2(d.getHours())}시` : `${p2(d.getHours())}:${p2(d.getMinutes())}`;
		});
		takerChart.data.datasets[0].data = buys;
		takerChart.data.datasets[1].data = sells.map(v => -v); // 아래로 대칭 표시
		takerChart.update();

		const b = buys.reduce((a, x) => a + x, 0);
		const s = sells.reduce((a, x) => a + x, 0);
		const share = b + s > 0 ? b / (b + s) * 100 : 50;
		document.getElementById('takerSummary').innerHTML =
			`<span class="text-up">매수 ${share.toFixed(1)}%</span> · <span class="text-down">매도 ${(100 - share).toFixed(1)}%</span>` +
			`<span class="text-muted"> · 순매수 ${b - s >= 0 ? '+' : '-'}${FI.fmtUsd(Math.abs(b - s))}</span>`;
	}

	/* ============================================================ 추이 */
	const baseOpts = (yTicks) => ({
		responsive: true, maintainAspectRatio: false, animation: false,
		interaction: { mode: 'index', intersect: false },
		plugins: { legend: { display: false } },
		scales: {
			x: { grid: { display: false }, ticks: { color: C.muted, maxTicksLimit: 7, maxRotation: 0 } },
			y: { grid: { color: C.grid }, ticks: { color: C.muted, callback: yTicks } }
		}
	});

	const oiOpts = baseOpts(v => FI.fmtUsd(v));
	oiOpts.scales.p = { position: 'right', grid: { display: false }, ticks: { color: C.muted, callback: v => FI.fmtPrice(v) } };
	oiOpts.plugins.tooltip = { callbacks: { label: c => c.dataset.yAxisID === 'p' ? ` 가격 ${FI.fmtPrice(c.parsed.y)}` : ` OI ${FI.fmtUsd(c.parsed.y)}` } };
	const oiChart = new Chart(document.getElementById('oiChart'), {
		type: 'line',
		data: { labels: [], datasets: [
			{ label: 'OI', data: [], yAxisID: 'y', borderColor: C.accent, backgroundColor: 'rgba(240,185,11,0.12)', fill: true, borderWidth: 1.5, pointRadius: 0, tension: 0.2 },
			{ label: '가격', data: [], yAxisID: 'p', borderColor: 'rgba(230,233,239,0.75)', borderWidth: 1.2, pointRadius: 0, tension: 0.2, spanGaps: true }
		] },
		options: oiOpts
	});

	const fundingOpts = baseOpts(v => v.toFixed(3) + '%');
	fundingOpts.plugins.tooltip = { callbacks: { label: c => ` 펀딩비 ${pct(c.parsed.y, 4)}` } };
	const fundingChart = new Chart(document.getElementById('fundingChart'), {
		type: 'bar',
		data: { labels: [], datasets: [{ label: '펀딩비', data: [], backgroundColor: [] }] },
		options: fundingOpts
	});

	const lsOpts = baseOpts(v => v + '%');
	lsOpts.plugins.tooltip = { filter: i => i.datasetIndex === 0, callbacks: { label: c => ` 롱 ${c.parsed.y.toFixed(1)}% / 숏 ${(100 - c.parsed.y).toFixed(1)}%` } };
	const lsChart = new Chart(document.getElementById('lsChart'), {
		type: 'line',
		data: { labels: [], datasets: [
			{ label: '롱 비율', data: [], borderColor: C.up, backgroundColor: 'rgba(22,199,132,0.10)', fill: true, borderWidth: 1.5, pointRadius: 0, tension: 0.2 },
			{ label: '평상시', data: [], borderColor: 'rgba(138,147,166,0.5)', borderDash: [4, 4], borderWidth: 1, pointRadius: 0 },
			{ label: '과열', data: [], borderColor: 'rgba(234,57,67,0.6)', borderDash: [4, 4], borderWidth: 1, pointRadius: 0 }
		] },
		options: lsOpts
	});

	function setStat(id, html) { document.getElementById(id).innerHTML = html; }
	const EMPTY = '<span class="text-muted">데이터 수집 중</span>';

	async function loadHistory(days) {
		let h;
		try {
			const res = await fetch(`/api/coin/${symbol}/history?days=${days}`);
			if (!res.ok) return;
			h = await res.json();
		} catch (e) { return; }

		const srcEl = document.getElementById('historySource');
		if (srcEl) {
			srcEl.textContent = h.source === 'LIVE'
				? '1시간 단위 · Binance 실시간 조회 (5분 캐시, OI·롱숏은 최근 500시간까지)'
				: '1시간 단위 · DB 저장 데이터';
		}

		// OI + 가격 (같은 1시간 경계 시각으로 맞춤)
		const priceBy = new Map(h.price.map(p => [p.ts, p.value]));
		oiChart.data.labels = h.openInterest.map(p => fmtHour(p.ts));
		oiChart.data.datasets[0].data = h.openInterest.map(p => p.value);
		oiChart.data.datasets[1].data = h.openInterest.map(p => priceBy.has(p.ts) ? priceBy.get(p.ts) : null);
		oiChart.update();
		if (h.openInterest.length >= 2) {
			const first = h.openInterest[0].value, last = h.openInterest[h.openInterest.length - 1].value;
			const pFirst = h.price.length ? h.price[0].value : null, pLast = h.price.length ? h.price[h.price.length - 1].value : null;
			const oiChg = (last - first) / first * 100;
			const pChg = pFirst ? (pLast - pFirst) / pFirst * 100 : null;
			setStat('oiStat', `OI <span class="${tone(oiChg)}">${pct(oiChg)}</span>` +
				(pChg !== null ? ` · 가격 <span class="${tone(pChg)}">${pct(pChg)}</span>` : '') +
				`<div class="fi-hint">${oiPriceReading(oiChg, pChg)}</div>`);
		} else setStat('oiStat', EMPTY);

		// 펀딩비 (% 단위)
		const rates = h.funding.map(p => p.value * 100);
		fundingChart.data.labels = h.funding.map(p => fmtHour(p.ts));
		fundingChart.data.datasets[0].data = rates;
		fundingChart.data.datasets[0].backgroundColor = rates.map(v =>
			Math.abs(v) >= 0.03 ? (v > 0 ? C.up : C.down) : (v >= 0 ? UP_A : DOWN_A));
		fundingChart.update();
		if (rates.length) {
			const avg = rates.reduce((a, x) => a + x, 0) / rates.length;
			setStat('fundingStat', `최근 <span class="${tone(rates[rates.length - 1])}">${pct(rates[rates.length - 1], 4)}</span>` +
				`<div class="fi-hint">기간 평균 ${pct(avg, 4)} · ${rates.length}회 정산</div>`);
		} else setStat('fundingStat', EMPTY);

		// 롱숏
		const longs = h.longShort.map(p => p.value * 100);
		lsChart.data.labels = h.longShort.map(p => fmtHour(p.ts));
		lsChart.data.datasets[0].data = longs;
		lsChart.data.datasets[1].data = longs.map(() => 70);
		lsChart.data.datasets[2].data = longs.map(() => 80);
		lsChart.update();
		if (longs.length) {
			const lo = Math.min(...longs), hi = Math.max(...longs);
			setStat('lsStat', `현재 롱 <span class="text-up">${longs[longs.length - 1].toFixed(1)}%</span>` +
				`<div class="fi-hint">기간 범위 ${lo.toFixed(1)}% ~ ${hi.toFixed(1)}%</div>`);
		} else setStat('lsStat', EMPTY);
	}

	/** 가격·OI 동시 변화 해석 (교과서적 해석이며 확정 신호가 아님) */
	function oiPriceReading(oi, price) {
		if (price === null || Math.abs(oi) < 1) return 'OI 변화 미미';
		if (oi > 0 && price > 0) return '가격↑ OI↑ — 신규 롱 유입 주도 상승';
		if (oi > 0 && price <= 0) return '가격↓ OI↑ — 신규 숏 유입 주도 하락';
		if (oi < 0 && price > 0) return '가격↑ OI↓ — 숏 청산(숏 커버) 주도 상승';
		return '가격↓ OI↓ — 롱 청산·정리 주도 하락';
	}

	document.getElementById('historyRange').addEventListener('click', e => {
		const btn = e.target.closest('button');
		if (!btn) return;
		document.querySelectorAll('#historyRange button').forEach(b => b.classList.toggle('active', b === btn));
		loadHistory(+btn.dataset.days);
	});

	loadCandles(true);
	loadHistory(+document.querySelector('#historyRange .active').dataset.days);
})();
