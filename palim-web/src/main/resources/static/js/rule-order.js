/*
 * 이름 다듬기 규칙 화면의 두 가지 손놀림.
 *
 * ① 끌어서 순서 바꾸기 — 규칙은 위에서부터 차례로 걸리고 순서가 결과를 바꾼다. 한 칸씩
 *    올리는 버튼만 있으면 「맨 아래를 맨 위로」 에 규칙 수만큼 눌러야 하고, 누를 때마다 화면이
 *    새로 그려져 어디까지 옮겼는지 놓친다.
 *
 * ② 프리셋을 칸에 채우기 — 넣는 것이 아니라 채우기만 한다. 정규식을 백지에서 쓰지 않게
 *    도와줄 뿐, 고를 수 있는 것만 주는 것이 아니다. 채운 뒤에 마음대로 고친다.
 *
 * 화면 안에 박아 두지 않는 이유는 이 서비스의 보안 정책(CSP) 이 박힌 스크립트를 실행하지 않기
 * 때문이다. 그렇게 두면 한 줄도 돌지 않으면서 콘솔에만 위반 기록이 남아, 화면만 봐서는
 * 「왜 안 되지」 를 알 수 없다.
 *
 * 그리고 이 파일이 막히거나 실패해도 순서를 바꿀 길은 남아 있어야 한다. 끌기가 유일한 방법이면
 * 스크립트가 죽는 순간 그 화면은 순서를 못 바꾸는 화면이 된다 — 키보드만 쓰는 사람도 마찬가지다.
 * 그래서 한 칸씩 옮기는 버튼을 화면에 두고, 이 파일이 살아 있을 때만 감춘다.
 */
(function () {
    'use strict';

    document.querySelectorAll('[data-preset-pattern]').forEach(function (button) {
        button.addEventListener('click', function () {
            var form = document.getElementById(button.dataset.presetTarget);
            if (!form) {
                return;
            }
            form.querySelector('[name="name"]').value = button.dataset.presetName || '';
            form.querySelector('[name="pattern"]').value = button.dataset.presetPattern || '';
            form.querySelector('[name="replacement"]').value =
                button.dataset.presetReplacement || '';
            form.querySelector('[name="pattern"]').focus();
        });
    });

    var body = document.querySelector('[data-reorder-rows]');
    var form = document.getElementById('reorderForm');
    if (!body || !form) {
        return;
    }

    var dragged = null;

    function saveOrder() {
        form.querySelectorAll('input[name="id"]').forEach(function (stale) {
            stale.remove();
        });
        body.querySelectorAll('[data-id]').forEach(function (row) {
            var input = document.createElement('input');
            input.type = 'hidden';
            input.name = 'id';
            input.value = row.dataset.id;
            form.appendChild(input);
        });
        form.submit();
    }

    body.querySelectorAll('[data-id]').forEach(function (row) {
        row.setAttribute('draggable', 'true');

        row.addEventListener('dragstart', function () {
            dragged = row;
            row.classList.add('opacity-40');
        });

        row.addEventListener('dragend', function () {
            row.classList.remove('opacity-40');
        });

        row.addEventListener('dragover', function (event) {
            event.preventDefault();
        });

        row.addEventListener('drop', function (event) {
            event.preventDefault();
            if (!dragged || dragged === row) {
                return;
            }
            var rows = Array.prototype.slice.call(body.children);
            if (rows.indexOf(dragged) < rows.indexOf(row)) {
                row.after(dragged);
            } else {
                row.before(dragged);
            }
            /*
             * 놓는 순간 저장한다. 따로 「저장」 을 누르게 하면 옮겨 놓고 안 누른 채 떠나는 일이
             * 생기고, 다음에 왔을 때 왜 그대로인지 알 방법이 없다.
             */
            saveOrder();
        });
    });

    // 끌기가 살아 있으면 한 칸씩 옮기는 버튼은 군더더기다. 여기까지 왔다는 것이 그 증거다.
    document.querySelectorAll('[data-order-fallback]').forEach(function (button) {
        button.classList.add('hidden');
    });

    document.querySelectorAll('[data-drag-handle]').forEach(function (handle) {
        handle.classList.remove('hidden');
    });
})();
