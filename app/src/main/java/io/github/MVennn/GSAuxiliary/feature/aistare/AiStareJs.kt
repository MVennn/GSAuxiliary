package io.github.MVennn.GSAuxiliary.feature.aistare

/**
 * 注入价格页 H5 的脚本（常量）。
 *
 * 设计原则（方案文档 §2）：**脚本越薄越好** —— 计算全在原生（[QuickConfigEngine]），
 * 这里只做「找按钮 → 插按钮 → 弹方案层 → 回填两个输入框」，宿主 H5 改版时
 * 摔坏的面积最小。
 *
 * 与宿主的分界（一条都不越过）：
 *  - 「重置」「保存」的行为、事件、监听**一个字节不动**；
 *  - 只在底部操作条**插入**第三个子节点并把三个子节点统一 `flex:1 1 0`（§3.1）；
 *  - 「配置」的字号 / 圆角 / 内边距从「保存」的 computedStyle 克隆（跟着宿主皮肤走），
 *    只覆盖底色（[AiStareQuickConfigTargets.BUTTON_COLOR]）与文字色；
 *  - 自建桥 `gsaux`（addJavascriptInterface），**不进宿主命令漏斗** ——
 *    宿主的 `showCommandError` 永远看不到我们的命令（§10-3 实测不禁用自挂桥）。
 *
 * 页面是**整页导航**（§10-7 实测），每次进价格页都会重新走 onPageFinished → 注入；
 * `MutationObserver` + 500ms 自检只做兜底（宿主哪天改成 SPA 也不至于掉线）。
 */
internal object AiStareJs {

    /**
     * 页面「最新价:24.32」的抓取正则（方向校验的基准价，纯 JS 零成本）。
     * 注意这里是**字符串形态**（注入时替换进 `/…/` 字面量），`\\s` 在 JS 里是 `\s`。
     */
    private const val PRICE_REGEX = "最新价[:：]\\s*([0-9]+(?:\\.[0-9]+)?)"

    // ---- 弹层与提示文案（写死中文）----
    // 金太阳的价格页本身只有中文界面，弹层跟着宿主页面走中文才自然；
    // 模块设置页的中英切换只管模块自己的界面（目标进程也拿不到模块资源）。
    private const val MSG_UNAVAILABLE = "暂时不可用"
    private const val MSG_NO_SCHEME = "还没有方案"
    private const val MSG_PICK_SCHEME = "选择方案"
    private const val MSG_MANAGE = "管理方案…"
    private const val MSG_UP_WRONG = "涨到应高于最新价"
    private const val MSG_DOWN_WRONG = "跌到应低于最新价"

    /** 页面行标记（找输入框用的上下文文本，真机 DOM 实测） */
    private const val UP_MARK = "涨到"
    private const val DOWN_MARK = "跌到"

