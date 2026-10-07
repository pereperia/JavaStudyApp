'use strict';

/* kurohon-tracker — 画面まわり
 *
 * 保持する状態は「今回タップした分（marks）」「表示中の章」「タップの動作モード」だけ。
 * サーバーから来た内容は毎回描き直すので、画面に状態を溜めない。
 */

const state = {
  tab: 'review',
  chapter: null,
  mode: 'record',          // 'record' | 'detail'
  topicFilter: '',         // '' = すべて / '__none__' = 未設定
  questions: [],           // [{id, number, topic, round, correct}]
  notesByNumber: {},
  topics: [],
  marks: {},               // { 番号: {result:'y'|'n', note:''} }
  detailQuestion: null,
  gaps: null
};

const HEAT_DAYS = 84;

const $ = (id) => document.getElementById(id);

const api = {
  get: (path) => fetch(path, { cache: 'no-store' }).then(okJson),
  post: (path, body) => fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body
  }).then(okJson)
};

function okJson(res) {
  if (!res.ok) {
    return res.text().then((t) => { throw new Error(t || ('HTTP ' + res.status)); });
  }
  return res.json();
}

/* ------------------------------------------------------------------ */
/*  起動                                                               */
/* ------------------------------------------------------------------ */

document.addEventListener('DOMContentLoaded', () => {
  $('book').addEventListener('change', switchBook);
  $('send').addEventListener('click', submit);
  $('clearSel').addEventListener('click', () => { state.marks = {}; renderGrid(); renderSelected(); });
  $('notesToggle').addEventListener('click', () => toggle('notesToggle', 'notesList'));
  $('topicFilter').addEventListener('change', (e) => { state.topicFilter = e.target.value; renderGrid(); });
  $('detailClose').addEventListener('click', closeDetail);
  $('detailTopicSave').addEventListener('click', saveTopic);
  $('answeredOn').value = todayString();
  $('answeredOn').addEventListener('change', updatePastWarn);
  $('answeredToday').addEventListener('click', () => {
    $('answeredOn').value = todayString();
    updatePastWarn();
  });

  $('logout').addEventListener('click', () => {
    api.post('/api/logout', '').then(() => { location.href = '/login.html'; }).catch(showError);
  });

  document.querySelectorAll('.mode-btn').forEach((btn) => {
    btn.addEventListener('click', () => setMode(btn.dataset.mode));
  });

  document.querySelectorAll('.tab-btn').forEach((btn) => {
    btn.addEventListener('click', () => setTab(btn.dataset.tab));
  });

  const saved = localStorage.getItem('examDate');
  if (saved) $('examDate').value = saved;
  $('examDate').addEventListener('change', (e) => {
    localStorage.setItem('examDate', e.target.value);
    if (state.gaps) renderGapSummary(state.gaps);
  });

  loadBooks().then(loadOverview);
});

const TAB_TITLES = { review: '今日の復習', record: '解答を記録', gaps: '穴の管理', stats: '統計' };
const TABS = ['review', 'record', 'gaps', 'stats'];
const CAUSES = [
  { key: 'knowledge',     label: '知識' },
  { key: 'understanding', label: '理解' },
  { key: 'careless',      label: 'ケアレス' }
];

function setTab(tab) {
  state.tab = tab;

  document.querySelectorAll('.tab-btn').forEach((b) => {
    const on = b.dataset.tab === tab;
    b.classList.toggle('active', on);
    b.setAttribute('aria-selected', String(on));
  });

  TABS.forEach((t) => {
    $('tab-' + t).classList.toggle('d-none', t !== tab);
  });

  if (tab === 'gaps') loadGaps();

  $('navTitle').textContent = TAB_TITLES[tab];
  updateActionBar();
  window.scrollTo({ top: 0, behavior: 'instant' });
}

/** 記録タブで、かつ選択があるときだけ下のバーを出す */
function updateActionBar() {
  const count = Object.keys(state.marks).length;
  $('actionBar').classList.toggle('d-none', !(state.tab === 'record' && count > 0));
  document.body.classList.toggle('has-action-bar', state.tab === 'record' && count > 0);
}

function todayString() {
  const d = new Date();
  return d.getFullYear() + '-' +
         String(d.getMonth() + 1).padStart(2, '0') + '-' +
         String(d.getDate()).padStart(2, '0');
}

/** 過去日を選んでいるあいだは注意書きを出す */
function updatePastWarn() {
  const value = $('answeredOn').value;
  const past = value !== '' && value < todayString();
  $('pastWarn').classList.toggle('d-none', !past);
  $('send').textContent = past ? value + ' で記録する' : '記録する';
}

