package io.github.rodrigorjsf.agenticchat.testsupport;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A {@link ChatModel} that answers from a script instead of a provider.
 *
 * <p>Written by hand rather than mocked. A mock of a four-method interface tells you
 * what was called; this records the actual {@link ChatRequest}s, which is what the
 * tests here need to assert — that the system prompt is byte-identical across turns,
 * that only the skill-management tools are visible before activation, and that the
 * user's text arrives wrapped rather than bare.
 */
public class ScriptedChatModel implements ChatModel {

    /**
     * Both are concurrent on purpose, and it is not defensive.
     *
     * <p>{@link #routeBy} exists because the trip-briefing workflow runs two
     * sub-agents at the same time — its own javadoc says so — and those two threads
     * land in {@link #chat} together. With a plain {@code ArrayList}, two concurrent
     * {@code add} calls lose one: {@code callCount()} came back 3 where four
     * sub-agents had run, intermittently, on a clean build. The symptom read exactly
     * like a sub-agent failing silently in the workflow, which is a far more alarming
     * bug than the one that was there. {@code ArrayDeque.poll} races the same way.
     */
    private final Deque<Function<ChatRequest, AiMessage>> script = new ConcurrentLinkedDeque<>();
    private final List<ChatRequest> requests = new CopyOnWriteArrayList<>();
    private AiMessage fallback = AiMessage.from("ok");
    private List<ChatModelListener> listeners = List.of();
    private Function<ChatRequest, AiMessage> router;

    /**
     * Real provider models notify listeners around every call, so this double does
     * too. Without it the token and cost accounting would be exercised by nothing.
     */
    public ScriptedChatModel withListeners(List<ChatModelListener> listeners) {
        this.listeners = List.copyOf(listeners);
        return this;
    }

    public ScriptedChatModel replyWith(String... texts) {
        for (String text : texts) {
            script.add(request -> AiMessage.from(text));
        }
        return this;
    }

    public ScriptedChatModel reply(Function<ChatRequest, AiMessage> responder) {
        script.add(responder);
        return this;
    }

    public ScriptedChatModel fallbackTo(String text) {
        this.fallback = AiMessage.from(text);
        return this;
    }

    /**
     * Answers by inspecting the request instead of by position.
     *
     * <p>Needed wherever calls are concurrent: a queue assumes an order, and a
     * parallel workflow stage does not have one. Routing on the system message is
     * both deterministic and closer to what the test is actually asserting — that
     * each sub-agent was asked the right thing.
     */
    public ScriptedChatModel routeBy(Function<ChatRequest, AiMessage> router) {
        this.router = router;
        return this;
    }

    public List<ChatRequest> requests() {
        return List.copyOf(requests);
    }

    public ChatRequest lastRequest() {
        return requests.getLast();
    }

    public int callCount() {
        return requests.size();
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        requests.add(chatRequest);
        var attributes = new java.util.concurrent.ConcurrentHashMap<Object, Object>();
        listeners.forEach(listener -> listener.onRequest(
                new ChatModelRequestContext(chatRequest, null, attributes)));

        AiMessage message;
        if (router != null) {
            message = router.apply(chatRequest);
        } else {
            var responder = script.poll();
            message = responder == null ? fallback : responder.apply(chatRequest);
        }
        var response = ChatResponse.builder()
                .aiMessage(message)
                .tokenUsage(new TokenUsage(100, 20))
                .finishReason(FinishReason.STOP)
                .build();

        listeners.forEach(listener -> listener.onResponse(
                new ChatModelResponseContext(response, chatRequest, null, attributes)));
        return response;
    }
}
