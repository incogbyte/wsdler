package burp;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import org.reficio.ws.SoapContext;
import org.reficio.ws.builder.SoapBuilder;
import org.reficio.ws.builder.SoapOperation;
import org.reficio.ws.builder.core.Wsdl;
import org.xml.sax.SAXException;

import javax.swing.*;
import javax.wsdl.Binding;
import javax.wsdl.BindingOperation;
import javax.wsdl.Definition;
import javax.wsdl.Import;
import javax.wsdl.Operation;
import javax.wsdl.PortType;
import javax.wsdl.WSDLException;
import javax.xml.namespace.QName;
import javax.xml.parsers.ParserConfigurationException;
import java.awt.*;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class WSDLParser {

    private MontoyaApi api;
    private WSDLParserTab tab;

    public WSDLParser(MontoyaApi api, WSDLParserTab tab) {
        this.api = api;
        this.tab = tab;
    }

    public int parseWSDL(HttpRequestResponse requestResponse) throws ParserConfigurationException, IOException, SAXException, WSDLException, ExecutionException, InterruptedException {
        ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(WSDLParser.class.getClassLoader());
        try {
            return parseWSDLInternal(requestResponse);
        } finally {
            Thread.currentThread().setContextClassLoader(originalCl);
        }
    }

    private int parseWSDLInternal(HttpRequestResponse requestResponse) throws ParserConfigurationException, IOException, SAXException, WSDLException, ExecutionException, InterruptedException {
        api.logging().logToOutput("[Wsdler] Starting parseWSDL...");

        HttpResponse response = requestResponse.response();

        if (response == null) {
            api.logging().logToOutput("[Wsdler] No response, sending request...");
            HttpRequestResponse sent = api.http().sendRequest(requestResponse.request());
            response = sent.response();
        }
        if (response == null) {
            api.logging().logToError("[Wsdler] Response is still null after sending request");
            JOptionPane.showMessageDialog(tab.getUiComponent().getParent(), "Can't Read Response", "Error", JOptionPane.ERROR_MESSAGE);
            return -1;
        }

        String url = requestResponse.request().url();
        api.logging().logToOutput("[Wsdler] URL: " + url);

        String requestName = url.substring(url.lastIndexOf("/") + 1);

        if (requestName.contains(".")) {
            requestName = requestName.substring(0, requestName.indexOf("."));
        }
        if (requestName.contains("?")) {
            requestName = requestName.substring(0, requestName.indexOf("?"));
        }
        api.logging().logToOutput("[Wsdler] Request name: " + requestName);

        Wsdl parser;
        try {
            api.logging().logToOutput("[Wsdler] Parsing WSDL from URL...");
            parser = Wsdl.parse(url);
            api.logging().logToOutput("[Wsdler] WSDL parsed successfully");
            fixBindingPortTypes(parser);
        } catch (Exception e) {
            api.logging().logToError("[Wsdler] Wsdl.parse() failed", e);
            showErrorDialog(e);
            return -3;
        }

        WSDLTab wsdltab = tab.createTab(requestName);
        List<QName> bindings;
        try {
            bindings = getBindingsSafe(parser);
            api.logging().logToOutput("[Wsdler] Found " + bindings.size() + " bindings");
        } catch (Exception e) {
            api.logging().logToError("[Wsdler] getBindings() failed", e);
            showErrorDialog(e);
            return -2;
        }
        SoapBuilder builder;
        List<SoapOperation> operations;
        SoapOperation operation;
        String bindingName;
        String operationName;
        HttpRequest xmlRequest = null;
        List<String> endpoints;
        int entryCount = 0;
        for (QName i : bindings) {
            boolean success = true;
            bindingName = i.getLocalPart();
            try {
                builder = parser.getBuilder(i);
            } catch (StackOverflowError | Exception e) {
                api.logging().logToError("[Wsdler] Could not find builder for binding: " + bindingName, e);
                continue;
            }
            operations = builder.getOperations();
            api.logging().logToOutput("[Wsdler] Binding " + bindingName + " has " + operations.size() + " operations");
            for (SoapOperation j : operations) {
                operationName = j.getOperationName();
                operation = builder.operation().name(operationName).find();
                try {
                    xmlRequest = createRequest(requestResponse, builder, operation);
                } catch (StackOverflowError | Exception e) {
                    api.logging().logToError("[Wsdler] createRequest failed for " + operationName, e);
                    success = false;
                }
                if (success) {
                    try {
                        endpoints = builder.getServiceUrls();
                    } catch (StackOverflowError e) {
                        // Fallback: use the original request URL as endpoint
                        endpoints = new ArrayList<>();
                        endpoints.add(requestResponse.request().url());
                    }
                    wsdltab.addEntry(new WSDLEntry(bindingName, xmlRequest, operationName, endpoints, requestResponse));
                    entryCount++;
                }
            }
        }
        api.logging().logToOutput("[Wsdler] Done. Added " + entryCount + " entries");
        return 0;
    }

    /**
     * Gets bindings safely, handling StackOverflowError from circular WSDL imports
     * (common in WCF .svc services).
     */
    private List<QName> getBindingsSafe(Wsdl wsdl) {
        try {
            return wsdl.getBindings();
        } catch (StackOverflowError e) {
            api.logging().logToOutput("[Wsdler] Circular WSDL imports detected, using fallback binding extraction");
            return getBindingsViaReflection(wsdl);
        }
    }

    private Definition getDefinition(Wsdl wsdl) throws Exception {
        Field facadeField = Wsdl.class.getDeclaredField("soapFacade");
        facadeField.setAccessible(true);
        Object facade = facadeField.get(wsdl);

        Field mbField = facade.getClass().getDeclaredField("messageBuilder");
        mbField.setAccessible(true);
        Object messageBuilder = mbField.get(facade);

        Field defField = messageBuilder.getClass().getDeclaredField("definition");
        defField.setAccessible(true);
        return (Definition) defField.get(messageBuilder);
    }

    private List<QName> getBindingsViaReflection(Wsdl wsdl) {
        try {
            Definition definition = getDefinition(wsdl);
            List<QName> bindings = collectBindings(definition);
            api.logging().logToOutput("[Wsdler] Fallback found " + bindings.size() + " bindings");
            return bindings;
        } catch (Exception ex) {
            api.logging().logToError("[Wsdler] Fallback binding extraction failed", ex);
            return new ArrayList<>();
        }
    }

    /**
     * Fixes broken binding-to-portType linkage in WCF split-WSDL documents.
     * WCF puts bindings and portTypes in separate WSDL documents connected via
     * circular imports. WSDL4J uses local-only lookups (def.getPortType()) that
     * miss cross-document references, leaving BindingOperation.getOperation() null
     * and producing empty SOAP bodies.
     */
    @SuppressWarnings("unchecked")
    private void fixBindingPortTypes(Wsdl parser) {
        try {
            Definition definition = getDefinition(parser);

            Map<QName, PortType> allPortTypes = new HashMap<>();
            collectPortTypesRecursive(definition, new HashSet<>(), allPortTypes);
            api.logging().logToOutput("[Wsdler] Found " + allPortTypes.size() + " port types across imports");

            Map<QName, Binding> allBindings = new HashMap<>();
            collectBindingsWithTypeRecursive(definition, new HashSet<>(), allBindings);

            int fixedBindings = 0;
            int fixedOps = 0;

            for (Map.Entry<QName, Binding> entry : allBindings.entrySet()) {
                Binding binding = entry.getValue();
                PortType pt = binding.getPortType();

                if (pt == null || pt.isUndefined() || pt.getOperations() == null || pt.getOperations().isEmpty()) {
                    QName ptQName = (pt != null) ? pt.getQName() : null;
                    if (ptQName == null) {
                        // Try reflection to get the unresolved QName
                        try {
                            Field f = binding.getClass().getDeclaredField("portTypeName");
                            f.setAccessible(true);
                            ptQName = (QName) f.get(binding);
                        } catch (Exception ignored) {}
                    }
                    if (ptQName != null) {
                        PortType resolved = allPortTypes.get(ptQName);
                        if (resolved != null && !resolved.isUndefined()) {
                            binding.setPortType(resolved);
                            pt = resolved;
                            fixedBindings++;
                        }
                    }
                    if (pt == null || pt.isUndefined()) {
                        // Fallback: match by operation names
                        pt = matchPortTypeByOperations(binding, allPortTypes);
                        if (pt != null) {
                            binding.setPortType(pt);
                            fixedBindings++;
                        }
                    }
                }

                if (pt != null && !pt.isUndefined() && pt.getOperations() != null) {
                    for (Object boObj : binding.getBindingOperations()) {
                        BindingOperation bo = (BindingOperation) boObj;
                        Operation op = bo.getOperation();
                        if (op == null || op.isUndefined() ||
                                op.getInput() == null || op.getInput().getMessage() == null) {
                            Operation resolved = pt.getOperation(bo.getName(), null, null);
                            if (resolved != null && !resolved.isUndefined()) {
                                bo.setOperation(resolved);
                                fixedOps++;
                            }
                        }
                    }
                }
            }

            if (fixedBindings > 0 || fixedOps > 0) {
                api.logging().logToOutput("[Wsdler] Fixed " + fixedBindings +
                        " binding port types and " + fixedOps + " operation linkages");
            }
        } catch (Exception e) {
            api.logging().logToError("[Wsdler] fixBindingPortTypes failed", e);
        }
    }

    private PortType matchPortTypeByOperations(Binding binding, Map<QName, PortType> allPortTypes) {
        Set<String> bindingOpNames = new HashSet<>();
        for (Object bo : binding.getBindingOperations()) {
            bindingOpNames.add(((BindingOperation) bo).getName());
        }
        PortType bestMatch = null;
        int bestScore = 0;
        for (PortType pt : allPortTypes.values()) {
            if (pt.isUndefined() || pt.getOperations() == null) continue;
            int score = 0;
            for (Object opObj : pt.getOperations()) {
                if (bindingOpNames.contains(((Operation) opObj).getName())) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestMatch = pt;
            }
        }
        return bestMatch;
    }

    @SuppressWarnings("unchecked")
    private void collectPortTypesRecursive(Definition def, Set<String> visited, Map<QName, PortType> result) {
        if (def == null) return;
        String uri = def.getDocumentBaseURI();
        if (uri != null && !visited.add(uri)) return;

        Map<QName, ?> local = def.getPortTypes();
        if (local != null) {
            for (Map.Entry<QName, ?> e : local.entrySet()) {
                PortType pt = (PortType) e.getValue();
                if (!pt.isUndefined()) {
                    result.put(e.getKey(), pt);
                }
            }
        }

        Map<String, List<Import>> imports = def.getImports();
        if (imports != null) {
            for (List<Import> importList : imports.values()) {
                for (Import imp : importList) {
                    collectPortTypesRecursive(imp.getDefinition(), visited, result);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void collectBindingsWithTypeRecursive(Definition def, Set<String> visited, Map<QName, Binding> result) {
        if (def == null) return;
        String uri = def.getDocumentBaseURI();
        if (uri != null && !visited.add(uri)) return;

        Map<QName, ?> local = def.getBindings();
        if (local != null) {
            result.putAll((Map<QName, Binding>) local);
        }

        Map<String, List<Import>> imports = def.getImports();
        if (imports != null) {
            for (List<Import> importList : imports.values()) {
                for (Import imp : importList) {
                    collectBindingsWithTypeRecursive(imp.getDefinition(), visited, result);
                }
            }
        }
    }

    /**
     * Collects bindings from a WSDL Definition with circular import detection.
     */
    @SuppressWarnings("unchecked")
    private List<QName> collectBindings(Definition def) {
        Set<String> visited = new HashSet<>();
        Map<QName, Object> allBindings = new HashMap<>();
        collectBindingsRecursive(def, visited, allBindings);
        return new ArrayList<>(allBindings.keySet());
    }

    @SuppressWarnings("unchecked")
    private void collectBindingsRecursive(Definition def, Set<String> visited, Map<QName, Object> result) {
        if (def == null) return;
        String uri = def.getDocumentBaseURI();
        if (uri != null && !visited.add(uri)) return; // cycle detected

        // Local bindings (non-recursive)
        Map<QName, ?> localBindings = def.getBindings();
        if (localBindings != null) {
            result.putAll((Map<QName, Object>) localBindings);
        }

        // Process imports with cycle detection
        Map<String, List<Import>> imports = def.getImports();
        if (imports != null) {
            for (List<Import> importList : imports.values()) {
                for (Import imp : importList) {
                    collectBindingsRecursive(imp.getDefinition(), visited, result);
                }
            }
        }
    }

    private HttpRequest createRequest(HttpRequestResponse requestResponse, SoapBuilder builder, SoapOperation operation) {
        SoapContext context = SoapContext.builder()
                .alwaysBuildHeaders(true).exampleContent(true).typeComments(true).buildOptional(true).build();
        String message = builder.buildInputMessage(operation, context);
        String serviceUrl;
        try {
            serviceUrl = builder.getServiceUrls().get(0);
        } catch (StackOverflowError e) {
            serviceUrl = requestResponse.request().url();
        }
        String host = getHost(serviceUrl);
        String endpointURL = getEndPoint(serviceUrl, host);

        List<String> headerLines = new ArrayList<>();
        headerLines.add("POST " + endpointURL + " HTTP/1.1");

        for (HttpHeader header : requestResponse.request().headers()) {
            String name = header.name();
            if (!name.equalsIgnoreCase("Host") && !name.equalsIgnoreCase("Content-Type")) {
                headerLines.add(header.name() + ": " + header.value());
            }
        }
        headerLines.add("SOAPAction: " + operation.getSoapAction());
        String contentType = message.contains("http://www.w3.org/2003/05/soap-envelope")
                ? "application/soap+xml;charset=UTF-8" : "text/xml;charset=UTF-8";
        headerLines.add("Content-Type: " + contentType);
        headerLines.add("Host: " + host);

        StringBuilder sb = new StringBuilder();
        for (String line : headerLines) {
            sb.append(line).append("\r\n");
        }
        sb.append("\r\n");
        sb.append(message);

        boolean useHttps = serviceUrl.startsWith("https://");
        String hostOnly = host;
        int port = useHttps ? 443 : 80;
        if (hostOnly.contains(":")) {
            port = Integer.parseInt(hostOnly.substring(hostOnly.indexOf(":") + 1));
            hostOnly = hostOnly.substring(0, hostOnly.indexOf(":"));
        }

        HttpService service = HttpService.httpService(hostOnly, port, useHttps);
        return HttpRequest.httpRequest(service, ByteArray.byteArray(sb.toString()));
    }

    private void showErrorDialog(Throwable e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getMessage());
        sb.append("\n");
        for (StackTraceElement ste : e.getStackTrace()) {
            sb.append(ste.toString());
            sb.append("\n");
        }
        JTextArea jta = new JTextArea(sb.toString());
        jta.setWrapStyleWord(true);
        jta.setLineWrap(true);
        jta.setEditable(false);
        JScrollPane jsp = new JScrollPane(jta, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(480, 320);
            }
        };
        JOptionPane.showMessageDialog(
                tab.getUiComponent().getParent(), jsp, "Error", JOptionPane.ERROR_MESSAGE);
    }

    private String getHost(String endpoint) {
        String host;

        if (endpoint.contains("https://")) {
            endpoint = endpoint.replace("https://", "");
        } else {
            endpoint = endpoint.replace("http://", "");
        }

        int index = endpoint.indexOf("/");
        host = endpoint.substring(0, index);

        return host;
    }

    private String getEndPoint(String endpoint, String host) {

        if (endpoint.contains("https://")) {
            endpoint = endpoint.replace("https://", "");
        } else {
            endpoint = endpoint.replace("http://", "");
        }

        endpoint = endpoint.replace(host, "");

        return endpoint;
    }
}
