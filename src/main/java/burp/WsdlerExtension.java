package burp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

public class WsdlerExtension implements BurpExtension {

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("Wsdler");

        WSDLParserTab tab = new WSDLParserTab(api);
        api.userInterface().registerSuiteTab("Wsdler", tab.getUiComponent());
        api.userInterface().registerContextMenuItemsProvider(new Menu(api, tab));
    }
}
