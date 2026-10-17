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
    private const val MSG_SAVED_OK = "保存成功"

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
  NS.pickScheme = true;      // 多方案时是否弹选择层（配置按钮设置）
  NS.skipConfirm = false;    // 保存成功是否免确认（保存后免确认）
  NS.saveObs = null;

  var BTN_TEXT = '__BTN_TEXT__';
  var BTN_COLOR = '__BTN_COLOR__';
  var FLEX = '1 1 0';

  /** JS → 原生日志（H5 里没有 console 可看，排查只能靠这条通道） */
  function D(m) {
    try { if (window.gsaux && window.gsaux.diag) window.gsaux.diag(String(m)); } catch (e) {}
  }

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
  /**
   * 从文字叶子往上，取「文字仍然只有这一个标签」的最外层元素 —— 那才是按钮本身。
   *
   * 4-项实测（2026-10-17）：旧版按 `cursor:pointer` / `clk` 类名往上爬，宿主的
   * `div.footer`（整条操作条）也带 pointer 光标，于是一爬就爬到容器上，克隆出来的是
   * **整条 footer**：两条 fixed 定位的 footer 完全重叠、`重置` 出现两次、原来的
   * `保存` 被压在下面 —— 用户看到的「配置把确认按钮顶掉了」。
   */
  function buttonOf(leaf, label) {
    var n = leaf, best = leaf;
    while (n && n !== document.body) {
      if ((n.textContent || '').trim() !== label) break;
      best = n;
      n = n.parentElement;
    }
    return best;
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
    var saveBtn = buttonOf(saveLeaf, '__SAVE_TEXT__');
    var bar = saveBtn.parentElement;
    // 兜底：万一整条只剩一个可点层（不同皮肤），再退回收缩到「按钮尺寸」的那一层
    if (!bar || bar === document.body) {
      saveBtn = clickableOf(saveLeaf);
      bar = saveBtn.parentElement;
    }
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
    // 样式全部随克隆的宿主 class 走；只覆盖底色与文字色（§3.1，改版跟皮肤）。
    // `opacity` 必须显式拉满：宿主的「保存」在没填价时是禁用态，可能用透明度表现，
    // 克隆会连带继承，看起来就像我们的按钮也点不动。
    btn.style.backgroundColor = BTN_COLOR;
    btn.style.color = '#FFFFFF';
    btn.style.opacity = '1';
    // 克隆会把宿主的**禁用态类**一起带过来（实测 `btn_save disable`）：那套样式是灰的，
    // 宿主的委托监听也可能按这个类判「不可点」。我们的按钮自己决定可不可点，摘掉它。
    if (btn.classList) { try { btn.classList.remove('disable'); } catch (e0) {} }
    btn.addEventListener('click', function(e) {
      // 捕获阶段 + stopPropagation：宿主的 footer 有自己的委托监听，
      // 冒泡阶段容易被它先截走（实测点下去毫无反应）
      e.stopPropagation();
      e.preventDefault();
      D('click');
      NS.onConfigClick();
    }, true);
    // 用户直接点宿主的「保存」也要走免确认：捕获阶段只登记，不阻止宿主自己的处理
    found.save.addEventListener('click', function() { NS.armSkip(); }, true);
    found.save.insertAdjacentElement('afterend', btn);
    // 三等分放在**插入之后**：新按钮自己也要一起设，否则它只有内容宽度
    // （旧版在插入前遍历 children，漏掉了自己那颗）。
    if (window.getComputedStyle(bar).display.indexOf('flex') < 0) bar.style.display = 'flex';
    for (var i = 0; i < bar.children.length; i++) {
      bar.children[i].style.flex = FLEX;
      bar.children[i].style.minWidth = '0';
    }
    NS.btn = btn;
    D('installed');
    return true;
  };

  // ---------- 点击「配置」 ----------
  NS.onConfigClick = function() {
    D('onConfig bridge=' + (typeof window.gsaux) + ' schemes=' + NS.schemes.length +
      ' pick=' + NS.pickScheme + ' skip=' + NS.skipConfirm);
    if (!window.gsaux) { NS.toast('__MSG_UNAVAILABLE__'); return; }
    if (!NS.schemes.length) { NS.toast('__MSG_NO_SCHEME__'); return; }
    // 一套方案，或者「多配置下弹出选择方案弹窗」被关掉 → 直接用默认方案
    if (NS.schemes.length === 1 || !NS.pickScheme) {
      var pick = NS.schemes[0];
      for (var i = 0; i < NS.schemes.length; i++) {
        if (NS.schemes[i]['def']) { pick = NS.schemes[i]; break; }
      }
      NS.apply(pick.id);
      return;
    }
    NS.showLayer();
  };
  NS.apply = function(id) {
    D('apply ' + id);
    try { window.gsaux.apply(String(id)); D('apply sent'); }
    catch (e) { D('apply err ' + e); NS.toast('__MSG_UNAVAILABLE__'); }
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
    D('fill ' + up + ',' + down + ' up=' + (!!ui.up) + ' down=' + (!!ui.down));
    setInput(ui.up, up);
    setInput(ui.down, down);
    NS.validate();
    // 填完顺带把宿主的「保存」按一次（用户 2026-10-17 的要求：配置完直接落库，
    // 不用再手动点一下）。宿主把保存从 disable 放开需要一两个 tick，交给 NS.save 轮询。
    setTimeout(function() { NS.save(); }, 80);
  };

  /**
   * 用代码点一次宿主的「保存」——`element.click()`，不是模拟触摸。
   *
   * 为什么不能填完立刻点：宿主是拿到 input/change 事件之后才把「保存」从
   * `btn_save disable` 放开；立刻点会被它当成禁用态忽略。这里轮询 `disable` 类，
   * 放开后点一次；1.5 秒仍没放开就放弃（值已经填好，用户可以自己点）。
   */
  NS.save = function() {
    var leaf = leafByText(document, '__SAVE_TEXT__');
    var sb = leaf ? buttonOf(leaf, '__SAVE_TEXT__') : null;
    if (!sb) return;
    var tries = 0;
    (function attempt() {
      var cls = (typeof sb.className === 'string') ? sb.className : '';
      var disabled = cls.indexOf('disable') >= 0 || sb.getAttribute('aria-disabled') === 'true';
      if (!disabled) {
        D('save click');
        NS.armSkip();
        try { sb.click(); } catch (e) {}
        return;
      }
      if (tries++ < 12) setTimeout(attempt, 120);
      else D('save give up disabled=' + cls);
    })();
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

  /**
   * 等宿主弹出「保存成功！」那个确认弹窗：**看见就藏掉**，换成我们的轻提示，
   * 再让原生收掉页面（`gsaux.saved()`）。
   *
   * 为什么是「等它出现再藏」而不是「不让它出现」：弹窗是宿主自己的 JS 画的，
   * 没有可拦截的公开入口；MutationObserver 是微任务级别，通常赶在首帧前就能隐藏，
   * 用户看到的是「一条提示 + 页面自动回去」。
   */
  NS.armSkip = function() {
    if (!NS.skipConfirm || NS.saveObs) return;
    NS.saveObs = new MutationObserver(function() {
      if (!NS.skipConfirm) { NS.disarmSkip(); return; }
      var hit = null;
      var all = document.querySelectorAll('div,span,p');
      for (var i = 0; i < all.length; i++) {
        var el = all[i];
        if (el.childElementCount !== 0) continue;
        if ((el.textContent || '').indexOf('__MSG_SAVED_OK__') >= 0) { hit = el; break; }
      }
      if (!hit) return;
      // 连遮罩一起藏（往上找到 fixed 定位的那一层）
      var box = hit;
      for (var k = 0; k < 6 && box.parentElement; k++) {
        box = box.parentElement;
        if (window.getComputedStyle(box).position === 'fixed') break;
      }
      box.style.display = 'none';
      NS.disarmSkip();
      NS.toast('__MSG_SAVED_OK__');
      D('skip: saved');
      // 延后 1.2 秒再收页面：提示是画在**页面里**的，立刻关掉就跟着页面一起没了，
      // 用户根本看不见（要求是「弹一条提示并自动返回」，先看见再回去）。
      setTimeout(function() {
        try { window.gsaux.saved(); } catch (e) { D('saved err ' + e); }
      }, 1200);
    });
    NS.saveObs.observe(document.body, { childList: true, subtree: true });
    // 兜底：10 秒还没等到（比如保存失败）就撤掉观察，免得一直挂着
    setTimeout(function() { NS.disarmSkip(); }, 10000);
  };
  NS.disarmSkip = function() {
    if (NS.saveObs) { NS.saveObs.disconnect(); NS.saveObs = null; }
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
  NS.init = function(schemesJson, enabled, pickScheme, skipConfirm) {
    NS.enabled = !!enabled;
    NS.pickScheme = pickScheme !== false;
    NS.skipConfirm = !!skipConfirm;
    try { NS.schemes = JSON.parse(schemesJson) || []; } catch (e) { NS.schemes = []; }
    D('init len=' + String(schemesJson || '').length + ' n=' + NS.schemes.length +
      ' pick=' + NS.pickScheme + ' skip=' + NS.skipConfirm);
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
     * 临时诊断脚本 v2：把底部操作条的真实结构 + 桥状态吐回原生。
     *
     * v1 在 `onPageFinished` 当场跑，那时操作条还没渲染（`chain:null`）——
     * 现在由原生在注入后 2.2 秒再调一次，此时按钮已经插进去了。
     * 排查完即删。
     */
    const val DIAG: String = """(function(){
  function r(el) {
    if (!el) return null;
    var b = el.getBoundingClientRect(), cs = getComputedStyle(el);
    return {
      tag: el.tagName,
      cls: (typeof el.className === 'string' ? el.className : '').slice(0, 90),
      txt: (el.textContent || '').trim().slice(0, 12),
      rect: [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)],
      display: cs.display, flex: cs.flex, position: cs.position,
      bg: cs.backgroundColor, z: cs.zIndex
    };
  }
  var out = { bridge: typeof window.gsaux, vw: window.innerWidth, vh: window.innerHeight };

  // 底部 40% 区域内所有「有文字」的叶子节点（找真实按钮文字用）
  var leaves = [], all = document.querySelectorAll('div,span,button,a,label,p');
  for (var i = 0; i < all.length; i++) {
    var e = all[i];
    if (e.childElementCount !== 0) continue;
    var t = (e.textContent || '').trim();
    if (!t) continue;
    var b = e.getBoundingClientRect();
    if (b.top > window.innerHeight * 0.6 && b.width > 0 && b.height > 0) {
      leaves.push([t.slice(0, 8), Math.round(b.x), Math.round(b.y),
                   Math.round(b.width), Math.round(b.height)]);
    }
  }
  out.leaves = leaves.slice(0, 30);

  // 「保存 / 确认」任一命中就给它的祖先链
  var key = null;
  for (var k = 0; k < leaves.length; k++) {
    if (leaves[k][0] === '__SAVE_TEXT__' || leaves[k][0] === '确认') { key = leaves[k][0]; break; }
  }
  out.keyText = key;
  if (key) {
    for (var j = 0; j < all.length; j++) {
      var el = all[j];
      if (el.childElementCount === 0 && (el.textContent || '').trim() === key) {
        var bb = el.getBoundingClientRect();
        if (bb.top > window.innerHeight * 0.6) {
          var n = el, chain = [];
          for (var m = 0; m < 4 && n; m++) { chain.push(r(n)); n = n.parentElement; }
          out.chain = chain;
          break;
        }
      }
    }
  }
  var ns = window.__gsaux;
  out.btn = ns && ns.btn ? r(ns.btn) : null;
  return JSON.stringify(out);
})()"""

    /**
     * 「原生调 JS」的片段：初始化（注入方案摘要 + 开关状态）。
     * [schemesJson] 必须是已转义的安全 JSON 字符串字面量（由 [safeJson] 生成）。
     */
    fun initCall(
        schemesJson: String,
        enabled: Boolean,
        pickScheme: Boolean,
        skipSaveConfirm: Boolean,
    ): String =
        "window.__gsaux && window.__gsaux.init($schemesJson, $enabled, $pickScheme, $skipSaveConfirm);"

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
        .replace("__MSG_SAVED_OK__", MSG_SAVED_OK)
        .replace("__PRICE_REGEX__", PRICE_REGEX)
}
