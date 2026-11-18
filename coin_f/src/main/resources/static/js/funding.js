/* Futures Insight - 펀딩비 분석 */
(function () {
	'use strict';

	const C = FI.colors;
	const UP_A = 'rgba(22,199,132,0.45)';
	const DOWN_A = 'rgba(234,57,67,0.45)';
	const p2 = n => String(n).padStart(2, '0');
	const fmtTime = ms => { const d = new Date(ms); return `${d.getMonth() + 1}/${d.getDate()} ${p2(d.getHours())}시`; };
	const pct = (v, d = 4) => (v > 0 ? '+' : '') + v.toFixed(d) + '%';

	const select = document.getElementById('fundingSymbol');
	if (!select) return;
	let days = +document.querySelector('#fundingRange .active').dataset.days;

	const chart = new Chart(document.getElementById('fundingHistChart'), {
		type: 'bar',
		data: { labels: [], datasets: [{ label: '펀딩비', data: [], backgroundColor: [] }] },
		options: {
			responsive: true, maintainAspectRatio: false, animation: false,
			plugins: { legend: { display: false }, tooltip: { callbacks: { label: c => ` 펀딩비 ${pct(c.parsed.y)}` } } },
			scales: {
				x: { grid: { display: false }, ticks: { color: C.muted, maxTicksLimit: 8, maxRotation: 0 } },
				y: { grid: { color: C.grid }, ticks: { color: C.muted, callback: v => v.toFixed(3) + '%' } }
			}
		}
	});

	async function load() {
		let h;
		try {
			const res = await fetch(`/api/funding/history?symbol=${select.value}&days=${days}`);
			if (!res.ok) return;
			h = await res.json();
		} catch (e) { return; }

		// 과열 기준은 8h 환산 0.03% → 정산 1회 기준으로 되돌린 값
		const hot = 0.03 * h.intervalHours / 8;
		const rates = h.points.map(p => p.value * 100);
		chart.data.labels = h.points.map(p => fmtTime(p.ts));
		chart.data.datasets[0].data = rates;
		chart.data.datasets[0].backgroundColor = rates.map(v => Math.abs(v) >= hot ? (v > 0 ? C.up : C.down) : (v >= 0 ? UP_A : DOWN_A));
		chart.update();

		document.getElementById('fundingHistSub').textContent =
			`정산 확정값 · ${h.intervalHours}시간 주기 · 진한 막대 = 과열(정산당 ±${hot.toFixed(4)}%)`;
		const stat = document.getElementById('fundingHistStat');
		if (!rates.length) { stat.innerHTML = '<span class="text-muted">이력이 없습니다.</span>'; return; }
		const avg = rates.reduce((a, x) => a + x, 0) / rates.length;
		const pos = rates.filter(v => v > 0).length;
		const hotCount = rates.filter(v => Math.abs(v) >= hot).length;
		stat.innerHTML = `정산 ${rates.length}회 · 평균 <span class="${avg >= 0 ? 'text-up' : 'text-down'}">${pct(avg)}</span>` +
			` · 양(+) ${(pos / rates.length * 100).toFixed(0)}% · 과열 ${hotCount}회` +
			` · 최고 ${pct(Math.max(...rates))} / 최저 ${pct(Math.min(...rates))}`;
	}

	select.addEventListener('change', load);
	document.getElementById('fundingRange').addEventListener('click', e => {
		const btn = e.target.closest('button');
		if (!btn) return;
		document.querySelectorAll('#fundingRange button').forEach(b => b.classList.toggle('active', b === btn));
		days = +btn.dataset.days;
		load();
	});
	load();
})();
