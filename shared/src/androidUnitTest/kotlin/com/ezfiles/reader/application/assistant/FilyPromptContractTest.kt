package com.ezfiles.reader.application.assistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The app must send the prompt the model was trained on; this compares against the training files. */
class FilyPromptContractTest {
    private val training = File("../training")

    // training/ is a local, ignored folder; without it there is nothing to compare against.
    @Test
    fun toolCatalogMatchesTraining() {
        val catalog = File(training, "tool_catalog.json").takeIf(File::exists) ?: return
        val trained = Json.parseToJsonElement(catalog.readText())
        assertEquals(trained, Json.parseToJsonElement(FILY_TOOL_CATALOG))
    }

    @Test
    fun systemPromptMatchesTrainingRows() {
        val data = File(training, "data/edge_v5/train.jsonl").takeIf(File::exists) ?: return
        data.useLines { lines ->
            lines.take(200).forEach { line ->
                val row = Json.parseToJsonElement(line).jsonObject
                val tools = row.getValue("tools").jsonArray.map { it.jsonPrimitive.content }
                val system = row.getValue("messages").jsonArray[0].jsonObject.getValue("content").jsonPrimitive.content
                assertEquals(system, FilyPrompt.system(tools), "row ${row["id"]}")
            }
        }
    }
}
