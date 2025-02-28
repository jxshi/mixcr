/*
 * Copyright (c) 2014-2023, MiLaboratories Inc. All Rights Reserved
 *
 * Before downloading or accessing the software, please read carefully the
 * License Agreement available at:
 * https://github.com/milaboratory/mixcr/blob/develop/LICENSE
 *
 * By downloading or accessing the software, you accept and agree to be bound
 * by the terms of the License Agreement. If you do not want to agree to the terms
 * of the Licensing Agreement, you must not download or access the software.
 */
package com.milaboratory.mixcr.cli

import cc.redberry.pipe.util.forEach
import com.milaboratory.app.InputFileType
import com.milaboratory.app.ValidationException
import com.milaboratory.mixcr.basictypes.Clone
import com.milaboratory.mixcr.basictypes.IOUtil
import com.milaboratory.mixcr.basictypes.IOUtil.extractFileInfo
import com.milaboratory.mixcr.basictypes.tag.TagType
import com.milaboratory.mixcr.cli.CommonDescriptions.Labels
import com.milaboratory.mixcr.export.CloneFieldsExtractorsFactory
import com.milaboratory.mixcr.export.ExportFieldDescription
import com.milaboratory.mixcr.export.FieldExtractor
import com.milaboratory.mixcr.export.MetaForExport
import com.milaboratory.mixcr.export.RowMetaForExport
import com.milaboratory.mixcr.postanalysis.overlap.OverlapGroup
import com.milaboratory.mixcr.postanalysis.overlap.OverlapUtil
import com.milaboratory.mixcr.postanalysis.preproc.ChainsFilter
import com.milaboratory.mixcr.postanalysis.util.OverlapBrowser
import com.milaboratory.util.SmartProgressReporter
import io.repseq.core.Chains
import io.repseq.core.GeneFeature
import io.repseq.core.GeneType.Joining
import io.repseq.core.GeneType.Variable
import picocli.CommandLine.Command
import picocli.CommandLine.Help.Visibility.ALWAYS
import picocli.CommandLine.Model
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.FileWriter
import java.io.PrintWriter
import java.nio.file.Path
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.io.path.createDirectories

@Command(
    description = ["Build cloneset overlap and export into tab delimited file."],
)
class CommandExportOverlap : MiXCRCommandWithOutputs() {
    companion object {
        private const val inputsLabel = "cloneset.(clns|clna)..."

        private const val outputLabel = "output.tsv"

        @JvmStatic
        fun mkSpec(): Model.CommandSpec {
            val export = CommandExportOverlap()
            val spec = Model.CommandSpec.forAnnotatedObject(export)
                .addPositional(
                    Model.PositionalParamSpec.builder()
                        .index("0")
                        .required(false)
                        .arity("0..*")
                        .type(Path::class.java)
                        .paramLabel(inputsLabel)
                        .hideParamSyntax(true)
                        .description("Paths to input files")
                        .build()
                )
                .addPositional(
                    Model.PositionalParamSpec.builder()
                        .index("1")
                        .required(false)
                        .arity("0..*")
                        .type(Path::class.java)
                        .paramLabel(outputLabel)
                        .hideParamSyntax(true)
                        .description(
                            "Path template where to write output export tables.",
                            "For each `chain` will be generated table with path `{outputDir}/{outputFileName}.{chain}.tsv`"
                        )
                        .build()
                )

            export.spec = spec // inject spec manually
            CloneFieldsExtractorsFactory.addOptionsToSpec(export.addedFields, spec)
            return spec
        }
    }

    @Parameters(
        index = "0",
        arity = "2..*",
        paramLabel = "$inputsLabel $outputLabel",
        hideParamSyntax = true,
        // help is covered by mkCommandSpec
        hidden = true
    )
    var inOut: List<Path> = mutableListOf()

    @Option(
        description = ["Output overlap for specified chains only; if multiple chains are specified, results per each chains will be exported in separate files."],
        names = ["--chains"],
        split = ",",
        paramLabel = Labels.CHAIN,
        order = OptionsOrder.main + 10_100,
        completionCandidates = ChainsCandidates::class
    )
    var chains: Set<String>? = null

