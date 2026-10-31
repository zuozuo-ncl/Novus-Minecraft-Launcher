package com.mclauncher.ui.page;

import javafx.scene.layout.Region;

public interface Page {

    String getTitle();

    Region getContent();

    default void onShow() {}

    default void onHide() {}

    default void setModalHost(com.mclauncher.ui.component.ModalHost host) {}
}
