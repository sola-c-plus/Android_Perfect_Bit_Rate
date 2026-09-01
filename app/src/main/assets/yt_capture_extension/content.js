if (window.self !== window.top) {
    throw new Error("[BitPerfect] Skip iframe");
}

try {
    Object.defineProperty(document, 'hidden', { value: false, writable: false });
    Object.defineProperty(document, 'visibilityState', { value: 'visible', writable: false });
    document.addEventListener('visibilitychange', (e) => e.stopImmediatePropagation(), true);
} catch(e) {}

let port = null;
let lastCodecName = "";
let adBlockEnabled = true;
let currentSampleRate = 48000;

let audioCtx = null;
let processor = null;
let silentGain = null;
let activeMediaElement = null;

const itagMap = {
    '251': { name: 'Opus 160kbps (48k)', rate: 48000 },
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
    const skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
    if (skipBtn) {
        try { skipBtn.click(); } catch(e) {}
    }

    const player = document.querySelector('#movie_player') || document.querySelector('.html5-video-player');
    const isAd = player?.classList.contains('ad-showing') || player?.classList.contains('ad-interrupting');
    const video = activeMediaElement || document.querySelector('video');

    if (isAd && video) {
        video.playbackRate = 8.0;
        if (!isNaN(video.duration) && video.duration > 0 && video.currentTime < video.duration - 0.5) {
            video.currentTime = video.duration - 0.2;
        }
    } else if (video && video.playbackRate > 1.0) {
        video.playbackRate = 1.0;
    }
}
setInterval(safeAdSkip, 1000);

function forceFullVolume() {
    const video = activeMediaElement || document.querySelector('video');
    if (video && video.volume < 1.0) {
        video.volume = 1.0;
    }
}
setInterval(forceFullVolume, 2000);

