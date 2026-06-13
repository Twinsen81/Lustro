(function() {
    function flagUrl(path) {
        return window.lustroApiUrl(path);
    }

    var allFlags = [];
    var lastCursor = null; // opaque cursor; omitted on the first poll

    function renderFlags() {
        var listEl = document.getElementById('flags-list');
        var countEl = document.getElementById('flags-count');
        if (!listEl) return;
        if (countEl) {
            var on = allFlags.filter(function(f) { return f.enabled; }).length;
            countEl.textContent = allFlags.length + ' flags (' + on + ' on)';
        }
        if (allFlags.length === 0) {
            listEl.innerHTML = '<div class="flags-empty">No flags. Upload a flags file to add some.</div>';
            return;
        }
        listEl.innerHTML = allFlags.map(function(f) {
            var checked = f.enabled ? ' checked' : '';
            return '<div class="flag-card">'
                + '<label class="flag-toggle" title="Toggle this flag. The change advances the poll cursor and is reflected immediately.">'
                + '<input type="checkbox"' + checked + ' data-action="toggleFlag" data-flag-id="' + window.debugEscapeHtml(f.id) + '">'
                + '<span class="flag-toggle-slider"></span></label>'
                + '<div class="flag-meta">'
                + '<span class="flag-id">' + window.debugEscapeHtml(f.id) + '</span>'
                + '<span class="flag-desc">' + window.debugEscapeHtml(f.description || '') + '</span>'
                + '</div>'
                + '<span class="status-pill ' + (f.enabled ? 'success' : '') + '">' + (f.enabled ? 'ON' : 'OFF') + '</span>'
                + '</div>';
        }).join('');
    }

    window.toggleFlag = function(id) {
        window.debugFetch(flagUrl('toggle'), {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ id: id })
        }).then(function() {
            // The poll loop will pick up the new state on its next tick; force an
            // immediate refresh so the UI feels responsive.
            fetchSnapshot();
        }).catch(function(e) {
            window.debugToast('Failed to toggle: ' + e.message, 'error');
        });
    };

    var uploadModal = null;

    window.openUploadModal = function() {
        if (!uploadModal) uploadModal = window.debugModal('flags-upload-modal');
        var ta = document.getElementById('flags-upload-text');
        if (ta && !ta.value) {
            ta.value = JSON.stringify([
                { id: 'experimental-search', description: 'Experimental search', enabled: true }
            ], null, 2);
        }
        uploadModal.show();
    };

    window.closeUploadModal = function() {
        if (uploadModal) uploadModal.hide();
    };

    // Read a picked file into the textarea so the user can review before upload.
    window.onUploadFileChange = function(inputEl) {
        var file = inputEl && inputEl.files && inputEl.files[0];
        if (!file) return;
        var reader = new FileReader();
        reader.onload = function() {
            var ta = document.getElementById('flags-upload-text');
            if (ta) ta.value = String(reader.result || '');
        };
        reader.readAsText(file);
    };

    window.submitUpload = function() {
        var ta = document.getElementById('flags-upload-text');
        var text = ta ? ta.value.trim() : '';
        if (!text) { window.debugToast('Nothing to upload', 'warning'); return; }
        window.debugFetch(flagUrl('upload'), {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: text
        }).then(function(r) { return r.json(); })
          .then(function(data) {
              window.debugToast('Imported ' + (data && data.imported || 0) + ' flag(s)', 'success');
              closeUploadModal();
              fetchSnapshot();
          })
          .catch(function(e) {
              window.debugToast('Upload failed: ' + e.message, 'error');
          });
    };

    // GET snapshot?cursor=<opaque> →
    //   { cursor, status: "reset"|"unchanged"|"delta", items? }
    // Same contract as the Network tab: echo the last cursor; unchanged carries
    // no items; reset/delta carry the authoritative current list. Unknown status
    // is treated as reset.
    function snapshotUrl() {
        var url = flagUrl('snapshot');
        if (lastCursor != null) url += '?cursor=' + encodeURIComponent(lastCursor);
        return url;
    }

    function handleSnapshot(data) {
        if (!data) return;
        var status = data.status;
        if (status === 'delta') {
            if (data.items) { allFlags = data.items; renderFlags(); }
        } else if (status !== 'unchanged') {
            allFlags = data.items || [];
            renderFlags();
        }
        if (data.cursor != null) lastCursor = data.cursor;
    }

    function fetchSnapshot() {
        window.debugFetch(snapshotUrl())
            .then(function(r) { return r.json(); })
            .then(handleSnapshot)
            .catch(function() { /* next poll retries */ });
    }

    function startPolling() {
        window.debugPoll(function() { return snapshotUrl(); }, 1500, handleSnapshot);
    }

    // The served HTML uses NO inline on*= handlers. Every interactive element
    // carries data-action plus data-* payload; delegated listeners on the tab
    // content root dispatch on data-action and survive re-renders.
    var CLICK_ACTIONS = {
        openUpload: function() { window.openUploadModal(); },
        closeUpload: function() { window.closeUploadModal(); },
        submitUpload: function() { window.submitUpload(); }
    };

    var CHANGE_ACTIONS = {
        toggleFlag: function(el) { window.toggleFlag(el.dataset.flagId); },
        onUploadFile: function(el) { window.onUploadFileChange(el); }
    };

    var delegationRoot = null;

    function onDelegatedClick(ev) {
        var el = ev.target.closest('[data-action]');
        if (!el || !delegationRoot.contains(el)) return;
        var fn = CLICK_ACTIONS[el.dataset.action];
        if (fn) fn(el, ev);
    }

    function onDelegatedChange(ev) {
        var el = ev.target.closest('[data-action]');
        if (!el || !delegationRoot.contains(el)) return;
        var fn = CHANGE_ACTIONS[el.dataset.action];
        if (fn) fn(el, ev);
    }

    function setupDelegation() {
        delegationRoot = document.getElementById('lustro-tab-content') || document.body;
        delegationRoot.addEventListener('click', onDelegatedClick);
        delegationRoot.addEventListener('change', onDelegatedChange);
    }

    // shared.js injects the authenticated /_view HTML then fires
    // lustroOnContentReady; all DOM-dependent setup runs there. Fall back to
    // immediate init on an older host without the hook.
    function init() {
        setupDelegation();
        startPolling();
    }
    if (typeof window.lustroOnContentReady === 'function') {
        window.lustroOnContentReady(init);
    } else {
        init();
    }
})();
