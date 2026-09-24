// Progressive enhancement only — every page works without JavaScript.
(function () {
  'use strict';

  // "Select all" for the batch-approve table.
  document.querySelectorAll('[data-select-all]').forEach(function (master) {
    master.addEventListener('change', function () {
      var name = master.getAttribute('data-select-all');
      document.querySelectorAll('input[type=checkbox][name="' + name + '"]').forEach(function (cb) {
        cb.checked = master.checked;
      });
    });
  });

  // Confirm destructive or bulk actions.
  document.querySelectorAll('form[data-confirm]').forEach(function (form) {
    form.addEventListener('submit', function (e) {
      if (!window.confirm(form.getAttribute('data-confirm'))) {
        e.preventDefault();
      }
    });
  });

  // Hovering a field row highlights its bounding box on the label image.
  document.querySelectorAll('[data-field-item]').forEach(function (row) {
    var id = row.getAttribute('data-field-item');
    var rect = document.getElementById('bbox-' + id);
    if (!rect) return;
    row.addEventListener('mouseenter', function () { rect.classList.add('highlight'); });
    row.addEventListener('mouseleave', function () { rect.classList.remove('highlight'); });
  });

  // Disable submit buttons while the (synchronous) AI analysis runs.
  document.querySelectorAll('form[data-busy]').forEach(function (form) {
    form.addEventListener('submit', function () {
      var btn = form.querySelector('button[type=submit]');
      if (btn) {
        btn.disabled = true;
        btn.textContent = form.getAttribute('data-busy');
      }
    });
  });

  // Pre-fill: read the chosen label images and fill empty form fields.
  var form = document.getElementById('submission-form');
  if (form && form.getAttribute('data-prefill-url')) {
    var input = document.getElementById('images');
    var button = document.getElementById('prefill-button');
    var status = document.getElementById('prefill-status');
    var notice = document.getElementById('prefill-notice');

    var readLabel = function () {
      if (!input.files || input.files.length === 0) {
        return;
      }
      var data = new FormData();
      Array.prototype.forEach.call(input.files, function (f) { data.append('images', f); });
      var csrf = form.querySelector('input[name="_csrf"]');
      var headers = { 'Accept': 'application/json' };
      if (csrf) {
        data.append('_csrf', csrf.value);
        headers['X-CSRF-TOKEN'] = csrf.value;
      }
      status.textContent = 'Reading label…';
      button.disabled = true;
      fetch(form.getAttribute('data-prefill-url'), { method: 'POST', body: data, headers: headers, credentials: 'same-origin' })
        .then(function (r) { return r.json().then(function (body) { return { ok: r.ok, body: body }; }); })
        .then(function (res) {
          if (!res.ok) {
            status.textContent = res.body.error || 'The label could not be read. Enter the values manually.';
            return;
          }
          var filled = applyPrefill(res.body);
          status.textContent = filled > 0
            ? 'Filled ' + filled + ' field(s) from the label in ' + res.body.processingTimeMs + ' ms. Review them below.'
            : 'No text could be recognised on this image. Enter the values manually.';
          if (filled > 0 && notice) { notice.hidden = false; }
        })
        .catch(function () { status.textContent = 'The label could not be read. Enter the values manually.'; })
        .then(function () { button.disabled = false; });
    };

    var setIfEmpty = function (name, value) {
      if (value === null || value === undefined || value === '') { return 0; }
      var el = form.querySelector('[name="' + name + '"]');
      if (!el || (el.type !== 'checkbox' && el.value && el.value.trim() !== '')) { return 0; }
      if (el.type === 'checkbox') {
        if (el.checked) { return 0; }
        el.checked = !!value;
      } else {
        el.value = value;
      }
      el.classList.add('prefilled');
      el.addEventListener('input', function () { el.classList.remove('prefilled'); }, { once: true });
      return 1;
    };

    var applyPrefill = function (p) {
      var n = 0;
      n += setIfEmpty('beverageType', p.beverageType);
      n += setIfEmpty('containerSizeMl', p.containerSizeMl);
      n += setIfEmpty('sulfiteDeclaration', p.sulfiteDeclaration);
      Object.keys(p.fields || {}).forEach(function (k) { n += setIfEmpty(k, p.fields[k]); });
      return n;
    };

    input.addEventListener('change', readLabel);
    button.addEventListener('click', readLabel);
    input.addEventListener('change', function () { button.disabled = !input.files || input.files.length === 0; });
  }
})();
