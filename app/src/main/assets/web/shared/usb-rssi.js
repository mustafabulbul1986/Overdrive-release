/**
 * OverDrive — USB RSSI device page module.
 *
 * A pure consumer of /api/usb-rssi: the daemon owns the scanner, the thresholds and the
 * decision, and this page renders that state and POSTs intent. The only thing it computes is
 * the chart.
 *
 * Inputs are seeded from the daemon on every poll EXCEPT while the user is editing them, so a
 * poll landing mid-keystroke cannot wipe what was typed but not saved yet.
 */
const UsbRssiPage = {
    POLL_MS: 700,
    timer: null,
    status: null,
    editing: false,
    sendingFirmware: false,

    init() {
        // Any edit freezes seeding until the next save, matching the inline-save pattern.
        document.addEventListener('input', (e) => {
            if (e.target && e.target.closest('.ur-field')) this.editing = true;
        });
        this.refresh();
        this.timer = setInterval(() => this.refresh(), this.POLL_MS);
        document.addEventListener('visibilitychange', () => {
            if (!document.hidden) this.refresh();
        });
    },

    // ============== Fetch / render ==============

    async refresh() {
        try {
            const resp = await fetch('/api/usb-rssi');
            const data = await resp.json();
            if (!data || !data.success) return;
            this.status = data;
            this.render();
        } catch (e) {
            console.warn('[UsbRssi] status fetch failed:', e);
            this.setText('urLinkBadge', this.t('usb_rssi.state_unreachable', 'Unreachable'));
        }
    },

    render() {
        const s = this.status;
        const c = s.config;
        this.chk('urEnabled', c.enabled);
        if (!this.editing) this.seed(c);

        // Everything below the master switch is inert while the device is off. Dimming and
        // pointer-events alone would still let the keyboard tab into the fields and type, so
        // the controls are disabled as well.
        document.querySelectorAll('.ur-gated').forEach((el) => {
            el.classList.toggle('off', !c.enabled);
            el.querySelectorAll('input, button, select, textarea')
                .forEach((f) => { f.disabled = !c.enabled; });
        });

        this.setText('urLinkBadge', c.enabled
            ? (s.running ? s.link : this.t('usb_rssi.state_starting', 'Starting…'))
            : this.t('usb_rssi.state_off', 'Off'));
        const note = document.getElementById('urLinkNote');
        if (note) {
            // No transport compiled in: say so plainly instead of leaving an empty page.
            const missing = c.enabled && !s.hasLink;
            note.style.display = missing ? '' : 'none';
            if (missing) note.textContent = this.t('usb_rssi.no_reader',
                'No USB reader is available in this build, so nothing is being read from the port.');
        }

        this.setText('urRssi', s.windowAverage === null || s.windowAverage === undefined ? '--' : s.windowAverage);
        const win = Math.min(c.nearWindowMs, c.farWindowMs);
        const age = s.beaconAgeMs >= 0 ? ' · ' + this.t('usb_rssi.last_packet', 'last packet')
            + ' ' + (s.beaconAgeMs / 1000).toFixed(1) + ' s' : '';
        this.setText('urSamples', s.windowCount + ' '
            + this.t('usb_rssi.readings_in', 'readings in') + ' ' + win + ' ms' + age);

        const pill = document.getElementById('urClass');
        if (pill) {
            const names = {
                near: this.t('usb_rssi.cls_near', 'Near'),
                far: this.t('usb_rssi.cls_far', 'Away'),
                between: this.t('usb_rssi.cls_between', 'In between'),
                sparse: this.t('usb_rssi.cls_sparse', 'Too few readings'),
                none: this.t('usb_rssi.cls_none', 'Not seen')
            };
            pill.textContent = names[s.classification] || s.classification;
            pill.className = 'ur-pill ' + s.classification;
        }
        this.setText('urDecision', s.decision
            ? this.t('usb_rssi.signal_says', 'Signal says') + ': '
                + (s.decision === 'near' ? this.t('usb_rssi.cls_near', 'Near') : this.t('usb_rssi.cls_far', 'Away'))
            : this.t('usb_rssi.signal_waiting', 'Signal has not settled yet'));

        this.setText('urScannerVersion', s.scannerVersion || '--');
        this.setText('urScannerUuid', s.scannerUuid || '--');
        const same = s.scannerUuid && c.uuid && s.scannerUuid === c.uuid;
        this.setText('urScannerUuidNote', s.scannerUuid && s.scannerUuid !== '-'
            ? (same ? this.t('usb_rssi.fw_uuid_same', 'matches the setting')
                    : this.t('usb_rssi.fw_uuid_diff', 'differs from the setting, sending…'))
            : '');

        if (s.firmwareState && s.firmwareState !== '-') {
            const kb = (n) => Math.round(n / 1024) + ' KB';
            this.setText('urFwState', s.firmwareState
                + (s.firmwareTotal ? ' · ' + kb(s.firmwareSent) + ' / ' + kb(s.firmwareTotal) : ''));
            const bar = document.getElementById('urFwBar');
            if (bar) bar.style.width = (s.firmwareTotal ? Math.round(100 * s.firmwareSent / s.firmwareTotal) : 0) + '%';
        }

        const ev = document.getElementById('urEvents');
        if (ev) ev.textContent = (s.events || []).slice().reverse().join('\n') || '--';

        this.drawChart(s.history || [], c);
    },

    seed(c) {
        ['nearRssi', 'farRssi', 'nearWindowMs', 'farWindowMs', 'percent',
         'nearMinSamples', 'farMinSamples', 'noSignalMs', 'major', 'minor'].forEach((k) => {
            const el = document.getElementById(k);
            if (el && document.activeElement !== el) el.value = c[k];
        });
        const u = document.getElementById('uuid');
        if (u && document.activeElement !== u) u.value = c.uuid || '';
    },

    /** Two minutes of samples as dots, with the two thresholds drawn across. */
    drawChart(history, c) {
        const cv = document.getElementById('urChart');
        if (!cv) return;
        const dpr = window.devicePixelRatio || 1;
        const W = cv.clientWidth, H = cv.clientHeight;
        if (!W || !H) return;
        cv.width = W * dpr;
        cv.height = H * dpr;
        const g = cv.getContext('2d');
        g.scale(dpr, dpr);
        g.clearRect(0, 0, W, H);

        const lo = -105, hi = -35;
        const y = (v) => H - ((Math.max(lo, Math.min(hi, v)) - lo) / (hi - lo)) * H;
        const x = (t) => W + (t / 120) * W;          // t is seconds ago (negative)
        const css = getComputedStyle(document.documentElement);
        const token = (name, fallback) => (css.getPropertyValue(name) || '').trim() || fallback;

        g.lineWidth = 1;
        g.strokeStyle = token('--border-subtle', '#333');
        g.fillStyle = token('--text-muted', '#888');
        g.font = '10px system-ui, sans-serif';
        for (let v = -100; v <= -40; v += 10) {
            g.beginPath();
            g.moveTo(0, y(v));
            g.lineTo(W, y(v));
            g.stroke();
            g.fillText(String(v), 2, y(v) - 2);
        }

        g.lineWidth = 2;
        g.setLineDash([6, 4]);
        g.strokeStyle = token('--status-success', '#12805c');
        g.beginPath(); g.moveTo(0, y(c.nearRssi)); g.lineTo(W, y(c.nearRssi)); g.stroke();
        g.strokeStyle = token('--status-error', '#b42318');
        g.beginPath(); g.moveTo(0, y(c.farRssi)); g.lineTo(W, y(c.farRssi)); g.stroke();
        g.setLineDash([]);

        g.fillStyle = token('--brand-primary', '#1d4ed8');
        history.forEach(([t, v]) => g.fillRect(x(t) - 1, y(v) - 1, 3, 3));
    },

    // ============== Intent ==============

    async toggleEnabled() {
        await this.post({ enabled: document.getElementById('urEnabled').checked });
    },

    async save() {
        const body = {};
        ['nearRssi', 'farRssi', 'nearWindowMs', 'farWindowMs', 'percent',
         'nearMinSamples', 'farMinSamples', 'noSignalMs', 'major', 'minor'].forEach((k) => {
            const el = document.getElementById(k);
            if (el) body[k] = Number(el.value);
        });
        const u = document.getElementById('uuid');
        if (u) body.uuid = u.value.trim();
        const ok = await this.post(body);
        const stamp = ok ? this.t('usb_rssi.saved', 'Saved') + ' ' + new Date().toLocaleTimeString()
                         : this.t('usb_rssi.uuid_rejected', 'That UUID is not valid — nothing was changed');
        this.setText('urSavedTuning', stamp);
        this.setText('urSavedBeacon', stamp);
    },

    async post(body) {
        try {
            const resp = await fetch('/api/usb-rssi/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
            const data = await resp.json();
            if (data && data.success === false) {
                BYD.core.toast(this.t('usb_rssi.uuid_rejected', 'That UUID is not valid — nothing was changed'), 'error');
                return false;
            }
            this.editing = false;
            if (data && data.config) { this.status = data; this.render(); }
            return true;
        } catch (e) {
            BYD.core.toast(this.t('usb_rssi.save_failed', 'Could not reach the car'), 'error');
            return false;
        }
    },

    async sendFirmware() {
        if (this.sendingFirmware) return;
        const input = document.getElementById('urFwFile');
        const file = input && input.files && input.files[0];
        if (!file) {
            this.setText('urFwState', this.t('usb_rssi.fw_pick', 'Pick a .bin file first'));
            return;
        }
        const kb = Math.round(file.size / 1024);
        if (!confirm(this.t('usb_rssi.fw_confirm', 'Send this file to the scanner?')
                + '\n\n' + file.name + ' (' + kb + ' KB)')) return;
        this.sendingFirmware = true;
        this.setText('urFwState', this.t('usb_rssi.fw_uploading', 'Uploading to the car…'));
        try {
            const buf = await file.arrayBuffer();
            const bytes = new Uint8Array(buf);
            // Chunked so a megabyte does not blow the argument limit of String.fromCharCode.
            let binary = '';
            for (let i = 0; i < bytes.length; i += 0x8000) {
                binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
            }
            const resp = await fetch('/api/usb-rssi/firmware', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ image: btoa(binary) })
            });
            const data = await resp.json();
            this.setText('urFwState', data && data.success
                ? this.t('usb_rssi.fw_sending', 'Sending to the scanner…')
                : this.t('usb_rssi.fw_rejected', 'Rejected') + ': ' + ((data && data.error) || '?'));
        } catch (e) {
            this.setText('urFwState', this.t('usb_rssi.fw_failed', 'Upload failed') + ': ' + e);
        } finally {
            this.sendingFirmware = false;
        }
    },

    // ============== Helpers ==============

    t(key, fallback) {
        try {
            const v = BYD.i18n && BYD.i18n.t ? BYD.i18n.t(key) : null;
            return (v && v !== key) ? v : fallback;
        } catch (e) {
            return fallback;
        }
    },

    setText(id, text) {
        const el = document.getElementById(id);
        if (el) el.textContent = text;
    },

    chk(id, value) {
        const el = document.getElementById(id);
        if (el) el.checked = !!value;
    }
};
