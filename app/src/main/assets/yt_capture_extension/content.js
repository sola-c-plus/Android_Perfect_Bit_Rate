if (window.self !== window.top) {
    throw new Error("[BitPerfect] Skip iframe");
}

try {
    Object.defineProperty(document, 'hidden', { value: false, writable: false });
    Object.defineProperty(document, 'visibilityState', { value: 'visible', writable: false });
    document.addEventListener('visibilitychange', (e) => e.stopImmediatePropagation(), true);
    window.addEventListener('blur', (e) => e.stopImmediatePropagation(), true);
    window.addEventListener('pagehide', (e) => e.stopImmediatePropagation(), true);
} catch(e) {}

let port = null;
let lastCodecName = "";
let adBlockEnabled = true;
let userWantsPlaying = false;
let detectedStreamRate = 48000;
let isWhiteThemeActive = false;

let audioCtx = null;
let processor = null;
let virtualDest = null;
let currentMediaElement = null;

let cachedBuffer = null;
let cachedView = null;
let cachedBytes = null;

function getTransferBuffers(sizeInBytes) {
    if (!cachedBuffer || cachedBuffer.byteLength !== sizeInBytes) {
        cachedBuffer = new ArrayBuffer(sizeInBytes);
        cachedView = new DataView(cachedBuffer);
        cachedBytes = new Uint8Array(cachedBuffer);
    }
    return { buffer: cachedBuffer, view: cachedView, bytes: cachedBytes };
}

const itagMap = {
    '251': { name: 'Opus 160kbps (48k)', rate: 48000 },
    '250': { name: 'Opus 70kbps (48k)', rate: 48000 },
    '249': { name: 'Opus 50kbps (48k)', rate: 48000 },
    '140': { name: 'AAC 128kbps (44.1k)', rate: 44100 },
    '141': { name: 'AAC 256kbps (44.1k)', rate: 44100 },
    '256': { name: 'AAC 256kbps (HQ 44.1k)', rate: 44100 },
    '258': { name: 'AAC 384kbps (5.1ch 44.1k)', rate: 44100 }
};

