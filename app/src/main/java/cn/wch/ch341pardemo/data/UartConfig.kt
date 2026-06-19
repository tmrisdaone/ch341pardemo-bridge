package cn.wch.ch341pardemo.data

/**
 * UART configuration knobs. Mirrors what the CH341PAR vendor lib accepts.
 */
data class UartConfig(
    val baudRate: Int = 115_200,
    val dataBits: Int = 8,
    val stopBits: Int = 1,            // 1 or 2
    val parity: Parity = Parity.NONE,
    val flowControl: FlowControl = FlowControl.NONE
) {
    enum class Parity { NONE, ODD, EVEN, MARK, SPACE }
    enum class FlowControl { NONE, RTS_CTS, DTR_DSR, XON_XOFF }

    companion object {
        val CommonBaudRates = listOf(
            1200, 2400, 4800, 9600, 19200, 38400, 57600,
            115_200, 230_400, 460_800, 921_600, 1_000_000, 1_500_000, 2_000_000
        )
    }
}

/**
 * One entry in the terminal log. A single read may produce many of these
 * (one per byte/line) so the renderer can scroll smoothly.
 */
data class TerminalLine(
    val timestampMs: Long,
    val direction: Direction,
    val bytes: ByteArray,
    val note: String? = null
) {
    enum class Direction { RX, TX, INFO, ERROR }
}
