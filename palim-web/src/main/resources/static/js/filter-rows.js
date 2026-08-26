/*
 * 볼 조건 편집 — 줄을 더하고 지우고, 담긴 값을 골라 넣는다.
 *
 * 파일로 두는 이유: CSP 가 script-src 'self' 라 화면에 박은 코드는 브라우저가 한 줄도
 * 실행하지 않는다. 그런데 화면은 200 으로 멀쩡히 열리고 버튼도 보인다 — 눌러도 아무 일이
 * 없을 뿐이라 사람 눈으로는 찾기 어렵다. 실제로 이 화면의 「+ 조건 추가」 가 그렇게 죽어
 * 있었고, 그동안 대조는 전 창고를 더해 견줬다.
 *
 * 이 코드가 없어도 조건은 걸 수 있어야 한다. 화면이 줄 하나를 늘 띄워 두고 값 칸이 글 입력이라,
 * 여기가 죽으면 「줄 하나짜리 조건」 으로 좁아질 뿐 막히지는 않는다.
 */
(function () {
    'use strict';

    /* 줄 하나 더하기. 서버 왕복을 두면 줄 하나 더할 때마다 화면이 깜빡이고 적던 값이 날아간다. */
    document.querySelectorAll('[data-add]').forEach(function (button) {
        button.addEventListener('click', function () {
            var side = button.dataset.side;
            var template = document.getElementById('row-template-' + side);
            var rows = document.getElementById('rows-' + side);
            if (!template || !rows) {
                return;
            }
            rows.appendChild(template.content.cloneNode(true));
        });
    });

    /* 줄 지우기. 줄은 나중에 생기기도 하므로 문서에 한 번만 건다. */
    document.addEventListener('click', function (event) {
        var button = event.target.closest('[data-remove]');
        if (button) {
            var row = button.closest('[data-row]');
            if (row) {
                row.remove();
            }
        }
    });

    /*
     * 담긴 값을 골라 조건에 넣는다.
     *
     * 목록을 보여주면서 고르게 하지 않으면 그 목록을 반만 쓰는 것이다 — 창고가 「300 정도로지스」
     * 인 것을 확인하고도 조건 칸에 300 을 손으로 옮겨 적어야 했다. 옮겨 적는 동안 틀리고,
     * 틀리면 조건이 아무것도 거르지 않는데 화면은 「걸려 있다」 고 말한다.
     */
    document.addEventListener('change', function (event) {
        var box = event.target.closest('[data-pick-field]');
        if (!box) {
            return;
        }
        var side = box.dataset.pickSide;
        var field = box.dataset.pickField;
        var rows = document.getElementById('rows-' + side);
        if (!rows) {
            return;
        }

        var input = valueInputFor(rows, field);
        if (!input) {
            input = addRowFor(side, rows, field);
        }
        if (!input) {
            return;
        }

        var picked = split(input.value);
        var value = box.dataset.pickValue;
        var at = picked.indexOf(value);
        if (box.checked && at < 0) {
            picked.push(value);
        } else if (!box.checked && at >= 0) {
            picked.splice(at, 1);
        }
        input.value = picked.join('|');
    });

    /* 그 칸에 이미 걸린 줄의 값 입력칸. 없으면 null. */
    function valueInputFor(rows, field) {
        var found = null;
        rows.querySelectorAll('[data-row]').forEach(function (row) {
            if (found) {
                return;
            }
            var select = row.querySelector('select[name="fieldKey"]');
            if (select && select.value === field) {
                found = row.querySelector('input[name="values"]');
            }
        });
        return found;
    }

    /* 그 칸으로 줄을 하나 새로 만들고 값 입력칸을 돌려준다. */
    function addRowFor(side, rows, field) {
        var template = document.getElementById('row-template-' + side);
        if (!template) {
            return null;
        }
        rows.appendChild(template.content.cloneNode(true));
        var row = rows.lastElementChild;
        var select = row.querySelector('select[name="fieldKey"]');
        if (select) {
            select.value = field;
        }
        // 값을 여럿 고를 수 있어야 하므로 «이것만» 으로 시작한다. 「같음」 이면 두 번째 값을
        // 고르는 순간 조건이 조용히 뜻을 잃는다.
        var operator = row.querySelector('select[name="operator"]');
        if (operator) {
            operator.value = 'IN';
        }
        return row.querySelector('input[name="values"]');
    }

    /* 세로줄로 이어 둔 값을 나눈다. 서버의 FilterValues 와 같은 규칙이다. */
    function split(raw) {
        if (!raw) {
            return [];
        }
        return raw.split('|')
                .map(function (value) { return value.trim(); })
                .filter(function (value) { return value.length > 0; });
    }
})();
