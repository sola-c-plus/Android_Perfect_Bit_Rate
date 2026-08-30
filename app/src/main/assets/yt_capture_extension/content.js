Object.defineProperty(document, 'hidden', { value: false, writable: false });
Object.defineProperty(document, 'visibilityState', { value: 'visible', writable: false });
document.addEventListener('visibilitychange', (e) => e.stopImmediatePropagation(), true);

let port = null;
let lastTitle = "";
let lastCodecName = "";
let adBlockEnabled = true;
let currentBitMode = "16bit";
let currentSampleRate = 48000;

let audioCtx = null;
let cachedSourceNode = null;
let cachedVideoElement = null;
let processor = null;

const itagMap = {
    '251': { name: 'Opus 160kbps (最高音質 48k)', rate: 48000 },
    '250': { name: 'Opus 70kbps (48k)', rate: 48000 },
    '249': { name: 'Opus 50kbps (48k)', rate: 48000 },
    '140': { name: 'AAC 128kbps (44.1k)', rate: 44100 },
    '141': { name: 'AAC 256kbps (44.1k)', rate: 44100 },
    '256': { name: 'AAC 256kbps (HQ 44.1k)', rate: 44100 },
    '258': { name: 'AAC 384kbps (5.1ch 44.1k)', rate: 44100 }
};

let adStyleElement = null;
function updateAdBlockStyles(enable) {
    if (enable) {
        if (!adStyleElement) {
            adStyleElement = document.createElement('style');
            adStyleElement.id = 'bp-adblock-style';
            adStyleElement.textContent = `
                .video-ads, .ytp-ad-module, .ytp-ad-overlay-container,
                ytmusic-player-ad-slot-renderer, #player-ads,
                .ytp-ad-message-container, iron-overlay-backdrop {
                    display: none !important;
                    pointer-events: none !important;
                }
            `;
            document.head?.appendChild(adStyleElement);
        }
    } else {
        if (adStyleElement) {
            adStyleElement.remove();
            adStyleElement = null;
        }
    }
}
updateAdBlockStyles(true);

function safeAdSkip() {
    if (!adBlockEnabled) return;
    const skipBtns = document.querySelectorAll('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button-container');
    skipBtns.forEach(btn => { try { btn.click(); } catch(e) {} });

    const player = document.querySelector('#movie_player') || document.querySelector('.html5-video-player');
    const isAd = player?.classList.contains('ad-showing') || player?.classList.contains('ad-interrupting');
    const video = document.querySelector('video');

    if (isAd && video) {
        video.playbackRate = 8.0;
        if (!isNaN(video.duration) && video.duration > 0 && video.currentTime < video.duration - 0.5) {
            video.currentTime = video.duration - 0.2;
        }
    } else if (video && video.playbackRate > 1.0) {
        video.playbackRate = 1.0;
    }
}
setInterval(safeAdSkip, 300);

function forceFullVolume() {
    const video = document.querySelector('video');
    if (video && video.volume < 1.0) {
        video.volume = 1.0;
    }
}
setInterval(forceFullVolume, 1000);

function scanStreamCodec() {
    let detectedName = "";
    let detectedRate = 48000;

    try {
        const entries = performance.getEntriesByType('resource');
        for (let i = entries.length - 1; i >= 0; i--) {
            const url = entries[i].name;
            if (url.includes('videoplayback')) {
                const matchItag = url.match(/[?&]itag=(\d+)/);
                if (matchItag && matchItag[1]) {
                    const itag = matchItag[1];
                    if (itagMap[itag]) {
                        detectedName = itagMap[itag].name;
                        detectedRate = itagMap[itag].rate;
                        break;
                    }
                }
                if (url.includes('mime=audio%2Fwebm') || url.includes('mime=audio/webm')) {
                    detectedName = 'Opus 160kbps (WebM 48k)';
                    detectedRate = 48000;
                    break;
                } else if (url.includes('mime=audio%2Fmp4') || url.includes('mime=audio/mp4')) {
                    detectedName = 'AAC (MP4 44.1k)';
                    detectedRate = 44100;
                    break;
                }
            }
        }
    } catch(e) {}

    if (detectedName && (detectedName !== lastCodecName || detectedRate !== currentSampleRate)) {
        lastCodecName = detectedName;
        currentSampleRate = detectedRate;

        if (port) {
            try {
                port.postMessage({
                    type: "codec",
                    codec: detectedName,
                    sampleRate: currentSampleRate
                });
            } catch(e) {}
        }
    }
}
setInterval(scanStreamCodec, 1000);

