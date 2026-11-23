package com.inspiredandroid.kai.data

import com.inspiredandroid.kai.ui.chat.History
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ConversationContinuityTest {
    @Test
    fun recallSurvivesRecreationAndIgnoresIncludedEntries() = runTest {
        val settings = AppSettings(MapSettings())
        val memory = ConversationContinuity(settings)
        memory.write("case-a", listOf(
            ContinuityEntry("request", "User", "Use the amended record"),
            ContinuityEntry("result", "Tool matter_search", "Located Exhibit A"),
        ))
        val restored = ConversationContinuity(settings)
        val context = restored.context("case-a", listOf(
            History(id = "request", role = History.Role.USER, content = "Use the amended record"),
        ))
        assertContains(context, "Located Exhibit A")
        assertFalse("Use the amended record" in context)
        assertEquals("", restored.context("case-b", emptyList()))
        restored.delete("case-a")
        assertEquals("", memory.context("case-a", emptyList()))
    }

    @Test
    fun firstRequestAndMostRecentUnseenWorkSurviveBounds() = runTest {
        val memory = ConversationContinuity(AppSettings(MapSettings()))
        memory.write("chat", listOf(ContinuityEntry("goal", "User", "The original goal")))
        repeat(60) { index ->
            memory.write("chat", listOf(ContinuityEntry("result-$index", "Tool", "discovery-$index")))
        }
        val prompt = memory.context("chat", emptyList())
        assertContains(prompt, "The original goal")
        assertContains(prompt, "discovery-59")
        assertFalse("discovery-20" in prompt)
    }
}
