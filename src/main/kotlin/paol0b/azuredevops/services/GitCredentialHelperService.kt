package paol0b.azuredevops.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Servizio per interagire con Git Credential Helper
 * per recuperare automaticamente il Personal Access Token salvato
 */
@Service(Service.Level.PROJECT)
class GitCredentialHelperService(private val project: Project) {

    private val logger = Logger.getInstance(GitCredentialHelperService::class.java)

    companion object {
        fun getInstance(project: Project): GitCredentialHelperService {
            return project.getService(GitCredentialHelperService::class.java)
        }
    }

    /**
     * Tenta di recuperare le credenziali per Azure DevOps dal Git Credential Helper
     * 
     * @param url URL del repository Azure DevOps
     * @return Il Personal Access Token se trovato, null altrimenti
     */
    fun getCredentialsFromHelper(url: String): String? {
        return try {
            // Prova prima con il protocollo specifico
            getCredentialForUrl(url) ?: 
            // Se non funziona, prova con URL generiche Azure DevOps
            getCredentialForUrl("https://dev.azure.com") ?:
            getCredentialForUrl("https://ssh.dev.azure.com")
        } catch (e: Exception) {
            logger.warn("Failed to retrieve credentials from Git credential helper", e)
            null
        }
    }

    /**
     * Recupera le credenziali per un URL specifico usando git credential fill
     */
    private fun getCredentialForUrl(url: String): String? {
        try {
            // Estrai il protocollo e host dall'URL
            val (protocol, host, path) = parseUrl(url) ?: return null
            
            logger.info("Attempting to get credentials for: protocol=$protocol, host=$host, path=$path")
            
            // Prepara l'input per git credential fill
            val input = buildString {
                appendLine("protocol=$protocol")
                appendLine("host=$host")
                if (path.isNotBlank()) {
                    appendLine("path=$path")
                }
                appendLine() // Riga vuota per terminare
            }
            
            // Esegui git credential fill con redirectErrorStream per evitare finestre popup
            val processBuilder = ProcessBuilder("git", "credential", "fill")
            
            // IMPORTANTE: Previeni l'apertura di finestre su Windows
            if (System.getProperty("os.name").lowercase().contains("windows")) {
                // Nascondi la finestra del processo su Windows
                processBuilder.redirectError(ProcessBuilder.Redirect.PIPE)
                processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE)
            } else {
                processBuilder.redirectErrorStream(true)
            }
            
            val process = processBuilder.start()
            
            // Invia input
            process.outputStream.use { outputStream ->
                outputStream.write(input.toByteArray())
                outputStream.flush()
            }
            
            // Leggi output con timeout per evitare blocchi
            val output = readProcessOutput(process, 5000) // 5 secondi timeout
            
            val exitCode = process.waitFor()
            
            if (exitCode == 0 && output.isNotBlank()) {
                // Parse l'output per estrarre la password (PAT)
                return parseCredentialOutput(output)
            } else {
                logger.debug("git credential fill returned exit code: $exitCode, output: $output")
            }
        } catch (e: Exception) {
            logger.debug("Failed to get credential for $url", e)
        }
        
