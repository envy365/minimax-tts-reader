let currentConfig = null;
let activeConfigName = '默认配置';
let logRefreshTimer = null;

var DEFAULT_CONFIG_NAME = '默认配置';

function init() {
    initSliderButtons();
    loadConfigList();
    loadActiveConfig();
    initTestPrompts();
    loadNormalizeMode();
    try { updateServiceUI(Android.isServiceRunning()); } catch(e) {}
    startLogRefresh();
}

// ===== 页面切换 =====

function switchPage(page) {
    var pages = ['runtime', 'config', 'account', 'llm', 'logs'];
    var pageIds = { runtime: 'pageRuntime', config: 'pageConfig', account: 'pageAccount', llm: 'pageLlm', logs: 'pageLogs' };
    var actionBar = document.getElementById('configActionBar');
    var tabs = document.querySelectorAll('.tab-item');
    for (var i = 0; i < tabs.length; i++) {
        tabs[i].classList.toggle('active', tabs[i].getAttribute('data-page') === page);
    }
    pages.forEach(function(p) {
        document.getElementById(pageIds[p]).style.display = (p === page) ? '' : 'none';
    });
    actionBar.style.display = (page === 'config') ? 'flex' : 'none';
    if (page === 'logs') loadLlmLogDates();
}

// ===== 配置管理 =====

function loadConfigList() {
    try {
        var list = JSON.parse(Android.getConfigList());
        var sel = document.getElementById('configSelect');
        var runtimeSel = document.getElementById('runtimeConfigSelect');
        sel.innerHTML = '';
        if (runtimeSel) runtimeSel.innerHTML = '';
        list.forEach(function(name) {
            var opt = document.createElement('option');
            opt.value = name;
            opt.textContent = name;
            sel.appendChild(opt);
            if (runtimeSel) {
                var opt2 = document.createElement('option');
                opt2.value = name;
                opt2.textContent = name;
                runtimeSel.appendChild(opt2);
            }
        });
    } catch (e) { console.error('Failed to load config list:', e); }
}

function loadActiveConfig() {
    try {
        activeConfigName = Android.getActiveConfigName();
    } catch (e) {
        activeConfigName = DEFAULT_CONFIG_NAME;
    }
    document.getElementById('configSelect').value = activeConfigName;
    var runtimeSel = document.getElementById('runtimeConfigSelect');
    if (runtimeSel) runtimeSel.value = activeConfigName;
    loadConfigByName(activeConfigName);
    updateReadOnlyMode();
    updateActionBar();
    updateActiveConfigDisplay();
}

function loadConfigByName(name) {
    try {
        var json = Android.loadConfigByName(name);
        currentConfig = JSON.parse(json);
        fillForm(currentConfig);
        // 重新加载依赖 currentConfig 的下拉选项
        loadVoices();
        loadOptions();
        loadLlmModels();
        updateLegadoRule();
    } catch (e) {
        // 浏览器环境降级：用默认值
        currentConfig = {};
        fillForm(currentConfig);
    }
    loadAccount();
    loadLlmSettings();
}

// ===== 全局账户 =====

function loadAccount() {
    try {
        var account = JSON.parse(Android.getAccount());
        document.getElementById('apiKey').value = account.apiKey || '';
        document.getElementById('groupId').value = account.groupId || '';
    } catch (e) {}
}

function saveAccount() {
    var apiKey = document.getElementById('apiKey').value.trim();
    var groupId = document.getElementById('groupId').value.trim();
    try {
        Android.saveAccount(apiKey, groupId);
    } catch (e) {}
}

function onConfigSelectChange() {
    var name = document.getElementById('configSelect').value;
    activeConfigName = name;
    try { Android.setActiveConfigName(name); } catch(e) {}
    loadConfigByName(name);
    updateReadOnlyMode();
    updateActionBar();
    updateActiveConfigDisplay();
    var runtimeSel = document.getElementById('runtimeConfigSelect');
    if (runtimeSel) runtimeSel.value = name;
}

function onRuntimeConfigSelectChange() {
    var name = document.getElementById('runtimeConfigSelect').value;
    activeConfigName = name;
    try { Android.setActiveConfigName(name); } catch(e) {}
    loadConfigByName(name);
    updateReadOnlyMode();
    updateActionBar();
    updateActiveConfigDisplay();
    document.getElementById('configSelect').value = name;
    try { Android.showToast('已切换: ' + name); } catch(e) {}
}

