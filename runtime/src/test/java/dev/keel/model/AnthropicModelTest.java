package dev.keel.model;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Message;
import dev.keel.KeelProperties;
import dev.keel.core.Block;
import dev.keel.core.Payloads;
import dev.keel.core.RunState;
import dev.keel.core.ToolSpec;
import dev.keel.core.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicModelTest {

    private static final String RESPONSE = """
            {"id":"msg_01","type":"message","role":"assistant","model":"claude-opus-5-5",
             "content":[
               {"type":"thinking","thinking":"","signature":"sig-123"},
               {"type":"text","text":"Sending the confirmation."},
               {"type":"tool_use","id":"toolu_01","name":"send_email",
                "input":{"to":"ada@example.com","subject":"Order ORD-1 confirmed","body":"Thanks"}}],
             "stop_reason":"tool_use","stop_sequence":null,
             "usage":{"input_tokens":812,"output_tokens":95}}
            """;

    @Test
    void responseKeepsTextAndToolUseAndTheRawBlocksForReplay() throws Exception {
        var reply = AnthropicModel.toReply(ObjectMappers.jsonMapper().readValue(RESPONSE, Message.class));

        assertThat(reply.stopReason()).isEqualTo("tool_use");
        assertThat(reply.text()).isEqualTo("Sending the confirmation.");
        assertThat(reply.toolUses()).singleElement().satisfies(use -> {
            assertThat(use.id()).isEqualTo("toolu_01");
            assertThat(use.input().get("to").asText()).isEqualTo("ada@example.com");
        });
        assertThat(reply.inputTokens()).isEqualTo(812);
        assertThat(reply.raw()).hasSize(3);
        assertThat(reply.raw().get(0).get("signature").asText()).isEqualTo("sig-123");
    }

    @Test
    void storedTurnsBecomeTheExactConversationIncludingThinkingBlocks() throws Exception {
        var reply = AnthropicModel.toReply(ObjectMappers.jsonMapper().readValue(RESPONSE, Message.class));
        var stored = Json.read(Json.tree(reply), dev.keel.core.ModelReply.class);
        var turn = new RunState.Turn(0, stored,
                Map.of("toolu_01", new Payloads.ToolCompleted("toolu_01", "{\"id\":\"em_1\"}", false)), Map.of("toolu_01", 1));
        var spec = new ToolSpec("send_email", "Send one email",
                Json.parse("{\"type\":\"object\",\"properties\":{\"to\":{\"type\":\"string\"}},\"required\":[\"to\"]}"));
        var request = new ModelRequest(UUID.randomUUID(), "Confirm order ORD-1", List.of(turn), List.of(spec));
        var config = new KeelProperties.Anthropic("test-key", "claude-opus-5-5", 8000, "low", true);

        var params = new AnthropicModel(config).params(request);

        assertThat(params.model().asString()).isEqualTo("claude-opus-5-5");
        assertThat(params.tools().orElseThrow()).hasSize(1);
        var messages = params.messages();
        assertThat(messages).hasSize(3);
        var assistant = messages.get(1).content().asBlockParams();
        assertThat(assistant).hasSize(3);
        assertThat(assistant.get(0).isThinking()).isTrue();
        assertThat(assistant.get(0).asThinking().signature()).isEqualTo("sig-123");
        assertThat(assistant.get(2).asToolUse().id()).isEqualTo("toolu_01");
        var result = messages.get(2).content().asBlockParams().getFirst().asToolResult();
        assertThat(result.toolUseId()).isEqualTo("toolu_01");
    }

    @Test
    void fakeRepliesWithoutRawBlocksAreRebuiltFromTheirContent() {
        var reply = new dev.keel.core.ModelReply(List.of(new Block.Text("hi"),
                new Block.ToolUse("toolu_x", "notes_read", Json.parse("{\"key\":\"orders\"}"))), "tool_use", "fake", 0, 0, null);
        var request = new ModelRequest(UUID.randomUUID(), "task", List.of(new RunState.Turn(0, reply, Map.of(), Map.of())), List.of());

        var messages = AnthropicModel.messages(request);

        assertThat(messages).hasSize(2);
        var blocks = messages.get(1).content().asBlockParams();
        assertThat(blocks.get(0).asText().text()).isEqualTo("hi");
        assertThat(blocks.get(1).asToolUse().name()).isEqualTo("notes_read");
    }
}
