package pl.adrros.bettercodereview.colors

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import java.awt.Color
import java.nio.file.Files
import java.nio.file.Path

private val LOG = logger<PackageColors>()

/**
 * Colours assigned to folders and files of the project, shown as their background in the project tree, the change
 * trees and the editor tabs, and on the package line of files inside them. A file takes the colour of its nearest
 * coloured ancestor (or its own). Kept in `.idea/betterCodeReviewColors.xml`; on first use the assignments and the
 * palette of the ProjectTree Color Highlighter plugin (`.idea/highlightedFiles.xml`) are imported.
 */
@Service(Service.Level.PROJECT)
@State(name = "BetterCodeReviewPackageColors", storages = [Storage("betterCodeReviewColors.xml")])
class PackageColors(private val project: Project) : PersistentStateComponent<PackageColors.ColorsState> {
    class ColorsState {
        @XCollection(propertyElementName = "colors")
        var colors: MutableList<PaletteEntry> = mutableListOf()

        @XCollection(propertyElementName = "files")
        var files: MutableList<FileEntry> = mutableListOf()

        @Attribute
        var packageLine: Boolean = true
    }

    @Tag("color")
    class PaletteEntry() {
        @Attribute var id: Int = 0
        @Attribute var name: String = ""
        @Attribute var value: String = ""

        constructor(id: Int, name: String, value: String) : this() {
            this.id = id
            this.name = name
            this.value = value
        }
    }

    /** [path] is relative to the project's base directory, with '/' separators. */
    @Tag("node")
    class FileEntry() {
        @Attribute var path: String = ""
        @Attribute var color: Int = 0

        constructor(path: String, color: Int) : this() {
            this.path = path
            this.color = color
        }
    }

    data class PaletteColor(val id: Int, val name: String, val color: Color)

    fun interface Listener {
        fun colorsChanged(project: Project)
    }

    // Read from background threads (the project tree paints asynchronously); replaced whole, never mutated in place.
    @Volatile private var current = ColorsState()
    @Volatile private var byPath: Map<String, Int> = emptyMap()
    @Volatile private var palette: List<PaletteColor> = emptyList()

    override fun getState(): ColorsState = current

    override fun loadState(state: ColorsState) {
        current = state
        reindex()
    }

    /** Nothing stored yet: take over the other plugin's settings. Without them nothing is written until the developer colours something. */
    override fun noStateLoaded() {
        current = importHighlightedFiles(project) ?: ColorsState()
        reindex()
    }

    /** An empty stored palette means the default one for the current theme, kept in memory only. */
    private fun reindex() {
        val entries = current.colors.ifEmpty { defaultPalette() }
        palette = entries.mapNotNull { entry -> parseColor(entry.value)?.let { PaletteColor(entry.id, entry.name, it) } }
        byPath = current.files.associate { it.path to it.color }
    }

    val colors: List<PaletteColor> get() = palette

    val tintsPackageLine: Boolean get() = current.packageLine

    /** The colour of [file] itself or of its nearest coloured ancestor inside the project; null outside the project or when none is coloured. */
    fun colorOf(file: VirtualFile): Color? = project.basePath?.let { colorOf(it, file.path) }

    fun colorOf(base: String, path: String): Color? {
        if (byPath.isEmpty()) return null
        var relative = relativePath(base, path) ?: return null
        while (true) {
            byPath[relative]?.let { id -> return palette.firstOrNull { it.id == id }?.color }
            if (relative.isEmpty()) return null
            relative = relative.substringBeforeLast('/', "")
        }
    }

    /** The colour assigned to [file] itself, not inherited. */
    fun assignedColorId(file: VirtualFile): Int? = project.basePath?.let { relativePath(it, file.path) }?.let(byPath::get)

    /** Assigns [colorId] to [files], or takes their own colour off when it is null. */
    fun assign(files: Collection<VirtualFile>, colorId: Int?) {
        val base = project.basePath ?: return
        assign(base, files.map { it.path }, colorId)
    }

    fun assign(base: String, absolutePaths: Collection<String>, colorId: Int?) {
        val paths = absolutePaths.mapNotNull { relativePath(base, it) }.toSet()
        if (paths.isEmpty()) return
        val files = current.files.filterNot { it.path in paths } + (colorId?.let { id -> paths.sorted().map { FileEntry(it, id) } } ?: emptyList())
        current = copyOf(current).apply { this.files = files.toMutableList() }
        reindex()
        fireChanged()
    }

    /** Replaces the palette and the package-line switch, as edited in the settings. */
    fun update(colors: List<PaletteColor>, packageLine: Boolean) {
        current = copyOf(current).apply {
            this.colors = colors.map { PaletteEntry(it.id, it.name, formatColor(it.color)) }.toMutableList()
            this.packageLine = packageLine
        }
        reindex()
        fireChanged()
    }