function toggle(btnId, panelId) {
  const btn = $(btnId);
  const open = btn.getAttribute('aria-expanded') === 'true';
  btn.setAttribute('aria-expanded', String(!open));
  $(panelId).classList.toggle('d-none', open);
}

/* ------------------------------------------------------------------ */
/*  教材                                                               */
/* ------------------------------------------------------------------ */

function loadBooks() {
  return api.get('/api/books').then((data) => {
    const sel = $('book');
    sel.innerHTML = '';
    $('logout').classList.toggle('d-none', !data.auth);
    data.books.forEach((b) => {
      const opt = document.createElement('option');
      opt.value = b;
      opt.textContent = label(b);
      if (b === data.current) opt.selected = true;
      sel.appendChild(opt);
    });
  });
}

function label(book) {
  if (book === 'kurohon') return '黒本';
  if (book === 'murasaki') return '紫本';
  return book;
}

function switchBook() {
  const book = $('book').value;
  state.marks = {};
  state.chapter = null;
  state.topicFilter = '';
  closeDetail();
  api.post('/api/books', 'book=' + encodeURIComponent(book))
    .then(loadOverview)
    .catch(showError);
}

/* ------------------------------------------------------------------ */
/*  サマリー・履歴・章・分野                                            */
/* ------------------------------------------------------------------ */

function loadOverview() {
  return Promise.all([
    api.get('/api/summary'),
    api.get('/api/chapters'),
    api.get('/api/history?days=' + HEAT_DAYS),
    api.get('/api/topics'),
    api.get('/api/review?limit=30'),
    api.get('/api/hours')
  ]).then(([summary, chapters, history, topics, review, hours]) => {
    renderSummary(summary);
    renderChapters(chapters);
    renderHeatmap(history.history);
    renderTopics(topics);
    renderReview(review);
    renderHours(hours);

    if (state.chapter === null && chapters.length) {
      const next = chapters.find((c) => c.untouched > 0) || chapters[0];
      selectChapter(next.chapter, true);
    } else if (state.chapter !== null) {
      markActiveChapter();
    }
  }).catch(showError);
}

/* ---- 穴 ---- */

function loadGaps() {
  return api.get('/api/gaps?limit=200')
    .then((data) => {
      state.gaps = data;
      renderGapSummary(data);
      renderGapList(data.items);
      setGapBadge(data.open);
    })
    .catch(showError);
}

function renderGapSummary(d) {
  $('gapOpen').textContent = d.open;
  $('gapClosed').textContent = d.closed;

  const total = d.open || 1;
  $('segK').style.width = (d.knowledge / total * 100) + '%';
  $('segU').style.width = (d.understanding / total * 100) + '%';
  $('segC').style.width = (d.careless / total * 100) + '%';

  $('cntK').textContent = d.knowledge;
  $('cntU').textContent = d.understanding;
  $('cntC').textContent = d.careless;
  $('cntN').textContent = d.unset;

  // 試験日までのペース
  const exam = $('examDate').value;
  if (!exam) {
    $('gapPace').textContent = '–';
    $('gapDaysLeft').textContent = '試験日を入力';
    return;
  }

  const days = Math.ceil(
    (new Date(exam + 'T00:00:00') - new Date(d.today + 'T00:00:00')) / 86400000);

  if (days <= 0) {
    $('gapPace').textContent = '–';
    $('gapDaysLeft').textContent = '期限を過ぎています';
    return;
  }

  $('gapPace').textContent = (d.open / days).toFixed(1);
  $('gapDaysLeft').textContent = '残り ' + days + ' 日';
}

function renderGapList(items) {
  const list = $('gapList');
  list.innerHTML = '';

  if (!items.length) {
    const li = document.createElement('li');
    li.className = 'list-group-item skeleton';
    li.textContent = '穴はありません';
    list.appendChild(li);
    return;
  }

  items.forEach((g) => list.appendChild(gapRow(g)));
}