// ★ コントラスト崩壊を完全に解消した完成版 White Mode CSS
const WHITE_THEME_CSS = `
    :root {
        --ytmusic-color-primary1: #ffffff !important;
        --ytmusic-color-primary2: #f5f5f7 !important;
        --ytmusic-color-primary3: #e5e5ea !important;
        --ytmusic-color-text1: #1c1c1e !important;
        --ytmusic-color-text2: #3a3a3c !important;
        --ytmusic-color-text3: #636366 !important;
        --ytmusic-color-white1: #1c1c1e !important;
        --ytmusic-color-white2: #2c2c2e !important;
        --ytmusic-color-black1: #ffffff !important;
        --ytmusic-color-black2: #f2f2f7 !important;
        --ytmusic-color-black3: #e5e5ea !important;
        --ytmusic-color-black4: #d1d1d6 !important;
        --ytmusic-brand-background-solid: #ffffff !important;
        --yt-spec-base-background: #ffffff !important;
        --yt-spec-text-primary: #1c1c1e !important;
        --yt-spec-text-secondary: #3a3a3c !important;
        --yt-spec-text-disabled: #8e8e93 !important;
        --ytmusic-overlay-background-brand: rgba(255, 255, 255, 0.96) !important;
        --ytmusic-guide-background: #ffffff !important;
    }

    html, body, #background.ytmusic-app, ytmusic-app {
        background-color: #ffffff !important;
        background: #ffffff !important;
        color: #1c1c1e !important;
    }

    /* 上部ナビゲーションバー */
    ytmusic-nav-bar, #nav-bar-background {
        background: #ffffff !important;
        border-bottom: 1px solid #e0e0e5 !important;
    }

    /* ★ 左側ドロワーメニュー (ホーム、探索、ライブラリ、ログイン) の完全修復 */
    ytmusic-guide-renderer,
    #guide-wrapper.ytmusic-app,
    tp-yt-app-drawer,
    tp-yt-app-drawer #contentContainer {
        background-color: #ffffff !important;
        background: #ffffff !important;
        color: #1c1c1e !important;
    }

    /* ドロワーメニュー内のテキスト・アイコンのコントラスト完全保証 */
    ytmusic-guide-entry-renderer tp-yt-paper-item,
    ytmusic-guide-entry-renderer .title,
    ytmusic-guide-section-renderer,
    #guide-links-primary,
    #guide-links-secondary,
    ytmusic-guide-signin-promo-renderer {
        color: #1c1c1e !important;
    }

    ytmusic-guide-signin-promo-renderer .descriptive-text {
        color: #3a3a3c !important;
        font-weight: 500 !important;
    }

    ytmusic-guide-entry-renderer yt-icon,
    ytmusic-guide-entry-renderer iron-icon,
    ytmusic-nav-bar yt-icon,
    ytmusic-nav-bar iron-icon {
        fill: #1c1c1e !important;
        color: #1c1c1e !important;
    }

    /* 選択中のメニュー項目 */
    ytmusic-guide-entry-renderer[is-primary] tp-yt-paper-item {
        background: #f2f2f7 !important;
    }

    /* ボタン・リンク */
    yt-button-renderer[is-paper-button],
    tp-yt-paper-button {
        color: #1c1c1e !important;
    }

    /* ログインボタンの背景と文字 */
    ytmusic-guide-signin-promo-renderer tp-yt-paper-button,
    #sign-in-button tp-yt-paper-button {
        background-color: #1c1c1e !important;
        color: #ffffff !important;
        border-radius: 20px !important;
    }
    #sign-in-button tp-yt-paper-button yt-formatted-string {
        color: #ffffff !important;
    }

    /* 下部プレイヤーバー */
    ytmusic-player-bar, #player-bar-background {
        background: #f8f8fa !important;
        border-top: 1px solid #e0e0e5 !important;
    }

    /* メインコンテンツエリア */
    ytmusic-browse-response,
    .background-gradient,
    ytmusic-item-section-renderer,
    #contents.ytmusic-section-list-renderer {
        background: #ffffff !important;
    }

    /* 曲名・アーティスト・見出し */
    .title.ytmusic-carousel-shelf-basic-header-renderer,
    .title.ytmusic-header-renderer,
    ytmusic-responsive-list-item-renderer [title],
    ytmusic-two-row-item-renderer [title],
    #title.ytmusic-player-bar,
    .yt-simple-endpoint {
        color: #1c1c1e !important;
    }

    .subtitle.ytmusic-header-renderer,
    .byline.ytmusic-player-bar,
    .byline.ytmusic-responsive-list-item-renderer,
    .byline.ytmusic-two-row-item-renderer,
    yt-formatted-string[has-link-only_]:not([force-default-style]) {
        color: #48484a !important;
    }

    /* チップ (ワークアウト、通勤、リラックス等) */
    ytmusic-chip-cloud-chip-renderer {
        background: #f2f2f7 !important;
        color: #1c1c1e !important;
        border: 1px solid #e0e0e5 !important;
    }
    ytmusic-chip-cloud-chip-renderer[is-selected] {
        background: #1c1c1e !important;
        color: #ffffff !important;
    }

    /* ポップアップメニュー */
    tp-yt-paper-listbox,
    ytmusic-menu-popup-renderer {
        background-color: #ffffff !important;
        border: 1px solid #e0e0e5 !important;
        color: #1c1c1e !important;
    }
    ytmusic-menu-navigation-item-renderer[is-selected],
    ytmusic-menu-service-item-renderer:hover {
        background: #f2f2f7 !important;
    }

    /* YouTube Music ロゴの反転 */
    .logo.style-scope.ytmusic-logo, picture.ytmusic-logo {
        filter: invert(1) hue-rotate(180deg) !important;
    }

    /* アルバムアート・動画サムネイルの反転除外 (鮮明に維持) */
    img, video, .image.ytmusic-two-row-item-renderer, yt-img-shadow img, #thumbnail img {
        filter: none !important;
    }
`;

let whiteStyleElement = null;
function updateWebWhiteTheme(enable) {
    isWhiteThemeActive = enable;
    if (enable) {
        if (!whiteStyleElement) {
            whiteStyleElement = document.createElement('style');
            whiteStyleElement.id = 'bp-ytm-white-theme';
            whiteStyleElement.textContent = WHITE_THEME_CSS;
            document.head?.appendChild(whiteStyleElement);
        }
    } else {
        if (whiteStyleElement) {
            whiteStyleElement.remove();
            whiteStyleElement = null;
        }
    }
}

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
    const video = currentMediaElement || document.querySelector('video');

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
    const video = currentMediaElement || document.querySelector('video');
    if (video && video.volume < 1.0) {
        video.volume = 1.0;
    }
}
setInterval(forceFullVolume, 2000);

function keepPlayingInBackground() {
    if (!userWantsPlaying) return;
    const video = currentMediaElement || document.querySelector('video') || document.querySelector('audio');
    if (video && video.paused && !video.ended) {
        video.play().catch(() => {});
    }
    if (audioCtx && (audioCtx.state === 'suspended' || audioCtx.state === 'interrupted')) {
        audioCtx.resume().catch(() => {});
    }
}
setInterval(keepPlayingInBackground, 1500);

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

    if (detectedName && (detectedName !== lastCodecName || detectedRate !== detectedStreamRate)) {
        lastCodecName = detectedName;
        detectedStreamRate = detectedRate;

        postNativeMessage({
            type: "codec",
            codec: detectedName,
            sampleRate: detectedStreamRate
        });
    }
}
setInterval(scanStreamCodec, 1000);