    @Option(
        description = [CommonDescriptions.OVERLAP_CRITERIA],
        names = ["--criteria"],
        showDefaultValue = ALWAYS,
        paramLabel = "<s>",
        order = OptionsOrder.main + 10_200
    )
    var overlapCriteria = "CDR3|AA|V|J"

    @Option(
        description = ["Filter out-of-frame sequences and clonotypes with stop-codons"],
        names = ["--only-productive"],
        order = OptionsOrder.main + 10_300
    )
    var onlyProductive = false

    @Option(
        description = ["Export not covered regions as empty text."],
        names = ["--not-covered-as-empty"],
        arity = "0",
        order = OptionsOrder.exportOptions + 400
    )
    var notCoveredAsEmpty: Boolean = false

    private val output get() = inOut.last()

    public override val inputFiles
        get() = inOut.subList(0, inOut.size - 1)

    override val outputFiles
        get() = listOf(output)

    private fun getOut(chains: Chains): Path {
        val fName = output.fileName.toString().replace("tsv", "${chains}.tsv")
        return output.toAbsolutePath().parent.resolve(fName)
    }

    override fun validate() {
        inputFiles.forEach { input ->
            ValidationException.requireFileType(input, InputFileType.CLNX)
        }
        ValidationException.requireFileType(output, InputFileType.TSV)
    }

    private val tagsInfo
        get() = inputFiles.map { IOUtil.extractHeader(it).tagsInfo }

    private fun tagLevel(tt: TagType) =
        if (tagsInfo.all { it.hasTagsWithType(tt) }) {
            val set = tagsInfo.map { it.getDepthFor(tt) }.toSet()
            if (set.size > 1)
                null
            else
                set.first()
        } else
            null

    var addedFields: MutableList<ExportFieldDescription> = mutableListOf()

    override fun run1() {
        val samples = inputFiles
        val chains = this.chains?.let { ChainsFilter.parseChainsList(it) }
        val criteria = OverlapUtil.parseCriteria(overlapCriteria)
        val extractors = mutableListOf<OverlapFieldExtractor>()
        extractors += ExtractorUnique(
            FieldExtractor(
                (if (criteria.isAA) "aaSeq" else "nSeq") + GeneFeature.encode(criteria.feature)
            ) { obj ->
                when {
                    criteria.isAA -> obj.getAAFeature(criteria.feature).toString()
                    else -> obj.getNFeature(criteria.feature).toString()
                }
            }
        )
        if (criteria.withV) {
            extractors += ExtractorUnique(
                FieldExtractor("vGene") {
                    it.getBestHit(Variable)!!.gene.name.name
                }
            )
        }
        if (criteria.withJ) {
            extractors += ExtractorUnique(
                FieldExtractor("jGene") {
                    it.getBestHit(Joining)!!.gene.name.name
                }
            )
        }
        extractors += NumberOfSamples()
        extractors += TotalCount()
        extractors += TotalFraction()

        tagLevel(TagType.Molecule)?.let { umiLevel ->
            extractors += TotalTagCount(umiLevel, "UMI")
            extractors += TotalTagFraction(umiLevel, "UMI")
        }

        tagLevel(TagType.Cell)?.let { cellLevel ->
            extractors += TotalTagCount(cellLevel, "CELL")
            extractors += TotalTagFraction(cellLevel, "CELL")
        }

        val fileInfo = extractFileInfo(samples[0])
        val headerForExport = MetaForExport(fileInfo)
        val fieldExtractors = CloneFieldsExtractorsFactory.createExtractors(addedFields, headerForExport)

        extractors += fieldExtractors.map { ExtractorPerSample(it) }

        val overlapBrowser = OverlapBrowser(onlyProductive)
        SmartProgressReporter.startProgressReport(overlapBrowser)

        val countsByChain = overlapBrowser.computeCountsByChain(samples)
        val chainsToWrite = chains?.intersect(countsByChain.keys) ?: countsByChain.keys
        val writers = chainsToWrite.associateWith { chain ->
            val out = getOut(chain)
            out.toAbsolutePath().parent.createDirectories()
            val writer = InfoWriter(out, samples, extractors)
            writer.writeHeader()
            writer
        }

        val overlap = OverlapUtil.overlap(samples.map { it.toString() }, { true }, criteria.ordering())
        val rowMetaForExport = RowMetaForExport(fileInfo.header.tagsInfo, headerForExport, notCoveredAsEmpty)
        overlap.mkElementsPort().use { port ->
            overlapBrowser.overlap(countsByChain, port).forEach { row ->
                for ((ch, cloneOverlapGroup) in row) {
                    if (chainsToWrite.contains(ch))
                        writers[ch]!!.writeRow(rowMetaForExport, cloneOverlapGroup)
                }
            }
        }
        writers.values.forEach { it.close() }
    }