    /**
     * 注入脚本本体。经 `WebView.evaluateJavascript` 执行，幂等
     * （重复注入直接短路，页面反复进出不会叠按钮）。
     */
    const val INJECT: String = """(function() {
  'use strict';
  var NS = window.__gsaux = window.__gsaux || {};
  if (NS.__installed) { NS.install(); return; }
  NS.__installed = true;
  NS.enabled = false;
  NS.schemes = [];
  NS.btn = null;
  NS.layer = null;
  NS.checkTimer = null;
  NS.observer = null;
  NS.interval = null;

  var BTN_TEXT = '__BTN_TEXT__';
  var BTN_COLOR = '__BTN_COLOR__';
  var FLEX = '1 1 0';

  // ---------- 定位：底部操作条（文本 + 结构双判据，§8） ----------
  function bottomQuarter(el) {
    var r = el.getBoundingClientRect();
    return r.top > window.innerHeight * 0.75;
  }
  function isLeaf(el) { return el.childElementCount === 0; }
  function leafByText(root, text) {
    var all = root.querySelectorAll('div,span,button,a,label');
    for (var i = all.length - 1; i >= 0; i--) {
      var el = all[i];
      if (isLeaf(el) && el.textContent.trim() === text && bottomQuarter(el)) return el;
    }
    return null;
  }
  function clickableOf(el) {
    var n = el;
    while (n && n !== document.body) {
      var cls = (typeof n.className === 'string') ? n.className : '';
      if (cls.indexOf('clk') >= 0) return n;
      var cs = window.getComputedStyle(n);
      if (cs.cursor === 'pointer' && n !== el) return n;
      n = n.parentElement;
    }
    return el.parentElement || el;
  }
  function findBar() {
    var saveLeaf = leafByText(document, '__SAVE_TEXT__');
    if (!saveLeaf) return null;
    var saveBtn = clickableOf(saveLeaf);
    var bar = saveBtn.parentElement;
    if (!bar || bar === document.body) return null;
    return { bar: bar, save: saveBtn, saveLeaf: saveLeaf };
  }

  // ---------- 注入：三等分 + 克隆「保存」改「配置」 ----------
  NS.install = function() {
    if (!NS.enabled) return false;
    if (NS.btn && document.contains(NS.btn)) return true;
    var found = findBar();
    if (!found) return false;
    var bar = found.bar;
    bar.style.display = 'flex';
    for (var i = 0; i < bar.children.length; i++) {
      bar.children[i].style.flex = FLEX;
      bar.children[i].style.minWidth = '0';
    }
    var btn = found.save.cloneNode(true);
    btn.removeAttribute('id');
    var leaf = btn;
    var queue = [btn];
    var textDone = false;
    while (queue.length) {
      var cur = queue.shift();
      if (isLeaf(cur)) {
        if (!textDone && cur.textContent.trim() === '__SAVE_TEXT__') {
          cur.textContent = BTN_TEXT; textDone = true;
        }
      } else {
        for (var j = 0; j < cur.children.length; j++) queue.push(cur.children[j]);
      }
    }
    if (!textDone) btn.textContent = BTN_TEXT;
    // 样式全部随克隆的宿主 class 走；只覆盖底色与文字色（§3.1，改版跟皮肤）
    btn.style.backgroundColor = BTN_COLOR;
    btn.style.color = '#FFFFFF';
    btn.addEventListener('click', function(e) {
      e.stopPropagation();
      NS.onConfigClick();
    });
    found.save.insertAdjacentElement('afterend', btn);
    NS.btn = btn;
    return true;
  };

  // ---------- 点击「配置」 ----------
  NS.onConfigClick = function() {
    if (!window.gsaux) { NS.toast('__MSG_UNAVAILABLE__'); return; }
    if (!NS.schemes.length) { NS.toast('__MSG_NO_SCHEME__'); return; }
    if (NS.schemes.length === 1) { NS.apply(NS.schemes[0].id); return; }
    NS.showLayer();
  };
  NS.apply = function(id) {
    try { window.gsaux.apply(String(id)); }
    catch (e) { NS.toast('__MSG_UNAVAILABLE__'); }
  };

  // ---------- 方案选择层（自绘，从底部升起） ----------
  NS.showLayer = function() {
    NS.closeLayer();
    var mask = document.createElement('div');
    mask.style.cssText = 'position:fixed;left:0;top:0;right:0;bottom:0;' +
      'background:rgba(0,0,0,0.45);z-index:2147483000;';
    var sheet = document.createElement('div');
    sheet.style.cssText = 'position:fixed;left:0;right:0;bottom:0;background:#212634;color:#fff;' +
      'z-index:2147483001;border-radius:16px 16px 0 0;padding:16px 16px 28px;' +
      'transform:translateY(100%);transition:transform 0.25s ease;';
    var title = document.createElement('div');
    title.textContent = '__MSG_PICK_SCHEME__';
    title.style.cssText = 'font-size:13px;color:rgba(255,255,255,0.55);padding:4px 8px 10px;';
    sheet.appendChild(title);
    for (var i = 0; i < NS.schemes.length; i++) {
      sheet.appendChild(rowOf(NS.schemes[i]));
    }
    var manage = document.createElement('div');
    manage.textContent = '__MSG_MANAGE__';
    manage.style.cssText = 'text-align:center;padding:14px 8px 2px;font-size:15px;' +
      'color:__BTN_COLOR__;cursor:pointer;';
    manage.addEventListener('click', function(e) {
      e.stopPropagation();
      NS.closeLayer();
      try { window.gsaux.manage(); } catch (err) {}
    });
    sheet.appendChild(manage);
    mask.appendChild(sheet);
    mask.addEventListener('click', function() { NS.closeLayer(); });
    document.body.appendChild(mask);
    NS.layer = mask;
    requestAnimationFrame(function() { sheet.style.transform = 'translateY(0)'; });

    function rowOf(s) {
      var row = document.createElement('div');
      row.style.cssText = 'padding:12px 8px;cursor:pointer;' +
        'border-bottom:1px solid rgba(255,255,255,0.08);';
      var name = document.createElement('div');
      name.textContent = s.name;
      name.style.cssText = 'font-size:16px;font-weight:600;';
      row.appendChild(name);
      if (s.preview) {
        var pv = document.createElement('div');
        pv.textContent = s.preview;
        pv.style.cssText = 'font-size:12px;color:rgba(255,255,255,0.45);margin-top:3px;';
        row.appendChild(pv);
      }
      row.addEventListener('click', function(e) {
        e.stopPropagation();
        NS.closeLayer();
        NS.apply(s.id);
      });
      return row;
    }
  };
  NS.closeLayer = function() {
    if (NS.layer) { NS.layer.remove(); NS.layer = null; }
  };

  // ---------- 回填（原生算完调回来） ----------
  NS.fill = function(up, down) {
    var ui = findInputs();
    setInput(ui.up, up);
    setInput(ui.down, down);
    NS.validate();
  };
  function setInput(inp, v) {
    if (!inp) return;
    inp.value = v;
    inp.dispatchEvent(new Event('input', { bubbles: true }));
    inp.dispatchEvent(new Event('change', { bubbles: true }));
    inp.dispatchEvent(new Event('blur', { bubbles: true }));
  }

  // ---------- 方向校验（§3.3：填反了描边变红并提示；纯展示，不拦保存） ----------
  function findInputs() {
    var inputs = document.querySelectorAll('input');
    var up = null, down = null;
    for (var i = 0; i < inputs.length; i++) {
      var t = (inputs[i].closest('div') || inputs[i]).textContent || '';
      var ctx = inputs[i].parentElement;
      for (var k = 0; k < 4 && ctx; k++) { t += ' ' + (ctx.textContent || ''); ctx = ctx.parentElement; }
      if (up === null && t.indexOf('__UP_MARK__') >= 0) up = inputs[i];
      else if (down === null && t.indexOf('__DOWN_MARK__') >= 0) down = inputs[i];
    }
    if (up === null && inputs.length >= 2) { up = inputs[0]; down = inputs[1]; }
    return { up: up, down: down };
  }
  function pagePrice() {
    var m = (document.body.innerText || '').match(/__PRICE_REGEX__/);
    return m ? parseFloat(m[1]) : NaN;
  }
  NS.validate = function() {
    var price = pagePrice();
    if (!isFinite(price)) return;
    var ui = findInputs();
    check(ui.up, parseFloat(upVal(ui.up)), price, '__MSG_UP_WRONG__', true);
    check(ui.down, parseFloat(downVal(ui.down)), price, '__MSG_DOWN_WRONG__', false);

    function upVal(i) { return i ? i.value : ''; }
    function downVal(i) { return i ? i.value : ''; }
    function check(inp, v, base, msg, mustHigher) {
      if (!inp || !isFinite(v)) return;
      var wrong = mustHigher ? (v <= base) : (v >= base);
      inp.style.borderColor = wrong ? BTN_COLOR : '';
      inp.style.outline = wrong ? ('1px solid ' + BTN_COLOR) : '';
      var tipId = 'gsaux-direction-tip';
      var old = inp.parentElement.querySelector('#' + tipId);
      if (old) old.remove();
      if (wrong) {
        var tip = document.createElement('div');
        tip.id = tipId;
        tip.textContent = msg;
        tip.style.cssText = 'font-size:11px;color:__BTN_COLOR__;margin-top:2px;';
        inp.parentElement.appendChild(tip);
      }
    }
  };

  // ---------- 轻提示（页面顶部，2 秒淡出） ----------
  NS.toast = function(text) {
    var t = document.createElement('div');
    t.textContent = text;
    t.style.cssText = 'position:fixed;left:50%;top:64px;transform:translateX(-50%);' +
      'background:rgba(0,0,0,0.75);color:#fff;font-size:13px;padding:8px 16px;' +
      'border-radius:999px;z-index:2147483002;transition:opacity 0.4s ease;pointer-events:none;';
    document.body.appendChild(t);
    setTimeout(function() { t.style.opacity = '0'; }, 1600);
    setTimeout(function() { t.remove(); }, 2100);
  };

  // ---------- 生命周期 ----------
  NS.remove = function() {
    NS.enabled = false;
    NS.closeLayer();
    if (NS.btn) { NS.btn.remove(); NS.btn = null; }
    if (NS.observer) { NS.observer.disconnect(); NS.observer = null; }
    if (NS.interval) { clearInterval(NS.interval); NS.interval = null; }
  };
  NS.init = function(schemesJson, enabled) {
    NS.enabled = !!enabled;
    try { NS.schemes = JSON.parse(schemesJson) || []; } catch (e) { NS.schemes = []; }
    if (!NS.enabled) {
      // 开关被关：立即摘掉按钮与自绘层（§8 降级链），页面回到原样
      NS.closeLayer();
      if (NS.btn) { NS.btn.remove(); NS.btn = null; }
      if (NS.observer) { NS.observer.disconnect(); NS.observer = null; }
      if (NS.interval) { clearInterval(NS.interval); NS.interval = null; }
      return;
    }
    NS.install();
    NS.watch();
  };
  NS.watch = function() {
    if (NS.observer) return;
    NS.observer = new MutationObserver(function() { schedule(); });
    NS.observer.observe(document.documentElement, { childList: true, subtree: true });
    NS.interval = setInterval(schedule, 500);
  };

  function schedule() {
    if (NS.checkTimer) return;
    NS.checkTimer = setTimeout(function() {
      NS.checkTimer = null;
      if (NS.enabled) NS.install();
    }, 300);
  }

  // 输入时实时校验（只加自己的监听，不碰宿主的）
  document.addEventListener('input', function() { NS.validate(); }, true);
})();"""

