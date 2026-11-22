package de.tubyoub.vpp.api;

import java.util.List;
import java.util.Map;

public interface PanelHttpResponse {
    int statusCode();
    String body();
    Map<String, List<String>> headers();
}