function onSaveConfig() {
    if (activeConfigName === DEFAULT_CONFIG_NAME) {
        showNewConfigModal();
        return;
    }
    try {
        var config = collectConfig();
        // apiKey/groupId/LLM 由各自页面独立保存，这里清空避免覆盖全局值
        config.apiKey = '';
        config.groupId = '';
        config.llmEnabled = false;
        config.llmModel = '';
        config.llmPrompt = '';
        config.llmMaxTokens = 0;
        config.llmTemperature = 0;
        config.llmThinking = '';
        Android.saveConfigByName(activeConfigName, JSON.stringify(config));
        currentConfig = config;
        updateLegadoRule();
        Android.showToast('保存成功');
    } catch (e) { Android.showToast('保存失败: ' + e.message); }
}

function onDeleteConfig() {
    if (activeConfigName === DEFAULT_CONFIG_NAME) return;
    if (!confirm('确认删除配置「' + activeConfigName + '」？此操作不可撤销。')) return;
    try {
        Android.deleteConfigByName(activeConfigName);
        Android.showToast('已删除: ' + activeConfigName);
        loadConfigList();
        activeConfigName = DEFAULT_CONFIG_NAME;
        try { Android.setActiveConfigName(DEFAULT_CONFIG_NAME); } catch(e) {}
        document.getElementById('configSelect').value = DEFAULT_CONFIG_NAME;
        loadConfigByName(DEFAULT_CONFIG_NAME);
        updateReadOnlyMode();
        updateActionBar();
        updateActiveConfigDisplay();
    } catch (e) { Android.showToast('删除失败: ' + e.message); }
}

function updateReadOnlyMode() {
    var configPage = document.getElementById('pageConfig');
    if (activeConfigName === DEFAULT_CONFIG_NAME) {
        configPage.classList.add('readonly-mode');
    } else {
        configPage.classList.remove('readonly-mode');
    }
}

function updateActionBar() {
    var btnSave = document.getElementById('btnSaveConfig');
    var btnDelete = document.getElementById('btnDeleteConfig');
    if (activeConfigName === DEFAULT_CONFIG_NAME) {
        btnSave.textContent = '保存为新配置';
        btnSave.className = 'btn btn-accent';
        btnDelete.style.display = 'none';
    } else {
        btnSave.textContent = '保存';
        btnSave.className = 'btn btn-primary';
        btnDelete.style.display = '';
    }
}

function updateActiveConfigDisplay() {
    var el = document.getElementById('activeConfigDisplay');
    if (el) el.textContent = activeConfigName;
}

// ===== 新建配置 Modal =====

function showNewConfigModal() {
    document.getElementById('newConfigModal').style.display = 'flex';
    var input = document.getElementById('newConfigName');
    input.value = '';
    setTimeout(function() { input.focus(); }, 100);
}

function hideNewConfigModal() {
    document.getElementById('newConfigModal').style.display = 'none';
}

function confirmNewConfig() {
    var name = document.getElementById('newConfigName').value.trim();
    if (!name) { Android.showToast('请输入配置名称'); return; }
    if (name === DEFAULT_CONFIG_NAME) { Android.showToast('不能使用"默认配置"作为名称'); return; }
    try {
        var config = collectConfig();
        config.apiKey = '';
        config.groupId = '';
        config.llmEnabled = false;
        config.llmModel = '';
        config.llmPrompt = '';
        config.llmMaxTokens = 0;
        config.llmTemperature = 0;
        config.llmThinking = '';
        Android.saveConfigByName(name, JSON.stringify(config));
        currentConfig = config;
        activeConfigName = name;
        Android.showToast('保存成功');
        hideNewConfigModal();
        loadConfigList();
        document.getElementById('configSelect').value = name;
        updateReadOnlyMode();
        updateActionBar();
        updateActiveConfigDisplay();
        updateLegadoRule();
    } catch (e) { Android.showToast('创建失败: ' + e.message); }
}

// ===== 日志刷新 =====

function startLogRefresh() {
    if (logRefreshTimer) clearInterval(logRefreshTimer);
    logRefreshTimer = setInterval(function() {
        try {
            var logsJson = Android.getLogs();
            if (logsJson) onLogsUpdate(logsJson);
        } catch(e) {}
    }, 2000);
}

// ===== 表单 =====

function fillForm(config) {
    document.getElementById('serverPort').value = config.serverPort || 9966;
    setRange('speed', config.speed != null ? config.speed : 1.0, 'speedVal');
    setRange('vol', config.vol != null ? config.vol : 1.0, 'volVal');
    setRange('pitch', config.pitch != null ? config.pitch : 0, 'pitchVal');
    setRange('vmPitch', config.vmPitch != null ? config.vmPitch : 0, 'vmPitchVal');
    setRange('vmIntensity', config.vmIntensity != null ? config.vmIntensity : 0, 'vmIntensityVal');
    setRange('vmTimbre', config.vmTimbre != null ? config.vmTimbre : 0, 'vmTimbreVal');
    document.getElementById('pronunciationDict').value = config.pronunciationDict || '';
    document.getElementById('textNormalization').checked = !!config.textNormalization;
    var thinkingSelect = document.getElementById('llmThinkingSelect');
    if (thinkingSelect) thinkingSelect.value = (config.llmThinking != null) ? config.llmThinking : 'disabled';
}

