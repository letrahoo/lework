package dev.lework

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.lework.platform.JvmPlatformTestHooks
import dev.lework.store.LinkedFilePreviewEntry
import dev.lework.store.ParsedLocalFileLink
import dev.lework.store.parseLocalFileLink
import dev.lework.ui.DesktopFilePreviewDialog
import dev.lework.ui.Everforest
import dev.lework.ui.detectCodeLanguage
import dev.lework.ui.highlightFileContent
import dev.lework.ui.highlightFileLines
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CodeSyntaxHighlighterTest {

    @BeforeTest
    fun setUp() {
        JvmPlatformTestHooks.reset()
    }

    @AfterTest
    fun tearDown() {
        JvmPlatformTestHooks.reset()
    }

    private fun colorsForToken(annotated: AnnotatedString, token: String): Set<Color> {
        val idx = annotated.text.indexOf(token)
        assertTrue(idx >= 0, "Expected token '$token' in:\n${annotated.text}")
        val end = idx + token.length
        return annotated.spanStyles
            .filter { it.start < end && it.end > idx }
            .map { it.item.color }
            .toSet()
    }

    @Test
    fun `detectCodeLanguage resolves extensions, special filenames, and shebang scripts`() {
        assertEquals("Kotlin", detectCodeLanguage("/repo/app/Main.kt").displayName)
        assertEquals("Kotlin Script", detectCodeLanguage("/repo/build.gradle.kts").displayName)
        assertEquals("TypeScript", detectCodeLanguage("/repo/hub/server.ts").displayName)
        assertEquals("TSX", detectCodeLanguage("/repo/web/App.tsx").displayName)
        assertEquals("JavaScript", detectCodeLanguage("/repo/scripts/check.mjs").displayName)
        assertEquals("Python", detectCodeLanguage("/repo/tools/train.py").displayName)
        assertEquals("Go", detectCodeLanguage("/repo/tsnet-bridge/main.go").displayName)
        assertEquals("Go Mod", detectCodeLanguage("/repo/tsnet-bridge/go.mod").displayName)
        assertEquals("Rust", detectCodeLanguage("/repo/src/lib.rs").displayName)
        assertEquals("C++", detectCodeLanguage("/repo/native/engine.cpp").displayName)
        assertEquals("Swift", detectCodeLanguage("/repo/iosApp/App.swift").displayName)
        assertEquals("Java", detectCodeLanguage("/repo/src/Server.java").displayName)
        assertEquals("SQL", detectCodeLanguage("/repo/db/schema.sql").displayName)
        assertEquals("JSON", detectCodeLanguage("/repo/package.json").displayName)
        assertEquals("JSONC", detectCodeLanguage("/repo/tsconfig.json").displayName)
        assertEquals("YAML", detectCodeLanguage("/repo/.github/workflows/ci.yml").displayName)
        assertEquals("TOML", detectCodeLanguage("/Users/dev/.codex/config.toml").displayName)
        assertEquals("HTML", detectCodeLanguage("/Users/dev/.agent-memory/overview.html").displayName)
        assertEquals("SCSS", detectCodeLanguage("/repo/styles/theme.scss").displayName)
        assertEquals("Protobuf", detectCodeLanguage("/repo/api/service.proto").displayName)
        assertEquals("Dockerfile", detectCodeLanguage("/repo/Dockerfile").displayName)
        assertEquals("Dockerfile", detectCodeLanguage("/repo/Dockerfile.prod").displayName)
        assertEquals("Makefile", detectCodeLanguage("/repo/Makefile").displayName)
        assertEquals("CMake", detectCodeLanguage("/repo/CMakeLists.txt").displayName)
        assertEquals("Ruby", detectCodeLanguage("/repo/Podfile").displayName)
        assertEquals("Zsh", detectCodeLanguage("/Users/dev/.zshrc").displayName)
        assertEquals("Gitignore", detectCodeLanguage("/repo/.gitignore").displayName)
        assertEquals("Markdown", detectCodeLanguage("/repo/README.md").displayName)

        // Shebang fallback for extensionless scripts
        assertEquals("Python", detectCodeLanguage("/usr/local/bin/mytool", "#!/usr/bin/env python3\nprint('hi')").displayName)
        assertEquals("Bash", detectCodeLanguage("/usr/local/bin/deploy", "#!/bin/bash\necho ok").displayName)
        assertEquals("JavaScript", detectCodeLanguage("/usr/local/bin/run", "#!/usr/bin/env node\nconsole.log(1)").displayName)
        assertEquals("plain", detectCodeLanguage("/tmp/notes.unknown", "just plain text").id)
    }

    @Test
    fun `highlights Kotlin, TypeScript, Python, Go, Rust, C++, and SQL with multi-line state preserved`() {
        // Kotlin with multi-line comment, annotation, types, function, string, number
        val ktCode = """
            /* Multi-line
               header comment */
            @Composable
            suspend fun loadCount(name: String): Int {
                val msg = "hello\nworld"
                return 42
            }
        """.trimIndent()
        val ktLines = highlightFileLines(ktCode, "Sample.kt")
        assertEquals(ktCode.split('\n').size, ktLines.size)
        assertTrue(Everforest.CommentGrey in colorsForToken(ktLines[0], "Multi-line"))
        assertTrue(Everforest.CommentGrey in colorsForToken(ktLines[1], "header comment"))
        assertTrue(Everforest.Orange in colorsForToken(ktLines[2], "@Composable"))
        assertTrue(Everforest.Red in colorsForToken(ktLines[3], "suspend"))
        assertTrue(Everforest.Green in colorsForToken(ktLines[3], "loadCount"))
        assertTrue(Everforest.Yellow in colorsForToken(ktLines[3], "String"))
        assertTrue(Everforest.Aqua in colorsForToken(ktLines[4], "\"hello"))
        assertTrue(Everforest.Purple in colorsForToken(ktLines[4], "\\n"))
        assertTrue(Everforest.Purple in colorsForToken(ktLines[5], "42"))

        // Rust lifetimes vs char literals, raw strings, attributes, macros
        val rsCode = """
            #[derive(Debug)]
            pub fn first<'a>(s: &'a str) -> char {
                let raw = r#"line 1
            line 2"#;
                println!("{}", raw);
                'x'
            }
        """.trimIndent()
        val rsLines = highlightFileLines(rsCode, "lib.rs")
        assertTrue(Everforest.Orange in colorsForToken(rsLines[0], "#[derive"))
        assertTrue(Everforest.Red in colorsForToken(rsLines[1], "pub"))
        assertTrue(Everforest.Orange in colorsForToken(rsLines[1], "'a"))
        assertTrue(Everforest.Yellow in colorsForToken(rsLines[1], "str"))
        assertTrue(Everforest.Aqua in colorsForToken(rsLines[2], "r#\"line 1"))
        assertTrue(Everforest.Aqua in colorsForToken(rsLines[3], "line 2\"#"))
        assertTrue(Everforest.Orange in colorsForToken(rsLines[4], "println!"))
        assertTrue(Everforest.Aqua in colorsForToken(rsLines[5], "'x'"))

        // Python decorator, triple-quoted docstring, f-string, comment
        val pyCode = """
            @dataclass
            def greet(user: str) -> str:
                '''Doc line 1
                Doc line 2'''
                # comment
                return f"hi {user}"
        """.trimIndent()
        val pyLines = highlightFileLines(pyCode, "app.py")
        assertTrue(Everforest.Orange in colorsForToken(pyLines[0], "@dataclass"))
        assertTrue(Everforest.Red in colorsForToken(pyLines[1], "def"))
        assertTrue(Everforest.Green in colorsForToken(pyLines[1], "greet"))
        assertTrue(Everforest.Aqua in colorsForToken(pyLines[2], "'''Doc line 1"))
        assertTrue(Everforest.Aqua in colorsForToken(pyLines[3], "Doc line 2'''"))
        assertTrue(Everforest.CommentGrey in colorsForToken(pyLines[4], "# comment"))

        // Go raw backtick string spanning lines + keywords + types
        val goCode = """
            package main
            func run(n int) string {
                return `multi
            line`
            }
        """.trimIndent()
        val goLines = highlightFileLines(goCode, "main.go")
        assertTrue(Everforest.Red in colorsForToken(goLines[0], "package"))
        assertTrue(Everforest.Yellow in colorsForToken(goLines[1], "int"))
        assertTrue(Everforest.Aqua in colorsForToken(goLines[2], "`multi"))
        assertTrue(Everforest.Aqua in colorsForToken(goLines[3], "line`"))

        // C++ preprocessor directive & SQL case-insensitive keywords
        val cppStyled = highlightFileContent("#include <vector>\nconst int n = 10;", "engine.cpp")
        assertTrue(Everforest.Orange in colorsForToken(cppStyled, "#include"))
        assertTrue(Everforest.Aqua in colorsForToken(cppStyled, "<vector>"))
        assertTrue(Everforest.Red in colorsForToken(cppStyled, "const"))

        val sqlStyled = highlightFileContent("-- query\nselect count(*) from users where name = 'O''Brien';", "query.sql")
        assertTrue(Everforest.CommentGrey in colorsForToken(sqlStyled, "-- query"))
        assertTrue(Everforest.Red in colorsForToken(sqlStyled, "select"))
        assertTrue(Everforest.Green in colorsForToken(sqlStyled, "count"))
        assertTrue(Everforest.Aqua in colorsForToken(sqlStyled, "'O"))
        assertTrue(Everforest.Purple in colorsForToken(sqlStyled, "''"))
    }

    @Test
    fun `highlights JSON, YAML, TOML, HTML with embedded CSS and JS, Shell, Dockerfile, and Markdown source`() {
        val jsonStyled = highlightFileContent("""{"name": "lework", "port": 64450, "tls": true}""", "config.json")
        assertTrue(Everforest.Blue in colorsForToken(jsonStyled, "\"name\""))
        assertTrue(Everforest.Aqua in colorsForToken(jsonStyled, "\"lework\""))
        assertTrue(Everforest.Purple in colorsForToken(jsonStyled, "64450"))
        assertTrue(Everforest.Purple in colorsForToken(jsonStyled, "true"))

        val yamlStyled = highlightFileContent("name: lework\nretries: 3\nenabled: true\n# note", "ci.yml")
        assertTrue(Everforest.Blue in colorsForToken(yamlStyled, "name"))
        assertTrue(Everforest.Purple in colorsForToken(yamlStyled, "3"))
        assertTrue(Everforest.Purple in colorsForToken(yamlStyled, "true"))
        assertTrue(Everforest.CommentGrey in colorsForToken(yamlStyled, "# note"))

        val tomlStyled = highlightFileContent("[server]\nport = 64450\nhost = \"127.0.0.1\"", "config.toml")
        assertTrue(Everforest.BoldGold in colorsForToken(tomlStyled, "[server]"))
        assertTrue(Everforest.Blue in colorsForToken(tomlStyled, "port"))
        assertTrue(Everforest.Aqua in colorsForToken(tomlStyled, "\"127.0.0.1\""))

        val htmlCode = """
            <!-- multi
                 comment -->
            <style>
            .card { color: #e6c384; padding: 12px; }
            </style>
            <script>
            const count = 7;
            </script>
            <div class="hero">Hello &amp; welcome</div>
        """.trimIndent()
        val htmlLines = highlightFileLines(htmlCode, "index.html")
        assertTrue(Everforest.CommentGrey in colorsForToken(htmlLines[0], "<!-- multi"))
        assertTrue(Everforest.CommentGrey in colorsForToken(htmlLines[1], "comment -->"))
        assertTrue(Everforest.Yellow in colorsForToken(htmlLines[3], ".card"))
        assertTrue(Everforest.Purple in colorsForToken(htmlLines[3], "#e6c384"))
        assertTrue(Everforest.Red in colorsForToken(htmlLines[6], "const"))
        assertTrue(Everforest.Purple in colorsForToken(htmlLines[6], "7"))
        assertTrue(Everforest.Red in colorsForToken(htmlLines[8], "div"))
        assertTrue(Everforest.Yellow in colorsForToken(htmlLines[8], "class"))
        assertTrue(Everforest.Aqua in colorsForToken(htmlLines[8], "\"hero\""))
        assertTrue(Everforest.Purple in colorsForToken(htmlLines[8], "&amp;"))

        val shellStyled = highlightFileContent("#!/bin/zsh\nexport PATH=\"${'$'}HOME/bin\"\ngit status --short", ".zshrc")
        assertTrue(Everforest.Orange in colorsForToken(shellStyled, "#!/bin/zsh"))
        assertTrue(Everforest.Red in colorsForToken(shellStyled, "export"))
        assertTrue(Everforest.Green in colorsForToken(shellStyled, "git"))
        assertTrue(Everforest.Yellow in colorsForToken(shellStyled, "--short"))

        val dockerStyled = highlightFileContent("FROM node:22-alpine\nWORKDIR /app\nRUN pnpm test", "Dockerfile")
        assertTrue(Everforest.Red in colorsForToken(dockerStyled, "FROM"))
        assertTrue(Everforest.Red in colorsForToken(dockerStyled, "WORKDIR"))
        assertTrue(Everforest.Red in colorsForToken(dockerStyled, "RUN"))

        val mdLines = highlightFileLines("# Title\n```kotlin\nval x = 1\n```", "README.md")
        assertTrue(Everforest.BoldGold in colorsForToken(mdLines[0], "# Title"))
        assertTrue(Everforest.Orange in colorsForToken(mdLines[1], "```kotlin"))
        assertTrue(Everforest.Red in colorsForToken(mdLines[2], "val"))
        assertTrue(Everforest.Purple in colorsForToken(mdLines[2], "1"))
    }

    @Test
    fun `preserves exact text and handles large minified lines safely without crashing`() {
        val hugeLine = "const x = " + "a".repeat(10_000) + ";"
        val lines = highlightFileLines(hugeLine, "bundle.min.js")
        assertEquals(1, lines.size)
        assertEquals(hugeLine, lines[0].text)
    }

    @Test
    fun `parseLocalFileLink supports colon line-column format, extensionless files, and dotfiles`() {
        assertEquals(
            ParsedLocalFileLink("src/main.go", startLine = 42, endLine = 42),
            parseLocalFileLink("src/main.go:42:10"),
        )
        assertEquals(ParsedLocalFileLink("Dockerfile"), parseLocalFileLink("Dockerfile"))
        assertEquals(ParsedLocalFileLink("Makefile"), parseLocalFileLink("Makefile"))
        assertEquals(ParsedLocalFileLink(".zshrc"), parseLocalFileLink(".zshrc"))
    }

    @Test
    fun `file preview dialog renders language badge for code files and source toggle for markdown`() = runComposeUiTest {
        JvmPlatformTestHooks.simulateDesktopLayout = true
        val goEntry = LinkedFilePreviewEntry(
            rawTarget = "/Users/dev/outside/main.go",
            resolvedPath = "/Users/dev/outside/main.go",
            displayName = "main.go",
            content = "package main\n\nfunc main() {\n    println(\"hi\")\n}",
            loading = false,
        )

        setContent {
            Box(modifier = Modifier.size(width = 1100.dp, height = 820.dp)) {
                DesktopFilePreviewDialog(
                    entry = goEntry,
                    canNavigateBack = false,
                    onNavigateBack = {},
                    onOpenNestedLink = { _, _ -> },
                    onClose = {},
                )
            }
        }

        onNodeWithTag("file_preview_lang_badge", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Go", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("file_preview_code_list", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `markdown preview opens line-anchored links in highlighted source mode and toggles to rendered view`() =
        runComposeUiTest {
            JvmPlatformTestHooks.simulateDesktopLayout = true
            val mdEntry = LinkedFilePreviewEntry(
                rawTarget = "/Users/dev/docs/README.md#L2-L3",
                resolvedPath = "/Users/dev/docs/README.md",
                displayName = "README.md",
                startLine = 2,
                endLine = 3,
                content = "# Lework Overview\n\n```kotlin\nval ready = true\n```",
                loading = false,
            )

            setContent {
                Box(modifier = Modifier.size(width = 1100.dp, height = 820.dp)) {
                    DesktopFilePreviewDialog(
                        entry = mdEntry,
                        canNavigateBack = false,
                        onNavigateBack = {},
                        onOpenNestedLink = { _, _ -> },
                        onClose = {},
                    )
                }
            }

            onNodeWithTag("file_preview_lang_badge", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("file_preview_tab_source", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("file_preview_tab_rendered", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("file_preview_code_list", useUnmergedTree = true).assertIsDisplayed()

            onNodeWithTag("file_preview_tab_rendered", useUnmergedTree = true).performClick()
            onNodeWithText("Lework Overview", substring = true, useUnmergedTree = true).assertIsDisplayed()
        }
}
