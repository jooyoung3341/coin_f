/* Futures Insight - 미결제약정(OI) 분석 */
(function () {
	'use strict';

	const C = FI.colors;
	const pct = v => (v > 0 ? '+' : '') + v.toFixed(2) + '%';

	/* ---------------- 가격 vs OI 산점도 ---------------- */
	const pts = (window.OI_POINTS || []).filter(r => r.priceChange24h !== null && r.priceChange24h !== undefined);
	const canvas = document.getElementById('oiScatter');
	if (canvas && pts.length) {
		const maxOi = Math.max(...pts.map(r => r.openInterestUsd));
		const color = r => r.change24h >= 0
			? (r.priceChange24h >= 0 ? 'rgba(22,199,132,0.7)' : 'rgba(234,57,67,0.7)')
			: (r.priceChange24h >= 0 ? 'rgba(240,185,11,0.7)' : 'rgba(138,147,166,0.7)');
		// 0 축선을 강조
		const zeroLine = ctx => ctx.tick.value === 0 ? 'rgba(255,255,255,0.25)' : C.grid;

		new Chart(canvas, {
			type: 'bubble',
			data: {
				datasets: [{
					data: pts.map(r => ({
						x: r.priceChange24h, y: r.change24h,
						r: 4 + Math.sqrt(r.openInterestUsd / maxOi) * 14,
						sym: r.symbol.replace(/USDT$/, ''), oi: r.openInterestUsd, reading: r.reading
					})),
					backgroundColor: pts.map(color),
					borderColor: 'rgba(0,0,0,0.3)'
				}]
			},
			options: {
				responsive: true, maintainAspectRatio: false, animation: false,
				plugins: {
					legend: { display: false },
					tooltip: {
						callbacks: {
							label: c => {
								const d = c.raw;
								return ` ${d.sym} · 가격 ${pct(d.x)} · OI ${pct(d.y)} · ${FI.fmtUsd(d.oi)} · ${d.reading}`;
							}
						}
					}
				},
				scales: {
					x: { title: { display: true, text: '가격 변화 24h', color: C.muted },
						grid: { color: zeroLine }, ticks: { color: C.muted, callback: v => v + '%' } },
					y: { title: { display: true, text: 'OI 변화 24h', color: C.muted },
						grid: { color: zeroLine }, ticks: { color: C.muted, callback: v => v + '%' } }
				}
			},
			plugins: [{
				// 점 옆에 심볼 표시
				id: 'labels',
				afterDatasetsDraw(chart) {
					const { ctx } = chart;
					ctx.save();
					ctx.font = '11px Pretendard, sans-serif';
					ctx.fillStyle = 'rgba(230,233,239,0.85)';
					chart.getDatasetMeta(0).data.forEach((el, i) => {
						const d = chart.data.datasets[0].data[i];
						ctx.fillText(d.sym, el.x + d.r * 0.7 + 2, el.y + 4);
					});
					ctx.restore();
				}
			}]
		});
	}

	/* ---------------- 표 정렬 ---------------- */
	const table = document.getElementById('oiTable');
	if (table) {
		const state = { col: -1, asc: true };
		table.querySelectorAll('thead th').forEach((th, idx) => {
			th.style.cursor = 'pointer';
			th.addEventListener('click', () => {
				const type = th.dataset.sort;
				state.asc = state.col === idx ? !state.asc : type === 'text';
				state.col = idx;
				const rows = [...table.tBodies[0].rows];
				rows.sort((a, b) => {
					const va = a.cells[idx].dataset.v, vb = b.cells[idx].dataset.v;
					// 값 없음(-)은 항상 맨 아래
					if (va === undefined) return 1;
					if (vb === undefined) return -1;
					const cmp = type === 'num' ? (+va) - (+vb) : va.localeCompare(vb);
					return state.asc ? cmp : -cmp;
				});
				rows.forEach(r => table.tBodies[0].appendChild(r));
				table.querySelectorAll('thead th').forEach((h, i) =>
					h.classList.toggle('fi-sorted', i === idx));
			});
		});
	}
})();
