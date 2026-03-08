package com.ballot.system

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*

// ══════════════════════════════════════════════════════════════
//  DATA MODELS
// ══════════════════════════════════════════════════════════════

data class Candidate(
    val name: String,
    val position: String
)

data class BallotConfig(
    val title: String,
    val date: String,                        // ISO-8601 e.g. "2025-11-10"
    val candidates: List<Candidate>,
    val copies: Int = 3                      // How many stub-sections per A4 page
)

data class BallotResponse(
    val html: String,
    val ballotId: String,
    val generatedAt: String
)

// ══════════════════════════════════════════════════════════════
//  FIDUCIAL MARKER GENERATOR  (ArUco-style simplified SVG)
// ══════════════════════════════════════════════════════════════

object FiducialGenerator {

    private val patterns = listOf(
        arrayOf(intArrayOf(1,0,1), intArrayOf(0,1,0), intArrayOf(1,0,1)),
        arrayOf(intArrayOf(1,1,0), intArrayOf(0,0,1), intArrayOf(1,1,0)),
        arrayOf(intArrayOf(0,1,1), intArrayOf(1,0,0), intArrayOf(0,1,1)),
        arrayOf(intArrayOf(1,0,0), intArrayOf(0,1,1), intArrayOf(1,0,0))
    )

    fun svg(id: Int): String {
        val p        = patterns[id % patterns.size]
        val cell     = 18
        val border   = 9
        val total    = cell * 3 + border * 2

        val cells = buildString {
            for (r in 0..2) for (c in 0..2) {
                if (p[r][c] == 1) {
                    append("""<rect x="${border + c * cell}" y="${border + r * cell}" """)
                    append("""width="$cell" height="$cell" fill="#000"/>""")
                }
            }
        }

        return """
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $total $total">
          <rect width="$total" height="$total" fill="white" stroke="black" stroke-width="3"/>
          <rect x="3" y="3" width="${total-6}" height="${total-6}" fill="white"/>
          $cells
          <rect x="$border" y="$border" width="6" height="6" fill="#000"/>
          <rect x="${total-border-6}" y="$border" width="6" height="6" fill="#000"/>
          <rect x="$border" y="${total-border-6}" width="6" height="6" fill="#000"/>
          <rect x="${total-border-6}" y="${total-border-6}" width="6" height="6" fill="#000"/>
        </svg>""".trimIndent()
    }
}

// ══════════════════════════════════════════════════════════════
//  BALLOT RENDERER
// ══════════════════════════════════════════════════════════════

object BallotRenderer {

