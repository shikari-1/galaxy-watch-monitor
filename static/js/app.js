/**
 * Galaxy Watch 4 Desktop Hardware Monitor - Client Application
 * Handles Real-time SSE / REST telemetry, SVG Gauge rendering,
 * OLED Anti-Burn-In pixel shifting, Screen Wake Lock, and Watch Face switching.
 */

(function () {
  'use strict';

  // State
  let currentFaceIndex = 0;
  const faces = ['faceArc', 'faceCyber', 'faceMinimal', 'faceGrid', 'faceChrono'];
  const faceButtons = document.querySelectorAll('.theme-btn');
  let eventSource = null;
  let pollIntervalTimer = null;
  let updateIntervalMs = 1000;
  let wakeLock = null;
  let lastTapTime = 0;
  let burnInEnabled = true;
  let burnInStep = 0;

  // Theme State
  const THEME_NAMES = {
    cyber: 'Cyberpunk Neon',
    matrix: 'Toxic Matrix',
    solar: 'Solar Flare',
    synthwave: 'Synthwave Outrun',
    stealth: 'Stealth Ice'
  };
  let currentTheme = localStorage.getItem('gw4_theme') || 'cyber';

  // Thermal Alert Thresholds
  let warnThreshold = parseInt(localStorage.getItem('gw4_warn'), 10) || 75;
  let critThreshold = parseInt(localStorage.getItem('gw4_crit'), 10) || 83;

  // 8-point orbital pixel shift offsets to protect Super AMOLED displays
  const BURN_IN_OFFSETS = [
    { x: 0, y: 0 },
    { x: 1, y: 1 },
    { x: 2, y: 0 },
    { x: 1, y: -1 },
    { x: 0, y: -2 },
    { x: -1, y: -1 },
    { x: -2, y: 0 },
    { x: -1, y: 1 }
  ];

  // DOM Elements Cache
  const elements = {
    // Cyber face
    cyberClock: document.getElementById('cyberClock'),
    cyberFreq: document.getElementById('cyberFreq'),
    cyberGpuTemp: document.getElementById('cyberGpuTemp'),
    cyberGpuLoad: document.getElementById('cyberGpuLoad'),
    cyberGpuVram: document.getElementById('cyberGpuVram'),
    cyberGpuPwr: document.getElementById('cyberGpuPwr'),
    cyberCpuTemp: document.getElementById('cyberCpuTemp'),
    cyberCpuLoad: document.getElementById('cyberCpuLoad'),
    cyberCpuMhz: document.getElementById('cyberCpuMhz'),
    cyberRamVal: document.getElementById('cyberRamVal'),
    cyberRamPct: document.getElementById('cyberRamPct'),
    cyberGpuArc: document.getElementById('cyberGpuArc'),
    cyberCpuArc: document.getElementById('cyberCpuArc'),
    cyberRamArc: document.getElementById('cyberRamArc'),
    cyberThermalBadge: document.getElementById('cyberThermalBadge'),
    liveDot: document.getElementById('liveDot'),

    // Arc face
    hudHost: document.getElementById('hudHost'),
    hudClock: document.getElementById('hudClock'),
    hudNet: document.getElementById('hudNet'),
    hudGpuArc: document.getElementById('hudGpuArc'),
    hudGpuTemp: document.getElementById('hudGpuTemp'),
    hudGpuLoad: document.getElementById('hudGpuLoad'),
    hudCpuArc: document.getElementById('hudCpuArc'),
    hudCpuTemp: document.getElementById('hudCpuTemp'),
    hudCpuLoad: document.getElementById('hudCpuLoad'),
    hudVramBar: document.getElementById('hudVramBar'),
    hudVramVal: document.getElementById('hudVramVal'),
    hudRamBar: document.getElementById('hudRamBar'),
    hudRamVal: document.getElementById('hudRamVal'),
    hudThermalBadge: document.getElementById('hudThermalBadge'),

    // Minimal face
    minClock: document.getElementById('minClock'),
    minGpuTemp: document.getElementById('minGpuTemp'),
    minGpuLoad: document.getElementById('minGpuLoad'),
    minCpuTemp: document.getElementById('minCpuTemp'),
    minCpuLoad: document.getElementById('minCpuLoad'),
    minRam: document.getElementById('minRam'),
    minPwr: document.getElementById('minPwr'),

    // Grid face
    gridGpuTemp: document.getElementById('gridGpuTemp'),
    gridGpuLoad: document.getElementById('gridGpuLoad'),
    gridGpuVram: document.getElementById('gridGpuVram'),
    gridCpuTemp: document.getElementById('gridCpuTemp'),
    gridCpuLoad: document.getElementById('gridCpuLoad'),
    gridCpuClock: document.getElementById('gridCpuClock'),
    gridRamPct: document.getElementById('gridRamPct'),
    gridRamGb: document.getElementById('gridRamGb'),
    gridPwr: document.getElementById('gridPwr'),
    gridClockMini: document.getElementById('gridClockMini'),
    gridNet: document.getElementById('gridNet'),

    // Chrono Cockpit face
    chronoClock: document.getElementById('chronoClock'),
    chronoGpuFan: document.getElementById('chronoGpuFan'),
    chronoGpuTemp: document.getElementById('chronoGpuTemp'),
    chronoGpuLoad: document.getElementById('chronoGpuLoad'),
    chronoGpuVram: document.getElementById('chronoGpuVram'),
    chronoGpuClock: document.getElementById('chronoGpuClock'),
    chronoPwr: document.getElementById('chronoPwr'),
    chronoStatusBadge: document.getElementById('chronoStatusBadge'),
    chronoCpuTemp: document.getElementById('chronoCpuTemp'),
    chronoCpuLoad: document.getElementById('chronoCpuLoad'),
    chronoCpuFreq: document.getElementById('chronoCpuFreq'),
    chronoNet: document.getElementById('chronoNet'),
    chronoRam: document.getElementById('chronoRam'),
    chronoGpuArc: document.getElementById('chronoGpuArc'),
    chronoCpuArc: document.getElementById('chronoCpuArc'),
    chronoRamArc: document.getElementById('chronoRamArc'),

    // Theme & Thermal controls
    activeThemeLabel: document.getElementById('activeThemeLabel'),
    paletteButtons: document.querySelectorAll('.palette-btn'),
    thermalStatusPill: document.getElementById('thermalStatusPill'),
    warnSlider: document.getElementById('warnSlider'),
    critSlider: document.getElementById('critSlider'),
    warnVal: document.getElementById('warnVal'),
    critVal: document.getElementById('critVal'),

    // Sidebar summary
    pcGpuName: document.getElementById('pcGpuName'),
    pcGpuStats: document.getElementById('pcGpuStats'),
    pcCpuStats: document.getElementById('pcCpuStats'),
    pcRamStats: document.getElementById('pcRamStats'),

    // Controls & Wrapper
    burnInWrapper: document.getElementById('burnInWrapper'),
    gw4Viewport: document.getElementById('gw4Viewport'),
    watchCaseBezel: document.getElementById('watchCaseBezel'),
    btnCopyUrl: document.getElementById('btnCopyUrl'),
    watchUrl: document.getElementById('watchUrl'),
    toast: document.getElementById('toast'),
    btnFullscreen: document.getElementById('btnFullscreen'),
    btnNextFace: document.getElementById('btnNextFace'),
    btnPureWatch: document.getElementById('btnPureWatch'),
    intervalSelect: document.getElementById('intervalSelect'),
    burnInToggle: document.getElementById('burnInToggle'),
    frameToggle: document.getElementById('frameToggle'),
    touchHint: document.getElementById('touchHint')
  };

  /**
   * Request Screen Wake Lock so display does not turn off on the desk charger
   */
  async function requestWakeLock() {
    try {
      if ('wakeLock' in navigator) {
        wakeLock = await navigator.wakeLock.request('screen');
        console.log('Screen Wake Lock acquired.');
        wakeLock.addEventListener('release', () => {
          console.log('Screen Wake Lock was released.');
        });
      }
    } catch (err) {
      console.warn('Wake Lock request error:', err);
    }
  }

  // Re-acquire wake lock if page visibility changes (e.g., coming back from background)
  document.addEventListener('visibilitychange', () => {
    if (wakeLock !== null && document.visibilityState === 'visible') {
      requestWakeLock();
    }
  });

  /**
   * Fullscreen API Helpers
   */
  function enterFullscreen() {
    const docEl = document.documentElement;
    const requestFs = docEl.requestFullscreen ||
                      docEl.webkitRequestFullscreen ||
                      docEl.mozRequestFullScreen ||
                      docEl.msRequestFullscreen;

    if (requestFs && !document.fullscreenElement && !document.webkitFullscreenElement) {
      requestFs.call(docEl).catch(() => {
        // Will be triggered on first touch interaction
      });
    }
  }

  function toggleFullscreen() {
    const isFullscreen = document.fullscreenElement || document.webkitFullscreenElement;
    if (!isFullscreen) {
      enterFullscreen();
    } else {
      const exitFs = document.exitFullscreen || document.webkitExitFullscreen;
      if (exitFs) exitFs.call(document);
    }
  }

  // Automatic Fullscreen Trigger
  function initAutoFullscreen() {
    // Attempt fullscreen immediately
    enterFullscreen();

    // Immediate user activation fallback: on first tap/touch, go fullscreen immediately
    const onUserTouch = () => {
      enterFullscreen();
      ['touchstart', 'touchend', 'click', 'pointerdown'].forEach(evt => {
        window.removeEventListener(evt, onUserTouch, { capture: true });
      });
    };

    ['touchstart', 'touchend', 'click', 'pointerdown'].forEach(evt => {
      window.addEventListener(evt, onUserTouch, { capture: true, passive: true });
    });
  }

  /**
   * OLED Anti-Burn-In Pixel Shifting Routine & Scale preservation
   * Subtly moves layout coordinates every 90 seconds while preserving scale
   */
  function applyBurnInShift() {
    if (!elements.burnInWrapper) return;
    const offset = burnInEnabled ? BURN_IN_OFFSETS[burnInStep] : { x: 0, y: 0 };
    const scale = getComputedStyle(document.documentElement).getPropertyValue('--gw4-scale') || 1;
    elements.burnInWrapper.style.transform = `scale(${scale}) translate(${offset.x}px, ${offset.y}px)`;
  }

  function advanceBurnInStep() {
    if (!burnInEnabled) return;
    burnInStep = (burnInStep + 1) % BURN_IN_OFFSETS.length;
    applyBurnInShift();
  }

  setInterval(advanceBurnInStep, 90000); // Every 90 seconds

  /**
   * Universal Viewport Auto-Scaler for Smartwatches
   * Ensures the 380px circular telemetry canvas fits 100% inside any watch screen
   * (e.g. 200px, 240px, 280px, 320px, 396px, 450px) without ever overflowing.
   */
  function updateViewportScale() {
    if (!elements.gw4Viewport) return;

    const screenDim = Math.min(window.innerWidth, window.innerHeight);
    const isWatch = document.body.classList.contains('pure-watch-mode') || window.innerWidth <= 500;
    
    let scale = 1;
    if (isWatch && screenDim > 0) {
      // 93% of the watch screen dimension provides a safe margin from the circular AMOLED bezel
      const safeDiameter = screenDim * 0.93;
      scale = Math.min(1.2, safeDiameter / 380);
    } else {
      scale = 1;
    }

    document.documentElement.style.setProperty('--gw4-scale', scale.toFixed(3));
    applyBurnInShift();
  }

  window.addEventListener('resize', updateViewportScale);
  window.addEventListener('orientationchange', updateViewportScale);

  /**
   * Switch Active Watch Face
   */
  function setWatchFace(faceName) {
    faces.forEach((fId, idx) => {
      const faceEl = document.getElementById(fId);
      if (faceEl) {
        if (fId === faceName || fId === `face${faceName.charAt(0).toUpperCase() + faceName.slice(1)}`) {
          faceEl.classList.add('active');
          currentFaceIndex = idx;
        } else {
          faceEl.classList.remove('active');
        }
      }
    });

    // Update sidebar buttons
    faceButtons.forEach(btn => {
      const fKey = btn.getAttribute('data-face');
      if (faceName.toLowerCase().includes(fKey)) {
        btn.classList.add('active');
      } else {
        btn.classList.remove('active');
      }
    });

    // Save to localStorage
    try {
      localStorage.setItem('gw4_face', faces[currentFaceIndex]);
    } catch (e) {}

    // Fade touch hint after first switch
    if (elements.touchHint) {
      elements.touchHint.style.opacity = '0';
    }
  }

  function cycleNextFace() {
    currentFaceIndex = (currentFaceIndex + 1) % faces.length;
    setWatchFace(faces[currentFaceIndex]);
  }

  /**
   * Color Theme Switcher
   */
  function setTheme(themeName, showToastMsg = false) {
    if (!THEME_NAMES[themeName]) return;
    currentTheme = themeName;
    document.documentElement.setAttribute('data-theme', themeName);

    if (elements.activeThemeLabel) {
      elements.activeThemeLabel.textContent = THEME_NAMES[themeName];
    }

    if (elements.paletteButtons) {
      elements.paletteButtons.forEach(btn => {
        if (btn.getAttribute('data-theme') === themeName) {
          btn.classList.add('active');
        } else {
          btn.classList.remove('active');
        }
      });
    }

    try {
      localStorage.setItem('gw4_theme', themeName);
    } catch (e) {}

    if (showToastMsg) {
      showToast(`Theme: ${THEME_NAMES[themeName]}`);
    }
  }

  function cycleNextTheme() {
    const keys = Object.keys(THEME_NAMES);
    const nextIdx = (keys.indexOf(currentTheme) + 1) % keys.length;
    setTheme(keys[nextIdx], true);
  }

  /**
   * Helper to set SVG path dash progress safely
   */
  function setPathProgress(pathEl, percent, fallbackLength) {
    if (!pathEl) return;
    let len = fallbackLength;
    try {
      len = pathEl.getTotalLength ? pathEl.getTotalLength() : fallbackLength;
    } catch (e) {}
    pathEl.style.strokeDasharray = len;
    const offset = Math.max(0, len - (len * (percent / 100)));
    pathEl.style.strokeDashoffset = offset;
  }

  /**
   * Apply Thermal Warning & Critical Alerts
   */
  function applyThermalAlerts(gpuTemp, cpuTemp) {
    const isGpuWarn = gpuTemp >= warnThreshold && gpuTemp < critThreshold;
    const isGpuCrit = gpuTemp >= critThreshold;
    const isCpuWarn = cpuTemp >= warnThreshold && cpuTemp < critThreshold;
    const isCpuCrit = cpuTemp >= critThreshold;
    const isAnyCrit = isGpuCrit || isCpuCrit;
    const isAnyWarn = (isGpuWarn || isCpuWarn) && !isAnyCrit;

    // Sidebar status pill
    if (elements.thermalStatusPill) {
      if (isAnyCrit) {
        elements.thermalStatusPill.className = 'thermal-pill crit';
        elements.thermalStatusPill.textContent = 'CRITICAL HOT 🔥';
      } else if (isAnyWarn) {
        elements.thermalStatusPill.className = 'thermal-pill warn';
        elements.thermalStatusPill.textContent = 'ELEVATED ⚠️';
      } else {
        elements.thermalStatusPill.className = 'thermal-pill';
        elements.thermalStatusPill.textContent = 'OPTIMAL ❄️';
      }
    }

    // Floating badges on watch faces
    const badgeText = isAnyCrit ? '🔥 CRIT' : '⚠️ WARN';
    const isVisible = isAnyCrit || isAnyWarn;

    if (elements.cyberThermalBadge) {
      elements.cyberThermalBadge.classList.toggle('visible', isVisible);
      elements.cyberThermalBadge.textContent = badgeText;
    }
    if (elements.hudThermalBadge) {
      elements.hudThermalBadge.classList.toggle('visible', isVisible);
      elements.hudThermalBadge.textContent = badgeText;
    }
    if (elements.chronoStatusBadge) {
      if (isAnyCrit) {
        elements.chronoStatusBadge.className = 'chrono-status-badge crit';
        elements.chronoStatusBadge.innerHTML = '<span>🔥 HOT</span>';
      } else if (isAnyWarn) {
        elements.chronoStatusBadge.className = 'chrono-status-badge warn';
        elements.chronoStatusBadge.innerHTML = '<span>⚠️ WARN</span>';
      } else {
        elements.chronoStatusBadge.className = 'chrono-status-badge';
        elements.chronoStatusBadge.innerHTML = '<span>NORMAL</span>';
      }
    }

    // Toggle warning / critical classes on GPU temperatures
    const gpuTempEls = [
      elements.cyberGpuTemp,
      elements.hudGpuTemp,
      elements.minGpuTemp,
      elements.gridGpuTemp,
      elements.chronoGpuTemp
    ];
    gpuTempEls.forEach(el => {
      if (el) {
        el.classList.toggle('temp-warn', isGpuWarn);
        el.classList.toggle('temp-crit', isGpuCrit);
      }
    });

    // Toggle warning / critical classes on CPU temperatures
    const cpuTempEls = [
      elements.cyberCpuTemp,
      elements.hudCpuTemp,
      elements.minCpuTemp,
      elements.gridCpuTemp,
      elements.chronoCpuTemp
    ];
    cpuTempEls.forEach(el => {
      if (el) {
        el.classList.toggle('temp-warn', isCpuWarn);
        el.classList.toggle('temp-crit', isCpuCrit);
      }
    });
  }

  // Telemetry Liveness & IST Timezone tracking
  let serverTimeOffsetMs = 0;
  let lastTelemetryReceivedTime = 0;
  const STALE_TIMEOUT_MS = 2500; // 2.5 seconds without update from server = stale/offline

  // Formatter for IST (Asia/Kolkata - 24-hour HH:mm)
  let istFormatter = null;
  try {
    istFormatter = new Intl.DateTimeFormat('en-GB', {
      timeZone: 'Asia/Kolkata',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false
    });
  } catch (e) {
    console.warn('Intl Asia/Kolkata not supported, falling back to manual offset calculation');
  }

  function getIstTime() {
    // Sync with server epoch if available
    const targetEpoch = Date.now() + serverTimeOffsetMs;
    const targetDate = new Date(targetEpoch);

    if (istFormatter) {
      return istFormatter.format(targetDate);
    }

    // Manual fallback for IST (UTC + 5 hours 30 mins)
    const utcMs = targetDate.getTime() + (targetDate.getTimezoneOffset() * 60000);
    const istDate = new Date(utcMs + (5.5 * 3600000));
    const hours = String(istDate.getHours()).padStart(2, '0');
    const minutes = String(istDate.getMinutes()).padStart(2, '0');
    return `${hours}:${minutes}`;
  }

  /**
   * Monitor Telemetry Liveness:
   * If updates from server stop for more than 2.5s, pause breathing on the clock.
   */
  function checkTelemetryLiveness() {
    const isAlive = (lastTelemetryReceivedTime > 0) && (Date.now() - lastTelemetryReceivedTime < STALE_TIMEOUT_MS);
    
    const clockEls = [
      elements.hudClock,
      elements.cyberClock,
      elements.minClock,
      elements.gridClockMini,
      elements.chronoClock
    ];

    clockEls.forEach(el => {
      if (el) {
        if (isAlive) {
          el.classList.remove('stale');
        } else {
          el.classList.add('stale');
        }
      }
    });

    if (elements.liveDot) {
      elements.liveDot.style.opacity = isAlive ? '1' : '0.2';
    }
  }

  setInterval(checkTelemetryLiveness, 500);

  /**
   * Update Digital Clocks in IST (24-Hour)
   */
  function updateClocks() {
    const timeStr = getIstTime();

    if (elements.cyberClock) elements.cyberClock.textContent = timeStr;
    if (elements.hudClock) elements.hudClock.textContent = timeStr;
    if (elements.minClock) elements.minClock.textContent = timeStr;
    if (elements.gridClockMini) elements.gridClockMini.textContent = timeStr;
    if (elements.chronoClock) elements.chronoClock.textContent = timeStr;

    checkTelemetryLiveness();
  }

  setInterval(updateClocks, 1000);
  updateClocks();

  /**
   * Update DOM Telemetry Elements
   */
  function updateTelemetry(data) {
    if (!data) return;

    lastTelemetryReceivedTime = Date.now();
    if (data.timestamp) {
      serverTimeOffsetMs = (data.timestamp * 1000) - Date.now();
    }
    checkTelemetryLiveness();

    const cpu = data.cpu || {};
    const gpu = data.gpu || {};
    const ram = data.ram || {};
    const net = data.net || {};
    const host = data.host || {};

    const gpuUsage = Math.round(gpu.usage || 0);
    const gpuTemp = Math.round(gpu.temperature || 0);
    const gpuVramGb = ((gpu.vram_used_mb || 0) / 1024).toFixed(1);
    const gpuVramPct = Math.round(gpu.vram_percent || 0);
    const gpuPwr = Math.round(gpu.power_w || 0);

    const cpuUsage = Math.round(cpu.usage || 0);
    const cpuTemp = Math.round(cpu.temperature || 0);
    const cpuFreqGhz = ((cpu.frequency_mhz || 3800) / 1000).toFixed(1);

    const ramPct = Math.round(ram.percent || 0);
    const ramUsedGb = (ram.used_gb || 0).toFixed(1);
    const ramTotalGb = (ram.total_gb || 16).toFixed(1);

    // Pulse live dot
    if (elements.liveDot) {
      elements.liveDot.style.transform = 'scale(1.4)';
      setTimeout(() => {
        if (elements.liveDot) elements.liveDot.style.transform = 'scale(1)';
      }, 200);
    }

    // --- FACE 1: CYBER RING ---
    if (elements.cyberGpuTemp) elements.cyberGpuTemp.textContent = gpuTemp;
    if (elements.cyberGpuLoad) elements.cyberGpuLoad.textContent = `${gpuUsage}%`;
    if (elements.cyberGpuVram) elements.cyberGpuVram.textContent = `${gpuVramGb}G`;
    if (elements.cyberGpuPwr) elements.cyberGpuPwr.textContent = gpuPwr;

    if (elements.cyberCpuTemp) elements.cyberCpuTemp.textContent = cpuTemp;
    if (elements.cyberCpuLoad) elements.cyberCpuLoad.textContent = `${cpuUsage}%`;
    if (elements.cyberCpuMhz) elements.cyberCpuMhz.textContent = cpu.frequency_mhz || 3800;
    if (elements.cyberFreq) elements.cyberFreq.textContent = `${cpuFreqGhz}G`;

    if (elements.cyberRamVal) elements.cyberRamVal.textContent = `${ramUsedGb}GB`;
    if (elements.cyberRamPct) elements.cyberRamPct.textContent = `${ramPct}%`;

    // Cyber SVG Arcs calculation (max dash = 380)
    // offset 380 = 0% filled, offset 0 = 100% filled
    if (elements.cyberGpuArc) {
      const gpuOffset = Math.max(0, 380 - (380 * (gpuUsage / 100)));
      elements.cyberGpuArc.style.strokeDashoffset = gpuOffset;
    }
    if (elements.cyberCpuArc) {
      const cpuOffset = Math.max(0, 380 - (380 * (cpuUsage / 100)));
      elements.cyberCpuArc.style.strokeDashoffset = cpuOffset;
    }
    if (elements.cyberRamArc) {
      const ramOffset = Math.max(0, 200 - (200 * (ramPct / 100)));
      elements.cyberRamArc.style.strokeDashoffset = ramOffset;
    }

    // Progressive color resolver: white (0-67%), warm amber (68-79%), orange (80-89%), bright red (90%+)
    function getMetricColor(pct, defaultCol = '') {
      if (pct >= 90) return '#ff0022';
      if (pct >= 80) return '#ff5500';
      if (pct >= 68) return '#ffb020';
      return defaultCol;
    }

    // --- FACE 2: DUAL ARC HUD (Clean 4-Metric Focused) ---
    if (elements.hudGpuTemp) elements.hudGpuTemp.textContent = gpuTemp;
    if (elements.hudCpuTemp) elements.hudCpuTemp.textContent = cpuTemp;

    // Dual Speedometer arcs: 0°C -> 0%, 90°C -> 100% full. Post 90°C till 100°C stays 100% full and red.
    const gpuTempPct = Math.min(100, Math.max(0, (gpuTemp / 90) * 100));
    const cpuTempPct = Math.min(100, Math.max(0, (cpuTemp / 90) * 100));
    setPathProgress(elements.hudGpuArc, gpuTempPct, 310);
    setPathProgress(elements.hudCpuArc, cpuTempPct, 310);

    // Arcs turn bright red once above 90% (81°C+ / 90°C+)
    if (elements.hudGpuArc) {
      elements.hudGpuArc.style.stroke = (gpuTempPct >= 90) ? '#ff0022' : '';
    }
    if (elements.hudGpuTemp) {
      elements.hudGpuTemp.style.color = getMetricColor(gpuTempPct, '#ffffff');
    }

    if (elements.hudCpuArc) {
      elements.hudCpuArc.style.stroke = (cpuTempPct >= 90) ? '#ff0022' : '';
    }
    if (elements.hudCpuTemp) {
      elements.hudCpuTemp.style.color = getMetricColor(cpuTempPct, '#ffffff');
    }

    // Memory formatted with full GB decimal precision and percentage
    const vramUsedGb = (gpu.vram_used_mb / 1024).toFixed(1);
    const vramTotalGb = (gpu.vram_total_mb / 1024).toFixed(1);
    if (elements.hudVramBar) {
      elements.hudVramBar.style.width = `${gpuVramPct}%`;
      if (gpuVramPct >= 90) {
        elements.hudVramBar.classList.add('crit-fill');
      } else {
        elements.hudVramBar.classList.remove('crit-fill');
      }
    }
    if (elements.hudVramVal) {
      elements.hudVramVal.textContent = `${vramUsedGb} / ${vramTotalGb}G (${gpuVramPct}%)`;
      elements.hudVramVal.style.color = getMetricColor(gpuVramPct, '#e5e7eb');
    }

    if (elements.hudRamBar) {
      elements.hudRamBar.style.width = `${ramPct}%`;
      if (ramPct >= 90) {
        elements.hudRamBar.classList.add('crit-fill');
      } else {
        elements.hudRamBar.classList.remove('crit-fill');
      }
    }
    if (elements.hudRamVal) {
      elements.hudRamVal.textContent = `${ramUsedGb} / ${ramTotalGb}G (${ramPct}%)`;
      elements.hudRamVal.style.color = getMetricColor(ramPct, '#e5e7eb');
    }

    // --- FACE 3: MINIMAL OLED ---
    if (elements.minGpuTemp) elements.minGpuTemp.textContent = gpuTemp;
    if (elements.minGpuLoad) elements.minGpuLoad.textContent = gpuUsage;
    if (elements.minCpuTemp) elements.minCpuTemp.textContent = cpuTemp;
    if (elements.minCpuLoad) elements.minCpuLoad.textContent = cpuUsage;
    if (elements.minRam) elements.minRam.textContent = `${ramPct}%`;
    if (elements.minPwr) elements.minPwr.textContent = `${gpuPwr}W`;

    // --- FACE 4: STAT GRID ---
    if (elements.gridGpuTemp) elements.gridGpuTemp.textContent = `${gpuTemp}°C`;
    if (elements.gridGpuLoad) elements.gridGpuLoad.textContent = `${gpuUsage}%`;
    if (elements.gridGpuVram) elements.gridGpuVram.textContent = `VRAM ${gpuVramGb}G`;
    if (elements.gridCpuTemp) elements.gridCpuTemp.textContent = `${cpuTemp}°C`;
    if (elements.gridCpuLoad) elements.gridCpuLoad.textContent = `${cpuUsage}%`;
    if (elements.gridCpuClock) elements.gridCpuClock.textContent = `${cpuFreqGhz} GHz`;
    if (elements.gridRamPct) elements.gridRamPct.textContent = `${ramPct}%`;
    if (elements.gridRamGb) elements.gridRamGb.textContent = `${ramUsedGb}G`;
    if (elements.gridPwr) elements.gridPwr.textContent = `${gpuPwr}W`;
    if (elements.gridNet) elements.gridNet.textContent = `${net.download_kbps || 0} KB/s`;

    // --- FACE 5: CHRONO COCKPIT ---
    const gpuFan = Math.round(gpu.fan_percent || 0);
    const gpuClock = Math.round(gpu.clock_mhz || 0);

    if (elements.chronoGpuTemp) elements.chronoGpuTemp.textContent = gpuTemp;
    if (elements.chronoGpuLoad) elements.chronoGpuLoad.textContent = `${gpuUsage}%`;
    if (elements.chronoGpuVram) elements.chronoGpuVram.textContent = `${gpuVramGb}G`;
    if (elements.chronoGpuClock) elements.chronoGpuClock.textContent = `${gpuClock}M`;
    if (elements.chronoGpuFan) elements.chronoGpuFan.textContent = `FAN ${gpuFan}%`;
    if (elements.chronoPwr) elements.chronoPwr.textContent = gpuPwr;

    if (elements.chronoCpuTemp) elements.chronoCpuTemp.textContent = cpuTemp;
    if (elements.chronoCpuLoad) elements.chronoCpuLoad.textContent = `${cpuUsage}%`;
    if (elements.chronoCpuFreq) elements.chronoCpuFreq.textContent = `${cpuFreqGhz}G`;
    if (elements.chronoNet) elements.chronoNet.textContent = `${net.download_kbps || 0}K/s`;

    if (elements.chronoRam) elements.chronoRam.textContent = `${ramUsedGb}GB (${ramPct}%)`;

    setPathProgress(elements.chronoGpuArc, gpuUsage, 240);
    setPathProgress(elements.chronoCpuArc, cpuUsage, 240);
    setPathProgress(elements.chronoRamArc, ramPct, 130);

    // Apply Thermal Alert Highlights across all active faces
    applyThermalAlerts(gpuTemp, cpuTemp);

    // --- DESKTOP SIDEBAR SUMMARY ---
    if (elements.pcGpuName && gpu.name) elements.pcGpuName.textContent = gpu.name;
    if (elements.pcGpuStats) elements.pcGpuStats.textContent = `${gpuTemp} °C | ${gpu.vram_used_mb || 0} / ${gpu.vram_total_mb || 0} MB`;
    if (elements.pcCpuStats) elements.pcCpuStats.textContent = `${cpuUsage} % | ${cpuTemp} °C`;
    if (elements.pcRamStats) elements.pcRamStats.textContent = `${ramUsedGb} GB / ${ramTotalGb} GB (${ramPct}%)`;
  }

  /**
   * Connect to Server-Sent Events (SSE) Stream
   */
  function startStream() {
    if (eventSource) {
      eventSource.close();
    }

    try {
      eventSource = new EventSource('/api/stream');

      eventSource.onmessage = function (event) {
        try {
          const data = JSON.parse(event.data);
          updateTelemetry(data);
        } catch (e) {
          console.error('Failed to parse SSE payload:', e);
        }
      };

      eventSource.onerror = function () {
        console.warn('SSE stream disconnected, falling back to REST polling...');
        eventSource.close();
        eventSource = null;
        startPolling();
      };
    } catch (e) {
      console.warn('SSE initialization failed, fallback to REST polling.');
      startPolling();
    }
  }

  /**
   * Fallback Polling Loop
   */
  function startPolling() {
    if (pollIntervalTimer) clearInterval(pollIntervalTimer);
    
    const fetchStats = async () => {
      try {
        const res = await fetch('/api/stats');
        if (res.ok) {
          const data = await res.json();
          updateTelemetry(data);
        }
      } catch (err) {
        console.warn('Fetch telemetry poll error:', err);
      }
    };

    fetchStats();
    pollIntervalTimer = setInterval(fetchStats, updateIntervalMs);
  }

  /**
   * Fullscreen Toggle
   */
  function toggleFullscreen() {
    if (!document.fullscreenElement) {
      document.documentElement.requestFullscreen().catch(err => {
        console.warn('Error attempting to enable fullscreen:', err.message);
      });
    } else {
      if (document.exitFullscreen) {
        document.exitFullscreen();
      }
    }
  }

  /**
   * Toast notification helper
   */
  function showToast(message) {
    if (!elements.toast) return;
    elements.toast.textContent = message;
    elements.toast.classList.add('show');
    setTimeout(() => {
      elements.toast.classList.remove('show');
    }, 2400);
  }

  /**
   * Generate Clean QR Code onto the Canvas
   * Generates a high-contrast QR code for phone scanning
   */
  function generateQrCode(text) {
    const canvas = document.getElementById('qrCanvas');
    if (!canvas) return;

    // Use a lightweight dynamic QR service or render local SVG/Canvas QR
    const ctx = canvas.getContext('2d');
    const img = new Image();
    const encoded = encodeURIComponent(text);
    img.crossOrigin = 'anonymous';
    img.src = `https://api.qrserver.com/v1/create-qr-code/?size=130x130&data=${encoded}&margin=2`;
    
    img.onload = () => {
      ctx.drawImage(img, 0, 0, 130, 130);
    };

    img.onerror = () => {
      // Fallback: draw clean stylish placeholder on error
      ctx.fillStyle = '#0a0c12';
      ctx.fillRect(0, 0, 130, 130);
      ctx.strokeStyle = '#00f3ff';
      ctx.strokeRect(10, 10, 110, 110);
      ctx.fillStyle = '#ffffff';
      ctx.font = '10px monospace';
      ctx.textAlign = 'center';
      ctx.fillText('PORT: 8080', 65, 70);
    };
  }

  /**
   * Setup Event Listeners
   */
  function setupListeners() {
    // Watch screen taps & long-press gesture
    if (elements.gw4Viewport) {
      let longPressTimer = null;
      let isLongPress = false;

      const startLongPress = () => {
        isLongPress = false;
        longPressTimer = setTimeout(() => {
          isLongPress = true;
          cycleNextTheme();
        }, 550);
      };

      const cancelLongPress = () => {
        if (longPressTimer) {
          clearTimeout(longPressTimer);
          longPressTimer = null;
        }
      };

      // Touch events for Watch
      elements.gw4Viewport.addEventListener('touchstart', startLongPress, { passive: true });
      elements.gw4Viewport.addEventListener('touchend', cancelLongPress, { passive: true });
      elements.gw4Viewport.addEventListener('touchmove', cancelLongPress, { passive: true });

      // Mouse events for Desktop preview
      elements.gw4Viewport.addEventListener('mousedown', startLongPress);
      elements.gw4Viewport.addEventListener('mouseup', cancelLongPress);
      elements.gw4Viewport.addEventListener('mouseleave', cancelLongPress);

      elements.gw4Viewport.addEventListener('click', (e) => {
        if (isLongPress) {
          isLongPress = false;
          return;
        }

        const currentTime = new Date().getTime();
        const tapLength = currentTime - lastTapTime;

        // Double tap (within 350ms) -> Cycle watch face
        if (tapLength < 350 && tapLength > 0) {
          cycleNextFace();
          lastTapTime = 0;
        } else {
          // Single tap -> Trigger Fullscreen immediately
          lastTapTime = currentTime;
          enterFullscreen();
        }

        // Keep wake lock alive on touch
        requestWakeLock();
      });
    }

    // Watch Face selector buttons
    faceButtons.forEach(btn => {
      btn.addEventListener('click', () => {
        const faceName = btn.getAttribute('data-face');
        setWatchFace(faceName);
      });
    });

    // Color Theme Palette Buttons
    if (elements.paletteButtons) {
      elements.paletteButtons.forEach(btn => {
        btn.addEventListener('click', () => {
          const tName = btn.getAttribute('data-theme');
          setTheme(tName, true);
        });
      });
    }

    // Thermal Threshold Sliders
    if (elements.warnSlider) {
      elements.warnSlider.value = warnThreshold;
      if (elements.warnVal) elements.warnVal.textContent = `${warnThreshold}°C`;
      elements.warnSlider.addEventListener('input', (e) => {
        warnThreshold = parseInt(e.target.value, 10);
        if (elements.warnVal) elements.warnVal.textContent = `${warnThreshold}°C`;
        try { localStorage.setItem('gw4_warn', warnThreshold); } catch (err) {}
      });
    }

    if (elements.critSlider) {
      elements.critSlider.value = critThreshold;
      if (elements.critVal) elements.critVal.textContent = `${critThreshold}°C`;
      elements.critSlider.addEventListener('input', (e) => {
        critThreshold = parseInt(e.target.value, 10);
        if (elements.critVal) elements.critVal.textContent = `${critThreshold}°C`;
        try { localStorage.setItem('gw4_crit', critThreshold); } catch (err) {}
      });
    }

    // Copy URL button
    if (elements.btnCopyUrl && elements.watchUrl) {
      elements.btnCopyUrl.addEventListener('click', () => {
        const text = elements.watchUrl.textContent.trim();
        navigator.clipboard.writeText(text).then(() => {
          showToast('Watch URL copied to clipboard!');
        }).catch(() => {
          showToast('Failed to copy. Please select and copy manually.');
        });
      });
    }

    // Action buttons
    if (elements.btnFullscreen) {
      elements.btnFullscreen.addEventListener('click', toggleFullscreen);
    }

    if (elements.btnNextFace) {
      elements.btnNextFace.addEventListener('click', cycleNextFace);
    }

    if (elements.btnPureWatch) {
      elements.btnPureWatch.addEventListener('click', () => {
        document.body.classList.toggle('pure-watch-mode');
        const isPure = document.body.classList.contains('pure-watch-mode');
        elements.btnPureWatch.textContent = isPure ? 'Show Companion' : 'Pure Watch Mode';
      });
    }

    // Bezel frame toggle
    if (elements.frameToggle && elements.watchCaseBezel) {
      elements.frameToggle.addEventListener('change', (e) => {
        if (e.target.checked) {
          elements.watchCaseBezel.style.background = '';
          elements.watchCaseBezel.style.boxShadow = '';
        } else {
          elements.watchCaseBezel.style.background = 'transparent';
          elements.watchCaseBezel.style.boxShadow = 'none';
        }
      });
    }

    // Burn-in switch
    if (elements.burnInToggle) {
      elements.burnInToggle.addEventListener('change', (e) => {
        burnInEnabled = e.target.checked;
        applyBurnInShift();
      });
    }

    // Telemetry rate selector
    if (elements.intervalSelect) {
      elements.intervalSelect.addEventListener('change', (e) => {
        updateIntervalMs = parseInt(e.target.value, 10) || 1000;
        startPolling();
      });
    }
  }

  /**
   * Initialization
   */
  function init() {
    // Auto pure-watch mode on small wearable screens or Android mobile devices
    if (window.innerWidth <= 768 || window.innerHeight <= 768 || /Android|Wear/i.test(navigator.userAgent)) {
      document.body.classList.add('pure-watch-mode');
    }

    updateViewportScale();

    // Apply saved or default theme
    setTheme(currentTheme, false);

    // Default to clean 4-metric Dual Arc HUD
    setWatchFace('faceArc');
    try {
      localStorage.setItem('gw4_face', 'faceArc');
    } catch (e) {}

    setupListeners();
    requestWakeLock();
    initAutoFullscreen();
    startStream();
  }

  // Run on DOM ready
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }

})();
