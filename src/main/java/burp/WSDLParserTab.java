package burp;

import burp.api.montoya.MontoyaApi;

import java.awt.*;
import javax.swing.*;

public class WSDLParserTab {

    JTabbedPane tabs;
    private MontoyaApi api;
    static int tabCount = 0;
    static int removedTabCount = 0;

    public WSDLParserTab(MontoyaApi api) {
        this.api = api;
        tabs = new JTabbedPane();
    }

    public WSDLTab createTab(String request) {
        WSDLTab wsdltab = new WSDLTab(api, tabs, request);
        tabs.setSelectedIndex(tabCount - removedTabCount);
        tabCount++;
        return wsdltab;
    }

    public Component getUiComponent() {
        return tabs;
    }
}
