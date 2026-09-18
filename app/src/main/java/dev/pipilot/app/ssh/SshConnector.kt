package dev.pipilot.app.ssh

import dev.pipilot.app.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.keepalive.KeepAliveRunner
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyFormat
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.InputStream
import java.io.OutputStream
import java.security.Security
import java.util.concurrent.TimeUnit

/** An established exec channel with a remote command already started. */
class SshExecSession(
    val stdin: OutputStream,
    val stdout: InputStream,
    val stderr: InputStream,
    val session: Session,
    /** Remote command handle: join() waits for exit; exitStatus is the code (diagnose "command exited on start"). */
    val command: Session.Command,
    private val client: SSHClient,
) {
    suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { session.close() }
        runCatching { client.disconnect() }
    }
}

data class SshConfig(
    val host: String,
    val port: Int = 22,
    val user: String,
    val auth: Auth,
) {
    sealed interface Auth {
        data class Password(val password: String) : Auth
        data class PrivateKey(val pem: String, val passphrase: String?) : Auth
    }
}

object SshConnector {

    init {
        // sshj needs BouncyCastle for PKCS8/Ed25519; Android's built-in BC is trimmed, so register the full provider
        runCatching {
            Security.removeProvider("BC")
            Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        }
    }

    private const val TAG = "SshConnector"

    /**
     * Connect and run [command] remotely; return a hanging exec session.
     * Host-key checks use PromiscuousVerifier (trust all). Common for a personal
     * server from a phone, but it allows MITM; a later version can pin the host key in settings.
     *
     * [readTimeoutMs] is the socket read timeout (SO_TIMEOUT); 0 = no timeout:
     * - long-lived RPC uses the default 0: an idle read must not die before the heartbeat is sent;
     * - short commands (runQuick) must pass a real timeout, otherwise a hung remote command
     *   blocks forever in readBytes() (coroutine cancel does not interrupt blocking IO;
     *   only a socket timeout actually aborts it).
     */
    suspend fun connectAndExec(
        config: SshConfig,
        command: String,
        readTimeoutMs: Long = 0,
    ): SshExecSession =
        withContext(Dispatchers.IO) {
            // KEEP_ALIVE = OpenSSH keepalive@openssh.com (requires a reply); default HEARTBEAT only sends IGNORE, which is not enough under NAT/OEM
            val sshConfig = DefaultConfig().apply {
                keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
            }
            val client = SSHClient(sshConfig)
            // Connect-phase timeout; read timeout comes from the caller (0 = disable SO_TIMEOUT for idle long-lived waits)
            client.connectTimeout = 15_000
            client.timeout = readTimeoutMs.toInt()
            // SSH application-layer heartbeat (seconds). Active for the whole session; 30s is enough
            // against NAT/idle drops without constant battery drain. Foreground also probes with get_state.
            client.connection.keepAlive.keepAliveInterval = 30
            (client.connection.keepAlive as? KeepAliveRunner)?.maxAliveCount = 3
            client.addHostKeyVerifier(PromiscuousVerifier())
            try {
                AppLog.i(TAG, "connect ${config.user}@${config.host}:${config.port}")
                client.connect(config.host, config.port)
                AppLog.d(TAG, "tcp ok, auth=${if (config.auth is SshConfig.Auth.Password) "password" else "key"}")
                when (val auth = config.auth) {
                    is SshConfig.Auth.Password ->
                        client.authPassword(config.user, auth.password)
                    is SshConfig.Auth.PrivateKey -> {
                        val key = loadPrivateKey(auth.pem, auth.passphrase)
                        client.authPublickey(config.user, key)
                    }
                }
                AppLog.i(TAG, "auth ok")
                val ka = client.connection.keepAlive
                AppLog.i(
                    TAG,
                    "ssh keepalive armed interval=${ka.keepAliveInterval}s provider=${ka.javaClass.simpleName} enabled=${ka.isEnabled}",
                )
                val session = client.startSession()
                val cmd = session.exec(command)
                AppLog.i(TAG, "exec started: ${command.take(120)}")
                SshExecSession(cmd.outputStream, cmd.inputStream, cmd.errorStream, session, cmd, client)
            } catch (e: Exception) {
                AppLog.e(TAG, "connect/exec failed: ${e.javaClass.simpleName}: ${e.message}")
                runCatching { client.disconnect() }
                throw SshException("SSH connect failed: ${e.message}", e)
            }
        }

