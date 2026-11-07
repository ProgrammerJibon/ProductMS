package com.productms.controller;

import javax.swing.JComponent;

/** A screen in the main window (Dashboard, POS, Products...). */
public interface Module {

    /** The view shown in the main window. */
    JComponent view();

    /** Called every time the module becomes visible, to reload data. */
    void onShow() throws Exception;
}
