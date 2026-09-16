package dev.pipilot.app.rpc

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PiRpcClientCloseTest {
    @Test
    fun closeWithoutNotifyDoesNotEmitClosed() = runBlocking {
        val client = PiRpcClient(
            stdin = ByteArrayOutputStream(),
            stdout = ByteArrayInputStream(ByteArray(0)),
            stderr = ByteArrayInputStream(ByteArray(0)),
        )
        assertEquals(PiRpcClient.ConnectionState.Connecting, client.connection.first())
        client.close(notify = false)
        assertTrue(client.connection.value !is PiRpcClient.ConnectionState.Closed)
        assertEquals(PiRpcClient.ConnectionState.Connecting, client.connection.value)
    }

    @Test
    fun closeWithNotifyEmitsClosed() = runBlocking {
        val client = PiRpcClient(
            stdin = ByteArrayOutputStream(),
            stdout = ByteArrayInputStream(ByteArray(0)),
            stderr = ByteArrayInputStream(ByteArray(0)),
        )
        client.close(notify = true)
        assertTrue(client.connection.value is PiRpcClient.ConnectionState.Closed)
    }
}
