-- ============================================================
-- 발송 설정과 그 비밀값 (#173)
--
-- 메일 서버 정보를 화면에서 넣고 고칠 수 있어야 한다. 설정 파일에 두면 값을 바꿀 때마다
-- 재배포해야 하는데, 이 저장소는 그 판단을 이미 내렸다(07-DECISIONS 014).
--
-- 정의와 비밀값을 «나눈다». 설정은 화면이 통째로 읽어 그리는 것이라, 비밀번호가 그 안에
-- 들어가는 순간 화면·감사 기록·직렬화로 새는 길이 한꺼번에 열린다.
-- ============================================================

-- 한 줄만 있는 표다. 보내는 곳이 여럿일 이유가 아직 없고, 여럿을 허용하면 「어느 것이
-- 쓰이는가」 를 화면과 코드가 각자 판단하게 된다.
CREATE TABLE delivery_setting
(
    id            uuid         NOT NULL,
    -- 메일 서버. 비어 있으면 «아직 넣지 않음» 이고, 그때는 메일이 나가지 않는다.
    smtp_host     varchar(200),
    smtp_port     integer      NOT NULL DEFAULT 587,
    smtp_username varchar(200),
    from_address  varchar(200),
    use_start_tls boolean      NOT NULL DEFAULT true,
    -- 받는 사람. 쉼표로 나눈다.
    recipients    varchar(500) NOT NULL DEFAULT '',
    -- 메일로 무엇까지 받을지. DIGEST_ONLY · DIGEST_AND_URGENT · ALL
    mail_scope    varchar(20)  NOT NULL DEFAULT 'DIGEST_ONLY',
    -- 하루 한 통을 보내는 시각(KST).
    digest_hour   integer      NOT NULL DEFAULT 7,
    digest_minute integer      NOT NULL DEFAULT 30,
    version       bigint,
    created_at    timestamptz,
    updated_at    timestamptz,
    CONSTRAINT pk_delivery_setting PRIMARY KEY (id)
);

-- 알림 쪽 비밀값. 연동 쪽(connector_secret)과 같은 모양이다.
--
-- 저장되는 것은 암호문뿐이다. 암호를 푸는 열쇠는 설정에 있고 DB 에 들어가지 않는다 —
-- 둘이 같은 곳에 있으면 한 번의 유출로 둘 다 잃는다.
CREATE TABLE notification_secret
(
    id              uuid        NOT NULL,
    tenant_id       uuid        NOT NULL,
    -- 값의 이름표(smtp.password). 암호를 푸는 열쇠가 아니다.
    secret_name     varchar(50) NOT NULL,
    -- base64(nonce || ciphertext || tag). AES-256-GCM.
    encrypted_value text        NOT NULL,
    created_at      timestamptz,
    updated_at      timestamptz,
    CONSTRAINT pk_notification_secret PRIMARY KEY (id)
);

-- 같은 이름의 값이 두 벌 저장되면 어느 것이 쓰이는지 알 수 없다.
CREATE UNIQUE INDEX ux_notification_secret
    ON notification_secret (tenant_id, secret_name);