function scanStreamCodec() {
    let detectedName = "";
    let detectedRate = 48000;

    try {
        const entries = performance.getEntriesByType('resource');
        const startIdx = Math.max(0, entries.length - 20);
        for (let i = entries.length - 1; i >= startIdx; i--) {
            const url = entries[i].name;
            if (url.includes('videoplayback')) {
                const matchItag = url.match(/[?&]itag=(\d+)/);
                if (matchItag && matchItag[1] && itagMap[matchItag[1]]) {
                    detectedName = itagMap[matchItag[1]].name;
                    detectedRate = itagMap[matchItag[1]].rate;
                    break;
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
        if (entries.length > 40) {
            performance.clearResourceTimings();
        }
    } catch(e) {}

    if (detectedName && (detectedName !== lastCodecName || detectedRate !== currentSampleRate)) {
        lastCodecName = detectedName;
        currentSampleRate = detectedRate;

        postNativeMessage({
            type: "codec",
            codec: detectedName,
            sampleRate: currentSampleRate
        });
    }
}
setInterval(scanStreamCodec, 1500);

function bytesToBase64(bytes) {
    let binary = '';
    const len = bytes.byteLength;
    const chunkSize = 4096;
    for (let i = 0; i < len; i += chunkSize) {
        const sub = bytes.subarray(i, Math.min(i + chunkSize, len));
        binary += String.fromCharCode.apply(null, sub);
    }
    return btoa(binary);
}

function getAudioContext() {
    if (!audioCtx || audioCtx.state === 'closed') {
        const AudioContextClass = window.AudioContext || window.webkitAudioContext;
        audioCtx = new AudioContextClass({ latencyHint: 'playback' });
    }
    if (audioCtx.state === 'suspended' || audioCtx.state === 'interrupted') {
        audioCtx.resume().catch(() => {});
    }
    return audioCtx;
}

function attachAudioPipeline(mediaEl) {
    if (!mediaEl) return;
    activeMediaElement = mediaEl;

    try {
        const ctx = getAudioContext();
        if (ctx.state === 'suspended') {
            ctx.resume().catch(() => {});
        }

        if (!mediaEl._bpSourceNode) {
            try {
                mediaEl._bpSourceNode = ctx.createMediaElementSource(mediaEl);
            } catch(e) {}
        }

        const sourceNode = mediaEl._bpSourceNode;
        if (!sourceNode) return;

        if (!processor || processor.context !== ctx) {
            processor = ctx.createScriptProcessor(4096, 2, 2);
            processor.onaudioprocess = function(e) {
                if (mediaEl.paused || mediaEl.ended) return;
                if (ctx.state === 'suspended') ctx.resume().catch(() => {});

                const inL = e.inputBuffer.getChannelData(0);
                const inR = e.inputBuffer.getChannelData(1);
                const inLen = inL.length;
                const srcRate = ctx.sampleRate || 48000;

                let left = inL;
                let right = inR;
                let len = inLen;

                if (srcRate !== currentSampleRate) {
                    const ratio = srcRate / currentSampleRate;
                    len = Math.floor(inLen / ratio);
                    left = new Float32Array(len);
                    right = new Float32Array(len);
                    for (let i = 0; i < len; i++) {
                        const pos = i * ratio;
                        const idx = Math.floor(pos);
                        const frac = pos - idx;
                        const nextIdx = Math.min(idx + 1, inLen - 1);
                        left[i] = inL[idx] * (1 - frac) + inL[nextIdx] * frac;
                        right[i] = inR[idx] * (1 - frac) + inR[nextIdx] * frac;
                    }
                }

                // 生の 32-bit Float PCM (Little Endian)
                const buffer = new ArrayBuffer(len * 8);
                const view = new DataView(buffer);
                for (let i = 0; i < len; i++) {
                    view.setFloat32(i * 8, left[i], true);
                    view.setFloat32(i * 8 + 4, right[i], true);
                }
                const bytes = new Uint8Array(buffer);
                const base64Pcm = bytesToBase64(bytes);

                postNativeMessage({
                    type: "pcm",
                    pcm: base64Pcm,
                    sampleRate: currentSampleRate,
                    bitMode: "float32"
                });
            };
        }

        if (!silentGain || silentGain.context !== ctx) {
            silentGain = ctx.createGain();
            silentGain.gain.value = 0.0;
        }

        try { sourceNode.disconnect(); } catch(e) {}
        try { processor.disconnect(); } catch(e) {}
        try { silentGain.disconnect(); } catch(e) {}

        sourceNode.connect(processor);
        processor.connect(silentGain);
        silentGain.connect(ctx.destination);

    } catch(e) {
        console.error("[BitPerfect] Attach error", e);
    }
}

const origPlay = HTMLMediaElement.prototype.play;
HTMLMediaElement.prototype.play = function() {
    const mediaEl = this;
    getAudioContext();
    attachAudioPipeline(mediaEl);
    scanStreamCodec();
    postNativeMessage({ type: "state", playing: true });
    return origPlay.apply(this, arguments);
};

function findAndAttachVideo() {
    const video = document.querySelector('video') || document.querySelector('audio');
    if (video) {
        attachAudioPipeline(video);
        scanStreamCodec();
    }
}
setInterval(findAndAttachVideo, 1000);

const observer = new MutationObserver(() => {
    findAndAttachVideo();
});
observer.observe(document.documentElement, { childList: true, subtree: true });

function handleNativeMessage(msg) {
    const cmd = (typeof msg === 'string') ? msg : (msg && msg.command ? msg.command : '');
    const video = activeMediaElement || document.querySelector('video');

    if (cmd === 'play') {
        getAudioContext();
        if (video) { video.muted = false; video.volume = 1.0; video.play().catch(() => {}); }
        document.querySelector('#play-pause-button')?.click();
    } else if (cmd === 'pause') {
        if (video) video.pause();
        document.querySelector('#play-pause-button')?.click();
    } else if (cmd === 'resume_audio') {
        getAudioContext();
        if (video) {
            video.muted = false;
            attachAudioPipeline(video);
        }
    } else if (cmd === 'next') {
        document.querySelector('.next-button')?.click();
    } else if (cmd === 'prev') {
        document.querySelector('.previous-button')?.click();
    } else if (cmd === 'seek' && msg.position !== undefined) {
        if (video) video.currentTime = msg.position / 1000.0;
    } else if (cmd === 'setAdBlock' && msg.enabled !== undefined) {
        adBlockEnabled = msg.enabled;
        updateAdBlockStyles(msg.enabled);
    }
}

function connectNativePort() {
    try {
        port = browser.runtime.connectNative("browser");
        port.onMessage.addListener(handleNativeMessage);
        port.onDisconnect.addListener(() => {
            port = null;
            setTimeout(connectNativePort, 1000);
        });
    } catch(e) {
        port = null;
        setTimeout(connectNativePort, 1000);
    }
}
connectNativePort();

function postNativeMessage(data) {
    if (!port) {
        connectNativePort();
    }
    if (port) {
        try {
            port.postMessage(data);
        } catch(e) {
            port = null;
            connectNativePort();
        }
    }
}

function syncPlaybackProgress() {
    const video = activeMediaElement || document.querySelector('video');
    if (video && !isNaN(video.duration) && video.duration > 0) {
        postNativeMessage({
            type: "progress",
            current: Math.floor(video.currentTime * 1000),
            duration: Math.floor(video.duration * 1000),
            playing: !video.paused
        });
    }
}
setInterval(syncPlaybackProgress, 1000);

function checkMetadata() {
    if (navigator.mediaSession && navigator.mediaSession.metadata) {
        const meta = navigator.mediaSession.metadata;
        let artworkUrl = (meta.artwork && meta.artwork.length > 0) ? meta.artwork[meta.artwork.length - 1].src : "";
        postNativeMessage({
            type: "meta",
            title: meta.title || "YouTube Music",
            artist: meta.artist || "",
            artwork: artworkUrl
        });
    }
}
setInterval(checkMetadata, 1500);

['click', 'touchstart', 'touchend', 'pointerdown', 'keydown'].forEach(evt => {
    document.addEventListener(evt, () => {
        getAudioContext();
        findAndAttachVideo();
    }, { passive: true, capture: true });
});