function gapRow(g) {
  const li = document.createElement('li');
  li.className = 'list-group-item gap-row';

  const head = document.createElement('div');
  head.className = 'gap-head';
  head.innerHTML =
    '<span class="rv-name">第' + g.chapter + '章 ' + g.number + '番</span>' +
    '<span class="rv-meta">' + g.round + '周目で不正解 · ' + (g.answeredAt || '') +
    (g.topic ? ' · ' + escapeHtml(g.topic) : '') + '</span>';
  li.appendChild(head);

  if (g.note) {
    const note = document.createElement('div');
    note.className = 'note-body gap-note';
    note.textContent = g.note;
    li.appendChild(note);
  }

  // 原因の3択
  const chips = document.createElement('div');
  chips.className = 'cause-chips';

  CAUSES.forEach((c) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'chip chip-' + c.key + (g.cause === c.key ? ' on' : '');
    b.textContent = c.label;
    b.addEventListener('click', () => {
      const next = g.cause === c.key ? '' : c.key;
      api.post('/api/gaps', 'questionId=' + g.questionId + '&cause=' + next)
        .then(() => {
          g.cause = next || null;
          chips.querySelectorAll('.chip').forEach((el) => el.classList.remove('on'));
          if (next) b.classList.add('on');
          return loadGapsSummaryOnly();
        })
        .catch(showError);
    });
    chips.appendChild(b);
  });
  li.appendChild(chips);

  // 再テスト日
  const dueWrap = document.createElement('div');
  dueWrap.className = 'gap-due';

  const label = document.createElement('span');
  label.className = 'gap-due-label';
  label.textContent = '再テスト';
  if (g.overdueDays > 0) {
    label.textContent += '（' + g.overdueDays + '日遅れ）';
    label.classList.add('overdue');
  }

  const input = document.createElement('input');
  input.type = 'date';
  input.className = 'note-input date-input';
  input.value = g.nextDue || '';
  input.addEventListener('change', () => {
    if (!input.value) return;
    api.post('/api/gaps', 'questionId=' + g.questionId + '&nextDue=' + input.value)
      .then(() => flash('再テスト日を ' + input.value + ' にしました', 'ok'))
      .catch(showError);
  });

  dueWrap.appendChild(label);
  dueWrap.appendChild(input);
  li.appendChild(dueWrap);

  return li;
}

/** 一覧は描き直さず、集計だけ更新する */
function loadGapsSummaryOnly() {
  return api.get('/api/gaps?limit=1').then((d) => {
    state.gaps = Object.assign({}, d, { items: state.gaps ? state.gaps.items : [] });
    renderGapSummary(d);
    setGapBadge(d.open);
  });
}

function setGapBadge(count) {
  const badge = $('tabGapBadge');
  badge.textContent = count > 99 ? '99+' : count;
  badge.classList.toggle('d-none', count === 0);
}

/* ---- 今日の復習 ---- */

function renderReview(data) {
  const list = $('reviewList');

  $('reviewCount').textContent = data.dueCount;
  setReviewBadge(data.dueCount);
  list.innerHTML = '';

  if (!data.items.length) {
    $('reviewNote').textContent = data.nextDueDate
      ? '次は ' + data.nextDueDate
      : '';
    const li = document.createElement('li');
    li.className = 'list-group-item skeleton';
    li.textContent = data.nextDueDate
      ? '今日の復習は終わりです'
      : '解答を記録すると、ここに復習予定が並びます';
    list.appendChild(li);
    return;
  }

  $('reviewNote').textContent = data.dueCount > data.items.length
    ? '上位 ' + data.items.length + ' 件' : '';

  data.items.forEach((item) => list.appendChild(reviewRow(item)));
}

function setReviewBadge(count) {
  const badge = $('tabReviewBadge');
  badge.textContent = count > 99 ? '99+' : count;
  badge.classList.toggle('d-none', count === 0);
}

function reviewRow(item) {
  const li = document.createElement('li');
  li.className = 'list-group-item review-row';

  const head = document.createElement('div');
  head.className = 'rv-head';

  const name = document.createElement('span');
  name.className = 'rv-name';
  name.textContent = '第' + item.chapter + '章 ' + item.number + '番';

  const meta = document.createElement('span');
  meta.className = 'rv-meta';
  let text = item.intervalDays + '日間隔';
  if (item.overdueDays > 0) text += ' · ' + item.overdueDays + '日遅れ';
  if (item.topic) text += ' · ' + item.topic;
  meta.textContent = text;

  head.appendChild(name);
  head.appendChild(meta);

  const actions = document.createElement('div');
  actions.className = 'rv-actions';

  const ok = document.createElement('button');
  ok.type = 'button';
  ok.className = 'rv-btn rv-ok';
  ok.textContent = '○';
  ok.addEventListener('click', () => answerReview(item, true, li));

  const ng = document.createElement('button');
  ng.type = 'button';
  ng.className = 'rv-btn rv-ng';
  ng.textContent = '×';
  ng.addEventListener('click', () => answerReview(item, false, li));

  actions.appendChild(ok);
  actions.appendChild(ng);

  li.appendChild(head);
  li.appendChild(actions);

  // メモは答えの手がかりになるので、押したときだけ見せる
  if (item.lastNote) {
    const toggle = document.createElement('button');
    toggle.type = 'button';
    toggle.className = 'rv-hint';
    toggle.textContent = '前回のメモを見る';

    const body = document.createElement('div');
    body.className = 'note-body d-none';
    body.textContent = item.lastNote;

    toggle.addEventListener('click', () => {
      body.classList.toggle('d-none');
      toggle.textContent = body.classList.contains('d-none')
        ? '前回のメモを見る' : '前回のメモを隠す';
    });

    li.appendChild(toggle);
    li.appendChild(body);
  }

  return li;
}

