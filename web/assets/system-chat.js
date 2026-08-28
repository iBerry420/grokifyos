/**
 * GrokifyOS Chat — Grok Build via WebSocket bridge
 */
(function (global) {
  'use strict';

  const API = global.API_BASE || '/api';
  let ws = null;
  let wsToken = '';
  let wsPath = '/grokpot-ws/';
  let currentSessionId = null;
  let sessionHasMessages = false;
  let isStreaming = false;
  let useHistory = localStorage.getItem('gos_sc_use_history') !== 'false';
  /** Enter inserts newline (send via button / Ctrl+Enter). Mirrors Android enter_for_newline. */
  let enterForNewline = (function () {
    const v = localStorage.getItem('gos_sc_enter_for_newline');
    if (v !== null) return v !== 'false';
    // Migrate legacy “Ctrl+Enter to send” (same default: true)
    return localStorage.getItem('gos_sc_ctrl_enter') !== 'false';
  })();
  let showTools = localStorage.getItem('gos_sc_show_tools') !== 'false';
  let showThoughts = localStorage.getItem('gos_sc_show_thoughts') !== 'false';
  let dbNotes = [];
  let cachedModels = [];

  let streamContainer = null;
  let streamBody = null;
  let streamText = '';
  let streamToolCount = 0;
  let streamStartTime = 0;
  let streamModel = '';
  let streamToolEvents = [];
  let streamThinkingSummary = '';
  let streamMsgId = null;
  let streamPersistTimer = null;
  let currentTextEl = null;
  let currentTextStr = '';
  let thinkingEl = null;
  let thinkingText = '';
  let streamTimeline = [];
  let streamTimerInterval = null;
  let hoveredMsgEl = null;
  let actionMouseY = 0;
  let hideMessageActions = function () {};
  let uiInitialized = false;
  let bridgeHealthy = true;
  let keepScreenOn = localStorage.getItem('gos_sc_keep_awake') !== 'false';
  let wakeLock = null;
  let wakeLockListenersBound = false;
  let wakeLockHeld = false;
  let wakeLockRetryTimer = null;
  let silentVideoEl = null;
  let wakeLockUserGestureBound = false;
  /** True after WS reconnect until agent_resume / no_agent / interrupted. */
  let pendingReconnect = false;

  function $(id) {
    return document.getElementById(id);
  }

  function esc(s) {
    if (typeof global.escapeHtml === 'function') return global.escapeHtml(s);
    const d = document.createElement('div');
    d.textContent = s == null ? '' : String(s);
    return d.innerHTML;
  }

  /**
   * Host sessions owned by marketplace plugins / Spotify Live DJ.
   * Titles use a leading middle-dot (·) — see HostAiClient / Android apps.
   * Hidden from main Chat history so DJ/plugin turns stay app-scoped.
   */
  function isInternalAppSessionTitle(title) {
    const t = String(title || '').trim();
    if (!t) return false;
    return t.charAt(0) === '·' || t.charAt(0) === '•';
  }

  /** Cap WS history so long chats cannot E2BIG the bridge spawn argv. */
  const HISTORY_MAX_MESSAGES = 20;
  const HISTORY_MAX_CHARS = 80000;
  const HISTORY_MSG_MAX_CHARS = 8000;

  function compactHistoryContent(raw) {
    let s = String(raw || '').replace(/<thinking>[\s\S]*?<\/thinking>/gi, '').trim();
    s = s.replace(/\n{3,}/g, '\n\n');
    if (s.length > HISTORY_MSG_MAX_CHARS) s = '…' + s.slice(-HISTORY_MSG_MAX_CHARS);
    return s;
  }

  function fitHistoryWindow(turns) {
    const cleaned = (turns || [])
      .map((t) => {
        const role = String((t && t.role) || '').toLowerCase();
        if (role !== 'user' && role !== 'assistant' && role !== 'system') return null;
        const content = compactHistoryContent(t && t.content);
        if (!content) return null;
        return { role, content };
      })
      .filter(Boolean)
      .slice(-HISTORY_MAX_MESSAGES);
    while (cleaned.length > 1 && cleaned.reduce((n, t) => n + t.content.length, 0) > HISTORY_MAX_CHARS) {
      cleaned.shift();
    }
    return cleaned;
  }

  function visibleSessions(sessions) {
    return (sessions || []).filter((s) => !isInternalAppSessionTitle(s && s.title));
  }

  async function apiGet(path) {
    const res = await fetch(API + path, { credentials: 'same-origin', headers: { Accept: 'application/json' } });
    const data = await res.json().catch(() => ({}));
    if (!res.ok || data.ok === false) throw new Error(data.error || 'request_failed');
    return data;
  }

  async function apiPost(path, body) {
    const res = await fetch(API + path, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify(body || {}),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok || data.ok === false) throw new Error(data.error || 'request_failed');
    return data;
  }

  let markedConfigured = false;

  function ensureMarkedConfig() {
    if (markedConfigured || typeof marked === 'undefined') return;
    markedConfigured = true;
    try {
      const renderer = new marked.Renderer();
      const defaultCode = renderer.code ? renderer.code.bind(renderer) : null;
      renderer.code = function (code, infostring, escaped) {
        const lang = (infostring || '').match(/^\S*/)?.[0] || '';
        let body = typeof code === 'object' && code !== null ? code.text || '' : code;
        const langFromObj = typeof code === 'object' && code !== null ? code.lang || lang : lang;
        if (typeof body !== 'string') body = String(body || '');
        const safe = esc(body);
        const langLabel = langFromObj ? esc(langFromObj) : 'code';
        return (
          '<div class="sc-code-block">' +
          '<div class="sc-code-header">' +
          '<span class="sc-code-lang">' +
          langLabel +
          '</span>' +
          '<button type="button" class="sc-code-copy" data-copy="1" title="Copy code">Copy</button>' +
          '</div>' +
          '<pre><code class="language-' +
          esc(langFromObj || 'text') +
          '">' +
          safe +
          '</code></pre></div>'
        );
      };
      renderer.link = function (href, title, text) {
        // marked v15 may pass token object
        if (href && typeof href === 'object') {
          text = href.text || '';
          title = href.title || null;
          href = href.href || '';
        }
        const u = String(href || '');
        const safeHref = /^(https?:|mailto:|\/|#)/i.test(u) ? u : '#';
        const t = title ? ' title="' + esc(title) + '"' : '';
        return (
          '<a href="' +
          esc(safeHref) +
          '"' +
          t +
          ' target="_blank" rel="noopener noreferrer">' +
          esc(String(text || safeHref)) +
          '</a>'
        );
      };
      marked.setOptions({
        gfm: true,
        breaks: true,
        pedantic: false,
        renderer: renderer,
      });
    } catch (_) {
      try {
        marked.setOptions({ gfm: true, breaks: true });
      } catch (__) {}
    }
  }

  /**
   * Prep model output for marked: heal stream-join artifacts, tidy emphasis,
   * and drop unpaired ** so chat bubbles don't show raw stars.
   *
   * NOTE: never match bold with a loose "star-star ... star-star" regex across
   * the whole string — that glues the closer of one bold to the opener of the
   * next ("** and **") and eats words between them.
   */
  function normalizeChatMarkdown(raw) {
    if (!raw) return '';
    let s = String(raw).replace(/\r\n/g, '\n');

    const fences = [];
    s = s.replace(/```[\s\S]*?```/g, (m) => {
      fences.push(m);
      return '\0FENCE' + (fences.length - 1) + '\0';
    });
    const inlines = [];
    s = s.replace(/`[^`\n]+`/g, (m) => {
      inlines.push(m);
      return '\0INLINE' + (inlines.length - 1) + '\0';
    });

    s = healMidwordSpaces(s);
    s = fixSentenceSpacing(s);
    s = balanceDoubleStars(s);
    s = balanceSingleStars(s);

    s = s.replace(/\0INLINE(\d+)\0/g, (_, i) => inlines[Number(i)] || '');
    s = s.replace(/\0FENCE(\d+)\0/g, (_, i) => fences[Number(i)] || '');
    return s;
  }

  /**
   * Insert missing spaces after .!? before a new sentence.
   * Fixes stream/agent glue like "sleep.Checking" → "sleep. Checking".
   * Skips single-letter abbreviations (U.S., A.I.) and leaves code/fences alone
   * (those are masked before this runs).
   */
  function fixSentenceSpacing(text) {
    if (!text) return text;
    let s = String(text);

    // word/digit/closer + .!? + Capital  →  add space (not U.S / A.B)
    s = s.replace(/(.?)([A-Za-z0-9)\]"'”’»])([.!?])([A-Z])/g, (all, pre, before, punct, after) => {
      const singleLetterAbbr =
        /[A-Z]/.test(before) && (pre === '' || /[^A-Za-z]/.test(pre));
      if (singleLetterAbbr) return all;
      return pre + before + punct + ' ' + after;
    });

    // Period glued to markdown emphasis: "done.**Next" / "done.*Next"
    s = s.replace(/([a-z0-9)\]"'”’])([.!?])(\*{1,2})(?=[A-Za-z])/g, '$1$2 $3');
    // Emphasis closed then capital with no space after punct inside: "**end.**Next" already handled;
    // bare "?**Next" style
    s = s.replace(/(\*{1,2})([.!?])([A-Z])/g, '$1$2 $3');

    return s;
  }

  /** Common short words — never glue these to neighbors when healing spaces. */
  const HEAL_KEEP = new Set(
    (
      'a an the and or but if in on at to of for from by as is it be we he she ' +
      'they you me my our your his her its are was were has had have will can ' +
      'may not no yes so up out off all any new old via per with this that than ' +
      'then when what who how why which into onto over under about after before ' +
      'between through during without within also just more most some such only ' +
      'other upon like back even well very much many own same too still need ' +
      'each few plus vs aka etc use used using user users app apps api apis ' +
      'web now get set put let see say said does did done go going gone come ' +
      'came make made take took give gave want should could would might must ' +
      'mode dark light full real next last first same both once file files ' +
      'data code text chat line lines page pages site name names type types ' +
      'true false null void open close show hide load save send read write ' +
      'run runs work works good best free hard soft high low long short left ' +
      'right main home view list item items form post get head body end start'
    ).split(/\s+/)
  );

  /**
   * Reverse stream-join damage: "normal izers", "G rok ify", "AP K", "I Ds".
   * High-precision only — never glue common phrase words ("dark mode").
   */
  function healMidwordSpaces(text) {
    if (!text) return text;
    let s = String(text);

    // Acronym / ID fragments
    s = s.replace(/\bI\s+Ds\b/g, 'IDs');
    s = s.replace(/\bI\s+D\b/g, 'ID');
    s = s.replace(/\b([A-Z]{1,3})\s+([A-Z]{1,3})\b/g, (m, a, b) =>
      a.length + b.length <= 5 ? a + b : m
    );

    // Morphological / syllable tails from broken tokens (multi-pass)
    const SUFF =
      'izers?|izing|ized|ifies|ify|ifying|able|ible|ables|ibles|apsible|apsible|' +
      'ates|ating|ated|ation|ations|ments?|ness|less|ful|ings?|edly|tions?|sions?|' +
      'ests?|wards?|ures?|ences?|ances?|ents?|ants?|ous|ives?|icals?|ials?|ying|' +
      'ened|ships?|hoods?|isms?|ists?|izes?|ises?|ories?|aries?|uals?|iests?|iers?|' +
      'ies|ied|ily|iness|ably|ibly|atives?|ators?|ability|ibility|' +
      // common broken tails seen in stream joins
      'oring|aring|ering|uring|oping|aping|uting|oting|isting|asting|esting|' +
      'igned|igning|igning|ifying|ified|ifier|ifiers|ifier|ormal|ormalize|' +
      'ocket|ockets|erver|ervers|ession|essions|uthentic|uthenticate|uthenticates|' +
      'otect|otects|ender|enders|endered|endering|ayout|ayouts|utton|uttons|' +
      'anel|anels|idget|idgets|odule|odules|lient|lients|ridge|ridges|' +
      'otals|otal|ount|ounts|essage|essages|istory|istories|ermission|ermissions|' +
      'ersion|ersions|ackage|ackages|uild|uilds|evice|evices|tatus|okens?|' +
      'pot|ify|kify'; // grok pot / Grok ify
    const suffRe = new RegExp('\\b([A-Za-z]{2,})\\s+(' + SUFF + ')\\b', 'gi');
    for (let n = 0; n < 8; n++) {
      const next = s.replace(suffRe, '$1$2');
      if (next === s) break;
      s = next;
    }

    // Single capital (not I/A) + continuation: "G rok" → "Grok"
    for (let n = 0; n < 4; n++) {
      const next = s.replace(/\b([B-HJ-Z])\s+([a-z]{2,12})\b/g, (m, a, b) =>
        HEAL_KEEP.has(b) ? m : a + b
      );
      if (next === s) break;
      s = next;
    }

    // Broken camelCase tech ids only (known Pascal fragments)
    const CAMEL =
      'Http|Https|Url|Uri|Json|Xml|Html|Sql|Api|Uuid|Null|True|False|Socket|' +
      'Stream|Client|Server|Token|Header|Request|Response|Config|Object|Array|' +
      'String|Number|Boolean|Integer|Double|Float|Class|Method|Field|Error|' +
      'Exception|Status|Code|Type|Name|Value|Key|Path|File|Dir|Query|Param|' +
      'Params|Body|Auth|User|Session|Device|Bridge|Model|Prompt|Chunk|Delta';
    const camelRe = new RegExp('([a-z0-9])\\s+(' + CAMEL + ')\\b', 'g');
    for (let n = 0; n < 6; n++) {
      const next = s.replace(camelRe, '$1$2');
      if (next === s) break;
      s = next;
    }

    return s;
  }

  /** Pair **…**; trim inner pad; drop unpaired stars (keep text). */
  function balanceDoubleStars(s) {
    let out = '';
    let i = 0;
    while (i < s.length) {
      if (s[i] === '*' && s[i + 1] === '*') {
        let j = i + 2;
        let found = -1;
        while (j < s.length - 1) {
          if (s[j] === '*' && s[j + 1] === '*') {
            found = j;
            break;
          }
          j++;
        }
        if (found > i + 2) {
          const inner = s.slice(i + 2, found).replace(/^\s+|\s+$/g, '');
          if (inner) out += '**' + inner + '**';
          i = found + 2;
        } else if (found === i + 2) {
          i = found + 2; // empty ****
        } else {
          i += 2; // unpaired opener
        }
      } else {
        out += s[i];
        i++;
      }
    }
    return out;
  }

  /** Pair *…* for italics; leave list markers and unpaired stars alone. */
  function balanceSingleStars(s) {
    let out = '';
    let i = 0;
    while (i < s.length) {
      if (s[i] === '*' && s[i + 1] !== '*') {
        // list marker "* " at line start
        const lineStart = i === 0 || s[i - 1] === '\n';
        if (lineStart && (s[i + 1] === ' ' || s[i + 1] === '\t')) {
          out += s[i];
          i++;
          continue;
        }
        let j = i + 1;
        let found = -1;
        while (j < s.length) {
          if (s[j] === '*' && s[j + 1] !== '*') {
            found = j;
            break;
          }
          if (s[j] === '*' && s[j + 1] === '*') break; // don't cross bold
          if (s[j] === '\n') break;
          j++;
        }
        if (found > i + 1) {
          const inner = s.slice(i + 1, found).replace(/^\s+|\s+$/g, '');
          if (inner) out += '*' + inner + '*';
          i = found + 1;
        } else {
          // unpaired — drop the star
          i++;
        }
      } else if (s[i] === '*' && s[i + 1] === '*') {
        // leave for already-balanced bold (shouldn't remain unpaired)
        out += '**';
        i += 2;
      } else {
        out += s[i];
        i++;
      }
    }
    return out;
  }

  function renderMarkdown(text) {
    if (!text) return '';
    const normalized = normalizeChatMarkdown(text);
    ensureMarkedConfig();
    if (typeof marked !== 'undefined') {
      try {
        return marked.parse(normalized);
      } catch {
        return esc(normalized).replace(/\n/g, '<br>');
      }
    }
    return esc(normalized).replace(/\n/g, '<br>');
  }

  function enhanceMarkdownDom(root) {
    if (!root) return;
    root.querySelectorAll('.sc-code-copy').forEach((btn) => {
      if (btn.dataset.bound) return;
      btn.dataset.bound = '1';
      btn.addEventListener('click', async (e) => {
        e.preventDefault();
        e.stopPropagation();
        const block = btn.closest('.sc-code-block');
        const code = block && block.querySelector('code');
        const text = code ? code.textContent || '' : '';
        try {
          await navigator.clipboard.writeText(text);
          btn.textContent = 'Copied';
          setTimeout(() => {
            btn.textContent = 'Copy';
          }, 1400);
        } catch (_) {
          btn.textContent = 'Fail';
        }
      });
    });
    root.querySelectorAll('.sc-md-content table').forEach((table) => {
      if (table.parentElement && table.parentElement.classList.contains('sc-table-wrap')) return;
      const wrap = document.createElement('div');
      wrap.className = 'sc-table-wrap';
      table.parentNode.insertBefore(wrap, table);
      wrap.appendChild(table);
    });
    // Inline media polish: images open full-size; bare video links → players
    root.querySelectorAll('.sc-md-content img').forEach((img) => {
      if (img.dataset.bound) return;
      img.dataset.bound = '1';
      img.loading = 'lazy';
      img.classList.add('sc-md-img');
      img.addEventListener('click', () => {
        if (img.src) window.open(img.src, '_blank', 'noopener');
      });
    });
    root.querySelectorAll('.sc-md-content a[href]').forEach((a) => {
      if (a.dataset.mediaBound) return;
      const href = a.getAttribute('href') || '';
      if (!/\.(mp4|webm|mov)(\?|$)/i.test(href) && !href.includes('/uploads/system-chat/')) return;
      if (!/\.(mp4|webm|mov)(\?|$)/i.test(href)) return;
      a.dataset.mediaBound = '1';
      const wrap = document.createElement('div');
      wrap.className = 'sc-media-card sc-media-video';
      wrap.innerHTML =
        '<video controls playsinline preload="metadata" src="' +
        esc(href) +
        '"></video>' +
        '<a class="sc-media-open" href="' +
        esc(href) +
        '" target="_blank" rel="noopener">Open video</a>';
      a.replaceWith(wrap);
    });
  }

  function buildMediaHtml(seg) {
    const url = seg.url || '';
    if (!url) return '';
    const kind = seg.kind === 'video' ? 'video' : 'image';
    const rawName = seg.name == null || seg.name === 'null' ? '' : String(seg.name);
    const name = esc(rawName || (kind === 'video' ? 'Video' : 'Image'));
    const rawTool = seg.tool && seg.tool !== 'null' ? String(seg.tool) : '';
    const tool = rawTool ? '<span class="sc-media-tool">' + esc(rawTool) + '</span>' : '';
    if (kind === 'video') {
      return (
        '<div class="sc-media-card sc-media-video">' +
        tool +
        '<video controls playsinline preload="metadata" src="' +
        esc(url) +
        '"></video>' +
        '<a class="sc-media-open" href="' +
        esc(url) +
        '" target="_blank" rel="noopener">' +
        name +
        '</a></div>'
      );
    }
    return (
      '<div class="sc-media-card sc-media-image">' +
      tool +
      '<a href="' +
      esc(url) +
      '" target="_blank" rel="noopener">' +
      '<img class="sc-md-img" src="' +
      esc(url) +
      '" alt="' +
      name +
      '" loading="lazy" />' +
      '</a></div>'
    );
  }

  function createMediaEl(seg) {
    const wrap = document.createElement('div');
    wrap.innerHTML = buildMediaHtml(seg);
    return wrap.firstElementChild;
  }

  /**
   * Join streamed token fragments. Prefer model whitespace; only insert a space
   * at clear sentence boundaries when both sides lack one (e.g. "end." + "Next").
   */
  function joinStreamText(prev, next) {
    if (!next) return prev || '';
    if (!prev) return next;
    if (next.startsWith(prev)) return next;
    if (prev.startsWith(next)) return prev;
    if (prev.endsWith(next)) return prev;
    const maxO = Math.min(64, prev.length, next.length);
    for (let o = maxO; o >= 1; o--) {
      if (prev.slice(-o) === next.slice(0, o)) return prev + next.slice(o);
    }
    // Sentence boundary lost between tokens
    if (/[.!?]["')\]]*$/.test(prev) && /^[A-Za-z*_]/.test(next)) {
      return prev + ' ' + next;
    }
    return prev + next;
  }

  function cloneTimeline(timeline) {
    return (timeline || []).map((seg) => {
      const copy = { type: seg.type };
      if (seg.type === 'thinking') {
        copy.content = seg.content || '';
        copy.done = !!seg.done;
      } else if (seg.type === 'text') {
        copy.content = seg.content || '';
      } else if (seg.type === 'tool') {
        copy.tool = seg.tool || 'tool';
        copy.detail = seg.detail || '';
        copy.success = seg.success;
        copy.info = seg.info || '';
      } else if (seg.type === 'media') {
        copy.kind = seg.kind || 'image';
        copy.url = seg.url || '';
        // Avoid literal "null" from JSON null / stringified null
        const nm = seg.name == null || seg.name === 'null' ? '' : String(seg.name);
        copy.name = nm || (copy.kind === 'video' ? 'Video' : 'Image');
        copy.tool = seg.tool && seg.tool !== 'null' ? seg.tool : '';
      }
      return copy;
    });
  }

  function syncLegacyFromTimeline() {
    streamText = '';
    streamThinkingSummary = '';
    streamToolEvents = [];
    streamToolCount = 0;
    streamTimeline.forEach((seg) => {
      if (seg.type === 'text') {
        if (streamText && seg.content) streamText += '\n\n';
        streamText += seg.content || '';
      }
      else if (seg.type === 'thinking') {
        streamThinkingSummary += (streamThinkingSummary ? '\n\n' : '') + (seg.content || '');
      } else if (seg.type === 'tool') {
        streamToolEvents.push({
          tool: seg.tool,
          detail: seg.detail,
          success: seg.success,
          info: seg.info,
        });
        streamToolCount++;
      }
    });
  }

  function getActiveTimelineThinking() {
    const last = streamTimeline[streamTimeline.length - 1];
    if (last && last.type === 'thinking' && !last.done) return last;
    return null;
  }

  function getActiveTimelineText() {
    const last = streamTimeline[streamTimeline.length - 1];
    if (last && last.type === 'text') return last;
    return null;
  }

  function buildTimelineFromLegacy(thinking, tools, content, media) {
    const timeline = [];
    if (thinking) timeline.push({ type: 'thinking', content: thinking, done: true });
    (tools || []).forEach((t) => {
      timeline.push({
        type: 'tool',
        tool: t.tool || 'tool',
        detail: t.detail || '',
        success: t.success,
        info: t.info || '',
      });
    });
    (media || []).forEach((m) => {
      if (!m || !m.url) return;
      timeline.push({
        type: 'media',
        kind: m.kind === 'video' ? 'video' : 'image',
        url: m.url,
        name: m.name || '',
        tool: m.tool || null,
      });
    });
    if (content) timeline.push({ type: 'text', content });
    return timeline;
  }

  function buildThinkingBlockHtml(content, streaming) {
    if (!content) return '';
    // Streaming: plain text for speed; finalized / history: same GFM as assistant messages.
    const body = streaming ? esc(content) : renderMarkdown(content);
    if (streaming) {
      return (
        '<div class="sc-thinking-block streaming">' +
        '<div class="sc-thinking-header"><span class="sc-thinking-spinner"></span> Thinking</div>' +
        '<div class="sc-thinking-content">' +
        body +
        '</div></div>'
      );
    }
    return (
      '<div class="sc-thinking-block done">' +
      '<div class="sc-thinking-header">▸ Thought (click to expand)</div>' +
      '<div class="sc-thinking-content collapsed sc-md-content">' +
      body +
      '</div></div>'
    );
  }

  function toolStatusIcon(success) {
    if (success === true) return '✓';
    if (success === false) return '✕';
    return '<span class="sc-tool-spinner" aria-hidden="true"></span>';
  }

  /** Pretty-print JSON-looking tool payloads for display. */
  function prettyToolText(raw) {
    if (raw == null || raw === '') return '';
    const text = String(raw);
    const trimmed = text.trim();
    if (
      (trimmed.startsWith('{') && trimmed.endsWith('}')) ||
      (trimmed.startsWith('[') && trimmed.endsWith(']'))
    ) {
      try {
        return JSON.stringify(JSON.parse(trimmed), null, 2);
      } catch (_) {
        /* keep raw */
      }
    }
    return text;
  }

  function oneLinePreview(text, maxLen) {
    const s = String(text || '')
      .replace(/\s+/g, ' ')
      .trim();
    if (!s) return '';
    const n = maxLen || 100;
    return s.length > n ? s.substring(0, n) + '…' : s;
  }

  function buildToolRowHtml(seg) {
    const running = seg.success == null;
    const cls =
      seg.success === true ? ' success' : seg.success === false ? ' failed' : running ? ' running' : '';
    const detail = prettyToolText(seg.detail || '');
    const info = prettyToolText(seg.info || '');
    const hasBody = !!(detail || info);
    const preview = oneLinePreview(detail || info, 110);
    return (
      '<div class="sc-tool-card' +
      cls +
      (hasBody ? '' : ' sc-tool-empty') +
      '">' +
      '<button type="button" class="sc-tool-card-header"' +
      (hasBody ? '' : ' disabled') +
      '>' +
      '<span class="sc-tool-status">' +
      toolStatusIcon(seg.success) +
      '</span>' +
      '<span class="sc-tool-name">' +
      esc(seg.tool || 'tool') +
      '</span>' +
      (preview ? '<span class="sc-tool-preview">' + esc(preview) + '</span>' : '') +
      (hasBody ? '<span class="sc-tool-chevron">▸</span>' : '') +
      '</button>' +
      (hasBody
        ? '<div class="sc-tool-card-body collapsed">' +
          (detail
            ? '<div class="sc-tool-section"><div class="sc-tool-section-label">Input</div><pre class="sc-tool-pre">' +
              esc(detail) +
              '</pre></div>'
            : '') +
          (info
            ? '<div class="sc-tool-section"><div class="sc-tool-section-label">Result</div><pre class="sc-tool-pre">' +
              esc(info) +
              '</pre></div>'
            : '') +
          '</div>'
        : '') +
      '</div>'
    );
  }

  function bindToolCard(el) {
    if (!el || el.dataset.bound) return;
    el.dataset.bound = '1';
    const header = el.querySelector('.sc-tool-card-header');
    const body = el.querySelector('.sc-tool-card-body');
    if (!header || !body) return;
    header.addEventListener('click', () => {
      const open = !body.classList.contains('collapsed');
      body.classList.toggle('collapsed', open);
      el.classList.toggle('open', !open);
      const chev = header.querySelector('.sc-tool-chevron');
      if (chev) chev.textContent = open ? '▸' : '▾';
    });
  }

  function createToolCardEl(seg) {
    const wrap = document.createElement('div');
    wrap.innerHTML = buildToolRowHtml(seg);
    const el = wrap.firstElementChild;
    bindToolCard(el);
    return el;
  }

  function updateToolCardEl(el, seg) {
    if (!el) return;
    const wasOpen = el.classList.contains('open');
    const next = createToolCardEl(seg);
    if (wasOpen) {
      next.classList.add('open');
      const body = next.querySelector('.sc-tool-card-body');
      const chev = next.querySelector('.sc-tool-chevron');
      if (body) body.classList.remove('collapsed');
      if (chev) chev.textContent = '▾';
    }
    el.replaceWith(next);
    return next;
  }

  function renderTimelineHtml(timeline, contentFallback) {
    let html = '';
    const segs = timeline && timeline.length ? timeline : buildTimelineFromLegacy(null, null, contentFallback);
    let hasText = false;
    segs.forEach((seg) => {
      if (seg.type === 'thinking') html += buildThinkingBlockHtml(seg.content, false);
      else if (seg.type === 'tool') {
        // History: never show perpetual running spinner for sealed rows
        const fixed = Object.assign({}, seg);
        if (fixed.success == null) fixed.success = true;
        html += buildToolRowHtml(fixed);
      } else if (seg.type === 'media') html += buildMediaHtml(seg);
      else if (seg.type === 'text' && seg.content) {
        hasText = true;
        html += '<div class="sc-md-content">' + renderMarkdown(seg.content) + '</div>';
      }
    });
    if (!hasText && contentFallback) {
      html += '<div class="sc-md-content">' + renderMarkdown(contentFallback) + '</div>';
    }
    return html;
  }

  function hydrateMessageChrome(root) {
    if (!root) return;
    root.querySelectorAll('.sc-tool-card').forEach(bindToolCard);
    root.querySelectorAll('.sc-md-content').forEach(enhanceMarkdownDom);
  }

  function renderStreamBodyFromTimeline(timeline) {
    if (!streamBody) return;
    streamBody.innerHTML = '';
    thinkingEl = null;
    thinkingText = '';
    currentTextEl = null;
    currentTextStr = '';

    timeline.forEach((seg) => {
      if (seg.type === 'thinking') {
        const el = document.createElement('div');
        el.innerHTML = buildThinkingBlockHtml(seg.content, !seg.done);
        const block = el.firstElementChild;
        streamBody.appendChild(block);
        if (!seg.done) {
          thinkingEl = block;
          thinkingText = seg.content || '';
        }
        seg.el = block;
      } else if (seg.type === 'tool') {
        const row = createToolCardEl(seg);
        streamBody.appendChild(row);
        seg.el = row;
      } else if (seg.type === 'media') {
        const card = createMediaEl(seg);
        if (card) {
          streamBody.appendChild(card);
          seg.el = card;
        }
      } else if (seg.type === 'text') {
        const el = document.createElement('div');
        el.className = 'sc-md-content sc-streaming-text';
        el.textContent = seg.content || '';
        streamBody.appendChild(el);
        currentTextEl = el;
        currentTextStr = seg.content || '';
        seg.el = el;
      }
    });
  }

  /** Only stick to bottom when the user is already near the end (or forced). */
  let pinScrollToBottom = true;

  function isNearBottom(el, threshold) {
    if (!el) return true;
    const t = threshold == null ? 120 : threshold;
    return el.scrollHeight - el.scrollTop - el.clientHeight <= t;
  }

  function scrollBottom(force) {
    const el = $('sc-messages');
    if (!el) return;
    if (!force && !pinScrollToBottom) return;
    el.scrollTop = el.scrollHeight;
    pinScrollToBottom = true;
  }

  function bindMessagesScroll() {
    const el = $('sc-messages');
    if (!el || el.dataset.scrollBound) return;
    el.dataset.scrollBound = '1';
    el.addEventListener(
      'scroll',
      () => {
        pinScrollToBottom = isNearBottom(el, 140);
      },
      { passive: true }
    );
  }

  function setConn(connected) {
    const dot = $('sc-conn-dot');
    if (dot) dot.classList.toggle('connected', !!connected);
  }

  function wsUrl() {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const path = wsPath.startsWith('/') ? wsPath : '/' + wsPath;
    const q = wsToken ? '?token=' + encodeURIComponent(wsToken) : '';
    // Path prefix only (subdirectory installs). Full origins in GROKPOT_SITE_BASE are ignored.
    let pathPrefix = '';
    const siteBase = (global.GROKPOT_SITE_BASE || '').replace(/\/$/, '');
    if (siteBase && !/^https?:\/\//i.test(siteBase)) {
      pathPrefix = siteBase;
    } else if (global.GROKPOT_WEB_BASE) {
      pathPrefix = String(global.GROKPOT_WEB_BASE).replace(/\/$/, '');
    }
    return proto + '//' + location.host + pathPrefix + path + q;
  }

  function buildStreamMeta(finalize, extra) {
    syncLegacyFromTimeline();
    const meta = {
      model: streamModel || null,
      duration: streamStartTime > 0 ? Date.now() - streamStartTime : 0,
      tool_count: streamToolCount,
      tools: streamToolEvents.length ? streamToolEvents : null,
      thinking: streamThinkingSummary || null,
      timeline: cloneTimeline(streamTimeline),
    };
    if (!finalize) meta.streaming = true;
    if (extra && typeof extra === 'object') Object.assign(meta, extra);
    return meta;
  }

  function saveStreamState() {
    if (!currentSessionId || !isStreaming) return;
    try {
      sessionStorage.setItem(
        'gp_sc_stream_state',
        JSON.stringify({
          sessionId: currentSessionId,
          content: streamText,
          thinking: streamThinkingSummary,
          tools: streamToolEvents,
          timeline: cloneTimeline(streamTimeline),
          model: streamModel,
          startTime: streamStartTime,
          msgId: streamMsgId,
          toolCount: streamToolCount,
        })
      );
    } catch (_) {}
  }

  function clearStreamState() {
    sessionStorage.removeItem('gp_sc_streaming');
    sessionStorage.removeItem('gp_sc_stream_state');
  }

  function persistStreamSnapshot(finalize, extraMeta) {
    if (!currentSessionId) return;
    saveStreamState();
    // Always allow finalize; mid-stream also create a row when we have any content
    if (!finalize && !streamMsgId && !(streamText || streamThinkingSummary || streamToolEvents.length)) return;
    if (streamPersistTimer && !finalize) return;
    if (streamPersistTimer) {
      clearTimeout(streamPersistTimer);
      streamPersistTimer = null;
    }
    const run = () => {
      streamPersistTimer = null;
      const content = streamText || '';
      if (!content && !streamMsgId && !finalize && !streamThinkingSummary && !streamToolEvents.length) return;
      const meta = buildStreamMeta(finalize, extraMeta);
      apiPost('/admin-system-chat-messages.php', {
        action: 'stream_upsert',
        session_id: currentSessionId,
        message_id: streamMsgId || 0,
        content,
        metadata: meta,
        finalize: !!finalize,
      })
        .then((res) => {
          if (res.id) streamMsgId = res.id;
          saveStreamState();
        })
        .catch(() => {});
    };
    if (finalize) run();
    else streamPersistTimer = setTimeout(run, 800);
  }

  /** Immediate checkpoint (WS drop / tab hide) — no debounce. */
  function flushStreamSnapshotNow(finalize, extraMeta) {
    if (!currentSessionId || !isStreaming) return;
    if (streamPersistTimer) {
      clearTimeout(streamPersistTimer);
      streamPersistTimer = null;
    }
    saveStreamState();
    const content = streamText || '';
    if (!content && !streamMsgId && !streamThinkingSummary && !streamToolEvents.length) return;
    const meta = buildStreamMeta(!!finalize, extraMeta);
    // fire-and-forget; keep local storage even if request fails
    apiPost('/admin-system-chat-messages.php', {
      action: 'stream_upsert',
      session_id: currentSessionId,
      message_id: streamMsgId || 0,
      content,
      metadata: meta,
      finalize: !!finalize,
    })
      .then((res) => {
        if (res.id) streamMsgId = res.id;
        saveStreamState();
      })
      .catch(() => {});
  }

  function hydrateStreamFromStored(stored) {
    if (!stored) return false;
    if (stored.timeline && stored.timeline.length) {
      streamTimeline = cloneTimeline(stored.timeline);
    } else {
      streamTimeline = buildTimelineFromLegacy(
        stored.thinking || '',
        stored.tools || [],
        stored.content || ''
      );
    }
    syncLegacyFromTimeline();
    streamMsgId = streamMsgId || stored.msgId || null;
    streamModel = streamModel || stored.model || '';
    if (stored.startTime) streamStartTime = stored.startTime;
    if (stored.toolCount != null) streamToolCount = stored.toolCount;
    return !!(streamText || streamThinkingSummary || streamToolEvents.length || streamMsgId);
  }

  async function hydrateStreamFromServer(sessionId) {
    try {
      const data = await apiGet('/admin-system-chat-messages.php?session_id=' + encodeURIComponent(sessionId));
      const partial = findStreamingMessage(data.messages || []);
      // Prefer last assistant even if not marked streaming (bridge may have finalized as interrupted)
      let msg = partial;
      if (!msg && data.messages && data.messages.length) {
        const last = data.messages[data.messages.length - 1];
        if (last.role === 'assistant' && (last.content || (last.metadata && last.metadata.interrupted))) {
          msg = last;
        }
      }
      if (!msg) return false;
      const meta = typeof msg.metadata === 'string' ? JSON.parse(msg.metadata) : msg.metadata || {};
      streamMsgId = msg.id;
      streamModel = streamModel || meta.model || '';
      if (meta.timeline && meta.timeline.length) {
        streamTimeline = cloneTimeline(meta.timeline);
      } else {
        streamTimeline = buildTimelineFromLegacy(meta.thinking || '', meta.tools || [], msg.content || '');
      }
      syncLegacyFromTimeline();
      if (meta.tool_count != null) streamToolCount = meta.tool_count;
      return !!(streamText || streamThinkingSummary || streamToolEvents.length || streamMsgId);
    } catch (_) {
      return false;
    }
  }

  function resetStreamBubbleForReplay() {
    if (streamTimerInterval) {
      clearInterval(streamTimerInterval);
      streamTimerInterval = null;
    }
    if (streamContainer) streamContainer.remove();
    streamContainer = null;
    streamBody = null;
    currentTextEl = null;
    currentTextStr = '';
    thinkingEl = null;
    thinkingText = '';
    streamTimeline = [];
    removeTyping();
  }

  function resetStreamStateForReplay() {
    resetStreamBubbleForReplay();
    streamText = '';
    streamToolCount = 0;
    streamToolEvents = [];
    streamThinkingSummary = '';
    streamTimeline = [];
  }

  function restoreStreamBubbleFromState(state) {
    resetStreamBubbleForReplay();
    streamModel = state.model || '';
    streamStartTime = state.startTime || Date.now();
    streamMsgId = state.msgId || null;
    isStreaming = true;

    if (state.timeline && state.timeline.length) {
      streamTimeline = cloneTimeline(state.timeline);
    } else {
      streamTimeline = buildTimelineFromLegacy(state.thinking || '', state.tools || [], state.content || '');
    }
    syncLegacyFromTimeline();

    const wrap = document.createElement('div');
    wrap.className = 'sc-msg assistant';
    wrap.innerHTML =
      '<div class="sc-msg-bubble">' +
      '<div class="sc-stream-status sc-stream-meta"></div>' +
      '<div class="sc-stream-body"></div></div>';
    $('sc-messages').appendChild(wrap);
    streamContainer = wrap;
    streamBody = wrap.querySelector('.sc-stream-body');
    renderStreamBodyFromTimeline(streamTimeline);

    updateStreamMeta();
    streamTimerInterval = setInterval(updateStreamMeta, 1000);
    updateSendState();
    scrollBottom();
  }

  function restoreStreamBubbleFromMessage(msg) {
    const meta = typeof msg.metadata === 'string' ? JSON.parse(msg.metadata) : msg.metadata || {};
    restoreStreamBubbleFromState({
      model: meta.model || '',
      startTime: Date.now() - (meta.duration || 0),
      content: msg.content || '',
      thinking: meta.thinking || '',
      tools: meta.tools || [],
      timeline: meta.timeline || null,
      toolCount: meta.tool_count || (meta.tools ? meta.tools.length : 0),
      msgId: msg.id,
    });
  }

  function getStreamStateFromStorage(sessionId) {
    try {
      const raw = sessionStorage.getItem('gp_sc_stream_state');
      if (!raw) return null;
      const state = JSON.parse(raw);
      if (!state || state.sessionId !== sessionId) return null;
      return state;
    } catch {
      return null;
    }
  }

  function findStreamingMessage(messages) {
    if (!messages || !messages.length) return null;
    const last = messages[messages.length - 1];
    if (last.role !== 'assistant') return null;
    const meta = typeof last.metadata === 'string' ? JSON.parse(last.metadata) : last.metadata;
    if (meta && meta.streaming) return last;
    return null;
  }

  async function resumeInterruptedStream(sessionId) {
    currentSessionId = sessionId;
    isStreaming = true;
    updateSendState();

    const stored = getStreamStateFromStorage(sessionId);
    if (stored) {
      streamModel = stored.model || '';
      streamStartTime = stored.startTime || Date.now();
      streamMsgId = stored.msgId || null;
      if (stored.timeline && stored.timeline.length) {
        streamTimeline = cloneTimeline(stored.timeline);
      } else {
        streamTimeline = buildTimelineFromLegacy(stored.thinking || '', stored.tools || [], stored.content || '');
      }
      syncLegacyFromTimeline();
    } else {
      try {
        const data = await apiGet('/admin-system-chat-messages.php?session_id=' + encodeURIComponent(sessionId));
        const partial = findStreamingMessage(data.messages || []);
        if (partial) {
          const meta = typeof partial.metadata === 'string' ? JSON.parse(partial.metadata) : partial.metadata || {};
          streamModel = meta.model || '';
          streamStartTime = Date.now() - (meta.duration || 0);
          streamMsgId = partial.id;
          if (meta.timeline && meta.timeline.length) {
            streamTimeline = cloneTimeline(meta.timeline);
          } else {
            streamTimeline = buildTimelineFromLegacy(meta.thinking || '', meta.tools || [], partial.content || '');
          }
          syncLegacyFromTimeline();
        }
      } catch (_) {}
    }

    if (streamText || streamThinkingSummary || streamToolEvents.length) {
      if (!(ws && ws.readyState === WebSocket.OPEN)) {
        restoreStreamBubbleFromState({
          model: streamModel,
          startTime: streamStartTime,
          timeline: cloneTimeline(streamTimeline),
          msgId: streamMsgId,
        });
      }
    } else {
      showTyping();
    }

    if (ws && ws.readyState === WebSocket.OPEN) {
      const savedMsgId = streamMsgId;
      const savedModel = streamModel;
      const savedStart = streamStartTime;
      resetStreamStateForReplay();
      streamMsgId = savedMsgId;
      streamModel = savedModel;
      streamStartTime = savedStart;
      ws.send(JSON.stringify({ type: 'reconnect', session_id: sessionId }));
    }
  }

  function finalizeInterruptedStream(reason) {
    if (!isStreaming) return;
    pendingReconnect = false;
    const content = streamText || '';
    const extra = { interrupted: true, interrupt_reason: reason || 'disconnected' };
    if (content || streamMsgId || streamThinkingSummary || streamToolEvents.length) {
      flushStreamSnapshotNow(true, extra);
      if (streamContainer) {
        finalizeThinkingBlock();
        // Seal open tools so interrupted turns don't leave eternal spinners
        streamTimeline.forEach((seg) => {
          if (seg.type === 'tool' && seg.success == null) seg.success = true;
          if (seg.type === 'thinking') seg.done = true;
        });
        syncLegacyFromTimeline();
        finalizeStreamTextSegments();
        stripStreamCursors(streamContainer);
        const st = streamContainer.querySelector('.sc-stream-meta');
        if (st) {
          st.textContent =
            (streamModel || '') +
            ' · ' +
            formatElapsed(Date.now() - streamStartTime) +
            (streamToolCount ? ' · ' + streamToolCount + ' tools' : '') +
            ' · interrupted';
        }
        if (streamMsgId) streamContainer.setAttribute('data-msg-id', streamMsgId);
        streamContainer = null;
        streamBody = null;
      } else if (content || streamToolEvents.length || streamThinkingSummary) {
        removeTyping();
        appendMessage('assistant', content, buildStreamMeta(true, extra), streamMsgId);
      }
    } else {
      removeTyping();
    }
    if (streamTimerInterval) {
      clearInterval(streamTimerInterval);
      streamTimerInterval = null;
    }
    isStreaming = false;
    streamMsgId = null;
    streamTimeline = [];
    clearStreamState();
    updateSendState();
    loadSessions();
    scrollBottom();
  }

  async function recoverInterruptedStream(reason) {
    if (!isStreaming) return;
    // Prefer live DOM/timeline; then sessionStorage; then server partial row
    if (!(streamText || streamThinkingSummary || streamToolEvents.length)) {
      const stored = getStreamStateFromStorage(currentSessionId);
      if (stored) hydrateStreamFromStored(stored);
    }
    if (!(streamText || streamThinkingSummary || streamToolEvents.length || streamMsgId) && currentSessionId) {
      await hydrateStreamFromServer(currentSessionId);
    }
    if (streamText || streamThinkingSummary || streamToolEvents.length || streamMsgId) {
      if (!streamContainer) {
        restoreStreamBubbleFromState({
          model: streamModel,
          startTime: streamStartTime || Date.now(),
          timeline: cloneTimeline(streamTimeline),
          msgId: streamMsgId,
        });
      }
      finalizeInterruptedStream(reason || 'no_agent');
    } else {
      pendingReconnect = false;
      isStreaming = false;
      removeTyping();
      clearStreamState();
      updateSendState();
    }
  }

  function connectWS() {
    if (!wsToken) return;
    ws = new WebSocket(wsUrl());
    ws.onopen = () => {
      setConn(true);
      if (isStreaming && currentSessionId) {
        // Keep bubble + sessionStorage until agent_resume (live) or no_agent (dead)
        saveStreamState();
        flushStreamSnapshotNow(false);
        pendingReconnect = true;
        ws.send(JSON.stringify({ type: 'reconnect', session_id: currentSessionId }));
      }
    };
    ws.onclose = () => {
      setConn(false);
      if (isStreaming) {
        saveStreamState();
        flushStreamSnapshotNow(false, { streaming: true });
      }
      pendingReconnect = false;
      setTimeout(connectWS, 2000);
    };
    ws.onerror = () => {
      try {
        ws.close();
      } catch (_) {}
    };
    ws.onmessage = (evt) => {
      let data;
      try {
        data = JSON.parse(evt.data);
      } catch {
        return;
      }
      handleWsEvent(data);
    };
  }

  function handleWsEvent(data) {
    switch (data.type) {
      case 'agent_resume':
        // Live agent still running — wipe bubble and accept full event replay
        pendingReconnect = false;
        if (data.message_id) streamMsgId = parseInt(data.message_id, 10) || streamMsgId;
        resetStreamStateForReplay();
        if (data.message_id) streamMsgId = parseInt(data.message_id, 10) || streamMsgId;
        isStreaming = true;
        updateSendState();
        break;
      case 'bridge_stopping':
        // Detached agents (HA) keep running — hold the stream and reconnect for agent_resume
        if (isStreaming) {
          saveStreamState();
          flushStreamSnapshotNow(false, { streaming: true, interrupt_reason: 'bridge_restart' });
          if (data.agents_survive || data.reason === 'worker_restart') {
            pendingReconnect = true;
            setConn(false);
            try { if (ws) ws.close(); } catch (_) {}
            setTimeout(connectWS, 400);
          }
        }
        break;
      case 'interrupted':
        if (data.message_id) streamMsgId = parseInt(data.message_id, 10) || streamMsgId;
        if (data.content && String(data.content).length > (streamText || '').length) {
          // Prefer longer server-side snapshot when we missed late chunks
          streamTimeline = buildTimelineFromLegacy(streamThinkingSummary, streamToolEvents, String(data.content));
          syncLegacyFromTimeline();
        }
        if (!streamContainer && (streamText || data.content)) {
          restoreStreamBubbleFromState({
            model: data.model || streamModel,
            startTime: streamStartTime || Date.now() - (data.duration || 0),
            timeline: cloneTimeline(streamTimeline),
            msgId: streamMsgId,
          });
        }
        finalizeInterruptedStream(data.reason || 'interrupted');
        break;
      case 'init':
        ensureStreamBubble();
        streamModel = data.model || '';
        updateStreamMeta();
        break;
      case 'thinking_delta':
        if (data.content) appendThinking(data.content);
        break;
      case 'thinking_done':
        finalizeThinkingSegment();
        break;
      case 'chunk':
        if (data.content) appendChunk(data.content);
        break;
      case 'text_replace':
        if (data.content != null) {
          ensureStreamBubble();
          while (streamTimeline.length && streamTimeline[streamTimeline.length - 1].type === 'text') {
            const last = streamTimeline.pop();
            if (last.el) last.el.remove();
          }
          currentTextEl = null;
          currentTextStr = '';
          const seg = { type: 'text', content: String(data.content) };
          streamTimeline.push(seg);
          const el = document.createElement('div');
          el.className = 'sc-md-content sc-streaming-text';
          el.textContent = balanceDoubleStars(
            fixSentenceSpacing(healMidwordSpaces(seg.content))
          );
          streamBody.appendChild(el);
          ensureStreamCursor(el);
          seg.el = el;
          currentTextEl = el;
          currentTextStr = seg.content;
          syncLegacyFromTimeline();
          saveStreamState();
          persistStreamSnapshot(false);
          scrollBottom();
        }
        break;
      case 'tool_start':
        handleToolStart(data);
        break;
      case 'tool_done':
        handleToolDone(data);
        break;
      case 'media':
        handleMediaEvent(data);
        break;
      case 'done':
        finalizeStream(data.content, data.duration, data.tools, data);
        break;
      case 'partial_msg_id':
        if (data.message_id) streamMsgId = parseInt(data.message_id, 10) || streamMsgId;
        saveStreamState();
        break;
      case 'error':
        finalizeStream('Error: ' + (data.content || 'unknown'), 0, 0, { model: streamModel });
        break;
      case 'no_agent':
        if (isStreaming) {
          // Agent died with the bridge — keep whatever we already streamed
          recoverInterruptedStream('no_agent');
        } else {
          pendingReconnect = false;
        }
        break;
    }
  }

  function updateStreamMeta() {
    const el = streamContainer && streamContainer.querySelector('.sc-stream-meta');
    if (el) el.textContent = streamModel + ' · ' + formatElapsed(Date.now() - streamStartTime);
  }

  function normalizeDuration(ms) {
    const n = Number(ms);
    if (!Number.isFinite(n) || n < 0 || n > 86400000) return 0;
    return n;
  }

  function formatElapsed(ms) {
    const s = (normalizeDuration(ms) / 1000) | 0;
    return s < 60 ? s + 's' : Math.floor(s / 60) + 'm ' + (s % 60) + 's';
  }

  function ensureStreamBubble() {
    if (streamContainer) return;
    hideMessageActions();
    removeTyping();
    if (!streamStartTime) streamStartTime = Date.now();
    if (!streamModel && $('sc-model-select')) streamModel = $('sc-model-select').value || '';

    const wrap = document.createElement('div');
    wrap.className = 'sc-msg assistant';
    wrap.innerHTML =
      '<div class="sc-msg-bubble">' +
      '<div class="sc-stream-status sc-stream-meta"></div>' +
      '<div class="sc-stream-body"></div></div>';
    $('sc-messages').appendChild(wrap);
    streamContainer = wrap;
    streamBody = wrap.querySelector('.sc-stream-body');
    if (!streamTimerInterval) streamTimerInterval = setInterval(updateStreamMeta, 1000);
    pinScrollToBottom = true;
    scrollBottom(true);
  }

  /** Remove live typing caret nodes (safety net for leftover DOM). */
  function stripStreamCursors(root) {
    if (!root || !root.querySelectorAll) return;
    root.querySelectorAll('.sc-stream-cursor').forEach((el) => el.remove());
  }

  /**
   * Close the active live text segment: drop caret, render markdown.
   * Prevents a blinking caret left at the end of every finished sentence/block
   * when tools or thinking interrupt the stream.
   */
  function closeActiveTextSegment() {
    const seg = getActiveTimelineText();
    const el = (seg && seg.el) || currentTextEl;
    if (el) {
      stripStreamCursors(el);
      el.classList.remove('sc-streaming-text');
      const content = seg ? seg.content || '' : currentTextStr || '';
      if (content) {
        el.innerHTML = renderMarkdown(content);
        enhanceMarkdownDom(el);
      }
    }
    currentTextEl = null;
    currentTextStr = '';
  }

  function ensureStreamCursor(el) {
    if (!el) return;
    let cursor = el.querySelector(':scope > .sc-stream-cursor');
    if (!cursor) {
      cursor = document.createElement('span');
      cursor.className = 'sc-stream-cursor';
      cursor.setAttribute('aria-hidden', 'true');
      el.appendChild(cursor);
    }
  }

  function appendThinking(text) {
    ensureStreamBubble();
    hideMessageActions();
    let seg = getActiveTimelineThinking();
    if (!seg) {
      // Leaving text for thinking — seal prior text so caret does not keep blinking
      closeActiveTextSegment();
      seg = { type: 'thinking', content: '', done: false };
      streamTimeline.push(seg);
      thinkingEl = document.createElement('div');
      thinkingEl.className = 'sc-thinking-block streaming';
      thinkingEl.innerHTML =
        '<div class="sc-thinking-header"><span class="sc-thinking-spinner"></span> Thinking</div>' +
        '<div class="sc-thinking-content"></div>';
      streamBody.appendChild(thinkingEl);
      seg.el = thinkingEl;
    }
    seg.content += text;
    thinkingText = seg.content;
    const contentEl = thinkingEl && thinkingEl.querySelector('.sc-thinking-content');
    if (contentEl) {
      contentEl.textContent = seg.content;
      // Keep latest thoughts visible inside the thinking pane
      contentEl.scrollTop = contentEl.scrollHeight;
    }
    syncLegacyFromTimeline();
    scrollBottom();
    saveStreamState();
    persistStreamSnapshot(false);
  }

  function finalizeThinkingSegment() {
    const seg = getActiveTimelineThinking();
    if (!seg && !thinkingEl) return;
    if (seg) seg.done = true;
    if (thinkingEl) {
      thinkingEl.classList.remove('streaming');
      thinkingEl.classList.add('done');
      const header = thinkingEl.querySelector('.sc-thinking-header');
      const content = thinkingEl.querySelector('.sc-thinking-content');
      if (header) setThinkingHeaderLabel(header, true);
      if (content) {
        const finalThought = seg ? seg.content : thinkingText;
        content.innerHTML = renderMarkdown(finalThought || '');
        content.classList.add('collapsed', 'sc-md-content');
      }
    }
    thinkingEl = null;
    thinkingText = '';
    syncLegacyFromTimeline();
  }

  function appendChunk(text) {
    if (!text) return;
    ensureStreamBubble();
    // Text after thinking: collapse the thought block so it doesn't keep spinning
    if (getActiveTimelineThinking()) finalizeThinkingSegment();
    let seg = getActiveTimelineText();
    if (!seg) {
      seg = { type: 'text', content: '' };
      streamTimeline.push(seg);
      currentTextEl = document.createElement('div');
      currentTextEl.className = 'sc-md-content sc-streaming-text';
      streamBody.appendChild(currentTextEl);
      seg.el = currentTextEl;
      currentTextStr = '';
    }
    const merged = joinStreamText(seg.content, text);
    if (merged === seg.content) return;
    seg.content = merged;
    currentTextStr = seg.content;
    if (currentTextEl) {
      // Live view: heal spaces + sentence gaps + strip orphan ** mid-stream
      const live = balanceDoubleStars(fixSentenceSpacing(healMidwordSpaces(seg.content)));
      // Keep a single text node + caret; avoid wiping so we can re-attach caret cleanly
      currentTextEl.textContent = live;
      currentTextEl.classList.add('sc-streaming-text');
      ensureStreamCursor(currentTextEl);
    }
    syncLegacyFromTimeline();
    scrollBottom();
    saveStreamState();
    persistStreamSnapshot(false);
  }

  function finalizeStreamTextSegments() {
    if (streamBody) stripStreamCursors(streamBody);
    streamTimeline.forEach((seg) => {
      if (seg.type === 'text' && seg.el) {
        stripStreamCursors(seg.el);
        seg.el.classList.remove('sc-streaming-text');
        seg.el.innerHTML = renderMarkdown(seg.content || '');
        enhanceMarkdownDom(seg.el);
      }
      if (seg.type === 'tool') {
        // Seal any tool that never received tool_done
        if (seg.success == null) seg.success = true;
        if (seg.el) {
          seg.el = updateToolCardEl(seg.el, seg);
        }
      }
      if (seg.type === 'thinking' && !seg.done) {
        seg.done = true;
      }
    });
    currentTextEl = null;
    currentTextStr = '';
    syncLegacyFromTimeline();
  }

  function findOpenToolSeg(toolName) {
    let fallback = -1;
    for (let i = streamTimeline.length - 1; i >= 0; i--) {
      const seg = streamTimeline[i];
      if (seg.type !== 'tool' || seg.success != null) continue;
      if (toolName && seg.tool === toolName) return seg;
      if (fallback < 0) fallback = i;
    }
    return fallback >= 0 ? streamTimeline[fallback] : null;
  }

  function handleToolStart(data) {
    ensureStreamBubble();
    // Seal prior text + open thinking so order stays chronological and spinners stop
    if (getActiveTimelineThinking()) finalizeThinkingSegment();
    closeActiveTextSegment();
    const seg = {
      type: 'tool',
      tool: data.tool || 'tool',
      detail: data.detail || '',
      success: null,
      info: '',
    };
    streamTimeline.push(seg);
    const row = createToolCardEl(seg);
    streamBody.appendChild(row);
    seg.el = row;
    syncLegacyFromTimeline();
    scrollBottom();
    saveStreamState();
    persistStreamSnapshot(false);
  }

  function handleToolDone(data) {
    // Parallel tool batches: match last open tool by name (not only the absolute last segment)
    const target = findOpenToolSeg(data.tool || '');
    if (target) {
      target.success = typeof data.success === 'boolean' ? data.success : true;
      if (data.info) target.info = data.info;
      if (data.tool) target.tool = data.tool;
      if (data.detail) target.detail = data.detail;
      target.el = updateToolCardEl(target.el, target);
    }
    syncLegacyFromTimeline();
    saveStreamState();
    persistStreamSnapshot(false);
  }

  function handleMediaEvent(data) {
    if (!data || !data.url) return;
    ensureStreamBubble();
    closeActiveTextSegment();
    // Dedupe by url
    if (streamTimeline.some((s) => s.type === 'media' && s.url === data.url)) return;
    const seg = {
      type: 'media',
      kind: data.kind === 'video' ? 'video' : 'image',
      url: data.url,
      name: data.name || '',
      tool: data.tool || null,
    };
    streamTimeline.push(seg);
    const card = createMediaEl(seg);
    if (card && streamBody) {
      streamBody.appendChild(card);
      seg.el = card;
    }
    syncLegacyFromTimeline();
    scrollBottom();
    saveStreamState();
    persistStreamSnapshot(false);
  }

  function setThinkingHeaderLabel(header, collapsed) {
    if (!header) return;
    header.textContent = collapsed ? '▸ Thought (click to expand)' : '▾ Thought (click to collapse)';
  }

  function finalizeThinkingBlock() {
    if (thinkingEl) finalizeThinkingSegment();
  }

  function finalizeStream(content, duration, tools, meta) {
    if (streamTimerInterval) {
      clearInterval(streamTimerInterval);
      streamTimerInterval = null;
    }
    const doneContent = content && String(content).trim() ? String(content) : '';
    const finalContent = doneContent || streamText || '';
    if (doneContent) streamText = doneContent;
    // Attach any media from the done payload that wasn't streamed live
    if (meta && Array.isArray(meta.media)) {
      meta.media.forEach((m) => {
        if (!m || !m.url) return;
        if (streamTimeline.some((s) => s.type === 'media' && s.url === m.url)) return;
        handleMediaEvent(m);
      });
    }
    const elapsed =
      duration > 0 && duration < 86400000
        ? duration
        : streamStartTime > 0
          ? Date.now() - streamStartTime
          : 0;
    // Seal open thinking before building final meta / DOM
    finalizeThinkingBlock();
    // Seal open tools so no spinners remain after the turn
    streamTimeline.forEach((seg) => {
      if (seg.type === 'tool' && seg.success == null) seg.success = true;
      if (seg.type === 'thinking') seg.done = true;
    });
    syncLegacyFromTimeline();

    const metaObj = {
      model: (meta && meta.model) || streamModel,
      duration: elapsed,
      tool_count: tools || streamToolCount,
      tools: streamToolEvents.length ? streamToolEvents : null,
      thinking: streamThinkingSummary || null,
      timeline: cloneTimeline(streamTimeline),
      media: (meta && meta.media) || streamTimeline.filter((s) => s.type === 'media').map((s) => ({
        kind: s.kind,
        url: s.url,
        name: s.name,
        tool: s.tool,
      })),
    };

    if (streamContainer) {
      // Keep interleaved segments — do not collapse all text into one block.
      // Only create a trailing text segment if we never streamed any text but have final content.
      const hasTextSeg = streamTimeline.some((s) => s.type === 'text' && s.content);
      if (finalContent && !hasTextSeg) {
        const seg = { type: 'text', content: finalContent };
        streamTimeline.push(seg);
        if (streamBody) {
          const el = document.createElement('div');
          el.className = 'sc-md-content';
          el.innerHTML = renderMarkdown(finalContent);
          streamBody.appendChild(el);
          enhanceMarkdownDom(el);
          seg.el = el;
        }
        syncLegacyFromTimeline();
        metaObj.timeline = cloneTimeline(streamTimeline);
      }
      finalizeStreamTextSegments();
      stripStreamCursors(streamContainer);
      const st = streamContainer.querySelector('.sc-stream-meta');
      if (st) st.textContent = (metaObj.model || '') + ' · ' + formatElapsed(metaObj.duration) + (metaObj.tool_count ? ' · ' + metaObj.tool_count + ' tools' : '');
      if (streamMsgId) streamContainer.setAttribute('data-msg-id', streamMsgId);
    } else if (finalContent || (metaObj.timeline && metaObj.timeline.length)) {
      removeTyping();
      appendMessage('assistant', finalContent, metaObj, streamMsgId);
    }

    persistStreamSnapshot(true);
    streamContainer = null;
    streamBody = null;
    streamTimeline = [];
    isStreaming = false;
    streamMsgId = null;
    clearStreamState();
    updateSendState();
    loadSessions();
    scrollBottom();
  }

  function showTyping() {
    removeTyping();
    const d = document.createElement('div');
    d.className = 'sc-msg assistant';
    d.id = 'sc-typing';
    d.innerHTML = '<div class="sc-msg-bubble"><div class="sc-typing"><span></span><span></span><span></span></div></div>';
    $('sc-messages').appendChild(d);
    scrollBottom();
  }

  function removeTyping() {
    const t = $('sc-typing');
    if (t) t.remove();
  }

  function fmtTokens(n) {
    n = Number(n) || 0;
    if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M';
    if (n >= 1000) return (n / 1000).toFixed(1) + 'k';
    return String(n);
  }

  function buildStatusHtml(meta) {
    if (!meta) return '';
    const parts = [];
    if (meta.duration) {
      const s = (meta.duration / 1000) | 0;
      parts.push(s < 60 ? s + 's' : Math.floor(s / 60) + 'm ' + (s % 60) + 's');
    }
    if (meta.tool_count) parts.push(meta.tool_count + ' tools');
    if (meta.input_tokens || meta.output_tokens) {
      parts.push(fmtTokens(meta.input_tokens) + ' → ' + fmtTokens(meta.output_tokens) + (meta.tokens_estimated ? '~' : ''));
    }
    const model = meta.model ? esc(meta.model) : '';
    const stats = parts.length ? '<span class="text-[#6b7280]">' + esc(parts.join(' · ')) + '</span>' : '';
    return '<div class="sc-stream-status">' + model + (stats ? ' ' + stats : '') + '</div>';
  }

  function buildToolsHtml(meta) {
    if (!meta || !meta.tools || !meta.tools.length) return '';
    let html = '<div class="sc-tools-stack">';
    meta.tools.forEach((t) => {
      html += buildToolRowHtml({
        tool: t.tool || 'tool',
        detail: t.detail || '',
        success: t.success,
        info: t.info || '',
      });
    });
    html += '</div>';
    return html;
  }

  function buildThinkingHtml(meta) {
    if (!meta || !meta.thinking) return '';
    return buildThinkingBlockHtml(meta.thinking, false);
  }

  function formatTimestamp(ts) {
    if (!ts) return '';
    try {
      // Full date + time to the second for conversation context.
      let d;
      if (typeof ts === 'number') {
        d = new Date(ts < 1e12 ? ts * 1000 : ts);
      } else {
        const s = String(ts).trim();
        d = new Date(s.includes('T') ? s : s.replace(' ', 'T'));
        if (isNaN(d.getTime())) d = new Date(s);
      }
      if (isNaN(d.getTime())) return '';
      const pad = (n) => String(n).padStart(2, '0');
      const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
      return (
        months[d.getMonth()] +
        ' ' +
        d.getDate() +
        ', ' +
        d.getFullYear() +
        ' · ' +
        pad(d.getHours()) +
        ':' +
        pad(d.getMinutes()) +
        ':' +
        pad(d.getSeconds())
      );
    } catch {
      return '';
    }
  }

  function appendMessage(role, content, metadata, msgId, excluded, createdAt) {
    const div = document.createElement('div');
    div.className = 'sc-msg ' + role + (excluded ? ' excluded' : '');
    if (msgId) div.setAttribute('data-msg-id', msgId);
    let inner = '';
    if (role === 'assistant' && metadata) {
      inner += buildStatusHtml(metadata);
      if (metadata.timeline && metadata.timeline.length) {
        // Ensure media entries from metadata.media appear even if older timelines lack them
        let tl = metadata.timeline;
        if (metadata.media && metadata.media.length) {
          const have = new Set(tl.filter((s) => s.type === 'media').map((s) => s.url));
          metadata.media.forEach((m) => {
            if (m && m.url && !have.has(m.url)) {
              tl = tl.concat([
                {
                  type: 'media',
                  kind: m.kind === 'video' ? 'video' : 'image',
                  url: m.url,
                  name: m.name || '',
                  tool: m.tool || null,
                },
              ]);
            }
          });
        }
        inner += renderTimelineHtml(tl, content || '');
      } else {
        inner += buildThinkingHtml(metadata);
        inner += buildToolsHtml(metadata);
        if (metadata.media && metadata.media.length) {
          metadata.media.forEach((m) => {
            inner += buildMediaHtml({
              kind: m.kind,
              url: m.url,
              name: m.name,
              tool: m.tool,
            });
          });
        }
        inner += '<div class="sc-md-content">' + renderMarkdown(content) + '</div>';
      }
    } else if (role === 'user') {
      inner = '<div class="sc-md-content">' + esc(content).replace(/\n/g, '<br>') + '</div>';
    } else {
      inner = '<div class="sc-md-content">' + renderMarkdown(content) + '</div>';
    }
    const ts = formatTimestamp(createdAt);
    div.innerHTML =
      '<div class="sc-msg-bubble">' + inner + '</div>' + (ts ? '<div class="sc-msg-timestamp">' + esc(ts) + '</div>' : '');
    hydrateMessageChrome(div);
    $('sc-messages').appendChild(div);
    return div;
  }

  function clearMessages() {
    const msgs = $('sc-messages');
    const welcome = $('sc-welcome');
    if (!msgs) return;
    msgs.innerHTML = '';
    if (welcome) {
      welcome.style.display = '';
      msgs.appendChild(welcome);
    }
  }

  function formatUsageReset(resetAt, withClock) {
    if (!resetAt) return withClock ? 'Reset time unknown' : 'reset unknown';
    try {
      const end = new Date(resetAt);
      if (Number.isNaN(end.getTime())) return (withClock ? 'Resets ' : 'resets ') + resetAt;
      const secs = Math.floor((end.getTime() - Date.now()) / 1000);
      let relative;
      if (secs <= 0) relative = 'soon';
      else if (secs < 3600) relative = 'in ' + Math.floor(secs / 60) + 'm';
      else if (secs < 86400) relative = 'in ' + Math.floor(secs / 3600) + 'h';
      else {
        const days = Math.floor(secs / 86400);
        const hours = Math.floor((secs % 86400) / 3600);
        relative = hours > 0 ? 'in ' + days + 'd ' + hours + 'h' : 'in ' + days + 'd';
      }
      if (!withClock) return 'resets ' + relative;
      const clock = end.toLocaleString(undefined, {
        weekday: 'short',
        hour: 'numeric',
        minute: '2-digit',
      });
      return 'Resets ' + relative + ' · ' + clock;
    } catch (_) {
      return (withClock ? 'Resets ' : 'resets ') + resetAt;
    }
  }

  function formatUsagePercent(n) {
    const v = Number(n) || 0;
    return Number.isInteger(v) ? String(v) : v.toFixed(1);
  }

  function formatUsagePercentLabel(n) {
    return formatUsagePercent(n) + '%';
  }

  function usageLevelClass(pct) {
    if (pct >= 90) return 'sc-usage-crit';
    if (pct >= 70) return 'sc-usage-warn';
    return '';
  }

  function renderUsageTrackerHtml(tracker) {
    if (!tracker || !tracker.ok || !tracker.totals) return '';
    const t = tracker.totals;
    const cell = (label, value) =>
      '<div class="sc-usage-metric"><div class="sc-usage-metric-val">' +
      escapeHtml(String(value || '0')) +
      '</div><div class="sc-usage-metric-label">' + escapeHtml(label) + '</div></div>';
    const dur = (n) => {
      n = Number(n) || 0;
      if (n < 60) return n + 's';
      if (n < 3600) return Math.floor(n / 60) + 'm';
      const h = n / 3600;
      return (h < 10 ? h.toFixed(1).replace(/\.0$/, '') : String(Math.round(h))) + 'h';
    };
    const metrics =
      '<div class="sc-usage-metrics">' +
      cell('Sessions', t.agent_sessions) +
      cell('Loops', t.model_loops) +
      cell('Tools', t.tool_calls) +
      cell('Messages', t.message_count) +
      cell('Wall', tracker.label_wall || dur(t.wall_time_s)) +
      cell('Model', dur(t.model_time_s)) +
      cell('Context', tracker.label_context || fmtTokens(t.last_context_tokens)) +
      cell('Est. in', tracker.label_input || fmtTokens(t.estimated_input_tokens)) +
      cell('Est. out', tracker.label_output || fmtTokens(t.estimated_output_tokens)) +
      '</div>';
    const days = Array.isArray(tracker.daily) ? tracker.daily : [];
    let chart = '';
    if (days.length >= 2) {
      const max = Math.max(1, ...days.map((d) => Number(d.estimated_input_tokens) || 0));
      chart =
        '<div class="sc-usage-chart">' +
        days.map((d) => {
          const v = Number(d.estimated_input_tokens) || 0;
          const h = Math.max(v > 0 ? 8 : 0, Math.round((v / max) * 72));
          const label = String(d.day || '').slice(5);
          return (
            '<div class="sc-usage-chart-col" title="' + escapeHtml(d.day || '') + '">' +
              '<div class="sc-usage-chart-val">' + escapeHtml(fmtTokens(v)) + '</div>' +
              '<div class="sc-usage-chart-bar"><span style="height:' + h + 'px"></span></div>' +
              '<div class="sc-usage-chart-day">' + escapeHtml(label) + '</div>' +
            '</div>'
          );
        }).join('') +
        '</div>';
    }
    return (
      '<div class="sc-usage-tracker">' +
        '<div class="sc-usage-products-label">Agent activity</div>' +
        metrics + chart +
      '</div>'
    );
  }

  function usageProductName(raw) {
    switch (raw) {
      case 'GrokBuild': return 'Build';
      case 'GrokChat': return 'Chat';
      case 'GrokImagine': return 'Imagine';
      default: return raw || 'Other';
    }
  }

  function escapeHtml(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function renderUsageDetail(data) {
    const detail = $('sc-usage-detail');
    const body = $('sc-usage-detail-body') || (detail && detail.querySelector('.sc-usage-detail-body'));
    const tierEl = $('sc-usage-tier');
    if (!body) return;

    if (!data || !data.ok) {
      const msg = (data && (data.message || data.error)) || 'Usage unavailable';
      if (detail) detail.classList.remove('sc-usage-warn', 'sc-usage-crit');
      if (tierEl) {
        tierEl.hidden = true;
        tierEl.textContent = '';
      }
      body.innerHTML = '<div class="sc-usage-error">' + escapeHtml(msg) + '</div>';
      return;
    }

    const pct = Number(data.usage_percent) || 0;
    const remaining = data.remaining_percent != null
      ? Number(data.remaining_percent)
      : Math.max(0, 100 - pct);
    const level = usageLevelClass(pct);
    if (detail) {
      detail.classList.toggle('sc-usage-warn', level === 'sc-usage-warn');
      detail.classList.toggle('sc-usage-crit', level === 'sc-usage-crit');
    }
    if (tierEl) {
      const tier = (data.subscription_tier || '').trim();
      tierEl.hidden = !tier;
      tierEl.textContent = tier;
    }

    const products = (data.products || [])
      .filter((p) => p.usage_percent != null && Number(p.usage_percent) > 0);
    let productsHtml = '';
    if (products.length) {
      productsHtml =
        '<div class="sc-usage-products">' +
        '<div class="sc-usage-products-label">By product</div>' +
        products.map((p) => {
          const pPct = Number(p.usage_percent) || 0;
          const pLevel = usageLevelClass(pPct);
          const w = Math.min(100, Math.max(pPct > 0 ? 2 : 0, pPct));
          return (
            '<div class="sc-usage-product ' + pLevel + '">' +
              '<div class="sc-usage-product-row">' +
                '<span class="sc-usage-product-name">' + escapeHtml(usageProductName(p.product)) + '</span>' +
                '<span class="sc-usage-product-pct">' + escapeHtml(formatUsagePercentLabel(pPct)) + '</span>' +
              '</div>' +
              '<div class="sc-usage-product-bar"><span style="width:' + w + '%"></span></div>' +
            '</div>'
          );
        }).join('') +
        '</div>';
    }

    const barW = Math.min(100, Math.max(pct > 0 ? 2 : 0, pct));
    body.innerHTML =
      '<div class="sc-usage-hero">' +
        '<div class="sc-usage-hero-left">' +
          '<span class="sc-usage-pct">' + escapeHtml(formatUsagePercent(pct)) + '</span>' +
          '<span class="sc-usage-pct-unit">%</span>' +
          '<span class="sc-usage-pct-label">used</span>' +
        '</div>' +
        '<div class="sc-usage-left">' + escapeHtml(formatUsagePercentLabel(remaining)) + ' left</div>' +
      '</div>' +
      '<div class="sc-usage-bar ' + level + '"><span style="width:' + barW + '%"></span></div>' +
      '<div class="sc-usage-reset">' + escapeHtml(formatUsageReset(data.reset_at, true)) + '</div>' +
      productsHtml +
      renderUsageTrackerHtml(data.tracker);
  }

  async function loadUsage(force) {
    const chip = $('sc-usage-chip');
    const refreshBtn = $('sc-usage-refresh');
    if (chip) chip.textContent = '…';
    if (refreshBtn) {
      refreshBtn.disabled = true;
      refreshBtn.textContent = '…';
    }
    try {
      const q = force ? '?refresh=1' : '';
      const data = await apiGet('/admin-system-chat-usage.php' + q);
      if (!data || !data.ok) {
        const msg = (data && (data.message || data.error)) || 'unavailable';
        if (chip) {
          chip.textContent = 'Usage —';
          chip.classList.remove('sc-usage-warn', 'sc-usage-crit');
          chip.title = msg;
        }
        renderUsageDetail(data);
        return;
      }
      const pct = Number(data.usage_percent) || 0;
      const tracker = data.tracker && data.tracker.ok ? data.tracker : null;
      const chipParts = [formatUsagePercentLabel(pct) + ' used'];
      if (tracker && tracker.label_wall) chipParts.push(tracker.label_wall);
      if (tracker && tracker.label_input && tracker.label_input !== '0') {
        chipParts.push(tracker.label_input + ' in');
      }
      chipParts.push(formatUsageReset(data.reset_at, false));
      const label = chipParts.join(' · ');
      if (chip) {
        chip.textContent = label;
        chip.classList.toggle('sc-usage-warn', pct >= 70 && pct < 90);
        chip.classList.toggle('sc-usage-crit', pct >= 90);
        chip.title = 'Weekly pool · tap to refresh\n' +
          (data.subscription_tier ? data.subscription_tier + '\n' : '') +
          'Reset: ' + (data.reset_at || 'unknown');
      }
      renderUsageDetail(data);
    } catch (e) {
      if (chip) {
        chip.textContent = 'Usage —';
        chip.title = e.message || 'usage failed';
      }
      renderUsageDetail({ ok: false, message: e.message || 'usage failed' });
    } finally {
      if (refreshBtn) {
        refreshBtn.disabled = false;
        refreshBtn.textContent = 'Refresh';
      }
    }
  }

  /**
   * Clear bridge Grok OAuth and start a fresh device-code login link (account switch).
   */
  async function logoutGrokAndShowLogin() {
    const btn = $('sc-usage-logout');
    const chip = $('sc-usage-chip');
    if (btn) {
      btn.disabled = true;
      btn.textContent = 'Signing out…';
    }
    try {
      const data = await apiPost('/admin-system-chat-login.php', { logout: true, action: 'logout' });
      const login = (data && data.login) || data || {};
      const url = (data && data.login_url)
        || login.verification_uri_complete
        || '';
      const code = (data && data.login_user_code) || login.user_code || '';
      const msg = (data && data.message)
        || login.message
        || 'Signed out. Open the login link to continue.';
      if (chip) {
        chip.textContent = 'Usage: re-login';
        chip.classList.remove('sc-usage-warn', 'sc-usage-crit');
        chip.title = msg;
      }
      let bodyHtml = '<div class="sc-usage-error">' + escapeHtml(msg) + '</div>';
      if (code) {
        bodyHtml += '<div class="sc-usage-user-code">Code: ' + escapeHtml(code) + '</div>';
      }
      if (url) {
        bodyHtml +=
          '<a class="sc-usage-login-link" href="' + escapeHtml(url) +
          '" target="_blank" rel="noopener noreferrer">Open Grok / xAI login</a>';
      }
      const body = $('sc-usage-detail-body');
      if (body) body.innerHTML = bodyHtml;
      if (url) {
        try { window.open(url, '_blank', 'noopener,noreferrer'); } catch (_) {}
      }
      // Poll usage after a short delay so approval can land.
      setTimeout(() => loadUsage(true), 8000);
    } catch (e) {
      const body = $('sc-usage-detail-body');
      if (body) {
        body.innerHTML = '<div class="sc-usage-error">' +
          escapeHtml(e.message || 'Logout failed') + '</div>';
      }
    } finally {
      if (btn) {
        btn.disabled = false;
        btn.textContent = 'Log out & get login link';
      }
    }
  }

  function effortsForCachedModel(modelId) {
    const row = (cachedModels || []).find((m) => m && m.id === modelId);
    if (row && Array.isArray(row.reasoning_efforts) && row.reasoning_efforts.length) {
      return row.reasoning_efforts.map((e) => String(e).toLowerCase());
    }
    const real = String(modelId || '').replace(/^(gb:|grok:)/, '');
    const match = real.toLowerCase().match(/^grok-(\d+)(?:\.(\d+))?/);
    if (match) {
      const major = parseInt(match[1], 10);
      const minor = parseInt(match[2] || '0', 10);
      if (major > 4 || (major === 4 && minor >= 6)) return ['low', 'medium', 'high', 'xhigh'];
    }
    return ['low', 'medium', 'high'];
  }

  function defaultEffortForCachedModel(modelId) {
    const row = (cachedModels || []).find((m) => m && m.id === modelId);
    const advertised = row && row.default_reasoning_effort
      ? String(row.default_reasoning_effort).toLowerCase()
      : '';
    const allowed = effortsForCachedModel(modelId);
    if (advertised && allowed.includes(advertised)) return advertised;
    return allowed.includes('xhigh') ? 'xhigh' : 'high';
  }

  function effortMap() {
    try {
      const raw = JSON.parse(localStorage.getItem('gp_sc_effort_by_model') || '{}');
      return raw && typeof raw === 'object' ? raw : {};
    } catch (_) {
      return {};
    }
  }

  function persistEffort(modelId, effort) {
    if (!effort) return;
    localStorage.setItem('gp_sc_effort', effort);
    if (!modelId) return;
    const map = effortMap();
    map[modelId] = effort;
    localStorage.setItem('gp_sc_effort_by_model', JSON.stringify(map));
  }

  function fillEffortSelect(modelId, preferred) {
    const sel = $('sc-effort-select');
    const hint = $('sc-effort-hint');
    if (!sel) return;
    const allowed = effortsForCachedModel(modelId);
    const map = effortMap();
    let chosen = String(preferred || map[modelId] || localStorage.getItem('gp_sc_effort') || '').toLowerCase();
    if (!allowed.includes(chosen)) chosen = defaultEffortForCachedModel(modelId);
    sel.innerHTML = '';
    allowed.forEach((effort) => {
      const opt = document.createElement('option');
      opt.value = effort;
      opt.textContent = effort;
      if (effort === chosen) opt.selected = true;
      sel.appendChild(opt);
    });
    sel.value = chosen;
    persistEffort(modelId, chosen);
    if (hint) {
      hint.textContent = allowed.includes('xhigh')
        ? 'How hard this model thinks. xhigh is grok-4.6+ only.'
        : 'How hard this model thinks. This model does not support xhigh.';
    }
  }

  async function loadModels() {
    const data = await apiGet('/admin-system-chat-models.php');
    wsToken = data.ws_token || '';
    wsPath = data.ws_path || '/grokpot-ws/';
    bridgeHealthy = data.bridge_healthy !== false;
    const warn = $('sc-bridge-warn');
    if (warn) warn.classList.toggle('hidden', bridgeHealthy);
    const sel = $('sc-model-select');
    if (!sel) return;
    sel.innerHTML = '';
    const models = (data.models || []).filter((m) => m.provider === 'grok-build' || (m.id && String(m.id).startsWith('gb:')));
    cachedModels = models;
    const ids = models.map((m) => m.id);
    let preferred = localStorage.getItem('gp_sc_model') || data.selected || data.default_model || '';
    // Migrate legacy Cursor / "auto" prefs to Grok Build
    if (!preferred || preferred === 'auto' || !ids.includes(preferred)) {
      preferred = data.selected || data.default_model || ids[0] || '';
      if (preferred) localStorage.setItem('gp_sc_model', preferred);
    }
    models.forEach((m) => {
      const opt = document.createElement('option');
      opt.value = m.id;
      opt.textContent = m.name || m.id.replace(/^gb:/, '');
      if (m.id === preferred) opt.selected = true;
      sel.appendChild(opt);
    });
    if (preferred && sel.value !== preferred && ids.includes(preferred)) {
      sel.value = preferred;
    }
    fillEffortSelect(sel.value, data.selected_reasoning_effort || '');
    if (!ws) connectWS();
  }

  async function discardEmptySession(id) {
    if (!id) return;
    try {
      await fetch(API + '/admin-system-chat-sessions.php?id=' + encodeURIComponent(id), {
        method: 'DELETE',
        credentials: 'same-origin',
      });
    } catch (_) {}
  }

  async function loadSessions() {
    const data = await apiGet('/admin-system-chat-sessions.php');
    const list = $('sc-session-list');
    if (!list) return;
    list.innerHTML = '';
    visibleSessions(data.sessions).forEach((s) => {
      const el = document.createElement('div');
      el.className = 'sc-session-item' + (s.id === currentSessionId ? ' active' : '');
      const bits = [];
      if (s.input_tokens) bits.push(fmtTokens(s.input_tokens) + ' in');
      if (s.last_context_tokens) bits.push(fmtTokens(s.last_context_tokens) + ' ctx');
      const meta = bits.length
        ? '<span class="sc-session-meta">' + esc(bits.join(' · ')) + '</span>'
        : '';
      el.innerHTML = '<span class="sc-session-main"><span class="sc-title">' + esc(s.title) +
        '</span>' + meta + '</span><button type="button" class="sc-del" title="Delete">×</button>';
      el.querySelector('.sc-title').onclick = () => switchSession(s.id, s.title);
      el.querySelector('.sc-del').onclick = (e) => {
        e.stopPropagation();
        deleteSession(s.id);
      };
      list.appendChild(el);
    });
    const title = $('sc-topbar-title');
    if (title && !currentSessionId) title.textContent = 'New Chat';
  }

  async function switchSession(id, title, opts) {
    opts = opts || {};
    // Refuse to open plugin / Live DJ bridge sessions in main Chat.
    if (isInternalAppSessionTitle(title)) {
      return;
    }
    const prevId = currentSessionId;
    if (prevId && prevId !== id && !sessionHasMessages) {
      await discardEmptySession(prevId);
    }
    currentSessionId = id;
    localStorage.setItem('gp_sc_session', id);
    $('sc-topbar-title').textContent = title;
    closePopovers();
    clearMessages();
    const data = await apiGet('/admin-system-chat-messages.php?session_id=' + encodeURIComponent(id));
    let messages = data.messages || [];
    const resumeStreaming = !opts.skipStreamResume && sessionStorage.getItem('gp_sc_streaming') === id;
    let streamingMsg = resumeStreaming ? findStreamingMessage(messages) : null;
    if (streamingMsg) messages = messages.slice(0, -1);
    sessionHasMessages = messages.length > 0 || !!streamingMsg;
    if (messages.length) $('sc-welcome').style.display = 'none';
    else $('sc-welcome').style.display = '';
    messages.forEach((m) => {
      appendMessage(m.role, m.content, m.metadata, m.id, m.excluded_from_context == 1, m.created_at);
    });
    if (resumeStreaming) {
      await resumeInterruptedStream(id);
    } else if (streamingMsg) {
      restoreStreamBubbleFromMessage(streamingMsg);
    }
    pinScrollToBottom = true;
    scrollBottom(true);
    loadSessions();
  }

  async function deleteSession(id) {
    await fetch(API + '/admin-system-chat-sessions.php?id=' + encodeURIComponent(id), {
      method: 'DELETE',
      credentials: 'same-origin',
    });
    if (id === currentSessionId) {
      currentSessionId = null;
      sessionHasMessages = false;
      localStorage.removeItem('gp_sc_session');
      $('sc-topbar-title').textContent = 'New Chat';
      clearMessages();
      $('sc-welcome').style.display = '';
    }
    loadSessions();
  }

  async function newChat() {
    if (currentSessionId && !sessionHasMessages) {
      await discardEmptySession(currentSessionId);
    }
    const data = await apiPost('/admin-system-chat-sessions.php', {});
    currentSessionId = data.id;
    sessionHasMessages = false;
    localStorage.setItem('gp_sc_session', data.id);
    $('sc-topbar-title').textContent = data.title;
    clearMessages();
    $('sc-welcome').style.display = '';
    loadSessions();
    $('sc-prompt').focus();
  }

  function getActiveNotes() {
    return dbNotes.filter((n) => n.enabled == 1).map((n) => n.note_text);
  }

  async function loadNotes() {
    const data = await apiPost('/admin-system-chat-notes.php', { action: 'list' });
    dbNotes = data.notes || [];
    renderNotes();
    updateNotesBadge();
  }

  function renderNotes() {
    const list = $('sc-notes-list');
    if (!list) return;
    list.innerHTML = '';
    dbNotes.forEach((note) => {
      const el = document.createElement('div');
      el.className = 'sc-note-item' + (note.enabled == 1 ? '' : ' disabled');
      el.innerHTML =
        '<button type="button" class="sc-note-toggle text-xs">✓</button>' +
        '<span class="flex-1 sc-note-text cursor-pointer">' + esc(note.note_text) + '</span>' +
        '<button type="button" class="sc-note-edit text-xs text-[#9ca3af]" title="Edit">✎</button>' +
        '<button type="button" class="sc-note-del text-xs text-red-400">×</button>';
      el.querySelector('.sc-note-text').onclick = () => startNoteEdit(note, el);
      el.querySelector('.sc-note-edit').onclick = () => startNoteEdit(note, el);
      el.querySelector('.sc-note-toggle').onclick = async () => {
        note.enabled = note.enabled == 1 ? 0 : 1;
        await apiPost('/admin-system-chat-notes.php', { action: 'toggle', note_id: note.id, enabled: note.enabled == 1 });
        renderNotes();
        updateNotesBadge();
      };
      el.querySelector('.sc-note-del').onclick = async () => {
        await apiPost('/admin-system-chat-notes.php', { action: 'delete', note_id: note.id });
        dbNotes = dbNotes.filter((n) => n.id !== note.id);
        renderNotes();
        updateNotesBadge();
      };
      list.appendChild(el);
    });
  }

  function updateNotesBadge() {
    const n = dbNotes.filter((x) => x.enabled == 1).length;
    const b = $('sc-notes-badge');
    if (b) {
      b.textContent = n ? String(n) : '';
      b.classList.toggle('hidden', !n);
    }
  }

  async function sendMessage() {
    const input = $('sc-prompt');
    const text = (input && input.value.trim()) || '';
    if (!text || isStreaming) return;

    if (!currentSessionId) {
      const s = await apiPost('/admin-system-chat-sessions.php', {});
      currentSessionId = s.id;
      localStorage.setItem('gp_sc_session', s.id);
    }
    sessionHasMessages = true;

    $('sc-welcome').style.display = 'none';
    const userEl = appendMessage('user', text);
    pinScrollToBottom = true;
    scrollBottom(true);
    input.value = '';
    input.style.height = '';
    localStorage.removeItem('gp_sc_draft');

    try {
      const saved = await apiPost('/admin-system-chat-messages.php', {
        session_id: currentSessionId,
        role: 'user',
        content: text,
      });
      if (saved.id && userEl) userEl.setAttribute('data-msg-id', saved.id);
    } catch (e) {
      console.warn(e);
    }

    let history = [];
    if (useHistory) {
      try {
        const h = await apiGet('/admin-system-chat-messages.php?session_id=' + encodeURIComponent(currentSessionId));
        history = fitHistoryWindow(
          (h.messages || [])
            .filter((m) => {
              if (m.excluded_from_context && m.excluded_from_context !== 0) return false;
              if (m.role === 'assistant' && m.metadata) {
                const meta = typeof m.metadata === 'string' ? JSON.parse(m.metadata) : m.metadata;
                if (meta && meta.streaming) return false;
              }
              return true;
            })
            .slice(0, -1)
            .map((m) => {
              let content = m.content || '';
              if (m.role === 'assistant' && m.metadata) {
                const meta = typeof m.metadata === 'string' ? JSON.parse(m.metadata) : m.metadata;
                if (meta && meta.timeline && meta.timeline.length) {
                  const parts = [];
                  meta.timeline.forEach((seg) => {
                    if (seg.type === 'thinking') return;
                    if (seg.type === 'tool') {
                      const detail = String(seg.detail || '');
                      parts.push(
                        '[' + (seg.tool || '') + '] ' + (detail.length > 240 ? detail.slice(0, 240) + '…' : detail)
                      );
                    } else if (seg.type === 'text' && seg.content) {
                      parts.push(seg.content);
                    }
                  });
                  content = parts.join('\n\n');
                }
              }
              return { role: m.role, content };
            })
        );
      } catch (_) {}
    }

    isStreaming = true;
    hideMessageActions();
    sessionStorage.setItem('gp_sc_streaming', currentSessionId);
    streamMsgId = null;
    streamText = '';
    streamToolCount = 0;
    streamToolEvents = [];
    streamThinkingSummary = '';
    streamTimeline = [];
    streamStartTime = 0;
    showTyping();
    updateSendState();
    loadSessions();

    const model = $('sc-model-select') && $('sc-model-select').value;
    const notes = getActiveNotes();
    // Pull latest phone notification snapshots so Grok can answer "what's on my phone?"
    try {
      const notifRes = await apiGet('/devices.php?action=notifications&as_notes=1');
      if (notifRes && notifRes.ok && Array.isArray(notifRes.notes) && notifRes.notes.length) {
        notes.push(notifRes.notes.join('\n'));
      }
    } catch (_) { /* offline / no devices — ignore */ }
    const payload = { prompt: text, session_id: currentSessionId, model: model || '' };
    const effortEl = $('sc-effort-select');
    if (effortEl && effortEl.value) payload.reasoning_effort = effortEl.value;
    if (history.length) payload.history = history;
    if (notes.length) payload.notes = notes;

    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(payload));
    } else {
      removeTyping();
      appendMessage('assistant', 'Connection lost. Check that GrokifyOS bridge is running (check GROKIFY_BRIDGE_URL).');
      isStreaming = false;
      updateSendState();
    }
  }

  function updateSendState() {
    const btn = $('sc-send-btn');
    const input = $('sc-prompt');
    if (btn) btn.disabled = isStreaming || !(input && input.value.trim());
  }

  function closePopovers() {
    document.querySelectorAll('.sc-wrap.open').forEach((w) => w.classList.remove('open'));
  }

  function toggleWrap(wrapId) {
    const wrap = $(wrapId);
    if (!wrap) return;
    const open = wrap.classList.contains('open');
    closePopovers();
    if (!open) wrap.classList.add('open');
  }

  let auditSinceId = 0;
  let auditEventSource = null;

  function startAuditStream() {
    stopAuditStream();
    const cat = ($('sc-log-filter-cat') && $('sc-log-filter-cat').value) || '';
    const lvl = ($('sc-log-filter-level') && $('sc-log-filter-level').value) || '';
    let url = API + '/admin-system-chat-audit.php?action=stream&since_id=' + auditSinceId;
    if (cat) url += '&category=' + encodeURIComponent(cat);
    if (lvl) url += '&level=' + encodeURIComponent(lvl);
    auditEventSource = new EventSource(url);
    auditEventSource.addEventListener('audit', (e) => {
      try {
        const ev = JSON.parse(e.data);
        appendAuditLine(ev);
        auditSinceId = Math.max(auditSinceId, parseInt(ev.id, 10) || 0);
      } catch (_) {}
    });
    auditEventSource.onerror = () => {};
  }

  function stopAuditStream() {
    if (auditEventSource) {
      auditEventSource.close();
      auditEventSource = null;
    }
  }

  function appendAuditLine(ev) {
    const body = $('sc-log-body');
    if (!body) return;
    const div = document.createElement('div');
    div.className = 'sc-log-line level-' + (ev.level || 'info');
    const t = (ev.created_at || '').replace('T', ' ').substring(0, 19);
    div.textContent = '[' + t + '] [' + ev.category + '] ' + ev.message;
    body.appendChild(div);
    while (body.children.length > 400) body.removeChild(body.firstChild);
    body.scrollTop = body.scrollHeight;
  }

  function startNoteEdit(note, itemEl) {
    const textEl = itemEl.querySelector('.sc-note-text');
    if (!textEl || itemEl.classList.contains('editing')) return;
    itemEl.classList.add('editing');
    const original = note.note_text;
    const input = document.createElement('input');
    input.type = 'text';
    input.className = 'flex-1 text-xs bg-[#0f1115] border border-[#272b31] rounded px-2 py-1 text-white';
    input.value = original;
    input.maxLength = 500;
    textEl.replaceWith(input);
    input.focus();
    const finish = async (save) => {
      if (save) {
        const trimmed = input.value.trim();
        if (trimmed && trimmed !== original) {
          await apiPost('/admin-system-chat-notes.php', { action: 'edit', note_id: note.id, note_text: trimmed });
          note.note_text = trimmed;
        }
      }
      loadNotes();
    };
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        finish(true);
      } else if (e.key === 'Escape') finish(false);
    });
    input.addEventListener('blur', () => finish(true));
  }

  function initMsgActions() {
    const messagesEl = $('sc-messages');
    const msgActionsEl = $('sc-msg-actions');
    const scRoot = $('sc-root');
    if (!messagesEl || !msgActionsEl) return;

    const MENU_W = 36;
    const MENU_H = 96;

    function hideMsgActions() {
      msgActionsEl.classList.remove('visible');
      hoveredMsgEl = null;
    }
    hideMessageActions = hideMsgActions;

    function isHoverableMsg(msgEl) {
      if (!msgEl || msgEl.id === 'sc-typing' || isStreaming) return false;
      if (streamContainer && msgEl === streamContainer) return false;
      return true;
    }

    function positionMsgActions(msgEl, mouseY) {
      const bubble = msgEl.querySelector('.sc-msg-bubble');
      if (!bubble) return;
      const bubbleRect = bubble.getBoundingClientRect();
      const bounds = (scRoot || messagesEl).getBoundingClientRect();
      const y = typeof mouseY === 'number' ? mouseY : actionMouseY || bubbleRect.top + 12;
      const menuH = msgActionsEl.offsetHeight || MENU_H;

      let top = y - menuH / 2;
      top = Math.max(bubbleRect.top, Math.min(top, bubbleRect.bottom - menuH));
      top = Math.max(bounds.top + 4, Math.min(top, bounds.bottom - menuH - 4));

      const isUser = msgEl.classList.contains('user');
      let left = isUser ? bubbleRect.left - MENU_W - 6 : bubbleRect.right + 6;
      left = Math.max(bounds.left + 4, Math.min(left, bounds.right - MENU_W - 4));
      if (left + MENU_W > window.innerWidth - 4) left = bubbleRect.left - MENU_W - 6;
      if (left < bounds.left + 4) left = bounds.left + 4;

      msgActionsEl.style.top = Math.round(top) + 'px';
      msgActionsEl.style.left = Math.round(left) + 'px';
    }

    messagesEl.addEventListener('mouseover', (e) => {
      if (isStreaming) {
        hideMsgActions();
        return;
      }
      const msgEl = e.target.closest('.sc-msg');
      if (!isHoverableMsg(msgEl)) return;
      if (msgEl === hoveredMsgEl) return;
      hoveredMsgEl = msgEl;
      actionMouseY = e.clientY;
      positionMsgActions(msgEl, e.clientY);
      msgActionsEl.classList.add('visible');
    });

    messagesEl.addEventListener('mousemove', (e) => {
      if (!hoveredMsgEl || isStreaming) return;
      actionMouseY = e.clientY;
      positionMsgActions(hoveredMsgEl, e.clientY);
    });

    messagesEl.addEventListener('mouseleave', () => {
      setTimeout(() => {
        if (!msgActionsEl.matches(':hover')) hideMsgActions();
      }, 120);
    });

    msgActionsEl.addEventListener('mouseleave', hideMsgActions);

    messagesEl.addEventListener('scroll', () => {
      if (hoveredMsgEl && !isStreaming) positionMsgActions(hoveredMsgEl, actionMouseY);
    });

    messagesEl.addEventListener('click', (e) => {
      const header = e.target.closest('.sc-thinking-header');
      if (header) {
        const block = header.closest('.sc-thinking-block');
        if (block && block.classList.contains('done')) {
          const content = block.querySelector('.sc-thinking-content');
          if (content) {
            content.classList.toggle('collapsed');
            setThinkingHeaderLabel(header, content.classList.contains('collapsed'));
          }
        }
        return;
      }
    });

    msgActionsEl.addEventListener('click', (e) => {
      const btn = e.target.closest('.sc-msg-action-btn');
      if (!btn || !hoveredMsgEl) return;
      const action = btn.getAttribute('data-action');
      const msgId = hoveredMsgEl.getAttribute('data-msg-id');

      if (action === 'copy') {
        const bubble = hoveredMsgEl.querySelector('.sc-msg-bubble');
        const text = bubble ? bubble.innerText : '';
        navigator.clipboard.writeText(text).catch(() => {});
      } else if (action === 'exclude') {
        const isExcluded = hoveredMsgEl.classList.contains('excluded');
        hoveredMsgEl.classList.toggle('excluded');
        if (msgId) {
          apiPost('/admin-system-chat-messages.php', {
            action: 'toggle_exclude',
            message_id: parseInt(msgId, 10),
            excluded: !isExcluded,
          }).catch(() => {});
        }
      } else if (action === 'delete') {
        hoveredMsgEl.remove();
        hideMsgActions();
        if (msgId) {
          apiPost('/admin-system-chat-messages.php', {
            action: 'delete',
            message_id: parseInt(msgId, 10),
          }).catch(() => {});
        }
      }
    });
  }

  function initUI() {
    bindMessagesScroll();
    const restartAudit = () => {
      $('sc-log-body').innerHTML = '';
      auditSinceId = 0;
      startAuditStream();
    };
    $('sc-log-filter-level') && $('sc-log-filter-level').addEventListener('change', restartAudit);
    $('sc-log-filter-cat') && $('sc-log-filter-cat').addEventListener('change', restartAudit);

    $('sc-open-log') &&
      $('sc-open-log').addEventListener('click', () => {
        $('sc-log-panel').classList.add('open');
        restartAudit();
      });
    $('sc-close-log') &&
      $('sc-close-log').addEventListener('click', () => {
        $('sc-log-panel').classList.remove('open');
        stopAuditStream();
      });

    $('sc-history-btn') && $('sc-history-btn').addEventListener('click', () => toggleWrap('sc-history-wrap'));
    $('sc-notes-btn') && $('sc-notes-btn').addEventListener('click', () => toggleWrap('sc-notes-wrap'));
    $('sc-settings-btn') && $('sc-settings-btn').addEventListener('click', () => {
      toggleWrap('sc-settings-wrap');
      if ($('sc-settings-wrap') && $('sc-settings-wrap').classList.contains('open')) {
        loadUsage(false);
        loadWorkDir();
      }
    });
    $('sc-usage-chip') &&
      $('sc-usage-chip').addEventListener('click', () => {
        loadUsage(true);
      });
    $('sc-usage-refresh') &&
      $('sc-usage-refresh').addEventListener('click', (e) => {
        e.preventDefault();
        e.stopPropagation();
        loadUsage(true);
      });
    $('sc-usage-logout') &&
      $('sc-usage-logout').addEventListener('click', (e) => {
        e.preventDefault();
        e.stopPropagation();
        logoutGrokAndShowLogin();
      });
    $('sc-new-chat') && $('sc-new-chat').addEventListener('click', () => newChat());

    bindWakeLockListeners();
    bindChatSettingsUi();
    applyChatVisibility();
    syncChatSettingsUi();

    const prompt = $('sc-prompt');
    if (prompt) {
      const draft = localStorage.getItem('gp_sc_draft');
      if (draft) {
        prompt.value = draft;
        updateSendState();
      }
      prompt.addEventListener('input', () => {
        prompt.style.height = 'auto';
        prompt.style.height = Math.min(prompt.scrollHeight, 160) + 'px';
        localStorage.setItem('gp_sc_draft', prompt.value);
        updateSendState();
      });
      prompt.addEventListener('keydown', (e) => {
        if (e.key !== 'Enter') return;
        if (enterForNewline) {
          // Enter = newline; Ctrl/Cmd+Enter sends (send button also works)
          if (e.ctrlKey || e.metaKey) {
            e.preventDefault();
            sendMessage();
          }
        } else if (!e.shiftKey) {
          e.preventDefault();
          sendMessage();
        }
      });
    }

    $('sc-send-btn') && $('sc-send-btn').addEventListener('click', sendMessage);

    $('sc-model-select') &&
      $('sc-model-select').addEventListener('change', async () => {
        const v = $('sc-model-select').value;
        localStorage.setItem('gp_sc_model', v);
        fillEffortSelect(v, (effortMap()[v] || ''));
        const effort = ($('sc-effort-select') && $('sc-effort-select').value) || '';
        try {
          await apiPost('/admin-system-chat-models.php', { model: v, reasoning_effort: effort });
        } catch (_) {}
      });

    $('sc-effort-select') &&
      $('sc-effort-select').addEventListener('change', async () => {
        const model = ($('sc-model-select') && $('sc-model-select').value) || '';
        const effort = $('sc-effort-select').value;
        persistEffort(model, effort);
        try {
          await apiPost('/admin-system-chat-models.php', { model, reasoning_effort: effort });
        } catch (_) {}
      });

    $('sc-notes-add') &&
      $('sc-notes-add').addEventListener('click', async () => {
        const inp = $('sc-notes-input');
        const text = (inp && inp.value.trim()) || '';
        if (!text) return;
        await apiPost('/admin-system-chat-notes.php', { action: 'create', note_text: text });
        inp.value = '';
        loadNotes();
      });

    document.addEventListener('click', (e) => {
      if (!e.target.closest('.sc-wrap')) closePopovers();
    });

    initMsgActions();
    updatePlaceholder();
  }

  function updatePlaceholder() {
    const p = $('sc-prompt');
    if (!p) return;
    p.placeholder = enterForNewline
      ? 'Message… (Enter = new line · Ctrl+Enter or send)'
      : 'Message… (Enter to send · Shift+Enter for newline)';
  }

  function applyChatVisibility() {
    const root = $('sc-root');
    if (!root) return;
    root.classList.toggle('sc-hide-tools', !showTools);
    root.classList.toggle('sc-hide-thoughts', !showThoughts);
  }

  function setUseHistory(on) {
    useHistory = !!on;
    localStorage.setItem('gos_sc_use_history', useHistory);
    syncChatSettingsUi();
  }

  async function setKeepScreenOn(on) {
    keepScreenOn = !!on;
    localStorage.setItem('gos_sc_keep_awake', keepScreenOn);
    syncChatSettingsUi();
    if (keepScreenOn) {
      await requestWakeLock();
    } else {
      await releaseWakeLock();
    }
  }

  function setEnterForNewline(on) {
    enterForNewline = !!on;
    localStorage.setItem('gos_sc_enter_for_newline', enterForNewline);
    // Keep legacy key in sync for older tabs / caches
    localStorage.setItem('gos_sc_ctrl_enter', enterForNewline);
    updatePlaceholder();
    syncChatSettingsUi();
  }

  function setShowTools(on) {
    showTools = !!on;
    localStorage.setItem('gos_sc_show_tools', showTools);
    applyChatVisibility();
    syncChatSettingsUi();
  }

  function setShowThoughts(on) {
    showThoughts = !!on;
    localStorage.setItem('gos_sc_show_thoughts', showThoughts);
    applyChatVisibility();
    syncChatSettingsUi();
  }

  function syncChatSettingsUi() {
    const ctx = $('sc-context-toggle');
    if (ctx) ctx.classList.toggle('active', useHistory);
    updateKeepAwakeButton();

    const setHistory = $('sc-set-history');
    if (setHistory) setHistory.checked = useHistory;
    const setAwake = $('sc-set-keep-awake');
    if (setAwake) setAwake.checked = keepScreenOn;
    const setEnter = $('sc-set-enter-newline');
    if (setEnter) setEnter.checked = enterForNewline;
    const setTools = $('sc-set-show-tools');
    if (setTools) setTools.checked = showTools;
    const setThoughts = $('sc-set-show-thoughts');
    if (setThoughts) setThoughts.checked = showThoughts;

    const enterHint = $('sc-set-enter-hint');
    if (enterHint) {
      enterHint.textContent = enterForNewline
        ? 'Enter inserts a new line; send with the button or Ctrl+Enter'
        : 'Enter sends the message; Shift+Enter for a new line';
    }
    const toolsHint = $('sc-set-tools-hint');
    if (toolsHint) {
      toolsHint.textContent = showTools
        ? 'Tool call cards appear in the chat transcript'
        : 'Tool call cards are hidden (still run normally)';
    }
    const thoughtsHint = $('sc-set-thoughts-hint');
    if (thoughtsHint) {
      thoughtsHint.textContent = showThoughts
        ? 'Thinking / thought cards appear in the chat transcript'
        : 'Thinking cards are hidden (model still thinks)';
    }
  }

  function bindChatSettingsUi() {
    $('sc-context-toggle') &&
      $('sc-context-toggle').addEventListener('click', () => {
        setUseHistory(!useHistory);
      });

    $('sc-keep-awake') &&
      $('sc-keep-awake').addEventListener('click', () => {
        setKeepScreenOn(!keepScreenOn);
      });

    $('sc-set-history') &&
      $('sc-set-history').addEventListener('change', (e) => {
        setUseHistory(e.target.checked);
      });
    $('sc-set-keep-awake') &&
      $('sc-set-keep-awake').addEventListener('change', (e) => {
        setKeepScreenOn(e.target.checked);
      });
    $('sc-set-enter-newline') &&
      $('sc-set-enter-newline').addEventListener('change', (e) => {
        setEnterForNewline(e.target.checked);
      });
    $('sc-set-show-tools') &&
      $('sc-set-show-tools').addEventListener('change', (e) => {
        setShowTools(e.target.checked);
      });
    $('sc-set-show-thoughts') &&
      $('sc-set-show-thoughts').addEventListener('change', (e) => {
        setShowThoughts(e.target.checked);
      });

    bindWorkDirUi();
  }

  let workDirState = {
    path: '',
    defaultPath: '',
    isDefault: true,
    browsePath: '',
    browseParent: null,
    browserOpen: false,
  };

  function setWorkDirStatus(msg, isError) {
    const el = $('sc-workdir-status');
    if (!el) return;
    el.textContent = msg || '';
    el.style.color = isError ? '#f87171' : '#9ca3af';
  }

  function applyWorkDirToUi(data) {
    if (!data) return;
    workDirState.path = data.path || '';
    workDirState.defaultPath = data.default_path || '';
    workDirState.isDefault = !!data.is_default;
    const cur = $('sc-workdir-current');
    if (cur) {
      cur.textContent = workDirState.path || '—';
    }
    const inp = $('sc-workdir-input');
    if (inp && document.activeElement !== inp) {
      inp.value = workDirState.path || '';
    }
    const hint = $('sc-workdir-hint');
    if (hint) {
      hint.textContent = workDirState.isDefault
        ? 'Default — GrokifyOS install workspace'
        : 'Custom project directory on the bridge server';
    }
  }

  async function loadWorkDir() {
    try {
      const data = await apiGet('/admin-system-chat-workdir.php');
      if (data && data.ok) {
        applyWorkDirToUi(data);
        setWorkDirStatus('');
      } else {
        setWorkDirStatus((data && data.error) || 'Could not load working directory', true);
      }
    } catch (e) {
      setWorkDirStatus(e.message || 'Could not load working directory', true);
    }
  }

  async function setWorkDir(path, reset) {
    setWorkDirStatus(reset ? 'Resetting…' : 'Saving…');
    try {
      const body = reset ? { reset: true } : { path: path };
      const data = await apiPost('/admin-system-chat-workdir.php', body);
      if (data && data.ok) {
        applyWorkDirToUi(data);
        setWorkDirStatus(reset ? 'Reset to default' : 'Saved — new chats use this folder');
      } else {
        setWorkDirStatus((data && (data.message || data.error)) || 'Save failed', true);
      }
    } catch (e) {
      setWorkDirStatus(e.message || 'Save failed', true);
    }
  }

  async function browseWorkDir(path) {
    try {
      const q =
        '/admin-system-chat-workdir.php?list=1' +
        (path ? '&path=' + encodeURIComponent(path) : '');
      const data = await apiGet(q);
      if (!data || !data.ok) {
        setWorkDirStatus((data && (data.message || data.error)) || 'Browse failed', true);
        return;
      }
      workDirState.browsePath = data.path || '';
      workDirState.browseParent = data.parent || null;
      const pathEl = $('sc-workdir-browse-path');
      if (pathEl) pathEl.textContent = workDirState.browsePath;
      const list = $('sc-workdir-list');
      if (list) {
        list.innerHTML = '';
        const entries = data.entries || [];
        if (!entries.length) {
          const empty = document.createElement('div');
          empty.className = 'sc-setting-sub';
          empty.style.padding = '0.45rem 0.55rem';
          empty.textContent = 'No subfolders';
          list.appendChild(empty);
        } else {
          entries.forEach((ent) => {
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'sc-workdir-item';
            btn.textContent = '📁 ' + (ent.name || '');
            btn.addEventListener('click', () => browseWorkDir(ent.path));
            list.appendChild(btn);
          });
        }
      }
      const browser = $('sc-workdir-browser');
      if (browser) browser.classList.remove('hidden');
      workDirState.browserOpen = true;
      const inp = $('sc-workdir-input');
      if (inp) inp.value = workDirState.browsePath;
    } catch (e) {
      setWorkDirStatus(e.message || 'Browse failed', true);
    }
  }

  function bindWorkDirUi() {
    if (!$('sc-workdir-current')) return;
    $('sc-workdir-apply') &&
      $('sc-workdir-apply').addEventListener('click', () => {
        const v = ($('sc-workdir-input') && $('sc-workdir-input').value.trim()) || '';
        if (!v) {
          setWorkDirStatus('Enter an absolute path', true);
          return;
        }
        setWorkDir(v, false);
      });
    $('sc-workdir-reset') &&
      $('sc-workdir-reset').addEventListener('click', () => setWorkDir('', true));
    $('sc-workdir-browse') &&
      $('sc-workdir-browse').addEventListener('click', () => {
        if (workDirState.browserOpen) {
          const browser = $('sc-workdir-browser');
          if (browser) browser.classList.add('hidden');
          workDirState.browserOpen = false;
          return;
        }
        browseWorkDir(workDirState.path || workDirState.defaultPath || '');
      });
    $('sc-workdir-up') &&
      $('sc-workdir-up').addEventListener('click', () => {
        if (workDirState.browseParent) browseWorkDir(workDirState.browseParent);
      });
    $('sc-workdir-use') &&
      $('sc-workdir-use').addEventListener('click', () => {
        const p = workDirState.browsePath || '';
        if (!p) return;
        setWorkDir(p, false);
      });
    $('sc-workdir-input') &&
      $('sc-workdir-input').addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
          e.preventDefault();
          $('sc-workdir-apply') && $('sc-workdir-apply').click();
        }
      });
  }

  /** True when chat UI is the active view (admin tab or Grokify panel). */
  function isSystemChatSectionVisible() {
    const adminSec = $('section-system-chat');
    if (adminSec) {
      return !adminSec.classList.contains('hidden');
    }
    // Grokify dashboard: chat panel uses .active, not #section-system-chat
    const panel = $('panel-chat');
    if (panel) {
      return panel.classList.contains('active');
    }
    const root = $('sc-root');
    if (!root) return false;
    try {
      const st = global.getComputedStyle(root);
      return st.display !== 'none' && st.visibility !== 'hidden';
    } catch (_) {
      return true;
    }
  }

  function wakeLockApiSupported() {
    return typeof navigator !== 'undefined' && 'wakeLock' in navigator && typeof navigator.wakeLock.request === 'function';
  }

  function clearWakeLockRetry() {
    if (wakeLockRetryTimer) {
      clearTimeout(wakeLockRetryTimer);
      wakeLockRetryTimer = null;
    }
  }

  function scheduleWakeLockRetry(ms) {
    clearWakeLockRetry();
    wakeLockRetryTimer = setTimeout(() => {
      wakeLockRetryTimer = null;
      requestWakeLock();
    }, ms || 8000);
  }

  function ensureSilentVideo() {
    if (silentVideoEl) return silentVideoEl;
    const v = document.createElement('video');
    v.setAttribute('playsinline', '');
    v.setAttribute('webkit-playsinline', '');
    v.setAttribute('muted', '');
    v.setAttribute('loop', '');
    v.muted = true;
    v.defaultMuted = true;
    v.loop = true;
    v.playsInline = true;
    v.autoplay = true;
    v.setAttribute('aria-hidden', 'true');
    v.style.cssText =
      'position:fixed;width:2px;height:2px;left:0;top:0;opacity:0.01;pointer-events:none;z-index:-1';

    // Prefer live canvas stream (widely supported); fall back to tiny MP4 data URI
    try {
      if (typeof HTMLCanvasElement !== 'undefined' && HTMLCanvasElement.prototype.captureStream) {
        const c = document.createElement('canvas');
        c.width = 2;
        c.height = 2;
        const ctx = c.getContext('2d');
        const paint = () => {
          if (!silentVideoEl) return;
          ctx.fillStyle = '#000';
          ctx.fillRect(0, 0, 2, 2);
          if (!silentVideoEl.paused) {
            silentVideoEl._raf = requestAnimationFrame(paint);
          }
        };
        v.srcObject = c.captureStream(1);
        v._paint = paint;
      }
    } catch (_) {}

    if (!v.srcObject) {
      // Tiny silent WebM (NoSleep-style) when canvas captureStream is unavailable
      v.src =
        'data:video/webm;base64,GkXfo0AgQoaBAUL3gQFC8oEEQvOBCEKCQAR3ZWJtQoeBAkKFgQIYU4BnQI0VSalmQCgq17FAAw9CQE2AQAZ3aGFtbXlXQUAGd2hhbW15RIlACECPQAAAAAAAFlSua0AxrkAu14EBY8WBAZyBACK1nEADdW5khkAFVl9WUDglhooHTBMUDBAiVFHaAiEhVQECcEOAAAADAAAAAAAA8nAAAAAAAAAAAAAAAAAAAAAAAOPjgQ3TcgEQAAAAAAAk52eBAAAAAADFUAAAAG//7+qXgQNxTbnQQ11r+hBFK4EBAO1AQAAAAAAADH0IfhE17GDD0JATEE9li4EBI+ODgghjA1GgAAABAAABhgAAAfQAAAAAAAQ';
    }

    document.body.appendChild(v);
    silentVideoEl = v;
    return v;
  }

  async function startSilentVideoFallback() {
    try {
      const v = ensureSilentVideo();
      if (typeof v._paint === 'function') {
        try {
          v._paint();
        } catch (_) {}
      }
      if (v.paused) {
        const p = v.play();
        if (p && typeof p.then === 'function') await p;
      }
      return !v.paused;
    } catch (_) {
      return false;
    }
  }

  async function stopSilentVideoFallback() {
    if (!silentVideoEl) return;
    try {
      if (silentVideoEl._raf) {
        cancelAnimationFrame(silentVideoEl._raf);
        silentVideoEl._raf = null;
      }
      silentVideoEl.pause();
      if (silentVideoEl.currentTime) silentVideoEl.currentTime = 0;
    } catch (_) {}
  }

  function updateKeepAwakeButton() {
    const btn = $('sc-keep-awake');
    if (!btn) return;
    btn.disabled = false;
    btn.classList.toggle('active', keepScreenOn);
    if (!keepScreenOn) {
      btn.textContent = 'Screen on';
      btn.title = 'Keep screen on while chat is open (tap to enable)';
      return;
    }
    if (wakeLockHeld) {
      btn.textContent = 'Screen on';
      btn.title = 'Screen wake active — display should stay on (tap to disable)';
      btn.classList.add('active');
      return;
    }
    btn.textContent = 'Screen on…';
    btn.title =
      'Trying to keep screen on. Tap once if your browser needs a gesture, or check battery saver / low power mode.';
  }

  async function releaseWakeLock() {
    clearWakeLockRetry();
    wakeLockHeld = false;
    const lock = wakeLock;
    wakeLock = null;
    if (lock) {
      try {
        if (!lock.released) await lock.release();
      } catch (_) {}
    }
    await stopSilentVideoFallback();
    updateKeepAwakeButton();
  }

  function isTouchLikely() {
    try {
      return (
        'ontouchstart' in window ||
        (typeof navigator !== 'undefined' && (navigator.maxTouchPoints || 0) > 0)
      );
    } catch (_) {
      return false;
    }
  }

  async function requestWakeLock() {
    if (!keepScreenOn) return;
    if (!isSystemChatSectionVisible() || document.visibilityState !== 'visible') return;

    let nativeOk = false;
    let videoOk = false;

    if (wakeLockApiSupported()) {
      try {
        if (wakeLock && !wakeLock.released) {
          nativeOk = true;
        } else {
          const lock = await navigator.wakeLock.request('screen');
          wakeLock = lock;
          nativeOk = true;
          lock.addEventListener('release', () => {
            // Browser/OS often drops the lock (tab switch, battery, idle). Re-acquire if still wanted.
            if (wakeLock === lock) wakeLock = null;
            wakeLockHeld = false;
            updateKeepAwakeButton();
            if (
              keepScreenOn &&
              isSystemChatSectionVisible() &&
              document.visibilityState === 'visible'
            ) {
              scheduleWakeLockRetry(1200);
            }
          });
        }
      } catch (_) {
        wakeLock = null;
        nativeOk = false;
      }
    }

    // On phones, pair native lock with a looping silent video — some OEMs still dim
    // with Wake Lock alone, and older WebViews only respond to media playback.
    if (!nativeOk || isTouchLikely()) {
      videoOk = await startSilentVideoFallback();
    } else {
      await stopSilentVideoFallback();
    }

    wakeLockHeld = nativeOk || videoOk;
    if (wakeLockHeld) {
      clearWakeLockRetry();
    } else {
      scheduleWakeLockRetry(10000);
    }

    updateKeepAwakeButton();
  }

  function bindWakeLockListeners() {
    if (wakeLockListenersBound) return;
    wakeLockListenersBound = true;

    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') {
        requestWakeLock();
      } else {
        // Spec: wake lock is released when hidden; clear our handle + stop video
        releaseWakeLock();
      }
    });

    window.addEventListener('pageshow', () => {
      if (keepScreenOn) requestWakeLock();
    });
    window.addEventListener('focus', () => {
      if (keepScreenOn) requestWakeLock();
    });

    // Many mobile browsers only grant Wake Lock / video.play after a user gesture
    if (!wakeLockUserGestureBound) {
      wakeLockUserGestureBound = true;
      const onGesture = () => {
        if (keepScreenOn && isSystemChatSectionVisible()) {
          requestWakeLock();
        }
      };
      document.addEventListener('pointerdown', onGesture, { passive: true });
      document.addEventListener('touchstart', onGesture, { passive: true });
      document.addEventListener('keydown', onGesture, { passive: true });
    }

    // Periodic re-assert while chat is open (covers silent OS drops)
    setInterval(() => {
      if (
        keepScreenOn &&
        isSystemChatSectionVisible() &&
        document.visibilityState === 'visible' &&
        !wakeLockHeld
      ) {
        requestWakeLock();
      }
    }, 15000);
  }

  function systemChatOnTabLeave() {
    releaseWakeLock();
  }

  async function systemChatInit() {
    if (!uiInitialized) {
      initUI();
      uiInitialized = true;
    }
    updateKeepAwakeButton();
    // Fire-and-forget; may need a later user gesture on mobile
    requestWakeLock();
    await loadModels();
    loadUsage(false);
    if (!ws || ws.readyState === WebSocket.CLOSED) connectWS();
    await loadNotes();
    await loadSessions();
    const saved = localStorage.getItem('gp_sc_session');
    const streamingSession = sessionStorage.getItem('gp_sc_streaming');
    if (streamingSession && /^[a-f0-9]{32}$/.test(streamingSession)) {
      try {
        const sessData = await apiGet('/admin-system-chat-sessions.php');
        const found = (sessData.sessions || []).find((s) => s.id === streamingSession);
        if (found && !isInternalAppSessionTitle(found.title)) {
          await switchSession(found.id, found.title);
        } else {
          clearStreamState();
        }
      } catch {
        clearStreamState();
      }
    } else if (saved && /^[a-f0-9]{32}$/.test(saved)) {
      try {
        const sessData = await apiGet('/admin-system-chat-sessions.php');
        const found = (sessData.sessions || []).find((s) => s.id === saved);
        if (found && !isInternalAppSessionTitle(found.title)) {
          await switchSession(found.id, found.title, { skipStreamResume: true });
        } else localStorage.removeItem('gp_sc_session');
      } catch {
        localStorage.removeItem('gp_sc_session');
      }
    }
    // Re-assert after async work (tab is still active)
    requestWakeLock();
  }

  global.systemChatInit = systemChatInit;
  global.systemChatOnTabLeave = systemChatOnTabLeave;
})(typeof window !== 'undefined' ? window : global);