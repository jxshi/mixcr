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

import com.milaboratory.cli.POverridesBuilderOps
import com.milaboratory.cli.ParamsResolver
import com.milaboratory.mixcr.presets.MiXCRParamsBundle
import com.milaboratory.mixcr.presets.MiXCRParamsSpec
import com.milaboratory.mixcr.presets.Presets
import com.milaboratory.util.K_YAML_OM
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
import java.nio.file.Path

@Command(
    description = ["Export all preset to directory"],
    hidden = true
)
class CommandExportAllPresets : Runnable, MiXCRPresetAwareCommand<Unit> {
    @Parameters(index = "0", arity = "1")
    lateinit var outputDir: Path

    override fun run() {
        outputDir.toFile().deleteRecursively()
        outputDir.toFile().mkdirs()
        val analyzePresetsDir = outputDir.resolve("analyze")
        analyzePresetsDir.toFile().mkdirs()
        Presets.nonAbstractPresetNames
            .filter { !it.contains("-legacy-v") }
            .forEach { presetName ->
                val (bundle, _) = paramsResolver.resolve(
                    MiXCRParamsSpec(presetName),
                    printParameters = false,
                    validate = false
                )
                val of = analyzePresetsDir.resolve("$presetName.yaml")
                K_YAML_OM.writeValue(of.toFile(), bundle)
            }
    }

    override val paramsResolver: ParamsResolver<MiXCRParamsBundle, Unit>
        get() = object : MiXCRParamsResolver<Unit>(MiXCRParamsBundle::exportPreset) {
            override fun POverridesBuilderOps<Unit>.paramsOverrides() {
            }
        }
}
