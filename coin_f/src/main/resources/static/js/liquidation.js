/* Futures Insight - 청산 분석 */
(function () {
	'use strict';

	const C = FI.colors;
	// 업계 관례: 롱 청산 = 빨강, 숏 청산 = 초록
	const LONG = 'rgba(234,57,67,0.8)';
	const SHORT = 'rgba(22,199,132,0.8)';
	const BIG = 100_000;
	const p2 = n => String(n).padStart(2, '0');
	const $ = id => document.getElementById(id);
	const base = s => s.replace(/USDT$/, '');
	const hhmmss = ms => { const d = new Date(ms); return `${p2(d.getHours())}:${p2(d.getMinutes())}:${p2(d.getSeconds())}`; };

	/* ============================================================ 요약 + 시간대별 + 순위 */
	const hourly = new Chart($('hourlyChart'), {
		type: 'bar',
		data: { labels: [], datasets: [
			{ label: '롱 청산', data: [], backgroundColor: LONG, stack: 's' },
			{ label: '숏 청산', data: [], backgroundColor: SHORT, stack: 's' }
		] },
		options: {
			responsive: true, maintainAspectRatio: false, animation: false,
			interaction: { mode: 'index', intersect: false },
			plugins: {
				legend: { labels: { color: C.muted, boxWidth: 10 } },
				tooltip: { callbacks: { label: c => ` ${c.dataset.label} ${FI.fmtUsd(c.parsed.y)}` } }
			},
			scales: {
				x: { stacked: true, grid: { display: false }, ticks: { color: C.muted, maxRotation: 0, autoSkip: true, maxTicksLimit: 12 } },
				y: { stacked: true, grid: { color: C.grid }, ticks: { color: C.muted, callback: v => FI.fmtUsd(v) } }
			}
		}
	});

	async function loadOverview() {
		let o;
		try {
			const res = await fetch('/api/liquidation/overview');
			if (!res.ok) return;
			o = await res.json();
		} catch (e) { return; }

		const h = o.lastHour, d = o.lastDay;
		$('s1hTotal').textContent = FI.fmtUsd(h.longUsd + h.shortUsd);
		$('s1hLong').textContent = '롱 ' + FI.fmtUsd(h.longUsd);
		$('s1hShort').textContent = '숏 ' + FI.fmtUsd(h.shortUsd);
		$('s24Total').textContent = FI.fmtUsd(d.longUsd + d.shortUsd);
		$('s24Long').textContent = '롱 ' + FI.fmtUsd(d.longUsd);
		$('s24Short').textContent = '숏 ' + FI.fmtUsd(d.shortUsd);
		$('s24Count').textContent = d.count.toLocaleString() + '건';
		const total = d.longUsd + d.shortUsd;
		$('s24Bias').innerHTML = total > 0
			? (d.longUsd >= d.shortUsd
				? `<span class="liq-long">롱 청산 우세 ${(d.longUsd / total * 100).toFixed(0)}%</span> — 하락 압력`
				: `<span class="liq-short">숏 청산 우세 ${(d.shortUsd / total * 100).toFixed(0)}%</span> — 상승 압력`)
			: '';
		if (o.largest) {
			$('sLargest').textContent = FI.fmtUsd(o.largest.valueUsd);
			$('sLargestInfo').innerHTML = `${base(o.largest.symbol)} · <span class="${o.largest.side === 'SELL' ? 'liq-long' : 'liq-short'}">${o.largest.side === 'SELL' ? '롱' : '숏'} 청산</span> · ${hhmmss(o.largest.time)}`;
		}

		// 수집 시간이 24시간보다 짧으면 안내
		const hours = (Date.now() - o.collectingSince) / 3_600_000;
		const note = $('liqPartial');
		if (hours < 24) {
			note.classList.remove('d-none');
			note.innerHTML = `<i class="bi bi-info-circle"></i> 청산 수집을 시작한 지 약 ${Math.max(0, hours).toFixed(1)}시간이 지났습니다. 24시간 집계는 그 이후 데이터만 포함합니다.`;
		} else note.classList.add('d-none');

		hourly.data.labels = o.hourly.map(b => p2(new Date(b.hourStart).getHours()) + '시');
		hourly.data.datasets[0].data = o.hourly.map(b => b.longUsd);
		hourly.data.datasets[1].data = o.hourly.map(b => b.shortUsd);
		hourly.update();

		$('rankBody').innerHTML = o.bySymbol.length ? o.bySymbol.map((r, i) => {
			const t = r.longUsd + r.shortUsd;
			const share = t ? r.longUsd / t * 100 : 0;
			return `<tr>
				<td class="fi-rank">${i + 1}</td>
				<td>${base(r.symbol)}</td>
				<td class="text-end num fw-semibold">${FI.fmtUsd(t)}</td>
				<td class="text-end num liq-long">${FI.fmtUsd(r.longUsd)}</td>
				<td class="text-end num liq-short">${FI.fmtUsd(r.shortUsd)}</td>
				<td class="fi-bar-cell"><div class="fi-liq-ratio"><span style="width:${share.toFixed(1)}%"></span></div></td>
			</tr>`;
		}).join('') : '<tr><td colspan="6" class="text-muted text-center py-4">아직 청산 데이터가 없습니다.</td></tr>';
	}

	/* ============================================================ 실시간 피드 */
	let lastSeen = 0;
	async function loadFeed() {
		let body;
		try {
			const res = await fetch('/api/market/liquidations?limit=40');
			if (!res.ok) return;
			body = await res.json();
		} catch (e) { return; }
		if (!body.recent.length) return;
		const newest = body.recent[0].time;
		$('liqFeed').innerHTML = body.recent.map(l => {
			const isLong = l.side === 'SELL';
			const fresh = lastSeen && l.time > lastSeen ? ' fi-feed-new' : '';
			const big = l.valueUsd >= BIG ? ' fi-feed-big' : '';
			return `<div class="fi-feed-row${fresh}${big}">
				<span class="fi-feed-time num">${hhmmss(l.time)}</span>
				<span class="fi-feed-sym">${base(l.symbol)}</span>
				<span class="${isLong ? 'liq-long' : 'liq-short'}">${isLong ? '롱' : '숏'}</span>
				<span class="fi-feed-price num text-muted">${FI.fmtPrice(l.price)}</span>
				<span class="fi-feed-val num">${FI.fmtUsd(l.valueUsd)}</span>
			</div>`;
		}).join('');
		lastSeen = newest;
	}

	/* ============================================================ 가격대별 분포 */
	let distSymbol = document.querySelector('#distSymbol .active').dataset.symbol;
	const currentPriceLine = {
		id: 'currentPrice',
		afterDatasetsDraw(chart) {
			const idx = chart.$currentIdx;
			if (idx === undefined || idx < 0) return;
			const y = chart.scales.y.getPixelForValue(idx);
			const { ctx, chartArea } = chart;
			ctx.save();
			ctx.strokeStyle = C.accent;
			ctx.setLineDash([4, 4]);
			ctx.beginPath();
			ctx.moveTo(chartArea.left, y);
			ctx.lineTo(chartArea.right, y);
			ctx.stroke();
			ctx.restore();
		}
	};
	const dist = new Chart($('distChart'), {
		type: 'bar',
		data: { labels: [], datasets: [
			{ label: '롱 청산', data: [], backgroundColor: LONG, stack: 's' },
			{ label: '숏 청산', data: [], backgroundColor: SHORT, stack: 's' }
		] },
		options: {
			indexAxis: 'y',
			responsive: true, maintainAspectRatio: false, animation: false,
			interaction: { mode: 'index', intersect: false },
			plugins: {
				legend: { labels: { color: C.muted, boxWidth: 10 } },
				tooltip: { callbacks: { label: c => ` ${c.dataset.label} ${FI.fmtUsd(c.parsed.x)}` } }
			},
			scales: {
				x: { stacked: true, grid: { color: C.grid }, ticks: { color: C.muted, callback: v => FI.fmtUsd(v) } },
				y: { stacked: true, reverse: true, grid: { display: false }, ticks: { color: C.muted, autoSkip: true, maxTicksLimit: 15 } }
			}
		},
		plugins: [currentPriceLine]
	});

	async function loadDist() {
		let d;
		try {
			const res = await fetch(`/api/liquidation/distribution?symbol=${distSymbol}`);
			if (!res.ok) return;
			d = await res.json();
		} catch (e) { return; }
		// 높은 가격이 위로 오도록 역순
		const b = [...d.buckets].reverse();
		dist.data.labels = b.map(x => FI.fmtPrice(x.from));
		dist.data.datasets[0].data = b.map(x => x.longUsd);
		dist.data.datasets[1].data = b.map(x => x.shortUsd);
		dist.$currentIdx = d.currentPrice == null ? -1 : b.findIndex(x => d.currentPrice >= x.from && d.currentPrice < x.to);
		dist.update();

		if (!d.eventCount) {
			$('distInfo').textContent = `${base(distSymbol)}: 최근 24시간 청산이 아직 없습니다.`;
			return;
		}
		let maxB = b[0];
		b.forEach(x => { if (x.longUsd + x.shortUsd > maxB.longUsd + maxB.shortUsd) maxB = x; });
		$('distInfo').innerHTML = `${d.eventCount}건 · 구간 폭 ${FI.fmtPrice(d.step)} · 최대 집중 구간 ` +
			`<strong>${FI.fmtPrice(maxB.from)} ~ ${FI.fmtPrice(maxB.to)}</strong> (${FI.fmtUsd(maxB.longUsd + maxB.shortUsd)})` +
			(d.currentPrice != null ? ` · 현재가 ${FI.fmtPrice(d.currentPrice)}` : '');
	}

	$('distSymbol').addEventListener('click', e => {
		const btn = e.target.closest('button');
		if (!btn) return;
		document.querySelectorAll('#distSymbol button').forEach(x => x.classList.toggle('active', x === btn));
		distSymbol = btn.dataset.symbol;
		loadDist();
	});

	loadOverview();
	loadFeed();
	loadDist();
	setInterval(loadFeed, 3_000);
	setInterval(loadOverview, 15_000);
	setInterval(loadDist, 30_000);
})();
