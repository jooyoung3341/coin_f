/* 오늘의 코인: 탭 유지 · 24h 스파크라인 · 1분 자동 새로고침 */
(function () {
	'use strict';

	const C = window.FI ? window.FI.colors : { up: '#16c784', down: '#ea3943' };
	const cache = {}; // symbol → close[] (탭 간 재사용)

	function draw(canvas, closes) {
		const ctx = canvas.getContext('2d');
		const w = canvas.width, h = canvas.height, pad = 2;
		ctx.clearRect(0, 0, w, h);
		if (!closes || closes.length < 2) return;
		const min = Math.min(...closes), max = Math.max(...closes);
		const span = max - min || 1;
		const x = i => pad + i * (w - pad * 2) / (closes.length - 1);
		const y = v => h - pad - (v - min) / span * (h - pad * 2);
		const color = closes[closes.length - 1] >= closes[0] ? C.up : C.down;

		ctx.beginPath();
		closes.forEach((v, i) => i === 0 ? ctx.moveTo(x(i), y(v)) : ctx.lineTo(x(i), y(v)));
		ctx.strokeStyle = color;
		ctx.lineWidth = 1.5;
		ctx.stroke();
		// 아래 채움
		ctx.lineTo(x(closes.length - 1), h);
		ctx.lineTo(x(0), h);
		ctx.closePath();
		ctx.globalAlpha = 0.12;
		ctx.fillStyle = color;
		ctx.fill();
		ctx.globalAlpha = 1;
	}

	async function load(symbol) {
		if (cache[symbol]) return cache[symbol];
		const res = await fetch(`/api/market/klines?symbol=${symbol}&interval=1h&limit=24`);
		if (!res.ok) throw new Error(res.status);
		const rows = await res.json();
		cache[symbol] = rows.map(r => r.close);
		return cache[symbol];
	}

	/** 보이는 탭의 스파크라인만 그림 (요청 수 절약) */
	function drawPane(pane) {
		if (!pane) return;
		pane.querySelectorAll('canvas.fi-spark:not([data-done])').forEach(c => {
			c.dataset.done = '1';
			load(c.dataset.symbol).then(closes => draw(c, closes)).catch(() => { /* 무시 */ });
		});
	}

	const tabs = document.getElementById('boardTabs');
	const desc = document.getElementById('boardDesc');
	if (tabs) {
		tabs.querySelectorAll('button').forEach(btn => {
			btn.addEventListener('shown.bs.tab', () => {
				if (desc) desc.textContent = btn.dataset.desc;
				history.replaceState(null, '', '#' + btn.dataset.key);
				drawPane(document.querySelector(btn.dataset.bsTarget));
			});
		});
		// 새로고침 후에도 보던 탭 유지
		const key = location.hash.slice(1);
		const saved = key && tabs.querySelector(`button[data-key="${key}"]`);
		if (saved && window.bootstrap) {
			bootstrap.Tab.getOrCreateInstance(saved).show();
		} else {
			drawPane(document.querySelector('.tab-pane.active'));
		}
	}

	// ---------------------------------------------------------------- 열 정렬
	// 숫자 열: 내림차순 → 오름차순 → 원래 순서, 코인명: 가나다(ABC)순 → 역순 → 원래 순서
	// 값이 없는 행(펀딩·OI 미수집 등)은 정렬 방향과 관계없이 항상 아래로
	const STORE = 'today-sort';
	const loadSorts = () => { try { return JSON.parse(sessionStorage.getItem(STORE)) || {}; } catch (e) { return {}; } };
	const saveSorts = s => { try { sessionStorage.setItem(STORE, JSON.stringify(s)); } catch (e) { /* 무시 */ } };
	const sorts = loadSorts(); // paneId → { key, dir }

	function applySort(table, key, dir) {
		const tbody = table.querySelector('tbody.js-sort-body'); // 상단 고정(BTC) tbody 는 제외
		const isText = key === 'asset';
		const rows = Array.from(tbody.rows);
		const val = r => {
			const v = r.dataset[key];
			if (v === undefined || v === '') return null;
			return isText ? v : Number(v);
		};
		rows.sort((a, b) => {
			if (!dir) return Number(a.dataset.rank) - Number(b.dataset.rank);
			const va = val(a), vb = val(b);
			if (va === null && vb === null) return Number(a.dataset.rank) - Number(b.dataset.rank);
			if (va === null) return 1;
			if (vb === null) return -1;
			const cmp = isText ? va.localeCompare(vb) : va - vb;
			return dir === 'asc' ? cmp : -cmp;
		});
		rows.forEach(r => tbody.appendChild(r));
		table.querySelectorAll('th.fi-sortable').forEach(th => {
			th.classList.remove('sort-asc', 'sort-desc');
			if (dir && th.dataset.sort === key) th.classList.add('sort-' + dir);
		});
	}

	document.querySelectorAll('table.fi-today').forEach(table => {
		const paneId = table.closest('.tab-pane').id;
		table.querySelectorAll('th.fi-sortable').forEach(th => {
			th.addEventListener('click', () => {
				const key = th.dataset.sort;
				const cur = sorts[paneId];
				let dir;
				if (key === 'rank') {
					dir = null;
				} else {
					const first = key === 'asset' ? 'asc' : 'desc';
					const second = first === 'asc' ? 'desc' : 'asc';
					dir = !cur || cur.key !== key ? first : cur.dir === first ? second : null;
				}
				if (dir) sorts[paneId] = { key, dir }; else delete sorts[paneId];
				saveSorts(sorts);
				applySort(table, key, dir);
			});
		});
		// 1분 자동 새로고침 후에도 정렬 유지
		const s = sorts[paneId];
		if (s) applySort(table, s.key, s.dir);
	});

	// ---------------------------------------------------------------- 심볼 복사 (BTCUSDT.P)
	// 정렬로 행이 옮겨져도 동작하도록 문서 단위 위임
	async function copyText(text) {
		if (navigator.clipboard && window.isSecureContext) {
			await navigator.clipboard.writeText(text);
			return;
		}
		// http(비보안) 접속 등 Clipboard API 를 쓸 수 없을 때
		const ta = document.createElement('textarea');
		ta.value = text;
		ta.style.position = 'fixed';
		ta.style.opacity = '0';
		document.body.appendChild(ta);
		ta.select();
		const ok = document.execCommand('copy');
		document.body.removeChild(ta);
		if (!ok) throw new Error('copy failed');
	}

	let toastTimer;
	function toast(msg) {
		let el = document.getElementById('fiCopyToast');
		if (!el) {
			el = document.createElement('div');
			el.id = 'fiCopyToast';
			el.className = 'fi-copy-toast';
			document.body.appendChild(el);
		}
		el.textContent = msg;
		el.classList.add('show');
		clearTimeout(toastTimer);
		toastTimer = setTimeout(() => el.classList.remove('show'), 1500);
	}

	document.addEventListener('click', async e => {
		const btn = e.target.closest('.fi-copy');
		if (!btn) return;
		const text = btn.dataset.copy;
		const icon = btn.querySelector('i');
		try {
			await copyText(text);
			icon.className = 'bi bi-check2';
			btn.classList.add('copied');
			toast(text + ' 복사됨');
		} catch (err) {
			toast('복사에 실패했습니다');
		}
		setTimeout(() => { icon.className = 'bi bi-copy'; btn.classList.remove('copied'); }, 1200);
	});

	setTimeout(() => location.reload(), 60_000);
})();
