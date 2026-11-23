package dev.lework

import dev.lework.ui.MermaidDiagram
import dev.lework.ui.MermaidDiagramKind
import dev.lework.ui.MermaidDirection
import dev.lework.ui.MermaidEdgeStyle
import dev.lework.ui.MermaidNodeShape
import dev.lework.ui.MermaidSeqArrowStyle
import dev.lework.ui.MermaidSeqItem
import dev.lework.ui.SeqNotePlacement
import dev.lework.ui.formatMermaidPercent
import dev.lework.ui.layoutGraphDiagram
import dev.lework.ui.layoutSequenceDiagram
import dev.lework.ui.parseMarkdownBlocks
import dev.lework.ui.MdBlock
import dev.lework.ui.parseMermaidDiagram
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EverforestMermaidTest {

    @Test
    fun `parses flowchart TD with all shapes, quoted labels with parens and br, subgraphs, comments, and directives`() {
        val src = """
            %% Architecture overview comment
            flowchart TD
                subgraph edge["Edge Layer (TLS)"]
                    A["API Gateway (v2)<br/>Entry"] --> B(Auth Filter)
                    B --> C([Rate Limiter])
                end
                C --> D{"Cache Hit (Redis)?"}
                D -->|Yes| E((Return))
                D -- Miss --> F[(Primary DB)]
                F -.->|async| G[[Audit Worker]]
                G ==> E
                A <--> B
                style A fill:#333
                classDef highlight stroke:#fff
                class A highlight
                click A "https://example.com"
        """.trimIndent()

        val diagram = assertIs<MermaidDiagram.Graph>(parseMermaidDiagram(src))
        assertEquals(MermaidDiagramKind.FLOWCHART, diagram.kind)
        assertEquals(MermaidDirection.TD, diagram.direction)

        val byId = diagram.nodes.associateBy { it.id }
        assertEquals(7, byId.size)
        assertEquals(MermaidNodeShape.RECT, byId["A"]?.shape)
        assertEquals("API Gateway (v2)\nEntry", byId["A"]?.label)
        assertEquals(MermaidNodeShape.ROUNDED, byId["B"]?.shape)
        assertEquals("Auth Filter", byId["B"]?.label)
        assertEquals(MermaidNodeShape.STADIUM, byId["C"]?.shape)
        assertEquals("Rate Limiter", byId["C"]?.label)
        assertEquals(MermaidNodeShape.DIAMOND, byId["D"]?.shape)
        assertEquals("Cache Hit (Redis)?", byId["D"]?.label)
        assertEquals(MermaidNodeShape.CIRCLE, byId["E"]?.shape)
        assertEquals("Return", byId["E"]?.label)
        assertEquals(MermaidNodeShape.CYLINDER, byId["F"]?.shape)
        assertEquals("Primary DB", byId["F"]?.label)
        assertEquals(MermaidNodeShape.SUBROUTINE, byId["G"]?.shape)
        assertEquals("Audit Worker", byId["G"]?.label)

        // Subgraph parsed cleanly
        assertEquals(1, diagram.subgraphs.size)
        assertEquals("edge", diagram.subgraphs[0].id)
        assertEquals("Edge Layer (TLS)", diagram.subgraphs[0].title)
        assertEquals(listOf("A", "B", "C"), diagram.subgraphs[0].nodeIds)

        // Edge styles & labels
        assertTrue(diagram.edges.any { it.from == "D" && it.to == "E" && it.label == "Yes" && it.style == MermaidEdgeStyle.SOLID_ARROW })
        assertTrue(diagram.edges.any { it.from == "D" && it.to == "F" && it.label == "Miss" && it.style == MermaidEdgeStyle.SOLID_ARROW })
        assertTrue(diagram.edges.any { it.from == "F" && it.to == "G" && it.label == "async" && it.style == MermaidEdgeStyle.DASHED_ARROW })
        assertTrue(diagram.edges.any { it.from == "G" && it.to == "E" && it.style == MermaidEdgeStyle.THICK_ARROW })
        assertTrue(diagram.edges.any { it.from == "A" && it.to == "B" && it.style == MermaidEdgeStyle.BIDIRECTIONAL })

        // Layout terminates cleanly with valid positive bounds and vertical layering (TD)
        val layout = layoutGraphDiagram(diagram)
        assertTrue(layout.width > 0f && layout.height > 0f)
        assertEquals(7, layout.nodeRects.size)
        val aRect = layout.nodeRects.getValue("A")
        val fRect = layout.nodeRects.getValue("F")
        assertTrue(aRect.top < fRect.top, "Expected TD layout to place A above F: A=$aRect, F=$fRect")
        assertEquals(1, layout.subgraphs.size)
        assertTrue(layout.subgraphs[0].rect.left < aRect.left)
        assertTrue(layout.subgraphs[0].rect.top < aRect.top)
    }

    @Test
    fun `parses graph LR with ampersand fan-in fan-out, chained edges, and cyclic back-edges without infinite loop`() {
        val src = """
            graph LR
                Web & Mobile --> Gateway --> Worker --> Store
                Worker -.->|retry| Gateway
                Store --- Archive
        """.trimIndent()

        val diagram = assertIs<MermaidDiagram.Graph>(parseMermaidDiagram(src))
        assertEquals(MermaidDiagramKind.FLOWCHART, diagram.kind)
        assertEquals(MermaidDirection.LR, diagram.direction)
        assertEquals(setOf("Web", "Mobile", "Gateway", "Worker", "Store", "Archive"), diagram.nodes.map { it.id }.toSet())

        // Fan-in `Web & Mobile --> Gateway` produces 2 edges
        assertTrue(diagram.edges.any { it.from == "Web" && it.to == "Gateway" })
        assertTrue(diagram.edges.any { it.from == "Mobile" && it.to == "Gateway" })
        // Chained edges
        assertTrue(diagram.edges.any { it.from == "Gateway" && it.to == "Worker" })
        assertTrue(diagram.edges.any { it.from == "Worker" && it.to == "Store" })
        // Cycle back-edge
        assertTrue(diagram.edges.any { it.from == "Worker" && it.to == "Gateway" && it.label == "retry" && it.style == MermaidEdgeStyle.DASHED_ARROW })
        // Solid undirected line
        assertTrue(diagram.edges.any { it.from == "Store" && it.to == "Archive" && it.style == MermaidEdgeStyle.SOLID_LINE })

        // Layout MUST break cycles deterministically and lay out left-to-right
        val layout = layoutGraphDiagram(diagram)
        assertTrue(layout.backEdgeIndices.isNotEmpty(), "Expected cycle edge Worker -> Gateway to be marked as back-edge")
        val webRect = layout.nodeRects.getValue("Web")
        val gwRect = layout.nodeRects.getValue("Gateway")
        val storeRect = layout.nodeRects.getValue("Store")
        assertTrue(webRect.left < gwRect.left, "Expected LR layout Web left of Gateway")
        assertTrue(gwRect.left < storeRect.left, "Expected LR layout Gateway left of Store")
    }

    @Test
    fun `parses sequenceDiagram with actor, participant aliases, self-calls, notes, and alt-else-end blocks`() {
        val src = """
            sequenceDiagram
                actor U as 用户
                participant App as 移动端 App
                participant Hub as Local Hub
                U->>App: 输入指令
                App->>Hub: WebSocket 发送 prompt
                Hub->>Hub: 校验会话状态
                Note over App,Hub: 双向长连接保活
                alt 权限需要审批
                    Hub-->>App: PermissionRequested
                    App->>Hub: Allow Once
                else 直接执行
                    Hub-->>App: AssistantDelta (流式输出)
                end
                Note right of Hub: 写入本地日志
        """.trimIndent()

        val seq = assertIs<MermaidDiagram.Sequence>(parseMermaidDiagram(src))
        assertEquals(MermaidDiagramKind.SEQUENCE, seq.kind)
        assertEquals(3, seq.participants.size)
        assertTrue(seq.participants[0].isActor)
        assertEquals("用户", seq.participants[0].label)
        assertFalse(seq.participants[1].isActor)
        assertEquals("移动端 App", seq.participants[1].label)

        val messages = seq.items.filterIsInstance<MermaidSeqItem.Message>()
        assertEquals(6, messages.size)
        assertTrue(messages.any { it.from == "Hub" && it.to == "Hub" && it.text == "校验会话状态" })
        assertTrue(messages.any { it.from == "Hub" && it.to == "App" && it.style == MermaidSeqArrowStyle.DASHED_ARROW })

        val notes = seq.items.filterIsInstance<MermaidSeqItem.Note>()
        assertEquals(2, notes.size)
        assertEquals(SeqNotePlacement.OVER, notes[0].placement)
        assertEquals(listOf("App", "Hub"), notes[0].participants)
        assertEquals(SeqNotePlacement.RIGHT_OF, notes[1].placement)

        assertTrue(seq.items.any { it is MermaidSeqItem.BlockStart && it.kind == "alt" && it.label == "权限需要审批" })
        assertTrue(seq.items.any { it is MermaidSeqItem.BlockDivider && it.kind == "else" && it.label == "直接执行" })
        assertTrue(seq.items.any { it is MermaidSeqItem.BlockEnd })

        val layout = layoutSequenceDiagram(seq)
        assertTrue(layout.width > 300f && layout.height > 200f)
        assertEquals(1, layout.blocks.size)
        assertEquals(1, layout.blocks[0].dividers.size)
        assertTrue(layout.participantX.getValue("U") < layout.participantX.getValue("App"))
        assertTrue(layout.participantX.getValue("App") < layout.participantX.getValue("Hub"))
    }

    @Test
    fun `parses stateDiagram-v2 with start and end pseudostates and named state transitions`() {
        val src = """
            stateDiagram-v2
                state "等待审批" as Waiting
                [*] --> Running : submit
                Running --> Waiting : tool_permission
                Waiting --> Running : approve
                Running --> Idle : turn_complete
                Idle --> [*]
        """.trimIndent()

        val diagram = assertIs<MermaidDiagram.Graph>(parseMermaidDiagram(src))
        assertEquals(MermaidDiagramKind.STATE, diagram.kind)

        val startNode = diagram.nodes.firstOrNull { it.shape == MermaidNodeShape.STATE_START }
        val endNode = diagram.nodes.firstOrNull { it.shape == MermaidNodeShape.STATE_END }
        val waitingNode = diagram.nodes.firstOrNull { it.id == "Waiting" }
        assertTrue(startNode != null, "Expected distinct STATE_START node")
        assertTrue(endNode != null, "Expected distinct STATE_END node")
        assertEquals("等待审批", waitingNode?.label)
        assertEquals(5, diagram.edges.size)
        assertTrue(diagram.edges.any { it.from == "Running" && it.to == "Waiting" && it.label == "tool_permission" })

        val layout = layoutGraphDiagram(diagram)
        assertTrue(layout.nodeRects.getValue(startNode!!.id).top < layout.nodeRects.getValue(endNode!!.id).top)
    }

    @Test
    fun `parses erDiagram and classDiagram entities, members, and relationships cleanly`() {
        val erSrc = """
            erDiagram
                USER {
                    string id PK
                    string email UK
                }
                SESSION {
                    string id PK
                    string user_id FK
                    string status
                }
                USER ||--o{ SESSION : owns
        """.trimIndent()

        val er = assertIs<MermaidDiagram.Graph>(parseMermaidDiagram(erSrc))
        assertEquals(MermaidDiagramKind.ER, er.kind)
        val user = er.nodes.first { it.id == "USER" }
        val session = er.nodes.first { it.id == "SESSION" }
        assertEquals(MermaidNodeShape.CARD, user.shape)
        assertEquals(listOf("string id PK", "string email UK"), user.members)
        assertEquals(3, session.members.size)
        assertEquals(1, er.edges.size)
        assertEquals("||", er.edges[0].fromMultiplicity)
        assertEquals("o{", er.edges[0].toMultiplicity)
        assertEquals("owns", er.edges[0].label)

        val classSrc = """
            classDiagram
                class Transport {
                    <<interface>>
                    +connect() Unit
                    +send(msg: String) Unit
                }
                class WsTransport {
                    -url: String
                    +connect() Unit
                }
                Transport <|.. WsTransport : implements
        """.trimIndent()

        val cls = assertIs<MermaidDiagram.Graph>(parseMermaidDiagram(classSrc))
        assertEquals(MermaidDiagramKind.CLASS, cls.kind)
        val transport = cls.nodes.first { it.id == "Transport" }
        assertEquals("<<interface>>", transport.subtitle)
        assertEquals(2, transport.members.size)
        assertEquals(1, cls.edges.size)
        assertEquals(MermaidEdgeStyle.DASHED_ARROW, cls.edges[0].style)
        assertTrue(cls.edges[0].reverseArrow)
        assertEquals("implements", cls.edges[0].label)

        val erLayout = layoutGraphDiagram(er)
        val clsLayout = layoutGraphDiagram(cls)
        assertTrue(erLayout.width > 120f && erLayout.height > 120f)
        assertTrue(clsLayout.width > 120f && clsLayout.height > 120f)
    }

    @Test
    fun `parses pie chart entries, title, showData flag, and normalizes fractions accurately`() {
        val src = """
            pie showData
                title 代码语言占比
                "Kotlin" : 60
                "TypeScript" : 30
                "Swift" : 10
        """.trimIndent()

        val pie = assertIs<MermaidDiagram.Pie>(parseMermaidDiagram(src))
        assertEquals(MermaidDiagramKind.PIE, pie.kind)
        assertTrue(pie.showData)
        assertEquals("代码语言占比", pie.title)
        assertEquals(3, pie.slices.size)
        assertTrue(abs(pie.slices[0].fraction - 0.6) < 1e-6)
        assertTrue(abs(pie.slices[1].fraction - 0.3) < 1e-6)
        assertTrue(abs(pie.slices[2].fraction - 0.1) < 1e-6)
        assertEquals("60%", formatMermaidPercent(pie.slices[0].fraction))
        assertEquals("33.3%", formatMermaidPercent(1.0 / 3.0))
    }

    @Test
    fun `handles partial streaming mermaid text and unsupported syntax gracefully without throwing`() {
        // Streaming incomplete flowchart (header only) -> Unsupported fallback until first node arrives
        assertIs<MermaidDiagram.Unsupported>(parseMermaidDiagram("flowchart TD"))
        assertIs<MermaidDiagram.Unsupported>(parseMermaidDiagram(""))

        // Streaming half-written edge & unclosed bracket & unclosed subgraph -> still renders parsed nodes safely
        val partialFlow = """
            flowchart TD
                subgraph sg1["Sync
                    A[Start] --> B[In Progress
                    B -->
        """.trimIndent()
        val partialParsed = assertIs<MermaidDiagram.Graph>(parseMermaidDiagram(partialFlow))
        assertTrue(partialParsed.nodes.any { it.id == "A" })
        assertTrue(partialParsed.nodes.any { it.id == "B" })
        val partialLayout = layoutGraphDiagram(partialParsed)
        assertTrue(partialLayout.width > 0f && partialLayout.height > 0f)

        // Unsupported diagram type (e.g., gantt / timeline) -> Unsupported fallback
        assertIs<MermaidDiagram.Unsupported>(
            parseMermaidDiagram(
                """
                gantt
                    title Release Plan
                    section Core
                    Build :a1, 2026-01-01, 5d
                """.trimIndent(),
            ),
        )

        // Markdown fence parser still produces MdBlock.CodeFence(lang = "mermaid", ...)
        val md = """
            ```mermaid
            flowchart LR
                A --> B
            ```
        """.trimIndent()
        val blocks = parseMarkdownBlocks(md)
        assertEquals(1, blocks.size)
        val fence = assertIs<MdBlock.CodeFence>(blocks[0])
        assertEquals("mermaid", fence.lang)
    }
}