function answerReview(item, correct, row) {
  row.querySelectorAll('button').forEach((b) => { b.disabled = true; });

  api.post('/api/review',
    'questionId=' + item.questionId + '&correct=' + (correct ? 'y' : 'n'))
    .then((r) => {
      flash('第' + item.chapter + '章 ' + item.number + '番を記録（' +
            (correct ? '○' : '×') + ' ' + r.round + '周目）', correct ? 'ok' : 'ng');
      row.remove();
      $('reviewCount').textContent = r.remaining;
      setReviewBadge(r.remaining);
      if (state.gaps) loadGapsSummaryOnly();

      if (!$('reviewList').children.length) {
        loadOverview();
      } else {
        refreshSideEffects();
      }
    })
    .catch((e) => {
      row.querySelectorAll('button').forEach((b) => { b.disabled = false; });
      showError(e);
    });
}

/** 復習で記録したあと、サマリーと章の表示だけ追随させる */
function refreshSideEffects() {
  Promise.all([api.get('/api/summary'), api.get('/api/chapters')])
    .then(([summary, chapters]) => {
      renderSummary(summary);
      renderChapters(chapters);
      if (state.chapter !== null) markActiveChapter();
    })
    .catch(showError);
}

function renderSummary(s) {
  const accuracy = s.attempts === 0 ? 0 : (s.correct / s.attempts) * 100;

  $('statAnswered').textContent = s.answeredQuestions;
  $('statTotal').textContent = '/ ' + s.totalQuestions;
  $('statAccuracy').textContent = s.attempts === 0 ? '–' : accuracy.toFixed(0) + '%';
  $('statCorrect').textContent = s.correct + ' / ' + s.attempts;
  $('statUntouched').textContent = s.totalQuestions - s.answeredQuestions;
}

/* ---- ヒートマップ ---- */

function renderHeatmap(history) {
  const byDate = {};
  history.forEach((d) => { byDate[d.date] = d; });

  const today = new Date();
  today.setHours(0, 0, 0, 0);

  // 直近 HEAT_DAYS 日を、日曜始まりの列で並べる
  const start = new Date(today);
  start.setDate(start.getDate() - (HEAT_DAYS - 1));
  start.setDate(start.getDate() - start.getDay());

  const box = $('heatmap');
  box.innerHTML = '';

  const max = history.reduce((m, d) => Math.max(m, d.total), 0);
  const cursor = new Date(start);
  let firstShown = null;

  while (cursor <= today) {
    const key = isoDate(cursor);
    const day = byDate[key];
    const cell = document.createElement('i');

    cell.className = 'heat lv' + level(day ? day.total : 0, max);
    cell.title = key + (day ? '  ' + day.total + '件（○' + day.correct + '）' : '  記録なし');
    if (isoDate(cursor) === isoDate(today)) cell.classList.add('today');

    box.appendChild(cell);
    if (!firstShown) firstShown = key;
    cursor.setDate(cursor.getDate() + 1);
  }

  $('heatRange').textContent = firstShown + ' 〜 ' + isoDate(today);
  $('streak').textContent = streakText(byDate, today);

  // 直近が見えるよう右端にスクロールしておく
  const scroller = box.parentElement;
  scroller.scrollLeft = scroller.scrollWidth;
}

function level(count, max) {
  if (count === 0) return 0;
  if (max <= 1) return 4;
  const ratio = count / max;
  if (ratio <= 0.25) return 1;
  if (ratio <= 0.5) return 2;
  if (ratio <= 0.75) return 3;
  return 4;
}

function streakText(byDate, today) {
  let streak = 0;
  const cursor = new Date(today);

  // 今日まだ解いていない場合は昨日から数える
  if (!byDate[isoDate(cursor)]) cursor.setDate(cursor.getDate() - 1);

  while (byDate[isoDate(cursor)]) {
    streak++;
    cursor.setDate(cursor.getDate() - 1);
  }

  const days = Object.keys(byDate).length;
  if (streak === 0) return days ? days + '日 学習' : '記録なし';
  return streak + '日 連続 · ' + days + '日 学習';
}

