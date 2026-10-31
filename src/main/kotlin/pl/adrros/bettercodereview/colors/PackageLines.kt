package pl.adrros.bettercodereview.colors

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Color

/**
 * Tints the `package` line of a file in a coloured package with that package's colour, so the editor shows which
 * part of the project the file belongs to. Works for any language that declares its package on a line of its own
 * (Java, Kotlin, Scala, Groovy, Go). The main editors get the tint and both sides of a diff. A file whose own folder
 * has no colour (a test next to coloured main sources) and a past revision, which has no file on disk, take the colour
 * of the folder their package names.
 */
object PackageLines {
    private val KEY = Key.create<RangeHighlighter>("BetterCodeReview.packageLine")
    private val PACKAGE = Regex("""(?m)^[ \t]*package[ \t]+([A-Za-z_`][\w.`]*)""")

    /** The package declaration sits near the top, after at most a licence header. */
    private const val SCAN_CHARS = 20_000

    /** Languages that declare their package on a line of its own; a README's "package manager" line stays plain. */
    private val EXTENSIONS = setOf("java", "kt", "kts", "scala", "groovy", "go")

    /** Above the caret row, so the tint stays when the caret is on that line. */
    private const val LAYER = HighlighterLayer.CARET_ROW + 1

    /** The 0-based line of the package declaration, or null. */
    fun packageLine(document: Document): Int? {
        val text = document.immutableCharSequence
        val match = PACKAGE.find(text.subSequence(0, minOf(text.length, SCAN_CHARS))) ?: return null
        return document.getLineNumber(match.range.first)
    }

    /** The declared package, "org.alpha.x", or null. */
    fun packageName(document: Document): String? {
        val text = document.immutableCharSequence
        return PACKAGE.find(text.subSequence(0, minOf(text.length, SCAN_CHARS)))?.groupValues?.get(1)?.replace("`", "")
    }

    fun highlighterOf(editor: Editor): RangeHighlighter? = editor.getUserData(KEY)

    /** The tint for a file, or null; headless tests, whose files live outside the project's base directory, replace it. */
    var colorFor: (Project, VirtualFile) -> Color? = { project, file ->
        project.service<PackageColors>().takeIf { it.tintsPackageLine }?.colorOf(file)
    }

    /**
     * The tint for a package seen in a diff of a past revision: the colour of its folder under the source roots, the
     * roots where that folder exists first. Headless tests replace it.
     */
    var colorOfPackage: (Project, String) -> Color? = { project, name ->
        val colors = project.service<PackageColors>().takeIf { it.tintsPackageLine }
        val base = project.basePath
        val relative = name.replace('.', '/')
        if (colors == null || base == null) null
        else ProjectRootManager.getInstance(project).contentSourceRoots
            .sortedBy { it.findFileByRelativePath(relative) == null }
            .firstNotNullOfOrNull { colors.colorOf(base, "${it.path}/$relative") }
    }

    /** Editors of the editor area and of diffs: previews and consoles stay as they are. */
    fun refresh(editor: Editor) {
        if (editor.editorKind == EditorKind.MAIN_EDITOR || editor.editorKind == EditorKind.DIFF) tint(editor)
    }

    internal fun tint(editor: Editor) {
        if (editor.isDisposed) return
        val project = editor.project ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document)
        val color = when {
            file != null -> file.takeIf { it.extension?.lowercase() in EXTENSIONS }
                ?.let { colorFor(project, it) ?: packageName(editor.document)?.let { name -> colorOfPackage(project, name) } }
            editor.editorKind == EditorKind.DIFF -> packageName(editor.document)?.let { colorOfPackage(project, it) }
            else -> null
        }
        val line = color?.let { packageLine(editor.document) }
        val existing = editor.getUserData(KEY)
        if (existing != null && existing.isValid && line != null &&
            editor.document.getLineNumber(existing.startOffset) == line && existing.getTextAttributes(null)?.backgroundColor == color
        ) {
            return
        }
        existing?.let { editor.markupModel.removeHighlighter(it) }
        editor.putUserData(KEY, null)
        if (line != null) {
            editor.putUserData(KEY, editor.markupModel.addLineHighlighter(line, LAYER, TextAttributes().apply { backgroundColor = color }))
        }
    }

    fun refreshAll(project: Project) {
        EditorFactory.getInstance().allEditors.filter { it.project == project }.forEach(::refresh)
    }

    /** On plugin unload, so a newer version starts from clean editors. */
    fun removeAll() {
        for (editor in EditorFactory.getInstance().allEditors) {
            editor.getUserData(KEY)?.let { editor.markupModel.removeHighlighter(it) }
            editor.putUserData(KEY, null)
        }
    }

    class EditorListener : EditorFactoryListener {
        override fun editorCreated(event: EditorFactoryEvent) = refresh(event.editor)
    }

    /** An edit near the top may add, move or remove the package line; edits further down leave it where it is. */
    class EditListener : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            if (event.offset > SCAN_CHARS) return
            EditorFactory.getInstance().getEditors(event.document).forEach(::refresh)
        }
    }
}
