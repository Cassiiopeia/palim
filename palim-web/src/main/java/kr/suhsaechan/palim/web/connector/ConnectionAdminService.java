package kr.suhsaechan.palim.web.connector;

import kr.suhsaechan.palim.common.error.BusinessException;
import kr.suhsaechan.palim.common.error.ErrorCode;
import kr.suhsaechan.palim.connector.define.Connector;
import kr.suhsaechan.palim.connector.define.ConnectorRepository;
import kr.suhsaechan.palim.connector.define.SourceType;
import kr.suhsaechan.palim.connector.model.TargetModel;
import kr.suhsaechan.palim.connector.model.TargetModelRepository;
import kr.suhsaechan.palim.connector.secret.ConnectorSecretService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 연결 설정 저장.
 *
 * <p>커넥터 정의와 비밀값을 <b>한 트랜잭션에서</b> 만든다. 둘이 갈라지면 "정의는 있는데
 * 인증정보가 없는" 커넥터가 남고, 그 상태는 화면에서 정상으로 보이면서 실행할 때만 실패한다.
 */
@Service
@RequiredArgsConstructor
public class ConnectionAdminService {

    private final ConnectorRepository connectorRepository;
    private final TargetModelRepository targetModelRepository;
    private final ConnectorSecretService secretService;

    @Transactional
    public Connector saveConnection(ConnectionForm form) {
        String code = require(form.getCode(), "코드");
        String name = require(form.getName(), "이름");
        require(form.getSecret(), "인증정보");

        connectorRepository.findByTenantIdAndCode(ConnectorAdminService.DEFAULT_TENANT, code)
                .ifPresent(existing -> {
                    throw new BusinessException(ErrorCode.INVALID_INPUT, "이미 쓰는 코드입니다: " + code);
                });

        TargetModel model = targetModelRepository
                .findByTenantIdAndCode(ConnectorAdminService.DEFAULT_TENANT,
                        form.getTargetModelCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT,
                        "목표 모델을 찾을 수 없습니다: " + form.getTargetModelCode()));

        Connector connector = connectorRepository.save(Connector.of(
                ConnectorAdminService.DEFAULT_TENANT, code, name, model.getId(),
                SourceType.HTTP_API, form.getDefaultUnit()));

        String ref = ConnectorSecretService.refOf(code);
        // sourceConfig 에는 비밀값이 없다. credentialRef 는 "어디에 있는지"만 가리킨다.
        connector.configureSource(form.toSourceConfig(), ref);
        // 인증정보는 커넥터 정의가 아니라 별도 저장소로 간다. 정의는 화면이 늘 읽기 때문이다.
        secretService.put(ref, secretNameOf(form), form.getSecret());
        return connector;
    }

    /** 인증 흐름에 따라 값의 이름표가 다르다. 나중에 사람이 무엇을 넣었는지 알아볼 수 있어야 한다. */
    private String secretNameOf(ConnectionForm form) {
        return switch (form.getPreset()) {
            case ZONE_SESSION -> "apiKey";
            case FORM_SESSION -> "password";
        };
    }

    private String require(String value, String label) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(label + "을(를) 입력하세요.");
        }
        return value.trim();
    }
}
