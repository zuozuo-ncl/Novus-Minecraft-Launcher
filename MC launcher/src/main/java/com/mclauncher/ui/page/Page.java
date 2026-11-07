package com.mclauncher.ui.page;

import javafx.scene.layout.Region;

/** 页面接口 */
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

    /** 注入内嵌弹窗宿主 */
    default void setModalHost(com.mclauncher.ui.component.ModalHost host) {}

    /** 启动器退出时调用：释放页面持有的线程池、连接池等资源。 */
    default void shutdown() {}
}
