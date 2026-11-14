import AppIntents
import Foundation
import Shared

struct EzFilesDocument: AppEntity {
    static let typeDisplayRepresentation = TypeDisplayRepresentation(name: "Document")
    static let defaultQuery = EzFilesDocumentQuery()

    let id: String
    let name: String

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(name)")
    }
}

struct EzFilesDocumentQuery: EntityStringQuery {
    @MainActor
    func entities(for identifiers: [String]) async throws -> [EzFilesDocument] {
        try await AssistantClient.documents(for: identifiers)
    }

    @MainActor
    func entities(matching string: String) async throws -> [EzFilesDocument] {
        try await AssistantClient.documents(query: string)
    }

    @MainActor
    func suggestedEntities() async throws -> [EzFilesDocument] {
        try await AssistantClient.documents(recent: true)
    }
}

/// No document names are donated to Spotlight or embedded in shortcut phrases.
struct EzFilesShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: FindEzFilesDocument(),
            phrases: ["Find a document in \(.applicationName)"],
            shortTitle: "Find document",
            systemImageName: "doc.text.magnifyingglass"
        )
        AppShortcut(
            intent: OpenRecentEzFilesDocument(),
            phrases: ["Open my recent document in \(.applicationName)"],
            shortTitle: "Open recent document",
            systemImageName: "clock"
        )
        AppShortcut(
            intent: ShareEzFilesDocument(),
            phrases: ["Share a document with \(.applicationName)"],
            shortTitle: "Share document",
            systemImageName: "square.and.arrow.up"
        )
        AppShortcut(
            intent: PrintEzFilesDocument(),
            phrases: ["Print a document with \(.applicationName)"],
            shortTitle: "Print document",
            systemImageName: "printer"
        )
    }
}

struct FindEzFilesDocument: AppIntent {
    static let title: LocalizedStringResource = "Find a document"
    static let description = IntentDescription("Finds up to 20 previously opened documents by filename when Assistant access is enabled in EzFiles Info.")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Document name")
    var query: String

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<[EzFilesDocument]> {
        .result(value: try await AssistantClient.documents(query: query))
    }
}

struct OpenRecentEzFilesDocument: AppIntent {
    static let title: LocalizedStringResource = "Open recent document"
    static let description = IntentDescription("Opens the most recently used, still accessible document in EzFiles.")
    static let openAppWhenRun = true
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @MainActor
    func perform() async throws -> some IntentResult {
        guard let document = try await AssistantClient.documents(recent: true).first else {
            throw AssistantFailure.unavailable("Open a document in EzFiles first.")
        }
        try await AssistantClient.perform(documentID: document.id, action: "open")
        return .result()
    }
}

struct ShareEzFilesDocument: AppIntent {
    static let title: LocalizedStringResource = "Share a document"
    static let description = IntentDescription("Opens EzFiles to review the selected original and choose a recipient in the system share sheet. It does not send a file automatically.")
    static let openAppWhenRun = true
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Document")
    var document: EzFilesDocument

    @MainActor
    func perform() async throws -> some IntentResult {
        try await AssistantClient.perform(documentID: document.id, action: "share")
        return .result()
    }
}

struct PrintEzFilesDocument: AppIntent {
    static let title: LocalizedStringResource = "Print a document"
    static let description = IntentDescription("Opens EzFiles to review a printable PDF, image or plain text document, then opens system print options. It does not print automatically.")
    static let openAppWhenRun = true
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Document")
    var document: EzFilesDocument

    @MainActor
    func perform() async throws -> some IntentResult {
        try await AssistantClient.perform(documentID: document.id, action: "print")
        return .result()
    }
}

private enum AssistantFailure: LocalizedError {
    case unavailable(String)

    var errorDescription: String? {
        switch self {
        case .unavailable(let message): return message
        }
    }
}

/// A callback bridge avoids assuming a particular Kotlin suspend/throws import mode.
@MainActor
private enum AssistantClient {
    static func documents(query: String = "", recent: Bool = false) async throws -> [EzFilesDocument] {
        try Task.checkCancellation()
        let result: [EzFilesDocument] = try await withCheckedThrowingContinuation { continuation in
            IosAssistantBridge.shared.documents(query: query, recent: recent) { documents, error in
                complete(documents: documents, error: error, continuation: continuation)
            }
        }
        try Task.checkCancellation()
        return result
    }

    static func documents(for ids: [String]) async throws -> [EzFilesDocument] {
        try Task.checkCancellation()
        let result: [EzFilesDocument] = try await withCheckedThrowingContinuation { continuation in
            IosAssistantBridge.shared.documentsForIds(ids: ids) { documents, error in
                complete(documents: documents, error: error, continuation: continuation)
            }
        }
        try Task.checkCancellation()
        return result
    }

    static func perform(documentID: String, action: String) async throws {
        try Task.checkCancellation()
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            IosAssistantBridge.shared.perform(documentId: documentID, action: action) { error in
                if let error {
                    continuation.resume(throwing: AssistantFailure.unavailable(error))
                } else {
                    continuation.resume()
                }
            }
        }
    }

    private static func complete(
        documents: [IosAssistantDocument]?,
        error: String?,
        continuation: CheckedContinuation<[EzFilesDocument], Error>
    ) {
        if let error {
            continuation.resume(throwing: AssistantFailure.unavailable(error))
        } else {
            continuation.resume(returning: (documents ?? []).map { EzFilesDocument(id: $0.id, name: $0.name) })
        }
    }
}
