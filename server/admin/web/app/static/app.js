/* 值班台的脚本。没有框架，也不该有 —— 服务器上没有 node。
 *
 * 只做五件事：
 *   主题开关（存 localStorage，首屏由 base.html 里的内联脚本先定）
 *   提示条关掉
 *   编辑器：行号、滚动同步、⌘S、格式化、改动行数
 *   弹层：删除 / 回滚 / 重置口令
 *   清单页：勾了几条
 */
(function () {
  'use strict';

  /* ── 主题 ───────────────────────────────────────────────────── */

  var root = document.documentElement;
  var toggle = document.querySelector('[data-theme-toggle]');

  function paintTheme() {
    if (!toggle) return;
    var dark = root.dataset.theme === 'dark';
    toggle.setAttribute('aria-pressed', dark ? 'true' : 'false');
    toggle.setAttribute('aria-label', dark ? '切到日间' : '切到夜间');
  }

  if (toggle) {
    paintTheme();
    toggle.addEventListener('click', function () {
      root.dataset.theme = root.dataset.theme === 'dark' ? 'light' : 'dark';
      try { localStorage.setItem('admin-theme', root.dataset.theme); } catch (e) {}
      paintTheme();
    });
  }

  /* ── 提示条 ─────────────────────────────────────────────────── */

  document.querySelectorAll('[data-dismiss]').forEach(function (button) {
    button.addEventListener('click', function () {
      var bar = button.closest('.flash');
      if (bar) bar.remove();
    });
  });

  /* ── 弹层 ───────────────────────────────────────────────────── */

  function openDialog(dialog) {
    if (!dialog) return;
    if (typeof dialog.showModal === 'function') dialog.showModal();
    else dialog.setAttribute('open', '');
    var focusable = dialog.querySelector('input:not([type=hidden]), button');
    if (focusable) focusable.focus();
  }

  /* [data-dialog] 是"一个弹层轮流用"：按钮自带 action 和目标名，
     点谁就把谁填进去，省得为每一行写一份 dialog */
  document.querySelectorAll('[data-dialog]').forEach(function (button) {
    button.addEventListener('click', function () {
      var dialog = document.getElementById(button.getAttribute('data-dialog'));
      if (!dialog) return;
      var form = dialog.querySelector('form');
      if (form && button.dataset.action) form.setAttribute('action', button.dataset.action);
      dialog.querySelectorAll('[data-fill]').forEach(function (slot) {
        var value = button.dataset[slot.getAttribute('data-fill')];
        slot.textContent = value ? value : '';
      });
      openDialog(dialog);
    });
  });

  document.addEventListener('click', function (event) {
    var opener = event.target.closest('[data-dialog-open]');
    if (opener) {
      event.preventDefault();
      openDialog(document.getElementById(opener.getAttribute('data-dialog-open')));
      return;
    }
    var closer = event.target.closest('[data-dialog-close]');
    if (closer) {
      event.preventDefault();
      var dialog = closer.closest('dialog');
      if (dialog) dialog.close();
      return;
    }
    if (event.target.tagName === 'DIALOG') event.target.close();
  });

  /* 重置口令：弹层里的输入在提交前塞进隐藏字段 */
  document.querySelectorAll('[data-password-form]').forEach(function (form) {
    form.addEventListener('submit', function (event) {
      var input = form.querySelector('[data-password-input]');
      var hidden = form.querySelector('input[name=password]');
      if (!input || !hidden) return;
      if (input.value.trim().length < 8) {
        event.preventDefault();
        input.focus();
        input.setCustomValidity('至少 8 位');
        input.reportValidity();
        return;
      }
      hidden.value = input.value;
    });
  });

  /* ── 清单页：勾了几条 ───────────────────────────────────────── */

  var marketForm = document.querySelector('[data-market]');
  if (marketForm) {
    var counter = document.querySelector('[data-market-count]');
    var recount = function () {
      var count = marketForm.querySelectorAll('input[name=pick]:checked').length;
      if (counter) counter.textContent = '已勾 ' + count + ' 条';
    };
    marketForm.addEventListener('change', recount);
    recount();
  }

  /* ── 编辑器 ─────────────────────────────────────────────────── */

  var form = document.querySelector('[data-editor]');
  if (!form) return;

  var area = form.querySelector('[data-code]');
  var gutter = form.querySelector('[data-gutter]');
  var diff = document.querySelector('[data-diff]');
  var original = area.value;

  function lineCount(text) {
    return text.split('\n').length;
  }

  function paintGutter() {
    if (!gutter) return;
    var count = lineCount(area.value);
    var numbers = '';
    for (var i = 1; i <= count; i += 1) numbers += i + '\n';
    gutter.textContent = numbers;
    gutter.scrollTop = area.scrollTop;
  }

  function changedLines(before, after) {
    var tally = {};
    before.split('\n').forEach(function (line) { tally[line] = (tally[line] || 0) + 1; });
    var added = 0;
    after.split('\n').forEach(function (line) {
      if (tally[line]) tally[line] -= 1;
      else added += 1;
    });
    var removed = 0;
    var reverse = {};
    after.split('\n').forEach(function (line) { reverse[line] = (reverse[line] || 0) + 1; });
    before.split('\n').forEach(function (line) {
      if (reverse[line]) reverse[line] -= 1;
      else removed += 1;
    });
    return { added: added, removed: removed };
  }

  function paintDiff() {
    if (!diff) return;
    var delta = changedLines(original, area.value);
    if (!delta.added && !delta.removed) {
      diff.textContent = '没有改动';
      diff.style.color = '';
      return;
    }
    diff.textContent = '改动 +' + delta.added + ' −' + delta.removed + ' 行';
    diff.style.color = 'var(--ink)';
  }

  area.addEventListener('input', function () {
    paintGutter();
    paintDiff();
  });
  area.addEventListener('scroll', function () {
    if (gutter) gutter.scrollTop = area.scrollTop;
  });
  paintGutter();
  paintDiff();

  /* ⌘S / Ctrl+S */
  document.addEventListener('keydown', function (event) {
    if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') {
      event.preventDefault();
      if (typeof form.requestSubmit === 'function') form.requestSubmit();
      else form.submit();
    }
  });

  /* 格式化：json 就真格式化，js 只做安全的收尾（行尾空白、末尾换行） */
  var formatButton = form.querySelector('[data-format]');
  if (formatButton) {
    formatButton.addEventListener('click', function () {
      var text = area.value;
      var looksJson = /^\s*[\[{]/.test(text);
      if (looksJson) {
        try {
          area.value = JSON.stringify(JSON.parse(text), null, 2) + '\n';
        } catch (error) {
          if (diff) {
            diff.textContent = '格式化不了 —— ' + error.message;
            diff.style.color = 'var(--dead)';
          }
          return;
        }
      } else {
        area.value = text
          .split('\n')
          .map(function (line) { return line.replace(/[ \t]+$/, ''); })
          .join('\n')
          .replace(/\n{4,}/g, '\n\n\n')
          .replace(/\s*$/, '\n');
      }
      paintGutter();
      paintDiff();
    });
  }

  /* 回滚：一个弹层复用，点哪一版就填哪一版的时间戳 */
  var restoreDialog = document.getElementById('restore');
  if (restoreDialog) {
    var restoreForm = restoreDialog.querySelector('form');
    var stampInput = restoreDialog.querySelector('[data-restore-stamp]');
    var timeLabel = restoreDialog.querySelector('[data-restore-time]');
    document.querySelectorAll('[data-restore]').forEach(function (button) {
      button.addEventListener('click', function () {
        restoreForm.setAttribute('action', restoreDialog.getAttribute('data-action'));
        if (stampInput) stampInput.value = button.getAttribute('data-stamp');
        if (timeLabel) timeLabel.textContent = button.getAttribute('data-time');
        openDialog(restoreDialog);
      });
    });
  }
})();