function isoDate(d) {
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return d.getFullYear() + '-' + m + '-' + day;
}

/* ---- 時間帯 ---- */

/** この件数を下回る時間帯は、正答率を語らない */
const HOUR_MIN_SAMPLE = 5;

function renderHours(data) {
  const box = $('hours');
  const notes = $('hourNotes');
  box.innerHTML = '';
  notes.innerHTML = '';

  const byHour = {};
  data.hours.forEach((h) => { byHour[h.hour] = h; });

  $('hourMeasured').textContent = data.measured
    ? '時刻が分かる ' + data.measured + ' 件' : '';

  if (!data.measured) {
    box.innerHTML = '<div class="skeleton">時刻付きの記録がまだありません</div>';
    notes.innerHTML =
      '<li>あとから入力した過去分は時刻を持たないので、ここには出ません。</li>';
    return;
  }

  const max = data.hours.reduce((m, h) => Math.max(m, h.total), 0);

  for (let hour = 0; hour < 24; hour++) {
    const h = byHour[hour];
    const bar = document.createElement('div');
    bar.className = 'hour-bar';

    const fill = document.createElement('span');
    fill.className = 'hour-fill';
    fill.style.height = h ? Math.max(3, (h.total / max) * 100) + '%' : '0';

    if (h) {
      const rate = Math.round((h.correct / h.total) * 100);
      bar.title = hour + '時台  ' + h.total + '件' +
                  (h.total >= HOUR_MIN_SAMPLE ? '  正答率 ' + rate + '%' : '');
      if (h.total < HOUR_MIN_SAMPLE) fill.classList.add('thin');
    } else {
      bar.title = hour + '時台  記録なし';
    }

    bar.appendChild(fill);
    box.appendChild(bar);
  }

  // 要約。件数の少ない時間帯で正答率を語らない
  const busiest = data.hours.reduce((a, b) => (b.total > a.total ? b : a));
  addNote(notes, 'よく解く時間帯', busiest.hour + '時台（' + busiest.total + '件）');

  const enough = data.hours.filter((h) => h.total >= HOUR_MIN_SAMPLE);

  if (enough.length >= 2) {
    const rate = (h) => h.correct / h.total;
    const best = enough.reduce((a, b) => (rate(b) > rate(a) ? b : a));
    const worst = enough.reduce((a, b) => (rate(b) < rate(a) ? b : a));

    addNote(notes, '正答率が高い', best.hour + '時台（' +
            Math.round(rate(best) * 100) + '%・' + best.total + '件）');
    addNote(notes, '正答率が低い', worst.hour + '時台（' +
            Math.round(rate(worst) * 100) + '%・' + worst.total + '件）');
  } else {
    addNote(notes, '正答率', HOUR_MIN_SAMPLE + '件以上の時間帯が 2 つ以上たまると出ます');
  }
}

function addNote(list, label, value) {
  const li = document.createElement('li');
  li.innerHTML = '<span class="hn-label">' + label + '</span>' +
                 '<span class="hn-value">' + escapeHtml(value) + '</span>';
  list.appendChild(li);
}

/* ---- 章 ---- */

function renderChapters(list) {
  const box = $('chapterList');
  box.innerHTML = '';

  if (!list.length) {
    box.innerHTML = '<div class="skeleton">問題が登録されていません</div>';
    return;
  }

  list.forEach((c) => {
    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'chapter-row';
    row.dataset.chapter = c.chapter;

    const okPct = c.total ? (c.ok / c.total) * 100 : 0;
    const ngPct = c.total ? (c.ng / c.total) * 100 : 0;

    row.innerHTML =
      '<span class="ch-name">第' + c.chapter + '章</span>' +
      '<span class="bar">' +
        '<span class="seg-ok" style="width:' + okPct + '%"></span>' +
        '<span class="seg-ng" style="width:' + ngPct + '%"></span>' +
      '</span>' +
      '<span class="ch-count">' + c.answered + ' / ' + c.total + '</span>';

    row.addEventListener('click', () => selectChapter(c.chapter));
    box.appendChild(row);
  });

  markActiveChapter();
}

function markActiveChapter() {
  document.querySelectorAll('.chapter-row').forEach((el) => {
    el.classList.toggle('active', Number(el.dataset.chapter) === state.chapter);
  });
}

/* ---- 分野 ---- */