function setRange(id, val, valId) {
    document.getElementById(id).value = val;
    var display = document.getElementById(valId);
    if (display) display.value = val;
}

function collectConfig() {
    return {
        apiKey: document.getElementById('apiKey').value.trim(),
        groupId: document.getElementById('groupId').value.trim(),
        model: document.getElementById('modelSelect').value,
        voice: document.getElementById('voiceSelect').value,
        speed: parseFloat(document.getElementById('speed').value),
        vol: parseFloat(document.getElementById('vol').value),
        pitch: parseInt(document.getElementById('pitch').value),
        textNormalization: document.getElementById('textNormalization').checked,
        emotion: document.getElementById('emotionSelect').value,
        languageBoost: document.getElementById('languageBoostSelect').value,
        serverPort: parseInt(document.getElementById('serverPort').value) || 9966,
        vmPitch: parseInt(document.getElementById('vmPitch').value),
        vmIntensity: parseInt(document.getElementById('vmIntensity').value),
        vmTimbre: parseInt(document.getElementById('vmTimbre').value),
        soundEffects: document.getElementById('soundEffectsSelect').value,
        sampleRate: parseInt(document.getElementById('sampleRateSelect').value),
        channel: parseInt(document.getElementById('channelSelect').value),
        pronunciationDict: document.getElementById('pronunciationDict').value.trim(),
        llmEnabled: llmEnabled,
        llmModel: document.getElementById('llmModelSelect').value,
        llmPrompt: document.getElementById('llmPrompt').value.trim(),
        llmMaxTokens: parseInt(document.getElementById('llmMaxTokens').value),
        llmTemperature: parseFloat(document.getElementById('llmTemperature').value),
        llmThinking: document.getElementById('llmThinkingSelect').value
    };
}

// ===== 下拉选项加载 =====

function loadVoices() {
    try {
        var voices = JSON.parse(Android.getVoices());
        var select = document.getElementById('voiceSelect');
        select.innerHTML = '';
        var curCat = '', curOg = null;
        voices.forEach(function(v) {
            if (v.category !== curCat) {
                curOg = document.createElement('optgroup');
                curOg.label = v.category;
                select.appendChild(curOg);
                curCat = v.category;
            }
            var opt = document.createElement('option');
            opt.value = v.id;
            opt.textContent = v.name + ' (' + v.gender + ')';
            curOg.appendChild(opt);
        });
        if (currentConfig) {
            select.value = currentConfig.voice || 'audiobook_male_1';
            if (!select.value) {
                showVoiceWarning('当前配置的音色「' + currentConfig.voice + '」不在预置列表中，朗读时将回退为默认音色「男性有声书1」');
            } else {
                hideVoiceWarning();
            }
        } else {
            hideVoiceWarning();
        }
    } catch (e) { console.error('Failed to load voices:', e); }
}

// ===== 音色无效提示 =====

function showVoiceWarning(message) {
    var el = document.getElementById('voiceWarning');
    if (el) { el.textContent = message; el.style.display = 'block'; }
}

function hideVoiceWarning() {
    var el = document.getElementById('voiceWarning');
    if (el) el.style.display = 'none';
}

function loadOptions() {
    try {
        var opt = JSON.parse(Android.getOptions());
        fillPairSelect('modelSelect', opt.models, currentConfig ? (currentConfig.model || 'speech-2.8-hd') : 'speech-2.8-hd');
        fillPairSelect('emotionSelect', opt.emotions, currentConfig ? currentConfig.emotion : '');
        fillPairSelect('languageBoostSelect', opt.languageBoosts, currentConfig ? currentConfig.languageBoost : 'auto');
        fillPairSelect('soundEffectsSelect', opt.soundEffects, currentConfig ? currentConfig.soundEffects : '');
        fillValueSelect('sampleRateSelect', opt.sampleRates, currentConfig ? currentConfig.sampleRate : 32000);
        fillPairSelect('channelSelect', opt.channels, currentConfig ? currentConfig.channel : 1);
    } catch (e) { console.error('Failed to load options:', e); }
}

function fillPairSelect(id, pairs, selected) {
    var sel = document.getElementById(id);
    sel.innerHTML = '';
    pairs.forEach(function(p) {
        var opt = document.createElement('option');
        var val = (p.first != null) ? p.first : p[0];
        var txt = (p.second != null) ? p.second : p[1];
        opt.value = val;
        opt.textContent = txt;
        sel.appendChild(opt);
    });
    sel.value = selected;
}