    /**
     * 「原生调 JS」的片段：初始化（注入方案摘要 + 开关状态）。
     * [schemesJson] 必须是已转义的安全 JSON 字符串字面量（由 [safeJson] 生成）。
     */
    fun initCall(schemesJson: String, enabled: Boolean): String =
        "window.__gsaux && window.__gsaux.init($schemesJson, $enabled);"

    /** 原生 → JS：回填两个价 */
    fun fillCall(up: String, down: String): String =
        "window.__gsaux && window.__gsaux.fill('$up', '$down');"

    /** 原生 → JS：轻提示 */
    fun toastCall(text: String): String =
        "window.__gsaux && window.__gsaux.toast('${escape(text)}');"

    /** 原生 → JS：移除按钮与自绘层（关开关时） */
    const val REMOVE_CALL: String = "window.__gsaux && window.__gsaux.remove();"

    /** 把字符串包成可安全内联进 JS 的字面量（双引号 + 反斜杠转义） */
    fun safeJson(json: String): String = "\"" + escape(json) + "\""

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")

    /**
     * 生成最终脚本：把占位符替换成实测值。
     * 占位符集中在这一步替换，保持 [INJECT] 与真机实测的页面契约对得上。
     */
    fun build(buttonText: String, buttonColor: String, saveText: String): String = INJECT
        .replace("__BTN_TEXT__", buttonText)
        .replace("__BTN_COLOR__", buttonColor)
        .replace("__SAVE_TEXT__", saveText)
        .replace("__UP_MARK__", UP_MARK)
        .replace("__DOWN_MARK__", DOWN_MARK)
        .replace("__MSG_UNAVAILABLE__", MSG_UNAVAILABLE)
        .replace("__MSG_NO_SCHEME__", MSG_NO_SCHEME)
        .replace("__MSG_PICK_SCHEME__", MSG_PICK_SCHEME)
        .replace("__MSG_MANAGE__", MSG_MANAGE)
        .replace("__MSG_UP_WRONG__", MSG_UP_WRONG)
        .replace("__MSG_DOWN_WRONG__", MSG_DOWN_WRONG)
        .replace("__PRICE_REGEX__", PRICE_REGEX)
}
