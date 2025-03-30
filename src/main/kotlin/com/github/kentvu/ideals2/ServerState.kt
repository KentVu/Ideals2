package com.github.kentvu.ideals2

sealed class ServerState {
    data object Stopped : ServerState()
    data object Started : ServerState()
    data class Message(val msg: String) : ServerState()
    companion object {
        fun values(): Array<ServerState> {
            return arrayOf(Stopped)
        }

        fun valueOf(value: String): ServerState {
            return when (value) {
                "Stopped" -> Stopped
                else -> throw IllegalArgumentException("No object com.github.kentvu.ideals2.ServerState.$value")
            }
        }
    }
}
