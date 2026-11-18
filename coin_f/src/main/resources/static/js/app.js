/* Futures Insight - 공통 스크립트 */
(function () {
	'use strict';

	const css = getComputedStyle(document.documentElement);
	const C = {
		accent: css.getPropertyValue('--fi-accent').trim() || '#f0b90b',
		up: css.getPropertyValue('--fi-up').trim() || '#16c784',
		down: css.getPropertyValue('--fi-down').trim() || '#ea3943',
		muted: css.getPropertyValue('--fi-muted').trim() || '#8a93a6',
		grid: 'rgba(255,255,255,0.05)'
	};

	// 상단 시계 (KST 기준 브라우저 로컬 시간)
	const clock = document.getElementById('fiClock');
	if (clock) {
		const tick = () => clock.textContent = new Date().toLocaleTimeString('ko-KR', { hour12: false });
		tick();
		setInterval(tick, 1000);
	}

	function fmtTime(ms, interval) {
		const d = new Date(ms);
		const p = n => String(n).padStart(2, '0');
		const hm = p(d.getHours()) + ':' + p(d.getMinutes());
		if (interval === '1m' || interval === '5m' || interval === '15m') return hm;
		return (d.getMonth() + 1) + '/' + d.getDate() + ' ' + hm;
	}

	// 서버 Formatters 와 동일한 규칙
	function fmtPrice(v) {
		if (v >= 1000) return v.toLocaleString('en-US', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
		if (v >= 10) return v.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
		if (v >= 1) return v.toFixed(3);
		return v.toFixed(5);
	}

	function fmtUsd(v) {
		const a = Math.abs(v);
		if (a >= 1e12) return '$' + (v / 1e12).toFixed(2) + 'T';
		if (a >= 1e9) return '$' + (v / 1e9).toFixed(2) + 'B';
		if (a >= 1e6) return '$' + (v / 1e6).toFixed(1) + 'M';
		if (a >= 1e3) return '$' + (v / 1e3).toFixed(1) + 'K';
		return '$' + v.toFixed(2);
	}

	function setTone(el, v) {
		el.classList.remove('text-up', 'text-down', 'text-muted');
		el.classList.add(v > 0 ? 'text-up' : v < 0 ? 'text-down' : 'text-muted');
	}

	/** 상단 현재가 카드 주기 갱신 */
	function liveTickers(intervalMs) {
		const cards = document.querySelectorAll('[data-ticker]');
		if (!cards.length) return;
		const last = {};
		// 화면에 있는 종목만 요청 (기본 종목 외 코인 상세 화면도 갱신되도록)
		const syms = [...new Set([...cards].map(c => c.dataset.ticker))];
		async function poll() {
			try {
				const res = await fetch('/api/market/tickers?symbols=' + encodeURIComponent(syms.join(',')));
				if (!res.ok) return;
				const body = await res.json();
				body.tickers.forEach(t => {
					const card = document.querySelector(`[data-ticker="${t.symbol}"]`);
					if (!card) return;
					const priceEl = card.querySelector('.js-price');
					const changeEl = card.querySelector('.js-change');
					const volEl = card.querySelector('.js-vol');
					if (!priceEl || !changeEl || !volEl) return; // 첫 수집 전 렌더링된 카드
					priceEl.textContent = fmtPrice(t.price);
					if (last[t.symbol] !== undefined && last[t.symbol] !== t.price) {
						priceEl.classList.remove('fi-flash-up', 'fi-flash-down');
						void priceEl.offsetWidth; // 애니메이션 재시작
						priceEl.classList.add(t.price > last[t.symbol] ? 'fi-flash-up' : 'fi-flash-down');
					}
					last[t.symbol] = t.price;
					changeEl.textContent = (t.changePercent24h > 0 ? '+' : '') + t.changePercent24h.toFixed(2) + '%';
					setTone(changeEl, t.changePercent24h);
					volEl.textContent = fmtUsd(t.quoteVolume24h);
				});
			} catch (e) { /* 네트워크 일시 오류는 다음 주기에 재시도 */ }
		}
		setInterval(poll, intervalMs);
	}

	/**
	 * 가격(종가 라인) + 거래량(막대) 차트
	 * opts: { symbol, symbolGroup, intervalGroup }
	 */
	function priceChart(canvasId, opts) {
		const canvas = document.getElementById(canvasId);
		if (!canvas || typeof Chart === 'undefined') return;

		const state = { symbol: opts.symbol || 'BTCUSDT', interval: '1h', limit: 168 };
		const activeIv = opts.intervalGroup && document.querySelector(opts.intervalGroup + ' .active');
		if (activeIv) { state.interval = activeIv.dataset.interval; state.limit = +activeIv.dataset.limit; }
		const activeSym = opts.symbolGroup && document.querySelector(opts.symbolGroup + ' .active');
		if (activeSym) state.symbol = activeSym.dataset.symbol;

		const ctx = canvas.getContext('2d');
		const grad = ctx.createLinearGradient(0, 0, 0, canvas.parentElement.clientHeight || 340);
		grad.addColorStop(0, 'rgba(240,185,11,0.25)');
		grad.addColorStop(1, 'rgba(240,185,11,0)');

		const chart = new Chart(ctx, {
			data: {
				labels: [],
				datasets: [
					{ type: 'line', label: '종가', data: [], yAxisID: 'y', borderColor: C.accent, backgroundColor: grad,
						fill: true, borderWidth: 1.8, pointRadius: 0, tension: 0.25 },
					{ type: 'bar', label: '거래량', data: [], yAxisID: 'v', backgroundColor: [], barPercentage: 1, categoryPercentage: 0.9 }
				]
			},
			options: {
				responsive: true,
				maintainAspectRatio: false,
				animation: { duration: 300 },
				interaction: { mode: 'index', intersect: false },
				plugins: {
					legend: { display: false },
					tooltip: {
						callbacks: {
							label: c => c.dataset.yAxisID === 'y'
								? ' 종가 ' + fmtPrice(c.parsed.y)
								: ' 거래량 ' + c.parsed.y.toLocaleString('en-US', { maximumFractionDigits: 0 })
						}
					}
				},
				scales: {
					x: { grid: { display: false }, ticks: { color: C.muted, maxTicksLimit: 8, maxRotation: 0 } },
					y: { position: 'right', grid: { color: C.grid }, ticks: { color: C.muted, callback: v => fmtPrice(v) } },
					v: { display: false, beginAtZero: true, max: ctx => (ctx.chart.data.datasets[1].data.reduce((a, b) => Math.max(a, b), 0) * 4) || 1 }
				}
			}
		});

		async function load() {
			const url = `/api/market/klines?symbol=${state.symbol}&interval=${state.interval}&limit=${state.limit}`;
			try {
				const res = await fetch(url);
				if (!res.ok) throw new Error(res.status);
				const rows = await res.json();
				chart.data.labels = rows.map(r => fmtTime(r.openTime, state.interval));
				chart.data.datasets[0].data = rows.map(r => r.close);
				chart.data.datasets[1].data = rows.map(r => r.volume);
				chart.data.datasets[1].backgroundColor = rows.map(r =>
					r.close >= r.open ? 'rgba(22,199,132,0.35)' : 'rgba(234,57,67,0.35)');
				chart.update();
			} catch (e) {
				console.error('차트 데이터 로드 실패', e);
			}
		}

		function bindGroup(selector, apply) {
			if (!selector) return;
			const group = document.querySelector(selector);
			if (!group) return;
			group.addEventListener('click', e => {
				const btn = e.target.closest('button');
				if (!btn) return;
				group.querySelectorAll('button').forEach(b => b.classList.remove('active'));
				btn.classList.add('active');
				apply(btn.dataset);
				load();
			});
		}

		bindGroup(opts.symbolGroup, ds => state.symbol = ds.symbol);
		bindGroup(opts.intervalGroup, ds => { state.interval = ds.interval; state.limit = +ds.limit; });

		load();
		return chart;
	}

	window.FI = { priceChart, liveTickers, fmtPrice, fmtUsd, colors: C };
})();
