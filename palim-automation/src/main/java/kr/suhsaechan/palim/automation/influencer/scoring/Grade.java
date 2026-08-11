package kr.suhsaechan.palim.automation.influencer.scoring;

/** 종합 등급. 룰 70 + AI 30 합산 총점 기준(AI 미심사 시 룰 점수만으로 잠정 등급). */
public enum Grade {
    S, A, B, C, D;

    public static Grade of(double total, ScoringProperties.GradeProps props) {
        if (total >= props.s()) {
            return S;
        }
        if (total >= props.a()) {
            return A;
        }
        if (total >= props.b()) {
            return B;
        }
        if (total >= props.c()) {
            return C;
        }
        return D;
    }
}
