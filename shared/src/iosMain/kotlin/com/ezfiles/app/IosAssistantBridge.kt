package com.ezfiles.app

import com.ezfiles.reader.application.assistant.AssistantAccessDenied
import com.ezfiles.reader.application.assistant.AssistantAction
import com.ezfiles.reader.application.assistant.AssistantDestination
import com.ezfiles.reader.application.assistant.AssistantDocument
import com.ezfiles.reader.platform.PickedDocuments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/** The only document metadata exported to the Swift App Entity adapter. */
data class IosAssistantDocument(val id: String, val name: String)

/**
 * Callback entry points keep Kotlin exceptions and coroutine implementation details
 * behind the framework boundary. All callbacks run on the main dispatcher.
 */
object IosAssistantBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dependencies get() = IosAppRuntime.dependencies

    fun documents(query: String, recent: Boolean, completion: (List<IosAssistantDocument>?, String?) -> Unit) {
        scope.launch {
            try {
                val authorization = requireAccess()
                val result = if (recent) {
                    dependencies.assistantActions.listRecentDocuments(limit = 20)
                } else {
                    dependencies.assistantActions.searchDocuments(query, limit = 20)
                }
                recheckAccess(authorization)
                completion(result.documents.map(::entity), null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                completion(null, failureMessage(failure))
            }
        }
    }

    fun documentsForIds(ids: List<String>, completion: (List<IosAssistantDocument>?, String?) -> Unit) {
        scope.launch {
            try {
                val authorization = requireAccess()
                require(ids.size <= 20) { "Too many documents." }
                val result = ids.distinct().map { entity(dependencies.assistantActions.getDocumentInfo(it)) }
                recheckAccess(authorization)
                completion(result, null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                completion(null, failureMessage(failure))
            }
        }
    }

    /** Queue a foreground review; this does not mean sharing or printing has completed. */
    fun perform(documentId: String, action: String, completion: (String?) -> Unit) {
        scope.launch {
            try {
                val authorization = requireAccess()
                val operation = when (action) {
                    "open" -> AssistantAction.Open
                    "share" -> AssistantAction.Share
                    "print" -> AssistantAction.Print
                    else -> throw IllegalArgumentException("Unknown action.")
                }
                val ref = dependencies.assistantActions.resolveDocument(documentId)
                recheckAccess(authorization)
                dependencies.assistantNavigation.request(AssistantDestination(ref, operation, authorization))
                completion(null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                completion(failureMessage(failure))
            }
        }
    }

    /** A Files/Open In handover is a direct user action and works with assistants off. */
    fun openUserDocument(url: NSURL) {
        if (!url.isFileURL()) return
        IosAppRuntime.openRequest.value = PickedDocuments.register(url)
    }

    private fun requireAccess(): Long {
        if (!UIApplication.sharedApplication.isProtectedDataAvailable()) throw AssistantAccessDenied()
        return dependencies.assistantAccess.captureAuthorization()
    }

    private fun recheckAccess(authorization: Long) {
        if (!UIApplication.sharedApplication.isProtectedDataAvailable()) throw AssistantAccessDenied()
        dependencies.assistantAccess.recheckAuthorization(authorization)
    }

    private fun entity(document: AssistantDocument) = IosAssistantDocument(document.id, document.name)

    private fun failureMessage(failure: Exception): String = when (failure) {
        is AssistantAccessDenied -> "Unlock the device and enable Assistant access in Xfiles Info to use Siri."
        else -> "This document is unavailable. Open it again in Xfiles and retry."
    }
}
