/*
 * Copyright (c) 2014-2024, MiLaboratories Inc. All Rights Reserved
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
import com.milaboratory.app.logger
import com.milaboratory.cli.POverridesBuilderOps
import com.milaboratory.mixcr.basictypes.Clone
import com.milaboratory.mixcr.basictypes.CloneSet
import com.milaboratory.mixcr.basictypes.CloneSetIO
import com.milaboratory.mixcr.basictypes.IOUtil
import com.milaboratory.mixcr.basictypes.MiXCRFileInfo
import com.milaboratory.mixcr.basictypes.tag.TagCountAggregator
import com.milaboratory.mixcr.basictypes.tag.TagType
import com.milaboratory.mixcr.basictypes.tag.TagsInfo
import com.milaboratory.mixcr.cli.CommandExportCloneGroupsParams.SortChainsBy
import com.milaboratory.mixcr.cli.CommonDescriptions.DEFAULT_VALUE_FROM_PRESET
import com.milaboratory.mixcr.clonegrouping.CellType
import com.milaboratory.mixcr.export.CloneGroup
import com.milaboratory.mixcr.export.CloneGroupFieldsExtractorsFactory
import com.milaboratory.mixcr.export.InfoWriter
import com.milaboratory.mixcr.export.MetaForExport
import com.milaboratory.mixcr.export.RowMetaForExport
import com.milaboratory.mixcr.presets.AnalyzeCommandDescriptor
import com.milaboratory.mixcr.presets.ExportMixins
import com.milaboratory.mixcr.presets.MiXCRParamsBundle
import com.milaboratory.mixcr.util.SubstitutionHelper
import com.milaboratory.util.asOutputPortWithProgress
import io.repseq.core.GeneFeature
import io.repseq.core.GeneFeature.CDR3
import io.repseq.core.VDJCLibraryRegistry
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Model
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.nio.file.Path
import java.nio.file.Paths

object CommandExportCloneGroups {
    const val COMMAND_NAME = AnalyzeCommandDescriptor.exportCloneGroups.name

    private fun CommandExportCloneGroupsParams.test(
        clone: Clone,
        assemblingFeatures: Array<GeneFeature>
    ): Boolean {
        if (filterOutOfFrames)
            if (clone.isOutOfFrameOrAbsent(CDR3)) return false

        if (filterStops)
            for (assemblingFeature in assemblingFeatures)
                if (clone.containsStopsOrAbsent(assemblingFeature)) return false

        return true
    }

    abstract class CmdBase : MiXCRCommandWithOutputs(), MiXCRPresetAwareCommand<CommandExportCloneGroupsParams> {
        @Option(
            description = [
                "Exclude clones with out-of-frame clone sequences (fractions will be recalculated).",
                DEFAULT_VALUE_FROM_PRESET
            ],
            names = ["-o", "--filter-out-of-frames"],
            order = OptionsOrder.main + 10_200
        )
        private var filterOutOfFrames = false

        @Option(
            description = [
                "Exclude sequences containing stop codons (fractions will be recalculated).",
                DEFAULT_VALUE_FROM_PRESET
            ],
            names = ["-t", "--filter-stops"],
            order = OptionsOrder.main + 10_300
        )
        private var filterStops = false

        @Option(
            description = [
                "Exclude groups containing only one clone (fractions will be recalculated).",
                DEFAULT_VALUE_FROM_PRESET
            ],
            names = ["--filter-groups-with-one-chain"],
            order = OptionsOrder.main + 10_400
        )
        private var filterOutGroupsWithOneClone = false

        @Mixin
        lateinit var exportDefaults: ExportDefaultOptions

        @Mixin
        lateinit var resetPreset: ResetPresetOptions


        override val paramsResolver =
            object : MiXCRParamsResolver<CommandExportCloneGroupsParams>(MiXCRParamsBundle::exportCloneGroups) {
                override fun POverridesBuilderOps<CommandExportCloneGroupsParams>.paramsOverrides() {
                    CommandExportCloneGroupsParams::filterOutOfFrames setIfTrue filterOutOfFrames
                    CommandExportCloneGroupsParams::filterStops setIfTrue filterStops
                    CommandExportCloneGroupsParams::filterOutGroupsWithOneClone setIfTrue filterOutGroupsWithOneClone
                    CommandExportCloneGroupsParams::noHeader setIfTrue exportDefaults.noHeader
                    CommandExportCloneGroupsParams::fields updateBy exportDefaults
                }
            }
    }

    @Command(
        description = ["Export clone groups into tab delimited file. Data should be processed by `${AnalyzeCommandDescriptor.assembleCells.name}`"]
    )
    class Cmd : CmdBase() {
        @Parameters(
            description = ["Path to input file with clones"],
            paramLabel = "data.(clns|clna)",
            index = "0"
        )
        lateinit var inputFile: Path

        @set:Parameters(
            description = ["Path where to write export table."],
            paramLabel = "table.tsv",
            index = "1",
            arity = "0..1"
        )
        var outputFile: Path? = null
            set(value) {
                ValidationException.requireFileType(value, InputFileType.TSV)
                field = value
            }

        @Mixin
        lateinit var exportMixins: ExportMiXCRMixins.CommandSpecific.ExportCloneGroups

        override val inputFiles
            get() = listOf(inputFile)

        override val outputFiles
            get() = listOfNotNull(outputFile)

        override fun validate() {
            ValidationException.requireFileType(inputFile, InputFileType.CLNX)
        }

        override fun initialize() {
            if (outputFile == null)
                logger.redirectSysOutToSysErr()
        }

        override fun run1() {
            val fileInfo = IOUtil.extractFileInfo(inputFile)
            val initialSet = CloneSetIO.read(inputFile, VDJCLibraryRegistry.getDefault())
            val tagsInfo = initialSet.cloneSetInfo.tagsInfo
            ValidationException.require(initialSet.header.calculatedCloneGroups) {
                "Groups are not calculated. Run `${CommandGroupClones.COMMAND_NAME}` for grouping clones."
            }
            val (_, params) = paramsResolver.resolve(
                resetPreset.overridePreset(fileInfo.header.paramsSpec).addMixins(exportMixins.mixins)
            )

            val recalculatedClones = filterClones(initialSet, params, fileInfo)

            var groups = cloneGroups(recalculatedClones, params.sortChainsBy, tagsInfo)
            val cellTypesToExport: List<CellType>
            val splitBy: List<Pair<String, List<CellType>>>

            if (params.types.isEmpty()) {
                cellTypesToExport = CellType.values().toList()
                splitBy = listOf(
                    "IG" to listOf(CellType.IGHL, CellType.IGHK),
                    "TRAB" to listOf(CellType.TRAB),
                    "TRGD" to listOf(CellType.TRGD)
                )
            } else {
                cellTypesToExport = params.types.distinct()
                groups = groups.filter { group ->
                    cellTypesToExport.any { cellType -> group.clonePairs.keys.all { chain -> cellType.hasChain(chain) } }
                }
                splitBy = cellTypesToExport.map { it.name to listOf(it) }
            }

            val headerForExport = MetaForExport(fileInfo)

            if (outputFile != null && params.splitFilesByCellType && splitBy.size > 1 && groups.isNotEmpty()) {
                val sFileName = outputFile!!.let { of ->
                    SubstitutionHelper.parseFileName(of.toString(), 1)
                }

                val keyForMixed = "mixed"
                val resultFiles = splitBy.toMap() + (keyForMixed to CellType.values().toList())
                val splitGroups = groups.groupBy { group ->
                    val chainsForGroup = group.clonePairs.keys
                    splitBy.firstOrNull { (_, cellTypes) ->
                        chainsForGroup.all { chain -> cellTypes.any { cellType -> cellType.hasChain(chain) } }
                    }?.first ?: keyForMixed
                }
                splitGroups.forEach { (fileName, forExport) ->
                    val fieldExtractors = CloneGroupFieldsExtractorsFactory(
                        resultFiles[fileName]!!,
                        params.showSecondaryChains
                    ).createExtractors(params.fields, headerForExport)

                    val rowMetaForExport = RowMetaForExport(
                        tagsInfo,
                        headerForExport,
                        exportDefaults.notCoveredAsEmpty
                    )
                    val substitutionValues = SubstitutionHelper.SubstitutionValues()
                    substitutionValues.add(fileName, "1", "cellType")
                    InfoWriter.create(
                        Paths.get(sFileName.render(substitutionValues)),
                        fieldExtractors,
                        !params.noHeader
                    ) { rowMetaForExport }.use { writer ->
                        forExport.asOutputPortWithProgress()
                            .reportProgress("Exporting $fileName clone groups")
                            .forEach { writer.put(it) }
                    }
                }
            } else {
                val fieldExtractors = CloneGroupFieldsExtractorsFactory(
                    cellTypesToExport,
                    params.showSecondaryChains
                ).createExtractors(params.fields, headerForExport)

                val rowMetaForExport = RowMetaForExport(
                    tagsInfo,
                    headerForExport,
                    exportDefaults.notCoveredAsEmpty
                )
                InfoWriter.create(
                    outputFile,
                    fieldExtractors,
                    !params.noHeader
                ) { rowMetaForExport }.use { writer ->
                    groups.asOutputPortWithProgress()
                        .reportProgress("Exporting clone groups")
                        .forEach { writer.put(it) }
                }
            }
        }

        private fun cloneGroups(
            recalculatedClones: CloneSet,
            sortChainsBy: SortChainsBy,
            tagsInfo: TagsInfo
        ): List<CloneGroup> = recalculatedClones
            .filter { it.groupIsDefined }
            .groupBy { it.group!! }
            .map { (groupId, clones) ->
                val clonesGroupedByChains = clones.groupBy { it.chains }
                CloneGroup(
                    groupId = groupId,
                    clonePairs = clonesGroupedByChains.mapValues { (_, clonesWithChain) ->
                        val effectiveSortChainsBy = when (sortChainsBy) {
                            SortChainsBy.Read -> SortChainsBy.Read
                            SortChainsBy.Molecule -> {
                                ValidationException.require(tagsInfo.hasTagsWithType(TagType.Molecule)) {
                                    "Can't sort clones by UMI. Try `${ExportMixins.ExportCloneGroupsSortChainsBy.CMD_OPTION} ${SortChainsBy.Read}"
                                }
                                SortChainsBy.Molecule
                            }

                            SortChainsBy.Auto -> when {
                                tagsInfo.hasTagsWithType(TagType.Molecule) -> SortChainsBy.Molecule
                                else -> SortChainsBy.Read
                            }
                        }

                        val sorted = when (effectiveSortChainsBy) {
                            SortChainsBy.Read -> clonesWithChain.sortedByDescending { clone -> clone.count }
                            SortChainsBy.Molecule -> {
                                val tag = tagsInfo.allTagsOfType(TagType.Molecule).maxBy { it.index }
                                clonesWithChain.sortedByDescending { clone -> clone.getTagDiversity(tag) }
                            }

                            else -> throw IllegalArgumentException()
                        }
                        CloneGroup.ClonePair(
                            sorted.first(),
                            sorted.getOrNull(1)
                        )
                    },
                    cloneCount = clonesGroupedByChains.mapValues { it.value.size },
                    totalReadsCount = clones.sumOf { it.count },
                    totalTagsCount = TagCountAggregator().also { aggregator ->
                        clones.forEach { clone -> aggregator.add(clone.tagCount) }
                    }.createAndDestroy()
                )
            }
            .sortedBy { it.groupId }

        private fun filterClones(
            initialSet: CloneSet,
            params: CommandExportCloneGroupsParams,
            fileInfo: MiXCRFileInfo
        ): CloneSet {
            val assemblingFeatures = fileInfo.header.assemblerParameters!!.assemblingFeatures
            val filteredClones = initialSet
                .filter { params.test(it, assemblingFeatures) }
                .groupBy { it.group!! }
                .values
                .filter { clones ->
                    !params.filterOutGroupsWithOneClone || clones.size > 1
                }
                .flatten()
            return CloneSet.Builder(filteredClones, initialSet.usedGenes, initialSet.header)
                .alreadyOrdered(initialSet.ordering)
                .calculateTotalCounts()
                .recalculateRanks()
                .build()
        }
    }

    @JvmStatic
    fun mkSpec(): Model.CommandSpec {
        val cmd = Cmd()
        val spec = Model.CommandSpec.forAnnotatedObject(cmd)
        cmd.spec = spec // inject spec manually
        CloneGroupFieldsExtractorsFactory.forAllChains
            .addOptionsToSpec(cmd.exportDefaults.addedFields, spec)
        return spec
    }
}
