/* 백테스트 화면: 누적 수익 곡선 + 주간 성과 */
(function () {
	'use strict';

	// 실행 중 표시 (서버에서 수 초 걸릴 수 있음)
	const form = document.getElementById('btForm');
	if (form) {
		form.addEventListener('submit', () => {
			const btn = document.getElementById('btSubmit');
			btn.disabled = true;
			btn.innerHTML = '<span class="spinner-border spinner-border-sm"></span>';
		});
	}

	const data = window.BT_DATA;
	if (!data || typeof Chart === 'undefined') return;
	const C = window.FI.colors;
	const pct = v => (v > 0 ? '+' : '') + v.toFixed(2) + '%';
	const p = n => String(n).padStart(2, '0');
	const label = ms => { const d = new Date(ms); return (d.getMonth() + 1) + '/' + d.getDate() + ' ' + p(d.getHours()) + 'h'; };

	// 누적 수익 곡선
	const eq = document.getElementById('equityChart');
	if (eq) {
		const values = data.equity.map(e => e.value);
		const last = values.length ? values[values.length - 1] : 0;
		const color = last >= 0 ? C.up : C.down;
		new Chart(eq, {
			type: 'line',
			data: {
				labels: data.equity.map(e => label(e.time)),
				datasets: [{
					data: values, borderColor: color, borderWidth: 1.8, pointRadius: 0, tension: 0.15,
					fill: { target: { value: 0 }, above: 'rgba(22,199,132,0.12)', below: 'rgba(234,57,67,0.12)' }
				}]
			},
			options: {
				responsive: true, maintainAspectRatio: false, animation: false,
				interaction: { mode: 'index', intersect: false },
				plugins: { legend: { display: false }, tooltip: { callbacks: { label: c => ' 누적 ' + pct(c.parsed.y) } } },
				scales: {
					x: { grid: { display: false }, ticks: { color: C.muted, maxTicksLimit: 7, maxRotation: 0 } },
					y: { position: 'right', grid: { color: C.grid }, ticks: { color: C.muted, callback: v => v + '%' } }
				}
			}
		});
	}

	// 주간 성과
	const wk = document.getElementById('weeklyChart');
	if (wk) {
		new Chart(wk, {
			type: 'bar',
			data: {
				labels: data.weekly.map(w => w.label),
				datasets: [{
					data: data.weekly.map(w => w.totalReturn),
					backgroundColor: data.weekly.map(w => w.totalReturn >= 0 ? 'rgba(22,199,132,0.7)' : 'rgba(234,57,67,0.7)'),
					borderRadius: 4
				}]
			},
			options: {
				responsive: true, maintainAspectRatio: false, animation: false,
				plugins: {
					legend: { display: false },
					tooltip: {
						callbacks: {
							label: c => {
								const w = data.weekly[c.dataIndex];
								return [' 합계 ' + pct(w.totalReturn), ' 거래 ' + w.count + '건 · 승률 ' + w.winRate.toFixed(1) + '%'];
							}
						}
					}
				},
				scales: {
					x: { grid: { display: false }, ticks: { color: C.muted } },
					y: { position: 'right', grid: { color: C.grid }, ticks: { color: C.muted, callback: v => v + '%' } }
				}
			}
		});
	}
})();
