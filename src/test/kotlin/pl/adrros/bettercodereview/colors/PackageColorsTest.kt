package pl.adrros.bettercodereview.colors

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Color
import java.nio.file.Files

class PackageColorsTest : BasePlatformTestCase() {
    private val base = "/work/zt-backend"
    private lateinit var defaultColorFor: (com.intellij.openapi.project.Project, com.intellij.openapi.vfs.VirtualFile) -> Color?

    override fun setUp() {
        super.setUp()
        defaultColorFor = PackageLines.colorFor
    }

    override fun tearDown() {
        try {
            PackageLines.colorFor = defaultColorFor
        } finally {
            super.tearDown()
        }
    }

    private fun colors() = PackageColors(project).apply { loadState(PackageColors.ColorsState()) }

    fun `test colours parse with and without alpha and format back`() {
        assertEquals(Color(0x2d, 0x40, 0x31), PackageColors.parseColor("#2d4031"))
        assertEquals(Color(0x3a, 0x32, 0x14, 0xf6), PackageColors.parseColor("#3a3214f6"))
        assertNull(PackageColors.parseColor("#12"))
        assertNull(PackageColors.parseColor("#zzzzzz"))
        assertEquals("#2d4031", PackageColors.formatColor(Color(0x2d, 0x40, 0x31)))
        assertEquals("#3a3214f6", PackageColors.formatColor(Color(0x3a, 0x32, 0x14, 0xf6)))
    }

    fun `test a file takes the colour of its nearest coloured ancestor`() {
        val colors = colors()
        val palette = colors.colors
        colors.assign(base, listOf("$base/src/main/java/org/alpha/adapter"), palette[0].id)
        colors.assign(base, listOf("$base/src/main/java/org/alpha/adapter/persist/audit_log"), palette[1].id)
        assertEquals(palette[0].color, colors.colorOf(base, "$base/src/main/java/org/alpha/adapter/Foo.java"))
        assertEquals(palette[1].color, colors.colorOf(base, "$base/src/main/java/org/alpha/adapter/persist/audit_log/change/EntityChangeRecorder.java"))
        assertNull(colors.colorOf(base, "$base/src/main/java/org/alpha/api/Bar.java"))
        assertNull("outside the project", colors.colorOf(base, "/elsewhere/src/main/java/org/alpha/adapter/Foo.java"))
        assertNull("a sibling with a common prefix", colors.colorOf(base, "$base/src/main/java/org/alpha/adapterX/Foo.java"))
    }

    fun `test clearing takes only the file's own colour off`() {
        val colors = colors()
        val id = colors.colors[0].id
        colors.assign(base, listOf("$base/src/a", "$base/src/a/b"), id)
        colors.assign(base, listOf("$base/src/a/b"), null)
        assertEquals("still inherited from src/a", colors.colors[0].color, colors.colorOf(base, "$base/src/a/b/C.java"))
        assertEquals(listOf("src/a"), colors.getState().files.map { it.path })
    }

    fun `test the palette and the package-line switch can be edited`() {
        val colors = colors()
        val edited = colors.colors.mapIndexed { index, color -> if (index == 0) color.copy(name = "Adapter", color = Color(1, 2, 3)) else color }
        colors.update(edited, packageLine = false)
        assertEquals("Adapter", colors.colors[0].name)
        assertEquals(Color(1, 2, 3), colors.colors[0].color)
        assertEquals("#010203", colors.getState().colors[0].value)
        assertFalse(colors.tintsPackageLine)
    }

