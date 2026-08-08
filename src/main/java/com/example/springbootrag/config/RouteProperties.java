package com.example.springbootrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Query routing: deciding which path answers a question. */
@ConfigurationProperties(prefix = "app.route")
public class RouteProperties {

    /** Off restores exactly the pre-feature behaviour: every question takes the RAG path. */
    private boolean enabled = true;
    /** Empty means "use app.chat.model" - a separate knob so routing can use a smaller model. */
    private String model = "";
    /** Hard cap on router output. The answer is one word; anything longer is leaked reasoning. */
    private int numPredict = 32;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getNumPredict() { return numPredict; }
    public void setNumPredict(int numPredict) { this.numPredict = numPredict; }
}
