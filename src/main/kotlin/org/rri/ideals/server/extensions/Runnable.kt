package org.rri.ideals.server.extensions

@JvmRecord
data class Runnable(val label: String, val args: Arguments) {
    @JvmRecord
    data class Arguments( // The working directory to run the command in.
        val cwd: String,  // Command to execute.
        val cmd: String,  // Arguments to pass to the executable.
        val executableArgs: List<String>
    )
}