    fun `test the other plugin's settings are imported`() {
        val dir = Files.createTempDirectory("bcr-colors")
        val file = dir.resolve("highlightedFiles.xml")
        Files.writeString(
            file,
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project version="4">
              <component name="ProjectTreeColorHighlighter">
                <files>
                  <node color="3" path="${'$'}PROJECT_DIR${'$'}/src/main/java/org/alpha/ztbackend/adapter" />
                  <node color="4" path="${'$'}PROJECT_DIR${'$'}/src/main/java/org/alpha/ztbackend/domain" />
                  <node color="4" path="/absolute/elsewhere" />
                </files>
                <colors>
                  <color id="3" value="#3a3214f6" name="Color 3" enabled="true" />
                  <color id="4" value="#2d4031" name="Domain" enabled="true" />
                  <color id="5" value="not a colour" name="Broken" enabled="true" />
                </colors>
              </component>
            </project>
            """.trimIndent(),
        )
        val state = PackageColors.importHighlightedFiles(file)!!
        assertEquals(listOf("src/main/java/org/alpha/ztbackend/adapter" to 3, "src/main/java/org/alpha/ztbackend/domain" to 4), state.files.map { it.path to it.color })
        assertEquals(listOf(3, 4), state.colors.map { it.id })
        val colors = PackageColors(project).apply { loadState(state) }
        assertEquals(Color(0x3a, 0x32, 0x14, 0xf6), colors.colorOf(base, "$base/src/main/java/org/alpha/ztbackend/adapter/persist/X.java"))
        assertNull(PackageColors.importHighlightedFiles(dir.resolve("missing.xml")))
    }

    fun `test without imported colours the default palette is used`() {
        val colors = colors()
        assertEquals(8, colors.colors.size)
        assertTrue(colors.tintsPackageLine)
    }

    fun `test the package line is found below a licence header and nowhere in a file without one`() {
        val java = myFixture.configureByText("A.java", "/*\n * Licence: package is a word here\n */\n\npackage org.alpha.x;\n\nclass A {}\n").let { myFixture.editor.document }
        assertEquals(4, PackageLines.packageLine(java))
        val noPackage = myFixture.configureByText("B.java", "// package comment\nclass B {}\n").let { myFixture.editor.document }
        assertNull(PackageLines.packageLine(noPackage))
        val kotlin = myFixture.configureByText("C.kt", "@file:JvmName(\"C\")\npackage org.alpha.y\n").let { myFixture.editor.document }
        assertEquals(1, PackageLines.packageLine(kotlin))
    }

    fun `test the editor tints the package line in the package's colour and follows edits`() {
        var color: Color? = Color(0x2d, 0x40, 0x31)
        PackageLines.colorFor = { _, _ -> color }
        myFixture.configureByText("A.java", "package org.alpha.x;\n\nclass A {}\n")
        val editor = myFixture.editor
        PackageLines.refresh(editor)
        assertNull("the test editor is not a main editor", PackageLines.highlighterOf(editor))
        PackageLines.tint(editor)
        val highlighter = PackageLines.highlighterOf(editor)!!
        assertEquals(0, editor.document.getLineNumber(highlighter.startOffset))
        assertEquals(color, highlighter.getTextAttributes(null)!!.backgroundColor)

        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { editor.document.insertString(0, "// header\n") }
        PackageLines.tint(editor)
        assertEquals("the highlighter moved with the line", 1, editor.document.getLineNumber(PackageLines.highlighterOf(editor)!!.startOffset))

        color = Color.RED
        PackageLines.tint(editor)
        assertEquals(Color.RED, PackageLines.highlighterOf(editor)!!.getTextAttributes(null)!!.backgroundColor)

        color = null
        PackageLines.tint(editor)
        assertNull(PackageLines.highlighterOf(editor))
        assertEmpty(editor.markupModel.allHighlighters.filter { it.getTextAttributes(null)?.backgroundColor == Color.RED })
    }

    fun `test the Package Color menu has its name, or the IDE drops it from the menu`() {
        val group = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("BetterCodeReview.PackageColor")
        assertEquals("Package Color", group.templatePresentation.text)
    }

    fun `test the project service starts with a palette`() {
        assertFalse(project.service<PackageColors>().colors.isEmpty())
    }

    fun `test the state survives a round trip through XML`() {
        val colors = colors()
        colors.assign(base, listOf(base, "$base/src/a"), colors.colors[2].id)
        colors.update(colors.colors.map { if (it.id == 1) it.copy(color = Color(1, 2, 3, 40)) else it }, packageLine = false)
        val element = com.intellij.util.xmlb.XmlSerializer.serialize(colors.getState())
        val restored = PackageColors(project).apply { loadState(com.intellij.util.xmlb.XmlSerializer.deserialize(element, PackageColors.ColorsState::class.java)) }
        assertEquals(colors.colors, restored.colors)
        assertFalse(restored.tintsPackageLine)
        assertEquals(colors.colors[2].color, restored.colorOf(base, "$base/src/a/B.java"))
        assertEquals("the base directory itself", colors.colors[2].color, restored.colorOf(base, "$base/README.md"))
    }

    fun `test the default palette is not written until it is edited`() {
        val colors = PackageColors(project).apply { noStateLoaded() }
        assertEquals(8, colors.colors.size)
        assertEmpty(colors.getState().colors)
        colors.assign(base, listOf("$base/src"), colors.colors[0].id)
        assertEmpty("colouring a folder does not store the palette", colors.getState().colors)
        assertEquals(colors.colors[0].color, colors.colorOf(base, "$base/src/A.java"))
    }

    fun `test a colour disabled in the other plugin comes over with none of its files`() {
        val file = Files.createTempDirectory("bcr-colors").resolve("highlightedFiles.xml")
        Files.writeString(
            file,
            """
            <project version="4">
              <component name="ProjectTreeColorHighlighter">
                <files>
                  <node color="1" path="${'$'}PROJECT_DIR${'$'}/a" />
                  <node color="2" path="${'$'}PROJECT_DIR${'$'}/b" />
                </files>
                <colors>
                  <color id="1" value="#112233" name="On" enabled="true" />
                  <color id="2" value="#445566" name="Off" enabled="false" />
                </colors>
              </component>
            </project>
            """.trimIndent(),
        )
        val state = PackageColors.importHighlightedFiles(file)!!
        assertEquals(listOf(1), state.colors.map { it.id })
        assertEquals(listOf("a"), state.files.map { it.path })
    }

    fun `test only files of languages with a package line are tinted`() {
        PackageLines.colorFor = { _, _ -> Color.GREEN }
        myFixture.configureByText("README.md", "package manager notes\n")
        PackageLines.tint(myFixture.editor)
        assertNull(PackageLines.highlighterOf(myFixture.editor))
        myFixture.configureByText("Main.go", "package main\n")
        PackageLines.tint(myFixture.editor)
        assertNotNull(PackageLines.highlighterOf(myFixture.editor))
    }
}
