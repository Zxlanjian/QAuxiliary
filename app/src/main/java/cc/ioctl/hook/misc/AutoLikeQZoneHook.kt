/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * 新增功能：QQ空间自动点赞（从 TimTool 移植）
 *
 * 原实现：TimTool/app/src/main/java/top/sacz/timtool/hook/item/qzone/AutoLikeQZone.kt
 *         @HookItem("辅助功能/QQ空间/在空间时自动点赞QQ空间")
 *
 * TIM 4.1.0 适配说明（classes21.dex，已用 baksmali 核对）：
 *   Lcom/qzone/reborn/feedx/presenter/bs;   （继承 com.qzone.reborn.feedx.presenter.e.a）
 *     protected C(Landroid/view/View;)V     // onViewCreated：绑定点攒/超赞/转发等控件
 *     private   N()V                        // doLike()：真正的点赞入口
 *
 *   N() 的 smali 关键片段（自带幂等保护，所以直接调不会重复点赞）：
 *     iget-object v0, p0, ...presenter/e/a;->g:Lcom/qzone/proxy/feedcomponent/model/BusinessFeedData;
 *     if-nez v0, :cond_5 / return-void                       // 没有 feed 数据直接退出
 *     invoke-virtual {p0, v0}, ...->I(LBusinessFeedData;)Z
 *     if-nez v0, :cond_19 / return-void                      // I() 返回 false 就不点（已赞/不可赞）
 *     iget-object v0, p0, ...->v:Ljava/util/List;            // 点赞处理器链
 *     invoke-interface {v1, v2, v3}, .../d/a;->d(SuperLikeView, BusinessFeedData)Z
 *     ... invoke-virtual {v0, v1}, Landroid/widget/ImageView;->setSelected(Z)V
 *
 *   所以 hook C() 的 after 里调一次 N()，就实现了“看到哪条点哪条”，
 *   而 N() 内部的 I() 判断保证不会把用户手动取消过的赞又点回去。
 */
package cc.ioctl.hook.misc

import cc.ioctl.util.hookAfterIfEnabled
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.classExists
import io.github.qauxv.util.xpcompat.XposedHelpers

@FunctionHookEntry
@UiItemAgentEntry
object AutoLikeQZoneHook : CommonSwitchFunctionHook("timas_auto_like_qzone") {

    private const val CLS_PRESENTER = "com.qzone.reborn.feedx.presenter.bs"

    override val name: String = "空间自动点赞"

    override val description: String =
        "浏览 QQ 空间动态时自动点赞；已点赞或不可点赞的动态由宿主自己判断，不会重复点"

    override val uiItemLocation: Array<String> = FunctionEntryRouter.Locations.Auxiliary.MISC_CATEGORY

    override val isAvailable: Boolean = classExists(CLS_PRESENTER)

    override fun initOnce(): Boolean {
        return try {
            val clz = Initiator.loadClass(CLS_PRESENTER)

            // protected C(Landroid/view/View;)V
            val bindMethod = clz.declaredMethods.firstOrNull { m ->
                m.name == "C" &&
                        m.returnType == Void.TYPE &&
                        m.parameterTypes.size == 1 &&
                        m.parameterTypes[0] == android.view.View::class.java
            } ?: return false

            // private N()V —— 点赞入口
            val likeMethod = clz.declaredMethods.firstOrNull { m ->
                m.name == "N" &&
                        m.returnType == Void.TYPE &&
                        m.parameterTypes.isEmpty()
            } ?: return false
            likeMethod.isAccessible = true

            hookAfterIfEnabled(bindMethod) { param ->
                try {
                    XposedHelpers.callMethod(param.thisObject, likeMethod.name)
                } catch (_: Throwable) {
                    // 点赞失败（网络/权限/已赞）不能影响动态列表渲染
                }
            }
            true
        } catch (t: Throwable) {
            traceError(t)
            false
        }
    }
}