    private fun copyOf(state: ColorsState) = ColorsState().apply {
        colors = state.colors.toMutableList()
        files = state.files.toMutableList()
        packageLine = state.packageLine
    }

    private fun fireChanged() {
        project.messageBus.syncPublisher(TOPIC).colorsChanged(project)
    }

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<Listener> = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)

        private const val OTHER_PLUGIN_FILE = "highlightedFiles.xml"
        private const val OTHER_PLUGIN_COMPONENT = "ProjectTreeColorHighlighter"
        private const val PROJECT_DIR = "\$PROJECT_DIR\$"

        /** "" for the base directory itself, null outside it. */
        fun relativePath(base: String, path: String): String? {
            if (FileUtil.pathsEqual(base, path)) return ""
            if (!FileUtil.isAncestor(base, path, true)) return null
            return FileUtil.getRelativePath(base, path, '/')
        }

        /** "#RRGGBB" or "#RRGGBBAA", the format the other plugin writes. */
        fun parseColor(value: String): Color? {
            val hex = value.removePrefix("#")
            if (hex.length != 6 && hex.length != 8) return null
            val number = hex.toLongOrNull(16) ?: return null
            return if (hex.length == 6) {
                Color(number.toInt())
            } else {
                Color((number shr 24).toInt() and 0xFF, (number shr 16).toInt() and 0xFF, (number shr 8).toInt() and 0xFF, number.toInt() and 0xFF)
            }
        }

        fun formatColor(color: Color): String {
            val rgb = "#%02x%02x%02x".format(color.red, color.green, color.blue)
            return if (color.alpha == 255) rgb else rgb + "%02x".format(color.alpha)
        }

        /** Muted tones for the current theme: dark backgrounds in a dark theme, pastels in a light one. */
        fun defaultPalette(): MutableList<PaletteEntry> {
            val values = if (JBColor.isBright()) {
                listOf("#f8d7d5", "#fbe3cc", "#f8f0c8", "#d8efd6", "#d4e7f8", "#dcdcf6", "#efd9f0", "#e4e4e4")
            } else {
                listOf("#4f060d", "#44220e", "#3f371b", "#162c16", "#0f2f47", "#171a34", "#311333", "#1e1e1e")
            }
            return values.mapIndexed { index, value -> PaletteEntry(index + 1, "Color ${index + 1}", value) }.toMutableList()
        }

        /** The other plugin's project file, when there is one; its paths are written with `$PROJECT_DIR$`. */
        fun importHighlightedFiles(project: Project): ColorsState? {
            val ideaDir = project.projectFilePath?.let { Path.of(it).parent } ?: return null
            return importHighlightedFiles(ideaDir.resolve(OTHER_PLUGIN_FILE))
        }

        fun importHighlightedFiles(file: Path): ColorsState? {
            if (!Files.isRegularFile(file)) return null
            return try {
                val component = JDOMUtil.load(file).getChildren("component").firstOrNull { it.getAttributeValue("name") == OTHER_PLUGIN_COMPONENT } ?: return null
                // The other plugin does not paint a colour it marks as disabled, so neither the colour nor its files come over.
                val colors = component.getChild("colors")?.getChildren("color").orEmpty().mapNotNull { element ->
                    if (element.getAttributeValue("enabled") == "false") return@mapNotNull null
                    val id = element.getAttributeValue("id")?.toIntOrNull() ?: return@mapNotNull null
                    val value = element.getAttributeValue("value")?.takeIf { parseColor(it) != null } ?: return@mapNotNull null
                    PaletteEntry(id, element.getAttributeValue("name") ?: "Color $id", value)
                }
                val ids = colors.map { it.id }.toSet()
                val files = component.getChild("files")?.getChildren("node").orEmpty().mapNotNull { element ->
                    val id = element.getAttributeValue("color")?.toIntOrNull()?.takeIf { it in ids } ?: return@mapNotNull null
                    val path = element.getAttributeValue("path") ?: return@mapNotNull null
                    if (path != PROJECT_DIR && !path.startsWith("$PROJECT_DIR/")) return@mapNotNull null
                    FileEntry(path.removePrefix(PROJECT_DIR).removePrefix("/"), id)
                }
                LOG.info("Imported ${files.size} coloured path(s) and ${colors.size} colour(s) from $file")
                ColorsState().apply {
                    this.colors = colors.toMutableList()
                    this.files = files.toMutableList()
                }
            } catch (e: Exception) {
                LOG.warn("Could not import $file", e)
                null
            }
        }
    }
}
