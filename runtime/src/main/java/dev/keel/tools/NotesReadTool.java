package dev.keel.tools;

import dev.keel.core.ToolSpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class NotesReadTool implements Tool {

    private static final ToolSpec SPEC = new ToolSpec("notes_read",
            "Read the last 50 lines of a shared notes page.",
            Schemas.object(Map.of("key", Schemas.string("Notes page name"))));

    private final JdbcTemplate jdbc;

    public NotesReadTool(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Kind kind() {
        return Kind.READ_ONLY;
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        var key = invocation.requireText("key");
        var lines = jdbc.queryForList("""
                select line from (select id, line from notes where key = ? order by id desc limit 50) recent order by id
                """, String.class, key);
        return ToolResult.ok(lines.isEmpty() ? "(empty)" : String.join("\n", lines));
    }
}
