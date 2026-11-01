package dev.keel.tools;

import dev.keel.core.ToolSpec;
import org.springframework.jdbc.core.JdbcTemplate;

public interface Tool {

    enum Kind { READ_ONLY, TRANSACTIONAL, EXTERNAL_EFFECT }

    ToolSpec spec();

    Kind kind();

    default ToolResult execute(ToolInvocation invocation) {
        throw new UnsupportedOperationException(spec().name() + " runs inside the event transaction");
    }

    default ToolResult executeInTransaction(ToolInvocation invocation, JdbcTemplate db) {
        throw new UnsupportedOperationException(spec().name() + " does not run inside the event transaction");
    }
}