    fun render(config: BallotConfig, ballotId: String): String {
        val formattedDate = runCatching {
            LocalDate.parse(config.date)
                .format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH))
        }.getOrDefault(config.date)

        // Group candidates by position, preserving insertion order
        val byPosition: Map<String, List<String>> = config.candidates
            .groupBy({ it.position }, { it.name })

        val sectionsHtml = (1..config.copies).joinToString("\n") { sectionNum ->
            buildSection(config.title, formattedDate, byPosition, sectionNum, config.copies, ballotId)
        }

        return buildFullPage(sectionsHtml)
    }

    private fun buildSection(
        title: String,
        date: String,
        byPosition: Map<String, List<String>>,
        sectionNum: Int,
        totalSections: Int,
        ballotId: String
    ): String {
        val sId = sectionNum.toString().padStart(2, '0')
        val positions = byPosition.entries.toList()

        // Two-column split
        val leftPositions  = positions.filterIndexed { i, _ -> i % 2 == 0 }
        val rightPositions = positions.filterIndexed { i, _ -> i % 2 != 0 }

        fun colHtml(entries: List<Map.Entry<String, List<String>>>) = entries.joinToString("") { (pos, names) ->
            val candidateRows = names.joinToString("") { name ->
                """<div class="candidate-row"><div class="vote-bubble"></div><div class="candidate-name">$name</div></div>"""
            }
            """<div class="position-block"><div class="position-title">$pos</div>$candidateRows</div>"""
        }

        val positionsGridHtml = if (byPosition.isEmpty()) {
            """<div class="empty-ballot">No candidates configured.</div>"""
        } else {
            """<div class="positions-grid">${colHtml(leftPositions)}${colHtml(rightPositions)}</div>"""
        }

        // Fiducial IDs offset per section
        val fOffset = (sectionNum - 1) * 4

        return """
        <div class="ballot-section">
          <div class="fiducial tl">${FiducialGenerator.svg(fOffset + 0)}</div>
          <div class="fiducial tr">${FiducialGenerator.svg(fOffset + 1)}</div>
          <div class="fiducial bl">${FiducialGenerator.svg(fOffset + 2)}</div>
          <div class="fiducial br">${FiducialGenerator.svg(fOffset + 3)}</div>

          <div class="ballot-header">
            <div class="ballot-title">$title</div>
            <div class="ballot-rule"><div class="ballot-rule-inner"></div></div>
            <div class="ballot-subtitle">Official Ballot · $date</div>
          </div>

          <div class="ballot-meta">
            <span>Section $sId / ${totalSections.toString().padStart(2, '0')}</span>
            <span>Mark inside the ○ bubble for your chosen candidate</span>
            <span>Ballot ID: $ballotId-$sId</span>
          </div>

          $positionsGridHtml

          <div class="section-stamp">§$sId · ${title.take(4).uppercase()}-${date.takeLast(4)}</div>
        </div>
        """.trimIndent()
    }

    private fun buildFullPage(sectionsHtml: String): String = """
    <!DOCTYPE html>
    <html lang="en">
    <head>
    <meta charset="UTF-8">
    <title>Ballot</title>
    <style>
      @import url('https://fonts.googleapis.com/css2?family=DM+Mono:wght@400;500&family=Playfair+Display:wght@700;900&family=Source+Serif+4:wght@300;400;600&display=swap');
      :root {
        --ink:#1a1a1a; --paper:#f5f0e8; --accent:#b5252a; --light-rule:#ccc5b5;
        --ballot-width:210mm; --ballot-height:297mm; --section-height:calc(297mm/3);
      }
      *{box-sizing:border-box;margin:0;padding:0;}
      body{background:#2c2c2c;display:flex;justify-content:center;padding:40px;}
      .ballot-page{width:var(--ballot-width);height:var(--ballot-height);background:var(--paper);
        position:relative;box-shadow:0 8px 48px rgba(0,0,0,.6);overflow:hidden;}
      .ballot-section{width:100%;height:var(--section-height);border-bottom:2px dashed #aaa5;
        position:relative;display:flex;flex-direction:column;padding:10mm 12mm;}
      .ballot-section:last-child{border-bottom:none;}
      .fiducial{position:absolute;width:12mm;height:12mm;}
      .fiducial svg{width:100%;height:100%;}
      .fiducial.tl{top:4mm;left:4mm;} .fiducial.tr{top:4mm;right:4mm;}
      .fiducial.bl{bottom:4mm;left:4mm;} .fiducial.br{bottom:4mm;right:4mm;}
      .ballot-header{text-align:center;padding:0 18mm;margin-bottom:3mm;}
      .ballot-title{font-family:'Playfair Display',serif;font-size:15pt;font-weight:900;
        color:var(--ink);letter-spacing:.12em;text-transform:uppercase;}
      .ballot-subtitle{font-family:'DM Mono',monospace;font-size:6.5pt;color:#555;
        letter-spacing:.12em;text-transform:uppercase;margin-top:1.5mm;}
      .ballot-rule{display:flex;align-items:center;gap:3mm;margin:2mm 0;}
      .ballot-rule::before,.ballot-rule::after{content:'';flex:1;height:1.5px;background:var(--ink);}
      .ballot-rule-inner{width:3mm;height:3mm;background:var(--accent);transform:rotate(45deg);}
      .ballot-meta{display:flex;justify-content:space-between;font-family:'DM Mono',monospace;
        font-size:5.5pt;color:#888;margin-bottom:3mm;padding:0 2mm;}
      .positions-grid{display:grid;grid-template-columns:1fr 1fr;gap:4mm 6mm;flex:1;}
      .position-block{border:1px solid var(--light-rule);padding:2.5mm 3mm;background:rgba(255,255,255,.35);}
      .position-title{font-family:'DM Mono',monospace;font-size:6pt;color:var(--accent);
        text-transform:uppercase;letter-spacing:.12em;border-bottom:1px solid var(--light-rule);
        padding-bottom:1.5mm;margin-bottom:1.5mm;}
      .candidate-row{display:flex;align-items:center;gap:2.5mm;padding:1mm 0;
        border-bottom:.5px solid #e0d8cc;}
      .candidate-row:last-child{border-bottom:none;}
      .vote-bubble{width:5mm;height:5mm;border:1.5px solid var(--ink);border-radius:50%;flex-shrink:0;background:white;}
      .candidate-name{font-family:'Source Serif 4',serif;font-size:7.5pt;color:var(--ink);}
      .section-stamp{position:absolute;bottom:5mm;right:16mm;font-family:'DM Mono',monospace;
        font-size:5pt;color:#999;letter-spacing:.1em;text-transform:uppercase;}
      .empty-ballot{flex:1;display:flex;align-items:center;justify-content:center;
        font-family:'DM Mono',monospace;font-size:7pt;color:#bbb;text-align:center;padding:0 18mm;}
      @media print{body{background:white;padding:0;}.ballot-page{box-shadow:none;}}
    </style>
    </head>
    <body>
    <div class="ballot-page">$sectionsHtml</div>
    </body>
    </html>
    """.trimIndent()
}