function fillValueSelect(id, values, selected) {
    var sel = document.getElementById(id);
    sel.innerHTML = '';
    values.forEach(function(v) {
        var opt = document.createElement('option');
        opt.value = v;
        opt.textContent = v;
        sel.appendChild(opt);
    });
    sel.value = selected;
}

function loadLlmModels() {
    try {
        var pairs = JSON.parse(Android.getLlmModels());
        var cur = (currentConfig && currentConfig.llmModel) ? currentConfig.llmModel : 'MiniMax-M2.7-highspeed';
        fillPairSelect('llmModelSelect', pairs, cur);
    } catch (e) { console.error('Failed to load LLM models:', e); }
}

// ===== 服务控制 =====

function startService() { Android.startService(); updateServiceUI(true); }
function stopService() { Android.stopService(); updateServiceUI(false); }
function restartService() { Android.restartService(); }

function updateServiceUI(running) {
    var badge = document.getElementById('statusBadge');
    var statusText = document.getElementById('serviceStatus');
    var btnStart = document.getElementById('btnStart');
    var btnStop = document.getElementById('btnStop');
    var btnRestart = document.getElementById('btnRestart');
    var serverUrl = document.getElementById('serverUrl');
    if (running) {
        badge.className = 'status-badge running';
        badge.querySelector('.status-text').textContent = '运行中';
        statusText.textContent = '运行中';
        statusText.style.color = '#6E8C7A';
        btnStart.disabled = true; btnStop.disabled = false; btnRestart.disabled = false;
        try { serverUrl.textContent = Android.getServerUrl(); }
        catch (e) { serverUrl.textContent = '-'; }
    } else {
        badge.className = 'status-badge';
        badge.querySelector('.status-text').textContent = '已停止';
        statusText.textContent = '未启动';
        statusText.style.color = '#A3483B';
        btnStart.disabled = false; btnStop.disabled = true; btnRestart.disabled = true;
        serverUrl.textContent = '-';
    }
    updateActiveConfigDisplay();
}

function onServiceStateChanged(running) { updateServiceUI(running); }

// ===== Legado 集成 =====

function updateLegadoRule() {
    try {
        var ruleJson = Android.getLegadoRule();
        var rule = JSON.parse(ruleJson);
        document.getElementById('legadoRule').textContent = JSON.stringify(rule, null, 2);
    } catch (e) { document.getElementById('legadoRule').textContent = '获取规则失败'; }
}

function copyLegadoRule() {
    var rule = document.getElementById('legadoRule').textContent;
    if (navigator.clipboard) {
        navigator.clipboard.writeText(rule).then(function() { Android.showToast('已复制到剪贴板'); });
    } else {
        var ta = document.createElement('textarea');
        ta.value = rule; document.body.appendChild(ta); ta.select();
        document.execCommand('copy'); document.body.removeChild(ta);
        Android.showToast('已复制到剪贴板');
    }
}

function importToLegado() { Android.importToLegado(); }

// ===== LLM 设置（全局） =====

var llmEnabled = false;

function toggleLlmEnabled() {
    llmEnabled = !llmEnabled;
    updateLlmToggleButton();
    try { Android.showToast(llmEnabled ? '预处理已启用' : '预处理已关闭'); } catch(e) {}
}

function updateLlmToggleButton() {
    var btn = document.getElementById('btnLlmToggle');
    if (llmEnabled) {
        btn.textContent = '关闭预处理';
        btn.className = 'btn btn-danger btn-block';
    } else {
        btn.textContent = '启用预处理';
        btn.className = 'btn btn-secondary btn-block';
    }
}

function loadLlmSettings() {
    try {
        var settings = JSON.parse(Android.getLlmSettings());
        llmEnabled = !!settings.llmEnabled;
        document.getElementById('llmPrompt').value = settings.llmPrompt || '';
        setRange('llmMaxTokens', settings.llmMaxTokens || 2048, 'llmMaxTokensVal');
        setRange('llmTemperature', settings.llmTemperature != null ? settings.llmTemperature : 0.1, 'llmTemperatureVal');
        var sel = document.getElementById('llmModelSelect');
        if (settings.llmModel && sel.options.length > 0) sel.value = settings.llmModel;
        var thinkingSelect = document.getElementById('llmThinkingSelect');
        if (thinkingSelect) thinkingSelect.value = (settings.llmThinking != null) ? settings.llmThinking : 'disabled';
        updateLlmToggleButton();
    } catch (e) {}
}

