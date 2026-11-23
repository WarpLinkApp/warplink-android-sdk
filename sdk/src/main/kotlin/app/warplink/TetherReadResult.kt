package app.warplink

/** What [WarpLink.readTether] found. */
sealed interface TetherReadResult {

    /**
     * The SDK read no clipboard, so there is no code. Android never reads the
     * clipboard: a Tether test code reaches it through the Play install
     * referrer, and [WarpLink.lastTetherTestReceipt] holds the receipt.
     */
    data object NoClipboard : TetherReadResult
}
