package kr.suhsaechan.palim.reconcile.define;

/**
 * 견줄 <b>두 원천과 각각의 창고 범위</b>를 한 묶음으로.
 *
 * <p>원천 이름만 넘기던 시절에는 창고를 <b>넘길 자리가 없어서</b> 빠뜨렸다. 자료에는 창고가
 * 들어 있었고 표의 자연키에도 창고가 있었는데, 견주는 쪽만 그것을 보지 않았다. 그 결과 위탁하지
 * 않은 창고 물량까지 합산되어 맞던 품목이 틀린 것으로 나왔다.
 *
 * <p>둘을 묶어 두면 <b>빠뜨릴 수 없다.</b> 새 조회를 만들 때 원천을 넘기는 순간 창고도 함께
 * 넘어간다 — 이것이 이 타입의 존재 이유다. 나중에 「로트만 본다」 같은 범위가 늘어도 여기만
 * 넓히면 모든 조회가 함께 따라온다.
 *
 * <p><b>창고만 담지 않는다.</b> 「어느 칸을 더할지」({@code compareField})도 같은 성격이다 —
 * 정의가 정하는데 조회가 안 받으면 각자 다른 칸을 더한다. 실제로 뜯어보기가 {@code base_quantity}
 * 를 고정으로 박아 두어, 다른 칸을 고른 정의에서 합계와 상세가 어긋났다. 「견주는 방식」 을 한
 * 묶음으로 들고 다니면 새 조회를 만들 때 그중 하나만 빠뜨릴 수가 없다.
 *
 * @param leftSource   좌측 원천. 대개 전산(ERP)
 * @param rightSource  우측 원천. 대개 재고를 맡긴 곳
 * @param leftScope    좌측에서 볼 창고. 비어 있으면 전부
 * @param rightScope   우측에서 볼 창고. 비어 있으면 전부
 * @param compareField 더할 수치 칸. 허용 목록에 없으면 기본 칸으로 되돌린다
 */
public record Pairing(String leftSource, String rightSource,
                      WarehouseScope leftScope, WarehouseScope rightScope,
                      String compareField) {

    public Pairing {
        leftScope = leftScope == null ? WarehouseScope.all() : leftScope;
        rightScope = rightScope == null ? WarehouseScope.all() : rightScope;
        // 칸 이름은 SQL 에 그대로 들어간다. 담을 때 한 번 걸러 두면 쓰는 쪽이 매번 신경 쓰지 않는다.
        compareField = CompareField.sanitize(compareField);
    }

    /** 견줄 칸을 따로 정하지 않는 자리. 기본 칸으로 본다. */
    public Pairing(String leftSource, String rightSource,
                   WarehouseScope leftScope, WarehouseScope rightScope) {
        this(leftSource, rightSource, leftScope, rightScope, null);
    }

    /** 정의가 정한 그대로. 화면·엔진은 이 길로만 만든다. */
    public static Pairing of(ReconcileDefinition definition) {
        return new Pairing(definition.getLeftSource(), definition.getRightSource(),
                definition.leftScope(), definition.rightScope(), definition.getCompareField());
    }

    /**
     * 창고를 가리지 않는 짝.
     *
     * <p>정의가 아직 없는 자리(설정 안내, 시험)에서만 쓴다. 대조 화면이 이것을 쓰면 창고를
     * 나눈 뜻이 사라진다.
     */
    public static Pairing ofSources(String leftSource, String rightSource) {
        return new Pairing(leftSource, rightSource, WarehouseScope.all(), WarehouseScope.all());
    }

    /**
     * 그 원천에 걸린 창고 범위.
     *
     * <p>좌측 이름과 같으면 좌측, 아니면 우측으로 본다. 두 원천이 같은 이름일 수는 없다 —
     * 같으면 자기 자신과 견주는 정의이므로 애초에 만들어지지 않는다.
     */
    public WarehouseScope scopeOf(String source) {
        return leftSource.equals(source) ? leftScope : rightScope;
    }

    /**
     * 한쪽에 창고가 여럿인데 <b>고르지 않은</b> 상태인가.
     *
     * <p>그대로 두면 맡기지 않은 물량까지 합산되어 조용히 틀린 답이 나온다. 화면이 이때
     * 「창고를 고르세요」 를 띄운다.
     *
     * @param leftWarehouseCount  좌측 원천에 담긴 창고 수
     * @param rightWarehouseCount 우측 원천에 담긴 창고 수
     */
    public boolean needsWarehouseChoice(int leftWarehouseCount, int rightWarehouseCount) {
        return (leftWarehouseCount > 1 && leftScope.isAll())
                || (rightWarehouseCount > 1 && rightScope.isAll());
    }
}
