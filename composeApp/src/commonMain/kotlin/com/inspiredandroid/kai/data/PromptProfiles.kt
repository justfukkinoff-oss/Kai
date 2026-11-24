package com.inspiredandroid.kai.data

internal enum class PromptProfile(val label: String, val starter: String) {
    DEFAULT("Default", HARDCODED_REKAI_PROMPT),
    LEGAL("Legal assistant",
        "You are my U.S. legal research and drafting assistant for 28 U.S.C. § 2254, the Tenth Circuit, and Oklahoma. " +
            "Distinguish record facts from legal authority. Search the bound matter and public law collections. " +
            "Preserve exact quotations and source provenance, flag anything unverified, and do not block drafting or export."),
    DEVELOPMENT("Dev assistant",
        "You are my Android development assistant. Work in the existing Linux workspace. Inspect the repository before editing; " +
            "use unified diffs and managed Git checkpoints. Compile Android APKs only through GitHub Actions; monitor the run and retrieve the artifact. " +
            "Report actual results and anything not verified."),
}

internal fun AppSettings.promptProfile(chatId: String?): PromptProfile =
    PromptProfile.entries.find { it.name == libraryValue("profile.${chatId ?: "draft"}") } ?: PromptProfile.DEFAULT

internal fun AppSettings.promptText(profile: PromptProfile): String =
    (if (profile == PromptProfile.DEFAULT) getSoulText() else libraryValue("prompt.${profile.name}")).ifBlank { profile.starter }

internal fun AppSettings.savePrompt(profile: PromptProfile, text: String) {
    if (profile == PromptProfile.DEFAULT) setSoulText(text) else setLibraryValue("prompt.${profile.name}", text)
}

internal fun AppSettings.setPromptProfile(profile: PromptProfile) {
    setLibraryValue("profile.${getCurrentConversationId() ?: "draft"}", if (profile == PromptProfile.DEFAULT) "" else profile.name)
}
