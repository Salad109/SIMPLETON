(() => {
    'use strict';

    const SCENE_HALF_W = 360;
    const SCENE_HALF_H = 360;
    const COLLISION_THRESHOLD_KM = 5;
    const PERIOD_SECONDS = 5400;
    const TWO_PI = Math.PI * 2;
    const COLLISION_T = 0.5;

    const PRESETS = [
        {
            id: 'crossing',
            label: 'Crossing',
            orbitA: {a: 260, e: 0.20, omega: 0},
            orbitB: {a: 260, e: 0.30, omega: Math.PI / 2.5},
            mode: 'pin-crossing'
        },
        {
            id: 'near-miss',
            label: 'Near-miss',
            orbitA: {a: 260, e: 0.20, omega: 0},
            orbitB: {a: 260, e: 0.30, omega: Math.PI / 2.5},
            mode: 'pin-crossing',
            mBExtra: 0.15907
        },
        {
            id: 'formation',
            label: 'Formation',
            orbitA: {a: 260, e: 0.10, omega: 0},
            orbitB: {a: 260, e: 0.10, omega: Math.PI / 30},
            mode: 'periapsis-aligned'
        },
        {
            id: 'eccentric',
            label: 'Eccentric',
            orbitA: {a: 260, e: 0.05, omega: 0},
            orbitB: {a: 260, e: 0.55, omega: Math.PI / 3},
            mode: 'pin-crossing'
        },
        {
            id: 'head-on',
            label: 'Head-on',
            orbitA: {a: 260, e: 0.25, omega: 0},
            orbitB: {a: 260, e: 0.25, omega: Math.PI / 3, dir: -1},
            mode: 'pin-crossing'
        }
    ];

    let orbitA = PRESETS[0].orbitA;
    let orbitB = PRESETS[0].orbitB;
    let phases = {mAOffset: 0, mBOffset: 0};

    const DEFAULTS = {tolerance: 84, cellSize: 74.0, stepSeconds: 10.8, stride: 32};

    const HALF_NEIGHBORS_2D = [
        {dx: 1, dy: 0},
        {dx: 1, dy: 1},
        {dx: 0, dy: 1},
        {dx: -1, dy: 1}
    ];

    const sceneCanvas = document.getElementById('scene');
    const sceneCtx = sceneCanvas.getContext('2d');
    const refineCanvas = document.getElementById('refine');
    const refineCtx = refineCanvas.getContext('2d');
    const gridBadge = document.getElementById('grid-badge');
    const coarseBadge = document.getElementById('coarse-badge');
    const conjunctionBadge = document.getElementById('conjunction-badge');

    const state = {
        t: COLLISION_T,
        playing: false,
        ...DEFAULTS,
        showGrid: true,
        showKnots: true,
        showInterp: true,
        showTolerance: true,
        showRefine: true,
        showTruth: true
    };

    function solveKepler(M, e) {
        let E = M;
        for (let i = 0; i < 8; i++) {
            const f = E - e * Math.sin(E) - M;
            const fp = 1 - e * Math.cos(E);
            E -= f / fp;
        }
        return E;
    }

    function trueAnomaly(M, e) {
        const E = solveKepler(M, e);
        return 2 * Math.atan2(
            Math.sqrt(1 + e) * Math.sin(E / 2),
            Math.sqrt(1 - e) * Math.cos(E / 2)
        );
    }

    function meanAnomalyFromTrue(nu, e) {
        const E = 2 * Math.atan2(
            Math.sqrt(1 - e) * Math.sin(nu / 2),
            Math.sqrt(1 + e) * Math.cos(nu / 2)
        );
        return E - e * Math.sin(E);
    }

    function orbitState(orbit, M) {
        const {a, e, omega, dir = 1} = orbit;
        const nu = trueAnomaly(M * dir, e);
        const p = a * (1 - e * e);
        const r = p / (1 + e * Math.cos(nu));
        const theta = nu + omega;
        const cosT = Math.cos(theta), sinT = Math.sin(theta);
        const meanMotion = TWO_PI / PERIOD_SECONDS;
        const h = meanMotion * a * a * Math.sqrt(1 - e * e);
        const vScale = (h / p) * dir;
        const vx = vScale * (-Math.sin(theta) - e * Math.sin(omega));
        const vy = vScale * (Math.cos(theta) + e * Math.cos(omega));
        return {x: r * cosT, y: r * sinT, vx, vy};
    }

    function findIntersectionTrueAnomalies(A, B) {
        const N = 720;
        let bestNuA = 0, bestNuB = 0, bestD2 = Infinity;
        const ptsB = new Array(N);
        for (let j = 0; j < N; j++) {
            const nuB = (j / N) * TWO_PI;
            const pB = B.a * (1 - B.e * B.e) / (1 + B.e * Math.cos(nuB));
            const thB = nuB + B.omega;
            ptsB[j] = {x: pB * Math.cos(thB), y: pB * Math.sin(thB), nu: nuB};
        }
        for (let i = 0; i < N; i++) {
            const nuA = (i / N) * TWO_PI;
            const pA = A.a * (1 - A.e * A.e) / (1 + A.e * Math.cos(nuA));
            const thA = nuA + A.omega;
            const xA = pA * Math.cos(thA), yA = pA * Math.sin(thA);
            for (let j = 0; j < N; j++) {
                const dx = xA - ptsB[j].x, dy = yA - ptsB[j].y;
                const d2 = dx * dx + dy * dy;
                if (d2 < bestD2) {
                    bestD2 = d2;
                    bestNuA = nuA;
                    bestNuB = ptsB[j].nu;
                }
            }
        }
        return {nuA: bestNuA, nuB: bestNuB};
    }

    function applyPreset(preset) {
        orbitA = preset.orbitA;
        orbitB = preset.orbitB;
        if (preset.mode === 'pin-crossing') {
            const {nuA, nuB} = findIntersectionTrueAnomalies(orbitA, orbitB);
            const dirA = orbitA.dir || 1;
            const dirB = orbitB.dir || 1;
            phases = {
                mAOffset: dirA * meanAnomalyFromTrue(nuA, orbitA.e) - TWO_PI * COLLISION_T,
                mBOffset: dirB * meanAnomalyFromTrue(nuB, orbitB.e) - TWO_PI * COLLISION_T + (preset.mBExtra || 0)
            };
        } else {
            phases = {mAOffset: 0, mBOffset: 0};
        }
    }

    applyPreset(PRESETS[0]);

    function stateA(t) {
        return orbitState(orbitA, TWO_PI * t + phases.mAOffset);
    }

    function stateB(t) {
        return orbitState(orbitB, TWO_PI * t + phases.mBOffset);
    }

    function distSq(p, q) {
        const dx = p.x - q.x, dy = p.y - q.y;
        return dx * dx + dy * dy;
    }

    function hermite(s0, s1, u, dt) {
        const u2 = u * u, u3 = u2 * u;
        const h00 = 2 * u3 - 3 * u2 + 1;
        const h10 = u3 - 2 * u2 + u;
        const h01 = -2 * u3 + 3 * u2;
        const h11 = u3 - u2;
        return {
            x: h00 * s0.x + h10 * dt * s0.vx + h01 * s1.x + h11 * dt * s1.vx,
            y: h00 * s0.y + h10 * dt * s0.vy + h01 * s1.y + h11 * dt * s1.vy
        };
    }

    // Like PropagationService, the last knot is pulled back onto the end of the window, so the final
    // interval is short rather than running past it. Every sample stays bracketed by two knots.
    function knotCount() {
        return Math.max(1, Math.ceil(PERIOD_SECONDS / (state.stride * state.stepSeconds)));
    }

    function interpPos(stateFn, t) {
        const knotDtParam = (state.stride * state.stepSeconds) / PERIOD_SECONDS;
        const k = Math.min(Math.floor(t / knotDtParam), knotCount() - 1);
        const t0 = k * knotDtParam;
        const t1 = Math.min(t0 + knotDtParam, 1);
        const u = (t - t0) / (t1 - t0);
        return hermite(stateFn(t0), stateFn(t1), u, (t1 - t0) * PERIOD_SECONDS);
    }

    function algoPos(stateFn, t) {
        const stepDt = state.stepSeconds / PERIOD_SECONDS;
        const maxStep = Math.floor(1 / stepDt);
        const idx = Math.min(maxStep - 1, Math.max(0, Math.floor(t / stepDt)));
        const t0 = idx * stepDt;
        const t1 = (idx + 1) * stepDt;
        const u = (t - t0) / (t1 - t0);
        const p0 = interpPos(stateFn, t0);
        const p1 = interpPos(stateFn, t1);
        return {
            x: (1 - u) * p0.x + u * p1.x,
            y: (1 - u) * p0.y + u * p1.y
        };
    }

    function readColors() {
        const css = getComputedStyle(document.documentElement);
        const get = name => css.getPropertyValue(name).trim();
        return {
            fg: get('--text'),
            muted: get('--muted'),
            border: get('--border'),
            accent: get('--accent'),
            accentBright: get('--accent-bright'),
            err: get('--err'),
            blue: get('--series-blue'),
            green: get('--series-green'),
            purple: get('--series-purple')
        };
    }

    function fitCanvas(canvas, ctx) {
        const dpr = window.devicePixelRatio || 1;
        const w = canvas.clientWidth, h = canvas.clientHeight;
        if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
            canvas.width = Math.round(w * dpr);
            canvas.height = Math.round(h * dpr);
        }
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        return {w, h};
    }

    function sceneScale(w, h) {
        const s = Math.min(w / (2 * SCENE_HALF_W), h / (2 * SCENE_HALF_H));
        return {sx: s, sy: s, ox: w / 2, oy: h / 2};
    }

    function sceneToPx(p, w, h) {
        const {sx, sy, ox, oy} = sceneScale(w, h);
        return {x: ox + p.x * sx, y: oy - p.y * sy};
    }

    function cellOf(p) {
        return {cx: Math.floor(p.x / state.cellSize), cy: Math.floor(p.y / state.cellSize)};
    }

    function fillCell(ctx, cx, cy, w, h, color, alpha) {
        const tl = sceneToPx({x: cx * state.cellSize, y: (cy + 1) * state.cellSize}, w, h);
        const {sx, sy} = sceneScale(w, h);
        ctx.fillStyle = color;
        ctx.globalAlpha = alpha;
        ctx.fillRect(tl.x, tl.y, state.cellSize * sx, state.cellSize * sy);
        ctx.globalAlpha = 1;
    }

    function strokeCell(ctx, cx, cy, w, h, color, lineWidth) {
        const tl = sceneToPx({x: cx * state.cellSize, y: (cy + 1) * state.cellSize}, w, h);
        const {sx, sy} = sceneScale(w, h);
        ctx.strokeStyle = color;
        ctx.lineWidth = lineWidth;
        ctx.strokeRect(tl.x + 0.5, tl.y + 0.5, state.cellSize * sx - 1, state.cellSize * sy - 1);
    }

    function drawGrid(ctx, w, h, C) {
        const cell = state.cellSize;
        ctx.strokeStyle = C.border;
        ctx.globalAlpha = 0.5;
        ctx.lineWidth = 1;
        ctx.beginPath();
        for (let x = -SCENE_HALF_W; x <= SCENE_HALF_W + 0.001; x += cell) {
            const px = sceneToPx({x, y: 0}, w, h).x;
            ctx.moveTo(px, 0);
            ctx.lineTo(px, h);
        }
        for (let y = -SCENE_HALF_H; y <= SCENE_HALF_H + 0.001; y += cell) {
            const py = sceneToPx({x: 0, y}, w, h).y;
            ctx.moveTo(0, py);
            ctx.lineTo(w, py);
        }
        ctx.stroke();
        ctx.globalAlpha = 1;
    }

    function drawTrajectory(ctx, traj, color, w, h) {
        ctx.strokeStyle = color;
        ctx.lineWidth = 1.5;
        ctx.globalAlpha = 0.45;
        ctx.beginPath();
        for (let i = 0; i <= 240; i++) {
            const p = sceneToPx(traj(i / 240), w, h);
            if (i === 0) ctx.moveTo(p.x, p.y); else ctx.lineTo(p.x, p.y);
        }
        ctx.stroke();
        ctx.globalAlpha = 1;
    }

    function drawKnot(ctx, p, w, h, C) {
        const px = sceneToPx(p, w, h);
        ctx.beginPath();
        ctx.arc(px.x, px.y, 4, 0, TWO_PI);
        ctx.fillStyle = C.accentBright;
        ctx.fill();
    }

    function drawSample(ctx, p, w, h, color) {
        const px = sceneToPx(p, w, h);
        ctx.beginPath();
        ctx.arc(px.x, px.y, 2.2, 0, TWO_PI);
        ctx.fillStyle = color;
        ctx.fill();
    }

    function drawDots(ctx, w, h, C) {
        const knotDt = (state.stride * state.stepSeconds) / PERIOD_SECONDS;
        const stepDt = state.stepSeconds / PERIOD_SECONDS;

        if (state.showKnots) {
            const last = knotCount();
            for (let k = 0; k <= last; k++) {
                const t = Math.min(k * knotDt, 1);
                drawKnot(ctx, stateA(t), w, h, C);
                drawKnot(ctx, stateB(t), w, h, C);
            }
        }

        if (state.showInterp) {
            for (let i = 0; i * stepDt <= 1.0001; i++) {
                if (i % state.stride === 0) continue;
                const t = i * stepDt;
                drawSample(ctx, interpPos(stateA, t), w, h, C.blue);
                drawSample(ctx, interpPos(stateB, t), w, h, C.blue);
            }
        }
    }

    function drawDisc(ctx, p, sceneRadius, color, w, h, fillAlpha, strokeAlpha) {
        const px = sceneToPx(p, w, h);
        const {sx} = sceneScale(w, h);
        ctx.beginPath();
        ctx.arc(px.x, px.y, sceneRadius * sx, 0, TWO_PI);
        ctx.fillStyle = color;
        ctx.globalAlpha = fillAlpha;
        ctx.fill();
        ctx.globalAlpha = strokeAlpha;
        ctx.lineWidth = 1;
        ctx.strokeStyle = color;
        ctx.stroke();
        ctx.globalAlpha = 1;
    }

    function drawSatellite(ctx, p, color, w, h) {
        const px = sceneToPx(p, w, h);
        ctx.beginPath();
        ctx.arc(px.x, px.y, 6, 0, TWO_PI);
        ctx.fillStyle = color;
        ctx.fill();
    }

    function drawSatelliteGhost(ctx, p, color, w, h) {
        const px = sceneToPx(p, w, h);
        ctx.beginPath();
        ctx.arc(px.x, px.y, 7, 0, TWO_PI);
        ctx.strokeStyle = color;
        ctx.lineWidth = 1.5;
        ctx.globalAlpha = 0.75;
        ctx.stroke();
        ctx.globalAlpha = 1;
    }

    function drawEarth(ctx, w, h, C) {
        const px = sceneToPx({x: 0, y: 0}, w, h);
        ctx.beginPath();
        ctx.arc(px.x, px.y, 7, 0, TWO_PI);
        ctx.fillStyle = C.muted;
        ctx.fill();
        ctx.lineWidth = 1;
        ctx.strokeStyle = C.fg;
        ctx.globalAlpha = 0.6;
        ctx.stroke();
        ctx.globalAlpha = 1;
    }

    function drawScene() {
        const {w, h} = fitCanvas(sceneCanvas, sceneCtx);
        const C = readColors();
        sceneCtx.clearRect(0, 0, w, h);

        const aIdeal = stateA(state.t);
        const bIdeal = stateB(state.t);
        const a = algoPos(stateA, state.t);
        const b = algoPos(stateB, state.t);
        const aCell = cellOf(a);
        const bCell = cellOf(b);
        const sameCell = aCell.cx === bCell.cx && aCell.cy === bCell.cy;

        if (state.showGrid) {
            drawGrid(sceneCtx, w, h, C);
            if (sameCell) {
                fillCell(sceneCtx, aCell.cx, aCell.cy, w, h, C.accentBright, 0.45);
            } else {
                fillCell(sceneCtx, aCell.cx, aCell.cy, w, h, C.accent, 0.30);
                fillCell(sceneCtx, bCell.cx, bCell.cy, w, h, C.err, 0.30);
            }
            const isHit = (cx, cy) =>
                (cx === aCell.cx && cy === aCell.cy) ||
                (cx === bCell.cx && cy === bCell.cy);
            for (const home of [aCell, bCell]) {
                strokeCell(sceneCtx, home.cx, home.cy, w, h, C.purple, 1);
                for (const off of HALF_NEIGHBORS_2D) {
                    const ncx = home.cx + off.dx, ncy = home.cy + off.dy;
                    const hit = !sameCell && isHit(ncx, ncy);
                    strokeCell(sceneCtx, ncx, ncy, w, h, hit ? C.accentBright : C.purple, hit ? 2.5 : 1);
                }
            }
            if (sameCell) {
                strokeCell(sceneCtx, aCell.cx, aCell.cy, w, h, C.accentBright, 2.5);
            }
        }

        drawTrajectory(sceneCtx, stateA, C.accent, w, h);
        drawTrajectory(sceneCtx, stateB, C.err, w, h);

        drawEarth(sceneCtx, w, h, C);

        drawDots(sceneCtx, w, h, C);

        if (state.showTolerance) {
            drawDisc(sceneCtx, a, state.tolerance, C.accent, w, h, 0.10, 0.5);
            drawDisc(sceneCtx, b, state.tolerance, C.err, w, h, 0.10, 0.5);
        }

        if (state.showTruth) {
            drawSatelliteGhost(sceneCtx, aIdeal, C.accent, w, h);
            drawSatelliteGhost(sceneCtx, bIdeal, C.err, w, h);
        }
        drawSatellite(sceneCtx, a, C.accent, w, h);
        drawSatellite(sceneCtx, b, C.err, w, h);

        const dist = Math.sqrt(distSq(a, b));
        const conjunction = dist < COLLISION_THRESHOLD_KM;
        const isForwardNeighbor = (from, to) => HALF_NEIGHBORS_2D.some(o =>
            from.cx + o.dx === to.cx && from.cy + o.dy === to.cy);
        const gridFlag = sameCell
            || isForwardNeighbor(aCell, bCell)
            || isForwardNeighbor(bCell, aCell);
        gridBadge.classList.toggle('active', gridFlag);
        coarseBadge.classList.toggle('active', gridFlag && dist < state.tolerance);
        conjunctionBadge.classList.toggle('active', conjunction);

        if (state.showRefine) {
            sceneCtx.beginPath();
            const pa = sceneToPx(a, w, h), pb = sceneToPx(b, w, h);
            sceneCtx.moveTo(pa.x, pa.y);
            sceneCtx.lineTo(pb.x, pb.y);
            sceneCtx.strokeStyle = dist < state.tolerance ? C.green : C.muted;
            sceneCtx.lineWidth = 1;
            sceneCtx.setLineDash([4, 3]);
            sceneCtx.stroke();
            sceneCtx.setLineDash([]);
        }
    }

    function drawRefineChart() {
        const {w, h} = fitCanvas(refineCanvas, refineCtx);
        const C = readColors();
        refineCtx.clearRect(0, 0, w, h);

        const padL = 38, padR = 14, padT = 14, padB = 22;
        const plotW = w - padL - padR;
        const plotH = h - padT - padB;

        const stepDt = state.stepSeconds / PERIOD_SECONDS;
        const maxStep = Math.floor(1 / stepDt);

        const N = 500;
        let dMax = 0;
        const truthD = new Array(N + 1);
        const algoD = new Array(N + 1);
        let segIdx = -1;
        let A0, A1, B0, B1;
        for (let i = 0; i <= N; i++) {
            const t = i / N;
            const dT = Math.sqrt(distSq(stateA(t), stateB(t)));
            truthD[i] = dT;
            if (dT > dMax) dMax = dT;

            const idx = Math.min(maxStep - 1, Math.max(0, Math.floor(t / stepDt)));
            if (idx !== segIdx) {
                segIdx = idx;
                A0 = interpPos(stateA, idx * stepDt);
                A1 = interpPos(stateA, (idx + 1) * stepDt);
                B0 = interpPos(stateB, idx * stepDt);
                B1 = interpPos(stateB, (idx + 1) * stepDt);
            }
            const u = (t - idx * stepDt) / stepDt;
            const ax = (1 - u) * A0.x + u * A1.x;
            const ay = (1 - u) * A0.y + u * A1.y;
            const bx = (1 - u) * B0.x + u * B1.x;
            const by = (1 - u) * B0.y + u * B1.y;
            const dx = ax - bx, dy = ay - by;
            const dA = Math.sqrt(dx * dx + dy * dy);
            algoD[i] = dA;
            if (dA > dMax) dMax = dA;
        }
        const yMax = Math.max(dMax, state.tolerance * 1.4);
        const tx = t => padL + t * plotW;
        const ty = d => padT + (1 - d / yMax) * plotH;

        refineCtx.strokeStyle = C.border;
        refineCtx.lineWidth = 1;
        refineCtx.beginPath();
        refineCtx.moveTo(padL, padT);
        refineCtx.lineTo(padL, padT + plotH);
        refineCtx.lineTo(padL + plotW, padT + plotH);
        refineCtx.stroke();

        refineCtx.setLineDash([4, 3]);
        refineCtx.strokeStyle = C.accent;
        refineCtx.beginPath();
        refineCtx.moveTo(padL, ty(state.tolerance));
        refineCtx.lineTo(padL + plotW, ty(state.tolerance));
        refineCtx.stroke();

        refineCtx.strokeStyle = C.err;
        refineCtx.beginPath();
        refineCtx.moveTo(padL, ty(COLLISION_THRESHOLD_KM));
        refineCtx.lineTo(padL + plotW, ty(COLLISION_THRESHOLD_KM));
        refineCtx.stroke();
        refineCtx.setLineDash([]);

        refineCtx.strokeStyle = C.fg;
        refineCtx.globalAlpha = 0.85;
        refineCtx.lineWidth = 1.5;
        refineCtx.beginPath();
        for (let i = 0; i <= N; i++) {
            const px = tx(i / N), py = ty(truthD[i]);
            if (i === 0) refineCtx.moveTo(px, py); else refineCtx.lineTo(px, py);
        }
        refineCtx.stroke();
        refineCtx.globalAlpha = 1;

        refineCtx.strokeStyle = C.green;
        refineCtx.globalAlpha = 0.85;
        refineCtx.lineWidth = 1.5;
        refineCtx.beginPath();
        for (let i = 0; i <= N; i++) {
            const px = tx(i / N), py = ty(algoD[i]);
            if (i === 0) refineCtx.moveTo(px, py); else refineCtx.lineTo(px, py);
        }
        refineCtx.stroke();
        refineCtx.globalAlpha = 1;

        const step = Math.max(0, Math.min(maxStep, Math.round(state.t / stepDt)));

        const intervals = [];
        if (step > 0) intervals.push({s0: step - 1, s1: step});
        if (step < maxStep) intervals.push({s0: step, s1: step + 1});

        const candidates = intervals.map(({s0, s1}) => {
            const t0 = s0 * stepDt;
            const t1 = s1 * stepDt;
            const A0 = interpPos(stateA, t0), A1 = interpPos(stateA, t1);
            const B0 = interpPos(stateB, t0), B1 = interpPos(stateB, t1);
            const sepX0 = A0.x - B0.x, sepY0 = A0.y - B0.y;
            const dSepX = (A1.x - B1.x) - sepX0;
            const dSepY = (A1.y - B1.y) - sepY0;
            const sep0Sq = sepX0 * sepX0 + sepY0 * sepY0;
            const sepDotD = sepX0 * dSepX + sepY0 * dSepY;
            const dSepSq = dSepX * dSepX + dSepY * dSepY;
            const u = dSepSq < 1e-12 ? 0.5 : Math.max(0, Math.min(1, -sepDotD / dSepSq));
            const dStar = Math.sqrt(Math.max(0, sep0Sq + 2 * sepDotD * u + dSepSq * u * u));
            return {t0, t1, a: sep0Sq, b: 2 * sepDotD, c: dSepSq, u, dStar};
        });

        const winner = candidates.reduce(
            (best, c) => !best || c.dStar < best.dStar ? c : best, null);

        if (winner) {
            const tStar = winner.t0 + winner.u * (winner.t1 - winner.t0);
            refineCtx.strokeStyle = C.green;
            refineCtx.setLineDash([2, 3]);
            refineCtx.beginPath();
            refineCtx.moveTo(tx(tStar), ty(0));
            refineCtx.lineTo(tx(tStar), ty(winner.dStar));
            refineCtx.stroke();
            refineCtx.setLineDash([]);
            refineCtx.fillStyle = C.green;
            refineCtx.beginPath();
            refineCtx.arc(tx(tStar), ty(winner.dStar), 4, 0, TWO_PI);
            refineCtx.fill();
        }

        refineCtx.strokeStyle = C.accent;
        refineCtx.lineWidth = 1;
        refineCtx.beginPath();
        refineCtx.moveTo(tx(state.t), padT);
        refineCtx.lineTo(tx(state.t), padT + plotH);
        refineCtx.stroke();

        refineCtx.fillStyle = C.muted;
        refineCtx.font = '10px "IBM Plex Mono", monospace';
        refineCtx.textBaseline = 'top';
        refineCtx.fillText('0 s', padL - 6, padT + plotH + 4);
        refineCtx.fillText(PERIOD_SECONDS + ' s', padL + plotW - 28, padT + plotH + 4);
        refineCtx.fillText('time', padL + plotW / 2 - 10, padT + plotH + 4);
        refineCtx.textBaseline = 'middle';
        refineCtx.textAlign = 'right';
        refineCtx.fillText(yMax.toFixed(0) + ' km', padL - 4, padT);
        refineCtx.fillText('0', padL - 4, padT + plotH);
        refineCtx.fillStyle = C.accent;
        refineCtx.fillText('tol', padL - 4, ty(state.tolerance));
        refineCtx.fillStyle = C.err;
        refineCtx.fillText('coll', padL - 4, ty(COLLISION_THRESHOLD_KM));
        refineCtx.textAlign = 'left';
    }

    function redraw() {
        drawScene();
        drawRefineChart();
    }

    const SLIDERS = [
        {id: 'tolerance', valId: 'tolerance-val', key: 'tolerance'},
        {id: 'cell-size', valId: 'cell-size-val', key: 'cellSize'},
        {id: 'step-seconds', valId: 'step-seconds-val', key: 'stepSeconds'},
        {id: 'stride', valId: 'stride-val', key: 'stride'}
    ];

    const TOGGLES = [
        {id: 'show-grid', key: 'showGrid'},
        {id: 'show-knots', key: 'showKnots'},
        {id: 'show-interp', key: 'showInterp'},
        {id: 'show-tolerance', key: 'showTolerance'},
        {id: 'show-refine', key: 'showRefine'},
        {id: 'show-truth', key: 'showTruth'}
    ];

    // Readout precision follows the slider's own step, so each value reads as it does in application.properties.
    function formatSlider(el) {
        return parseFloat(el.value).toFixed((el.step.split('.')[1] || '').length);
    }

    function setSliderUi(id, valId, v) {
        const el = document.getElementById(id);
        el.value = v;
        document.getElementById(valId).textContent = formatSlider(el);
    }

    function setTimeUi(t) {
        document.getElementById('time').value = Math.round(t * 1000);
        document.getElementById('time-label').textContent = 't = ' + (t * PERIOD_SECONDS).toFixed(0) + ' s';
    }

    function setupControls() {
        for (const s of SLIDERS) {
            const el = document.getElementById(s.id);
            const valEl = document.getElementById(s.valId);
            el.addEventListener('input', () => {
                state[s.key] = parseFloat(el.value);
                valEl.textContent = formatSlider(el);
                redraw();
            });
        }

        for (const t of TOGGLES) {
            const el = document.getElementById(t.id);
            el.addEventListener('change', () => {
                state[t.key] = el.checked;
                redraw();
            });
        }

        document.getElementById('time').addEventListener('input', e => {
            state.t = parseFloat(e.target.value) / 1000;
            setTimeUi(state.t);
            redraw();
        });

        const playBtn = document.getElementById('play');
        playBtn.addEventListener('click', () => {
            state.playing = !state.playing;
            playBtn.textContent = state.playing ? '⏸' : '▶';
            if (state.playing) requestAnimationFrame(playLoop);
        });

        document.getElementById('reset-defaults').addEventListener('click', resetDefaults);

        const presetRow = document.getElementById('preset-row');
        const presetBtns = PRESETS.map(p => {
            const btn = document.createElement('button');
            btn.className = 'pg-preset-btn';
            btn.textContent = p.label;
            btn.dataset.preset = p.id;
            btn.addEventListener('click', () => selectPreset(p.id));
            presetRow.appendChild(btn);
            return btn;
        });

        function selectPreset(id) {
            const preset = PRESETS.find(p => p.id === id);
            applyPreset(preset);
            for (const b of presetBtns) b.classList.toggle('active', b.dataset.preset === id);
            redraw();
        }

        selectPreset(PRESETS[0].id);
    }

    function resetDefaults() {
        Object.assign(state, DEFAULTS);
        for (const s of SLIDERS) setSliderUi(s.id, s.valId, state[s.key]);
        redraw();
    }

    function playLoop() {
        if (!state.playing) return;
        state.t = (state.t + 0.0008) % 1;
        setTimeUi(state.t);
        redraw();
        requestAnimationFrame(playLoop);
    }

    function init() {
        setupControls();
        setTimeUi(state.t);
        const ro = new ResizeObserver(redraw);
        ro.observe(sceneCanvas);
        ro.observe(refineCanvas);
        document.querySelector('.theme-btn')?.addEventListener('click', () => {
            requestAnimationFrame(redraw);
        });
        redraw();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
