package com.example.springbootrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Guardrails: what is refused at ingest, and what is checked before an answer ships. */
@ConfigurationProperties(prefix = "app.guard")
public class GuardProperties {

    private final Quarantine quarantine = new Quarantine();
    private final Groundedness groundedness = new Groundedness();

    public Quarantine getQuarantine() { return quarantine; }
    public Groundedness getGroundedness() { return groundedness; }

    public static class Quarantine {
        /**
         * On by default. A control that ships off is a control nobody has. The flag exists so a
         * deliberate bulk import of a corpus known to contain credential-shaped text can be run,
         * not so the feature can be quietly skipped.
         */
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Groundedness {
        /**
         * OFF by default, on purpose. Refusing a good answer is a worse product failure than the
         * leak this check addresses, because it happens on every ordinary question rather than on
         * an attack, and the false-refusal rate has not been measured yet. The default flips only
         * when a number earns it - the same pattern as {@code app.rerank.provider}.
         */
        private boolean enabled = false;
        /** Empty means "use app.chat.model" - a separate knob so the check can use a smaller model. */
        private String model = "";
        /** Fixed sampling: a verdict that changes between two identical asks is not a control. */
        private int seed = 42;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getSeed() { return seed; }
        public void setSeed(int seed) { this.seed = seed; }
    }
}
