-- ============================================================
-- 연동 인증정보 (#64)
--
-- 커넥터 정의(connector)와 비밀값을 분리한다. 정의는 목록·매핑 화면이 늘 읽지만 비밀값은
-- 실행 순간에만 필요하다. 한 테이블에 두면 화면 조회 경로에 비밀값이 딸려 나가는 길이 생긴다.
--
-- 저장되는 것은 암호문뿐이다. 암호화 마스터키는 설정(palim.crypto.master-key)에 있고
-- DB 에 들어가지 않는다 — 둘이 같은 곳에 있으면 한 번의 유출로 둘 다 잃는다.
-- ============================================================

CREATE TABLE connector_secret
(
    id              uuid         NOT NULL,
    tenant_id       uuid         NOT NULL,
    -- 커넥터가 credential_ref 로 이 묶음을 가리킨다. 커넥터를 지워도 남아 재사용할 수 있다.
    credential_ref  varchar(100) NOT NULL,
    -- 값의 이름표(apiKey·password). 암호키가 아니다.
    secret_name     varchar(50)  NOT NULL,
    -- base64(nonce || ciphertext || tag). AES-256-GCM.
    encrypted_value text         NOT NULL,
    created_at      timestamptz,
    updated_at      timestamptz,
    CONSTRAINT pk_connector_secret PRIMARY KEY (id)
);

-- 같은 이름의 값이 두 벌 저장되면 어느 것이 쓰이는지 알 수 없다.
CREATE UNIQUE INDEX ux_connector_secret
    ON connector_secret (tenant_id, credential_ref, secret_name);
CREATE INDEX ix_connector_secret_ref ON connector_secret (tenant_id, credential_ref);