function renderTopics(topics) {
  state.topics = topics;
  $('topicCount').textContent = topics.length;

  const box = $('topicList');
  box.innerHTML = '';

  if (!topics.length) {
    box.innerHTML =
      '<div class="skeleton">分野はまだ設定されていません。<br>' +
      '「タップで詳細」に切り替えて問題を選ぶと入力できます。</div>';
  } else {
    topics.forEach((t) => {
      const row = document.createElement('div');
      row.className = 'chapter-row';

      const okPct = t.total ? (t.ok / t.total) * 100 : 0;
      const ngPct = t.total ? (t.ng / t.total) * 100 : 0;
      const done = t.ok + t.ng;
      const rate = done ? Math.round((t.ok / done) * 100) + '%' : '–';

      row.innerHTML =
        '<span class="ch-name topic-name">' + escapeHtml(t.topic) + '</span>' +
        '<span class="bar">' +
          '<span class="seg-ok" style="width:' + okPct + '%"></span>' +
          '<span class="seg-ng" style="width:' + ngPct + '%"></span>' +
        '</span>' +
        '<span class="ch-count">' + rate + ' · ' + done + '/' + t.total + '</span>';

      box.appendChild(row);
    });
  }

  // 詳細パネルの入力補完
  const dl = $('topicOptions');
  dl.innerHTML = '';
  topics.forEach((t) => {
    const opt = document.createElement('option');
    opt.value = t.topic;
    dl.appendChild(opt);
  });
}

/* ------------------------------------------------------------------ */
/*  問題一覧                                                            */
/* ------------------------------------------------------------------ */

function selectChapter(chapter, force) {
  if (!force && state.chapter !== chapter && Object.keys(state.marks).length) {
    if (!confirm('未送信の記録があります。章を切り替えると破棄されます。よろしいですか？')) {
      return;
    }
  }

  state.chapter = chapter;
  state.marks = {};
  state.topicFilter = '';
  closeDetail();
  markActiveChapter();
  updateActionBar();
  $('chapterTitle').textContent = '第' + chapter + '章';

  return Promise.all([
    api.get('/api/questions?chapter=' + chapter),
    api.get('/api/notes?chapter=' + chapter)
  ]).then(([questions, notes]) => {
    state.questions = questions;

    state.notesByNumber = {};
    notes.forEach((n) => {
      (state.notesByNumber[n.number] = state.notesByNumber[n.number] || []).push(n);
    });

    renderTopicFilter();
    renderGrid();
    renderSelected();
    renderNotes(notes);

    const done = questions.filter((q) => q.round !== null).length;
    $('chapterInfo').textContent = done + ' / ' + questions.length + ' 問 着手';
  }).catch(showError);
}

function renderTopicFilter() {
  const sel = $('topicFilter');
  const inChapter = [];

  state.questions.forEach((q) => {
    if (q.topic && inChapter.indexOf(q.topic) === -1) inChapter.push(q.topic);
  });
  inChapter.sort();

  sel.innerHTML = '';
  addOption(sel, '', 'すべての分野');
  inChapter.forEach((t) => addOption(sel, t, t));
  if (state.questions.some((q) => !q.topic)) addOption(sel, '__none__', '未設定');

  sel.value = state.topicFilter;
  sel.classList.toggle('d-none', inChapter.length === 0);
}

function addOption(sel, value, text) {
  const opt = document.createElement('option');
  opt.value = value;
  opt.textContent = text;
  sel.appendChild(opt);
}

function visibleQuestions() {
  if (state.topicFilter === '') return state.questions;
  if (state.topicFilter === '__none__') return state.questions.filter((q) => !q.topic);
  return state.questions.filter((q) => q.topic === state.topicFilter);
}

function renderGrid() {
  const grid = $('grid');
  grid.innerHTML = '';

  const list = visibleQuestions();

  if (!list.length) {
    grid.innerHTML = '<div class="skeleton">該当する問題がありません</div>';
    return;
  }

  list.forEach((q) => {
    const mark = state.marks[q.number];
    const div = document.createElement('div');

    div.className = 'q' + stateClass(q, mark);
    if (state.notesByNumber[q.number]) div.classList.add('has-note');
    if (q.topic) div.classList.add('has-topic');
    if (state.detailQuestion && state.detailQuestion.id === q.id) div.classList.add('focus');

    div.innerHTML = '<span>' + q.number + '</span>' +
                    '<span class="mark">' + markLabel(q, mark) + '</span>';

    div.addEventListener('click', () => {
      if (state.mode === 'detail') {
        openDetail(q);
      } else {
        cycle(q.number);
      }
    });
    grid.appendChild(div);
  });
}

