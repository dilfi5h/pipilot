package dev.pipilot.app.ssh

import android.util.Log
import dev.pipilot.app.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/** 一条已建立并启动远端命令的 exec 通道。*/
class SshExecSession(
    val stdin: OutputStream,
    val stdout: InputStream,
    val stderr: InputStream,
    val session: Session,
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
        // sshj 依赖 BouncyCastle 做 PKCS8/Ed25519;Android 自带的 BC 是裁剪版,需要注册完整版
        runCatching {
            Security.removeProvider("BC")
            Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        }
    }

    private const val TAG = "SshConnector"

    /**
     * 连接并在远端执行 [command],返回挂起的 exec 会话。
     * 注意:host key 校验用 PromiscuousVerifier(信任所有)。移动端对个人服务器场景常见,
     * 但严格来说允许 MITM;后续版本可在设置里固定 host key。
     */
    suspend fun connectAndExec(config: SshConfig, command: String): SshExecSession =
        withContext(Dispatchers.IO) {
            val client = SSHClient(DefaultConfig())
            client.timeout = 15_000
            client.connection.keepAlive.keepAliveInterval = 30
            client.addHostKeyVerifier(PromiscuousVerifier())
            try {
                AppLog.i(TAG, "connect ${config.user}@${config.host}:${config.port}")
                client.connect(config.host, config.port)
                AppLog.i(TAG, "tcp ok, auth=${if (config.auth is SshConfig.Auth.Password) "password" else "key"}")
                when (val auth = config.auth) {
                    is SshConfig.Auth.Password ->
                        client.authPassword(config.user, auth.password)
                    is SshConfig.Auth.PrivateKey -> {
                        val key = loadPrivateKey(auth.pem, auth.passphrase)
                        client.authPublickey(config.user, key)
                    }
                }
                AppLog.i(TAG, "auth ok")
                val session = client.startSession()
                val cmd = session.exec(command)
                AppLog.i(TAG, "exec started: ${command.take(120)}")
                Log.d(TAG, "exec started: $command")
                SshExecSession(cmd.outputStream, cmd.inputStream, cmd.errorStream, session, client)
            } catch (e: Exception) {
                AppLog.e(TAG, "connect/exec failed: ${e.javaClass.simpleName}: ${e.message}")
                runCatching { client.disconnect() }
                throw SshException("SSH 连接失败: ${e.message}", e)
            }
        }

    /**
     * 用 sshj 官方格式探测(KeyProviderUtil)选择解析器:
     * - `OPENSSH PRIVATE KEY`(ssh-keygen 新格式,含 ed25519) → OpenSSHv1
     *   (com.hierynomus…OpenSSHKeyV1KeyFile;注意 net.schmizz…OpenSSHKeyFile
     *   只是 PKCS8+独立公钥的壳,解析不了这种格式)
     * - `PRIVATE KEY` / `ENCRYPTED PRIVATE KEY`(PKCS#8) → PKCS8(或 OpenSSH 壳)
     * 口令统一透传,无口令传 null。探测/解析失败抛 SshException,
     * 认证不会被静默跳过(避免含糊的 exhausted 错误)。
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
            throw SshException("私钥格式无法识别: ${e.message}", e)
        }
        val provider = keyProviderFor(format, trimmed, finder)
        try {
            provider.private // 触发实际解析,格式不对这里就抛
            AppLog.i(TAG, "key parsed ok (format=$format)")
        } catch (e: Exception) {
            AppLog.e(TAG, "key parse failed (format=$format): ${e.javaClass.simpleName}: ${e.message}")
            throw SshException("私钥解析失败(${format}): ${e.message}", e)
        }
        return provider
    }

    /**
     * 按探测到的格式建解析器。OpenSSHv1 用 com.hierynomus 包下的
     * OpenSSHKeyV1KeyFile(字符串类名反射,避免编译期依赖内部包);
     * 其余走 net.schmizz 的 PKCS8/OpenSSH 壳。
     */
    private fun keyProviderFor(format: KeyFormat, pem: String, finder: PasswordFinder?): FileKeyProvider {
        val provider: FileKeyProvider = when (format) {
            KeyFormat.OpenSSHv1 -> try {
                Class.forName("com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile")
                    .getDeclaredConstructor().newInstance() as FileKeyProvider
            } catch (e: Exception) {
                throw SshException("缺少 OpenSSHv1 解析器: ${e.message}", e)
            }
            KeyFormat.OpenSSH -> OpenSSHKeyFile()
            KeyFormat.PKCS8 -> PKCS8KeyFile()
            else -> throw SshException("不支持的私钥格式: $format")
        }
        if (finder != null) provider.init(java.io.StringReader(pem), finder)
        else provider.init(java.io.StringReader(pem))
        return provider
    }

    /** 便捷:执行短命令并拿到完整输出(用于探测 pi 是否安装、列 session 目录等)。*/
    suspend fun runQuick(config: SshConfig, command: String, timeoutMs: Long = 15_000): String =
        withContext(Dispatchers.IO) {
            connectAndExec(config, command).let { s ->
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