    private class InfoWriter(
        out: Path,
        samples: List<Path>,
        private val extractors: List<OverlapFieldExtractor>
    ) : AutoCloseable {
        private val writer: PrintWriter = PrintWriter(FileWriter(out.toFile()))
        private val samplesNames = samples.map { sampleName -> removeExt(sampleName.toString()) }

        fun writeHeader() {
            writer.println(extractors
                .flatMap { fieldExtractor -> fieldExtractor.header(samplesNames) }
                .joinToString("\t")
            )
        }

        fun writeRow(header: RowMetaForExport, row: OverlapGroup<Clone>) {
            writer.println(extractors
                .flatMap { fieldExtractor -> fieldExtractor.values(header, row) }
                .joinToString("\t")
            )
        }

        override fun close() {
            writer.close()
        }

        companion object {
            private fun removeExt(sampleName: String): String = sampleName
                .replace(".clns", "")
                .replace(".clna", "")
        }
    }

    private interface OverlapFieldExtractor {
        fun header(samples: List<String>): List<String>
        fun values(header: RowMetaForExport, row: OverlapGroup<Clone>): List<String>
    }

    private class TotalCount : OverlapFieldExtractor {
        override fun header(samples: List<String>): List<String> =
            samples.map { sample -> "${sample}_readCountAggregated" }

        override fun values(header: RowMetaForExport, row: OverlapGroup<Clone>): List<String> =
            row.map { clones -> clones.sumOf { it.count }.toString() }
    }

    private class TotalFraction : OverlapFieldExtractor {
        override fun header(samples: List<String>): List<String> =
            samples.map { sample -> "${sample}_readFractionAggregated" }

        override fun values(header: RowMetaForExport, row: OverlapGroup<Clone>): List<String> =
            row.map { clones -> clones.sumOf { it.fraction }.toString() }
    }

    private class TotalTagCount(val level: Int, val tagName: String) : OverlapFieldExtractor {
        override fun header(samples: List<String>): List<String> =
            samples.map { sample -> "${sample}_unique${tagName}CountAggregated" }

        override fun values(header: RowMetaForExport, row: OverlapGroup<Clone>): List<String> =
            row.map { clones -> clones.sumOf { it.getTagDiversity(level) }.toString() }
    }

    private class TotalTagFraction(val level: Int, val tagName: String) : OverlapFieldExtractor {
        override fun header(samples: List<String>): List<String> =
            samples.map { sample -> "${sample}_unique${tagName}FractionAggregated" }

        override fun values(header: RowMetaForExport, row: OverlapGroup<Clone>): List<String> =
            row.map { clones -> clones.sumOf { it.getTagDiversityFraction(level) }.toString() }
    }

    private class NumberOfSamples : OverlapFieldExtractor {
        override fun header(samples: List<String>): List<String> = listOf("nSamples")

        override fun values(header: RowMetaForExport, row: OverlapGroup<Clone>): List<String> =
            listOf(row.count { clones -> clones.isNotEmpty() }.toString())
    }

    private class ExtractorUnique(
        private val extractor: FieldExtractor<Clone>
    ) : OverlapFieldExtractor {
        override fun header(samples: List<String>): List<String> = listOf(extractor.header)

        override fun values(header: RowMetaForExport, row: OverlapGroup<Clone>): List<String> =
            listOf(extractor.extractValue(header, row.first { clones -> clones.isNotEmpty() }.first()))
    }

    private class ExtractorPerSample(val extractor: FieldExtractor<Clone>) : OverlapFieldExtractor {
        override fun header(samples: List<String>): List<String> =
            samples.map { sample -> sample + "_" + extractor.header }

        override fun values(header: RowMetaForExport, row: OverlapGroup<Clone>): List<String> =
            row.map { clones ->
                clones.joinToString(",") { clone -> extractor.extractValue(header, clone) }
            }
    }
}