/** 未 → ○ → × → 未 */
function cycle(number) {
  const cur = state.marks[number];

  if (cur === undefined) {
    state.marks[number] = { result: 'y', note: '', cause: null };
  } else if (cur.result === 'y') {
    cur.result = 'n';
  } else {
    delete state.marks[number];
  }

  renderGrid();
  renderSelected();
}

function stateClass(q, mark) {
  if (mark) return mark.result === 'y' ? ' ok sel' : ' ng sel';
  if (q.correct === null) return '';
  return q.correct ? ' ok' : ' ng';
}

function markLabel(q, mark) {
  if (mark) return mark.result === 'y' ? '○' : '×';
  if (q.correct === null) return '–';
  return (q.correct ? '○' : '×') + (q.round > 1 ? q.round : '');
}

function setMode(mode) {
  state.mode = mode;
  document.querySelectorAll('.mode-btn').forEach((b) => {
    b.classList.toggle('active', b.dataset.mode === mode);
  });
  $('modeHint').innerHTML = mode === 'detail'
    ? 'タップすると、その問題の<span class="text-body">周回の履歴と分野</span>を表示します。'
    : 'タップするたびに <span class="text-body">未 → ○ → × → 未</span> と切り替わります。';
  if (mode === 'record') closeDetail();
}

/* ------------------------------------------------------------------ */
/*  問題の詳細（周回履歴 + 分野）                                       */
/* ------------------------------------------------------------------ */

