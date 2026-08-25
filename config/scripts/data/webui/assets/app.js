/*!
 * Mindustry WebUI - 公共 JS
 * 提供 API 封装、认证管理、i18n、侧边栏/顶部栏组件、工具函数
 */
(function (global) {
  'use strict';

  var TOKEN_KEY = 'webui_session_token';
  var LANG_KEY = 'webui_lang';
  var DEFAULT_LANG = 'zh_CN';

  /* ============ SVG 矢量图标系统 (Feather Icons 风格) ============ */
  var ICONS = {
    dashboard: '<rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/>',
    players: '<path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>',
    maps: '<polygon points="1 6 1 22 8 18 16 22 23 18 23 2 16 6 8 2 1 6"/><line x1="8" y1="2" x2="8" y2="18"/><line x1="16" y1="6" x2="16" y2="22"/>',
    console: '<polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/>',
    logout: '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/>',
    refresh: '<polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/>',
    ban: '<circle cx="12" cy="12" r="10"/><line x1="4.93" y1="4.93" x2="19.07" y2="19.07"/>',
    unban: '<rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 9.9-1"/>',
    trash: '<polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>',
    upload: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/>',
    play: '<polygon points="5 3 19 12 5 21 5 3"/>',
    globe: '<circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/>',
    server: '<rect x="2" y="2" width="20" height="8" rx="2" ry="2"/><rect x="2" y="14" width="20" height="8" rx="2" ry="2"/><line x1="6" y1="6" x2="6.01" y2="6"/><line x1="6" y1="18" x2="6.01" y2="18"/>',
    memory: '<path d="M6 19v-3"/><path d="M10 19v-3"/><path d="M14 19v-3"/><path d="M18 19v-3"/><path d="M8 11V9"/><path d="M16 11V9"/><path d="M12 11V9"/><path d="M2 15h20"/><path d="M2 7a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v1.1a2 2 0 0 0 0 3.837V17a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-5.1a2 2 0 0 0 0-3.837Z"/>',
    clock: '<circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>',
    users: '<path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>',
    activity: '<polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/>',
    info: '<circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/>',
    gamepad: '<line x1="6" y1="11" x2="10" y2="11"/><line x1="8" y1="9" x2="8" y2="13"/><line x1="15" y1="12" x2="15.01" y2="12"/><line x1="18" y1="10" x2="18.01" y2="10"/><path d="M17.32 5H6.68a4 4 0 0 0-3.978 3.59c-.006.052-.01.101-.017.152C2.604 9.416 2 14.456 2 16a3 3 0 0 0 3 3c1 0 1.5-.5 2-1l1.414-1.414A2 2 0 0 1 9.828 16h4.344a2 2 0 0 1 1.414.586L17 18c.5.5 1 1 2 1a3 3 0 0 0 3-3c0-1.545-.604-6.584-.685-7.258-.007-.05-.011-.1-.017-.152A4 4 0 0 0 17.32 5z"/>',
    sun: '<circle cx="12" cy="12" r="5"/><line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/><line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/>',
    moon: '<path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/>',
    menu: '<line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="18" x2="21" y2="18"/>',
    settings: '<path d="M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>'
  };
  function icon(name, size) {
    size = size || 18;
    var path = ICONS[name] || ICONS.info;
    return '<svg xmlns="http://www.w3.org/2000/svg" width="' + size + '" height="' + size +
      '" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' +
      'stroke-linecap="round" stroke-linejoin="round" class="bi-icon">' + path + '</svg>';
  }

  /* ============ Mindustry 颜色码解析 ============ */
  // Mindustry 颜色名到 hex 的映射（arc.graphics.Colors 全量 + Mindustry Pal 颜色）
  // Pal 颜色在 Mindustry 启动时注册到 Colors map（如 accent/health/heal）
  var COLOR_MAP = {
    // Mindustry Pal 颜色 (运行时注册)
    'accent': '#ffd37f',
    'health': '#ff341c',
    'heal': '#98ffa9',
    'unlaunched': '#8982ed',
    'highlight': '#ffe5a8',
    'stat': '#ffd37f',
    'negstat': '#e55454',
    // arc.graphics.Colors 标准注册色 (37 个)
    'clear': 'transparent',
    'black': '#000000',
    'white': '#ffffff',
    'lightgray': '#bfbfbf',
    'lightgrey': '#bfbfbf', // British alias of lightgray
    'lightishgray': '#c8c8c8',
    'gray': '#7f7f7f',
    'grey': '#7f7f7f', // British alias of gray
    'darkgray': '#3f3f3f',
    'darkgrey': '#3f3f3f', // British alias of darkgray
    'blue': '#4169e1', // = Color.royal in arc
    'navy': '#000080',
    'royal': '#4169e1',
    'slate': '#708090',
    'sky': '#87ceeb',
    'cyan': '#00ffff',
    'teal': '#008080',
    'green': '#38d667', // arc Colors.put 覆盖
    'acid': '#7fff00',
    'lime': '#32cd32',
    'forest': '#228b22',
    'olive': '#6b8e23',
    'yellow': '#ffff00',
    'gold': '#ffd700',
    'goldenrod': '#daa520',
    'orange': '#ffa500',
    'brown': '#8b4513',
    'tan': '#d2b48c',
    'brick': '#b22222',
    'red': '#e55454', // arc Colors.put 覆盖
    'scarlet': '#ff341c',
    'crimson': '#dc143c',
    'coral': '#ff7f50',
    'salmon': '#fa8072',
    'pink': '#ff69b4',
    'magenta': '#ff00ff',
    'purple': '#a020f0',
    'violet': '#ee82ee',
    'maroon': '#b03060',
    // ConsoleColor 枚举颜色名 (脚本系统中使用 [light_yellow] 等颜色码)
    // 这些名称不在 arc Colors 注册表中, 需手动补充
    'light_red': '#fa8072',
    'light_green': '#38d667',
    'light_yellow': '#ffd700',
    'light_blue': '#87ceeb',
    'light_purple': '#ff69b4',
    'light_cyan': '#00ffff',
    'light_gray': '#bfbfbf',
    'bold': '',
    'italic': '',
    'underlined': '',
    'back_default': '',
    'back_red': '#e55454',
    'back_green': '#38d667',
    'back_yellow': '#ffff00',
    'back_blue': '#4169e1'
  };

  function parseMindustryColors(text) {
    if (!text) return '';
    var html = '';
    // 颜色栈: [name]/[#hex] PUSH, [] POP. 栈底为默认色(空=继承CSS).
    // 匹配 Mindustry GlyphLayout.parseColorMarkup 语义
    var DEFAULT_COLOR = '';
    var colorStack = [DEFAULT_COLOR];
    // 其他样式属性 (arc &xx / ANSI 控制, 不进颜色栈)
    var state = { bg: '', bold: false, dim: false, underline: false, italic: false };
    var i = 0;

    // 3字符码映射（&l 前缀亮色）— 匹配 arc Colors.put / Color 字段实际颜色
    var termColors3 = {
      '&lc': '#00ffff', '&lb': '#87ceeb', '&ly': '#ffd700', '&lr': '#fa8072',
      '&lk': '#bfbfbf', '&lw': '#ffffff', '&lg': '#38d667', '&lm': '#ff69b4'
    };
    // 3字符背景色码（&b 前缀）
    var bgColors = {
      '&br': '#e55454', '&bg': '#38d667', '&by': '#ffff00', '&bb': '#4169e1'
    };
    // 2字符码映射（单字母，暗色小写 / 亮色大写）
    var termColors2 = {
      '&k': '#7f7f7f', '&w': '#ffffff', '&r': '#e55454', '&g': '#38d667',
      '&y': '#ffff00', '&b': '#4169e1', '&m': '#a020f0', '&c': '#00ffff',
      '&p': '#a020f0',
      '&K': '#bfbfbf', '&W': '#ffffff', '&R': '#fa8072', '&G': '#38d667',
      '&Y': '#ffd700', '&B': '#87ceeb', '&M': '#ff69b4', '&C': '#00ffff',
      '&P': '#ff69b4'
    };

    // ANSI 颜色码映射（\u001b[XXm）— 与 termColors2 一致
    var ansiColors = {
      30: '#7f7f7f', 31: '#e55454', 32: '#38d667', 33: '#ffff00',
      34: '#4169e1', 35: '#a020f0', 36: '#00ffff', 37: '#ffffff',
      90: '#bfbfbf', 91: '#fa8072', 92: '#38d667', 93: '#ffd700',
      94: '#87ceeb', 95: '#ff69b4', 96: '#00ffff', 97: '#ffffff'
    };

    function currentColor() { return colorStack[colorStack.length - 1]; }
    function pushColor(c) { colorStack.push(c); }
    function popColor() { if (colorStack.length > 1) colorStack.pop(); }
    function setCurrentColor(c) { colorStack[colorStack.length - 1] = c; }
    function resetAll() {
      colorStack = [DEFAULT_COLOR];
      state.bg = ''; state.bold = false; state.dim = false; state.underline = false; state.italic = false;
    }
    var spanOpen = false;
    function hasStyle() {
      return !!currentColor() || state.bg || state.bold || state.dim || state.underline || state.italic;
    }
    function closeSpan() {
      if (spanOpen) {
        html += '</span>';
        spanOpen = false;
      }
    }
    function openSpan() {
      if (!hasStyle()) return;
      var style = '';
      var cc = currentColor();
      if (cc) style += 'color:' + cc + ';';
      if (state.bg) style += 'background-color:' + state.bg + ';';
      if (state.bold) style += 'font-weight:bold;';
      if (state.dim) style += 'opacity:0.7;';
      if (state.underline) style += 'text-decoration:underline;';
      if (state.italic) style += 'font-style:italic;';
      if (style) { html += '<span style="' + style + '">'; spanOpen = true; }
    }
    function applyChange() { closeSpan(); openSpan(); }

    while (i < text.length) {
      // 检查 ANSI 转义码 \u001b[...m
      if (text.charCodeAt(i) === 27 && text[i + 1] === '[') {
        var endIdx = text.indexOf('m', i + 2);
        if (endIdx > 0) {
          var params = text.substring(i + 2, endIdx).split(';');
          for (var pi = 0; pi < params.length; pi++) {
            var code = parseInt(params[pi], 10);
            if (code === 0) { closeSpan(); resetAll(); }
            else if (code === 1) { state.bold = true; applyChange(); }
            else if (code === 2) { state.dim = true; applyChange(); }
            else if (code === 3) { state.italic = true; applyChange(); }
            else if (code === 4) { state.underline = true; applyChange(); }
            else if (ansiColors[code]) { setCurrentColor(ansiColors[code]); applyChange(); }
          }
          i = endIdx + 1;
          continue;
        }
      }

      // 检查终端颜色码 &xx
      if (text[i] === '&') {
        // 先尝试 3 字符码
        if (i + 3 <= text.length) {
          var code3 = text.substring(i, i + 3);
          if (termColors3[code3]) { setCurrentColor(termColors3[code3]); applyChange(); i += 3; continue; }
          if (bgColors[code3]) { state.bg = bgColors[code3]; applyChange(); i += 3; continue; }
          if (code3 === '&bd') { state.bg = ''; applyChange(); i += 3; continue; }
          if (code3 === '&fb') { state.bold = true; applyChange(); i += 3; continue; }
          if (code3 === '&fd') { state.dim = true; applyChange(); i += 3; continue; }
          if (code3 === '&fu') { state.underline = true; applyChange(); i += 3; continue; }
          if (code3 === '&fi') { state.italic = true; applyChange(); i += 3; continue; }
          if (code3 === '&fr') { closeSpan(); resetAll(); i += 3; continue; }
        }
        // 再尝试 2 字符码
        if (i + 2 <= text.length) {
          var code2 = text.substring(i, i + 2);
          if (termColors2[code2]) { setCurrentColor(termColors2[code2]); applyChange(); i += 2; continue; }
        }
        // 都不匹配，& 作为普通字符
      }

      // 检查 Mindustry 方括号颜色码: [name], [#hex], [], [[
      // 与终端 mindustryColorToArc 行为对齐: 非颜色码格式的 [...] 保留字面文本
      if (text[i] === '[') {
        // [[ → 渲染字面量 [
        if (text[i + 1] === '[') {
          html += escapeHtmlChar('[');
          i += 2;
          continue;
        }
        var end = text.indexOf(']', i);
        if (end > i) {
          var bracketCode = text.substring(i + 1, end);
          if (bracketCode === '') {
            // [] → POP 颜色栈 (回到前色, 栈空时留在默认色)
            popColor();
            applyChange();
            i = end + 1;
            continue;
          } else if (bracketCode[0] === '#' && /^[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$/.test(bracketCode.substring(1))) {
            // [#hex] → PUSH hex 颜色入栈 (仅 6/8 位十六进制合法)
            pushColor(bracketCode);
            applyChange();
            i = end + 1;
            continue;
          } else if (/^[a-zA-Z_]+$/.test(bracketCode)) {
            // [name] → PUSH 命名颜色入栈 (仅纯字母/下划线才作为颜色名解析)
            var color = COLOR_MAP[bracketCode.toLowerCase()];
            if (!color) {
              // 尝试去掉下划线变体（如 light_gray -> lightgray）
              color = COLOR_MAP[bracketCode.toLowerCase().replace(/_/g, '')];
            }
            if (color) {
              pushColor(color);
              applyChange();
            }
            // 未知颜色名 → 静默跳过 (不修改栈, 不输出字面文本)
            i = end + 1;
            continue;
          }
          // 非颜色码格式 (含 | < > / 中文等, 如 usage 中的 [current|last|<局号>])
          // 保留字面 [ 并继续处理后续字符, ] 也会作为普通字符输出
        }
        // 没有找到 ] 或不是合法颜色码格式, [ 作为普通字符输出
        html += escapeHtmlChar('[');
        i++;
        continue;
      }

      html += escapeHtmlChar(text[i]);
      i++;
    }
    closeSpan();
    return html;
  }

  function escapeHtmlChar(c) {
    switch (c) {
      case '<': return '&lt;';
      case '>': return '&gt;';
      case '&': return '&amp;';
      case '"': return '&quot;';
      default: return c;
    }
  }

  /* ============ 认证管理 ============ */
  // 从 cookie 读取 session token（后端登录时通过 Set-Cookie 设置）
  function getTokenFromCookie() {
    var match = document.cookie.match(/(?:^|;\s*)webui_session=([^;]+)/);
    return match ? decodeURIComponent(match[1]) : '';
  }
  function getToken() {
    // 优先 sessionStorage，其次 cookie（关闭标签页再打开时恢复登录态）
    return sessionStorage.getItem(TOKEN_KEY) || getTokenFromCookie() || '';
  }
  function setToken(token) {
    sessionStorage.setItem(TOKEN_KEY, token);
  }
  function clearToken() {
    sessionStorage.removeItem(TOKEN_KEY);
    // 后端会在 /api/logout 和 /api/config/token 响应中清除 cookie，
    // 这里仅作为兜底（cookie 非 HttpOnly，前端可清除）
    document.cookie = 'webui_session=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT';
  }
  function isLoggedIn() {
    return !!getToken();
  }
  function logout() {
    apiPost('/api/logout').catch(function () {}).finally(function () {
      clearToken();
      location.href = 'login.html';
    });
  }

  /* ============ 主题管理 ============ */
  var THEME_KEY = 'webui_theme';

  function getTheme() {
    return localStorage.getItem(THEME_KEY) || 'light';
  }

  function setTheme(theme) {
    localStorage.setItem(THEME_KEY, theme);
    applyTheme(theme);
  }

  function applyTheme(theme) {
    var html = document.documentElement;
    html.classList.remove('light', 'dark');
    html.classList.add(theme);
  }

  function toggleTheme() {
    var current = getTheme();
    var next = current === 'light' ? 'dark' : 'light';
    setTheme(next);
  }

  // 页面加载时立即应用主题（防止闪烁）
  applyTheme(getTheme());

  // ==================== 主题自定义 ====================
  var THEME_CUSTOM_KEY = 'webui_theme_custom';

  function getCustomTheme() {
    try {
      return JSON.parse(localStorage.getItem(THEME_CUSTOM_KEY) || '{}');
    } catch (e) { return {}; }
  }

  function saveCustomTheme(theme) {
    localStorage.setItem(THEME_CUSTOM_KEY, JSON.stringify(theme));
  }

  function applyCustomTheme() {
    var theme = getCustomTheme();
    var root = document.documentElement;
    // 主色
    if (theme.primary) {
      root.style.setProperty('--webui-primary', theme.primary);
    }
    // 卡片透明度 (0-100)
    if (theme.cardOpacity !== undefined && theme.cardOpacity !== null) {
      var isDark = document.documentElement.classList.contains('dark');
      var baseR = isDark ? 35 : 255;
      var baseG = isDark ? 38 : 255;
      var baseB = isDark ? 50 : 255;
      var alpha = theme.cardOpacity / 100;
      root.style.setProperty('--card-bg', 'rgba(' + baseR + ',' + baseG + ',' + baseB + ',' + alpha + ')');
    }
    // 侧边栏透明度
    if (theme.sidebarOpacity !== undefined && theme.sidebarOpacity !== null) {
      var isDark2 = document.documentElement.classList.contains('dark');
      var sR = isDark2 ? 20 : 30;
      var sG = isDark2 ? 22 : 33;
      var sB = isDark2 ? 30 : 40;
      var sAlpha = theme.sidebarOpacity / 100;
      root.style.setProperty('--sidebar-bg', 'rgba(' + sR + ',' + sG + ',' + sB + ',' + sAlpha + ')');
    }
    // 毛玻璃强度 (0-20)
    if (theme.blurStrength !== undefined && theme.blurStrength !== null) {
      root.style.setProperty('--glass-blur', 'blur(' + theme.blurStrength + 'px) saturate(150%)');
    }
  }

  // 页面加载时应用自定义主题
  applyCustomTheme();

  // 主题切换时重新应用自定义主题（因为 dark/light 切换后 rgba 基色不同）
  var _originalApplyTheme = applyTheme;
  applyTheme = function(theme) {
    _originalApplyTheme(theme);
    applyCustomTheme();
  };

  /* ============ API 封装 ============ */
  /* 后端业务错误消息 -> i18n 键映射(未命中的原样显示) */
  var ERROR_TRANSLATIONS = {
    'Invalid JSON': 'webui.error.invalidJson',
    'Invalid username': 'webui.error.invalidUsername',
    'Username already exists': 'webui.error.usernameExists',
    'Password too short (min 6)': 'webui.error.passwordTooShort',
    'User not found': 'webui.error.userNotFound',
    'Cannot modify admin': 'webui.error.cannotModifyAdmin',
    'Cannot delete guest': 'webui.error.cannotDeleteGuest',
    'File not found': 'webui.error.fileNotFound',
    'Invalid path': 'webui.error.invalidPath',
    'Invalid filename': 'webui.error.invalidFilename',
    'No file uploaded': 'webui.error.noFile',
    'Expected multipart/form-data': 'webui.error.badUpload',
    'Name required': 'webui.error.nameRequired',
    'Invalid username or password': 'webui.error.badCredentials',
    'Too many failures, account locked for 15 minutes': 'webui.error.locked',
    'Guest user is disabled, login please': 'webui.error.guestDisabled',
    /* 后端中文业务消息(announcement/settings 等) */
    '标题和内容不能为空': 'webui.announcement.errorEmpty',
    '公告不存在': 'webui.announcement.notFound',
    '保存失败': 'webui.common.saveFailed',
    '删除失败': 'webui.common.deleteFailed',
    '生成颜色数据失败': 'webui.settings.colorGenFailed'
  };
  function localizeError(msg) {
    if (!msg) return msg;
    var key = ERROR_TRANSLATIONS[msg];
    if (key) return WebUI.t(key, msg);
    // 前缀匹配(如 "Upload failed: xxx")
    for (var k in ERROR_TRANSLATIONS) {
      if (msg.indexOf(k) === 0) {
        return WebUI.t(ERROR_TRANSLATIONS[k], k) + msg.substring(k.length);
      }
    }
    return msg;
  }

  function request(method, url, body) {
    var headers = { 'Accept': 'application/json' };
    var token = getToken();
    if (token) headers['Authorization'] = 'Bearer ' + token;
    var opts = { method: method, headers: headers };
    if (body !== undefined && body !== null) {
      headers['Content-Type'] = 'application/json';
      opts.body = typeof body === 'string' ? body : JSON.stringify(body);
    }
    return fetch(url, opts).then(function (resp) {
      if (resp.status === 401) {
        clearToken();
        if (!location.pathname.endsWith('login.html') && !location.pathname.endsWith('index.html')) {
          location.href = 'login.html';
          // 返回永不 resolve 的 promise，阻止后续 catch 执行错误显示
          return new Promise(function () {});
        }
        var err = new Error('未授权或会话已过期');
        err.code = 401;
        throw err;
      }
      if (resp.status === 403) {
        // 无权限: 不清 token 不跳页, 让页面显示"无权限"错误
        var e403 = new Error(WebUI.t('webui.login.forbidden', '你没有权限执行此操作'));
        e403.code = 403;
        throw e403;
      }
      var contentType = resp.headers.get('Content-Type') || '';
      if (contentType.indexOf('application/json') !== -1) {
        return resp.json().then(function (data) {
          if (data && typeof data === 'object' && 'code' in data) {
            if (data.code !== 0 && data.code !== 200) {
              var e = new Error(localizeError(data.msg) || ('请求失败 code=' + data.code));
              e.code = data.code;
              e.data = data.data;
              throw e;
            }
            return data.data !== undefined ? data.data : data;
          }
          return data;
        });
      }
      return resp.text();
    }).catch(function (err) {
      // 网络层错误(Failed to fetch 等): 本地化提示; 带 code 的业务错误原样抛
      if (err && err.code) throw err;
      var netErr = new Error(WebUI.t('webui.login.networkError', '网络错误，请重试'));
      netErr.code = -1;
      throw netErr;
    });
  }
  function apiGet(url) { return request('GET', url); }
  function apiPost(url, body) { return request('POST', url, body || {}); }
  function apiPut(url, body) { return request('PUT', url, body || {}); }
  function apiDelete(url) { return request('DELETE', url); }

  /* ============ 语言管理 ============ */
  function getLang() {
    return localStorage.getItem(LANG_KEY) || DEFAULT_LANG;
  }
  function setLang(lang) {
    localStorage.setItem(LANG_KEY, lang || DEFAULT_LANG);
  }
  var _i18nTable = null;
  function loadI18n(lang) {
    return fetch('/api/i18n?lang=' + encodeURIComponent(lang), { headers: { 'Accept': 'application/json' } })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        var table = (data && data.data) ? data.data : data;
        _i18nTable = table || {};
        applyI18n();
        return _i18nTable;
      })
      .catch(function () {
        _i18nTable = {};
        applyI18n();
        return _i18nTable;
      });
  }
  function t(key, fallback) {
    if (_i18nTable && _i18nTable[key]) return _i18nTable[key];
    return fallback !== undefined ? fallback : key;
  }
  function applyI18n() {
    var nodes = document.querySelectorAll('[data-i18n]');
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      var key = el.getAttribute('data-i18n');
      var val = t(key, null);
      if (val !== null) el.textContent = val;
    }
    var phNodes = document.querySelectorAll('[data-i18n-placeholder]');
    for (var j = 0; j < phNodes.length; j++) {
      var p = phNodes[j];
      var pkey = p.getAttribute('data-i18n-placeholder');
      var pval = t(pkey, null);
      if (pval !== null) p.setAttribute('placeholder', pval);
    }
    var titleNodes = document.querySelectorAll('[data-i18n-title]');
    for (var t0 = 0; t0 < titleNodes.length; t0++) {
      var te = titleNodes[t0];
      var tv = t(te.getAttribute('data-i18n-title'), null);
      if (tv !== null) te.setAttribute('title', tv);
    }
    var altNodes = document.querySelectorAll('[data-i18n-alt]');
    for (var a0 = 0; a0 < altNodes.length; a0++) {
      var ae = altNodes[a0];
      var av = t(ae.getAttribute('data-i18n-alt'), null);
      if (av !== null) ae.setAttribute('alt', av);
    }
  }

  /* ============ 侧边栏 / 顶部栏组件 ============ */
  // perm: 所需权限节点, 无权限的用户不显示该入口 (role=2 管理员全部可见)
  // requireLogin: 需登录用户(游客 role=1 不显示)
  var NAV_ITEMS = [
    { page: 'dashboard', href: 'dashboard.html', icon: 'dashboard', i18n: 'webui.nav.dashboard', text: '仪表盘', perm: 'webui.api.status' },
    { page: 'players', href: 'players.html', icon: 'players', i18n: 'webui.nav.players', text: '玩家管理', perm: 'webui.api.players' },
    { page: 'maps', href: 'maps.html', icon: 'maps', i18n: 'webui.nav.maps', text: '地图存档', perm: 'webui.api.maps' },
    { page: 'console', href: 'console.html', icon: 'console', i18n: 'webui.nav.console', text: '控制台', perm: 'webui.api.console' },
    { page: 'announcement', href: 'announcement.html', icon: 'info', i18n: 'webui.nav.announcement', text: '公告管理', perm: 'webui.api.announcements' },
    { page: 'users', href: 'users.html', icon: 'users', i18n: 'webui.nav.users', text: '用户管理', perm: 'webui.api.users' },
    { page: 'settings', href: 'settings.html', icon: 'settings', i18n: 'webui.settings.title', text: '设置', perm: 'webui.api.settings' }
  ];

  // 当前用户信息(来自 /api/me): {username, role, permissions}
  var ME = null;
  function hasPerm(p) {
    if (!ME) return false;
    if (ME.role === 2) return true; // 管理员全部权限
    if (Array.isArray(p)) return p.some(function (x) { return (ME.permissions || []).indexOf(x) >= 0; });
    return (ME.permissions || []).indexOf(p) >= 0;
  }
  function loadMe() {
    return apiGet('/api/me').then(function (me) { ME = me; return me; });
  }

  function sidebarHtml(activePage, isOffcanvas) {
    var items = NAV_ITEMS.filter(function (it) {
      if (it.perm && !hasPerm(it.perm)) return false;
      if (it.requireLogin && ME && ME.role === 1) return false; // 游客不显示登录用户页面
      return true;
    }).map(function (it) {
      var active = it.page === activePage ? ' active' : '';
      // data-i18n 放在文本 span 上(applyI18n 用 textContent 替换, 放父元素会清掉图标子节点)
      return '<a href="' + it.href + '" class="nav-link' + active + '">' +
        '<span class="nav-icon">' + icon(it.icon) + '</span>' +
        '<span data-i18n="' + it.i18n + '">' + it.text + '</span></a>';
    }).join('');
    var brand = '<div class="sidebar-brand">' + icon('gamepad', 22) + '<span>Mindustry</span></div>';
    return brand + '<nav class="sidebar-nav">' + items + '</nav>';
  }

  function renderSidebar(activePage) {
    var pc = document.getElementById('appSidebar');
    if (pc) pc.innerHTML = sidebarHtml(activePage, false);
    var off = document.getElementById('appOffcanvasBody');
    if (off) off.innerHTML = sidebarHtml(activePage, true);
    bindLogout();
  }

  // 语言名称显示映射(常见语言的中文名,资源站可能只返回 code)
  var LANG_NAMES = {
    'zh_CN': '简体中文', 'zh_TW': '繁體中文', 'en': 'English',
    'ja': '日本語', 'ko': '한국어', 'ru': 'Русский', 'fr': 'Français',
    'de': 'Deutsch', 'es': 'Español', 'pt': 'Português', 'it': 'Italiano'
  };
  function langDisplayName(code) {
    return LANG_NAMES[code] || code;
  }

  function loadLangOptions() {
    WebUI.apiGet('/api/i18n/langs').then(function (list) {
      var dropdown = document.querySelector('.dropdown-menu.dropdown-menu-end');
      if (!dropdown || !list || !list.length) return;
      var html = '';
      for (var i = 0; i < list.length; i++) {
        var item = list[i];
        var code = item.code || item;
        var name = item.name || langDisplayName(code);
        html += '<li><a class="dropdown-item lang-option" data-lang="' + WebUI.escapeHtml(code) + '" href="#">' +
                WebUI.escapeHtml(name) + '</a></li>';
      }
      dropdown.innerHTML = html;
      // 重新绑定点击事件
      var langItems = dropdown.querySelectorAll('.lang-option');
      for (var i = 0; i < langItems.length; i++) {
        langItems[i].addEventListener('click', function (e) {
          e.preventDefault();
          var lang = e.currentTarget.getAttribute('data-lang');
          setLang(lang);
          loadI18n(lang);
        });
      }
    }).catch(function () {});
  }

  function topbarHtml(titleKey, titleText) {
    var themeIcon = getTheme() === 'dark' ? 'sun' : 'moon';
    return '<button class="btn btn-link btn-sm d-lg-none" id="menuToggle" type="button" ' +
      'data-bs-toggle="offcanvas" data-bs-target="#appOffcanvas" aria-label="菜单">' + icon('menu', 20) + '</button>' +
      '<h1 class="topbar-title" data-i18n="' + titleKey + '">' + titleText + '</h1>' +
      '<div class="topbar-actions">' +
        '<button class="btn btn-link btn-sm theme-toggle" id="themeToggle" title="切换主题">' + icon(themeIcon, 18) + '</button>' +
        '<div class="dropdown">' +
          '<button class="btn btn-link btn-sm dropdown-toggle" data-bs-toggle="dropdown">' + icon('globe', 18) + '</button>' +
          '<ul class="dropdown-menu dropdown-menu-end" id="langMenu">' +
            '<li><span class="dropdown-item-text text-muted small">Loading...</span></li>' +
          '</ul>' +
        '</div>' +
        '<button class="btn btn-link btn-sm" id="logoutBtn">' + icon('logout', 18) + '</button>' +
      '</div>';
  }

  function renderTopbar(titleKey, titleText) {
    var el = document.getElementById('appTopbar');
    if (el) el.innerHTML = topbarHtml(titleKey, titleText);
    var topLogout = document.getElementById('logoutBtn');
    if (topLogout) topLogout.addEventListener('click', logout);
    var themeToggle = document.getElementById('themeToggle');
    if (themeToggle) {
      themeToggle.addEventListener('click', function () {
        toggleTheme();
        // 更新图标
        var newIcon = getTheme() === 'dark' ? 'sun' : 'moon';
        themeToggle.innerHTML = icon(newIcon, 18);
      });
    }
    var langItems = document.querySelectorAll('.lang-option');
    for (var i = 0; i < langItems.length; i++) {
      langItems[i].addEventListener('click', function (e) {
        e.preventDefault();
        var lang = e.currentTarget.getAttribute('data-lang');
        setLang(lang);
        loadI18n(lang);
      });
    }
  }

  function bindLogout() {
    // 退出入口统一在右上角 topbar(logoutBtn), 侧边栏不再生成退出按钮
  }

  /* ============ 页面初始化 ============ */
  function initPage(pageName, titleKey, titleText) {
    var hasToken = isLoggedIn();
    // 游客模式: 无 token 仅允许仪表盘(游客无需登录自动降级), 其他页面跳登录
    if (!hasToken && pageName !== 'dashboard') {
      location.href = 'login.html';
      return Promise.reject(new Error('未登录'));
    }
    // 加载当前用户信息(权限), 无 token 且游客禁用时 /api/me 返回 401 由 request() 跳登录
    return loadMe().catch(function (err) {
      if (err && err.code === 401) {
        location.href = 'login.html';
        return new Promise(function () {});
      }
      throw err;
    }).then(function () {
      renderSidebar(pageName);
      renderTopbar(titleKey, titleText || titleKey);
    // 自动替换 [data-icon] 元素为 SVG 图标
    var iconNodes = document.querySelectorAll('[data-icon]');
    for (var i = 0; i < iconNodes.length; i++) {
      iconNodes[i].innerHTML = icon(iconNodes[i].getAttribute('data-icon'));
    }
    return loadI18n(getLang()).then(function () {
      // 动态加载可用语言列表
      loadLangOptions();
      // 加载自定义背景图片
      WebUI.apiGet('/api/background').then(function (res) {
        if (res && res.enabled && res.url) {
          document.body.style.backgroundImage = 'url("' + res.url + '")';
          document.body.style.backgroundSize = 'cover';
          document.body.style.backgroundPosition = 'center';
          document.body.style.backgroundAttachment = 'fixed';
          document.body.style.backgroundRepeat = 'no-repeat';
          document.body.classList.remove('has-gradient-bg');
        } else {
          // 无背景图时使用渐变光斑
          document.body.classList.add('has-gradient-bg');
        }
      }).catch(function () {
        document.body.classList.add('has-gradient-bg');
      });
      // 页面内容入场动效
      var content = document.querySelector('.app-content') || document.querySelector('.login-card');
      if (content) {
        content.style.animation = 'fadeInUp 0.3s ease-out';
      }
    });
  });
  }

  /* ============ 工具函数 ============ */
  function escapeHtml(str) {
    if (str === null || str === undefined) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }
  function formatTime(iso) {
    if (!iso) return '-';
    var d = new Date(iso);
    if (isNaN(d.getTime())) return String(iso);
    var pad = function (n) { return n < 10 ? '0' + n : '' + n; };
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
      ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
  }
  function formatUptime(seconds) {
    seconds = Number(seconds) || 0;
    var d = Math.floor(seconds / 86400);
    var h = Math.floor((seconds % 86400) / 3600);
    var m = Math.floor((seconds % 3600) / 60);
    var s = Math.floor(seconds % 60);
    if (d > 0) return d + '天 ' + h + '时 ' + m + '分';
    if (h > 0) return h + '时 ' + m + '分 ' + s + '秒';
    if (m > 0) return m + '分 ' + s + '秒';
    return s + '秒';
  }
  // 防抖: 延迟执行, 多次调用只执行最后一次
  function debounce(fn, delay) {
    var timer = null;
    return function () {
      var ctx = this, args = arguments;
      if (timer) clearTimeout(timer);
      timer = setTimeout(function () { fn.apply(ctx, args); }, delay);
    };
  }
  // 复制文本到剪贴板, 返回 Promise
  function copyToClipboard(text) {
    if (navigator.clipboard) {
      return navigator.clipboard.writeText(text);
    }
    return new Promise(function (resolve, reject) {
      var ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      try { document.execCommand('copy'); resolve(); }
      catch (e) { reject(e); }
      finally { document.body.removeChild(ta); }
    });
  }
  // 轻量 toast 提示
  function toast(message, type) {
    type = type || 'info';
    var container = document.getElementById('toastContainer');
    if (!container) {
      container = document.createElement('div');
      container.id = 'toastContainer';
      container.className = 'toast-container position-fixed top-0 end-0 p-3';
      container.style.zIndex = '1080';
      document.body.appendChild(container);
    }
    var id = 't' + Date.now() + Math.random();
    var bg = type === 'error' ? 'bg-danger' : type === 'success' ? 'bg-success' : 'bg-primary';
    var html = '<div id="' + id + '" class="toast align-items-center text-white ' + bg +
      ' border-0" role="alert"><div class="d-flex"><div class="toast-body">' +
      escapeHtml(message) + '</div><button type="button" class="btn-close btn-close-white me-2 m-auto" ' +
      'data-bs-dismiss="toast"></button></div></div>';
    container.insertAdjacentHTML('beforeend', html);
    var el = document.getElementById(id);
    var inst = new global.bootstrap.Toast(el, { delay: 3000 });
    inst.show();
    el.addEventListener('hidden.bs.toast', function () { el.remove(); });
  }

  /* ============ 导出 ============ */
  global.WebUI = {
    getToken: getToken,
    setToken: setToken,
    clearToken: clearToken,
    isLoggedIn: isLoggedIn,
    logout: logout,
    apiGet: apiGet,
    apiPost: apiPost,
    apiPut: apiPut,
    apiDelete: apiDelete,
    getLang: getLang,
    setLang: setLang,
    loadI18n: loadI18n,
    applyI18n: applyI18n,
    t: t,
    icon: icon,
    getTheme: getTheme,
    setTheme: setTheme,
    toggleTheme: toggleTheme,
    getCustomTheme: getCustomTheme,
    saveCustomTheme: saveCustomTheme,
    applyCustomTheme: applyCustomTheme,
    initPage: initPage,
    renderSidebar: renderSidebar,
    hasPerm: hasPerm,
    renderTopbar: renderTopbar,
    sidebarHtml: sidebarHtml,
    topbarHtml: topbarHtml,
    escapeHtml: escapeHtml,
    formatTime: formatTime,
    formatUptime: formatUptime,
    debounce: debounce,
    copyToClipboard: copyToClipboard,
    toast: toast,
    parseMindustryColors: parseMindustryColors
  };
})(window);
