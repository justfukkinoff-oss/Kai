package com.inspiredandroid.kai.data

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObjectiveRecoveryTest {
    @Test
    fun recoveryPromptCarriesTheGoalAndBoundedProgress() {
        val state = ObjectiveState(
            goal = "Verify the record citation",
            summary = "Do not change the goal",
            completedSteps = "Indexed the transcript",
            currentBlocker = "Missing Exhibit B",
            nextAction = "Check the docket",
            keyFindings = "Trial Tr. 142:10-18" + "x".repeat(2_500),
        )
        val prompt = buildObjectiveContinuationPrompt(state)
        for (text in listOf("[Goal]", "[Completed Steps]", "[Current Blocker]", "[Next Action]", "[Key Findings/Citations]")) {
            assertTrue(text in prompt)
        }
        assertTrue("Verify the record citation" in prompt)
        assertTrue("Trial Tr. 142:10-18" in prompt)
        assertFalse(state.keyFindings in prompt)
        assertFalse(state.summary in prompt)
    }

    @Test
    fun heartbeatCadenceAndFailureFloorAreIndependentOfPausing() {
        val state = ObjectiveState(goal = "Goal", lastRunAtMs = 10)
        assertTrue(objectiveIsDue(state.copy(lastRunAtMs = 0), 0, 30))
        assertFalse(objectiveIsDue(state, 120_009, 2))
        assertTrue(objectiveIsDue(state, 120_010, 2))
        assertFalse(objectiveIsDue(state.copy(status = ObjectiveStatus.FAILED), 600_009, 1))
        assertTrue(objectiveIsDue(state.copy(status = ObjectiveStatus.FAILED), 600_010, 1))
        assertFalse(objectiveIsDue(state.copy(status = ObjectiveStatus.PAUSED), 700_000, 1))
        assertFalse(objectiveIsDue(state.copy(status = ObjectiveStatus.DONE), 700_000, 1))
    }

    @Test
    fun objectiveFilesAndSettingsTrackEachConversationSeparately() = runTest {
        val files = mutableMapOf<String, String?>()
        val settings = AppSettings(MapSettings())
        val store = ObjectiveStore(settings) { id, contents -> files[id] = contents }
        store.update("one") { ObjectiveState("First goal") }
        store.update("two") { ObjectiveState("Second goal", nextAction = "Read Exhibit A") }
        assertEquals(setOf("one", "two"), store.getAll().keys)
        assertTrue("First goal" in files.getValue("one").orEmpty())
        assertFalse("Second goal" in files.getValue("one").orEmpty())
        assertTrue("Read Exhibit A" in files.getValue("two").orEmpty())
        store.update("one") { null }
        assertNull(files["one"])
        assertEquals(setOf("two"), ObjectiveStore(settings).getAll().keys)
    }

    @Test
    fun researchAndDraftModesAreRecognizedAndDraftLabelCannotBeOmitted() {
        assertEquals(ChatMode.RESEARCH, ChatMode.fromName("RESEARCH"))
        assertEquals(ChatMode.DRAFT, ChatMode.fromName("DRAFT"))
        assertTrue(markDraftUnverified("Ready to file").startsWith("**UNVERIFIED DRAFT:"))
    }
}
