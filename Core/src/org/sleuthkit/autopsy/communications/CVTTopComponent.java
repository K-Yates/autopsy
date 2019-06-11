/*
 * Autopsy Forensic Browser
 *
 * Copyright 2017-2018 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.communications;

import com.google.common.eventbus.Subscribe;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.ImageIcon;
import javax.swing.JTabbedPane;
import org.openide.util.Lookup;
import org.openide.util.NbBundle;
import org.openide.windows.Mode;
import org.openide.windows.RetainLocation;
import org.openide.windows.TopComponent;
import org.openide.windows.WindowManager;
import org.sleuthkit.autopsy.coreutils.ThreadConfined;

/**
 * Top component which displays the Communications Visualization Tool.
 */
@TopComponent.Description(preferredID = "CVTTopComponent", persistenceType = TopComponent.PERSISTENCE_NEVER)
@TopComponent.Registration(mode = "cvt", openAtStartup = false)
@RetainLocation("cvt")
@NbBundle.Messages("CVTTopComponent.name= Communications Visualization")
@SuppressWarnings("PMD.SingularField") // UI widgets cause lots of false positives
public final class CVTTopComponent extends TopComponent {

    private static final long serialVersionUID = 1L;

    @ThreadConfined(type = ThreadConfined.ThreadType.AWT)
    public CVTTopComponent() {
        initComponents();
        setName(Bundle.CVTTopComponent_name());

        /*
         * Associate a Lookup with the GlobalActionContext (GAC) so that
         * selections in the sub views can be exposed to context-sensitive
         * actions.
         */
        final ModifiableProxyLookup proxyLookup = new ModifiableProxyLookup(accountsBrowser.getLookup());
        associateLookup(proxyLookup);
        // Make sure the Global Actions Context is proxying the selection of the active tab.
        browseVisualizeTabPane.addChangeListener(changeEvent -> {
            Component selectedComponent = browseVisualizeTabPane.getSelectedComponent();
            if(selectedComponent instanceof Lookup.Provider) {
                Lookup lookup = ((Lookup.Provider)selectedComponent).getLookup();
                proxyLookup.setNewLookups(lookup);
            }
            filtersPane.setDeviceAccountTypeEnabled(browseVisualizeTabPane.getSelectedIndex() != 0);
        });
        
        
        /*
         * Connect the filtersPane to the accountsBrowser and visualizaionPanel
         * via an Eventbus
         */
        CVTEvents.getCVTEventBus().register(this);
        CVTEvents.getCVTEventBus().register(vizPanel);
        CVTEvents.getCVTEventBus().register(accountsBrowser);
        CVTEvents.getCVTEventBus().register(filtersPane);
    }

    @Subscribe
    void pinAccount(CVTEvents.PinAccountsEvent pinEvent) {
        browseVisualizeTabPane.setSelectedIndex(1);
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {
        GridBagConstraints gridBagConstraints;

        browseVisualizeTabPane = new JTabbedPane();
        accountsBrowser = new AccountsBrowser();
        vizPanel = new VisualizationPanel();
        filtersPane = new FiltersPanel();

        setLayout(new GridBagLayout());

        browseVisualizeTabPane.setFont(new Font("Tahoma", 0, 18)); // NOI18N
        browseVisualizeTabPane.addTab(NbBundle.getMessage(CVTTopComponent.class, "CVTTopComponent.accountsBrowser.TabConstraints.tabTitle_1"), new ImageIcon(getClass().getResource("/org/sleuthkit/autopsy/communications/images/table.png")), accountsBrowser); // NOI18N
        browseVisualizeTabPane.addTab(NbBundle.getMessage(CVTTopComponent.class, "CVTTopComponent.vizPanel.TabConstraints.tabTitle_1"), new ImageIcon(getClass().getResource("/org/sleuthkit/autopsy/communications/images/emblem-web.png")), vizPanel); // NOI18N

        gridBagConstraints = new GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.fill = GridBagConstraints.BOTH;
        gridBagConstraints.anchor = GridBagConstraints.NORTHWEST;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 1.0;
        gridBagConstraints.insets = new Insets(15, 0, 15, 15);
        add(browseVisualizeTabPane, gridBagConstraints);
        browseVisualizeTabPane.getAccessibleContext().setAccessibleName(NbBundle.getMessage(CVTTopComponent.class, "CVTTopComponent.browseVisualizeTabPane.AccessibleContext.accessibleName")); // NOI18N

        filtersPane.setMinimumSize(new Dimension(256, 495));
        gridBagConstraints = new GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.fill = GridBagConstraints.BOTH;
        gridBagConstraints.anchor = GridBagConstraints.NORTHWEST;
        gridBagConstraints.weighty = 1.0;
        gridBagConstraints.insets = new Insets(15, 15, 15, 5);
        add(filtersPane, gridBagConstraints);
    }// </editor-fold>//GEN-END:initComponents


    // Variables declaration - do not modify//GEN-BEGIN:variables
    private AccountsBrowser accountsBrowser;
    private JTabbedPane browseVisualizeTabPane;
    private FiltersPanel filtersPane;
    private VisualizationPanel vizPanel;
    // End of variables declaration//GEN-END:variables

    @Override
    public void componentOpened() {
        super.componentOpened();
        WindowManager.getDefault().setTopComponentFloating(this, true);
    }

    @Override
    public void open() {
        super.open();
        /*
         * when the window is (re)opened make sure the filters and accounts are
         * in an up to date and consistent state.
         *
         * Re-applying the filters means we will lose the selection...
         */
        filtersPane.updateAndApplyFilters(true);
    }

    @Override
    public List<Mode> availableModes(List<Mode> modes) {
        /*
         * This looks like the right thing to do, but online discussions seems
         * to indicate this method is effectively deprecated. A break point
         * placed here was never hit.
         */
        return modes.stream().filter(mode -> mode.getName().equals("cvt"))
                .collect(Collectors.toList());
    }
}
