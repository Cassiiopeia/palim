/*
 * 세션 강제 종료 감시.
 *
 * 같은 계정으로 다른 곳에서 로그인하면 서버가 SSE 로 "duplicate" 이벤트를 밀어준다.
 * 안내를 띄운 뒤 로그인 화면으로 이동한다.
 *
 * 사내 CM 은 100ms setInterval 폴링을 쓰는데, 초당 10회 요청에 더해 매 요청이 세션 최근 사용
 * 시각을 갱신해 유휴 타임아웃을 무력화한다. SSE 는 그 부작용이 없다.
 *
 * 이 연결이 끊겨도 기능은 성립한다 — 만료된 세션의 다음 요청은 서버 필터가 로그인 화면으로
 * 보낸다. SSE 는 "화면을 보고만 있는 사용자에게 즉시 알리는" 역할만 한다.
 */
(function () {
    'use strict';

    // 로그인·확인 화면에서는 감시하지 않는다. 세션이 아직 인증 상태가 아니다.
    if (window.location.pathname.indexOf('/login') === 0) {
        return;
    }
    if (typeof EventSource === 'undefined') {
        return;
    }

    var source = new EventSource('/api/session/watch');

    source.addEventListener('duplicate', function () {
        source.close();
        window.alert('다른 곳에서 로그인되어 현재 접속이 종료됩니다.');
        window.location.href = '/login?expired=duplicate';
    });

    // 타임아웃·네트워크 단절 시 EventSource 가 스스로 재연결한다. 별도 처리 없음.
})();