    /**
     * Pick a parser via sshj's official format probe (KeyProviderUtil):
     * - `OPENSSH PRIVATE KEY` (new ssh-keygen format, including ed25519) → OpenSSHv1
     *   (com.hierynomus…OpenSSHKeyV1KeyFile; note that net.schmizz…OpenSSHKeyFile
     *   is only a PKCS8 + separate-public-key shell and cannot parse this format)
     * - `PRIVATE KEY` / `ENCRYPTED PRIVATE KEY` (PKCS#8) → PKCS8 (or the OpenSSH shell)
     * Passphrases are forwarded; null if none. Probe/parse failures throw SshException
     * so auth is never skipped silently (avoids a vague "exhausted" error).
     */
    private fun loadPrivateKey(pem: String, passphrase: String?): FileKeyProvider {
        val finder = passphrase?.let { pw ->
            object : PasswordFinder {
                override fun reqPassword(resource: Resource<*>): CharArray = pw.toCharArray()
                override fun shouldRetry(resource: Resource<*>): Boolean = false
            }
        }
        val trimmed = pem.trim()
        val format = try {
            KeyProviderUtil.detectKeyFileFormat(trimmed, false)
        } catch (e: Exception) {
            AppLog.e(TAG, "key probe/parse failed: ${e.javaClass.simpleName}: ${e.message}")
            throw SshException("Unrecognized private-key format: ${e.message}", e)
        }
        val provider = keyProviderFor(format, trimmed, finder)
        try {
            provider.private // force actual parse; wrong format throws here
            AppLog.d(TAG, "key parsed ok (format=$format)")
        } catch (e: Exception) {
            AppLog.e(TAG, "key parse failed (format=$format): ${e.javaClass.simpleName}: ${e.message}")
            throw SshException("Private-key parse failed ($format): ${e.message}", e)
        }
        return provider
    }

    /**
     * Build a parser for the probed format. OpenSSHv1 uses OpenSSHKeyV1KeyFile
     * in the com.hierynomus package (reflected by class name to avoid a compile-time
     * dependency on an internal package); everything else uses net.schmizz PKCS8/OpenSSH shells.
     */
    private fun keyProviderFor(format: KeyFormat, pem: String, finder: PasswordFinder?): FileKeyProvider {
        val provider: FileKeyProvider = when (format) {
            KeyFormat.OpenSSHv1 -> try {
                Class.forName("com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile")
                    .getDeclaredConstructor().newInstance() as FileKeyProvider
            } catch (e: Exception) {
                throw SshException("Missing OpenSSHv1 parser: ${e.message}", e)
            }
            KeyFormat.OpenSSH -> OpenSSHKeyFile()
            KeyFormat.PKCS8 -> PKCS8KeyFile()
            else -> throw SshException("Unsupported private-key format: $format")
        }
        if (finder != null) provider.init(java.io.StringReader(pem), finder)
        else provider.init(java.io.StringReader(pem))
        return provider
    }

    /** Convenience: run a short command and return full output (probe whether pi is installed, list session dir, etc.). */
    suspend fun runQuick(config: SshConfig, command: String, timeoutMs: Long = 15_000): String =
        withContext(Dispatchers.IO) {
            connectAndExec(config, command, readTimeoutMs = timeoutMs).let { s ->
                try {
                    val out = s.stdout.readBytes().toString(Charsets.UTF_8)
                    s.session.join(timeoutMs, TimeUnit.MILLISECONDS)
                    out
                } finally {
                    s.close()
                }
            }
        }
}

class SshException(message: String, cause: Throwable? = null) : Exception(message, cause)
