package spw.cue

import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.WorkshopApi

internal object NativeApiReport {
    fun describe(host: String): String = buildString {
        appendLine("SPW 宿主版本  $host")
        appendLine("检查范围  宿主加载的公开 Workshop API JVM 签名")
        appendLine("LIBRARY_WRITE 权限标识  " +
            if (PluginPermission::class.java.enumConstants.any { it.name == "LIBRARY_WRITE" }) "存在" else "未发现")
        appendLine("权限标识与虚拟曲目注册接口分别验证。")
        appendLine()
        for (type in listOf(WorkshopApi::class.java, WorkshopApi.Library::class.java, WorkshopApi.Playback::class.java)) {
            appendLine(type.simpleName)
            type.methods.asSequence()
                .filter { !it.isSynthetic && !it.isBridge }
                .map { method ->
                    val parameters = method.parameterTypes.joinToString(", ") { it.simpleName }
                    "  ${method.name}($parameters) → ${method.returnType.simpleName}"
                }
                .sorted()
                .forEach { appendLine(it) }
            appendLine()
        }
    }
}
