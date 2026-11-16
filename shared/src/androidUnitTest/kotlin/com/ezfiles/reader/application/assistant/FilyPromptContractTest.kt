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

    @Test
    fun toolCatalogMatchesTraining() {
        val trained = Json.parseToJsonElement(File(training, "tool_catalog.json").readText())
        assertEquals(trained, Json.parseToJsonElement(FILY_TOOL_CATALOG))
    }

    @Test
    fun systemPromptMatchesTrainingRows() {
        // The generated data is not committed; the catalog test above still guards the schemas.
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
