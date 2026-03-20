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
import javax.imageio.ImageIO
import javax.swing.Icon

/**
 * Service for loading, caching, and serving user avatars from Azure DevOps.
 *
 * Every call to [getAvatar] for the same URL+size returns the **same**
 * [DelegatingIcon] instance. That icon starts with a placeholder delegate
 * and is transparently swapped to the real avatar once loaded. Because all
 * JBLabels share the same icon object, a single `repaint()` after the swap
 * updates every visible occurrence — no per-caller callbacks needed.
 */
@Service(Service.Level.PROJECT)
class AvatarService(private val project: Project) {

    private val logger = Logger.getInstance(AvatarService::class.java)

    /**
     * Shared delegating icons keyed by sized URL.
     * Every caller for the same URL+size gets the exact same [DelegatingIcon].
     */
    private val icons = ConcurrentHashMap<String, DelegatingIcon>()

    /** URLs currently being fetched (prevents duplicate network requests). */
    private val loading = ConcurrentHashMap.newKeySet<String>()

    companion object {
        private const val DEFAULT_SIZE = 24
        private const val FETCH_SCALE = 2
        private val PLACEHOLDER_ICON: Icon = AllIcons.General.User

        fun getInstance(project: Project): AvatarService {
            return project.getService(AvatarService::class.java)
        }
    }

    /**
     * Get an avatar icon for the given image URL.
     *
     * Returns a shared [DelegatingIcon] that initially paints the placeholder.
     * When the real avatar loads, the delegate is swapped in-place so every
     * JBLabel holding this icon automatically shows the real avatar on repaint.
     *
     * @param imageUrl The Azure DevOps image URL for the user
     * @param size The desired icon size in pixels (width and height)
     * @param onLoaded Optional callback invoked on the EDT when the real avatar is ready
     * @return A shared icon instance that updates in-place when the avatar loads
     */
    fun getAvatar(imageUrl: String?, size: Int = DEFAULT_SIZE, onLoaded: (() -> Unit)? = null): Icon {
        if (imageUrl.isNullOrBlank()) return PLACEHOLDER_ICON

        val physicalSize = size * FETCH_SCALE
        val sizedUrl = appendSizeParam(imageUrl, physicalSize)

        // Get or create the shared wrapper for this URL+size
        val wrapper = icons.computeIfAbsent(sizedUrl) { DelegatingIcon(PLACEHOLDER_ICON, size) }

        // If the delegate is already the real icon, fire callback and return
        if (wrapper.isLoaded) {
            onLoaded?.let { cb -> ApplicationManager.getApplication().invokeLater { cb() } }
            return wrapper
        }

        // Start async load if not already in progress
        if (loading.add(sizedUrl)) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val image = loadImage(sizedUrl, physicalSize)
                    if (image != null) {
                        val realIcon = createHiDpiIcon(image, size)
                        wrapper.swapDelegate(realIcon)
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to load avatar from $sizedUrl: ${e.message}")
                } finally {
                    loading.remove(sizedUrl)
                    // Fire the onLoaded callback for this specific call
                    onLoaded?.let { cb ->
                        ApplicationManager.getApplication().invokeLater { cb() }
                    }
                }
            }
        }

        return wrapper
    }

    /**
     * Get an avatar icon synchronously (blocking). Use only on background threads.
     */
    fun getAvatarSync(imageUrl: String?, size: Int = DEFAULT_SIZE): Icon {
        if (imageUrl.isNullOrBlank()) return PLACEHOLDER_ICON

        val physicalSize = size * FETCH_SCALE
        val sizedUrl = appendSizeParam(imageUrl, physicalSize)

        val wrapper = icons.computeIfAbsent(sizedUrl) { DelegatingIcon(PLACEHOLDER_ICON, size) }
        if (wrapper.isLoaded) return wrapper

        return try {
            val image = loadImage(sizedUrl, physicalSize)
            if (image != null) {
                val realIcon = createHiDpiIcon(image, size)
                wrapper.swapDelegate(realIcon)
            }
            wrapper
        } catch (e: Exception) {
            logger.warn("Failed to load avatar synchronously: ${e.message}")
            wrapper
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
        icons.clear()
    }

    // ── DelegatingIcon ──────────────────────────────────────────────────

    /**
     * Mutable icon wrapper shared by all callers for the same URL+size.
     *
     * Starts with a placeholder delegate. When the real avatar loads,
     * [swapDelegate] replaces the delegate and repaints every component
     * that has ever rendered this icon.
     */
    private class DelegatingIcon(
        @Volatile var delegate: Icon,
        private val logicalSize: Int
    ) : Icon {

        /** True once the real avatar has been loaded. */
        @Volatile var isLoaded: Boolean = false
            private set

        /** Components that have painted this icon — repainted on swap. */
        private val paintedComponents = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Component, Boolean>())

        override fun getIconWidth() = logicalSize
        override fun getIconHeight() = logicalSize

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            c?.let { synchronized(paintedComponents) { paintedComponents.add(it) } }
            delegate.paintIcon(c, g, x, y)
        }

        fun swapDelegate(newIcon: Icon) {
            delegate = newIcon
            isLoaded = true
            // Repaint all components that have ever rendered this icon
            ApplicationManager.getApplication().invokeLater {
                synchronized(paintedComponents) {
                    paintedComponents.forEach { comp ->
                        if (comp.isShowing) comp.repaint()
                    }
                }
            }
        }
    }

    // ── Image loading ───────────────────────────────────────────────────

    private fun loadImage(url: String, size: Int): BufferedImage? {
        return try {
            val configService = AzureDevOpsConfigService.getInstance(project)
            val token = configService.getConfig().personalAccessToken

            val connection = URI(url).toURL().openConnection() as java.net.HttpURLConnection
            try {
                if (token.isNotBlank()) {
                    val credentials = ":$token"
                    val encoded = java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())
                    connection.setRequestProperty("Authorization", "Basic $encoded")
                }
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val raw = connection.inputStream.use { stream ->
                    ImageIO.read(stream)
                } ?: return null

                val result = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
                val g2d = result.createGraphics()
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2d.drawImage(raw, 0, 0, size, size, null)
                g2d.dispose()

                makeCircular(result)
            } finally {
                connection.disconnect()
            }
        } catch (e: Throwable) {
            logger.debug("Error loading image from $url: ${e.message}")
            null
        }
    }

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

    private fun appendSizeParam(url: String, size: Int): String {
        return if (url.contains("size=")) {
            url
        } else {
            val separator = if (url.contains("?")) "&" else "?"
            "${url}${separator}size=$size"
        }
    }
}