        return null
    }
    
    /**
     * Legge l'output del processo con timeout per evitare blocchi
     */
    private fun readProcessOutput(process: Process, timeoutMs: Long): String {
        val output = StringBuilder()
        val startTime = System.currentTimeMillis()
        
        process.inputStream.bufferedReader().use { reader ->
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                if (reader.ready()) {
                    val line = reader.readLine() ?: break
                    output.appendLine(line)
                } else {
                    Thread.sleep(50)
                    // Controlla se il processo è terminato
                    if (!process.isAlive) {
                        // Leggi eventuali dati rimanenti
                        reader.lines().forEach { output.appendLine(it) }
                        break
                    }
                }
            }
        }
        
        return output.toString()
    }

    /**
     * Salva le credenziali nel Git Credential Helper
     * 
     * @param url URL del repository
     * @param username Username (per Azure DevOps può essere vuoto o qualsiasi valore)
     * @param password Personal Access Token
     */
    fun saveCredentialsToHelper(url: String, username: String = "", password: String): Boolean {
        return try {
            val (protocol, host, path) = parseUrl(url) ?: return false
            
            val input = buildString {
                appendLine("protocol=$protocol")
                appendLine("host=$host")
                if (path.isNotBlank()) {
                    appendLine("path=$path")
                }
                appendLine("username=${username.ifBlank { "PersonalAccessToken" }}")
                appendLine("password=$password")
                appendLine()
            }
            
            val processBuilder = ProcessBuilder("git", "credential", "approve")
            
            // IMPORTANTE: Previeni l'apertura di finestre su Windows
            if (System.getProperty("os.name").lowercase().contains("windows")) {
                processBuilder.redirectError(ProcessBuilder.Redirect.PIPE)
                processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE)
            } else {
                processBuilder.redirectErrorStream(true)
            }
            
            val process = processBuilder.start()
            
            process.outputStream.use { outputStream ->
                outputStream.write(input.toByteArray())
                outputStream.flush()
            }
            
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                logger.info("Credentials saved to Git credential helper for $host")
                true
            } else {
                logger.warn("Failed to save credentials to Git credential helper, exit code: $exitCode")
                false
            }
        } catch (e: Exception) {
            logger.warn("Failed to save credentials to Git credential helper", e)
            false
        }
    }

    /**
     * Verifica se Git Credential Helper è disponibile
     */
    fun isCredentialHelperAvailable(): Boolean {
        return try {
            val processBuilder = ProcessBuilder("git", "credential", "--help")
            
            // IMPORTANTE: Previeni l'apertura di finestre su Windows
            if (System.getProperty("os.name").lowercase().contains("windows")) {
                processBuilder.redirectError(ProcessBuilder.Redirect.PIPE)
                processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE)
            } else {
                processBuilder.redirectErrorStream(true)
            }
            
            val process = processBuilder.start()
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            logger.debug("Git credential helper not available", e)
            false
        }
    }

    /**
     * Parse l'output di git credential fill
     * Formato:
     * protocol=https
     * host=dev.azure.com
     * username=...
     * password=...
     */
    private fun parseCredentialOutput(output: String): String? {
        val lines = output.lines()
        for (line in lines) {
            if (line.startsWith("password=")) {
                val password = line.substringAfter("password=").trim()
                if (password.isNotBlank()) {
                    return password
                }
            }
        }
        return null
    }

    /**
     * Parse un URL e estrae protocollo, host e path
     * 
     * @return Triple di (protocol, host, path) o null se parsing fallisce
     */
    private fun parseUrl(url: String): Triple<String, String, String>? {
        return try {
            // Gestisci URL SSH
            if (url.startsWith("git@") || url.startsWith("ssh://")) {
                val cleanUrl = url.replace("git@", "ssh://").replace(":", "/")
                val uri = java.net.URI(cleanUrl)
                return Triple("ssh", uri.host ?: "", uri.path?.removePrefix("/") ?: "")
            }
            
            // Gestisci URL HTTPS
            val uri = java.net.URI(url)
            val protocol = uri.scheme ?: "https"
            val host = uri.host ?: return null
            val path = uri.path?.removePrefix("/")?.removeSuffix(".git") ?: ""
            
            Triple(protocol, host, path)
        } catch (e: Exception) {
            logger.debug("Failed to parse URL: $url", e)
            null
        }
    }

    /**
     * Tenta di recuperare le credenziali per il repository corrente
     */
    fun getCredentialsForCurrentRepository(): String? {
        val gitService = GitRepositoryService.getInstance(project)
        val remoteUrl = gitService.getRemoteUrl() ?: return null
        
        logger.info("Attempting to retrieve credentials for remote URL: $remoteUrl")
        return getCredentialsFromHelper(remoteUrl)
    }
}
