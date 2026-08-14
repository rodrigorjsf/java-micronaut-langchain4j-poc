package io.github.rodrigorjsf.agenticchat.api;

import io.github.rodrigorjsf.agenticchat.conversation.ChatTurnService;
import io.github.rodrigorjsf.agenticchat.memory.ConversationId;
import io.github.rodrigorjsf.agenticchat.skills.SkillCatalog;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;

/**
 * The HTTP surface: one endpoint to talk, one to ask what the assistant can do.
 *
 * <p>{@code @ExecuteOn(BLOCKING)} matters here. A turn blocks on the model and on
 * outbound tool calls for seconds at a time; running that on a Netty event loop
 * would stall every other request on the same loop. On Java 25 that executor is
 * backed by virtual threads, so a blocking turn parks a virtual thread rather than
 * holding a platform one.
 */
@Controller("/api/chat")
@ExecuteOn(TaskExecutors.BLOCKING)
public class ChatController {

    private final ChatTurnService turns;
    private final SkillCatalog skills;

    public ChatController(ChatTurnService turns, SkillCatalog skills) {
        this.turns = turns;
        this.skills = skills;
    }

    @Post
    public ChatResponse chat(@Valid @Body ChatRequest request) {
        var conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? ConversationId.newId()
                : new ConversationId(request.conversationId());

        var turn = turns.handle(conversationId, request.message());
        return ChatResponse.of(conversationId.value(), turn);
    }

    @Get("/capabilities")
    public CapabilitiesResponse capabilities() {
        return new CapabilitiesResponse(skills.names());
    }
}
