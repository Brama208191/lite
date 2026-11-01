package dev.keel.tools;

import dev.keel.core.ToolSpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class NotesAppendTool implements Tool {

    private static final ToolSpec SPEC = new ToolSpec("notes_append",
            "Append one line to a shared notes page (a durable key-value log other runs can read).",
            Schemas.object(new LinkedHashMap<>(Map.of(
                    "key", Schemas.string("Notes page name, e.g. orders"),
                    "line", Schemas.string("Text to append")))));

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Kind kind() {
        return Kind.TRANSACTIONAL;
    }

    @Override
    public ToolResult executeInTransaction(ToolInvocation invocation, JdbcTemplate db) {
        var key = invocation.requireText("key");
        var line = invocation.requireText("line");
        if (key.length() > 100 || line.length() > 2000) {
            return ToolResult.error("'key' is limited to 100 characters and 'line' to 2000");
        }
        db.update("insert into notes (key, line, run_id) values (?, ?, ?)", key, line, invocation.runId());
        Integer count = db.queryForObject("select count(*) from notes where key = ?", Integer.class, key);
        return ToolResult.ok("Added to the " + key + " notes (" + count + (count == 1 ? " line" : " lines") + " so far).");
    }
}
