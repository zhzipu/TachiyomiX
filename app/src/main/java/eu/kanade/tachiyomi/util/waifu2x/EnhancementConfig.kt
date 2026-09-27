package eu.kanade.tachiyomi.util.waifu2x

import eu.kanade.tachiyomi.modelpack.ModelPackModel
import eu.kanade.tachiyomi.modelpack.ModelPackStyle

/**
 * 由模型描述符推导实际推理参数的规则集合。
 *
 * 这里只做「描述符 + 用户偏好 → 实际参数」的换算，不保存任何模型目录信息：
 * 哪些模型存在、支持哪些倍率/降噪/精度、是否支持 NPU，全部来自模型包描述符。
 */
object EnhancementConfig {

    /** 精度模式：0=FP16、1=FP32、2=INT8、3=BF16（与原生层一致）。 */
    const val PRECISION_FP16 = 0
    const val PRECISION_INT8 = 2

    fun styleOf(model: ModelPackModel, style: Int): ModelPackStyle? =
        model.styles.firstOrNull { it.value == style }

    fun defaultStyle(model: ModelPackModel): Int =
        model.defaultStyle ?: model.styles.firstOrNull()?.value ?: 0

    /** 允许的放大倍率，风格可以覆盖模型级取值。 */
    fun allowedScales(model: ModelPackModel, style: Int): List<Int> =
        styleOf(model, style)?.scales?.takeIf { it.isNotEmpty() } ?: model.scales

    /** 模型文件目录：风格自带目录时优先使用（例如 Real-ESRGAN 的 photo 风格）。 */
    fun assetPath(model: ModelPackModel, style: Int): String =
        styleOf(model, style)?.assetPath?.takeIf { it.isNotBlank() } ?: model.assetPath

    /** 底层模型自身的倍率（photo 风格用 4x 模型输出 2x）。 */
    fun modelScale(model: ModelPackModel, style: Int, outputScale: Int): Int =
        styleOf(model, style)?.modelScale ?: outputScale

    /** 把用户存的倍率收敛到描述符允许的集合。 */
    fun effectiveScale(model: ModelPackModel, requestedScale: Int, style: Int): Int {
        val scales = allowedScales(model, style)
        if (scales.isEmpty()) return requestedScale
        if (requestedScale in scales) return requestedScale
        return model.defaults.scale?.takeIf { it in scales } ?: scales.first()
    }

    /** 把用户存的降噪档位收敛到描述符允许的集合；模型没有降噪选项时返回 0。 */
    fun denoiseLevel(model: ModelPackModel, requested: Int): Int {
        val levels = model.denoiseLevels
        if (levels.isEmpty()) return 0
        if (requested in levels) return requested
        return model.defaults.denoise?.takeIf { it in levels } ?: levels.first()
    }

    /** 该模型在当前倍率下是否有 NPU 实现。 */
    fun supportsNpu(model: ModelPackModel, scale: Int): Boolean {
        if (!model.supportsNpu) return false
        val scales = model.npuScales.takeIf { it.isNotEmpty() } ?: model.scales
        return scales.isEmpty() || scale in scales
    }

    /** 允许的精度模式；NPU 通路只支持 FP16 与 INT8。 */
    fun allowedPrecisions(model: ModelPackModel, useNpu: Boolean): List<Int> {
        val precisions = model.precisions.ifEmpty { listOf(PRECISION_FP16, 1, PRECISION_INT8, 3) }
        return if (useNpu) precisions.filter { it == PRECISION_FP16 || it == PRECISION_INT8 } else precisions
    }

    /** 解析实际后端：请求 NPU 但设备/模型/倍率不支持时回退 Vulkan。 */
    fun resolveBackend(model: ModelPackModel, requestedBackend: Int, scale: Int): Int {
        return if (
            requestedBackend == Waifu2x.PROCESSING_BACKEND_QUALCOMM_NPU &&
            supportsNpu(model, scale) &&
            Waifu2x.isQualcommNpuAvailable()
        ) {
            Waifu2x.PROCESSING_BACKEND_QUALCOMM_NPU
        } else {
            Waifu2x.PROCESSING_BACKEND_VULKAN
        }
    }

    /** 解析实际精度：NPU 通路下只有 INT8 生效，其余归一为 FP16。 */
    fun resolvePrecision(model: ModelPackModel, requestedPrecision: Int, backend: Int, scale: Int): Int {
        val precision = requestedPrecision.coerceIn(0, 3)
        if (resolveBackend(model, backend, scale) != Waifu2x.PROCESSING_BACKEND_QUALCOMM_NPU) {
            return precision
        }
        return if (supportsNpu(model, scale) && precision == PRECISION_INT8) PRECISION_INT8 else PRECISION_FP16
    }
}
