package com.example.springbootrag.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecordRendererProfileTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final RecordRenderer renderer = new RecordRenderer();

    @Test
    void excludedPathNeverReachesTextOrValues() throws Exception {
        RenderProfile p = RenderProfile.parse("""
                {"exclude":["rawOcrText"]}""");

        List<RenderedBlock> blocks = renderer.render(M.readTree("""
                {"id":"X","rawOcrText":"noisy dump"}"""), p);

        assertThat(blocks.get(0).text()).doesNotContain("noisy dump");
        assertThat(blocks.get(0).values()).doesNotContainKey("rawOcrText");
    }

    @Test
    void filterOnlyPathIsInValuesButNotInText() throws Exception {
        RenderProfile p = RenderProfile.parse("""
                {"filterOnly":["batchId"]}""");

        List<RenderedBlock> blocks = renderer.render(M.readTree("""
                {"id":"X","batchId":"B-77"}"""), p);

        assertThat(blocks.get(0).text()).doesNotContain("B-77");
        assertThat(blocks.get(0).values()).containsEntry("batchId", "B-77");
    }

    @Test
    void profileLabelIsUsedInTheRenderedLine() throws Exception {
        RenderProfile p = RenderProfile.parse("""
                {"labels":{"issueDate":"Invoice date"}}""");

        List<RenderedBlock> blocks = renderer.render(M.readTree("""
                {"issueDate":"2026-05-02"}"""), p);

        assertThat(blocks.get(0).text()).contains("Invoice date: 2026-05-02");
    }

    @Test
    void profileWrapperKeysUnwrapATenantSpecificShape() throws Exception {
        RenderProfile p = RenderProfile.parse("""
                {"wrapper":{"valueKeys":["val"],"confidenceKeys":["certainty"],"groundingKeys":[]}}""");

        List<RenderedBlock> blocks = renderer.render(M.readTree("""
                {"customer":{"val":"ACME","certainty":0.9}}"""), p);

        assertThat(blocks.get(0).text()).contains("ACME").doesNotContain("0.9");
    }
}
