/**
 * Palette Nav - UI Interactions
 * 配色、夜间模式、时钟、背景切换、搜索引擎、搜索建议、分类筛选
 *
 * 原生 ES5，无 jQuery / 无框架依赖。
 * 服务端配置通过 <body> 的 data-* 属性注入。
 * 搜索区 DOM 契约：form#search-form / input#search-input /
 * input[name="sou"][data-alias][data-hint] / localStorage['theme_sou']
 * 分类栏 DOM 契约：.category-nav > .category-scroll + .category-arrow[data-scroll-dir]
 * 背景 DOM 契约：.bg-layer / <video id="bgVideo">（模板预渲染，脚本复用）/
 * body[data-background-url|data-background-video-url|data-show-background-by-default]；
 * 可见状态由 html[data-background="image"]、html[data-background-video] 与
 * .bg-layer.image-bg 表示，档位判断读取 DOM 而不是 localStorage。
 */
(function () {
    'use strict';

    var COLOR_KEY = 'palette_color';
    var NIGHT_KEY = 'palette_nightMode';
    var BG_KEY = 'palette_bgSetting';
    var SOU_KEY = 'theme_sou';

    var colors = ['purple', 'blue', 'green', 'pink', 'orange', 'teal'];

    // ============ 通用工具 ============
    function storageGet(key) {
        try {
            return window.localStorage.getItem(key);
        } catch (e) {
            return null;
        }
    }

    function storageSet(key, value) {
        try {
            window.localStorage.setItem(key, value);
        } catch (e) {
            // 隐私模式等场景写入失败，静默降级
        }
    }

    function bodyData(key) {
        if (!document.body || !document.body.dataset) {
            return '';
        }
        var value = document.body.dataset[key];
        return value == null ? '' : String(value);
    }

    function trimText(value) {
        return String(value == null ? '' : value).replace(/^\s+|\s+$/g, '');
    }

    function pad2(value) {
        return value < 10 ? '0' + value : '' + value;
    }

    function containsColor(color) {
        for (var i = 0; i < colors.length; i++) {
            if (colors[i] === color) {
                return true;
            }
        }
        return false;
    }

    // ============ 主题色彩 ============
    function updateColorActive(color) {
        var items = document.querySelectorAll('.color-opt');
        for (var i = 0; i < items.length; i++) {
            if (items[i].getAttribute('data-color') === color) {
                items[i].classList.add('active');
            } else {
                items[i].classList.remove('active');
            }
        }
    }

    function setColor(color) {
        if (!containsColor(color)) {
            color = 'purple';
        }
        document.documentElement.setAttribute('data-theme', color);
        storageSet(COLOR_KEY, color);
        updateColorActive(color);
    }

    // ============ 夜间模式 ============
    function updateNightModeIcon() {
        var isNight = document.documentElement.classList.contains('night-mode');
        var sunIcon = document.querySelector('#nightBtn .icon-sun');
        var moonIcon = document.querySelector('#nightBtn .icon-moon');
        if (sunIcon && moonIcon) {
            sunIcon.style.display = isNight ? 'none' : '';
            moonIcon.style.display = isNight ? '' : 'none';
        }
        var nightBtn = document.getElementById('nightBtn');
        if (nightBtn) {
            nightBtn.setAttribute('aria-pressed', isNight ? 'true' : 'false');
        }
    }

    // ============ 时钟与问候 ============
    function updateClock() {
        var now = new Date();
        var timeEl = document.getElementById('clockTime');
        var dateEl = document.getElementById('clockDate');
        var greetingEl = document.getElementById('clockGreeting');

        if (timeEl) {
            timeEl.textContent = pad2(now.getHours()) + ':' + pad2(now.getMinutes()) + ':' + pad2(now.getSeconds());
        }

        if (dateEl) {
            var weekdays = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'];
            dateEl.textContent = now.getFullYear() + '年' + (now.getMonth() + 1) + '月' + now.getDate() + '日 ' + weekdays[now.getDay()];
        }

        if (greetingEl) {
            var h = now.getHours();
            var greeting;
            if (h < 6) {
                greeting = '夜深了，早点休息吧';
            } else if (h < 9) {
                greeting = '早上好！新的一天开始了';
            } else if (h < 12) {
                greeting = '上午好！愿你精力充沛';
            } else if (h < 14) {
                greeting = '中午好！记得按时吃饭';
            } else if (h < 18) {
                greeting = '下午好！工作辛苦了';
            } else if (h < 22) {
                greeting = '晚上好！放松一下吧';
            } else {
                greeting = '夜深了，晚安';
            }
            greetingEl.textContent = greeting;
        }
    }

    // ============ 背景切换 ============
    var bgLayer = document.querySelector('.bg-layer');

    // 模板在配置了视频背景时会预渲染 <video id="bgVideo">，脚本复用它而不是
    // 动态插入，首屏才能和图片背景一样在绘制前就命中。没有模板元素时按旧逻辑创建。
    function videoElement(src) {
        var video = document.getElementById('bgVideo');
        if (video) {
            return video;
        }
        video = document.createElement('video');
        video.id = 'bgVideo';
        video.className = 'bg-video';
        video.loop = true;
        video.muted = true;
        video.playsInline = true;
        video.src = src;
        document.body.appendChild(video);
        return video;
    }

    // 自动显示视频时，只有真正开始播放才记录偏好；清理时丢弃未完成的监听。
    var autoVideoPersist = null;

    function detachAutoVideoPersist(video) {
        if (autoVideoPersist) {
            video.removeEventListener('playing', autoVideoPersist);
            autoVideoPersist = null;
        }
    }

    // 只清理画面与首屏属性，不写访客偏好（用于自动默认显示失败回退）。
    function clearBackgroundVisual() {
        document.documentElement.removeAttribute('data-background');
        document.documentElement.removeAttribute('data-background-video');
        if (bgLayer) {
            bgLayer.classList.remove('image-bg');
            bgLayer.style.backgroundImage = 'none';
        }
        var video = document.getElementById('bgVideo');
        if (video) {
            detachAutoVideoPersist(video);
            video.onerror = null;
            try {
                video.pause();
            } catch (e) {
                // 旧浏览器或已卸载的元素
            }
        }
    }

    // auto 为 true 时只显示设置里的默认背景，不写访客偏好：站点默认值不等于
    // 访客自己的选择，否则管理员关闭「默认显示」后老访客仍会首屏看到背景。
    function setImageBackground(auto) {
        var url = bodyData('backgroundUrl');
        if (!bgLayer || !url) {
            return false;
        }
        if (!auto) {
            storageSet(BG_KEY, 'image');
        }
        clearBackgroundVisual();
        bgLayer.classList.add('image-bg');
        // 模板 <head> 里已有 html[data-background="image"] 的样式规则，
        // 这里同步属性，保证切换开关时首屏规则与运行时状态一致。
        document.documentElement.setAttribute('data-background', 'image');
        bgLayer.style.backgroundImage = 'url("' + url.replace(/"/g, '%22') + '")';
        return true;
    }

    function setVideoBackground(auto) {
        var src = bodyData('backgroundVideoUrl');
        if (!src) {
            return false;
        }
        document.documentElement.removeAttribute('data-background');
        document.documentElement.setAttribute('data-background-video', '');
        if (bgLayer) {
            bgLayer.classList.remove('image-bg');
            bgLayer.style.backgroundImage = 'none';
        }

        var video = videoElement(src);
        detachAutoVideoPersist(video);
        if (auto) {
            // 首访默认显示：等视频真正播放后再记录偏好，失败不落盘，
            // 否则一次临时故障（源站不可达、编码不支持）会让访客永久失去背景。
            autoVideoPersist = function () {
                storageSet(BG_KEY, 'video');
                detachAutoVideoPersist(video);
            };
            video.addEventListener('playing', autoVideoPersist);
        } else {
            storageSet(BG_KEY, 'video');
        }
        // 视频地址失效、跨域被拒或浏览器不支持编码时回退到无背景。
        video.onerror = function () {
            detachAutoVideoPersist(video);
            if (auto) {
                clearBackgroundVisual();
            } else {
                clearBackground();
            }
        };
        // 模板内的首屏 play() 可能已经失败，补一次判定。
        if (video.error) {
            video.onerror();
            return true;
        }
        try {
            var played = video.play();
            if (played && typeof played.catch === 'function') {
                played.catch(function () {});
            }
        } catch (e) {
            // 自动播放被拒时保留已显示的画面
        }
        return true;
    }

    function clearBackground() {
        storageSet(BG_KEY, 'none');
        clearBackgroundVisual();
    }

    // 后台是否配置了视频背景地址
    function hasVideoBackground() {
        return !!bodyData('backgroundVideoUrl');
    }

    // 后台设置的「首次访问是否直接显示背景」；访客保存过的选择始终优先。
    function defaultBackgroundKind() {
        if (bodyData('showBackgroundByDefault') !== 'true') {
            return '';
        }
        return hasVideoBackground() ? 'video' : 'image';
    }

    function restoreBackground() {
        // 无保存值时按设置决定首访默认值；开关关闭时仅保留主题渐变。
        var stored = storageGet(BG_KEY);
        var auto = !stored;
        var kind = stored || defaultBackgroundKind();
        if (kind === 'video') {
            // 首访默认值失败时只回退画面、不写 none，下次访问仍会重试。
            if (!setVideoBackground(auto)) {
                clearBackgroundVisual();
            }
        } else if (kind === 'image') {
            if (!hasVideoBackground() && setImageBackground(auto)) {
                return;
            }
            // 配置了视频地址后历史图片偏好失效。若开启了默认显示，直接切到视频
            // 而不是把访客永久置为无背景；关闭时保持原有清零行为。
            if (defaultBackgroundKind() === 'video' && setVideoBackground(true)) {
                return;
            }
            clearBackground();
        }
    }

    // 当前画面是否显示背景要从 DOM 读：首访默认背景在访客点击前可能还没写入
    // localStorage（视频要等 playing，图片是站点默认值），读存储会把“已显示”
    // 误判成“未显示”，导致第一次点击背景按钮毫无反应。
    function visibleBackgroundKind() {
        if (document.documentElement.hasAttribute('data-background-video')) {
            return 'video';
        }
        if (document.documentElement.getAttribute('data-background') === 'image'
                || (bgLayer && bgLayer.classList.contains('image-bg'))) {
            return 'image';
        }
        return 'none';
    }

    function cycleBackground() {
        var current = visibleBackgroundKind();
        // 配置了视频地址：只切换 视频 / 无背景；未配置：只切换 图片 / 无背景
        if (hasVideoBackground()) {
            if (current === 'video') {
                clearBackground();
            } else {
                setVideoBackground();
            }
            return;
        }
        if (current === 'image') {
            clearBackground();
        } else {
            setImageBackground();
        }
    }

    // ============ 搜索引擎 ============
    var searchForm = document.getElementById('search-form');
    var searchInput = document.getElementById('search-input');
    var searchSwitcher = document.getElementById('engineSwitcher');
    var engineIcon = document.getElementById('engineIcon');
    var engineOptions = document.getElementById('engineOptions');
    var suggestionList = document.getElementById('searchSuggestions');
    var engines = document.querySelectorAll('input[name="sou"]');

    var suggestTimer = null;
    var suggestScript = null;
    var suggestCallbackName = null;

    function currentEngine() {
        for (var i = 0; i < engines.length; i++) {
            if (engines[i].checked) {
                return engines[i];
            }
        }
        return engines.length ? engines[0] : null;
    }

    function currentAlias() {
        var engine = currentEngine();
        return engine ? (engine.getAttribute('data-alias') || '') : '';
    }

    function hideSuggestions() {
        if (!suggestionList) {
            return;
        }
        suggestionList.className = 'search-suggestions';
        while (suggestionList.firstChild) {
            suggestionList.removeChild(suggestionList.firstChild);
        }
    }

    function cleanupSuggestion() {
        if (suggestTimer) {
            clearTimeout(suggestTimer);
            suggestTimer = null;
        }
        if (suggestScript && suggestScript.parentNode) {
            suggestScript.parentNode.removeChild(suggestScript);
        }
        suggestScript = null;
        if (suggestCallbackName) {
            var name = suggestCallbackName;
            suggestCallbackName = null;
            // 已发起但尚未执行的 JSONP 仍可能回调，先替换为空函数再延迟删除，
            // 避免控制台出现 “paletteSuggest... is not defined”
            window[name] = function () {};
            setTimeout(function () {
                try {
                    delete window[name];
                } catch (e) {
                    window[name] = undefined;
                }
            }, 10000);
        }
    }

    function clearSuggestions() {
        cleanupSuggestion();
        hideSuggestions();
    }

    function updateEngineUI() {
        var engine = currentEngine();
        if (!engine) {
            return;
        }

        var label = engine.parentNode;
        var iconSpan = label && label.querySelector ? label.querySelector('.engine-option-icon') : null;
        if (engineIcon && iconSpan) {
            engineIcon.innerHTML = iconSpan.innerHTML;
        }

        var labels = document.querySelectorAll('.engine-option');
        for (var i = 0; i < labels.length; i++) {
            var input = labels[i].querySelector ? labels[i].querySelector('input[name="sou"]') : null;
            if (input === engine) {
                labels[i].classList.add('active');
            } else {
                labels[i].classList.remove('active');
            }
        }

        if (searchInput) {
            searchInput.placeholder = engine.getAttribute('data-hint') || '请输入搜索内容';
        }
        clearSuggestions();
    }

    function isEngineMenuOpen() {
        return !!(engineOptions && engineOptions.classList.contains('show'));
    }

    function setEngineMenu(open) {
        if (engineOptions) {
            if (open) {
                engineOptions.classList.add('show');
            } else {
                engineOptions.classList.remove('show');
            }
        }
        if (searchSwitcher) {
            if (open) {
                searchSwitcher.classList.add('open');
            } else {
                searchSwitcher.classList.remove('open');
            }
            searchSwitcher.setAttribute('aria-expanded', open ? 'true' : 'false');
        }
        if (open) {
            clearSuggestions();
            var engine = currentEngine();
            if (engine && engine.focus) {
                try {
                    engine.focus();
                } catch (e) {
                    // 忽略聚焦失败
                }
            }
        }
    }

    function toggleEngineMenu() {
        setEngineMenu(!isEngineMenuOpen());
    }

    function restoreEngine() {
        var saved = storageGet(SOU_KEY);
        if (saved) {
            for (var i = 0; i < engines.length; i++) {
                if (engines[i].getAttribute('data-alias') === saved) {
                    engines[i].checked = true;
                    break;
                }
            }
        }
        updateEngineUI();
    }

    function doSearch(keyword) {
        var engine = currentEngine();
        keyword = trimText(keyword);
        var engineUrl = engine ? trimText(engine.value) : '';
        // 搜索地址为空时不做任何跳转，避免拼出站内相对 URL
        if (!engineUrl || !keyword) {
            return;
        }
        var win = window.open(engineUrl + encodeURIComponent(keyword), '_blank');
        if (win) {
            win.opener = null;
        }
    }

    function renderSuggestions(data) {
        if (!suggestionList) {
            return;
        }
        while (suggestionList.firstChild) {
            suggestionList.removeChild(suggestionList.firstChild);
        }
        if (!data || !data.s || !data.s.length) {
            suggestionList.className = 'search-suggestions';
            return;
        }
        for (var i = 0; i < data.s.length; i++) {
            var item = document.createElement('li');
            item.innerHTML = '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>';
            item.appendChild(document.createTextNode(String(data.s[i])));
            suggestionList.appendChild(item);
        }
        suggestionList.className = 'search-suggestions show';
    }

    function fetchSuggestions(keyword) {
        if (bodyData('baiduSuggest') !== 'true') {
            hideSuggestions();
            return;
        }
        if (currentAlias() !== 'baidu' || !suggestionList) {
            hideSuggestions();
            return;
        }
        cleanupSuggestion();

        var callbackName = 'paletteSuggest' + (new Date().getTime()) + Math.floor(Math.random() * 1000);
        var script = document.createElement('script');

        window[callbackName] = function (data) {
            // 已被后续请求取代时丢弃迟到的结果
            if (suggestCallbackName !== callbackName) {
                return;
            }
            cleanupSuggestion();
            renderSuggestions(data);
        };

        script.src = 'https://suggestion.baidu.com/su?wd=' + encodeURIComponent(keyword) + '&cb=' + callbackName;
        script.onerror = function () {
            // 只清理本次请求，避免误删已被后续请求替换的 script
            if (suggestScript !== script) {
                return;
            }
            cleanupSuggestion();
            hideSuggestions();
        };

        suggestCallbackName = callbackName;
        suggestScript = script;
        document.body.appendChild(script);
    }

    function bindSearch() {
        if (searchSwitcher) {
            searchSwitcher.addEventListener('click', function (e) {
                e.stopPropagation();
                toggleEngineMenu();
            });
            searchSwitcher.addEventListener('keydown', function (e) {
                var key = e.key || e.keyCode;
                if (key === 'Enter' || key === ' ' || key === 13 || key === 32) {
                    e.preventDefault();
                    toggleEngineMenu();
                } else if (key === 'ArrowDown' || key === 40) {
                    e.preventDefault();
                    setEngineMenu(true);
                } else if (key === 'Escape' || key === 27) {
                    setEngineMenu(false);
                }
            });
        }

        if (engineOptions) {
            engineOptions.addEventListener('click', function (e) {
                // 点击选项时不触发 document 的“点击外部关闭”
                e.stopPropagation();

                if (!e.target.closest('.engine-option')) {
                    return;
                }
                // 选中状态、偏好写入与 UI 刷新统一交给 radio 的 change 事件，
                // 这里只负责关闭菜单（含重复点击已选中项的场景）
                setEngineMenu(false);
            });

            engineOptions.addEventListener('keydown', function (e) {
                var key = e.key || e.keyCode;
                var count = engines.length;
                if (!count) {
                    return;
                }

                if (key === 'ArrowDown' || key === 'ArrowUp' || key === 40 || key === 38) {
                    e.preventDefault();
                    var index = -1;
                    for (var k = 0; k < count; k++) {
                        if (engines[k].checked) {
                            index = k;
                            break;
                        }
                    }
                    if (index < 0) {
                        index = 0;
                    } else {
                        index = (index + ((key === 'ArrowDown' || key === 40) ? 1 : count - 1)) % count;
                    }
                    engines[index].checked = true;
                    storageSet(SOU_KEY, engines[index].getAttribute('data-alias') || '');
                    updateEngineUI();
                    if (engines[index].focus) {
                        engines[index].focus();
                    }
                } else if (key === 'Enter' || key === ' ' || key === 13 || key === 32) {
                    e.preventDefault();
                    var current = currentEngine();
                    if (current) {
                        storageSet(SOU_KEY, current.getAttribute('data-alias') || '');
                        updateEngineUI();
                    }
                    setEngineMenu(false);
                    if (searchInput) {
                        searchInput.focus();
                    }
                } else if (key === 'Escape' || key === 27) {
                    e.preventDefault();
                    setEngineMenu(false);
                    if (searchSwitcher) {
                        searchSwitcher.focus();
                    }
                }
            });
        }

        for (var i = 0; i < engines.length; i++) {
            engines[i].addEventListener('change', function () {
                storageSet(SOU_KEY, this.getAttribute('data-alias') || '');
                updateEngineUI();
                setEngineMenu(false);
            });
        }

        if (searchForm) {
            searchForm.addEventListener('submit', function (e) {
                e.preventDefault();
                var keyword = searchInput ? trimText(searchInput.value) : '';
                if (!keyword) {
                    if (searchInput) {
                        searchInput.focus();
                    }
                    return;
                }
                doSearch(keyword);
                clearSuggestions();
            });
        }

        if (searchInput) {
            searchInput.addEventListener('input', function () {
                var keyword = trimText(this.value);
                if (suggestTimer) {
                    clearTimeout(suggestTimer);
                    suggestTimer = null;
                }
                if (!keyword) {
                    clearSuggestions();
                    return;
                }
                suggestTimer = setTimeout(function () {
                    fetchSuggestions(keyword);
                }, 250);
            });
        }

        if (suggestionList) {
            suggestionList.addEventListener('click', function (e) {
                var node = e.target;
                while (node && node !== suggestionList && (!node.tagName || node.tagName.toLowerCase() !== 'li')) {
                    node = node.parentNode;
                }
                if (!node || node === suggestionList) {
                    return;
                }
                var word = trimText(node.textContent || node.innerText || '');
                if (word && searchInput) {
                    searchInput.value = word;
                    clearSuggestions();
                    doSearch(word);
                }
            });
        }
    }

    // ============ 分类筛选 ============
    function filterCategory(categoryId) {
        var groups = document.querySelectorAll('.link-group');
        for (var i = 0; i < groups.length; i++) {
            var groupId = groups[i].getAttribute('data-group-id');
            groups[i].style.display = (categoryId === '0' || groupId === categoryId) ? '' : 'none';
        }
    }

    // ============ 分类栏横向滚动 ============
    // .category-scroll 隐藏了滚动条：触屏可原生滑动，但桌面鼠标既看不到滚动条，
    // 竖向滚轮也不会转成横向滚动，分类较多时后半部分无法访问。
    // 这里补：滚轮→横向滚动、按住拖动、两侧箭头、边缘渐隐。
    function setClass(node, name, enabled) {
        if (!node) {
            return;
        }
        if (enabled) {
            node.classList.add(name);
        } else {
            node.classList.remove(name);
        }
    }

    function prefersReducedMotion() {
        try {
            return !!(window.matchMedia
                && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
        } catch (e) {
            return false;
        }
    }

    function scrollScrollerBy(scroller, amount) {
        if (!scroller || !amount) {
            return;
        }
        if (prefersReducedMotion() || typeof scroller.scrollBy !== 'function') {
            scroller.scrollLeft += amount;
            return;
        }
        try {
            scroller.scrollBy({ left: amount, behavior: 'smooth' });
        } catch (e) {
            scroller.scrollLeft += amount;
        }
    }

    // 让选中的分类尽量居中，避免它在可视区边缘或完全滑出
    function scrollCategoryIntoView(item) {
        var scroller = document.querySelector('.category-nav .category-scroll');
        if (!item || !scroller) {
            return;
        }
        var scrollerRect = scroller.getBoundingClientRect();
        var itemRect = item.getBoundingClientRect();
        if (itemRect.left >= scrollerRect.left && itemRect.right <= scrollerRect.right) {
            return;
        }
        scrollScrollerBy(scroller, (itemRect.left - scrollerRect.left)
            - (scrollerRect.width - itemRect.width) / 2);
    }

    function bindCategoryScroll() {
        var nav = document.querySelector('.category-nav');
        if (!nav) {
            return;
        }
        var scroller = nav.querySelector('.category-scroll');
        if (!scroller) {
            return;
        }
        var EPS = 2;
        var drag = null;
        var suppressClick = false;

        function updateState() {
            var max = scroller.scrollWidth - scroller.clientWidth;
            var scrollable = max > EPS;
            var atStart = scroller.scrollLeft <= EPS;
            var atEnd = scroller.scrollLeft >= max - EPS;
            setClass(nav, 'is-scrollable', scrollable);
            setClass(nav, 'at-start', atStart);
            setClass(nav, 'at-end', atEnd);
            setClass(nav, 'can-scroll-left', scrollable && !atStart);
            setClass(nav, 'can-scroll-right', scrollable && !atEnd);
        }

        function onWheel(e) {
            if (scroller.scrollWidth <= scroller.clientWidth + EPS) {
                return;
            }
            // 触控板/横向滚轮已经能横滚，交回浏览器处理
            if (Math.abs(e.deltaX) > Math.abs(e.deltaY)) {
                return;
            }
            var unit = e.deltaMode === 1 ? 16 : (e.deltaMode === 2 ? scroller.clientWidth : 1);
            var max = scroller.scrollWidth - scroller.clientWidth;
            var next = scroller.scrollLeft + e.deltaY * unit;
            if (next < 0) {
                next = 0;
            } else if (next > max) {
                next = max;
            }
            if (Math.abs(next - scroller.scrollLeft) < 1) {
                return;
            }
            e.preventDefault();
            scroller.scrollLeft = next;
        }

        function onMouseDown(e) {
            if (e.button !== 0 || scroller.scrollWidth <= scroller.clientWidth + EPS) {
                return;
            }
            drag = { startX: e.clientX, startLeft: scroller.scrollLeft, moved: false };
            // 阻止拖动时的文本选中与原生图片拖拽
            e.preventDefault();
        }

        function onMouseMove(e) {
            if (!drag) {
                return;
            }
            var dx = e.clientX - drag.startX;
            if (!drag.moved) {
                if (Math.abs(dx) < 5) {
                    return;
                }
                drag.moved = true;
                scroller.classList.add('is-dragging');
            }
            scroller.scrollLeft = drag.startLeft - dx;
        }

        function onMouseUp() {
            if (!drag) {
                return;
            }
            var moved = drag.moved;
            drag = null;
            scroller.classList.remove('is-dragging');
            if (moved) {
                // 拖动结束后浏览器还会派发 click，抑制它以免误切分类
                suppressClick = true;
                setTimeout(function () {
                    suppressClick = false;
                }, 0);
            }
        }

        scroller.addEventListener('wheel', onWheel);
        scroller.addEventListener('mousedown', onMouseDown);
        document.addEventListener('mousemove', onMouseMove);
        document.addEventListener('mouseup', onMouseUp);
        scroller.addEventListener('click', function (e) {
            if (!suppressClick) {
                return;
            }
            suppressClick = false;
            e.preventDefault();
            e.stopPropagation();
        }, true);
        scroller.addEventListener('scroll', updateState);
        window.addEventListener('resize', updateState);
        window.addEventListener('load', updateState);
        updateState();

        var arrows = nav.querySelectorAll('.category-arrow');
        for (var i = 0; i < arrows.length; i++) {
            (function (arrow) {
                arrow.addEventListener('click', function () {
                    var dir = arrow.getAttribute('data-scroll-dir') === 'prev' ? -1 : 1;
                    scrollScrollerBy(scroller, dir * Math.max(160, Math.round(scroller.clientWidth * 0.7)));
                });
            }(arrows[i]));
        }

        // 字体/图标加载会改变标签宽度，需要重新判断是否溢出
        if (typeof window.ResizeObserver === 'function') {
            try {
                new window.ResizeObserver(updateState).observe(scroller);
            } catch (e) {
                // 忽略不支持的情况，已有 resize / load 兜底
            }
        }
    }

    function bindCategories() {
        var items = document.querySelectorAll('.category-item');
        for (var i = 0; i < items.length; i++) {
            items[i].addEventListener('click', function () {
                var categoryId = this.getAttribute('data-cat') || '0';
                var allItems = document.querySelectorAll('.category-item');
                for (var j = 0; j < allItems.length; j++) {
                    allItems[j].classList.remove('active');
                    allItems[j].setAttribute('aria-pressed', 'false');
                }
                this.classList.add('active');
                this.setAttribute('aria-pressed', 'true');
                filterCategory(categoryId);
                scrollCategoryIntoView(this);
            });
        }
    }

    // ============ 下拉菜单（键盘 / 触摸可用） ============
    // 调色板与「更多」菜单原本只靠 :hover 展开，触屏和键盘都无法操作。
    // 这里统一由触发按钮切换 .open，CSS 同时保留 :hover 以维持桌面悬停体验。
    function closeAllDropdowns() {
        var opened = document.querySelectorAll('.dropdown.open, .palette-dropdown.open');
        for (var i = 0; i < opened.length; i++) {
            opened[i].classList.remove('open');
            var trigger = opened[i].querySelector('.icon-btn');
            if (!trigger) {
                continue;
            }
            trigger.setAttribute('aria-expanded', 'false');
            if (opened[i].contains && opened[i].contains(document.activeElement) && document.activeElement !== trigger) {
                try {
                    trigger.focus();
                } catch (e) {
                    // 忽略聚焦失败
                }
            }
        }
    }

    function bindDropdowns() {
        var dropdowns = document.querySelectorAll('.palette-dropdown, .dropdown');
        for (var i = 0; i < dropdowns.length; i++) {
            (function (dropdown) {
                var trigger = dropdown.querySelector('.icon-btn');
                if (!trigger) {
                    return;
                }
                trigger.addEventListener('click', function (e) {
                    // 阻止冒泡，避免刚展开就被 document 的关闭逻辑收起
                    e.stopPropagation();
                    var willOpen = !dropdown.classList.contains('open');
                    closeAllDropdowns();
                    if (willOpen) {
                        dropdown.classList.add('open');
                        trigger.setAttribute('aria-expanded', 'true');
                    }
                });
            })(dropdowns[i]);
        }
        document.addEventListener('click', closeAllDropdowns);
    }

    // ============ 初始化 ============
    function applyDefaultNightMode() {
        var stored = storageGet(NIGHT_KEY);
        if (stored === 'true') {
            document.documentElement.classList.add('night-mode');
            return;
        }
        if (stored === 'false') {
            document.documentElement.classList.remove('night-mode');
            return;
        }
        var mode = bodyData('nightMode') || 'auto';
        var prefersDark = false;
        try {
            prefersDark = !!(window.matchMedia
                && window.matchMedia('(prefers-color-scheme: dark)').matches);
        } catch (e) {
            prefersDark = false;
        }
        var night = mode === 'dark' || (mode === 'auto' && prefersDark);
        if (night) {
            document.documentElement.classList.add('night-mode');
        } else {
            document.documentElement.classList.remove('night-mode');
        }
    }

    function init() {
        setColor(storageGet(COLOR_KEY) || bodyData('defaultColor') || 'purple');
        applyDefaultNightMode();
        updateNightModeIcon();
        updateClock();
        setInterval(updateClock, 1000);
        restoreBackground();
        restoreEngine();
        bindSearch();
        bindCategories();
        bindCategoryScroll();
        bindDropdowns();

        var nightBtn = document.getElementById('nightBtn');
        if (nightBtn) {
            nightBtn.addEventListener('click', function () {
                var isNight = document.documentElement.classList.toggle('night-mode');
                storageSet(NIGHT_KEY, isNight ? 'true' : 'false');
                updateNightModeIcon();
            });
        }

        var bgBtn = document.getElementById('bgBtn');
        if (bgBtn) {
            bgBtn.addEventListener('click', cycleBackground);
        }

        document.addEventListener('click', function (e) {
            var target = e.target;
            if (!target.closest('.search-engine-switcher') && !target.closest('.engine-options')) {
                setEngineMenu(false);
            }

            var colorOpt = target.closest('.color-opt');
            if (colorOpt) {
                setColor(colorOpt.getAttribute('data-color'));
                return;
            }

            if (!target.closest('.search-area')) {
                clearSuggestions();
            }
        });

        document.addEventListener('keydown', function (e) {
            var key = e.key || e.keyCode;
            if (key === 'Escape' || key === 27) {
                setEngineMenu(false);
                closeAllDropdowns();
            }
        });

        if (window.innerWidth >= 768 && searchInput) {
            setTimeout(function () {
                try {
                    searchInput.focus();
                } catch (e) {
                    // 忽略聚焦失败
                }
            }, 300);
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