function bytesToBase64(bytes) {
    let binary = '';
    const len = bytes.byteLength;
    const chunkSize = 8192;
    for (let i = 0; i < len; i += chunkSize) {
        const sub = bytes.subarray(i, Math.min(i + chunkSize, len));
        binary += String.fromCharCode.apply(null, sub);
    }
    return btoa(binary);
}

function attachAudioPipeline(mediaEl) {
    if (!mediaEl) return;
    if (currentMediaElement !== mediaEl) {
        currentMediaElement = mediaEl;

        const onTrackChanged = () => {
            scanStreamCodec();
            postNativeMessage({ type: "flush" });
        };
        mediaEl.addEventListener('loadstart', onTrackChanged, { passive: true });
        mediaEl.addEventListener('loadedmetadata', onTrackChanged, { passive: true });
        mediaEl.addEventListener('emptied', onTrackChanged, { passive: true });

        mediaEl.addEventListener('pause', () => {
            userWantsPlaying = false;
            postNativeMessage({ type: "state", playing: false });
        }, { passive: true });

        mediaEl.addEventListener('play', () => {
            userWantsPlaying = true;
            postNativeMessage({ type: "state", playing: true });
        }, { passive: true });
    }

    try {
        const ctx = getAudioContext();
        if (ctx.state === 'suspended') {
            ctx.resume().catch(() => {});
        }

        if (!mediaEl._bpSourceNode) {
            try {
                mediaEl._bpSourceNode = ctx.createMediaElementSource(mediaEl);
            } catch(e) {
                return;
            }
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
                const len = inL.length;

                const totalBytes = len * 8;
                const { view, bytes } = getTransferBuffers(totalBytes);

                for (let i = 0; i < len; i++) {
                    view.setFloat32(i * 8, inL[i], true);
                    view.setFloat32(i * 8 + 4, inR[i], true);
                }

                const base64Pcm = bytesToBase64(bytes);

                postNativeMessage({
                    type: "pcm",
                    pcm: base64Pcm,
                    sampleRate: detectedStreamRate,
                    bitMode: "float32"
                });
            };
        }

        if (!virtualDest || virtualDest.context !== ctx) {
            virtualDest = ctx.createMediaStreamDestination();
        }

        try { sourceNode.disconnect(); } catch(e) {}
        try { processor.disconnect(); } catch(e) {}

        sourceNode.connect(processor);
        processor.connect(virtualDest);

    } catch(e) {
        console.error("[BitPerfect] Attach error", e);
    }
}

const origPlay = HTMLMediaElement.prototype.play;
HTMLMediaElement.prototype.play = function() {
    const mediaEl = this;
    userWantsPlaying = true;
    scanStreamCodec();
    attachAudioPipeline(mediaEl);
    postNativeMessage({ type: "state", playing: true });
    return origPlay.apply(this, arguments);
};

const origPause = HTMLMediaElement.prototype.pause;
HTMLMediaElement.prototype.pause = function() {
    userWantsPlaying = false;
    postNativeMessage({ type: "state", playing: false });
    return origPause.apply(this, arguments);
};

function findAndAttachVideo() {
    const video = document.querySelector('video') || document.querySelector('audio');
    if (video && video !== currentMediaElement) {
        attachAudioPipeline(video);
        scanStreamCodec();
    }
}
setInterval(findAndAttachVideo, 1000);

const observer = new MutationObserver(() => {
    findAndAttachVideo();
    // DOM動的生成時もWhiteテーマを欠損なく維持
    if (isWhiteThemeActive && !document.getElementById('bp-ytm-white-theme')) {
        updateWebWhiteTheme(true);
    }
});
observer.observe(document.documentElement, { childList: true, subtree: true });

function handleNativeMessage(msg) {
    const cmd = (typeof msg === 'string') ? msg : (msg && msg.command ? msg.command : '');
    const video = currentMediaElement || document.querySelector('video');

    if (cmd === 'setWebTheme') {
        updateWebWhiteTheme(msg.theme === 'light');
    } else if (cmd === 'heartbeat') {
        keepPlayingInBackground();
    } else if (cmd === 'play') {
        userWantsPlaying = true;
        getAudioContext();
        if (video) { video.muted = false; video.volume = 1.0; video.play().catch(() => {}); }
        document.querySelector('#play-pause-button')?.click();
    } else if (cmd === 'pause') {
        userWantsPlaying = false;
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
        postNativeMessage({ type: "flush" });
    } else if (cmd === 'prev') {
        document.querySelector('.previous-button')?.click();
        postNativeMessage({ type: "flush" });
    } else if (cmd === 'seek' && msg.position !== undefined) {
        if (video) video.currentTime = msg.position / 1000.0;
        postNativeMessage({ type: "flush" });
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
    const video = currentMediaElement || document.querySelector('video');
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