function saveLlmSettings() {
    var settings = {
        llmEnabled: llmEnabled,
        llmModel: document.getElementById('llmModelSelect').value,
        llmPrompt: document.getElementById('llmPrompt').value.trim(),
        llmMaxTokens: parseInt(document.getElementById('llmMaxTokens').value),
        llmTemperature: parseFloat(document.getElementById('llmTemperature').value),
        llmThinking: document.getElementById('llmThinkingSelect').value
    };
    try {
        Android.saveLlmSettings(JSON.stringify(settings));
    } catch (e) {}
}

// ===== LLM 预处理日志 =====

function getApiBase() {
    var port = parseInt(document.getElementById('serverPort').value) || 9966;
    return 'http://127.0.0.1:' + port;
}

function loadLlmLogDates() {
    var dateSel = document.getElementById('llmLogDateSelect');
    var list = document.getElementById('llmLogList');
    if (!dateSel || !list) return;
    var prev = dateSel.value;
    fetch(getApiBase() + '/api/llm-logs/dates')
        .then(function(r) { return r.json(); })
        .then(function(data) {
            var dates = (data && data.dates) ? data.dates : [];
            dateSel.innerHTML = '';
            if (!dates.length) {
                var opt = document.createElement('option');
                opt.value = '';
                opt.textContent = '暂无日志';
                dateSel.appendChild(opt);
                list.innerHTML = '<div class="log-empty">暂无日志</div>';
                return;
            }
            dates.forEach(function(d) {
                var opt = document.createElement('option');
                opt.value = d;
                opt.textContent = d;
                dateSel.appendChild(opt);
            });
            if (prev && dates.indexOf(prev) >= 0) dateSel.value = prev;
            loadLlmLogs();
        })
        .catch(function() {
            list.innerHTML = '<div class="log-empty">无法连接本地服务，请先启动服务再刷新</div>';
        });
}

function loadLlmLogs() {
    var dateSel = document.getElementById('llmLogDateSelect');
    var list = document.getElementById('llmLogList');
    if (!dateSel || !list) return;
    var date = dateSel.value;
    if (!date) {
        list.innerHTML = '<div class="log-empty">暂无日志</div>';
        return;
    }
    list.innerHTML = '<div class="log-empty">加载中…</div>';
    fetch(getApiBase() + '/api/llm-logs?date=' + encodeURIComponent(date))
        .then(function(r) { return r.json(); })
        .then(function(data) {
            var logs = (data && data.logs) ? data.logs : [];
            if (!logs.length) {
                list.innerHTML = '<div class="log-empty">当日暂无日志</div>';
                return;
            }
            list.innerHTML = logs.map(renderLlmLogItem).join('');
        })
        .catch(function() {
            list.innerHTML = '<div class="log-empty">加载日志失败，请稍后重试</div>';
        });
}

function renderLlmLogItem(log) {
    var status = llmLogStatus(log);
    var lines = [];
    lines.push('<div class="llm-log-item">');
    lines.push('<div class="llm-log-head" onclick="toggleLlmLogDetail(this)">');
    lines.push('<div class="llm-log-line"><span class="llm-log-status ' + status.cls + '">' + status.text + '</span><span class="llm-log-time">' + escapeHtml(log.time || '') + '</span></div>');
    lines.push('<div class="llm-log-line">#' + (log.requestId != null ? log.requestId : '-') + ' · ' + escapeHtml(log.model || '-') + ' · ' + escapeHtml(llmPromptTypeText(log.promptType)) + ' · 文本 ' + (log.textLen != null ? log.textLen : '-') + ' 字 · ' + (log.durationMs != null ? log.durationMs : '-') + 'ms</div>');
    if (log.textPreview) lines.push('<div class="llm-log-preview">' + escapeHtml(log.textPreview) + '</div>');
    lines.push('</div>');
    lines.push('<div class="llm-log-detail" style="display:none;">');
    lines.push('<div class="llm-log-detail-title">输出</div><pre class="llm-log-pre">' + (log.output ? escapeHtml(log.output) : '(无输出，请求被跳过)') + '</pre>');
    lines.push('<div class="llm-log-detail-title">思考内容</div><pre class="llm-log-pre">' + (log.reasoning ? escapeHtml(log.reasoning) : '(无)') + '</pre>');
    if (log.error) lines.push('<div class="llm-log-detail-title">错误</div><pre class="llm-log-pre llm-log-error">' + escapeHtml(log.error) + '</pre>');
    lines.push('</div>');
    lines.push('</div>');
    return lines.join('');
}

function llmLogStatus(log) {
    if (log.skipped) return { text: '跳过', cls: 'warn' };
    if (log.error) return { text: '错误', cls: 'err' };
    if (log.fallback) return { text: '回退', cls: 'warn' };
    return { text: '正常', cls: 'ok' };
}

function llmPromptTypeText(type) {
    if (type === 'custom') return '自定义';
    if (type === 'default_28') return '默认prompt(2.8)';
    if (type === 'default_basic') return '基础prompt(2.8以下)';
    return type || '-';
}

