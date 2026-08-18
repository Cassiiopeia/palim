package kr.suhsaechan.palim.reconcile.rule;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;

/**
 * <b>사람이 넣은 정규식</b>을 돌리는 자리를 감싼다.
 *
 * <p>이 제품은 정규식을 사용자가 직접 넣는다. 어떤 품목명 체계가 올지 미리 알 수 없어 그 길을
 * 막지 않기로 했기 때문이다. 대신 그 자유에는 대가가 따른다 — {@code (a+)+$} 같은 되돌아가는
 * 패턴 하나면 매칭이 사실상 끝나지 않는다.
 *
 * <p><b>톰캣은 이미 돌고 있는 요청 스레드를 죽이지 않는다.</b> 브라우저가 기다리다 포기해도 그
 * 스레드는 계속 돈다. 몇 번 반복하면 스레드 풀이 마르고 서버 전체가 응답을 멈춘다. 그래서 정규식을
 * 돌리는 자리는 <b>딴 스레드에 맡기고 제한 시간을 건다.</b>
 *
 * <p>그런데 자바의 {@code Matcher} 는 인터럽트를 보지 않아 {@code cancel(true)} 만으로는 멈추지
 * 않는다. {@link #guard} 로 감싼 입력은 글자를 내줄 때마다 인터럽트를 확인하므로 그때 실제로 멈춘다.
 *
 * <p>이 두 가지를 한 곳에 둔 이유는, 정규식을 돌리는 자리가 <b>미리보기 말고도 늘어났기</b>
 * 때문이다. 같은 보호를 각자 다시 짜면 한쪽만 고쳐져 어긋나고, 어긋난 쪽은 「가끔 서버가 멈춘다」
 * 로만 드러나 원인을 찾기 어렵다.
 */
@Slf4j
public final class RegexGuard {

    private RegexGuard() {
    }

    /**
     * 제한 시간을 걸어 돌린다.
     *
     * <p>한 번에 스레드 하나를 쓰고 끝나면 접는다. 데몬으로 두는 이유는, 감싸기가 어떤 이유로 안
     * 먹어 스레드가 살아남더라도 <b>서버 종료를 붙잡지 않게</b> 하려는 것이다. 요청 스레드는 어떤
     * 경우에도 제한 시간 안에 풀려난다 — 그것이 이 구조의 목적이다.
     */
    public static <T> T runWithTimeout(Duration timeout, Callable<T> work) {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "regex-guard");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<T> future = executor.submit(work);
            try {
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new BusinessException(ErrorCode.NORMALIZATION_PREVIEW_TIMEOUT);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof BusinessException business) {
                    throw business;
                }
                log.warn("정규식 실행 실패", e);
                throw new BusinessException(ErrorCode.NORMALIZATION_PREVIEW_TIMEOUT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.NORMALIZATION_PREVIEW_TIMEOUT);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /** 인터럽트를 확인하는 입력으로 감싼다. 이것이 없으면 취소가 아무 일도 하지 않는다. */
    public static CharSequence guard(CharSequence value) {
        return new Interruptible(value);
    }

    /**
     * 글자를 내줄 때마다 인터럽트를 확인하는 입력.
     *
     * <p>자바의 {@code Matcher} 는 인터럽트를 보지 않는다. 읽을 때마다 확인하게 만들면 취소가
     * 실제로 먹는다.
     */
    private static final class Interruptible implements CharSequence {

        private final CharSequence inner;

        private Interruptible(CharSequence inner) {
            this.inner = inner;
        }

        @Override
        public char charAt(int index) {
            if (Thread.currentThread().isInterrupted()) {
                throw new BusinessException(ErrorCode.NORMALIZATION_PREVIEW_TIMEOUT);
            }
            return inner.charAt(index);
        }

        @Override
        public int length() {
            return inner.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Interruptible(inner.subSequence(start, end));
        }

        @Override
        public String toString() {
            return inner.toString();
        }
    }
}
