#!/usr/bin/env python3
"""영상 자막을 추출해 JSON 으로 출력한다.

호출 규약 (04-CONVENTIONS):
  - 인자는 배열로 받는다. 쉘 문자열 조립 금지 — 영상 ID 는 외부 입력이다.
  - stdout 에는 JSON 만. 사람이 읽을 메시지는 stderr 로.
  - 종료코드 0(성공/자막없음) / 1(실패). "자막 없음" 은 오류가 아니라 정상 결과다.

사용:
  python3 youtube_transcript.py <video_id> [언어코드...]

출력:
  {"status": "OK", "language": "ko", "content": "..."}
  {"status": "NONE", "language": null, "content": null}      # 자막이 없는 영상
  {"status": "BLOCKED", "language": null, "content": null}    # 차단·차이 감지

주의:
  YouTube 공식 API 는 남의 영상 자막을 주지 않는다(captions.download 는 소유자 인증 필수).
  yt-dlp 로 공개 자동자막을 읽되, 이 경로는 언제든 깨질 수 있다는 전제로 쓴다 —
  실패는 예외가 아니라 정상 분기이며 호출자는 메타+댓글로 폴백한다.
"""

import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

# 자막 우선순위. 한국어 → 자동생성 한국어 → 영어 순으로 시도한다.
DEFAULT_LANGUAGES = ["ko", "ko-KR", "en"]

# 자막 1편의 상한. 이보다 길면 잘라낸다 — AI 입력 토큰이 비용의 대부분이다.
MAX_CHARS = 8000

TIMEOUT_SECONDS = 60


def emit(status, language=None, content=None, exit_code=0):
    """stdout 에 JSON 만 쓰고 종료한다."""
    print(json.dumps({"status": status, "language": language, "content": content},
                     ensure_ascii=False))
    sys.exit(exit_code)


def strip_vtt(raw):
    """WebVTT 를 평문으로.

    타임코드·위치 지시자·중복 줄을 제거한다. 자동자막은 같은 문장을 여러 큐에 겹쳐
    출력하므로 중복 제거를 하지 않으면 길이가 3배로 부풀고 AI 입력이 낭비된다.
    """
    lines = []
    for line in raw.splitlines():
        line = line.strip()
        if not line or line.startswith(("WEBVTT", "Kind:", "Language:")):
            continue
        if "-->" in line:
            continue
        # <00:00:01.000><c> 같은 인라인 타임태그 제거
        line = re.sub(r"<[^>]+>", "", line).strip()
        if not line:
            continue
        if lines and lines[-1] == line:
            continue
        lines.append(line)
    return " ".join(lines)


def fetch(video_id, languages):
    with tempfile.TemporaryDirectory() as workdir:
        output = str(Path(workdir) / "sub")
        command = [
            "yt-dlp",
            "--skip-download",
            "--write-auto-subs",
            "--write-subs",
            "--sub-langs", ",".join(languages),
            "--sub-format", "vtt",
            "--no-warnings",
            "--output", output,
            f"https://www.youtube.com/watch?v={video_id}",
        ]

        try:
            result = subprocess.run(command, capture_output=True, text=True,
                                    timeout=TIMEOUT_SECONDS)
        except FileNotFoundError:
            print("yt-dlp 가 설치되어 있지 않습니다", file=sys.stderr)
            emit("BLOCKED", exit_code=1)
        except subprocess.TimeoutExpired:
            print("자막 추출 시간 초과", file=sys.stderr)
            emit("BLOCKED", exit_code=1)

        if result.returncode != 0:
            # 비공개·삭제·지역 제한·차단이 모두 여기로 온다. 구분해도 대응이 같으므로 묶는다.
            print(f"yt-dlp 실패: {result.stderr[:500]}", file=sys.stderr)
            emit("BLOCKED", exit_code=0)

        # 우선순위 순으로 첫 번째 매칭 파일을 쓴다.
        for language in languages:
            for path in Path(workdir).glob(f"sub.{language}*.vtt"):
                text = strip_vtt(path.read_text(encoding="utf-8", errors="replace"))
                if text:
                    return language, text[:MAX_CHARS]

        return None, None


def main():
    if len(sys.argv) < 2:
        print("사용법: youtube_transcript.py <video_id> [언어코드...]", file=sys.stderr)
        emit("BLOCKED", exit_code=1)

    video_id = sys.argv[1]
    # 영상 ID 는 외부 입력이다. 형식을 검증해 예기치 않은 인자가 섞이지 않게 한다.
    if not re.fullmatch(r"[A-Za-z0-9_-]{5,32}", video_id):
        print("영상 ID 형식이 올바르지 않습니다", file=sys.stderr)
        emit("BLOCKED", exit_code=1)

    languages = sys.argv[2:] or DEFAULT_LANGUAGES

    language, content = fetch(video_id, languages)
    if content:
        emit("OK", language, content)
    emit("NONE")


if __name__ == "__main__":
    main()
