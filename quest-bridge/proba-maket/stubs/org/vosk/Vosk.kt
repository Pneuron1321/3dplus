package org.vosk
// заглушки Vosk — только чтобы EarService собирался на столе
class Model(path: String) { fun close() {} }
class Recognizer(m: Model, rate: Float, grammar: String? = null) {
    fun setWords(b: Boolean) {}
    fun setPartialWords(b: Boolean) {}
    fun acceptWaveForm(b: ShortArray, n: Int): Boolean = false
    val result: String get() = "{}"
    val partialResult: String get() = "{}"
    val finalResult: String get() = "{}"
    fun reset() {}
    fun setEndpointerDelays(a: Float, b: Float, c: Float) {}
    fun setEndpointerMode(m: Any?) {}
    fun close() {}
}