function openDetail(q) {
  state.detailQuestion = q;
  $('detailCard').classList.remove('d-none');
  $('detailTitle').textContent = '第' + q.chapter + '章 ' + q.number + '番';
  $('detailTopic').value = q.topic || '';
  $('detailHistory').innerHTML = '<li class="list-group-item skeleton">読み込み中…</li>';
  renderGrid();

  api.get('/api/attempts?questionId=' + q.id)
    .then(renderDetailHistory)
    .catch(showError);

  $('detailCard').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

function renderDetailHistory(list) {
  const box = $('detailHistory');
  box.innerHTML = '';

  if (!list.length) {
    box.innerHTML = '<li class="list-group-item skeleton">まだ解答していません</li>';
    return;
  }

  list.forEach((a) => {
    const li = document.createElement('li');
    li.className = 'list-group-item';

    const head = document.createElement('div');
    head.className = 'note-head d-flex align-items-center gap-2';
    head.innerHTML =
      '<span class="round-badge">' + a.round + '周目</span>' +
      '<span class="' + (a.correct ? 'text-ok' : 'text-ng') + '">' +
      (a.correct ? '○ 正解' : '× 不正解') + '</span>' +
      '<span class="ms-auto">' + (a.answeredAt || '') + '</span>';

    li.appendChild(head);

    if (a.note) {
      const body = document.createElement('div');
      body.className = 'note-body';
      body.textContent = a.note;
      body.addEventListener('click', () => editNote(a, body));
      li.appendChild(body);
    }

    box.appendChild(li);
  });
}

function saveTopic() {
  const q = state.detailQuestion;
  if (!q) return;

  const topic = $('detailTopic').value.trim();

  api.post('/api/topics',
    'questionId=' + q.id + '&topic=' + encodeURIComponent(topic))
    .then(() => {
      q.topic = topic || null;
      flash('分野を保存しました', 'ok');
      renderTopicFilter();
      renderGrid();
      return api.get('/api/topics').then(renderTopics);
    })
    .catch(showError);
}

function closeDetail() {
  state.detailQuestion = null;
  const card = $('detailCard');
  if (card) card.classList.add('d-none');
}

/* ------------------------------------------------------------------ */
/*  今回の記録（メモ入力）                                              */
/* ------------------------------------------------------------------ */

function renderSelected() {
  const numbers = Object.keys(state.marks).map(Number).sort((a, b) => a - b);
  const card = $('selectedCard');
  const list = $('selectedList');

  $('selCount').textContent = numbers.length;
  $('send').disabled = numbers.length === 0;
  card.classList.toggle('d-none', numbers.length === 0);
  updateActionBar();

  if (numbers.length === 0 && $('answeredOn')) {
    $('answeredOn').value = todayString();
    updatePastWarn();
  }

  list.innerHTML = '';

  numbers.forEach((n) => {
    const mark = state.marks[n];
    const li = document.createElement('li');
    li.className = 'list-group-item';

    const row = document.createElement('div');
    row.className = 'sel-row';

    const badge = document.createElement('span');
    badge.className = 'sel-badge ' + (mark.result === 'y' ? 'ok' : 'ng');
    badge.textContent = n + ' ' + (mark.result === 'y' ? '○' : '×');

    const input = document.createElement('input');
    input.type = 'text';
    input.className = 'note-input';
    input.placeholder = mark.result === 'n' ? '間違えた理由をメモ（任意）' : 'メモ（任意）';
    input.value = mark.note;
    input.maxLength = 500;
    input.addEventListener('input', (e) => { mark.note = e.target.value; });

    row.appendChild(badge);
    row.appendChild(input);
    li.appendChild(row);

    if (mark.result === 'n') {
      const chips = document.createElement('div');
      chips.className = 'cause-chips mt-2';
      CAUSES.forEach((c) => {
        const b = document.createElement('button');
        b.type = 'button';
        b.className = 'chip chip-' + c.key + (mark.cause === c.key ? ' on' : '');
        b.textContent = c.label;
        b.addEventListener('click', () => {
          mark.cause = mark.cause === c.key ? null : c.key;
          chips.querySelectorAll('.chip').forEach((el) => el.classList.remove('on'));
          if (mark.cause) b.classList.add('on');
        });
        chips.appendChild(b);
      });
      li.appendChild(chips);
    }

    list.appendChild(li);
  });
}

/* ------------------------------------------------------------------ */
/*  メモ一覧                                                            */
/* ------------------------------------------------------------------ */

function renderNotes(notes) {
  const list = $('notesList');
  $('noteCount').textContent = notes.length;
  list.innerHTML = '';

  if (!notes.length) {
    const li = document.createElement('li');
    li.className = 'list-group-item skeleton';
    li.textContent = 'この章にメモはまだありません';
    list.appendChild(li);
    return;
  }

  notes.forEach((n) => {
    const li = document.createElement('li');
    li.className = 'list-group-item note-item';

    const head = document.createElement('div');
    head.className = 'note-head';
    head.textContent = n.number + '番 · ' + n.round + '周目 · ' +
                       (n.correct ? '○' : '×') + ' · ' + (n.answeredAt || '');

    const body = document.createElement('div');
    body.className = 'note-body';
    body.textContent = n.note;
    body.addEventListener('click', () => editNote(n, body));

    li.appendChild(head);
    li.appendChild(body);
    list.appendChild(li);
  });
}

function editNote(note, bodyEl) {
  const input = document.createElement('input');
  input.type = 'text';
  input.className = 'note-input';
  input.value = note.note;
  input.maxLength = 500;

  const commit = () => {
    const value = input.value;
    if (value === note.note) {
      bodyEl.textContent = note.note;
      input.replaceWith(bodyEl);
      return;
    }
    api.post('/api/notes',
      'attemptId=' + note.attemptId + '&note=' + encodeURIComponent(value))
      .then(() => {
        note.note = value;
        bodyEl.textContent = value;
        input.replaceWith(bodyEl);
        flash('メモを更新しました', 'ok');
      })
      .catch(showError);
  };

  input.addEventListener('blur', commit);
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') input.blur(); });

  bodyEl.replaceWith(input);
  input.focus();
}

/* ------------------------------------------------------------------ */
/*  送信                                                                */
/* ------------------------------------------------------------------ */

function submit() {
  const numbers = Object.keys(state.marks).map(Number).sort((a, b) => a - b);
  if (!numbers.length) return;

  const params = new URLSearchParams();
  params.set('chapter', state.chapter);
  params.set('results', numbers.map((n) => n + ':' + state.marks[n].result).join(','));

  const on = $('answeredOn').value;
  if (on) params.set('answeredOn', on);

  numbers.forEach((n) => {
    const note = state.marks[n].note.trim();
    if (note) params.set('note_' + n, note);
    if (state.marks[n].cause) params.set('cause_' + n, state.marks[n].cause);
  });

  $('send').disabled = true;
  flash('送信中…', '');

  api.post('/api/attempts', params.toString())
    .then((r) => {
      state.marks = {};
      const when = r.answeredOn === todayString() ? '' : '（' + r.answeredOn + '）';
      flash(r.recorded + '件記録しました' + when + '（○' + r.correct + ' ×' + r.wrong + '）', 'ok');
      selectChapter(state.chapter, true);
      loadOverview();
    })
    .catch((e) => {
      $('send').disabled = false;
      showError(e);
    });
}

/* ------------------------------------------------------------------ */

function flash(text, kind) {
  const el = $('msg');
  el.textContent = text;
  el.className = 'small flex-grow-1 ' + (kind || '');
}

function showError(e) {
  console.error(e);
  flash('エラー: ' + (e && e.message ? e.message : e), 'ng');
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[c]));
}
