package burp;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class Menu implements ContextMenuItemsProvider {
    private MontoyaApi api;
    private WSDLParserTab tab;
    public static Timer timer;

    public Menu(MontoyaApi api, WSDLParserTab tab) {
        this.api = api;
        this.tab = tab;
        timer = new Timer();
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        List<Component> list = new ArrayList<>();

        HttpRequestResponse requestResponse = null;
        if (event.messageEditorRequestResponse().isPresent()) {
            requestResponse = event.messageEditorRequestResponse().get().requestResponse();
        } else if (!event.selectedRequestResponses().isEmpty()) {
            requestResponse = event.selectedRequestResponses().get(0);
        }

        if (requestResponse == null) {
            return list;
        }

        final HttpRequestResponse finalRequestResponse = requestResponse;
        JMenuItem item = new JMenuItem("Parse WSDL");
        item.addActionListener(e -> {
            WSDLParser parser = new WSDLParser(api, tab);
            try {
                new Worker(parser, finalRequestResponse, tab, api).execute();
            } catch (Exception e1) {
                api.logging().logToError("Failed to start WSDL parsing", e1);
            }
        });
        list.add(item);

        return list;
    }
}

class Worker extends SwingWorker<Void, Void> {

    private JDialog dialog = new JDialog();
    private WSDLParser parser;
    private HttpRequestResponse requestResponse;
    private WSDLParserTab tab;
    private MontoyaApi api;
    private int status;

    public Worker(WSDLParser parser, HttpRequestResponse requestResponse, WSDLParserTab tab, MontoyaApi api) {
        JProgressBar progressBar = new JProgressBar();
        progressBar.setString("Parsing WSDL");
        progressBar.setStringPainted(true);
        progressBar.setIndeterminate(true);
        dialog.getContentPane().add(progressBar);
        dialog.pack();
        dialog.setLocationRelativeTo(tab.getUiComponent().getParent());
        dialog.setModal(false);
        dialog.setVisible(true);
        this.parser = parser;
        this.requestResponse = requestResponse;
        this.tab = tab;
        this.api = api;
    }

    @Override
    protected Void doInBackground() throws Exception {
        status = parser.parseWSDL(requestResponse);
        return null;
    }

    @Override
    protected void done() {
        dialog.dispose();
        try {
            get();
        } catch (Exception e) {
            api.logging().logToError("WSDL parsing failed", e);
            return;
        }
        if (status != -1 && status != -2 && status != -3) {
            try {
                final JTabbedPane parent = (JTabbedPane) tab.getUiComponent().getParent();
                final int index = parent.indexOfComponent(tab.getUiComponent());
                parent.setBackgroundAt(index, new Color(229, 137, 1));

                Menu.timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        parent.setBackgroundAt(index, new Color(0, 0, 0));
                    }
                }, 5000);
            } catch (Exception e) {
                api.logging().logToError("Tab highlight failed", e);
            }
        }
    }
}