function toggleLlmLogDetail(head) {
    var detail = head.nextElementSibling;
    if (detail) detail.style.display = (detail.style.display === 'none') ? '' : 'none';
}

function fillDefaultPrompt() {
    try { document.getElementById('llmPrompt').value = Android.getDefaultLlmPrompt(); }
    catch (e) { Android.showToast('获取默认 prompt 失败'); }
}

function fillBasicPrompt() {
    try { document.getElementById('llmPrompt').value = Android.getBasicLlmPrompt(); }
    catch (e) { Android.showToast('获取基础 prompt 失败'); }
}

function clearPrompt() { document.getElementById('llmPrompt').value = ''; }

// ===== 测试连接 =====

function testConnection() {
    var cfg = collectConfig();
    if (!cfg.apiKey) { Android.showToast('请先填写 API Key'); return; }
    if (!cfg.groupId) { Android.showToast('请先填写 GroupId'); return; }
    var btn = document.getElementById('btnTestConnection');
    btn.disabled = true; btn.textContent = '测试中...';
    var hint = document.getElementById('connectionResult');
    hint.textContent = '测试中...';
    Android.testConnection(JSON.stringify(cfg));
}

function onTestConnectionResult(ok, msg) {
    var btn = document.getElementById('btnTestConnection');
    btn.disabled = false; btn.textContent = '测试连接';
    var hint = document.getElementById('connectionResult');
    hint.textContent = (ok ? '✅ ' : '❎ ') + msg;
    hint.style.color = ok ? '#6E8C7A' : '#A3483B';
    if (!ok && /voice|音色/i.test(msg || '')) {
        showVoiceWarning('连接测试返回音色相关错误：' + msg);
    }
    Android.showToast(ok ? '连接成功' : '连接失败');
}

// ===== 语音测试 =====

var testPrompts = [
    "落魄谷中寒风吹，春秋蝉鸣少年归。",
    "人啊，走在自己的人生路上，就不要怕肮脏！踩着白骨和血肉，一步步走向辉煌！人啊，走在自己的人生路上，就不要怕悲伤！踏着汗水和泪水，一步步行向光芒！人啊！走在自己的人生路上，就不要怕仿徨！循着信念和理想，一步步走出迷惘！人啊！走在自己的人生路上，就注定要漂泊流浪！无需愤恨你没有知己，因为你还有自己！去吧，走向巅峰，踏向属于自己的天堂。",
    "人们总是害怕孤独，总要贪恋热闹的人群，总不愿无所事事。因为当他们面对孤独，往往就会面对痛苦。但是一旦能直面这种痛苦，人就往往有了才华和勇气。所以。有句俗语--杰出者必孤独。",
    "我喜欢无足鸟，你知道为什么吗？因为它没有鸟足，只有翅膀。因此只能飞翔。当它落地之时，就意味着它的毁灭。",
    "人生匆匆百年，如梦幻泡影。人活在这个世界上是为了什么？无非是走上一遭见证精彩罢了。我虽然不想死，但却不畏惧死亡。我已走在路上，纵死不悔。",
    "千万不要低估旁人的智慧，往往只有蠢才才会认为别人愚蠢。",
    "这个世界上，总会有一群“老”人。他们四处兜售着社会的经验，把他人的理想当做幻想，把他人的热情当做轻狂，把他人的坚持当做桀骜。他们常在教训后辈中，寻找自己的存在感和优越感。",
    "每个人他生来就是孤独！人就像是一座座的浮冰孤岛，在命运的海洋中漂浮流荡。人和人的相遇，就像是浮冰孤岛之间的相互碰撞，只要是碰撞，就必有影响。有时候，浮冰孤岛相互粘在了一起，以“利益”、“亲情”、“友情”、“爱情”、“仇恨”之名。但是最终，它们都将分开。孤独地走向毁灭。这就是人生的真相。",
    "遵守规矩不算是本事，真正的本事是破坏规矩而享受利益，却不受惩罚。真正的大本事，是破坏旧秩序，建立新规矩，一直享受利益。",
    "甘于弱小，而不自发努力，只想向强者乞讨的人，根本就不值得同情。",
    "这个世界其实是灰色的。有时候黑的能转成白的，白的能转成黑的。有的黑的未必比白的阴险，有的白的可能罪孽更深。",
    "态度是心的面具。",
    "他走的路，注定是无边的黑暗，注定是无比的孤独。他朝圣的方向，只是心中的光明--永生--一丝微小到不存在的可能。这个世界上，没有人明白他，而他，也不需要别人明白。",
    "人，本就是天地间的宝石。只是宝石璀璨与否，需要我们自己的雕琢。我们的每一次努力，每一次选择，都是一次雕琢。",
    "我们既然意识到自己的渺小，那就更应该变得强大。我们本来就是渺小的，只是从无知变得有知，你感到痛苦，是因为你在成长。",
    "我曾经呐喊过，渐渐的我不发出声音。我曾经哭泣过，渐渐的我不再流泪。我曾经悲伤过，渐渐的我能承受一切。我曾经喜悦过，渐渐的我看淡世间。而如今！我只剩下面无表情，我的目光如磐石般坚硬，我的心中剩下坚持。这就是我，一个小人物，我的--坚持！",
    "此生就愿成真月，出天山，戏云海，照古今，行走在黑暗的诸天之上。",
    "人的一生之精彩，在于自己追逐梦想的过程。不必苛求旁人的不失望或者喜欢。走自己的路，让旁人失望和不喜欢去吧！",
    "真正可靠的还是自己，真正成熟的人，永远不会依靠别人雪中送炭。",
    "这神性往光明处踏出微微一步，就是佛。往黑暗迈出半步，就是魔。",
    "他的底线就是没有原则，他的原则就是没有底线。",
    "当一个人惧怕的时候，他就成了奴隶。",
    "规矩只是无形的约束，时间越久，利益越大，就越无力。",
    "人之道，在于抗争。君子如龙，自强不息的精神意志，即使天塌地陷，也不能湮灭斗争之精神。命运不是不可以改变的！",
    "人千方百计的学习，来认知世界，知晓规则，就是要利用规则。若是被规则牵绊，反而因为自身所学而束手束脚，这才是真正的悲剧。",
    "在寻找成功的过程中，人往往会变得面目全非，而人最大的失败，就是失去自我。",
    "什么是失败？什么是成功？一开始就坚持住，不放弃，结束后欣然接受结果，不后悔，就是成功！坚持到底，不惧失败，就是成功！"
];

