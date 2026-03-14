package paol0b.azuredevops.services

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import javax.swing.Icon

/**
 * Service for loading, caching, and serving user avatars from Azure DevOps.
 * Avatars are loaded asynchronously and cached in memory.
 */
@Service(Service.Level.PROJECT)
class AvatarService(private val project: Project) {

    private val logger = Logger.getInstance(AvatarService::class.java)

    // Cache: imageUrl -> loaded Icon
    private val cache = ConcurrentHashMap<String, Icon>()

    // Set of URLs currently being loaded (to avoid duplicate requests)
    private val loading = ConcurrentHashMap.newKeySet<String>()
    // Callbacks waiting for a URL to finish loading
    private val pendingCallbacks = ConcurrentHashMap<String, CopyOnWriteArrayList<() -> Unit>>()

    companion object {
        private const val DEFAULT_SIZE = 24
        // Fetch at 2× the logical size so avatars are crisp on HiDPI / Retina displays.
        // The custom icon reports the logical size to Swing, but holds the 2× physical pixels,
        // which map 1:1 on a 2× screen and are BICUBIC-downscaled on 1× screens.
        private const val FETCH_SCALE = 2
        private val PLACEHOLDER_ICON: Icon = AllIcons.General.User

        fun getInstance(project: Project): AvatarService {
            return project.getService(AvatarService::class.java)
        }
    }

    /**
     * Get an avatar icon for the given image URL.
     * Returns a placeholder immediately and loads the real avatar asynchronously.
     * Once loaded, the callback (if provided) is invoked on the EDT.
     *
     * @param imageUrl The Azure DevOps image URL for the user
     * @param size The desired icon size in pixels (width and height)
     * @param onLoaded Optional callback invoked on the EDT when the real avatar is ready
     * @return The cached icon if available, otherwise a placeholder
     */
    fun getAvatar(imageUrl: String?, size: Int = DEFAULT_SIZE, onLoaded: (() -> Unit)? = null): Icon {
        if (imageUrl.isNullOrBlank()) return PLACEHOLDER_ICON

        val physicalSize = size * FETCH_SCALE
        val sizedUrl = appendSizeParam(imageUrl, physicalSize)

        // Return cached icon if available
        cache[sizedUrl]?.let { return it }

        if (onLoaded != null) {
            pendingCallbacks.computeIfAbsent(sizedUrl) { CopyOnWriteArrayList() }.add(onLoaded)
        }

        cache[sizedUrl]?.let { icon ->
            if (onLoaded != null) {
                pendingCallbacks.remove(sizedUrl)
                ApplicationManager.getApplication().invokeLater { onLoaded() }
            }
            return icon
        }

        // Start async load if not already in progress
        if (loading.add(sizedUrl)) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val image = loadImage(sizedUrl, physicalSize)
                    if (image != null) {
                        val icon = createHiDpiIcon(image, size)
                        cache[sizedUrl] = icon
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to load avatar from $sizedUrl: ${e.message}")
                } finally {
                    val callbacks = pendingCallbacks.remove(sizedUrl)
                    if (callbacks != null && callbacks.isNotEmpty()) {
                        ApplicationManager.getApplication().invokeLater {
                            callbacks.forEach { it() }
                        }
                    }
                    loading.remove(sizedUrl)
                }
            }
        }

        return PLACEHOLDER_ICON
    }

    /**
     * Get an avatar icon synchronously (blocking). Use only on background threads.
     *
     * @param imageUrl The Azure DevOps image URL
     * @param size The desired icon size
     * @return The loaded icon, or placeholder on failure
     */
    fun getAvatarSync(imageUrl: String?, size: Int = DEFAULT_SIZE): Icon {
        if (imageUrl.isNullOrBlank()) return PLACEHOLDER_ICON

        val physicalSize = size * FETCH_SCALE
        val sizedUrl = appendSizeParam(imageUrl, physicalSize)
        cache[sizedUrl]?.let { return it }

        return try {
            val image = loadImage(sizedUrl, physicalSize)
            if (image != null) {
                val icon = createHiDpiIcon(image, size)
                cache[sizedUrl] = icon
                icon
            } else {
                PLACEHOLDER_ICON
            }
        } catch (e: Exception) {
            logger.warn("Failed to load avatar synchronously: ${e.message}")
            PLACEHOLDER_ICON
        }
    }

    /**
     * Preload avatars for a list of image URLs (fire and forget).
     */
    fun preloadAvatars(imageUrls: List<String?>, size: Int = DEFAULT_SIZE) {
        imageUrls.filterNotNull().filter { it.isNotBlank() }.forEach { url ->
            getAvatar(url, size)
        }
    }

    /**
     * Clear the avatar cache.
     */
    fun clearCache() {
        cache.clear()
    }

    /**
     * Load and scale an image from a URL.
     */
    private fun loadImage(url: String, size: Int): BufferedImage? {
        return try {
            val configService = AzureDevOpsConfigService.getInstance(project)
            val token = configService.getConfig().personalAccessToken

            val connection = URI(url).toURL().openConnection()
            if (token.isNotBlank()) {
                val credentials = ":$token"
                val encoded = java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())
                connection.setRequestProperty("Authorization", "Basic $encoded")
            }
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            val raw = connection.getInputStream().use { stream ->
                ImageIO.read(stream)
            } ?: return null

            // Scale to target size using high-quality BICUBIC interpolation.
            // Drawing into a fresh ARGB buffer avoids ColorModel issues.
            val result = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g2d = result.createGraphics()
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2d.drawImage(raw, 0, 0, size, size, null)
            g2d.dispose()

            makeCircular(result)
        } catch (e: Throwable) {
            // Catch Throwable to handle NoClassDefFoundError from broken JBR native libraries
            logger.debug("Error loading image from $url: ${e.message}")
            null
        }
    }

    /**
     * Creates an icon that reports [logicalSize] × [logicalSize] to Swing layout,
     * but paints the full-resolution [image] (which is [logicalSize] × FETCH_SCALE pixels).
     * On HiDPI screens the rendering pipeline maps logical → physical at the system scale,
     * so the 2× image lands 1:1 on physical pixels — no upscaling, no blur.
     */
    private fun createHiDpiIcon(image: BufferedImage, logicalSize: Int): Icon {
        return object : Icon {
            override fun getIconWidth() = logicalSize
            override fun getIconHeight() = logicalSize
            override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.drawImage(image, x, y, logicalSize, logicalSize, null)
                } finally {
                    g2.dispose()
                }
            }
        }
    }

    /**
     * Create a circular version of a buffered image (for avatar display).
     */
    private fun makeCircular(image: BufferedImage): BufferedImage {
        val size = image.width
        val result = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g2d = result.createGraphics()
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        val clip = java.awt.geom.Ellipse2D.Float(0f, 0f, size.toFloat(), size.toFloat())
        g2d.clip = clip
        g2d.drawImage(image, 0, 0, null)
        g2d.dispose()
        return result
    }

    /**
     * Append a size query parameter to the avatar URL if not already present.
     */
    private fun appendSizeParam(url: String, size: Int): String {
        return if (url.contains("size=")) {
            url
        } else {
            val separator = if (url.contains("?")) "&" else "?"
            "${url}${separator}size=$size"
        }
    }
}