function setupAudioPipeline() {
    const video = document.querySelector('video') || document.querySelector('audio');
    if (!video) return;

    try {
        if (!audioCtx) {
            const AudioContextClass = window.AudioContext || window.webkitAudioContext;
            audioCtx = new AudioContextClass({
                sampleRate: 48000,
                latencyHint: 'playback'
            });
        }

        if (cachedVideoElement !== video || !cachedSourceNode) {
            cachedVideoElement = video;
            try {
                cachedSourceNode = audioCtx.createMediaElementSource(video);
            } catch(err) {}
        }

        if (cachedSourceNode && !processor) {
            processor = audioCtx.createScriptProcessor(4096, 2, 2);
            processor.onaudioprocess = function(e) {
                if (video.paused || video.ended) {
                    return;
                }

                if (audioCtx.state === 'suspended') audioCtx.resume();

                const inL = e.inputBuffer.getChannelData(0);
                const inR = e.inputBuffer.getChannelData(1);
                const inLen = inL.length;

                let left = inL;
                let right = inR;
                let len = inLen;

                // 44.1kHz音源の場合は、正確な44.1kHz PCMフレーム（44100/48000）へ変換
                if (currentSampleRate === 44100) {
                    len = Math.floor(inLen * (44100 / 48000));
                    left = new Float32Array(len);
                    right = new Float32Array(len);
                    const ratio = 48000 / 44100;
                    for (let i = 0; i < len; i++) {
                        const pos = i * ratio;
                        const idx = Math.floor(pos);
                        const frac = pos - idx;
                        const nextIdx = Math.min(idx + 1, inLen - 1);
                        left[i] = inL[idx] * (1 - frac) + inL[nextIdx] * frac;
                        right[i] = inR[idx] * (1 - frac) + inR[nextIdx] * frac;
                    }
                }

                let bytes = null;

                if (currentBitMode === "32bit") {
                    const buffer = new ArrayBuffer(len * 8);
                    const view = new DataView(buffer);
                    for (let i = 0; i < len; i++) {
                        view.setFloat32(i * 8, left[i], true);
                        view.setFloat32(i * 8 + 4, right[i], true);
                    }
                    bytes = new Uint8Array(buffer);
                } else if (currentBitMode === "24bit") {
                    const buffer = new ArrayBuffer(len * 6);
                    const view = new DataView(buffer);
                    for (let i = 0; i < len; i++) {
                        let l = Math.max(-1.0, Math.min(1.0, left[i]));
                        let r = Math.max(-1.0, Math.min(1.0, right[i]));
                        let intL = l < 0 ? Math.round(l * 8388608) : Math.round(l * 8388607);
                        let intR = r < 0 ? Math.round(r * 8388608) : Math.round(r * 8388607);
                        intL = Math.max(-8388608, Math.min(8388607, intL));
                        intR = Math.max(-8388608, Math.min(8388607, intR));
                        if (intL < 0) intL = 0x1000000 + intL;
                        if (intR < 0) intR = 0x1000000 + intR;

                        view.setUint8(i * 6, intL & 0xFF);
                        view.setUint8(i * 6 + 1, (intL >> 8) & 0xFF);
                        view.setUint8(i * 6 + 2, (intL >> 16) & 0xFF);
                        view.setUint8(i * 6 + 3, intR & 0xFF);
                        view.setUint8(i * 6 + 4, (intR >> 8) & 0xFF);
                        view.setUint8(i * 6 + 5, (intR >> 16) & 0xFF);
                    }
                    bytes = new Uint8Array(buffer);
                } else {
                    const buffer = new ArrayBuffer(len * 4);
                    const view = new DataView(buffer);
                    for (let i = 0; i < len; i++) {
                        let l = Math.max(-1.0, Math.min(1.0, left[i]));
                        let r = Math.max(-1.0, Math.min(1.0, right[i]));
                        view.setInt16(i * 4, l < 0 ? Math.round(l * 32768) : Math.round(l * 32767), true);
                        view.setInt16(i * 4 + 2, r < 0 ? Math.round(r * 32768) : Math.round(r * 32767), true);
                    }
                    bytes = new Uint8Array(buffer);
                }

                let binary = '';
                const chunkSize = 8192;
                for (let i = 0; i < bytes.length; i += chunkSize) {
                    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunkSize));
                }

                if (port) {
                    try {
                        port.postMessage({
                            type: "pcm",
                            pcm: btoa(binary),
                            sampleRate: currentSampleRate,
                            bitMode: currentBitMode
                        });
                    } catch(err) {
                        connectNativePort();
                    }
                }
            };

            try { cachedSourceNode.disconnect(); } catch(e) {}
            cachedSourceNode.connect(processor);
        }

        video.addEventListener('play', () => {
            if (audioCtx && audioCtx.state === 'suspended') audioCtx.resume();
            scanStreamCodec();
            if (port) port.postMessage({ type: "state", playing: true });
        });
        video.addEventListener('pause', () => {
            if (audioCtx && audioCtx.state === 'running') audioCtx.suspend();
            if (port) port.postMessage({ type: "state", playing: false });
        });
    } catch(e) {
        console.error("[BitPerfect] Pipeline error", e);
    }
}

