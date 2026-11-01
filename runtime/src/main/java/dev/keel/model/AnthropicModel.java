package dev.keel.model;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import dev.keel.KeelProperties;
import dev.keel.core.Block;
import dev.keel.core.Json;
import dev.keel.core.ModelReply;
import dev.keel.core.RunState;
import dev.keel.core.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AnthropicModel implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger("keel.model");

    static final String SYSTEM = """
            You are an operations agent running inside a crash-safe runtime. Complete the user's task with the \
            tools provided. Each tool call is recorded and executed at most once, so never repeat a call that \
            already returned a result. Keep tool inputs minimal and concrete. When the task is done, reply with \
            a short summary of what you did and stop.""";

    private final KeelProperties.Anthropic config;
    private final AnthropicClient client;

    public AnthropicModel(KeelProperties.Anthropic config) {
        this.config = config;
        this.client = AnthropicOkHttpClient.builder().apiKey(config.apiKey()).maxRetries(2).build();
    }

    @Override
    public String id() {
        return "anthropic";
    }

    @Override
    public ModelReply next(ModelRequest request) {
        log.info("keel.model.call run={} turn={} model={}", request.runId(), request.turns().size(), config.model());
        try {
            var message = client.messages().create(params(request));
            return toReply(message);
        } catch (RateLimitException | InternalServerException | AnthropicIoException e) {
            throw new ModelException("Claude API unavailable: " + e.getMessage(), true, e);
        } catch (AnthropicServiceException e) {
            throw new ModelException("Claude API rejected the request (" + e.statusCode() + "): " + e.getMessage(), false, e);
        }
    }

    MessageCreateParams params(ModelRequest request) {
        var builder = MessageCreateParams.builder()
                .model(config.model())
                .maxTokens(config.maxTokens())
                .system(SYSTEM)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(config.effort())).build())
                .messages(messages(request));
        for (var spec : request.tools()) {
            builder.addTool(tool(spec));
        }
        if (config.fallbacks()) {
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01");
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return builder.build();
    }

    static List<MessageParam> messages(ModelRequest request) {
        var messages = new ArrayList<MessageParam>();
        messages.add(MessageParam.builder().role(MessageParam.Role.USER).content(request.task()).build());
        for (var turn : request.turns()) {
            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.ASSISTANT)
                    .contentOfBlockParams(assistantBlocks(turn))
                    .build());
            var results = new ArrayList<ContentBlockParam>();
            for (var use : turn.reply().toolUses()) {
                var result = turn.results().get(use.id());
                if (result != null) {
                    results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(use.id())
                            .content(result.content())
                            .isError(result.isError())
                            .build()));
                }
            }
            if (!results.isEmpty()) {
                messages.add(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(results).build());
            }
        }
        return messages;
    }

    private static List<ContentBlockParam> assistantBlocks(RunState.Turn turn) {
        var raw = turn.reply().raw();
        if (raw != null && raw.isArray()) {
            var mapper = ObjectMappers.jsonMapper();
            var blocks = new ArrayList<ContentBlockParam>();
            for (var node : raw) {
                try {
                    blocks.add(mapper.readValue(node.toString(), ContentBlock.class).toParam());
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException("Stored model response is not a valid content block", e);
                }
            }
            return blocks;
        }
        var blocks = new ArrayList<ContentBlockParam>();
        for (var block : turn.reply().content()) {
            switch (block) {
                case Block.Text text -> blocks.add(ContentBlockParam.ofText(
                        com.anthropic.models.messages.TextBlockParam.builder().text(text.text()).build()));
                case Block.ToolUse use -> blocks.add(ContentBlockParam.ofToolUse(
                        com.anthropic.models.messages.ToolUseBlockParam.builder()
                                .id(use.id())
                                .name(use.name())
                                .input(JsonValue.from(Json.MAPPER.convertValue(use.input(), Object.class)))
                                .build()));
            }
        }
        return blocks;
    }

    private static Tool tool(ToolSpec spec) {
        var schema = spec.inputSchema();
        var properties = Tool.InputSchema.Properties.builder();
        schema.path("properties").properties().forEach(entry ->
                properties.putAdditionalProperty(entry.getKey(), JsonValue.from(Json.MAPPER.convertValue(entry.getValue(), Map.class))));
        var required = new ArrayList<String>();
        schema.path("required").forEach(node -> required.add(node.asText()));
        return Tool.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(Tool.InputSchema.builder().properties(properties.build()).required(required).build())
                .build();
    }

    static ModelReply toReply(Message message) {
        var mapper = ObjectMappers.jsonMapper();
        var content = new ArrayList<Block>();
        for (var block : message.content()) {
            block.text().ifPresent(text -> content.add(new Block.Text(text.text())));
            block.toolUse().ifPresent(use -> content.add(new Block.ToolUse(use.id(), use.name(), toTree(mapper, use._input()))));
        }
        var stopReason = message.stopReason().map(reason -> reason.asString()).orElse(null);
        JsonNode raw = Json.parse(write(mapper, message.content()));
        return new ModelReply(content, stopReason, message.model().asString(),
                message.usage().inputTokens(), message.usage().outputTokens(), raw);
    }

    private static JsonNode toTree(com.fasterxml.jackson.databind.ObjectMapper mapper, JsonValue value) {
        return Json.parse(write(mapper, value));
    }

    private static String write(com.fasterxml.jackson.databind.ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