function initTestPrompts() {
    var sel = document.getElementById('testPromptSelect');
    sel.innerHTML = '';
    testPrompts.forEach(function(p, i) {
        var opt = document.createElement('option');
        opt.value = i;
        opt.textContent = p.length > 20 ? p.substring(0, 20) + '...' : p;
        sel.appendChild(opt);
    });
    sel.value = 0;
}

function onTestPromptSelect() {
    var sel = document.getElementById('testPromptSelect');
    var idx = parseInt(sel.value);
    document.getElementById('testText').value = testPrompts[idx];
}

function randomTestPrompt() {
    var sel = document.getElementById('testPromptSelect');
    var current = parseInt(sel.value);
    var idx;
    do {
        idx = Math.floor(Math.random() * testPrompts.length);
    } while (idx === current && testPrompts.length > 1);
    sel.value = idx;
    document.getElementById('testText').value = testPrompts[idx];
}

function testTts() {
    var text = document.getElementById('testText').value.trim();
    if (!text) { Android.showToast('请输入测试文本'); return; }
    try {
        Android.testTts(text);
        var btn = document.getElementById('btnTest');
        btn.disabled = true; btn.textContent = '合成中...';
    } catch (e) { Android.showToast('测试失败: ' + e.message); }
}

function onTestResult(fileUrl) {
    var player = document.getElementById('audioPlayer');
    var container = document.getElementById('audioPlayerContainer');
    player.src = fileUrl; container.style.display = 'block'; player.play();
    var btn = document.getElementById('btnTest');
    btn.disabled = false; btn.textContent = '测试合成';
    Android.showToast('合成成功');
}

function onTestError(errorMessage) {
    var btn = document.getElementById('btnTest');
    btn.disabled = false; btn.textContent = '测试合成';
    Android.showToast('合成失败: ' + errorMessage);
    if (/voice|音色/i.test(errorMessage || '')) {
        showVoiceWarning('语音测试返回音色相关错误：' + errorMessage);
    }
}

// ===== 其他 =====

function toggleApiKeyVisibility() {
    var input = document.getElementById('apiKey');
    var icon = document.getElementById('eyeIcon');
    if (input.type === 'password') { input.type = 'text'; icon.textContent = '🔒'; }
    else { input.type = 'password'; icon.textContent = '👁'; }
}

function requestBatteryOptimization() { Android.requestBatteryOptimization(); }

function onLogsUpdate(logsJson) {
    try {
        var logs = typeof logsJson === 'string' ? JSON.parse(logsJson) : logsJson;
        var container = document.getElementById('logContainer');
        if (!logs || logs.length === 0) { container.innerHTML = '<div class="log-empty">暂无日志</div>'; return; }
        container.innerHTML = logs.map(function(log) {
            var low = log.toLowerCase();
            var isError = low.indexOf('error') >= 0 || low.indexOf('fail') >= 0;
            var isSuccess = low.indexOf('started') >= 0 || low.indexOf('ready') >= 0 || low.indexOf('success') >= 0;
            var cls = isError ? 'error' : (isSuccess ? 'success' : '');
            return '<div class="log-entry"><span class="log-msg ' + cls + '">' + escapeHtml(log) + '</span></div>';
        }).join('');
    } catch (e) {}
}

