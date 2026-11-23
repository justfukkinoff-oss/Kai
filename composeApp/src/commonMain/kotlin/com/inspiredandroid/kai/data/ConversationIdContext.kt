package com.inspiredandroid.kai.data

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

class ConversationIdElement(val conversationId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ConversationIdElement>
}

suspend fun currentConversationIdOrNull(): String? = coroutineContext[ConversationIdElement]?.conversationId

internal class ObjectiveRunElement(val startedAtMs: Long, val providerSessionId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ObjectiveRunElement>
}

internal suspend fun currentObjectiveRun(): ObjectiveRunElement? = coroutineContext[ObjectiveRunElement]