function connectNativePort() {
    try {
        port = browser.runtime.connectNative("browser");
        port.onMessage.addListener((msg) => {
            const cmd = (typeof msg === 'string') ? msg : (msg && msg.command ? msg.command : '');
            const video = document.querySelector('video');

            if (cmd === 'play') {
                if (video) { video.muted = false; video.volume = 1.0; video.play().catch(() => {}); }
                document.querySelector('#play-pause-button')?.click();
            } else if (cmd === 'pause') {
                if (video) video.pause();
                document.querySelector('#play-pause-button')?.click();
            } else if (cmd === 'next') {
                document.querySelector('.next-button')?.click();
            } else if (cmd === 'prev') {
                document.querySelector('.previous-button')?.click();
            } else if (cmd === 'seek' && msg.position !== undefined) {
                if (video) video.currentTime = msg.position / 1000.0;
            } else if (cmd === 'setAdBlock' && msg.enabled !== undefined) {
                adBlockEnabled = msg.enabled;
                updateAdBlockStyles(msg.enabled);
            } else if (cmd === 'setBitMode' && msg.mode !== undefined) {
                currentBitMode = msg.mode;
            }
        });
    } catch(e) {
        setTimeout(connectNativePort, 1000);
    }
}
connectNativePort();

function syncPlaybackProgress() {
    const video = document.querySelector('video');
    if (video && port && !isNaN(video.duration) && video.duration > 0) {
        try {
            port.postMessage({
                type: "progress",
                current: Math.floor(video.currentTime * 1000),
                duration: Math.floor(video.duration * 1000),
                playing: !video.paused
            });
        } catch(e) {}
    }
}
setInterval(syncPlaybackProgress, 1000);

function checkMetadata() {
    if (navigator.mediaSession && navigator.mediaSession.metadata) {
        const meta = navigator.mediaSession.metadata;
        let artworkUrl = (meta.artwork && meta.artwork.length > 0) ? meta.artwork[meta.artwork.length - 1].src : "";
        if (port) {
            try {
                port.postMessage({
                    type: "meta",
                    title: meta.title || "YouTube Music",
                    artist: meta.artist || "",
                    artwork: artworkUrl
                });
            } catch(e) {}
        }
    }
}
setInterval(checkMetadata, 1000);

setInterval(setupAudioPipeline, 1000);
document.addEventListener('click', setupAudioPipeline);
setupAudioPipeline();