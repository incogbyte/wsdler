package burp;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;

import java.util.List;

public class WSDLEntry {

    final String bindingName;
    final HttpRequest request;
    final String operationName;
    final HttpRequestResponse requestResponse;
    final List<String> endpoints;

    WSDLEntry(String bindingName, HttpRequest request, String operationName, List<String> endpoints, HttpRequestResponse requestResponse) {
        this.bindingName = bindingName;
        this.request = request;
        this.operationName = operationName;
        this.endpoints = endpoints;
        this.requestResponse = requestResponse;
    }
}
