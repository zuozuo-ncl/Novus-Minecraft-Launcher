package com.mclauncher.ui.page;

import javafx.scene.layout.Region;

/**
 * 页面接口
 *
 * 启动器的主内容区由多个页面组成，侧边栏点击时整体切换。
 * 每个页面自行构建 UI，并在被显示时刷新数据。
 */
public interface Page {

    /** 页面标题（用于顶部显示） */
    String getTitle();

    /** 构建页面内容（只调用一次，之后复用） */
    Region getContent();

    /**
     * 页面被显示时调用
     * 用于刷新列表等懒加载操作
     */
    default void onShow() {}

    /**
     * 页面离开时调用
     * 用于停止后台任务、释放资源
     */
    default void onHide() {}

    /**
     * 注入内嵌弹窗宿主
     *
     * 默认空实现，需要弹窗的页面自行覆盖。
     * 这样 MainWindow 可以对所有页面统一调用，不必逐页面判断类型。
     */
    default void setModalHost(com.mclauncher.ui.component.ModalHost host) {}
}
