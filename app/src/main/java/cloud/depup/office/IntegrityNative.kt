package cloud.depup.office

/** Native checks fail closed when the shared library cannot be loaded. */
internal object IntegrityNative {
    init { System.loadLibrary("depup_integrity") }

    @JvmStatic external fun scan(): Int

    /** Creates a sealed, anonymous memfd containing PDF bytes; caller owns the returned descriptor. */
    @JvmStatic external fun createSealedMemfd(bytes: ByteArray): Int
}