function clearLogs() { document.getElementById('logContainer').innerHTML = '<div class="log-empty">暂无日志</div>'; }

function escapeHtml(text) {
    var div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// ===== 响度归一化（v0.6.x）=====

function loadNormalizeMode() {
    try {
        var mode = Android.getNormalizeMode();
        var sel = document.getElementById('normalizeModeSelect');
        if (sel) sel.value = mode || 'off';
    } catch (e) {}
}

function onNormalizeModeChange() {
    try {
        var mode = document.getElementById('normalizeModeSelect').value;
        Android.setNormalizeMode(mode);
        Android.showToast('响度模式已切换：' + mode + '（下次合成生效）');
    } catch (e) {}
}

// ===== 滑块输入校验 =====

function onRangeInput(id) {
    var slider = document.getElementById(id);
    var display = document.getElementById(id + 'Val');
    var step = parseFloat(slider.step);
    var precision = getStepPrecision(step);
    var val = parseFloat(slider.value).toFixed(precision);
    slider.value = val;
    if (display) display.value = val;
}

// === 增强滑块：数字输入、加减按钮、长按连发 ===

function getStepPrecision(step) {
    var s = String(step);
    var dot = s.indexOf('.');
    return dot >= 0 ? s.length - dot - 1 : 0;
}

function clampAndRound(val, min, max, step) {
    val = parseFloat(val);
    if (isNaN(val)) val = min;
    var precision = getStepPrecision(step);
    val = Math.round(val / step) * step;
    val = parseFloat(val.toFixed(precision));
    if (val < min) val = min;
    if (val > max) val = max;
    return val.toFixed(precision);
}

function onSliderInput(id) {
    var input = document.getElementById(id + 'Val');
    var raw = input.value;
    var neg = '';
    if (raw.charAt(0) === '-') { neg = '-'; raw = raw.slice(1); }
    raw = raw.replace(/[^\d.]/g, '');
    var parts = raw.split('.');
    if (parts.length > 2) raw = parts[0] + '.' + parts.slice(1).join('');
    if (raw.charAt(0) === '.') raw = '0' + raw;
    input.value = neg + raw;
}

function onSliderInputBlur(id) {
    var slider = document.getElementById(id);
    var input = document.getElementById(id + 'Val');
    var min = parseFloat(slider.min);
    var max = parseFloat(slider.max);
    var step = parseFloat(slider.step);
    var val = input.value;
    if (val === '' || val === '-' || val === '.') val = slider.value;
    val = clampAndRound(val, min, max, step);
    slider.value = val;
    input.value = val;
}

function adjustSlider(id, dir) {
    var slider = document.getElementById(id);
    var step = parseFloat(slider.step);
    var min = parseFloat(slider.min);
    var max = parseFloat(slider.max);
    var current = parseFloat(slider.value);
    var newVal = clampAndRound(current + dir * step, min, max, step);
    slider.value = newVal;
    var display = document.getElementById(id + 'Val');
    if (display) display.value = newVal;
}

var _lpTimer = null;
var _lpInterval = null;

function sliderBtnPress(id, dir) {
    adjustSlider(id, dir);
    _lpTimer = setTimeout(function() {
        _lpInterval = setInterval(function() {
            adjustSlider(id, dir);
        }, 100);
    }, 500);
}

function sliderBtnRelease() {
    if (_lpTimer) { clearTimeout(_lpTimer); _lpTimer = null; }
    if (_lpInterval) { clearInterval(_lpInterval); _lpInterval = null; }
}

function initSliderButtons() {
    var btns = document.querySelectorAll('.slider-btn');
    for (var i = 0; i < btns.length; i++) {
        (function(btn) {
            var target = btn.getAttribute('data-target');
            var dir = parseInt(btn.getAttribute('data-dir'));
            btn.addEventListener('touchstart', function(e) {
                e.preventDefault();
                sliderBtnPress(target, dir);
            }, {passive: false});
            btn.addEventListener('touchend', function(e) {
                sliderBtnRelease();
            });
            btn.addEventListener('mousedown', function(e) {
                e.preventDefault();
                sliderBtnPress(target, dir);
            });
            btn.addEventListener('mouseup', function(e) {
                sliderBtnRelease();
            });
            btn.addEventListener('mouseleave', function(e) {
                sliderBtnRelease();
            });
        })(btns[i]);
    }
}

document.addEventListener('DOMContentLoaded', init);
