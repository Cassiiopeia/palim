/*
 * 후처리 스크립트 화면의 두 가지 — 예제 넣기, 순서 끌어 옮기기.
 *
 * 화면 안에 박은 코드는 이 서비스의 보안 정책에 막혀 실행되지 않는다(07-DECISIONS 031).
 * 그래서 파일로 둔다. 그리고 이 파일이 막히거나 실패해도 길이 남아야 한다 — 순서는 ▲▼
 * 버튼으로도 바꿀 수 있고, 그 버튼은 여기가 살아 있을 때만 숨는다.
 */
(function () {
    'use strict';

    // ── 예제 넣기 ────────────────────────────────────────────────
    //
    // 빈 상자를 주면 아무도 시작하지 못한다. 커서 자리에 그대로 붙여 준다.
    var body = document.getElementById('scriptBody');
    document.querySelectorAll('[data-example]').forEach(function (button) {
        button.addEventListener('click', function () {
            if (!body) {
                return;
            }
            var snippet = button.getAttribute('data-snippet') || '';
            var at = body.selectionStart;
            var before = body.value.slice(0, at);
            var after = body.value.slice(body.selectionEnd);
            // 줄 가운데에 붙으면 문법이 깨진다. 항상 새 줄에서 시작한다.
            var lead = before.endsWith('\n') || before === '' ? '' : '\n';
            body.value = before + lead + snippet + '\n' + after;
            body.focus();
            body.selectionStart = body.selectionEnd = at + lead.length + snippet.length + 1;
        });
    });

    // ── 순서 끌어 옮기기 ─────────────────────────────────────────
    var list = document.querySelector('[data-script-list]');
    if (!list) {
        return;
    }
    var dragging = null;

    list.querySelectorAll('[data-script-row]').forEach(function (row) {
        row.setAttribute('draggable', 'true');

        row.addEventListener('dragstart', function () {
            dragging = row;
            row.classList.add('opacity-50');
        });
        row.addEventListener('dragend', function () {
            row.classList.remove('opacity-50');
            dragging = null;
            submitOrder();
        });
        row.addEventListener('dragover', function (event) {
            event.preventDefault();
            if (!dragging || dragging === row) {
                return;
            }
            // 잡은 것이 가리키는 줄보다 위에 있으면 아래로, 아니면 위로 넣는다.
            var rows = Array.prototype.slice.call(
                list.querySelectorAll('[data-script-row]'));
            var from = rows.indexOf(dragging);
            var to = rows.indexOf(row);
            list.insertBefore(dragging, from < to ? row.nextSibling : row);
        });
    });

    function submitOrder() {
        var form = document.querySelector('[data-script-order-form]');
        if (!form) {
            return;
        }
        // 폼에 순서를 다시 채워 넣는다. 화면에 보이는 그대로가 곧 처리 순서다.
        form.querySelectorAll('[name="scriptIds"]').forEach(function (input) {
            input.remove();
        });
        list.querySelectorAll('[data-script-row]').forEach(function (row) {
            var input = document.createElement('input');
            input.type = 'hidden';
            input.name = 'scriptIds';
            input.value = row.getAttribute('data-script-row');
            form.appendChild(input);
        });
        form.submit();
    }

    /*
     * 끌어 옮길 수 있게 됐으니 ▲▼ 버튼은 군더더기다. 반대로 이 파일이 막히면 버튼이 남아
     * 순서를 바꿀 길이 된다 — 고를 수는 있는데 반영할 방법이 없는 상태를 만들지 않는다.
     */
    document.querySelectorAll('[data-move-fallback]').forEach(function (button) {
        button.classList.add('hidden');
    });
})();
