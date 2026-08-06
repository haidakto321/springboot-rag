package com.example.springbootrag.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecordRendererTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final RecordRenderer renderer = new RecordRenderer();

    private List<RenderedBlock> render(String json) throws Exception {
        return renderer.render(M.readTree(json), null);
    }

    @Test
    void topLevelScalarsBecomeOneHeaderBlock() throws Exception {
        List<RenderedBlock> blocks = render("""
                {"invoiceNumber":"INV-5575","issueDate":"2026-05-02","total":1899.5}""");

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).breadcrumb()).isEmpty();
        assertThat(blocks.get(0).text())
                .contains("Invoice number: INV-5575")
                .contains("Issue date: 2026-05-02")
                .contains("Total: 1899.5");
    }

    @Test
    void nestedObjectBecomesItsOwnBlock() throws Exception {
        List<RenderedBlock> blocks = render("""
                {"id":"INV-1","customer":{"name":"ACME","city":"Berlin"}}""");

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(1).breadcrumb()).isEqualTo("customer");
        assertThat(blocks.get(1).text()).contains("Name: ACME").contains("City: Berlin");
    }

    @Test
    void eachArrayElementIsItsOwnBlockWithABreadcrumb() throws Exception {
        List<RenderedBlock> blocks = render("""
                {"id":"INV-1",
                 "lineItems":[{"sku":"A-1","description":"Widget"},
                              {"sku":"B-2","description":"Gadget"}]}""");

        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(1).breadcrumb()).isEqualTo("lineItems[0]");
        assertThat(blocks.get(1).text()).contains("Widget");
        assertThat(blocks.get(2).breadcrumb()).isEqualTo("lineItems[1]");
        assertThat(blocks.get(2).text()).contains("Gadget");
    }

    @Test
    void arrayElementBlockCarriesParentScalarContext() throws Exception {
        List<RenderedBlock> blocks = render("""
                {"invoiceNumber":"INV-1","lineItems":[{"sku":"A-1"}]}""");

        // An element chunk alone ("SKU: A-1") is unanswerable without knowing whose invoice it is.
        assertThat(blocks.get(1).text()).contains("INV-1");
    }

    @Test
    void arrayOfScalarsStaysOneLineInsideItsOwningBlock() throws Exception {
        List<RenderedBlock> blocks = render("""
                {"id":"X","tags":["urgent","paid"]}""");

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).text()).contains("Tags: urgent, paid");
    }

    @Test
    void nullAndEmptyValuesAreOmitted() throws Exception {
        List<RenderedBlock> blocks = render("""
                {"id":"X","note":null,"comment":"","ok":"yes"}""");

        assertThat(blocks.get(0).text()).doesNotContain("Note").doesNotContain("Comment");
        assertThat(blocks.get(0).text()).contains("Ok: yes");
    }

    @Test
    void wrappedValuesRenderTheValueAndNeverTheProvenance() throws Exception {
        List<RenderedBlock> blocks = render("""
                {"customer":{"value":"ACME Corp","confidence":0.82,
                             "grounding":{"page":2,"bbox":[12,44,90,60]}}}""");

        String allText = blocks.stream().map(RenderedBlock::text).reduce("", String::concat);
        assertThat(allText).contains("ACME Corp");
        assertThat(allText).doesNotContain("0.82").doesNotContain("12").doesNotContain("bbox");
    }

    @Test
    void provenanceLandsInTheProvMapUnderTheFieldPath() throws Exception {
        List<RenderedBlock> blocks = render("""
                {"customer":{"value":"ACME","confidence":0.82,"grounding":{"page":2}}}""");

        assertThat(blocks.get(0).prov()).containsKey("customer");
        assertThat(blocks.get(0).values()).containsEntry("customer", "ACME");
    }

    @Test
    void nestedWrapperInsideAnArrayElementUnwraps() throws Exception {
        List<RenderedBlock> blocks = render("""
                {"lineItems":[{"sku":{"value":"A-1","confidence":0.4}}]}""");

        assertThat(blocks.get(0).text()).contains("A-1").doesNotContain("0.4");
        assertThat(nested(blocks.get(0).prov(), "lineItems", "sku")).isNotNull();
    }

    @Test
    void metadataIsKeyedByPathNotByLeafName() throws Exception {
        // Filters address full paths (values.lineItems[].sku). Storing leaf names would make
        // every such filter match nothing - which is exactly how this was first found, live.
        List<RenderedBlock> blocks = render("""
                {"invoiceNumber":"INV-1",
                 "customer":{"name":"ACME","city":"Berlin"},
                 "lineItems":[{"sku":"A-1"}]}""");

        RenderedBlock section = blocks.stream()
                .filter(b -> b.breadcrumb().equals("customer")).findFirst().orElseThrow();
        assertThat(nested(section.values(), "customer", "name")).isEqualTo("ACME");

        RenderedBlock element = blocks.stream()
                .filter(b -> b.breadcrumb().equals("lineItems[0]")).findFirst().orElseThrow();
        assertThat(nested(element.values(), "lineItems", "sku")).isEqualTo("A-1");
    }

    @Test
    void nonHeaderBlocksInheritRecordLevelScalars() throws Exception {
        // So "ACME invoices whose line item is B-2" can be answered by the line-item chunk.
        List<RenderedBlock> blocks = render("""
                {"invoiceNumber":"INV-1","lineItems":[{"sku":"A-1"}]}""");

        RenderedBlock element = blocks.stream()
                .filter(b -> b.breadcrumb().equals("lineItems[0]")).findFirst().orElseThrow();
        assertThat(element.values()).containsEntry("invoiceNumber", "INV-1");
    }

    @Test
    void deeplyNestedPathsKeepEveryLevel() throws Exception {
        List<RenderedBlock> blocks = render("""
                {"parties":{"buyer":{"contact":{"email":"a@b.c"}}}}""");

        RenderedBlock section = blocks.get(0);
        Object contact = nested(section.values(), "parties", "buyer");
        assertThat(contact).isInstanceOf(java.util.Map.class);
        assertThat(((java.util.Map<?, ?>) ((java.util.Map<?, ?>) contact).get("contact")).get("email"))
                .isEqualTo("a@b.c");
    }

    private static Object nested(java.util.Map<String, Object> map, String... path) {
        Object cur = map;
        for (String p : path) {
            if (!(cur instanceof java.util.Map<?, ?> m)) return null;
            cur = m.get(p);
        }
        return cur;
    }

    @Test
    void labelSplitsCamelCaseAndSnakeCase() {
        assertThat(RecordRenderer.label("issueDate")).isEqualTo("Issue date");
        assertThat(RecordRenderer.label("invoice_number")).isEqualTo("Invoice number");
        assertThat(RecordRenderer.label("total")).isEqualTo("Total");
    }

    @Test
    void emptyRecordRendersNoBlocks() throws Exception {
        assertThat(render("{}")).isEmpty();
    }
}
