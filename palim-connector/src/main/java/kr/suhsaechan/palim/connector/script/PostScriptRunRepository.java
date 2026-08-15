package kr.suhsaechan.palim.connector.script;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostScriptRunRepository extends JpaRepository<PostScriptRun, UUID> {

    /** 화면에서 「최근에 몇 건 바뀌었나」를 보여줄 때. */
    List<PostScriptRun> findByScriptIdOrderByStartedAtDesc(UUID scriptId);
}
