/*
 * 값을 고르면 그 자리에서 반영한다.
 *
 * 예전에는 <select onchange="this.form.submit()"> 처럼 태그 안에 직접 적었는데, 이 서비스의
 * 보안 정책(CSP)은 화면 안에 박힌 스크립트를 실행하지 않는다. 그래서 그 코드는 **한 줄도 돌지
 * 않았고**, 고르기만 하면 아무 일도 일어나지 않았다. 콘솔에만 위반 기록이 남아 화면만 봐서는
 * 알 수 없었다. 파일로 두면 같은 정책 안에서 정상으로 동작한다.
 */
(function () {
    'use strict';

    document.querySelectorAll('[data-auto-submit]').forEach(function (field) {
        field.addEventListener('change', function () {
            if (field.form) {
                field.form.submit();
            }
        });
    });

    /*
     * 스크립트가 살아 있으면 「바꾸기」 버튼은 군더더기다. 반대로 이 파일이 막히거나 실패하면
     * 버튼이 그대로 남아 길이 된다 — 고를 수는 있는데 반영할 방법이 없는 상태를 만들지 않는다.
     */
    document.querySelectorAll('[data-auto-submit-fallback]').forEach(function (button) {
        button.classList.add('hidden');
    });
})();
