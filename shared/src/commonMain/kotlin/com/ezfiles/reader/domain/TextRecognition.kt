package com.ezfiles.reader.domain

/** A line of recognized text and where it sits on its picture, as fractions of the picture's size (0..1). */
data class RecognizedLine(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

/** [text] is the whole result with its line and paragraph breaks; both are empty when nothing was found. */
data class RecognizedText(val text: String, val lines: List<RecognizedLine>)

/** On-device text recognition. Nothing about the picture leaves the device. */
interface TextRecognizer {
    /**
     * Reads the picture at [imageUri], off the main thread. Throws
     * [DocumentException] when the picture or the recognizer fails; finding no
     * text is an empty result, not a failure.
     */
    suspend fun recognize(imageUri: String): RecognizedText
}