// ══════════════════════════════════════════════════════════════
//  REST CONTROLLER
// ══════════════════════════════════════════════════════════════

@RestController
@RequestMapping("/api/ballot")
@CrossOrigin(origins = ["*"])
class BallotController {

    /**
     * POST /api/ballot/preview
     * Returns rendered HTML ballot for browser preview.
     */
    @PostMapping("/preview", produces = [MediaType.TEXT_HTML_VALUE])
    fun preview(@RequestBody config: BallotConfig): ResponseEntity<String> {
        val ballotId = generateBallotId()
        val html     = BallotRenderer.render(config, ballotId)
        return ResponseEntity.ok(html)
    }

    /**
     * POST /api/ballot/generate
     * Returns JSON with rendered HTML + metadata.
     */
    @PostMapping("/generate", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun generate(@RequestBody config: BallotConfig): ResponseEntity<BallotResponse> {
        val ballotId = generateBallotId()
        val html     = BallotRenderer.render(config, ballotId)
        return ResponseEntity.ok(
            BallotResponse(
                html        = html,
                ballotId    = ballotId,
                generatedAt = LocalDate.now().toString()
            )
        )
    }

    /**
     * GET /api/ballot/health
     */
    @GetMapping("/health")
    fun health() = mapOf("status" to "ok", "service" to "ballot-generator")

    private fun generateBallotId(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..8).map { chars.random() }.joinToString("") +
               "-" + System.currentTimeMillis().toString().takeLast(4)
    }
}

// ══════════════════════════════════════════════════════════════
//  SPRING BOOT ENTRY POINT
// ══════════════════════════════════════════════════════════════

@SpringBootApplication
class BallotSystemApplication

fun main(args: Array<String>) {
    runApplication<BallotSystemApplication>(*args)
}

/*
 * ── BUILD.GRADLE.KTS (dependencies) ──────────────────────────
 *
 * dependencies {
 *     implementation("org.springframework.boot:spring-boot-starter-web")
 *     implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
 *     implementation("org.jetbrains.kotlin:kotlin-reflect")
 *
 *     // Optional: PDF export via Flying Saucer + OpenPDF
 *     // implementation("org.xhtmlrenderer:flying-saucer-pdf-openpdf:9.4.0")
 *     // implementation("com.github.librepdf:openpdf:1.3.43")
 *
 *     testImplementation("org.springframework.boot:spring-boot-starter-test")
 * }
 *
 * ── EXAMPLE API CALL (curl) ───────────────────────────────────
 *
 * curl -X POST http://localhost:8080/api/ballot/preview \
 *   -H "Content-Type: application/json" \
 *   -d '{
 *     "title": "General Election 2025",
 *     "date":  "2025-11-10",
 *     "copies": 3,
 *     "candidates": [
 *       { "position": "President",      "name": "Alejandra Reyes"   },
 *       { "position": "President",      "name": "Marco Villanueva"  },
 *       { "position": "Vice President", "name": "Sofia Cruz"        },
 *       { "position": "Treasurer",      "name": "Jerome Tan"        }
 *     ]
 *   }'
 */
