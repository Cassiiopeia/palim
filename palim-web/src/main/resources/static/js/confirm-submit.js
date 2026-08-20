/*
 * 되돌릴 수 없는 제출은 한 번 묻는다.
 *
 * 태그 안에 onclick="return confirm(...)" 을 적으면 이 서비스의 보안 정책(CSP)이 실행하지
 * 않는다 — 한 줄도 돌지 않고 콘솔에만 위반 기록이 남아, 화면만 봐서는 «물어보지 않는» 것이
 * 그냥 그런 설계인지 고장인지 알 수 없다(auto-submit.js 가 같은 이유로 파일이다).
 *
 * 묻는 말은 버튼이 정한다. 「정말 지울까요?」 만으로는 아무 정보가 없어 사람이 그냥 누른다.
 * 무엇이 함께 사라지는지 적어야 손이 멈춘다.
 */
(function () {
    'use strict';

    document.querySelectorAll('[data-confirm]').forEach(function (button) {
        button.addEventListener('click', function (event) {
            if (!window.confirm(button.getAttribute('data-confirm'))) {
                event.preventDefault();
            }
        });
    });
})();
